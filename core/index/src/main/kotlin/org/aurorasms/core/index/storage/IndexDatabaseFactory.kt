// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.storage

import android.content.Context
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

object IndexDatabaseFactory {
    const val DATABASE_NAME: String = "aurora_index.db"

    fun create(context: Context): AuroraIndexDatabase = Room.databaseBuilder(
        context.applicationContext,
        AuroraIndexDatabase::class.java,
        DATABASE_NAME,
    ).openHelperFactory(ControlledIndexOpenHelperFactory)
        .addCallback(IndexFtsTriggerPolicy.callback)
        .build()

    fun createInMemory(context: Context): AuroraIndexDatabase = Room.inMemoryDatabaseBuilder(
        context.applicationContext,
        AuroraIndexDatabase::class.java,
    ).addCallback(IndexFtsTriggerPolicy.callback).build()

    fun close(database: AuroraIndexDatabase) {
        database.close()
    }

    /** Opens and verifies the disposable index, rebuilding only its known file once if needed. */
    fun open(context: Context): IndexDatabaseOpenResult {
        val initial = create(context)
        return try {
            verifyIntegrity(initial)
            IndexDatabaseOpenResult.Opened(initial, recovered = false)
        } catch (failure: RuntimeException) {
            if (!failure.isRebuildableIndexFailure(context)) {
                initial.close()
                return IndexDatabaseOpenResult.Failed(IndexDatabaseOpenFailureReason.OPEN_FAILED)
            }
            val rebuilt = try {
                recoverKnownIndex(context, initial)
            } catch (_: RuntimeException) {
                return IndexDatabaseOpenResult.Failed(IndexDatabaseOpenFailureReason.RECOVERY_FAILED)
            }
            try {
                verifyIntegrity(rebuilt)
                IndexDatabaseOpenResult.Opened(rebuilt, recovered = true)
            } catch (_: RuntimeException) {
                rebuilt.close()
                IndexDatabaseOpenResult.Failed(IndexDatabaseOpenFailureReason.RECOVERY_FAILED)
            }
        }
    }

    /** Closes and deletes only Aurora's rebuildable index; durable state is never addressed here. */
    fun recoverKnownIndex(
        context: Context,
        openDatabase: AuroraIndexDatabase,
    ): AuroraIndexDatabase {
        val appContext = context.applicationContext
        val expected = appContext.getDatabasePath(DATABASE_NAME).canonicalFile
        val databaseDirectory = requireNotNull(expected.parentFile).canonicalFile
        val appDatabaseDirectory = requireNotNull(appContext.getDatabasePath("boundary").parentFile).canonicalFile
        require(expected.name == DATABASE_NAME && databaseDirectory == appDatabaseDirectory) {
            "Index recovery target is outside the private database boundary"
        }
        openDatabase.close()
        check(appContext.deleteDatabase(DATABASE_NAME) || !expected.exists()) {
            "The known index database could not be removed"
        }
        return create(appContext)
    }

    private fun verifyIntegrity(database: AuroraIndexDatabase) {
        val sqlite = database.openHelper.writableDatabase
        sqlite.query("PRAGMA quick_check(1)").use { cursor ->
            if (!cursor.moveToFirst() || cursor.getString(0) != "ok" || cursor.moveToNext()) {
                throw SQLiteDatabaseCorruptException("Disposable index integrity check failed")
            }
        }
    }
}

enum class IndexDatabaseOpenFailureReason {
    OPEN_FAILED,
    RECOVERY_FAILED,
}

sealed interface IndexDatabaseOpenResult {
    data class Opened(
        val database: AuroraIndexDatabase,
        val recovered: Boolean,
    ) : IndexDatabaseOpenResult {
        override fun toString(): String = "IndexDatabaseOpenResult.Opened(recovered=$recovered)"
    }

    data class Failed(val reason: IndexDatabaseOpenFailureReason) : IndexDatabaseOpenResult
}

private fun RuntimeException.isRebuildableIndexFailure(context: Context): Boolean {
    val failures = generateSequence<Throwable>(this) { it.cause }.toList()
    if (failures.any { it is SQLiteFullException || it is SQLiteDiskIOException }) return false
    if (failures.any { it is SQLiteCantOpenDatabaseException || it is SecurityException }) return false
    return failures.any { it is SQLiteDatabaseCorruptException } ||
        failures.any { it is IllegalStateException } ||
        context.knownIndexHasInvalidHeader()
}

private fun Context.knownIndexHasInvalidHeader(): Boolean = try {
    val path = applicationContext.getDatabasePath(IndexDatabaseFactory.DATABASE_NAME)
    if (!path.isFile || path.length() == 0L) return false
    val expected = "SQLite format 3\u0000".encodeToByteArray()
    val actual = ByteArray(expected.size)
    val bytesRead = path.inputStream().use { stream -> stream.read(actual) }
    bytesRead != expected.size || !actual.contentEquals(expected)
} catch (_: Exception) {
    false
}

/** Prevents the framework callback from deleting bytes before Aurora classifies the failure. */
private object ControlledIndexOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
    private val delegate = FrameworkSQLiteOpenHelperFactory()

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val callback = configuration.callback
        val controlledCallback = object : SupportSQLiteOpenHelper.Callback(callback.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = callback.onConfigure(db)

            override fun onCreate(db: SupportSQLiteDatabase) = callback.onCreate(db)

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                callback.onUpgrade(db, oldVersion, newVersion)

            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                callback.onDowngrade(db, oldVersion, newVersion)

            override fun onOpen(db: SupportSQLiteDatabase) = callback.onOpen(db)

            override fun onCorruption(db: SupportSQLiteDatabase) {
                throw SQLiteDatabaseCorruptException("Disposable index corruption reported")
            }
        }
        val controlledConfiguration = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name)
            .callback(controlledCallback)
            .noBackupDirectory(configuration.useNoBackupDirectory)
            .allowDataLossOnRecovery(false)
            .build()
        return delegate.create(controlledConfiguration)
    }
}

/**
 * Room's default external-content triggers rebuild FTS on every content-row update. Replace only
 * the update pair so advancing last_seen_generation for an unchanged fingerprint cannot churn FTS.
 */
private object IndexFtsTriggerPolicy {
    val callback: RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            install(db)
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            install(db)
        }
    }

    private fun install(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_indexed_messages_fts_BEFORE_UPDATE")
        db.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_indexed_messages_fts_AFTER_UPDATE")
        db.execSQL(
            """
            CREATE TRIGGER room_fts_content_sync_indexed_messages_fts_BEFORE_UPDATE
            BEFORE UPDATE OF body, subject, searchable_text ON indexed_messages
            WHEN OLD.body IS NOT NEW.body
              OR OLD.subject IS NOT NEW.subject
              OR OLD.searchable_text IS NOT NEW.searchable_text
            BEGIN
              DELETE FROM indexed_messages_fts WHERE docid = OLD.row_id;
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER room_fts_content_sync_indexed_messages_fts_AFTER_UPDATE
            AFTER UPDATE OF body, subject, searchable_text ON indexed_messages
            WHEN OLD.body IS NOT NEW.body
              OR OLD.subject IS NOT NEW.subject
              OR OLD.searchable_text IS NOT NEW.searchable_text
            BEGIN
              INSERT INTO indexed_messages_fts(docid, body, subject, searchable_text)
              VALUES (NEW.row_id, NEW.body, NEW.subject, NEW.searchable_text);
            END
            """.trimIndent(),
        )
    }
}
