package io.legado.app.help.book

import java.util.BitSet

/**
 * Matches connected components of same-book hashes at Hamming distance <= 10.
 * Each component contributes at most one match, using maximum bipartite matching.
 * Components are prepared once per book and reused across comparisons and runs.
 *
 * For >= 64 books, four disjoint 16-bit partitions filter candidates exactly: if
 * total distance is <= 10, some partition has distance <= 2 (otherwise it is at
 * least 12). Probing all 1 + 16 + 120 = 137 masks therefore cannot miss a match.
 * Shared partitions may produce false positives, which the exact matcher rejects.
 * Only prior books are indexed, and only one book's candidate BitSet is retained.
 * Dense data still requires quadratic book-pair comparisons in the worst case.
 */
internal class DuplicateContentMatcher(
    books: List<List<Long>>,
    checkActive: () -> Unit = {}
) {
    private val prepared = books.map {
        checkActive()
        clusters(it)
    }

    /**
     * Calls [onMatch] with (earlier index, later index, positive match count).
     * Reports (processed books, total books) once per book, including empty books.
     * Returns the number of exact book-pair comparisons, including rejected pairs.
     * Exceptions from callbacks propagate, including cancellation from [checkActive].
     */
    fun forEachMatch(
        checkActive: () -> Unit = {},
        progress: (Int, Int) -> Unit = { _, _ -> },
        onMatch: (Int, Int, Int) -> Unit
    ): Long {
        val checks = Checks(checkActive)
        val index = if (prepared.size >= 64) {
            Array(4) { HashMap<Int, MutableList<Int>>() }
        } else null
        val candidates = BitSet(prepared.size)
        var comparisons = 0L
        for (book in prepared.indices) {
            checkActive()
            candidates.clear()
            // Unique values per band prevent repeated hashes from duplicating postings.
            val values = if (index != null) Array(4) { HashSet<Int>() } else null
            if (index == null) {
                candidates.set(0, book)
            } else {
                for (cluster in prepared[book]) for (hash in cluster) {
                    for (band in 0 until 4) {
                        checks.tick()
                        values!![band].add(((hash ushr (band * 16)) and 0xffffL).toInt())
                    }
                }
                for (band in 0 until 4) for (value in values!![band]) {
                    for (mask in masks) {
                        checks.tick()
                        val bucket = index[band][value xor mask] ?: continue
                        for (previous in bucket) {
                            checks.tick()
                            candidates.set(previous)
                        }
                    }
                }
            }
            var previous = candidates.nextSetBit(0)
            while (previous >= 0) {
                checkActive()
                comparisons++
                val matches = match(prepared[previous], prepared[book], checks)
                if (matches > 0) onMatch(previous, book, matches)
                previous = candidates.nextSetBit(previous + 1)
            }
            if (index != null) {
                for (band in 0 until 4) for (value in values!![band]) {
                    checks.tick()
                    index[band].getOrPut(value) { ArrayList() }.add(book)
                }
            }
            progress(book + 1, prepared.size)
        }
        return comparisons
    }

    private class Checks(private val checkActive: () -> Unit) {
        private var remaining = 256
        fun tick() {
            if (--remaining == 0) {
                remaining = 256
                checkActive()
            }
        }
    }

    companion object {
        private val masks = buildList {
            add(0)
            for (a in 0 until 16) {
                add(1 shl a)
                for (b in a + 1 until 16) add((1 shl a) or (1 shl b))
            }
        }

        internal fun count(a: List<Long>, b: List<Long>): Int =
            match(clusters(a), clusters(b), Checks {})

        private fun close(a: Long, b: Long) = java.lang.Long.bitCount(a xor b) <= 10

        private fun clusters(hashes: List<Long>): List<List<Long>> {
            val groups = ArrayList<List<Long>>()
            for (hash in hashes.distinct()) {
                val merged = ArrayList<Long>()
                val iterator = groups.iterator()
                while (iterator.hasNext()) {
                    val group = iterator.next()
                    if (group.any { close(it, hash) }) {
                        merged.addAll(group)
                        iterator.remove()
                    }
                }
                merged.add(hash)
                groups.add(merged)
            }
            return groups
        }

        private fun match(left: List<List<Long>>, right: List<List<Long>>, checks: Checks): Int {
            // Build edges once so augmenting paths do not repeat hash comparisons.
            val edges = left.map { group ->
                right.indices.filter { j ->
                    checks.tick()
                    group.any { a ->
                        right[j].any { b ->
                            checks.tick()
                            close(a, b)
                        }
                    }
                }
            }
            val matched = IntArray(right.size) { -1 }
            fun augment(i: Int, visited: BooleanArray): Boolean {
                checks.tick()
                for (j in edges[i]) {
                    checks.tick()
                    if (visited[j]) continue
                    visited[j] = true
                    if (matched[j] == -1 || augment(matched[j], visited)) {
                        matched[j] = i
                        return true
                    }
                }
                return false
            }
            return left.indices.count { augment(it, BooleanArray(right.size)) }
        }
    }
}
