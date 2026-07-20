// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Arrays

enum class AuroraBackupExportFailure {
    INVALID_DESTINATION,
    ROLE_REQUIRED,
    PERMISSION_DENIED,
    PROVIDER_UNAVAILABLE,
    PASSPHRASE_POLICY,
    LIMIT_EXCEEDED,
    CRYPTO_UNAVAILABLE,
    SOURCE_FAILURE,
    DOCUMENT_FAILURE,
}

sealed interface AuroraBackupExportResult {
    data class Success(val summary: AuroraBackupSummary) : AuroraBackupExportResult

    data class Failed(
        val reason: AuroraBackupExportFailure,
        /** False means the UI must warn that an incomplete destination may remain. */
        val incompleteDestinationRemoved: Boolean,
    ) : AuroraBackupExportResult
}

enum class AuroraRestoreSelectionFailure {
    INVALID_SOURCE,
    DOCUMENT_UNAVAILABLE,
    SOURCE_FAILURE,
    LIMIT_EXCEEDED,
    PRIVATE_STORAGE_FAILURE,
}

sealed interface AuroraRestoreSelectionResult {
    data class Success(
        val session: AuroraBackupStagingSession,
        val encryptedBytes: Long,
    ) : AuroraRestoreSelectionResult

    data class Failed(val reason: AuroraRestoreSelectionFailure) : AuroraRestoreSelectionResult
}

sealed interface AuroraRestoreReviewResult {
    data class Success(
        val session: AuroraBackupStagingSession,
        val summary: AuroraBackupSummary,
    ) : AuroraRestoreReviewResult

    data class Failed(val reason: AuroraBackupFailure) : AuroraRestoreReviewResult
    data object NoActiveSelection : AuroraRestoreReviewResult
}

sealed interface AuroraRestoreConfirmationResult {
    data class Success(val summary: AuroraRestoreSummary) : AuroraRestoreConfirmationResult
    data class Failed(
        val reason: AuroraRestoreFailure,
        val rollbackComplete: Boolean,
    ) : AuroraRestoreConfirmationResult

    data object NoValidatedArchive : AuroraRestoreConfirmationResult
}

data class AuroraBackupStartupRecoveryResult(
    val restoreFailure: AuroraRestoreResult.Failed?,
    val stagingCleanupSucceeded: Boolean,
)

/**
 * Single-owner controller for the explicit SAF backup and restore journey.
 *
 * Methods are blocking and must be called from a bounded worker dispatcher. No URI,
 * passphrase, key, message, or attachment is retained in controller state. Restore
 * provider mutation is reachable only from [confirmRestore] after successful review.
 */
