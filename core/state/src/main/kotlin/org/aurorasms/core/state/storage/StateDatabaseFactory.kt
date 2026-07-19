// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import android.content.Context
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.CancellationException

enum class StateDatabaseOpenFailureReason {
    INCOMPATIBLE_SCHEMA,
    UNREADABLE_OR_CORRUPT,
    STORAGE_UNAVAILABLE,
}

enum class StateDatabaseRecoveryAction {
    PRESERVE_DATABASE_AND_REQUIRE_EXPLICIT_RECOVERY,
}

sealed interface StateDatabaseOpenResult {
    class Opened(val database: AuroraStateDatabase) : StateDatabaseOpenResult {
        override fun toString(): String = "StateDatabaseOpenResult.Opened(REDACTED)"
    }

    data class Failed(
        val reason: StateDatabaseOpenFailureReason,
        val recoveryAction: StateDatabaseRecoveryAction =
            StateDatabaseRecoveryAction.PRESERVE_DATABASE_AND_REQUIRE_EXPLICIT_RECOVERY,
    ) : StateDatabaseOpenResult
}

object StateDatabaseFactory {
    const val DATABASE_NAME: String = "aurora_state.db"

    /** Opens and validates durable state without ever deleting or replacing it. */
    fun open(context: Context): StateDatabaseOpenResult {
        val database = Room.databaseBuilder(
            context.applicationContext,
            AuroraStateDatabase::class.java,
            DATABASE_NAME,
        )
            .openHelperFactory(NonDeletingStateOpenHelperFactory)
            .addMigrations(
                STATE_MIGRATION_1_2,
                STATE_MIGRATION_2_3,
                STATE_MIGRATION_3_4,
                STATE_MIGRATION_4_5,
                STATE_MIGRATION_5_6,
            )
            .addCallback(DraftIdentityEnforcement.callback)
            .addCallback(AppearanceSelectionEnforcement.callback)
            .addCallback(AppearanceOverrideSequenceEnforcement.callback)
            .addCallback(ComposerSmsOperationEnforcement.callback)
            .addCallback(AcknowledgedComposerSmsEnforcement.callback)
            .build()
        return try {
            database.openHelper.writableDatabase
            StateDatabaseOpenResult.Opened(database)
        } catch (cancellation: CancellationException) {
            database.close()
            throw cancellation
        } catch (failure: SQLiteException) {
            database.close()
            StateDatabaseOpenResult.Failed(failure.toStateOpenFailureReason())
        } catch (_: SecurityException) {
            database.close()
            StateDatabaseOpenResult.Failed(StateDatabaseOpenFailureReason.STORAGE_UNAVAILABLE)
        } catch (_: IllegalArgumentException) {
            database.close()
            StateDatabaseOpenResult.Failed(StateDatabaseOpenFailureReason.INCOMPATIBLE_SCHEMA)
        } catch (_: IllegalStateException) {
            database.close()
            StateDatabaseOpenResult.Failed(StateDatabaseOpenFailureReason.INCOMPATIBLE_SCHEMA)
        }
    }

    fun close(database: AuroraStateDatabase) {
        database.close()
    }
}

/** Durable state corruption is reported to Aurora and never delegated to auto-deletion. */
private object NonDeletingStateOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
    private val delegate = FrameworkSQLiteOpenHelperFactory()

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val callback = configuration.callback
        val nonDeletingCallback = object : SupportSQLiteOpenHelper.Callback(callback.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = callback.onConfigure(db)

            override fun onCreate(db: SupportSQLiteDatabase) = callback.onCreate(db)

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                callback.onUpgrade(db, oldVersion, newVersion)

            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                callback.onDowngrade(db, oldVersion, newVersion)

            override fun onOpen(db: SupportSQLiteDatabase) = callback.onOpen(db)

            override fun onCorruption(db: SupportSQLiteDatabase) {
                throw SQLiteDatabaseCorruptException("Durable state corruption reported")
            }
        }
        val controlledConfiguration = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name)
            .callback(nonDeletingCallback)
            .noBackupDirectory(configuration.useNoBackupDirectory)
            .allowDataLossOnRecovery(false)
            .build()
        return delegate.create(controlledConfiguration)
    }
}

private fun SQLiteException.toStateOpenFailureReason(): StateDatabaseOpenFailureReason {
    val failures = generateSequence<Throwable>(this) { it.cause }.toList()
    return if (
        failures.any {
            it is SQLiteFullException ||
                it is SQLiteDiskIOException ||
                it is SQLiteCantOpenDatabaseException
        }
    ) {
        StateDatabaseOpenFailureReason.STORAGE_UNAVAILABLE
    } else {
        StateDatabaseOpenFailureReason.UNREADABLE_OR_CORRUPT
    }
}
