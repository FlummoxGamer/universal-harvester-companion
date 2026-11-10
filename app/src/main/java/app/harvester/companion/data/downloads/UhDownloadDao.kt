package app.harvester.companion.data.downloads

import androidx.paging.DataSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface UhDownloadDao {

    @Insert suspend fun insert(entity: UhDownloadEntity): Long

    @Update suspend fun update(entity: UhDownloadEntity)

    @Query("SELECT * FROM downloads ORDER BY created_at DESC") fun getDownloads(): Flow<List<UhDownloadEntity>>

    @Query("SELECT * FROM downloads ORDER BY created_at DESC") suspend fun getDownloadsList(): List<UhDownloadEntity>

    @Delete suspend fun delete(entity: UhDownloadEntity)

    @Query("DELETE FROM downloads") suspend fun deleteAllDownloads()

    @Query("SELECT * FROM downloads ORDER BY created_at DESC")
    fun getDownloadsPaged(): DataSource.Factory<Int, UhDownloadEntity>
}
