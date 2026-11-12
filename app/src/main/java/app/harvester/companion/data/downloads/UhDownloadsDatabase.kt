package app.harvester.companion.data.downloads

import android.content.Context
import android.os.Environment
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import mozilla.components.browser.state.state.content.DownloadState

@Database(entities = [UhDownloadEntity::class], version = 6)
@TypeConverters(UhStatusConverter::class)
internal abstract class UhDownloadsDatabase : RoomDatabase() {
    abstract fun downloadDao(): UhDownloadDao

    companion object {
        @Volatile private var instance: UhDownloadsDatabase? = null

        @Synchronized
        fun get(context: Context): UhDownloadsDatabase {
            instance?.let { return it }

            return Room.databaseBuilder(
                context,
                UhDownloadsDatabase::class.java,
                "uh_downloads_database",
            )
            .addMigrations(
                UhMigrations.migration_1_2,
                UhMigrations.migration_2_3,
                UhMigrations.migration_3_4,
                UhMigrations.migration_4_5,
                UhMigrations.migration_5_6,
            )
            .fallbackToDestructiveMigration()
            .build()
            .also { instance = it }
        }
    }
}

@Suppress("MaxLineLength", "MagicNumber")
internal object UhMigrations {
    val migration_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN is_private INTEGER NOT NULL DEFAULT 0")
        }
    }
    
    val migration_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE temp_downloads (`id` TEXT NOT NULL, `url` TEXT NOT NULL, `file_name` TEXT, `content_type` TEXT, `content_length` INTEGER, `status` INTEGER NOT NULL, `destination_directory` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("INSERT INTO temp_downloads (id,url,file_name,content_type,content_length,status,destination_directory,created_at) SELECT id,url,file_name,content_type,content_length,status,destination_directory,created_at FROM downloads where is_private = 0")
            db.execSQL("DROP TABLE downloads")
            db.execSQL("ALTER TABLE temp_downloads RENAME TO downloads")
        }
    }

    val migration_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE downloads SET url='' WHERE url LIKE 'data:%' ")
        }
    }

    val migration_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN etag TEXT")
        }
    }
    
    val migration_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val publicDownloadsPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
            db.execSQL("CREATE TABLE downloads_new ( `id` TEXT NOT NULL, `url` TEXT NOT NULL, `file_name` TEXT, `content_type` TEXT, `content_length` INTEGER, `status` INTEGER NOT NULL, `directory_path` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `etag` TEXT, PRIMARY KEY(`id`))")
            db.execSQL("INSERT INTO downloads_new (id, url, file_name, content_type, content_length, status, directory_path, created_at, etag) SELECT id, url, file_name, content_type, content_length, status, '$publicDownloadsPath', created_at, etag FROM downloads")
            db.execSQL("DROP TABLE downloads")
            db.execSQL("ALTER TABLE downloads_new RENAME TO downloads")
        }
    }
}

@Suppress("unused")
internal class UhStatusConverter {
    private val statusArray = DownloadState.Status.entries.toTypedArray()

    @TypeConverter
    fun toInt(status: DownloadState.Status): Int {
        return status.id
    }

    @TypeConverter
    fun toStatus(index: Int): DownloadState.Status? {
        return statusArray.find { it.id == index }
    }
}
