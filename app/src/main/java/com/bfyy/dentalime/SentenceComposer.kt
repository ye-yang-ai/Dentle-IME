package com.bfyy.dentalime

import kotlin.math.ln

internal data class WordOption(
    val word: String, val rank: Int, val domain: Int = 0, val spellingMask: Int = 0
)

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
        val tail: SegmentNode?,
        val spellingMask: Int
    )

    private data class SegmentNode(val segment: PinyinSegment, val previous: SegmentNode?)

    fun compose(
        input: String,
        options: Map<String, List<WordOption>>,
        transitions: Map<Pair<String, String>, Int>,
        limit: Int,
        initials: Boolean = false,
        mixed: Boolean = false
    ): List<PinyinCandidate> {
        val diverse = initials || mixed
        val beamWidth = if (mixed) 64 else if (diverse) 96 else BEAM_WIDTH
        val paths = Array(input.length + 1) { mutableListOf<Path>() }
        paths[0].add(Path("", 0.0, 0, "", null, 0))

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
                        val phraseBonus = if (mixed && word.domain == 1) 8.0
                            else if (diverse && word.domain == 2) 2.5 else 0.0
                        val singleCharacterPenalty = if (diverse && word.word.length == 1) 2.5 else 0.0
                        destination.add(
                            Path(
                                path.text + word.word,
                                path.score + ln(word.rank.toDouble() + 1) - WORD_PENALTY +
                                    contextBonus + phraseBonus - singleCharacterPenalty,
                                path.words + 1,
                                word.word,
                                SegmentNode(PinyinSegment(start, end, word.word), path.tail),
                                path.spellingMask or word.spellingMask
                            )
                        )
                    }
                }
                if (destination.size > beamWidth * 2) prune(destination, beamWidth, diverse, mixed)
            }
        }

        if (diverse && paths[input.length].size > beamWidth) {
            prune(paths[input.length], beamWidth, diverse, mixed)
        }

        return paths[input.length]
            .asSequence()
            .filter { if (mixed) it.words > 0 && it.spellingMask == 3 else it.words > 1 }
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
                PinyinCandidate(path.text, input, segments.asReversed(), path.score)
            }
            .toList()
    }

    private fun prune(paths: MutableList<Path>, beamWidth: Int, diverse: Boolean, mixed: Boolean) {
        val ranked = paths.sortedByDescending { it.score }
            .distinctBy { if (mixed) it.text to it.spellingMask else it.text to 0 }
        val varied = if (diverse) ranked.distinctBy {
            (if (mixed) it.text.take(2) + it.text.takeLast(1) else it.text.take(2)) to
                (if (mixed) it.spellingMask else 0)
        }
            .take(beamWidth / 2)
            else emptyList()
        val kept = varied.mapTo(hashSetOf()) { it.text to it.spellingMask }
        val best = varied + ranked.filter { kept.add(it.text to it.spellingMask) }
            .take(beamWidth - varied.size)
        paths.clear()
        paths.addAll(best)
    }
}
