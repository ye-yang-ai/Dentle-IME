package com.bfyy.dentalime

object T9 {
    private val letters = mapOf(
        '2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl",
        '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz"
    )

    fun encode(pinyin: String): String = buildString(pinyin.length) {
        pinyin.forEach { letter ->
            val digit = letters.entries.firstOrNull { letter in it.value }?.key
                ?: throw IllegalArgumentException("Unsupported pinyin letter: $letter")
            append(digit)
        }
    }

    fun letters(digit: Char): String = letters[digit].orEmpty()
}
