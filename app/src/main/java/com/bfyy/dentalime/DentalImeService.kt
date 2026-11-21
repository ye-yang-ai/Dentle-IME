package com.bfyy.dentalime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.ArrayDeque
import java.util.concurrent.Executors

class DentalImeService : InputMethodService() {
    private var chinese = true
    private var symbols = false
    private var shifted = false
    private var keyRows: LinearLayout? = null
    private var renderedMode = -1
    private var compositionLabel: TextView? = null
    private var candidateStrip: HorizontalScrollView? = null
    private val candidateLabels = mutableListOf<TextView>()
    private val composition = StringBuilder()
    private var candidates = emptyList<PinyinCandidate>()
    private var dictionary: PinyinDictionary? = null
    private var dictionaryFailed = false
    private var destroyed = false
    private val pendingKeys = ArrayDeque<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dictionaryLoader = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        dictionaryLoader.execute {
            val result = runCatching { PinyinDictionary(applicationContext) }
            result.exceptionOrNull()?.let { Log.e("DentalImeService", "Could not load pinyin dictionary", it) }
            mainHandler.post {
                val loaded = result.getOrNull()
                if (destroyed) {
                    loaded?.close()
                    return@post
                }
                dictionary = loaded
                dictionaryFailed = loaded == null
                updateCandidates()
                val queued = pendingKeys.toList()
                pendingKeys.clear()
                queued.forEach(::press)
            }
        }
    }

    override fun onCreateInputView(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(8), dp(3), dp(8))
            setBackgroundColor(Color.rgb(226, 231, 231))
            addCandidateArea()
            keyRows = LinearLayout(this@DentalImeService).apply {
                orientation = LinearLayout.VERTICAL
            }.also { addView(it) }
            renderedMode = -1
            renderKeys()
            updateCandidateViews()
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val inputType = attribute?.inputType ?: InputType.TYPE_CLASS_TEXT
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        chinese = inputClass == InputType.TYPE_CLASS_TEXT && variation !in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI
        )
        symbols = inputClass == InputType.TYPE_CLASS_NUMBER ||
            inputClass == InputType.TYPE_CLASS_PHONE || inputClass == InputType.TYPE_CLASS_DATETIME
        shifted = false
        composition.clear()
        candidates = emptyList()
        pendingKeys.clear()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        renderKeys()
        updateCandidateViews()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onFinishInput() {
        super.onFinishInput()
        composition.clear()
        candidates = emptyList()
        pendingKeys.clear()
        updateCandidateViews()
    }

    override fun onDestroy() {
        destroyed = true
        dictionaryLoader.shutdownNow()
        dictionary?.close()
        super.onDestroy()
    }

    private fun renderKeys() {
        val rowsContainer = keyRows ?: return
        val mode = (if (chinese) 1 else 0) or (if (symbols) 2 else 0) or
            (if (shifted) 4 else 0)
        if (renderedMode == mode && rowsContainer.childCount > 0) return
        rowsContainer.removeAllViews()
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
        rows.forEach { rowsContainer.addKeyRow(it) }
        rowsContainer.addKeyRow(listOf(if (chinese) "中" else "EN", if (symbols) "ABC" else "?123", "🌐", ",", "space", ".", "↵"))
        renderedMode = mode
    }

    private fun LinearLayout.addCandidateArea() {
        compositionLabel = TextView(this@DentalImeService).apply {
            textSize = 14f
            setTextColor(Color.rgb(42, 85, 92))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), 0, 0, 0)
        }.also {
            addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(26)))
        }

        val strip = HorizontalScrollView(this@DentalImeService).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
        }
        candidateStrip = strip
        val row = LinearLayout(this@DentalImeService).apply { orientation = LinearLayout.HORIZONTAL }
        candidateLabels.clear()
        repeat(11) {
            candidateLabels.add(candidateLabel().also { row.addView(it) })
        }
        strip.addView(row)
        addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
    }

    private fun candidateLabel(): TextView =
        TextView(this).apply {
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(24, 39, 42))
            setPadding(dp(12), 0, dp(12), 0)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)
            )
        }

    private fun updateCandidateViews() {
        compositionLabel?.text = composition.toString()
        candidateLabels.forEachIndexed { index, label ->
            val candidate = candidates.getOrNull(index)
            val raw = index == candidates.size && composition.isNotEmpty()
            when {
                candidate != null -> {
                    label.text = candidate.word
                    label.contentDescription = "${candidate.word}，${candidate.pinyin}"
                    label.setOnClickListener { commitComposition(candidate.word, rememberSelection = true) }
                    label.visibility = View.VISIBLE
                }
                raw -> {
                    val value = composition.toString()
                    label.text = value
                    label.contentDescription = "$value，原样输入"
                    label.setOnClickListener { commitComposition(value) }
                    label.visibility = View.VISIBLE
                }
                else -> label.visibility = View.GONE
            }
        }
        candidateStrip?.takeIf { it.scrollX != 0 }?.scrollTo(0, 0)
    }

    private fun updateCandidates() {
        candidates = if (composition.isEmpty()) emptyList()
            else dictionary?.lookup(composition.toString()) ?: emptyList()
        updateCandidateViews()
    }

    private fun commitComposition(
        value: String = candidates.firstOrNull()?.word ?: composition.toString(),
        rememberSelection: Boolean = false
    ) {
        if (composition.isEmpty()) return
        if (rememberSelection && candidates.any { it.word == value }) {
            dictionary?.recordSelection(composition.toString(), value)
        }
        currentInputConnection?.commitText(value, 1)
        composition.clear()
        candidates = emptyList()
        updateCandidateViews()
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
                    label == "space" -> if (chinese) "中文" else "English"
                    shifted && label.length == 1 && label[0].isLetter() -> label.uppercase()
                    else -> label
                }
                gravity = Gravity.CENTER
                textSize = if (label == "space" || label == "?123") 14f else 21f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(24, 39, 42))
                background = GradientDrawable().apply {
                    cornerRadius = dp(5).toFloat()
                    setColor(if (label in listOf("⇧", "⌫", "?123", "ABC", "🌐", "↵", "中", "EN"))
                        Color.rgb(190, 207, 208) else Color.WHITE)
                }
                contentDescription = when (label) {
                    "⌫" -> "删除"
                    "⇧" -> "大写"
                    "↵" -> "回车"
                    "🌐" -> "切换输入法"
                    "space" -> "空格"
                    "中", "EN" -> "切换中英文"
                    else -> label
                }
                setOnClickListener { press(label) }
            }
            val weight = if (label == "space") 3.2f else if (label in listOf("?123", "ABC")) 1.5f else 1f
            row.addView(key, LinearLayout.LayoutParams(0, dp(45), weight).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        addView(row)
    }

    private fun press(label: String) {
        val composingLetter = chinese && !symbols && !shifted &&
            label.length == 1 && label[0] in 'a'..'z'
        if (pendingKeys.isNotEmpty() || (composition.isNotEmpty() && dictionary == null &&
                !dictionaryFailed && label != "⌫" && label != "🌐" && !composingLetter)) {
            pendingKeys.addLast(label)
            return
        }
        when (label) {
            "中", "EN" -> {
                commitComposition()
                chinese = !chinese
                shifted = false
                renderKeys()
            }
            "?123", "ABC" -> {
                commitComposition()
                symbols = !symbols
                renderKeys()
            }
            "⇧" -> {
                commitComposition()
                shifted = !shifted
                renderKeys()
            }
            "⌫" -> {
                if (composition.isNotEmpty()) {
                    composition.deleteCharAt(composition.lastIndex)
                    if (composition.isEmpty()) currentInputConnection?.commitText("", 1)
                    else currentInputConnection?.setComposingText(composition.toString(), 1)
                    updateCandidates()
                    return
                }
                val connection = currentInputConnection ?: return
                val selected = connection.getSelectedText(0)
                if (!selected.isNullOrEmpty()) connection.commitText("", 1)
                else connection.deleteSurroundingTextInCodePoints(1, 0)
            }
            "🌐" -> (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showInputMethodPicker()
            "↵" -> {
                commitComposition()
                val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                if (action != null && action != EditorInfo.IME_ACTION_NONE &&
                    action != EditorInfo.IME_ACTION_UNSPECIFIED
                ) currentInputConnection?.performEditorAction(action)
                else currentInputConnection?.commitText("\n", 1)
            }
            "space" -> {
                if (composition.isNotEmpty()) commitComposition(rememberSelection = true)
                else currentInputConnection?.commitText(" ", 1)
            }
            else -> {
                if (composingLetter) {
                    composition.append(label)
                    currentInputConnection?.setComposingText(composition.toString(), 1)
                    updateCandidates()
                    return
                }
                commitComposition()
                val value = if (chinese && label == ",") "，"
                    else if (chinese && label == ".") "。"
                    else if (shifted) label.uppercase() else label
                currentInputConnection?.commitText(value, 1)
                if (shifted) {
                    shifted = false
                    renderKeys()
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
