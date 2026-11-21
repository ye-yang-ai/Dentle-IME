package com.bfyy.dentalime

import kotlin.math.ln

internal data class WordOption(val word: String, val rank: Int)

internal object SentenceComposer {
    const val MAX_WORD_PINYIN = 30
    const val MAX_INPUT_PINYIN = 120
    private const val BEAM_WIDTH = 24
    private const val WORD_PENALTY = 15.0

    private data class Path(
        val text: String,
        val score: Double,
        val words: Int,
        val lastWord: String,
        val tail: SegmentNode?
    )

    private data class SegmentNode(val segment: PinyinSegment, val previous: SegmentNode?)

    fun compose(
        input: String,
        options: Map<String, List<WordOption>>,
        transitions: Map<Pair<String, String>, Int>,
        limit: Int
    ): List<PinyinCandidate> {
        val paths = Array(input.length + 1) { mutableListOf<Path>() }
        paths[0].add(Path("", 0.0, 0, "", null))

        for (start in input.indices) {
            if (paths[start].isEmpty()) continue
            for (end in start + 1..minOf(input.length, start + MAX_WORD_PINYIN)) {
                val words = options[input.substring(start, end)] ?: continue
                val destination = paths[end]
                for (path in paths[start]) {
                    for (word in words) {
                        val transition = transitions[path.lastWord to word.word]
                        val contextBonus = if (transition == null) 0.0
                            else ((550 - transition) / 20.0).coerceIn(0.0, 6.0)
                        destination.add(
                            Path(
                                path.text + word.word,
                                path.score + ln(word.rank.toDouble() + 1) - WORD_PENALTY + contextBonus,
                                path.words + 1,
                                word.word,
                                SegmentNode(PinyinSegment(start, end, word.word), path.tail)
                            )
                        )
                    }
                }
                if (destination.size > BEAM_WIDTH * 2) prune(destination)
            }
        }

        return paths[input.length]
            .asSequence()
            .filter { it.words > 1 }
            .sortedByDescending { it.score }
            .distinctBy { it.text }
            .take(limit)
            .map { path ->
                val segments = mutableListOf<PinyinSegment>()
                var node = path.tail
                while (node != null) {
                    segments.add(node.segment)
                    node = node.previous
                }
                PinyinCandidate(path.text, input, segments.asReversed())
            }
            .toList()
    }

    private fun prune(paths: MutableList<Path>) {
        val best = paths.sortedByDescending { it.score }.distinctBy { it.text }
            .take(BEAM_WIDTH)
        paths.clear()
        paths.addAll(best)
    }
}