class AuroraBackupDocumentController internal constructor(
    private val documents: AuroraBackupDocumentAccess,
    private val openBackupSource: () -> AuroraBackupSourceOpenResult,
    private val archive: AuroraBackupArchive,
    private val staging: AuroraBackupStagingStore,
    private val restore: (() -> InputStream) -> AuroraRestoreResult,
    private val recoverRestore: () -> AuroraRestoreResult?,
) {
    constructor(context: Context) : this(productionDependencies(context.applicationContext))

    private constructor(dependencies: ProductionDependencies) : this(
        documents = dependencies.documents,
        openBackupSource = dependencies.openBackupSource,
        archive = dependencies.archive,
        staging = dependencies.staging,
        restore = dependencies.restore,
        recoverRestore = dependencies.recoverRestore,
    )

    private var state: RestoreState = RestoreState.Idle

    @Synchronized
    fun export(destination: Uri, passphrase: CharArray): AuroraBackupExportResult {
        if (!documents.accepts(destination)) {
            return AuroraBackupExportResult.Failed(
                AuroraBackupExportFailure.INVALID_DESTINATION,
                incompleteDestinationRemoved = false,
            )
        }
        val workingPassphrase = passphrase.copyOf()
        try {
            if (!AuroraBackupArchive.passphraseMeetsPolicy(workingPassphrase)) {
                return exportFailure(destination, AuroraBackupExportFailure.PASSPHRASE_POLICY)
            }
            val entries = when (val source = openBackupSource()) {
                is AuroraBackupSourceOpenResult.Ready -> source.entries
                AuroraBackupSourceOpenResult.RoleRequired -> {
                    return exportFailure(destination, AuroraBackupExportFailure.ROLE_REQUIRED)
                }
                AuroraBackupSourceOpenResult.PermissionDenied -> {
                    return exportFailure(destination, AuroraBackupExportFailure.PERMISSION_DENIED)
                }
                is AuroraBackupSourceOpenResult.Unavailable -> {
                    return exportFailure(destination, AuroraBackupExportFailure.PROVIDER_UNAVAILABLE)
                }
            }
            val written = try {
                documents.openDestination(destination)?.use { output ->
                    archive.writeEncrypted(entries, workingPassphrase, output)
                }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: RuntimeException) {
                null
            } ?: return exportFailure(destination, AuroraBackupExportFailure.DOCUMENT_FAILURE)
            return when (written) {
                is AuroraBackupWriteResult.Success -> AuroraBackupExportResult.Success(written.summary)
                is AuroraBackupWriteResult.Failed -> exportFailure(
                    destination,
                    written.reason.toExportFailure(),
                )
            }
        } finally {
            Arrays.fill(workingPassphrase, '\u0000')
        }
    }

    @Synchronized
    fun selectRestoreSource(source: Uri): AuroraRestoreSelectionResult {
        cancelRestore()
        if (!documents.accepts(source)) {
            return AuroraRestoreSelectionResult.Failed(AuroraRestoreSelectionFailure.INVALID_SOURCE)
        }
        val input = try {
            documents.openSource(source)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        } ?: return AuroraRestoreSelectionResult.Failed(
            AuroraRestoreSelectionFailure.DOCUMENT_UNAVAILABLE,
        )
        val staged = try {
            input.use(staging::stageEncrypted)
        } catch (_: IOException) {
            AuroraBackupStageResult.Failed(AuroraBackupStageFailure.SOURCE_FAILURE)
        } catch (_: RuntimeException) {
            AuroraBackupStageResult.Failed(AuroraBackupStageFailure.SOURCE_FAILURE)
        }
        return when (staged) {
            is AuroraBackupStageResult.Success -> {
                state = RestoreState.Staged(staged.session)
                AuroraRestoreSelectionResult.Success(staged.session, staged.encryptedBytes)
            }
            is AuroraBackupStageResult.Failed -> {
                state = RestoreState.Idle
                AuroraRestoreSelectionResult.Failed(staged.reason.toSelectionFailure())
            }
        }
    }

    @Synchronized
    fun authenticateRestore(
        session: AuroraBackupStagingSession,
        passphrase: CharArray,
    ): AuroraRestoreReviewResult {
        val selected = state as? RestoreState.Staged
            ?: return AuroraRestoreReviewResult.NoActiveSelection
        if (selected.session != session) return AuroraRestoreReviewResult.NoActiveSelection
        val workingPassphrase = passphrase.copyOf()
        return try {
            when (val result = staging.authenticate(session, workingPassphrase)) {
                is AuroraBackupAuthenticateResult.Success -> {
                    state = RestoreState.Validated(result.archive, result.summary)
                    AuroraRestoreReviewResult.Success(session, result.summary)
                }
                is AuroraBackupAuthenticateResult.Failed -> {
                    AuroraRestoreReviewResult.Failed(result.reason)
                }
            }
        } finally {
            Arrays.fill(workingPassphrase, '\u0000')
        }
    }

    /** The sole transition that can call the provider restore coordinator. */
    @Synchronized
    fun confirmRestore(session: AuroraBackupStagingSession): AuroraRestoreConfirmationResult {
        val validated = state as? RestoreState.Validated
            ?: return AuroraRestoreConfirmationResult.NoValidatedArchive
        if (validated.archive.session != session) {
            return AuroraRestoreConfirmationResult.NoValidatedArchive
        }
        state = RestoreState.Restoring
        val result = try {
            restore(validated.archive::open)
        } catch (_: IOException) {
            AuroraRestoreResult.Failed(AuroraRestoreFailure.SOURCE_FAILED, rollbackComplete = true)
        } catch (_: RuntimeException) {
            AuroraRestoreResult.Failed(AuroraRestoreFailure.SOURCE_FAILED, rollbackComplete = true)
        } finally {
            staging.cleanup(session)
            state = RestoreState.Idle
        }
        return when (result) {
            is AuroraRestoreResult.Success -> AuroraRestoreConfirmationResult.Success(result.summary)
            is AuroraRestoreResult.Failed -> AuroraRestoreConfirmationResult.Failed(
                result.reason,
                result.rollbackComplete,
            )
        }
    }

    /** Safe for navigation-away, picker cancellation, and lifecycle backgrounding. */
    @Synchronized
    fun cancelRestore(): Boolean {
        val session = when (val current = state) {
            RestoreState.Idle,
            RestoreState.Restoring,
            -> null
            is RestoreState.Staged -> current.session
            is RestoreState.Validated -> current.archive.session
        }
        state = RestoreState.Idle
        return session?.let(staging::cleanup) ?: true
    }

    /** Provider rollback precedes deletion of any unrelated plaintext crash residue. */
    @Synchronized
    fun recoverStartup(): AuroraBackupStartupRecoveryResult {
        state = RestoreState.Idle
        val recovery = try {
            recoverRestore()
        } catch (_: RuntimeException) {
            AuroraRestoreResult.Failed(
                AuroraRestoreFailure.RECOVERY_REQUIRED,
                rollbackComplete = false,
            )
        }
        return AuroraBackupStartupRecoveryResult(
            restoreFailure = recovery as? AuroraRestoreResult.Failed,
            stagingCleanupSucceeded = staging.reconcileStartup(),
        )
    }

    private fun exportFailure(
        destination: Uri,
        reason: AuroraBackupExportFailure,
    ): AuroraBackupExportResult.Failed = AuroraBackupExportResult.Failed(
        reason = reason,
        incompleteDestinationRemoved = documents.delete(destination),
    )

    private sealed interface RestoreState {
        data object Idle : RestoreState
        data class Staged(val session: AuroraBackupStagingSession) : RestoreState
        data class Validated(
            val archive: AuroraBackupValidatedArchive,
            val summary: AuroraBackupSummary,
        ) : RestoreState
        data object Restoring : RestoreState
    }

    private data class ProductionDependencies(
        val documents: AuroraBackupDocumentAccess,
        val openBackupSource: () -> AuroraBackupSourceOpenResult,
        val archive: AuroraBackupArchive,
        val staging: AuroraBackupStagingStore,
        val restore: (() -> InputStream) -> AuroraRestoreResult,
        val recoverRestore: () -> AuroraRestoreResult?,
    )

    private companion object {
        fun productionDependencies(context: Context): ProductionDependencies {
            val archive = AuroraBackupArchive()
            val staging = AuroraBackupStagingStore(context)
            val coordinator = AuroraRestoreCoordinator(
                archive = archive,
                journal = AuroraRestoreJournal(context),
                provider = AndroidAuroraRestoreProvider(context),
            )
            val source = AndroidTelephonyBackupSource(context)
            return ProductionDependencies(
                documents = AndroidAuroraBackupDocumentAccess(context),
                openBackupSource = source::open,
                archive = archive,
                staging = staging,
                restore = coordinator::restore,
                recoverRestore = coordinator::recover,
            )
        }
    }
}

