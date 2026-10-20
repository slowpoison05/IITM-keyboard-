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
 * WordFlow's local text-entry queue.
 * Uses Android's standard InputConnection API; it does not synthesize hardware key events
 * or bypass website/editor restrictions.
 */
class WordFlowInputMethod : InputMethodService() {

    private val tokens = mutableListOf<String>()
    private var nextIndex = 0
    private var clipboardText = ""
    // UTF-16 offset; advances by a complete Unicode code point.
    private var nextCharOffset = 0
    private var characterMode = false

    private var statusView: TextView? = null
    private var previewView: TextView? = null
    private var nextButton: Button? = null
    private var skipButton: Button? = null
    private var modeButton: Button? = null

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

        modeButton = makeButton("Mode: Word") {
            characterMode = !characterMode
            updateUi(if (characterMode) "Character-by-character mode" else "Word mode")
        }
        root.addView(modeButton, fullWidthButtonParams())

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        skipButton = makeButton("Skip") {
            if (characterMode) {
                if (nextCharOffset < clipboardText.length) {
                    val cp = clipboardText.codePointAt(nextCharOffset)
                    nextCharOffset += Character.charCount(cp)
                }
            } else if (nextIndex < tokens.size) {
                nextIndex++
            }
            updateUi()
        }
        nextButton = makeButton("Next Word") {
            if (characterMode) insertNextCharacter() else insertNextToken()
        }
        val resetButton = makeButton("Reset") {
            nextIndex = 0
            nextCharOffset = 0
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

        if (text.isEmpty()) {
            tokens.clear()
            clipboardText = ""
            nextIndex = 0
            nextCharOffset = 0
            updateUi("Clipboard is empty or has no text")
            return
        }

        clipboardText = text
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
        nextCharOffset = 0
        updateUi("Loaded ${tokens.size} word tokens and ${text.codePointCount(0, text.length)} characters")
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

    /** Inserts one Unicode code point per explicit user tap through the normal IME API. */
    private fun insertNextCharacter() {
        if (nextCharOffset >= clipboardText.length) {
            updateUi("All characters processed")
            return
        }

        val connection: InputConnection? = currentInputConnection
        if (connection == null) {
            updateUi("No focused text field")
            return
        }

        val codePoint = clipboardText.codePointAt(nextCharOffset)
        val charCount = Character.charCount(codePoint)
        val character = clipboardText.substring(nextCharOffset, nextCharOffset + charCount)
        val accepted = connection.commitText(character, 1)
        if (accepted) {
            nextCharOffset += charCount
            updateUi()
        } else {
            updateUi("Editor did not accept input")
        }
    }

    private fun updateUi(message: String? = null) {
        val totalChars = clipboardText.codePointCount(0, clipboardText.length)
        val doneChars = if (clipboardText.isEmpty()) 0 else
            clipboardText.codePointCount(0, nextCharOffset.coerceAtMost(clipboardText.length))

        statusView?.text = message ?: if (characterMode) {
            when {
                clipboardText.isEmpty() -> "Copy text, focus a field, then load clipboard"
                nextCharOffset >= clipboardText.length -> "Complete • ${totalChars} characters"
                else -> "Character ${doneChars + 1} of ${totalChars}"
            }
        } else {
            when {
                tokens.isEmpty() -> "Copy text, focus a field, then load clipboard"
                nextIndex >= tokens.size -> "Complete • ${tokens.size} tokens"
                else -> "Token ${nextIndex + 1} of ${tokens.size}"
            }
        }

        previewView?.text = if (characterMode) {
            when {
                clipboardText.isEmpty() -> "WordFlow"
                nextCharOffset >= clipboardText.length -> "Done"
                else -> {
                    val cp = clipboardText.codePointAt(nextCharOffset)
                    val ch = String(Character.toChars(cp))
                    when (ch) {
                        "\n" -> "↵ (newline)"
                        "\r" -> "↵ (carriage return)"
                        "\t" -> "⇥ (tab)"
                        " " -> "␠ (space)"
                        else -> ch
                    }
                }
            }
        } else {
            when {
                tokens.isEmpty() -> "WordFlow"
                nextIndex >= tokens.size -> "Done"
                else -> tokens[nextIndex].replace("\n", "↵").ifBlank { "Whitespace" }
            }
        }

        modeButton?.text = if (characterMode) "Mode: Character" else "Mode: Word"
        nextButton?.text = if (characterMode) "Next Char" else "Next Word"
        nextButton?.isEnabled = if (characterMode) {
            clipboardText.isNotEmpty() && nextCharOffset < clipboardText.length
        } else {
            tokens.isNotEmpty() && nextIndex < tokens.size
        }
        skipButton?.isEnabled = if (characterMode) {
            clipboardText.isNotEmpty() && nextCharOffset < clipboardText.length
        } else {
            tokens.isNotEmpty() && nextIndex < tokens.size
        }
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
