package com.wordflow.keyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.util.TypedValue

/**
 * WordFlow is a regular QWERTY input method with optional manual text queue controls.
 * All typing uses Android's standard InputConnection API.
 */
class WordFlowInputMethod : InputMethodService() {

    private val tokens = mutableListOf<String>()
    private var nextIndex = 0
    private var clipboardText = ""
    private var nextCharOffset = 0
    private var characterMode = false
    private var shifted = false
    private var symbolsMode = false
    private val autoWriteHandler = Handler(Looper.getMainLooper())
    private var autoWriteRunnable: Runnable? = null
    private var autoWriteConnection: InputConnection? = null
    private var speedLevel = 0 // 0 = Fast, 1 = Normal, 2 = Slow

    private var statusView: TextView? = null
    private var previewView: TextView? = null
    private var nextButton: Button? = null
    private var skipButton: Button? = null
    private var modeButton: Button? = null
    private var shiftButton: Button? = null
    private var symbolButton: Button? = null
    private var writeAllButton: Button? = null
    private var speedButton: Button? = null

    private data class KeySpec(
        val label: String,
        val weight: Float = 1f,
        val action: () -> Unit
    )

    override fun onFinishInput() {
        if (autoWriteRunnable != null) stopAutoWrite("Auto-write stopped")
        super.onFinishInput()
    }

    override fun onDestroy() {
        autoWriteRunnable?.let { autoWriteHandler.removeCallbacks(it) }
        autoWriteRunnable = null
        autoWriteConnection = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(Color.rgb(225, 231, 240))
        }

