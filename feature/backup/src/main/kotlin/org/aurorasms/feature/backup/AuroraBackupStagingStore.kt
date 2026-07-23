// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.backup

import android.content.Context
import android.os.Build
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Arrays
import java.util.UUID

class AuroraBackupStagingSession internal constructor(
    internal val value: String,
) {
    init {
        require(SESSION_PATTERN.matches(value))
    }

    override fun toString(): String = "AuroraBackupStagingSession(REDACTED)"

    override fun equals(other: Any?): Boolean =
        other is AuroraBackupStagingSession && value == other.value

    override fun hashCode(): Int = value.hashCode()

    private companion object {
        val SESSION_PATTERN = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
        )
    }
}

enum class AuroraBackupStageFailure {
    SOURCE_FAILURE,
    LIMIT_EXCEEDED,
    STORAGE_FAILURE,
}

sealed interface AuroraBackupStageResult {
    data class Success(
        val session: AuroraBackupStagingSession,
        val encryptedBytes: Long,
    ) : AuroraBackupStageResult

    data class Failed(val reason: AuroraBackupStageFailure) : AuroraBackupStageResult
}

sealed interface AuroraBackupAuthenticateResult {
    data class Success(
        val archive: AuroraBackupValidatedArchive,
        val summary: AuroraBackupSummary,
    ) : AuroraBackupAuthenticateResult

    data class Failed(val reason: AuroraBackupFailure) : AuroraBackupAuthenticateResult
}

/**
 * Capability for one authenticated private plaintext archive.
 *
 * Every [open] rechecks the owned descriptor. Cleanup invalidates the capability.
 */
class AuroraBackupValidatedArchive internal constructor(
    private val store: AuroraBackupStagingStore,
    val session: AuroraBackupStagingSession,
) {
    @Throws(IOException::class)
    fun open(): InputStream = store.openValidated(session)

    override fun toString(): String = "AuroraBackupValidatedArchive(session=$session, REDACTED)"
}

/**
 * Owns the restore archive only inside credential-encrypted, backup-excluded storage.
 *
 * The selected encrypted document is copied with a hard bound. Plaintext first lands
 * in a private `.pending` file and can become `.validated` only after both the GCM tag
 * and the complete message archive validation succeed. No provider API is reachable
 * from this class.
 */
