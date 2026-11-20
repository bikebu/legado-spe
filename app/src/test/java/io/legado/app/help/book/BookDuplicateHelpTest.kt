package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookDuplicateHelpTest {
    @Test
    fun fingerprintInvalidatesWhenContentChangesAtSameUrl() {
        val original = BookDuplicateHelp.fingerprintSourceKey(listOf("chapter-1" to "original"))
        val updated = BookDuplicateHelp.fingerprintSourceKey(listOf("chapter-1" to "updated"))
        assertNotEquals(original, updated)
    }

    @Test
    fun fingerprintInvalidatesWhenEmptySampleBecomesAvailable() {
        assertNotEquals(
            BookDuplicateHelp.fingerprintSourceKey(emptyList()),
            BookDuplicateHelp.fingerprintSourceKey(listOf("chapter-1" to "now cached"))
        )
    }

    @Test
    fun fingerprintVersionInvalidatesUnchangedSamples() {
        val samples = listOf("chapter-1" to "same content")
        assertNotEquals(
            BookDuplicateHelp.fingerprintSourceKey(samples, 1),
            BookDuplicateHelp.fingerprintSourceKey(samples, 2)
        )
        assertEquals(
            BookDuplicateHelp.fingerprintSourceKey(samples, 2),
            BookDuplicateHelp.fingerprintSourceKey(samples.toList(), 2)
        )
    }

    @Test
    fun oneChapterCannotSupportMultipleMatches() {
        assertEquals(1, BookDuplicateHelp.contentMatchCount(listOf(0L, 1L), listOf(0L)))
        assertEquals(1, BookDuplicateHelp.contentMatchCount(listOf(0L, 0L), listOf(0L, 0L)))
    }

    @Test
    fun independentMatchesAreFoundEvenWhenGreedyMatchingWouldFail() {
        // 左边 0 可匹配右边两章，1048575 仅能匹配右边的 1023。
        // 同书内两份采样距离均为 20，不会被聚为重复正文。
        assertEquals(2, BookDuplicateHelp.contentMatchCount(
            listOf(0L, 1048575L), listOf(1023L, 1072693248L)
        ))
    }

    @Test
    fun nearDuplicateSamplesCannotIncreaseConfidence() {
        assertEquals(1, BookDuplicateHelp.contentMatchCount(listOf(0L, 1L), listOf(0L, 1L)))
        // 0 与 1048575 距离为 20，但中间样本分别与两者相似，整体视为一个簇。
        val chain = listOf(0L, 1048575L, 1023L)
        assertEquals(1, BookDuplicateHelp.contentMatchCount(chain, chain.reversed()))
    }

    @Test
    fun contentDistanceBoundaryAndEmptySamples() {
        assertEquals(1, BookDuplicateHelp.contentMatchCount(listOf(0L), listOf(1023L)))
        assertEquals(0, BookDuplicateHelp.contentMatchCount(listOf(0L), listOf(2047L)))
        assertEquals(0, BookDuplicateHelp.contentMatchCount(emptyList(), listOf(0L)))
        assertEquals(0, BookDuplicateHelp.contentMatchCount(listOf(0L), emptyList()))
        val hashes = listOf(0L, -1L, 0xffffffffL)
        assertEquals(3, BookDuplicateHelp.contentMatchCount(hashes, hashes.reversed()))
    }

    @Test
    fun firstChapterCanAdvanceWhenContentMatches() {
        assertEquals(800, BookDuplicateHelp.mergedChapterPosition(0, 100, 0, 800, true))
    }

    @Test
    fun sameChapterNeverMovesBackwards() {
        assertNull(BookDuplicateHelp.mergedChapterPosition(12, 800, 12, 100, true))
        assertNull(BookDuplicateHelp.mergedChapterPosition(12, 800, 12, 800, true))
    }

    @Test
    fun differentContentCannotOverwritePositionInSameChapter() {
        assertNull(BookDuplicateHelp.mergedChapterPosition(12, 100, 12, 800, false))
    }

    @Test
    fun laterChapterWithDifferentContentStartsAtBeginning() {
        assertEquals(0, BookDuplicateHelp.mergedChapterPosition(12, 100, 15, 800, false))
        assertEquals(800, BookDuplicateHelp.mergedChapterPosition(12, 100, 15, 800, true))
    }

    @Test
    fun earlierChapterNeverReplacesProgress() {
        assertNull(BookDuplicateHelp.mergedChapterPosition(12, 100, 10, 800, true))
    }
}
