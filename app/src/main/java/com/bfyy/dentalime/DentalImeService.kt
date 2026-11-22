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
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class DentalImeService : InputMethodService() {
    private data class SelectedPrefix(val pinyin: String, val word: String)
    private data class CandidateChoice(val candidate: PinyinCandidate, val consumed: Int)
    private var chinese = true
    private var symbols = false
    private var shifted = false
    private var keyRows: LinearLayout? = null
    private var renderedMode = -1
    private var compositionLabel: TextView? = null
    private var segmentStrip: HorizontalScrollView? = null
    private var segmentRow: LinearLayout? = null
    private var candidateStrip: HorizontalScrollView? = null
    private var editBackLabel: TextView? = null
    private val candidateLabels = mutableListOf<TextView>()
    private val composition = StringBuilder()
    private val originalInput = StringBuilder()
    private val selectedPrefixes = mutableListOf<SelectedPrefix>()
    private var remainingInitialPending = false
    private var candidates = emptyList<PinyinCandidate>()
    private val candidateConsumed = mutableListOf<Int>()
    private var sentenceDraft: PinyinCandidate? = null
    private var draftEdited = false
    private var editingSegment: Int? = null
    private var dictionary: PinyinDictionary? = null
    private var dictionaryFailed = false
    private var destroyed = false
    private var lookupPending = false
    private var candidateGeneration = 0
    private val pendingKeys = ArrayDeque<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dictionaryWorker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()
    )

    override fun onCreate() {
        super.onCreate()
        dictionaryWorker.execute {
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
        originalInput.clear()
        selectedPrefixes.clear()
        remainingInitialPending = false
        candidates = emptyList()
        candidateConsumed.clear()
        clearSentenceDraft()
        pendingKeys.clear()
        lookupPending = false
        candidateGeneration++
        if (dictionary != null) dictionaryWorker.queue.clear()
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
        originalInput.clear()
        selectedPrefixes.clear()
        remainingInitialPending = false
        candidates = emptyList()
        candidateConsumed.clear()
        clearSentenceDraft()
        pendingKeys.clear()
        lookupPending = false
        candidateGeneration++
        if (dictionary != null) dictionaryWorker.queue.clear()
        updateCandidateViews()
    }

    override fun onDestroy() {
        destroyed = true
        candidateGeneration++
        dictionaryWorker.queue.clear()
        dictionary?.let { loaded -> dictionaryWorker.execute { loaded.close() } }
        dictionaryWorker.shutdown()
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

        segmentStrip = HorizontalScrollView(this@DentalImeService).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
        }.also { strip ->
            segmentRow = LinearLayout(this@DentalImeService).apply {
                orientation = LinearLayout.HORIZONTAL
            }.also { row ->
                strip.addView(row, ViewGroup.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ))
            }
            addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)))
        }

        val strip = HorizontalScrollView(this@DentalImeService).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
        }
        candidateStrip = strip
        val row = LinearLayout(this@DentalImeService).apply { orientation = LinearLayout.HORIZONTAL }
        editBackLabel = candidateLabel().apply {
            text = "‹"
            textSize = 28f
            contentDescription = "返回整句候选"
            setOnClickListener {
                editingSegment = null
                updateCandidates()
            }
        }.also(row::addView)
        candidateLabels.clear()
        repeat(30) {
            candidateLabels.add(candidateLabel().also { row.addView(it) })
        }
        strip.addView(row, ViewGroup.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))
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
        compositionLabel?.text = selectedPrefixes.joinToString("") { it.word } + composition.toString()
        val draft = sentenceDraft
        segmentRow?.apply {
            removeAllViews()
            draft?.segments?.forEachIndexed { index, segment ->
                addView(TextView(this@DentalImeService).apply {
                    text = segment.word
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(24, 39, 42))
                    setPadding(dp(10), 0, dp(10), 0)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(4).toFloat()
                        setColor(if (editingSegment == index) Color.rgb(140, 198, 189)
                            else Color.rgb(206, 220, 217))
                    }
                    contentDescription = "修改${segment.word}，拼音${draft.pinyin.substring(segment.start, segment.end)}"
                    setOnClickListener {
                        editingSegment = index
                        updateCandidates()
                    }
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)
                ).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
        }
        editBackLabel?.visibility = if (editingSegment != null) View.VISIBLE else View.GONE
        candidateLabels.forEachIndexed { index, label ->
            val candidate = candidates.getOrNull(index)
            val raw = editingSegment == null && index == candidates.size && composition.isNotEmpty()
            when {
                candidate != null -> {
                    label.text = candidate.word
                    label.contentDescription = "${candidate.word}，${candidate.pinyin}"
                    label.setOnClickListener {
                        if (editingSegment != null) replaceDraftSegment(candidate.word)
                        else {
                            val consumed = candidateConsumed.getOrNull(index) ?: composition.length
                            if (consumed < composition.length) selectPrefix(candidate, consumed)
                            else commitComposition(candidate.word, rememberSelection = true)
                        }
                    }
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
        val input = composition.toString()
        val generation = ++candidateGeneration
        val loaded = dictionary
        val segment = editingSegment?.let { sentenceDraft?.segments?.getOrNull(it) }
        val lookupInput = segment?.let { input.substring(it.start, it.end) } ?: input
        candidates = emptyList()
        candidateConsumed.clear()
        updateCandidateViews()
        if (input.isEmpty() || loaded == null) {
            lookupPending = input.isNotEmpty() && loaded == null && !dictionaryFailed
            if (!lookupPending) drainPendingKeys()
            return
        }

        lookupPending = true
        dictionaryWorker.queue.clear()
        dictionaryWorker.execute {
            val result = runCatching {
                if (segment != null) {
                    loaded.lookupExact(lookupInput).map { CandidateChoice(it, lookupInput.length) }
                } else {
                    val found = loaded.lookup(input, if (input.length == 1) 30 else 10)
                    val prefixes = loaded.lookupPrefixes(input)
                    val wholeFirst = found.firstOrNull()?.let {
                        it.segments.size > 1 || loaded.memoryCount(input, it.word) > 0
                    } == true
                    val choices = mutableListOf<CandidateChoice>()
                    if (wholeFirst) choices.add(CandidateChoice(found.first(), input.length))
                    prefixes.forEach { choices.add(CandidateChoice(it.candidate, it.consumed)) }
                    found.forEach { candidate ->
                        if (choices.none { it.candidate.word == candidate.word }) {
                            choices.add(CandidateChoice(candidate, input.length))
                        }
                    }
                    choices.take(if (input.length == 1) 30 else 10)
                }
            }
            result.exceptionOrNull()?.let { Log.e("DentalImeService", "Candidate lookup failed", it) }
            mainHandler.post {
                if (destroyed || generation != candidateGeneration) return@post
                lookupPending = false
                val choices = result.getOrDefault(emptyList())
                candidateConsumed.clear()
                candidateConsumed.addAll(choices.map { it.consumed })
                val found = choices.map { it.candidate }
                if (segment != null) {
                    candidates = found
                } else {
                    val edited = sentenceDraft?.takeIf { draftEdited && it.pinyin == input }
                    candidates = if (edited != null) {
                        candidateConsumed.add(0, input.length)
                        listOf(edited) + found.filter { it.word != edited.word }.take(9)
                    } else found
                    if (edited == null) {
                        sentenceDraft = found.firstOrNull()?.takeIf { it.segments.size > 1 && it.pinyin == input }
                    }
                }
                updateCandidateViews()
                drainPendingKeys()
            }
        }
    }

    private fun drainPendingKeys() {
        val queued = pendingKeys.toList()
        pendingKeys.clear()
        queued.forEach(::press)
    }

    private fun commitComposition(
        value: String = if (editingSegment != null || draftEdited)
            sentenceDraft?.word ?: composition.toString()
        else candidates.firstOrNull()?.word ?: composition.toString(),
        rememberSelection: Boolean = true
    ) {
        if (composition.isEmpty() && selectedPrefixes.isEmpty()) return
        val input = originalInput.toString().ifEmpty {
            selectedPrefixes.joinToString("") { it.pinyin } + composition.toString()
        }
        val finalValue = selectedPrefixes.joinToString("") { it.word } + value
        if (rememberSelection && (sentenceDraft?.word == finalValue ||
                candidates.any { it.word == value } || selectedPrefixes.isNotEmpty())) {
            dictionary?.recordSelection(input, finalValue)
        }
        currentInputConnection?.commitText(finalValue, 1)
        composition.clear()
        originalInput.clear()
        selectedPrefixes.clear()
        remainingInitialPending = false
        candidates = emptyList()
        candidateConsumed.clear()
        clearSentenceDraft()
        updateCandidateViews()
    }

    private fun selectPrefix(candidate: PinyinCandidate, consumed: Int) {
        if (consumed <= 0 || consumed > composition.length) return
        selectedPrefixes.add(SelectedPrefix(composition.substring(0, consumed), candidate.word))
        composition.delete(0, consumed)
        remainingInitialPending = composition.length == 1
        currentInputConnection?.setComposingText("", 1)
        if (composition.isNotEmpty()) {
            currentInputConnection?.setComposingText(composition.toString(), 1)
        }
        clearSentenceDraft()
        updateCandidates()
    }

    private fun replaceDraftSegment(word: String) {
        val index = editingSegment ?: return
        val draft = sentenceDraft ?: return
        val segment = draft.segments[index]
        sentenceDraft = draft.replaceSegment(index, word)
        draftEdited = true
        editingSegment = null
        dictionary?.recordSelection(draft.pinyin.substring(segment.start, segment.end), word)
        updateCandidates()
    }

    private fun clearSentenceDraft() {
        sentenceDraft = null
        draftEdited = false
        editingSegment = null
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
        if (pendingKeys.isNotEmpty() || (composition.isNotEmpty() && lookupPending &&
                label != "⌫" && label != "🌐" && !composingLetter)) {
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
                    clearSentenceDraft()
                    composition.deleteCharAt(composition.lastIndex)
                    if (selectedPrefixes.isEmpty() && originalInput.isNotEmpty()) {
                        originalInput.deleteCharAt(originalInput.lastIndex)
                    }
                    if (composition.isEmpty()) currentInputConnection?.commitText("", 1)
                    else currentInputConnection?.setComposingText(composition.toString(), 1)
                    updateCandidates()
                    return
                }
                if (selectedPrefixes.isNotEmpty()) {
                    val removed = selectedPrefixes.removeAt(selectedPrefixes.lastIndex)
                    composition.insert(0, removed.pinyin)
                    currentInputConnection?.setComposingText(composition.toString(), 1)
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
                if (editingSegment != null && candidates.isNotEmpty()) {
                    replaceDraftSegment(candidates.first().word)
                } else if (composition.isNotEmpty() || selectedPrefixes.isNotEmpty()) {
                    commitComposition(rememberSelection = true)
                }
                else currentInputConnection?.commitText(" ", 1)
            }
            else -> {
                if (composingLetter) {
                    clearSentenceDraft()
                    if (remainingInitialPending && composition.length == 1 &&
                        composition[0] == label[0]
                    ) {
                        remainingInitialPending = false
                        updateCandidates()
                        return
                    }
                    remainingInitialPending = false
                    composition.append(label)
                    if (selectedPrefixes.isEmpty()) originalInput.append(label)
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