class AuroraBackupStagingStore internal constructor(
    private val directory: File,
    private val archive: AuroraBackupArchive = AuroraBackupArchive(),
    private val newSession: () -> String = { UUID.randomUUID().toString() },
    private val maximumEncryptedBytes: Long = AuroraBackupArchive.MAX_ENCRYPTED_ARCHIVE_BYTES,
    private val maximumPlaintextBytes: Long = AuroraBackupArchive.MAX_PLAINTEXT_ARCHIVE_BYTES,
) {
    constructor(context: Context) : this(
        File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME),
    )

    init {
        require(maximumEncryptedBytes > 0L)
        require(maximumPlaintextBytes > 0L)
    }

    @Synchronized
    fun stageEncrypted(source: InputStream): AuroraBackupStageResult {
        if (!reconcileStartup()) {
            return AuroraBackupStageResult.Failed(AuroraBackupStageFailure.STORAGE_FAILURE)
        }
        val session = runCatching { AuroraBackupStagingSession(newSession()) }.getOrNull()
            ?: return AuroraBackupStageResult.Failed(AuroraBackupStageFailure.STORAGE_FAILURE)
        val files = SessionFiles(directory, session)
        var identity: OwnedFileIdentity? = null
        return try {
            var copied = 0L
            identity = writeExclusive(files.encryptedPending) { output ->
                copied = copyBounded(source, output, maximumEncryptedBytes)
            }
            if (!moveOwned(files.encryptedPending, files.encryptedStaged, identity)) {
                cleanupSession(files)
                return AuroraBackupStageResult.Failed(AuroraBackupStageFailure.STORAGE_FAILURE)
            }
            AuroraBackupStageResult.Success(session, copied)
        } catch (_: StageLimitException) {
            identity?.let { deleteOwned(files.encryptedPending, it) }
            cleanupSession(files)
            AuroraBackupStageResult.Failed(AuroraBackupStageFailure.LIMIT_EXCEEDED)
        } catch (_: SourceReadException) {
            identity?.let { deleteOwned(files.encryptedPending, it) }
            cleanupSession(files)
            AuroraBackupStageResult.Failed(AuroraBackupStageFailure.SOURCE_FAILURE)
        } catch (_: IOException) {
            identity?.let { deleteOwned(files.encryptedPending, it) }
            cleanupSession(files)
            AuroraBackupStageResult.Failed(AuroraBackupStageFailure.STORAGE_FAILURE)
        } catch (_: RuntimeException) {
            identity?.let { deleteOwned(files.encryptedPending, it) }
            cleanupSession(files)
            AuroraBackupStageResult.Failed(AuroraBackupStageFailure.STORAGE_FAILURE)
        }
    }

    /**
     * Authenticates, validates, and consumes the staged encrypted copy.
     *
     * The caller retains ownership of [passphrase]. This method clears its private copy.
     */
    @Synchronized
    fun authenticate(
        session: AuroraBackupStagingSession,
        passphrase: CharArray,
    ): AuroraBackupAuthenticateResult {
        val files = SessionFiles(directory, session)
        if (!ensureSafeDirectory() || !isOnlyExpectedSessionState(files)) {
            cleanupSession(files)
            return AuroraBackupAuthenticateResult.Failed(AuroraBackupFailure.IO_FAILURE)
        }
        val workingPassphrase = passphrase.copyOf()
        var pendingIdentity: OwnedFileIdentity? = null
        try {
            val decryptResult: AuroraBackupDecryptResult
            openOwnedInput(
                files.encryptedStaged,
                maximumBytes = maximumEncryptedBytes,
            ).use { encrypted ->
                var observed: AuroraBackupDecryptResult? = null
                pendingIdentity = writeExclusive(files.plaintextPending) { pending ->
                    observed = archive.decryptToPending(encrypted, workingPassphrase, pending)
                }
                decryptResult = observed
                    ?: return failAndCleanup(files, AuroraBackupFailure.IO_FAILURE)
            }
            if (decryptResult is AuroraBackupDecryptResult.Failed) {
                val removed = pendingIdentity?.let {
                    deleteOwned(files.plaintextPending, it) && syncDirectory()
                } ?: !files.plaintextPending.existsNoFollow()
                if (!removed) {
                    return failAndCleanup(files, AuroraBackupFailure.IO_FAILURE)
                }
                return AuroraBackupAuthenticateResult.Failed(decryptResult.reason)
            }

            val validation = openOwnedInput(
                files.plaintextPending,
                maximumBytes = maximumPlaintextBytes,
                expectedIdentity = pendingIdentity,
            ).use(archive::validateMessagePlaintext)
            val summary = (validation as? AuroraBackupValidationResult.Success)?.summary
                ?: return failAndCleanup(
                    files,
                    (validation as AuroraBackupValidationResult.Failed).reason,
                )
            val identity = pendingIdentity
                ?: return failAndCleanup(files, AuroraBackupFailure.IO_FAILURE)
            if (!moveOwned(files.plaintextPending, files.plaintextValidated, identity)) {
                return failAndCleanup(files, AuroraBackupFailure.IO_FAILURE)
            }
            if (!deleteOwned(files.encryptedStaged) || !syncDirectory()) {
                return failAndCleanup(files, AuroraBackupFailure.IO_FAILURE)
            }
            return AuroraBackupAuthenticateResult.Success(
                archive = AuroraBackupValidatedArchive(this, session),
                summary = summary,
            )
        } catch (_: IOException) {
            return failAndCleanup(files, AuroraBackupFailure.IO_FAILURE)
        } catch (_: RuntimeException) {
            return failAndCleanup(files, AuroraBackupFailure.IO_FAILURE)
        } finally {
            Arrays.fill(workingPassphrase, '\u0000')
        }
    }

    /** Deletes every staging file owned by this feature, including crash leftovers. */
    @Synchronized
    fun reconcileStartup(): Boolean {
        if (!directory.exists()) return ensureSafeDirectory()
        if (!ensureSafeDirectory()) return false
        val children = directory.listFiles() ?: return false
        var deleted = false
        for (child in children) {
            if (!MANAGED_FILE_PATTERN.matches(child.name)) continue
            if (!deleteOwned(child)) return false
            deleted = true
        }
        return !deleted || syncDirectory()
    }

    /** Deletes one active selection when the screen leaves or backgrounds. */
    @Synchronized
    fun cleanup(session: AuroraBackupStagingSession): Boolean =
        cleanupSession(SessionFiles(directory, session))

    @Throws(IOException::class)
    internal fun openValidated(session: AuroraBackupStagingSession): InputStream = synchronized(this) {
        val files = SessionFiles(directory, session)
        if (!ensureSafeDirectory() || !isOnlyValidatedState(files)) {
            throw IOException("Validated backup archive is unavailable")
        }
        openOwnedInput(
            files.plaintextValidated,
            maximumBytes = maximumPlaintextBytes,
        )
    }

    private fun failAndCleanup(
        files: SessionFiles,
        reason: AuroraBackupFailure,
    ): AuroraBackupAuthenticateResult.Failed {
        cleanupSession(files)
        return AuroraBackupAuthenticateResult.Failed(reason)
    }

    private fun isOnlyExpectedSessionState(files: SessionFiles): Boolean =
        safeStat(files.encryptedStaged)?.isOwnedRegularFile() == true &&
            !files.encryptedPending.existsNoFollow() &&
            !files.plaintextPending.existsNoFollow() &&
            !files.plaintextValidated.existsNoFollow()

    private fun isOnlyValidatedState(files: SessionFiles): Boolean =
        safeStat(files.plaintextValidated)?.isOwnedRegularFile() == true &&
            !files.encryptedPending.existsNoFollow() &&
            !files.encryptedStaged.existsNoFollow() &&
            !files.plaintextPending.existsNoFollow()

    private fun cleanupSession(files: SessionFiles): Boolean {
        if (!directory.exists()) return true
        if (!ensureSafeDirectory()) return false
        var deleted = false
        for (file in files.all) {
            if (!file.existsNoFollow()) continue
            if (!deleteOwned(file)) return false
            deleted = true
        }
        return !deleted || syncDirectory()
    }

    @Throws(IOException::class)
    private fun writeExclusive(
        file: File,
        writer: (FileOutputStream) -> Unit,
    ): OwnedFileIdentity {
        if (!ensureSafeDirectory() || file.parentFile != directory) {
            throw IOException("Unsafe staging directory")
        }
        val descriptor = try {
            Os.open(
                file.absolutePath,
                OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                    OsConstants.O_NOFOLLOW or closeOnExecFlag(),
                OWNER_READ_WRITE_MODE,
            )
        } catch (error: ErrnoException) {
            throw IOException("Could not create private staging file", error)
        }
        var output: FileOutputStream? = null
        var identity: OwnedFileIdentity? = null
        try {
            val stat = Os.fstat(descriptor)
            if (!stat.isOwnedRegularFile()) throw IOException("Unsafe private staging file")
            identity = stat.identity()
            output = FileOutputStream(descriptor)
            output.use { stream ->
                writer(stream)
                stream.flush()
                stream.fd.sync()
            }
            return identity
        } catch (error: ErrnoException) {
            throw IOException("Could not inspect private staging file", error)
        } finally {
            if (output == null) closeQuietly(descriptor)
            if (identity == null) unlinkQuietly(file)
        }
    }

    @Throws(IOException::class)
    private fun openOwnedInput(
        file: File,
        maximumBytes: Long,
        expectedIdentity: OwnedFileIdentity? = null,
    ): FileInputStream {
        if (!ensureSafeDirectory() || file.parentFile != directory) {
            throw IOException("Unsafe staging directory")
        }
        val descriptor = try {
            Os.open(
                file.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or closeOnExecFlag(),
                0,
            )
        } catch (error: ErrnoException) {
            throw IOException("Could not open private staging file", error)
        }
        return try {
            val stat = Os.fstat(descriptor)
            val identity = stat.identity()
            if (
                !stat.isOwnedRegularFile() ||
                stat.st_size !in 1..maximumBytes ||
                (expectedIdentity != null && expectedIdentity != identity)
            ) {
                throw IOException("Unsafe private staging file")
            }
            FileInputStream(descriptor)
        } catch (error: ErrnoException) {
            closeQuietly(descriptor)
            throw IOException("Could not inspect private staging file", error)
        } catch (error: IOException) {
            closeQuietly(descriptor)
            throw error
        } catch (error: RuntimeException) {
            closeQuietly(descriptor)
            throw error
        }
    }

    private fun moveOwned(
        source: File,
        destination: File,
        expectedIdentity: OwnedFileIdentity,
    ): Boolean {
        if (destination.existsNoFollow()) return false
        val before = safeStat(source) ?: return false
        if (!before.isOwnedRegularFile() || before.identity() != expectedIdentity) return false
        return try {
            Os.rename(source.absolutePath, destination.absolutePath)
            val after = Os.lstat(destination.absolutePath)
            after.isOwnedRegularFile() && after.identity() == expectedIdentity && syncDirectory()
        } catch (_: ErrnoException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun deleteOwned(
        file: File,
        expectedIdentity: OwnedFileIdentity? = null,
    ): Boolean {
        val stat = try {
            Os.lstat(file.absolutePath)
        } catch (error: ErrnoException) {
            return error.errno == OsConstants.ENOENT
        } catch (_: RuntimeException) {
            return false
        }
        if (OsConstants.S_ISLNK(stat.st_mode)) return unlinkQuietly(file)
        if (!stat.isOwnedRegularFile()) return false
        if (expectedIdentity != null && stat.identity() != expectedIdentity) return false
        return unlinkQuietly(file)
    }

    private fun ensureSafeDirectory(): Boolean {
        if (!directory.existsNoFollow()) {
            try {
                Os.mkdir(directory.absolutePath, OWNER_DIRECTORY_MODE)
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EEXIST) return false
            } catch (_: RuntimeException) {
                return false
            }
        }
        val stat = safeStat(directory) ?: return false
        return OsConstants.S_ISDIR(stat.st_mode) &&
            !OsConstants.S_ISLNK(stat.st_mode) &&
            stat.st_uid == Process.myUid()
    }

    private fun syncDirectory(): Boolean {
        val descriptor = try {
            Os.open(
                directory.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or closeOnExecFlag(),
                0,
            )
        } catch (_: ErrnoException) {
            return false
        } catch (_: RuntimeException) {
            return false
        }
        return try {
            val stat = Os.fstat(descriptor)
            if (!OsConstants.S_ISDIR(stat.st_mode)) return false
            Os.fsync(descriptor)
            true
        } catch (_: ErrnoException) {
            false
        } catch (_: RuntimeException) {
            false
        } finally {
            closeQuietly(descriptor)
        }
    }

    private fun safeStat(file: File): StructStat? = try {
        Os.lstat(file.absolutePath)
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) null else null
    } catch (_: RuntimeException) {
        null
    }

    private fun StructStat.isOwnedRegularFile(): Boolean =
        OsConstants.S_ISREG(st_mode) && st_nlink == 1L && st_uid == Process.myUid()

    private fun StructStat.identity(): OwnedFileIdentity = OwnedFileIdentity(st_dev, st_ino)

    private fun File.existsNoFollow(): Boolean = try {
        Os.lstat(absolutePath)
        true
    } catch (error: ErrnoException) {
        error.errno != OsConstants.ENOENT
    } catch (_: RuntimeException) {
        true
    }

    private data class OwnedFileIdentity(
        val device: Long,
        val inode: Long,
    )

    private data class SessionFiles(
        val directory: File,
        val session: AuroraBackupStagingSession,
    ) {
        private val prefix = "$FILE_PREFIX${session.value}"
        val encryptedPending = File(directory, "$prefix.encrypted.pending")
        val encryptedStaged = File(directory, "$prefix.encrypted.staged")
        val plaintextPending = File(directory, "$prefix.plaintext.pending")
        val plaintextValidated = File(directory, "$prefix.plaintext.validated")
        val all = listOf(
            encryptedPending,
            encryptedStaged,
            plaintextPending,
            plaintextValidated,
        )
    }

    private companion object {
        const val DIRECTORY_NAME = "aurora_restore_staging_v1"
        const val FILE_PREFIX = "aurora_restore_"
        const val OWNER_READ_WRITE_MODE = 384
        const val OWNER_DIRECTORY_MODE = 448
        val MANAGED_FILE_PATTERN = Regex(
            "aurora_restore_[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-" +
                "[89ab][0-9a-f]{3}-[0-9a-f]{12}\\." +
                "(?:encrypted\\.(?:pending|staged)|plaintext\\.(?:pending|validated))",
        )
    }
}

