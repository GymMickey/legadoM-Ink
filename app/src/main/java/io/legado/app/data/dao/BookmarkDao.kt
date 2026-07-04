package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.Bookmark
import kotlinx.coroutines.flow.Flow

/**
 * 书签数据访问接口
 */
@Dao
interface BookmarkDao {

    @get:Query(
        """
        select * from bookmarks order by bookName collate localized, bookAuthor collate localized, chapterIndex, chapterPos
    """
    )
    val all: List<Bookmark>

    @get:Query("select count(*) from bookmarks")
    val count: Int

    @Query("select * from bookmarks order by bookName collate localized, bookAuthor collate localized, chapterIndex, chapterPos")
    fun flowAll(): Flow<List<Bookmark>>

    @Query(
        """SELECT * FROM bookmarks 
        where bookName like '%'||:key||'%' or bookAuthor like '%'||:key||'%'
        or chapterName like '%'||:key||'%' or bookText like '%'||:key||'%'
        or content like '%'||:key||'%'
        order by bookName collate localized, bookAuthor collate localized, chapterIndex, chapterPos"""
    )
    fun flowSearchAll(key: String): Flow<List<Bookmark>>

    @Query(
        """select * from bookmarks 
        where bookName = :bookName and bookAuthor = :bookAuthor 
        order by chapterIndex"""
    )
    fun flowByBook(bookName: String, bookAuthor: String): Flow<List<Bookmark>>

    @Query(
        """SELECT * FROM bookmarks 
        where bookName = :bookName and bookAuthor = :bookAuthor 
        and (chapterName like '%'||:key||'%' or content like '%'||:key||'%')
        order by chapterIndex"""
    )
    fun flowSearch(bookName: String, bookAuthor: String, key: String): Flow<List<Bookmark>>

    @Query(
        """select * from bookmarks 
        where bookName = :bookName and bookAuthor = :bookAuthor 
        order by chapterIndex"""
    )
    fun getByBook(bookName: String, bookAuthor: String): List<Bookmark>

    @Query(
        """SELECT * FROM bookmarks 
        where bookName = :bookName and bookAuthor = :bookAuthor 
        and (chapterName like '%'||:key||'%' or content like '%'||:key||'%')
        order by chapterIndex"""
    )
    fun search(bookName: String, bookAuthor: String, key: String): List<Bookmark>

    @Query(
        """SELECT * FROM bookmarks 
        where bookName = :bookName and bookAuthor = :bookAuthor 
        and chapterName like '%'||:key||'%'
        order by chapterIndex"""
    )
    fun flowSearchChapter(bookName: String, bookAuthor: String, key: String): Flow<List<Bookmark>>

    @Query(
        """SELECT * FROM bookmarks 
        where bookName = :bookName and bookAuthor = :bookAuthor 
        and bookText like '%'||:key||'%'
        order by chapterIndex"""
    )
    fun flowSearchBookText(bookName: String, bookAuthor: String, key: String): Flow<List<Bookmark>>

    @Query(
        """SELECT * FROM bookmarks 
        where bookName = :bookName and bookAuthor = :bookAuthor 
        and content like '%'||:key||'%'
        order by chapterIndex"""
    )
    fun flowSearchContent(bookName: String, bookAuthor: String, key: String): Flow<List<Bookmark>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg bookmark: Bookmark)

    @Update
    fun update(bookmark: Bookmark)

    @Delete
    fun delete(vararg bookmark: Bookmark)

    @Query("delete from bookmarks")
    fun deleteAll()

}
