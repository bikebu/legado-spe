package io.legado.app.help.book

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookMergeHistoryPolicyTest {
    @Test
    fun retentionExpiresAtExactlySevenDays() {
        val created = 1_700_000_000_000L
        val deadline = created + BookMergeHistoryPolicy.RETENTION_MILLIS
        assertFalse(BookMergeHistoryPolicy.isExpired(created, deadline - 1))
        assertTrue(BookMergeHistoryPolicy.isExpired(created, deadline))
        assertTrue(BookMergeHistoryPolicy.isExpired(created, deadline + 1))
    }

    @Test
    fun clockMovingBackDoesNotExpireRecord() {
        assertFalse(BookMergeHistoryPolicy.isExpired(100L, 99L))
    }

    @Test
    fun retentionDoesNotOverflowAtLongLimits() {
        assertTrue(BookMergeHistoryPolicy.isExpired(Long.MIN_VALUE, Long.MAX_VALUE))
        assertFalse(BookMergeHistoryPolicy.isExpired(Long.MAX_VALUE, Long.MIN_VALUE))
    }

    @Test
    fun newerOverlappingUrlBlocksRestoreEvenWithDifferentNames() {
        assertTrue(blocks(1, listOf("a", "b"), listOf("first"), 2, listOf("b", "c"), listOf("second")))
    }

    @Test
    fun newerSharedNameBlocksRestoreEvenWithDifferentUrls() {
        assertTrue(blocks(1, listOf("a"), listOf("shared"), 2, listOf("b"), listOf("shared")))
    }

    @Test
    fun newerIndependentMergeDoesNotBlockRestore() {
        assertFalse(blocks(1, listOf("a"), listOf("first"), 2, listOf("b"), listOf("second")))
    }

    @Test
    fun olderOverlapDoesNotBlockNewerRestore() {
        assertFalse(blocks(2, listOf("a"), listOf("shared"), 1, listOf("a"), listOf("shared")))
    }

    @Test
    fun recordDoesNotBlockItself() {
        assertFalse(blocks(1, listOf("a"), listOf("shared"), 1, listOf("a"), listOf("shared")))
    }

    @Test
    fun sequenceDeterminesOrderRegardlessOfTimestampTiesOrClockChanges() {
        // No wall clock input is needed for dependency ordering.
        assertTrue(blocks(41, listOf("a"), emptyList(), 42, listOf("a"), emptyList()))
        assertFalse(blocks(42, listOf("a"), emptyList(), 41, listOf("a"), emptyList()))
    }

    private fun blocks(
        sequence: Long, urls: List<String>, names: List<String>,
        otherSequence: Long, otherUrls: List<String>, otherNames: List<String>
    ) = BookMergeHistoryPolicy.blocksRestore(sequence, urls, names, otherSequence, otherUrls, otherNames)
}
