package com.bfyy.dentalime

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import kotlin.math.ln

data class PinyinSegment(val start: Int, val end: Int, val word: String)

data class PinyinCandidate(
    val word: String,
    val pinyin: String,
    val segments: List<PinyinSegment> = emptyList(),
    val compositionScore: Double = 0.0
) {
    fun replaceSegment(index: Int, replacement: String): PinyinCandidate {
        val updated = segments.toMutableList()
        updated[index] = updated[index].copy(word = replacement)
        return copy(word = updated.joinToString("") { it.word }, segments = updated)
    }
}

class PinyinDictionary(
    context: Context,
    private val memory: CandidateMemory = CandidateMemory(context)
) : Closeable {
    private companion object {
        const val MAX_INITIALS_INPUT = 20
        const val MAX_INITIALS_WORD = 12
        const val INITIALS_OPTIONS_PER_KEY = 24
    }

    private val database: SQLiteDatabase

    init {
        val file = File(context.noBackupFilesDir, "pinyin.db")
        val versionFile = File(context.noBackupFilesDir, "pinyin.version")
        val packageUpdate = context.packageManager
            .getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (!file.exists() || !versionFile.exists() || versionFile.readText() != packageUpdate) {
            val pending = File(context.noBackupFilesDir, "pinyin.pending")
            context.assets.open("pinyin.db").use { source ->
                pending.outputStream().use { destination -> source.copyTo(destination) }
            }
            check(!file.exists() || file.delete()) { "Could not replace pinyin database" }
            check(pending.renameTo(file)) { "Could not install pinyin database" }
            versionFile.writeText(packageUpdate)
        }
        database = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    fun lookup(input: String, limit: Int = 10): List<PinyinCandidate> {
        if (input.isEmpty()) return emptyList()
        val result = mutableListOf<PinyinCandidate>()
        val seen = hashSetOf<String>()

        fun collect(sql: String, args: Array<String>) {
            database.rawQuery(sql, args).use { cursor ->
                while (cursor.moveToNext()) {
                    val word = cursor.getString(0)
                    if (seen.add(word)) result.add(PinyinCandidate(word, cursor.getString(1)))
                }
            }
        }

        collect(
            "SELECT word, pinyin FROM entries WHERE pinyin = ? ORDER BY rank DESC, freq DESC LIMIT $limit",
            arrayOf(input)
        )
        if (input.length in 6..SentenceComposer.MAX_INPUT_PINYIN && result.size < limit) {
            for (candidate in sentenceCandidates(input, limit - result.size)) {
                if (seen.add(candidate.word)) result.add(candidate)
            }
        }
        if (input.length in 2..MAX_INITIALS_INPUT && result.size < limit) {
            collect(
                "SELECT word, initials FROM entries WHERE initials = ? AND initials != '' " +
                    "ORDER BY rank DESC, freq DESC LIMIT ${minOf(4, limit - result.size)}",
                arrayOf(input)
            )
            if (result.size < limit) {
                for (candidate in initialsCandidates(input, limit - result.size)) {
                    if (seen.add(candidate.word)) result.add(candidate)
                }
            }
        }
        if (result.size < limit) {
            collect(
                "SELECT word, pinyin FROM entries WHERE pinyin > ? AND pinyin < ? " +
                    "ORDER BY freq DESC LIMIT ${limit - result.size}",
                arrayOf(input, "$input{")
            )
        }
        return sortByMemory(input, result)
    }

    fun lookupExact(input: String, limit: Int = 10): List<PinyinCandidate> {
        if (input.isEmpty()) return emptyList()
        val result = mutableListOf<PinyinCandidate>()
        val seen = hashSetOf<String>()
        database.rawQuery(
            "SELECT word, pinyin FROM entries WHERE pinyin = ? ORDER BY rank DESC, freq DESC LIMIT $limit",
            arrayOf(input)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val word = cursor.getString(0)
                if (seen.add(word)) result.add(PinyinCandidate(word, cursor.getString(1)))
            }
        }
        if (input.length <= MAX_INITIALS_WORD && result.size < limit) {
            database.rawQuery(
                "SELECT word, initials FROM entries WHERE initials = ? AND initials != '' " +
                    "ORDER BY rank DESC, freq DESC LIMIT ${limit * 4}",
                arrayOf(input)
            ).use { cursor ->
                while (cursor.moveToNext() && result.size < limit) {
                    val word = cursor.getString(0)
                    if (seen.add(word)) result.add(PinyinCandidate(word, cursor.getString(1)))
                }
            }
        }
        return sortByMemory(input, result)
    }

    fun recordSelection(input: String, word: String) = memory.record(input, word)

    private fun sortByMemory(input: String, result: List<PinyinCandidate>): List<PinyinCandidate> =
        result.withIndex()
            .sortedWith(compareByDescending<IndexedValue<PinyinCandidate>> {
                memory.count(input, it.value.word)
            }.thenBy { it.index })
            .map { it.value }

    private fun sentenceCandidates(input: String, limit: Int): List<PinyinCandidate> {
        val keys = linkedSetOf<String>()
        for (start in input.indices) {
            for (end in start + 1..minOf(input.length, start + SentenceComposer.MAX_WORD_PINYIN)) {
                keys.add(input.substring(start, end))
            }
        }

        val options = mutableMapOf<String, MutableList<WordOption>>()
        val transitions = mutableMapOf<Pair<String, String>, Int>()
        for (batch in keys.chunked(400)) {
            val placeholders = List(batch.size) { "?" }.joinToString(",")
            database.rawQuery(
                "SELECT pinyin, word, rank FROM entries WHERE pinyin IN ($placeholders) " +
                    "ORDER BY pinyin, rank DESC, freq DESC",
                batch.toTypedArray()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val choices = options.getOrPut(cursor.getString(0)) { mutableListOf() }
                    if (choices.size < 4) {
                        choices.add(WordOption(cursor.getString(1), cursor.getInt(2)))
                    }
                }
            }
            database.rawQuery(
                "SELECT left_word, right_word, score FROM transitions " +
                    "WHERE pinyin IN ($placeholders)",
                batch.toTypedArray()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    transitions[cursor.getString(0) to cursor.getString(1)] = cursor.getInt(2)
                }
            }
        }
        return SentenceComposer.compose(input, options, transitions, limit)
    }

    private fun initialsCandidates(input: String, limit: Int): List<PinyinCandidate> {
        val keys = linkedSetOf<String>()
        for (start in input.indices) {
            for (end in start + 1..minOf(input.length, start + MAX_INITIALS_WORD)) {
                keys.add(input.substring(start, end))
            }
        }
        val options = mutableMapOf<String, MutableList<WordOption>>()
        val transitions = mutableMapOf<Pair<String, String>, Int>()
        for (batch in keys.chunked(400)) {
            val placeholders = List(batch.size) { "?" }.joinToString(",")
            database.rawQuery(
                "SELECT initials, word, rank, domain FROM entries WHERE initials IN ($placeholders) " +
                    "ORDER BY initials, rank DESC, freq DESC",
                batch.toTypedArray()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val choices = options.getOrPut(cursor.getString(0)) { mutableListOf() }
                    if (choices.size < INITIALS_OPTIONS_PER_KEY &&
                        choices.none { it.word == cursor.getString(1) }
                    ) {
                        choices.add(WordOption(cursor.getString(1), cursor.getInt(2), cursor.getInt(3)))
                    }
                }
            }
            database.rawQuery(
                "SELECT left_word, right_word, score FROM transitions " +
                    "WHERE initials IN ($placeholders)",
                batch.toTypedArray()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    transitions[cursor.getString(0) to cursor.getString(1)] = cursor.getInt(2)
                }
            }
        }
        val composed = SentenceComposer.compose(
            input, options, transitions, maxOf(limit * 12, 120), initials = true
        )
        val pairs = composed.flatMap { candidate ->
            candidate.segments.zipWithNext { left, right ->
                left.word.takeLast(1) + right.word.take(1)
            }
        }.distinct()
        val pairRanks = mutableMapOf<String, Int>()
        for (batch in pairs.chunked(400)) {
            val placeholders = List(batch.size) { "?" }.joinToString(",")
            database.rawQuery(
                "SELECT word, max(rank) FROM entries WHERE word IN ($placeholders) GROUP BY word",
                batch.toTypedArray()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    pairRanks[cursor.getString(0)] = cursor.getInt(1)
                }
            }
        }
        val ranked = composed.sortedByDescending { candidate ->
            candidate.compositionScore + candidate.segments.zipWithNext { left, right ->
                val pair = left.word.takeLast(1) + right.word.take(1)
                pairRanks[pair]?.let { (ln(it.toDouble() + 1) - 6.0) * 2 } ?: -12.0
            }.sum()
        }
        val varied = ranked.distinctBy { it.word.take(2) }.take(limit)
        if (varied.size == limit) return varied
        return varied + ranked.filter { candidate -> varied.none { it.word == candidate.word } }
            .take(limit - varied.size)
    }

    override fun close() = database.close()
}
