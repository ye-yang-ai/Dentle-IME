package com.bfyy.dentalime

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

data class PinyinCandidate(val word: String, val pinyin: String)

class PinyinDictionary(
    context: Context,
    private val memory: CandidateMemory = CandidateMemory(context)
) : Closeable {
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
        if (result.size < limit) {
            collect(
                "SELECT word, pinyin FROM entries WHERE pinyin > ? AND pinyin < ? " +
                    "ORDER BY freq DESC LIMIT ${limit - result.size}",
                arrayOf(input, "$input{")
            )
        }
        return result.withIndex()
            .sortedWith(compareByDescending<IndexedValue<PinyinCandidate>> {
                memory.count(input, it.value.word)
            }.thenBy { it.index })
            .map { it.value }
    }

    fun recordSelection(input: String, word: String) = memory.record(input, word)

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

    override fun close() = database.close()
}