        statusView = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            maxLines = 1
        }
        root.addView(statusView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(20)
        ))

        previewView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(25, 45, 75))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            maxLines = 1
        }
        root.addView(previewView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(24)
        ))

        val toolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        toolsRow.addView(makeButton("Load", 10f) { loadClipboard() }, toolParams(1.05f))

        modeButton = makeButton("Word", 10f) {
            characterMode = !characterMode
            updateUi(if (characterMode) "Character mode" else "Word mode")
        }
        toolsRow.addView(modeButton, toolParams(0.9f))

        nextButton = makeButton("Next", 10f) {
            if (characterMode) insertNextCharacter() else insertNextToken()
        }
        toolsRow.addView(nextButton, toolParams(0.9f))

        skipButton = makeButton("Skip", 10f) {
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
        toolsRow.addView(skipButton, toolParams(0.8f))

        toolsRow.addView(makeButton("Reset", 10f) {
            nextIndex = 0
            nextCharOffset = 0
            updateUi()
        }, toolParams(0.85f))
        root.addView(toolsRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(36)
        ))

        val autoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        writeAllButton = makeButton("▶ Write All", 12f) { startOrStopAutoWrite() }
        autoRow.addView(writeAllButton, toolParams(2.2f))
        speedButton = makeButton("Speed: Fast", 12f) {
            speedLevel = (speedLevel + 1) % 3
            updateUi("Auto-write speed: " + speedName())
        }
        autoRow.addView(speedButton, toolParams(1.2f))
        root.addView(autoRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(38)
        ))

        if (!symbolsMode) {
            addKeyRow(root, listOf(
                letterKey("q"), letterKey("w"), letterKey("e"), letterKey("r"), letterKey("t"),
                letterKey("y"), letterKey("u"), letterKey("i"), letterKey("o"), letterKey("p")
            ))
            addKeyRow(root, listOf(
                letterKey("a"), letterKey("s"), letterKey("d"), letterKey("f"), letterKey("g"),
                letterKey("h"), letterKey("j"), letterKey("k"), letterKey("l")
            ), sideInset = dp(8))
            addKeyRow(root, listOf(
                KeySpec("⇧", 1.45f) { toggleShift() },
                letterKey("z"), letterKey("x"), letterKey("c"), letterKey("v"),
                letterKey("b"), letterKey("n"), letterKey("m"),
                KeySpec("⌫", 1.45f) { deleteBeforeCursor() }
            ))
            addKeyRow(root, listOf(
                KeySpec("?123", 1.35f) { toggleSymbols() },
                KeySpec(",", 0.8f) { commitKey(if (shifted) "<" else ",") },
                KeySpec("☺", 0.8f) { commitKey("😊") },
                KeySpec("English", 3.5f) { commitKey(" ") },
                KeySpec(".", 0.8f) { commitKey(if (shifted) ">" else ".") },
                KeySpec("↵", 1.25f) { handleEnter() }
            ))
        } else {
            addKeyRow(root, listOf(
                symbolKey("1"), symbolKey("2"), symbolKey("3"), symbolKey("4"), symbolKey("5"),
                symbolKey("6"), symbolKey("7"), symbolKey("8"), symbolKey("9"), symbolKey("0")
            ))
            addKeyRow(root, listOf(
                symbolKey("@"), symbolKey("#"), symbolKey("$"), symbolKey("%"), symbolKey("&"),
                symbolKey("-"), symbolKey("+"), symbolKey("("), symbolKey(")"), symbolKey("/")
            ), sideInset = dp(8))
            addKeyRow(root, listOf(
                KeySpec("=\\\\<", 1.45f) { toggleShift() },
                symbolKey("*"), symbolKey("\""), symbolKey("'"), symbolKey(":"),
                symbolKey(";"), symbolKey("!"), symbolKey("?"),
                KeySpec("⌫", 1.45f) { deleteBeforeCursor() }
            ))
            addKeyRow(root, listOf(
                KeySpec("ABC", 1.35f) { toggleSymbols() },
                KeySpec(",", 0.8f) { commitKey(",") },
                KeySpec("_", 0.8f) { commitKey("_") },
                KeySpec("English", 3.5f) { commitKey(" ") },
                KeySpec(".", 0.8f) { commitKey(".") },
                KeySpec("↵", 1.25f) { handleEnter() }
            ))
        }

        updateUi()
        return root
    }

    private fun letterKey(letter: String) = KeySpec(
        if (shifted) letter.uppercase() else letter, 1f
    ) {
        commitKey(if (shifted) letter.uppercase() else letter)
        if (shifted) {
            shifted = false
            setInputView(onCreateInputView())
        }
    }

    private fun symbolKey(value: String) = KeySpec(value, 1f) {
        val shiftedValue = when (value) {
            "1" -> "!"
            "2" -> "@"
            "3" -> "#"
            "4" -> "$"
            "5" -> "%"
            "6" -> "^"
            "7" -> "&"
            "8" -> "*"
            "9" -> "("
            "0" -> ")"
            "-" -> "_"
            "+" -> "="
            "(" -> "["
            ")" -> "]"
            "/" -> "\\"
            "*" -> "+"
            "\"" -> "'"
            "'" -> "\""
            ":" -> ";"
            ";" -> ":"
            "!" -> "?"
            "?" -> "!"
            else -> value
        }
        commitKey(if (shifted) shiftedValue else value)
        if (shifted) {
            shifted = false
            updateUi()
        }
    }

    private fun toggleShift() {
        shifted = !shifted
        setInputView(onCreateInputView())
    }

    private fun toggleSymbols() {
        symbolsMode = !symbolsMode
        // Rebuild the input view so the alternate key set is displayed.
        setInputView(onCreateInputView())
    }

    private fun addKeyRow(parent: LinearLayout, keys: List<KeySpec>, sideInset: Int = 0) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(sideInset, dp(2), sideInset, dp(2))
        }
        keys.forEach { key ->
            row.addView(makeButton(key.label, if (key.label.length > 2) 12f else 21f) {
                key.action()
            }, keyParams(key.weight))
        }
        parent.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(49)
        ))
    }

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
        val leadingWhitespace = text.takeWhile { it.isWhitespace() }
        val extracted = Regex("""\S+\s*""").findAll(text).map { it.value }.toList()
        tokens.addAll(extracted)
        if (tokens.isNotEmpty() && leadingWhitespace.isNotEmpty()) {
            tokens[0] = leadingWhitespace + tokens[0]
        }

        nextIndex = 0
        nextCharOffset = 0
        updateUi("Loaded " + tokens.size + " word tokens and " +
            text.codePointCount(0, text.length) + " characters")
    }

    private fun insertNextToken() {
        if (nextIndex >= tokens.size) {
            updateUi("All tokens processed")
            return
        }
        val connection = currentInputConnection
        if (connection == null) {
            updateUi("No focused text field")
            return
        }
        if (connection.commitText(tokens[nextIndex], 1)) {
            nextIndex++
            updateUi()
        } else updateUi("Editor did not accept input")
    }

    private fun insertNextCharacter() {
        if (nextCharOffset >= clipboardText.length) {
            updateUi("All characters processed")
            return
        }
        val connection = currentInputConnection
        if (connection == null) {
            updateUi("No focused text field")
            return
        }
        val codePoint = clipboardText.codePointAt(nextCharOffset)
        val charCount = Character.charCount(codePoint)
        val character = clipboardText.substring(nextCharOffset, nextCharOffset + charCount)
        if (connection.commitText(character, 1)) {
            nextCharOffset += charCount
            updateUi()
        } else updateUi("Editor did not accept input")
    }

    private fun commitKey(value: String) {
        val connection = currentInputConnection ?: run {
            updateUi("No focused text field")
            return
        }
        connection.finishComposingText()
        if (!connection.commitText(value, 1)) updateUi("Editor did not accept input")
    }

    private fun deleteBeforeCursor() {
        if (currentInputConnection == null) return
        // Use the normal Backspace key event so selected text is deleted as a range.
        // Do not also delete surrounding text, which could remove an extra character.
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    private fun startOrStopAutoWrite() {
        if (autoWriteRunnable != null) {
            stopAutoWrite("Auto-write stopped")
            return
        }
        val connection = currentInputConnection
        if (connection == null) {
            updateUi("Tap a text field before Write All")
            return
        }
        if (characterMode && nextCharOffset >= clipboardText.length) {
            updateUi("No characters left; load text or Reset")
            return
        }
        if (!characterMode && nextIndex >= tokens.size) {
            updateUi("No words left; load text or Reset")
            return
        }

        autoWriteConnection = connection
        writeAllButton?.text = "■ Stop"
        updateUi("Writing all loaded text • " + speedName())

        val task = object : Runnable {
            override fun run() {
                if (autoWriteConnection !== currentInputConnection) {
                    stopAutoWrite("Stopped because the text field changed")
                    return
                }
                val accepted = if (characterMode) {
                    if (nextCharOffset >= clipboardText.length) {
                        stopAutoWrite("All characters written")
                        return
                    }
                    val cp = clipboardText.codePointAt(nextCharOffset)
                    val count = Character.charCount(cp)
                    val part = clipboardText.substring(nextCharOffset, nextCharOffset + count)
                    if (autoWriteConnection?.commitText(part, 1) == true) {
                        nextCharOffset += count
                        true
                    } else false
                } else {
                    if (nextIndex >= tokens.size) {
                        stopAutoWrite("All words written")
                        return
                    }
                    if (autoWriteConnection?.commitText(tokens[nextIndex], 1) == true) {
                        nextIndex++
                        true
                    } else false
                }
                if (!accepted) {
                    stopAutoWrite("Editor did not accept more input")
                    return
                }
                updateUi("Writing… " + if (characterMode) {
                    nextCharOffset.coerceAtMost(clipboardText.length).toString() + "/" +
                        clipboardText.length + " characters"
                } else {
                    nextIndex.toString() + "/" + tokens.size + " words"
                })
                autoWriteHandler.postDelayed(this, autoWriteDelay())
            }
        }
        autoWriteRunnable = task
        autoWriteHandler.post(task)
    }

    private fun stopAutoWrite(message: String) {
        autoWriteRunnable?.let { autoWriteHandler.removeCallbacks(it) }
        autoWriteRunnable = null
        autoWriteConnection = null
        writeAllButton?.text = "▶ Write All"
        updateUi(message)
    }

    private fun autoWriteDelay(): Long = when (speedLevel) {
        0 -> if (characterMode) 8L else 18L
        1 -> if (characterMode) 20L else 45L
        else -> if (characterMode) 45L else 100L
    }

    private fun speedName(): String = when (speedLevel) {
        0 -> "Fast"
        1 -> "Normal"
        else -> "Slow"
    }

    private fun handleEnter() {
        val connection: InputConnection = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val isMultiline = info != null &&
            (info.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0

        if (!isMultiline && action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            if (!connection.performEditorAction(action)) commitKey("\n")
        } else {
            commitKey("\n")
        }
    }

    private fun updateUi(message: String? = null) {
        val totalChars = clipboardText.codePointCount(0, clipboardText.length)
        val doneChars = clipboardText.codePointCount(
            0, nextCharOffset.coerceAtMost(clipboardText.length)
        )

        statusView?.text = message ?: if (characterMode) {
            when {
                clipboardText.isEmpty() -> "Ready • type normally or load copied text"
                nextCharOffset >= clipboardText.length -> "Complete • " + totalChars + " characters"
                else -> "Character " + (doneChars + 1) + " of " + totalChars
            }
        } else {
            when {
                tokens.isEmpty() -> "Ready • type normally or load copied text"
                nextIndex >= tokens.size -> "Complete • " + tokens.size + " tokens"
                else -> "Token " + (nextIndex + 1) + " of " + tokens.size
            }
        }

        previewView?.text = if (characterMode) {
            when {
                clipboardText.isEmpty() -> "WordFlow • standard keyboard ready"
                nextCharOffset >= clipboardText.length -> "Queue complete"
                else -> {
                    val cp = clipboardText.codePointAt(nextCharOffset)
                    val ch = String(Character.toChars(cp))
                    when (ch) {
                        "\n" -> "Next: ↵ newline"
                        "\r" -> "Next: ↵ carriage return"
                        "\t" -> "Next: ⇥ tab"
                        " " -> "Next: ␠ space"
                        else -> "Next: " + ch
                    }
                }
            }
        } else {
            when {
                tokens.isEmpty() -> "WordFlow • standard keyboard ready"
                nextIndex >= tokens.size -> "Queue complete"
                else -> "Next: " + tokens[nextIndex].replace("\n", "↵").trimEnd()
            }
        }

        modeButton?.text = if (characterMode) "Char" else "Word"
        nextButton?.text = if (characterMode) "Next char" else "Next word"
        speedButton?.text = "Speed: " + speedName()
        if (autoWriteRunnable == null) writeAllButton?.text = "▶ Write All"
        nextButton?.isEnabled = if (characterMode) {
            clipboardText.isNotEmpty() && nextCharOffset < clipboardText.length
        } else tokens.isNotEmpty() && nextIndex < tokens.size
        skipButton?.isEnabled = if (characterMode) {
            clipboardText.isNotEmpty() && nextCharOffset < clipboardText.length
        } else tokens.isNotEmpty() && nextIndex < tokens.size
        shiftButton?.text = if (shifted) "⇧" else "⇧"
        symbolButton?.text = if (symbolsMode) "ABC" else "?123"
    }

    private fun makeButton(label: String, sizeSp: Float = 14f, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            minHeight = 0
            minWidth = 0
            setPadding(dp(1), 0, dp(1), 0)
            if (sizeSp >= 18f) {
                minimumHeight = dp(45)
                minimumWidth = 0
            }
            setOnClickListener { action() }
        }

    private fun keyParams(weight: Float) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
            setMargins(dp(2), dp(1), dp(2), dp(1))
        }

    private fun toolParams(weight: Float) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
            setMargins(dp(1), dp(1), dp(1), dp(1))
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
