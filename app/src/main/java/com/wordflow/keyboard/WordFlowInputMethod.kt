package com.wordflow.keyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.util.TypedValue

/**
 * MVP input method for inserting locally queued text one token at a time.
 * Uses Android's standard InputConnection API and does not bypass site restrictions.
 */
class WordFlowInputMethod : InputMethodService() {

    private val tokens = mutableListOf<String>()
    private var nextIndex = 0

    private var statusView: TextView? = null
    private var previewView: TextView? = null
    private var nextButton: Button? = null
    private var skipButton: Button? = null

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.rgb(239, 243, 249))
        }

        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        root.addView(statusView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        previewView = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.rgb(25, 45, 75))
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        root.addView(previewView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ))

        root.addView(makeButton("Load Clipboard") { loadClipboard() },
            fullWidthButtonParams())

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        skipButton = makeButton("Skip") {
            if (nextIndex < tokens.size) nextIndex++
            updateUi()
        }
        nextButton = makeButton("Next Word") { insertNextToken() }
        val resetButton = makeButton("Reset") {
            nextIndex = 0
            updateUi()
        }

        controls.addView(skipButton, buttonParams())
        controls.addView(nextButton, buttonParams())
        controls.addView(resetButton, buttonParams())
        root.addView(controls)

        updateUi()
        return root
    }

    /** Reads clipboard text only after the user taps Load Clipboard. */
    private fun loadClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData? = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        } else ""

        if (text.isBlank()) {
            tokens.clear()
            nextIndex = 0
            updateUi("Clipboard is empty or has no text")
            return
        }

        tokens.clear()
        // Keep each word with trailing spaces/newlines and punctuation.
        val leadingWhitespace = text.takeWhile { it.isWhitespace() }
        val extracted = Regex("""\S+\s*""")
            .findAll(text)
            .map { it.value }
            .toList()

        tokens.addAll(extracted)
        if (tokens.isNotEmpty() && leadingWhitespace.isNotEmpty()) {
            tokens[0] = leadingWhitespace + tokens[0]
        }

        nextIndex = 0
        updateUi("Loaded ${tokens.size} word tokens")
    }

    private fun insertNextToken() {
        if (nextIndex >= tokens.size) {
            updateUi("All tokens processed")
            return
        }

        val connection: InputConnection? = currentInputConnection
        if (connection == null) {
            updateUi("No focused text field")
            return
        }

        val accepted = connection.commitText(tokens[nextIndex], 1)
        if (accepted) {
            nextIndex++
            updateUi()
        } else {
            updateUi("Editor did not accept input")
        }
    }

    private fun updateUi(message: String? = null) {
        statusView?.text = message ?: when {
            tokens.isEmpty() -> "Copy text, focus a field, then load clipboard"
            nextIndex >= tokens.size -> "Complete • ${tokens.size} tokens"
            else -> "Token ${nextIndex + 1} of ${tokens.size}"
        }

        previewView?.text = when {
            tokens.isEmpty() -> "WordFlow"
            nextIndex >= tokens.size -> "Done"
            else -> tokens[nextIndex].replace("\n", "↵").ifBlank { "Whitespace" }
        }

        nextButton?.isEnabled = tokens.isNotEmpty() && nextIndex < tokens.size
        skipButton?.isEnabled = tokens.isNotEmpty() && nextIndex < tokens.size
    }

    private fun makeButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener { action() }
        }

    private fun buttonParams() =
        LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            setMargins(dp(2), dp(4), dp(2), dp(4))
        }

    private fun fullWidthButtonParams() =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
        ).apply {
            setMargins(dp(2), dp(4), dp(2), dp(4))
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
