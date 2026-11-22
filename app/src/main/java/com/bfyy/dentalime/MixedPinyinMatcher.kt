package com.bfyy.dentalime

import android.database.sqlite.SQLiteDatabase

internal class MixedPinyinMatcher(private val database: SQLiteDatabase) {
    private data class Edge(val node: Int, val syllable: String)
    private data class State(val node: Int, val end: Int, val mask: Int, val depth: Int)
    private data class Match(val node: Int, val mask: Int)

    private val rootEdges: List<Edge> by lazy {
        val edges = mutableListOf<Edge>()
        database.rawQuery("SELECT id, syllable FROM mixed_nodes WHERE parent = 0", null)
            .use { cursor ->
                while (cursor.moveToNext()) edges.add(Edge(cursor.getInt(0), cursor.getString(1)))
            }
        edges
    }
    private val childrenByParent: Map<Int, List<Edge>> by lazy {
        val children = mutableMapOf<Int, MutableList<Edge>>()
        database.rawQuery("SELECT id, parent, syllable FROM mixed_nodes", null)
            .use { cursor ->
                while (cursor.moveToNext()) {
                    children.getOrPut(cursor.getInt(1)) { mutableListOf() }
                        .add(Edge(cursor.getInt(0), cursor.getString(2)))
                }
            }
        children
    }
    private val fullSyllablesByInitial by lazy {
        rootEdges.map { it.syllable }
            .filter { it.length > 1 || it in listOf("a", "e", "o") }
            .groupBy { it[0] }
    }
    private val wordsCache = mutableMapOf<Int, MutableList<WordOption>>()

    fun canSpellFully(input: String): Boolean {
        val reachable = BooleanArray(input.length + 1)
        reachable[0] = true
        for (start in input.indices) {
            if (!reachable[start]) continue
            for (syllable in fullSyllablesByInitial[input[start]].orEmpty()) {
                if (input.startsWith(syllable, start)) reachable[start + syllable.length] = true
            }
        }
        return reachable[input.length]
    }

    fun initialsFor(input: String, syllableCount: Int): String? {
        val failed = hashSetOf<Pair<Int, Int>>()
        fun match(start: Int, remaining: Int): String? {
            if (start == input.length) return if (remaining == 0) "" else null
            if (remaining == 0 || !failed.add(start to remaining)) return null
            for (syllable in fullSyllablesByInitial[input[start]].orEmpty()
                .sortedByDescending { it.length }) {
                if (input.startsWith(syllable, start)) {
                    val suffix = match(start + syllable.length, remaining - 1)
                    if (suffix != null) return syllable[0] + suffix
                }
            }
            return null
        }
        return match(0, syllableCount)
    }

    fun options(input: String): Map<String, List<WordOption>> {
        if (wordsCache.size > 2048) wordsCache.clear()
        val children = childrenByParent
        val matches = mutableMapOf<String, MutableList<Match>>()
        val reachedNodes = hashSetOf<Int>()
        for (start in input.indices) {
            val pending = ArrayDeque<State>()
            val seen = hashSetOf<Triple<Int, Int, Int>>()
            pending.add(State(0, start, 0, 0))
            while (pending.isNotEmpty() && seen.size < 3000) {
                val state = pending.removeFirst()
                if (!seen.add(Triple(state.node, state.end, state.mask))) continue
                if (state.node != 0) {
                    matches.getOrPut(input.substring(start, state.end)) { mutableListOf() }
                        .add(Match(state.node, state.mask))
                    reachedNodes.add(state.node)
                }
                if (state.depth == 12 || state.end - start >= SentenceComposer.MAX_WORD_PINYIN) continue
                for (edge in children[state.node].orEmpty()) {
                    if (state.end < input.length && input[state.end] == edge.syllable[0]) {
                        if (edge.syllable.length > 1) {
                            pending.add(State(edge.node, state.end + 1, state.mask or 2, state.depth + 1))
                        }
                        if (input.startsWith(edge.syllable, state.end)) {
                            pending.add(State(edge.node, state.end + edge.syllable.length,
                                state.mask or (if (edge.syllable.length > 1 ||
                                    edge.syllable in listOf("a", "e", "o")) 1 else 2),
                                state.depth + 1))
                        }
                    }
                }
            }
        }
        loadWords(reachedNodes)
        val options = mutableMapOf<String, MutableList<WordOption>>()
        for ((key, matchesForKey) in matches) {
            val choices = options.getOrPut(key) { mutableListOf() }
            for (match in matchesForKey) {
                for (word in wordsCache[match.node].orEmpty()) {
                    if (choices.any { it.word == word.word && it.spellingMask == match.mask }) continue
                    val choice = word.copy(spellingMask = match.mask)
                    val sameMask = choices.filter { it.spellingMask == match.mask }
                    if (sameMask.size < 24) choices.add(choice)
                    else {
                        val weakest = sameMask.minBy { it.rank }
                        if (choice.rank > weakest.rank) {
                            choices.remove(weakest)
                            choices.add(choice)
                        }
                    }
                }
            }
        }
        return options
    }

    private fun loadWords(nodes: Set<Int>) {
        for (batch in nodes.filterNot { wordsCache.containsKey(it) }.chunked(400)) {
            batch.forEach { wordsCache[it] = mutableListOf() }
            val placeholders = List(batch.size) { "?" }.joinToString(",")
            database.rawQuery(
                "SELECT m.node, e.word, e.rank, e.domain FROM mixed_entries m " +
                    "JOIN entries e ON e.rowid = m.entry_rowid WHERE m.node IN ($placeholders) " +
                    "ORDER BY m.node, e.rank DESC, e.freq DESC",
                batch.map { it.toString() }.toTypedArray()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val choices = wordsCache.getValue(cursor.getInt(0))
                    if (choices.size < 24) {
                        choices.add(WordOption(cursor.getString(1), cursor.getInt(2), cursor.getInt(3)))
                    }
                }
            }
        }
    }
}