internal interface AuroraBackupDocumentAccess {
    fun accepts(uri: Uri): Boolean
    @Throws(IOException::class, SecurityException::class)
    fun openSource(uri: Uri): InputStream?
    @Throws(IOException::class, SecurityException::class)
    fun openDestination(uri: Uri): OutputStream?
    fun delete(uri: Uri): Boolean
}

private class AndroidAuroraBackupDocumentAccess(context: Context) : AuroraBackupDocumentAccess {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    override fun accepts(uri: Uri): Boolean = uri.scheme == ContentResolver.SCHEME_CONTENT

    override fun openSource(uri: Uri): InputStream? = resolver.openInputStream(uri)

    override fun openDestination(uri: Uri): OutputStream? = resolver.openOutputStream(uri, "w")

    override fun delete(uri: Uri): Boolean = try {
        if (DocumentsContract.isDocumentUri(appContext, uri)) {
            DocumentsContract.deleteDocument(resolver, uri)
        } else {
            resolver.delete(uri, null, null) > 0
        }
    } catch (_: SecurityException) {
        false
    } catch (_: RuntimeException) {
        false
    }
}

private fun AuroraBackupFailure.toExportFailure(): AuroraBackupExportFailure = when (this) {
    AuroraBackupFailure.PASSPHRASE_POLICY -> AuroraBackupExportFailure.PASSPHRASE_POLICY
    AuroraBackupFailure.LIMIT_EXCEEDED -> AuroraBackupExportFailure.LIMIT_EXCEEDED
    AuroraBackupFailure.CRYPTO_UNAVAILABLE -> AuroraBackupExportFailure.CRYPTO_UNAVAILABLE
    AuroraBackupFailure.SOURCE_FAILURE -> AuroraBackupExportFailure.SOURCE_FAILURE
    AuroraBackupFailure.IO_FAILURE -> AuroraBackupExportFailure.DOCUMENT_FAILURE
    AuroraBackupFailure.UNSUPPORTED_VERSION,
    AuroraBackupFailure.AUTHENTICATION_OR_CORRUPTION,
    AuroraBackupFailure.INVALID_ARCHIVE,
    -> AuroraBackupExportFailure.SOURCE_FAILURE
}

private fun AuroraBackupStageFailure.toSelectionFailure(): AuroraRestoreSelectionFailure = when (this) {
    AuroraBackupStageFailure.SOURCE_FAILURE -> AuroraRestoreSelectionFailure.SOURCE_FAILURE
    AuroraBackupStageFailure.LIMIT_EXCEEDED -> AuroraRestoreSelectionFailure.LIMIT_EXCEEDED
    AuroraBackupStageFailure.STORAGE_FAILURE -> AuroraRestoreSelectionFailure.PRIVATE_STORAGE_FAILURE
}
