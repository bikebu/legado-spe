package io.legado.app.help.book

/** Pure retention/dependency policy. Sequence is commit order, even if the clock moves back. */
internal object BookMergeHistoryPolicy {
    const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000

    fun isExpired(createdAt: Long, now: Long): Boolean =
        now >= createdAt && now.toULong() - createdAt.toULong() >= RETENTION_MILLIS.toULong()

    fun blocksRestore(
        sequence: Long,
        bookUrls: Collection<String>,
        bookNames: Collection<String>,
        otherSequence: Long,
        otherBookUrls: Collection<String>,
        otherBookNames: Collection<String>
    ): Boolean = otherSequence > sequence &&
        (bookUrls.any { it in otherBookUrls } || bookNames.any { it in otherBookNames })
}
