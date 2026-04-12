package com.abook.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.abook.data.db.entity.BookEntity
import com.abook.data.db.entity.ChapterEntity
import com.abook.data.db.entity.ReadingPositionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY lastOpenedAt DESC, addedAt DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT id FROM books WHERE lastOpenedAt IS NOT NULL ORDER BY lastOpenedAt DESC LIMIT 1")
    fun getLastOpenedBookId(): Flow<String?>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBook(bookId: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBook(bookId: String)

    @Query("UPDATE books SET lastOpenedAt = :time WHERE id = :bookId")
    suspend fun updateLastOpened(bookId: String, time: Long)

    // Chapters
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY `index`")
    suspend fun getChapters(bookId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND `index` = :index")
    suspend fun getChapter(bookId: String, index: Int): ChapterEntity?

    // Reading positions
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePosition(position: ReadingPositionEntity)

    @Query("SELECT * FROM reading_positions WHERE bookId = :bookId")
    suspend fun getPosition(bookId: String): ReadingPositionEntity?
}
