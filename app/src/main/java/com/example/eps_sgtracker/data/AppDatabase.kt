package com.example.eps_sgtracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// exportSchema = true writes the schema JSON to app/schemas (see the room.schemaLocation KSP arg
// in build.gradle.kts). It has to be on BEFORE the first production release: once real users hold
// a v1 database, bumping to v2 without a Migration throws at runtime for every one of them, and
// without an exported v1 schema there is no way to author or test that migration afterwards.
@Database(entities = [TleEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tleDao(): TleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            // Double-checked locking needs the second check INSIDE the lock: without it, two
            // threads that both observe a null INSTANCE each build a separate RoomDatabase over
            // the same file, and the loser is left holding an orphaned instance with its own
            // open connection. Only one call site exists today, but the invariant should hold.
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tracker_database"
                )
                    // This table is a pure TLE cache - every row is re-fetchable from CelesTrak,
                    // and checkAndRefreshIfExpired refetches anything over 48h old anyway. A
                    // destructive fallback therefore costs the user nothing beyond one refetch,
                    // which is a far better outcome than a crash on a schema bump.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
