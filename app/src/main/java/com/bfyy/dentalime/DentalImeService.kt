package com.bfyy.dentalime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView

class DentalImeService : InputMethodService() {
    private var symbols = false
    private var shifted = false
    private var keyboard: LinearLayout? = null

    override fun onCreateInputView(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(8), dp(3), dp(8))
            setBackgroundColor(Color.rgb(226, 231, 231))
            keyboard = this
            renderKeys()
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        symbols = false
        shifted = false
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboard?.renderKeys()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    private fun LinearLayout.renderKeys() {
        removeAllViews()
        val rows = if (symbols) {
            listOf(
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                listOf("@", "#", "$", "%", "&", "-", "+", "(", ")"),
                listOf("=", "*", "\"", "'", ":", ";", "!", "?", "⌫")
            )
        } else {
            listOf(
                "qwertyuiop".map(Char::toString),
                "asdfghjkl".map(Char::toString),
                listOf("⇧") + "zxcvbnm".map(Char::toString) + "⌫"
            )
        }
        rows.forEach { addKeyRow(it) }
        addKeyRow(listOf(if (symbols) "ABC" else "?123", "🌐", ",", "space", ".", "↵"))
    }

    private fun LinearLayout.addKeyRow(labels: List<String>) {
        val row = LinearLayout(this@DentalImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            )
        }
        labels.forEach { label ->
            val key = TextView(this@DentalImeService).apply {
                text = when {
                    label == "space" -> "English"
                    shifted && label.length == 1 && label[0].isLetter() -> label.uppercase()
                    else -> label
                }
                gravity = Gravity.CENTER
                textSize = if (label == "space") 14f else 21f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(24, 39, 42))
                background = GradientDrawable().apply {
                    cornerRadius = dp(5).toFloat()
                    setColor(if (label in listOf("⇧", "⌫", "?123", "ABC", "🌐", "↵"))
                        Color.rgb(190, 207, 208) else Color.WHITE)
                }
                contentDescription = when (label) {
                    "⌫" -> "删除"
                    "⇧" -> "大写"
                    "↵" -> "回车"
                    "🌐" -> "切换输入法"
                    "space" -> "空格"
                    else -> label
                }
                setOnClickListener { press(label) }
            }
            val weight = if (label == "space") 4f else if (label in listOf("?123", "ABC")) 1.5f else 1f
            row.addView(key, LinearLayout.LayoutParams(0, dp(45), weight).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        addView(row)
    }

    private fun press(label: String) {
        when (label) {
            "?123", "ABC" -> {
                symbols = !symbols
                keyboard?.renderKeys()
            }
            "⇧" -> {
                shifted = !shifted
                keyboard?.renderKeys()
            }
            "⌫" -> {
                val connection = currentInputConnection ?: return
                val selected = connection.getSelectedText(0)
                if (!selected.isNullOrEmpty()) connection.commitText("", 1)
                else connection.deleteSurroundingTextInCodePoints(1, 0)
            }
            "🌐" -> (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showInputMethodPicker()
            "↵" -> {
                val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                if (action != null && action != EditorInfo.IME_ACTION_NONE &&
                    action != EditorInfo.IME_ACTION_UNSPECIFIED
                ) currentInputConnection?.performEditorAction(action)
                else currentInputConnection?.commitText("\n", 1)
            }
            else -> {
                currentInputConnection?.commitText(
                    if (label == "space") " " else if (shifted) label.uppercase() else label, 1
                )
                if (shifted) {
                    shifted = false
                    keyboard?.renderKeys()
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
