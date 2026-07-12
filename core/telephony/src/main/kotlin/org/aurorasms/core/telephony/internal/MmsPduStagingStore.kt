// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.telephony.EncodedMmsPdu

enum class MmsPduDirection(val grantFlag: Int) {
    SEND_SOURCE(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    DOWNLOAD_TARGET(Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
}

data class StagedMmsPdu(
    val operationId: MessageId,
    val uri: Uri,
    val direction: MmsPduDirection,
    val fileName: String,
)

sealed interface MmsStagingResult {
    data class Ready(val staged: StagedMmsPdu) : MmsStagingResult
    data class Failed(val reason: Reason) : MmsStagingResult

    enum class Reason {
        INVALID_OPERATION_ID,
        DIRECTORY_UNAVAILABLE,
        FILE_CREATION_FAILED,
        PAYLOAD_TOO_LARGE,
    }
}

class MmsPduStagingStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext
    private val authority = "${appContext.packageName}$AUTHORITY_SUFFIX"
    private val cacheRoot = File(appContext.cacheDir, ROOT_DIRECTORY)
    private val sendRoot = File(cacheRoot, SEND_DIRECTORY)
    private val downloadRoot = File(cacheRoot, DOWNLOAD_DIRECTORY)

    suspend fun stageSend(
        operationId: MessageId,
        pdu: EncodedMmsPdu,
    ): MmsStagingResult = withContext(ioDispatcher) {
        if (operationId.kind != ProviderKind.PENDING_OPERATION) {
            return@withContext MmsStagingResult.Failed(MmsStagingResult.Reason.INVALID_OPERATION_ID)
        }
        if (pdu.size > EncodedMmsPdu.MAX_ENCODED_BYTES) {
            return@withContext MmsStagingResult.Failed(MmsStagingResult.Reason.PAYLOAD_TOO_LARGE)
        }
        val file = createUniqueFile(sendRoot)
            ?: return@withContext MmsStagingResult.Failed(MmsStagingResult.Reason.FILE_CREATION_FAILED)
        try {
            FileOutputStream(file).use { output ->
                output.write(pdu.copyBytes())
                output.fd.sync()
            }
            ready(operationId, file, MmsPduDirection.SEND_SOURCE)
        } catch (_: IOException) {
            file.delete()
            MmsStagingResult.Failed(MmsStagingResult.Reason.FILE_CREATION_FAILED)
        } catch (_: RuntimeException) {
            file.delete()
            MmsStagingResult.Failed(MmsStagingResult.Reason.FILE_CREATION_FAILED)
        }
    }

    suspend fun createDownloadTarget(operationId: MessageId): MmsStagingResult = withContext(ioDispatcher) {
        if (operationId.kind != ProviderKind.PENDING_OPERATION) {
            return@withContext MmsStagingResult.Failed(MmsStagingResult.Reason.INVALID_OPERATION_ID)
        }
        val file = createUniqueFile(downloadRoot)
            ?: return@withContext MmsStagingResult.Failed(MmsStagingResult.Reason.FILE_CREATION_FAILED)
        ready(operationId, file, MmsPduDirection.DOWNLOAD_TARGET)
    }

    suspend fun readCompletedDownload(staged: StagedMmsPdu): EncodedMmsPdu.CreationResult =
        withContext(ioDispatcher) {
            require(staged.direction == MmsPduDirection.DOWNLOAD_TARGET) {
                "Only a download target can be read as a completed download"
            }
            val file = resolve(staged.uri, staged.direction)
                ?: return@withContext EncodedMmsPdu.CreationResult.Rejected(
                    EncodedMmsPdu.CreationResult.Reason.EMPTY,
                )
            if (file.length() <= 0L) {
                return@withContext EncodedMmsPdu.CreationResult.Rejected(
                    EncodedMmsPdu.CreationResult.Reason.EMPTY,
                )
            }
            if (file.length() > EncodedMmsPdu.MAX_ENCODED_BYTES) {
                return@withContext EncodedMmsPdu.CreationResult.Rejected(
                    EncodedMmsPdu.CreationResult.Reason.TOO_LARGE,
                )
            }
            EncodedMmsPdu.create(file.readBytes())
        }

    suspend fun cleanup(uri: Uri, expectedDirection: MmsPduDirection): Boolean = withContext(ioDispatcher) {
        val file = resolve(uri, expectedDirection) ?: return@withContext false
        appContext.revokeUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        !file.exists() || file.delete()
    }

    suspend fun cleanupExpired(
        nowMillis: Long,
        maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
        maxFiles: Int = MAX_EXPIRY_FILES_PER_RUN,
    ): Int = withContext(ioDispatcher) {
        require(nowMillis >= 0L && maxAgeMillis >= 0L && maxFiles in 1..MAX_EXPIRY_FILES_PER_RUN)
        val cutoff = nowMillis - maxAgeMillis
        sequenceOf(
            sendRoot to MmsPduDirection.SEND_SOURCE,
            downloadRoot to MmsPduDirection.DOWNLOAD_TARGET,
        ).flatMap { (directory, direction) ->
            directory.listFiles().orEmpty().asSequence().map { it to direction }
        }.filter { (file, _) -> file.isFile && FILE_NAME.matches(file.name) && file.lastModified() <= cutoff }
            .take(maxFiles)
            .count { (file, direction) ->
                val uri = runCatching { uriFor(file) }.getOrNull()
                if (uri != null) {
                    appContext.revokeUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                resolveFile(file, direction) != null && (!file.exists() || file.delete())
            }
    }

    private fun ready(
        operationId: MessageId,
        file: File,
        direction: MmsPduDirection,
    ): MmsStagingResult = try {
        MmsStagingResult.Ready(
            StagedMmsPdu(
                operationId = operationId,
                uri = uriFor(file),
                direction = direction,
                fileName = file.name,
            ),
        )
    } catch (_: IllegalArgumentException) {
        file.delete()
        MmsStagingResult.Failed(MmsStagingResult.Reason.DIRECTORY_UNAVAILABLE)
    }

    private fun createUniqueFile(directory: File): File? {
        if (!ensureConfinedDirectory(directory)) return null
        return try {
            repeat(MAX_CREATE_ATTEMPTS) {
                val candidate = File(directory, "${UUID.randomUUID()}.pdu")
                if (candidate.createNewFile()) return candidate
            }
            null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun ensureConfinedDirectory(directory: File): Boolean = try {
        val cacheCanonical = appContext.cacheDir.canonicalFile
        val directoryCanonical = directory.canonicalFile
        if (!directoryCanonical.path.startsWith(cacheCanonical.path + File.separator)) return false
        directoryCanonical.mkdirs() || directoryCanonical.isDirectory
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(appContext, authority, file)

    private fun resolve(uri: Uri, expectedDirection: MmsPduDirection): File? {
        if (uri.scheme != ContentResolverScheme || uri.authority != authority) return null
        val segments = uri.pathSegments
        if (segments.size != 2 || !FILE_NAME.matches(segments[1])) return null
        val expectedPathName = when (expectedDirection) {
            MmsPduDirection.SEND_SOURCE -> SEND_PATH_NAME
            MmsPduDirection.DOWNLOAD_TARGET -> DOWNLOAD_PATH_NAME
        }
        if (segments[0] != expectedPathName) return null
        val root = when (expectedDirection) {
            MmsPduDirection.SEND_SOURCE -> sendRoot
            MmsPduDirection.DOWNLOAD_TARGET -> downloadRoot
        }
        return resolveFile(File(root, segments[1]), expectedDirection)
    }

    private fun resolveFile(file: File, direction: MmsPduDirection): File? = try {
        val expectedRoot = when (direction) {
            MmsPduDirection.SEND_SOURCE -> sendRoot.canonicalFile
            MmsPduDirection.DOWNLOAD_TARGET -> downloadRoot.canonicalFile
        }
        val canonical = file.canonicalFile
        canonical.takeIf { it.parentFile == expectedRoot && FILE_NAME.matches(it.name) }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    companion object {
        const val SEND_PATH_NAME = "mms_send"
        const val DOWNLOAD_PATH_NAME = "mms_download"
        const val AUTHORITY_SUFFIX = ".mms-pdu"
        const val DEFAULT_MAX_AGE_MILLIS: Long = 15L * 60L * 1_000L
        const val MAX_EXPIRY_FILES_PER_RUN: Int = 100

        private const val ROOT_DIRECTORY = "mms-pdu"
        private const val SEND_DIRECTORY = "send"
        private const val DOWNLOAD_DIRECTORY = "download"
        private const val MAX_CREATE_ATTEMPTS = 8
        private const val ContentResolverScheme = "content"
        private val FILE_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pdu")
    }
}
