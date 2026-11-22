package com.bfyy.dentalime

import android.content.Context

class CandidateMemory(context: Context, name: String = FILE_NAME) {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun count(pinyin: String, word: String): Int =
        preferences.getInt("$pinyin|$word", 0).coerceAtLeast(0)

    fun remembered(pinyin: String, limit: Int): List<String> {
        val prefix = "$pinyin|"
        return preferences.all.asSequence()
            .filter { (key, value) -> key.startsWith(prefix) && value is Int && value > 0 }
            .sortedByDescending { (_, value) -> value as Int }
            .map { (key, _) -> key.removePrefix(prefix) }
            .filter { it.isNotEmpty() }
            .take(limit)
            .toList()
    }

    fun record(pinyin: String, word: String) {
        if (pinyin.isEmpty()) return
        val current = count(pinyin, word)
        if (current < Int.MAX_VALUE) {
            preferences.edit().putInt("$pinyin|$word", current + 1).apply()
        }
    }

    fun clear() = preferences.edit().clear().apply()

    companion object {
        private const val FILE_NAME = "candidate_memory"
    }
}
