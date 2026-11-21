package com.bfyy.dentalime

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

data class PinyinCandidate(val word: String, val pinyin: String)

class PinyinDictionary(context: Context) {
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
        if (result.size < limit) {
            collect(
                "SELECT word, pinyin FROM entries WHERE pinyin > ? AND pinyin < ? " +
                    "ORDER BY freq DESC LIMIT ${limit - result.size}",
                arrayOf(input, "$input{")
            )
        }
        return result
    }

    fun close() = database.close()
}
