package tw.pentamaster.bizcard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [BusinessCard::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {

    abstract fun cardDao(): CardDao

    companion object {
        /**
         * v0.4 adds bilingual name/company columns without rewriting any existing row.
         * Existing `name` / `company` values remain the local-language fields; the new
         * English fields start empty and can be filled by OCR or manual editing.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN nameEn TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE cards ADD COLUMN companyEn TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bizcard.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
