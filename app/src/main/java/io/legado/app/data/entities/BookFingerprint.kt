package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * 书籍正文指纹，用于书架去重
 * 只根据已缓存的正文计算，书籍删除时自动删除
 */
@Entity(
    tableName = "book_fingerprints",
    foreignKeys = [(ForeignKey(
        entity = Book::class,
        parentColumns = ["bookUrl"],
        childColumns = ["bookUrl"],
        onDelete = ForeignKey.CASCADE
    ))]
)
data class BookFingerprint(
    @PrimaryKey
    @ColumnInfo(defaultValue = "")
    var bookUrl: String = "",
    // 算法版本及实际采样正文的摘要；同一章节地址的正文更新也会失效
    @ColumnInfo(defaultValue = "")
    var sourceKey: String = "",
    // 每个采样章节的SimHash，逗号分隔，为空表示没有足够的缓存正文
    @ColumnInfo(defaultValue = "")
    var chapterHashes: String = "",
    @ColumnInfo(defaultValue = "0")
    var updateTime: Long = System.currentTimeMillis()
) {

    fun hashList(): List<Long> {
        if (chapterHashes.isEmpty()) return emptyList()
        return chapterHashes.split(',').mapNotNull { it.toLongOrNull() }
    }

}
