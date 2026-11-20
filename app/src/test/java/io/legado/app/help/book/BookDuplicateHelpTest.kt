package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookDuplicateHelpTest {
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