private class StageLimitException : IOException()

private class SourceReadException(cause: IOException) : IOException(cause)

@Throws(IOException::class)
private fun copyBounded(source: InputStream, destination: OutputStream, maximumBytes: Long): Long {
    val buffer = ByteArray(64 * 1_024)
    var copied = 0L
    while (true) {
        val read = try {
            source.read(buffer)
        } catch (error: IOException) {
            throw SourceReadException(error)
        }
        if (read < 0) break
        if (read == 0) {
            val value = try {
                source.read()
            } catch (error: IOException) {
                throw SourceReadException(error)
            }
            if (value < 0) break
            if (copied >= maximumBytes) throw StageLimitException()
            destination.write(value)
            copied += 1L
            continue
        }
        if (copied > maximumBytes - read.toLong()) throw StageLimitException()
        destination.write(buffer, 0, read)
        copied += read.toLong()
    }
    return copied
}

private fun closeOnExecFlag(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) OsConstants.O_CLOEXEC else 0

private fun closeQuietly(descriptor: FileDescriptor) {
    runCatching { Os.close(descriptor) }
}

private fun unlinkQuietly(file: File): Boolean = try {
    Os.remove(file.absolutePath)
    true
} catch (error: ErrnoException) {
    error.errno == OsConstants.ENOENT
} catch (_: RuntimeException) {
    false
}
