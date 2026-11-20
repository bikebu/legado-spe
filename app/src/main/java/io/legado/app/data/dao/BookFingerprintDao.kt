package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.BookFingerprint

@Dao
interface BookFingerprintDao {

    @Query("select * from book_fingerprints where bookUrl = :bookUrl")
    fun get(bookUrl: String): BookFingerprint?

    @Query("select * from book_fingerprints where bookUrl in (:bookUrls)")
    fun getByBookUrls(bookUrls: List<String>): List<BookFingerprint>

    @get:Query("select * from book_fingerprints")
    val all: List<BookFingerprint>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg fingerprint: BookFingerprint)

    @Query("delete from book_fingerprints where bookUrl = :bookUrl")
    fun delete(bookUrl: String)

}
