package io.legado.app.help.book

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DuplicateContentMatcherTest {
    @Test
    fun boundariesRepeatedHashesTransitiveClustersAndAugmentingPaths() {
        assertEquals(0, DuplicateContentMatcher.count(emptyList(), listOf(0L)))
        assertEquals(0, DuplicateContentMatcher.count(listOf(0L), emptyList()))
        assertEquals(1, DuplicateContentMatcher.count(listOf(0L), listOf(1023L)))
        assertEquals(0, DuplicateContentMatcher.count(listOf(0L), listOf(2047L)))
        assertEquals(1, DuplicateContentMatcher.count(listOf(0L, 0L), listOf(0L, 0L)))
        // The last hash joins two previously separate groups; endpoints are distance 20.
        val chain = listOf(0L, 1048575L, 1023L, 0L)
        assertEquals(1, DuplicateContentMatcher.count(chain, chain.reversed()))
        // Left 0 has two choices, but left 1048575 can only use right 1023.
        assertEquals(2, DuplicateContentMatcher.count(
            listOf(0L, 1048575L), listOf(1023L, 1072693248L)
        ))
        val independent = listOf(0L, -1L, 0xffffffffL)
        assertEquals(3, DuplicateContentMatcher.count(independent, independent.reversed()))
    }

    @Test
    fun indexedBoundaryChangesEveryPartitionAndHandlesSignBit() {
        // No unchanged partition exists. Distance 10 is split as 3, 3, 2, 2.
        val delta = 7L or (7L shl 16) or (3L shl 32) or (3L shl 48)
        assertEquals(10, java.lang.Long.bitCount(delta))
        val books = MutableList(64) { emptyList<Long>() }
        val base = Long.MIN_VALUE
        books[0] = listOf(base, base)
        books[1] = listOf(base xor delta)
        books[2] = listOf(base xor delta xor (1L shl 50))
        val actual = collect(books).first
        assertEquals(1, actual[0 to 1])
        assertTrue((0 to 2) !in actual)
        assertEquals(brute(books), actual)
    }

    @Test
    fun deterministicRandomIndexedAndSmallInputsEqualIndependentReference() {
        val random = Random(7319L)
        for (size in listOf(0, 1, 12, 63, 64, 95, 140)) {
            val anchors = List(12) { random.nextLong() }
            val books = List(size) { book ->
                if (book % 13 == 0) emptyList() else buildList {
                    repeat(1 + random.nextInt(5)) {
                        val anchor = anchors[random.nextInt(anchors.size)]
                        var hash = anchor
                        repeat(random.nextInt(12)) {
                            hash = hash xor (1L shl random.nextInt(64))
                        }
                        add(hash)
                        if (random.nextBoolean()) add(hash)
                    }
                }
            }
            val (actual, comparisons) = collect(books)
            assertEquals("book count $size", brute(books), actual)
            val exhaustive = size.toLong() * (size - 1) / 2
            if (size < 64) assertEquals(exhaustive, comparisons)
            else assertTrue(comparisons <= exhaustive)
        }
    }

    @Test
    fun indexedMatchingRetainsClusterChainsAndAugmentingPaths() {
        val books = MutableList(64) { emptyList<Long>() }
        books[0] = listOf(0L, 1048575L)
        books[1] = listOf(1023L, 1072693248L)
        books[2] = listOf(0L, 1048575L, 1023L, 1023L)
        books[3] = books[2].reversed()
        val actual = collect(books).first
        assertEquals(2, actual[0 to 1])
        assertEquals(1, actual[2 to 3])
        assertEquals(brute(books), actual)
    }

    @Test
    fun thousandRandomBooksSubstantiallyReduceExactComparisons() {
        val random = Random(983451L)
        val books = List(1000) { List(3) { random.nextLong() } }
        val (actual, comparisons) = collect(books)
        val exhaustive = books.size.toLong() * (books.size - 1) / 2
        assertEquals(499500L, exhaustive)
        assertEquals(brute(books), actual)
        // Broad operation-count assertion, independent of machine speed.
        assertTrue("$comparisons exact comparisons out of $exhaustive", comparisons < exhaustive / 4)
    }

    @Test
    fun denseBucketsDeduplicateCandidatesAndProgressIsPerBook() {
        val books = List(80) { listOf(0L, 0L, 1L, 1L) }
        val matcher = DuplicateContentMatcher(books)
        repeat(2) {
            val progress = ArrayList<Pair<Int, Int>>()
            val seen = HashSet<Pair<Int, Int>>()
            val comparisons = matcher.forEachMatch(progress = { done, total ->
                progress.add(done to total)
            }) { a, b, count ->
                assertTrue(a < b)
                assertTrue(seen.add(a to b))
                assertEquals(1, count)
            }
            assertEquals(3160L, comparisons)
            assertEquals(3160, seen.size)
            assertEquals((1..80).map { it to 80 }, progress)
        }
    }

    @Test
    fun cancellationPropagatesDuringDenseBucketScan() {
        val matcher = DuplicateContentMatcher(List(1000) { listOf(0L) })
        var armed = false
        var checks = 0
        val cancelled = Cancellation()
        try {
            matcher.forEachMatch(checkActive = {
                if (armed && ++checks == 2) throw cancelled
            }, progress = { done, _ ->
                if (done == 900) armed = true
            }) { _, later, _ ->
                assertTrue("Must cancel before comparing book 900", later < 900)
            }
            fail("Expected cancellation inside a dense bucket")
        } catch (exception: Cancellation) {
            assertTrue(exception === cancelled)
            assertEquals(2, checks)
        }
    }

    @Test
    fun cancellationPropagatesInsideExactComparison() {
        val random = Random(712L)
        val matcher = DuplicateContentMatcher(List(2) { List(80) { random.nextLong() } })
        var armed = false
        var checks = 0
        val cancelled = Cancellation()
        try {
            matcher.forEachMatch(checkActive = {
                // Book-start and pair-start checks precede the periodic exact-work check.
                if (armed && ++checks == 3) throw cancelled
            }, progress = { done, _ -> if (done == 1) armed = true }) { _, _, _ ->
                fail("Comparison should not complete")
            }
            fail("Expected cancellation inside an exact comparison")
        } catch (exception: Cancellation) {
            assertTrue(exception === cancelled)
            assertEquals(3, checks)
        }
    }

    private class Cancellation : RuntimeException()

    private fun collect(books: List<List<Long>>): Pair<Map<Pair<Int, Int>, Int>, Long> {
        val result = LinkedHashMap<Pair<Int, Int>, Int>()
        val comparisons = DuplicateContentMatcher(books).forEachMatch { a, b, count ->
            assertTrue(a < b)
            assertTrue(count > 0)
            assertTrue(result.put(a to b, count) == null)
        }
        return result to comparisons
    }

    private fun brute(books: List<List<Long>>): Map<Pair<Int, Int>, Int> {
        val result = LinkedHashMap<Pair<Int, Int>, Int>()
        for (b in books.indices) for (a in 0 until b) {
            val count = referenceCount(books[a], books[b])
            if (count > 0) result[a to b] = count
        }
        return result
    }

    // Original second-round semantics, kept independent of the production matcher.
    private fun referenceCount(a: List<Long>, b: List<Long>): Int {
        fun close(x: Long, y: Long) = java.lang.Long.bitCount(x xor y) <= 10
        fun clusters(hashes: List<Long>): List<List<Long>> {
            val groups = arrayListOf<List<Long>>()
            hashes.distinct().forEach { hash ->
                val touching = groups.filter { group -> group.any { close(it, hash) } }
                groups.removeAll(touching.toSet())
                groups.add(touching.flatten() + hash)
            }
            return groups
        }
        val left = clusters(a)
        val right = clusters(b)
        val matched = IntArray(right.size) { -1 }
        fun augment(i: Int, visited: BooleanArray): Boolean {
            for (j in right.indices) {
                if (visited[j] || left[i].none { x -> right[j].any { close(x, it) } }) continue
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
