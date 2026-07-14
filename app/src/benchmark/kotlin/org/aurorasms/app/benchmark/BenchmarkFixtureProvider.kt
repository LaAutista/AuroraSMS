// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.benchmark

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.aurorasms.app.AuroraSmsApplication
import org.aurorasms.core.index.storage.AuroraIndexDatabase
import org.aurorasms.core.index.storage.IndexCheckpointEntity
import org.aurorasms.core.index.storage.IndexDatabaseFactory
import org.aurorasms.core.index.storage.IndexedMessageEntity
import org.aurorasms.core.index.sync.IndexProjectionMapper
import org.aurorasms.core.index.sync.IndexedProviderProjection
import org.aurorasms.core.testing.SyntheticIndexFixtures
import org.aurorasms.core.testing.SyntheticIndexRecord
import org.aurorasms.core.testing.SyntheticIndexShape

/** Signature-protected, benchmark-variant-only control plane for synthetic index state. */
class BenchmarkFixtureProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (arg != null) return failure(FixtureError.UNEXPECTED_ARGUMENT)
        return when (method) {
            METHOD_SEED -> seed(extras)
            METHOD_CLEANUP -> cleanup(extras)
            else -> failure(FixtureError.UNKNOWN_METHOD)
        }
    }

    private fun seed(extras: Bundle?): Bundle {
        if (extras == null || extras.keySet() != SEED_KEYS) {
            return failure(FixtureError.INVALID_EXTRAS)
        }
        val shape = BenchmarkFixtureShape.fromWireValue(extras.getString(KEY_SHAPE))
            ?: return failure(FixtureError.INVALID_SHAPE)
        if (extras.getLong(KEY_SEED, Long.MIN_VALUE) != SyntheticIndexFixtures.FIXED_SEED) {
            return failure(FixtureError.INVALID_SEED)
        }
        val app = context?.applicationContext as? AuroraSmsApplication
            ?: return failure(FixtureError.INVALID_APPLICATION)
        if (app.isContainerInitialized) return failure(FixtureError.RUNTIME_ALREADY_OPEN)

        return runBlocking(Dispatchers.IO) {
            runCatching { seedKnownIndex(app, shape) }
                .getOrElse { failure(FixtureError.SEED_FAILED) }
        }
    }

    private fun cleanup(extras: Bundle?): Bundle {
        if (extras != null && !extras.isEmpty) return failure(FixtureError.INVALID_EXTRAS)
        val app = context?.applicationContext as? AuroraSmsApplication
            ?: return failure(FixtureError.INVALID_APPLICATION)
        if (app.isContainerInitialized) return failure(FixtureError.RUNTIME_ALREADY_OPEN)
        return runBlocking(Dispatchers.IO) {
            runCatching {
                deleteKnownIndex(app)
                success(messageCount = 0, threadCount = 0, primaryThreadId = 0L)
            }.getOrElse { failure(FixtureError.CLEANUP_FAILED) }
        }
    }

    private suspend fun seedKnownIndex(
        app: AuroraSmsApplication,
        shape: BenchmarkFixtureShape,
    ): Bundle {
        deleteKnownIndex(app)
        val database = IndexDatabaseFactory.create(app)
        return try {
            populateCompleteGeneration(database, shape)
            val fixture = SyntheticIndexFixtures.fixture(shape.sourceShape)
            success(
                messageCount = shape.sourceShape.messageCount,
                threadCount = shape.sourceShape.expectedThreadCount,
                primaryThreadId = if (fixture.size == 0) 0L else fixture.recordAt(0).providerThreadId.value,
            )
        } finally {
            database.close()
        }
    }

    private suspend fun populateCompleteGeneration(
        database: AuroraIndexDatabase,
        shape: BenchmarkFixtureShape,
    ) {
        val fixture = SyntheticIndexFixtures.fixture(shape.sourceShape)
        val generationId = database.indexSyncDao().startGeneration(nowMillis = STARTED_AT_MILLIS)
        var checkpoint = CheckpointState()
        var start = 0
        var batchNumber = 0L
        if (fixture.size == 0) {
            database.indexSyncDao().putCheckpoint(
                checkpoint.toCheckpoint(generationId, SMS_KIND, exhausted = true, UPDATED_AT_MILLIS),
            )
            database.indexSyncDao().putCheckpoint(
                checkpoint.toCheckpoint(generationId, MMS_KIND, exhausted = true, UPDATED_AT_MILLIS),
            )
        }
        while (start < fixture.size) {
            val projections = fixture.window(start, FIXTURE_BATCH_SIZE).map { record ->
                record.toProjection(generationId)
            }
            checkpoint = checkpoint.consuming(projections.map(IndexedProviderProjection::message))
            val exhausted = start + projections.size == fixture.size
            database.indexedMessageDao().commitScanningProjectionBatch(
                generationId = generationId,
                projections = projections,
                smsCheckpoint = checkpoint.toCheckpoint(
                    generationId,
                    SMS_KIND,
                    exhausted,
                    UPDATED_AT_MILLIS + batchNumber,
                ),
                mmsCheckpoint = checkpoint.toCheckpoint(
                    generationId,
                    MMS_KIND,
                    exhausted,
                    UPDATED_AT_MILLIS + batchNumber,
                ),
                nowMillis = UPDATED_AT_MILLIS + batchNumber,
                targetBatchSize = FIXTURE_BATCH_SIZE,
            )
            start += projections.size
            batchNumber += 1L
        }
        val verifyingAt = UPDATED_AT_MILLIS + batchNumber + 1L
        check(database.indexSyncDao().markVerifying(generationId, verifyingAt) == 1)
        checkNotNull(
            database.indexSyncDao().finishVerifiedGeneration(
                generationId = generationId,
                nowMillis = verifyingAt + 1L,
                smsProviderCount = checkpoint.smsCount,
                mmsProviderCount = checkpoint.mmsCount,
            ),
        )
        database.indexedMessageDao().optimizeFullTextIndex()
        check(database.indexedMessageDao().count() == fixture.size.toLong())
    }

    private fun SyntheticIndexRecord.toProjection(generationId: Long): IndexedProviderProjection =
        when (this) {
            is SyntheticIndexRecord.Sms ->
                IndexProjectionMapper.projectionFromSms(providerMessage, generationId)
            is SyntheticIndexRecord.Mms ->
                IndexProjectionMapper.projectionFromMms(providerMessage, generationId)
        }

    private fun deleteKnownIndex(app: AuroraSmsApplication) {
        val expected = app.getDatabasePath(IndexDatabaseFactory.DATABASE_NAME).canonicalFile
        val databaseDirectory = requireNotNull(expected.parentFile).canonicalFile
        val ownedDirectory = requireNotNull(app.getDatabasePath("boundary").parentFile).canonicalFile
        check(expected.name == IndexDatabaseFactory.DATABASE_NAME && databaseDirectory == ownedDirectory)
        check(app.deleteDatabase(IndexDatabaseFactory.DATABASE_NAME) || !expected.exists())
    }

    private fun success(
        messageCount: Int,
        threadCount: Int,
        primaryThreadId: Long,
    ): Bundle = Bundle().apply {
        putBoolean(KEY_SUCCESS, true)
        putInt(KEY_MESSAGE_COUNT, messageCount)
        putInt(KEY_THREAD_COUNT, threadCount)
        putLong(KEY_PRIMARY_THREAD_ID, primaryThreadId)
    }

    private fun failure(error: FixtureError): Bundle = Bundle().apply {
        putBoolean(KEY_SUCCESS, false)
        putString(KEY_ERROR, error.wireValue)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private data class CheckpointState(
        val smsTimestampMillis: Long? = null,
        val smsProviderId: Long? = null,
        val smsCount: Long = 0L,
        val mmsTimestampMillis: Long? = null,
        val mmsProviderId: Long? = null,
        val mmsCount: Long = 0L,
    ) {
        fun consuming(rows: List<IndexedMessageEntity>): CheckpointState {
            val lastSms = rows.lastOrNull { it.providerKind == SMS_KIND }
            val lastMms = rows.lastOrNull { it.providerKind == MMS_KIND }
            return copy(
                smsTimestampMillis = lastSms?.timestampMillis ?: smsTimestampMillis,
                smsProviderId = lastSms?.providerId ?: smsProviderId,
                smsCount = smsCount + rows.count { it.providerKind == SMS_KIND },
                mmsTimestampMillis = lastMms?.timestampMillis ?: mmsTimestampMillis,
                mmsProviderId = lastMms?.providerId ?: mmsProviderId,
                mmsCount = mmsCount + rows.count { it.providerKind == MMS_KIND },
            )
        }

        fun toCheckpoint(
            generationId: Long,
            providerKind: Int,
            exhausted: Boolean,
            nowMillis: Long,
        ): IndexCheckpointEntity = if (providerKind == SMS_KIND) {
            IndexCheckpointEntity(
                generationId,
                providerKind,
                smsTimestampMillis,
                smsProviderId,
                exhausted,
                smsCount,
                nowMillis,
            )
        } else {
            IndexCheckpointEntity(
                generationId,
                providerKind,
                mmsTimestampMillis,
                mmsProviderId,
                exhausted,
                mmsCount,
                nowMillis,
            )
        }
    }

    private enum class FixtureError(val wireValue: String) {
        UNKNOWN_METHOD("unknown_method"),
        UNEXPECTED_ARGUMENT("unexpected_argument"),
        INVALID_EXTRAS("invalid_extras"),
        INVALID_SHAPE("invalid_shape"),
        INVALID_SEED("invalid_seed"),
        INVALID_APPLICATION("invalid_application"),
        RUNTIME_ALREADY_OPEN("runtime_already_open"),
        SEED_FAILED("seed_failed"),
        CLEANUP_FAILED("cleanup_failed"),
    }

    companion object {
        const val AUTHORITY: String = "org.aurorasms.app.benchmark.fixture"
        const val CONTROL_PERMISSION: String = "org.aurorasms.app.permission.BENCHMARK_CONTROL"
        const val METHOD_SEED: String = "seed"
        const val METHOD_CLEANUP: String = "cleanup"
        const val KEY_SHAPE: String = "shape"
        const val KEY_SEED: String = "seed"
        const val KEY_SUCCESS: String = "success"
        const val KEY_ERROR: String = "error"
        const val KEY_MESSAGE_COUNT: String = "message_count"
        const val KEY_THREAD_COUNT: String = "thread_count"
        const val KEY_PRIMARY_THREAD_ID: String = "primary_thread_id"

        private const val SMS_KIND = 1
        private const val MMS_KIND = 2
        private const val FIXTURE_BATCH_SIZE = SyntheticIndexFixtures.MAX_WINDOW_SIZE
        private const val STARTED_AT_MILLIS = 1L
        private const val UPDATED_AT_MILLIS = 10L
        private val SEED_KEYS: Set<String> = setOf(KEY_SHAPE, KEY_SEED)
    }
}

internal enum class BenchmarkFixtureShape(
    val wireValue: String,
    val sourceShape: SyntheticIndexShape,
) {
    INBOX_20K("inbox_20k", SyntheticIndexShape.SHALLOW_20_THOUSAND_THREADS),
    THREAD_250K("thread_250k", SyntheticIndexShape.SINGLE_THREAD_250_THOUSAND),
    SEARCH_500K("search_500k", SyntheticIndexShape.MESSAGES_500_THOUSAND),
    ;

    companion object {
        fun fromWireValue(value: String?): BenchmarkFixtureShape? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}
