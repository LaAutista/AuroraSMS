// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** One bounded logical entry. The writer owns framing, paths, checksums, and encryption. */
class AuroraBackupEntry(
    val type: AuroraBackupEntryType,
    private val writeContent: (OutputStream) -> Unit,
) {
    internal fun writeTo(output: OutputStream) = writeContent(output)

    override fun toString(): String = "AuroraBackupEntry(type=$type, content=REDACTED)"
}

enum class AuroraBackupEntryType(
    internal val code: Int,
    internal val extension: String,
) {
    SMS(1, "sms"),
    MMS(2, "mms"),
    MMS_PART(3, "part"),
    ;

    internal companion object {
        fun decode(code: Int): AuroraBackupEntryType? = entries.singleOrNull { it.code == code }
    }
}

data class AuroraBackupSummary(
    val entryCount: Long,
    val smsCount: Long,
    val mmsCount: Long,
    val mmsPartCount: Long,
    val plaintextContentBytes: Long,
) {
    init {
        require(entryCount >= 0L)
        require(smsCount >= 0L)
        require(mmsCount >= 0L)
        require(mmsPartCount >= 0L)
        require(plaintextContentBytes >= 0L)
        require(entryCount == smsCount + mmsCount + mmsPartCount)
    }

    override fun toString(): String =
        "AuroraBackupSummary(entryCount=$entryCount, smsCount=$smsCount, " +
            "mmsCount=$mmsCount, mmsPartCount=$mmsPartCount, " +
            "plaintextContentBytes=$plaintextContentBytes)"
}

sealed interface AuroraBackupWriteResult {
    data class Success(val summary: AuroraBackupSummary) : AuroraBackupWriteResult
    data class Failed(val reason: AuroraBackupFailure) : AuroraBackupWriteResult
}

sealed interface AuroraBackupDecryptResult {
    data class Success(val plaintextBytes: Long) : AuroraBackupDecryptResult
    data class Failed(val reason: AuroraBackupFailure) : AuroraBackupDecryptResult
}

sealed interface AuroraBackupValidationResult {
    data class Success(val summary: AuroraBackupSummary) : AuroraBackupValidationResult
    data class Failed(val reason: AuroraBackupFailure) : AuroraBackupValidationResult
}

enum class AuroraBackupFailure {
    PASSPHRASE_POLICY,
    UNSUPPORTED_VERSION,
    AUTHENTICATION_OR_CORRUPTION,
    LIMIT_EXCEEDED,
    INVALID_ARCHIVE,
    CRYPTO_UNAVAILABLE,
    IO_FAILURE,
    SOURCE_FAILURE,
}

/**
 * Version-one streaming encrypted archive envelope and bounded plaintext framing.
 *
 * Decryption may write unauthenticated bytes only to a caller-owned private pending
 * file. The caller must delete that file unless [decryptToPending] succeeds, then
 * run [validatePlaintext] before any provider or durable-state mutation.
 */
class AuroraBackupArchive(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun writeEncrypted(
        entries: Sequence<AuroraBackupEntry>,
        passphrase: CharArray,
        destination: OutputStream,
    ): AuroraBackupWriteResult {
        if (!passphraseMeetsPolicy(passphrase)) {
            return AuroraBackupWriteResult.Failed(AuroraBackupFailure.PASSPHRASE_POLICY)
        }
        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val header = encodeHeader(salt, nonce)
        val key = deriveKey(passphrase, salt)
            ?: return AuroraBackupWriteResult.Failed(AuroraBackupFailure.CRYPTO_UNAVAILABLE)
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            cipher.updateAAD(header)
            destination.write(header)
            val cipherOutput = CipherOutputStream(NonClosingOutputStream(destination), cipher)
            val summary = PlaintextWriter(cipherOutput).use { writer -> writer.write(entries) }
            destination.flush()
            AuroraBackupWriteResult.Success(summary)
        } catch (_: ArchiveLimitException) {
            AuroraBackupWriteResult.Failed(AuroraBackupFailure.LIMIT_EXCEEDED)
        } catch (_: ArchiveSourceException) {
            AuroraBackupWriteResult.Failed(AuroraBackupFailure.SOURCE_FAILURE)
        } catch (_: GeneralSecurityException) {
            AuroraBackupWriteResult.Failed(AuroraBackupFailure.CRYPTO_UNAVAILABLE)
        } catch (_: IOException) {
            AuroraBackupWriteResult.Failed(AuroraBackupFailure.IO_FAILURE)
        } catch (_: RuntimeException) {
            AuroraBackupWriteResult.Failed(AuroraBackupFailure.SOURCE_FAILURE)
        } finally {
            Arrays.fill(key, 0)
        }
    }

    fun decryptToPending(
        encryptedSource: InputStream,
        passphrase: CharArray,
        privatePendingDestination: OutputStream,
    ): AuroraBackupDecryptResult {
        if (!passphraseMeetsPolicy(passphrase)) {
            return AuroraBackupDecryptResult.Failed(AuroraBackupFailure.PASSPHRASE_POLICY)
        }
        val source = MaximumInputStream(encryptedSource, MAX_ENCRYPTED_ARCHIVE_BYTES)
        val header = try {
            readExact(source, HEADER_BYTES)
        } catch (_: ArchiveLimitException) {
            return AuroraBackupDecryptResult.Failed(AuroraBackupFailure.LIMIT_EXCEEDED)
        } catch (_: IOException) {
            return AuroraBackupDecryptResult.Failed(AuroraBackupFailure.INVALID_ARCHIVE)
        }
        val decoded = decodeHeader(header)
            ?: return AuroraBackupDecryptResult.Failed(AuroraBackupFailure.INVALID_ARCHIVE)
        if (decoded.version != FORMAT_VERSION || decoded.kdf != KDF_ID || decoded.cipher != CIPHER_ID) {
            return AuroraBackupDecryptResult.Failed(AuroraBackupFailure.UNSUPPORTED_VERSION)
        }
        if (decoded.iterations !in MINIMUM_ACCEPTED_ITERATIONS..MAXIMUM_ACCEPTED_ITERATIONS) {
            return AuroraBackupDecryptResult.Failed(AuroraBackupFailure.INVALID_ARCHIVE)
        }
        val key = deriveKey(passphrase, decoded.salt, decoded.iterations)
            ?: return AuroraBackupDecryptResult.Failed(AuroraBackupFailure.CRYPTO_UNAVAILABLE)
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, decoded.nonce),
            )
            cipher.updateAAD(header)
            val boundedDestination = MaximumOutputStream(
                NonClosingOutputStream(privatePendingDestination),
                MAX_PLAINTEXT_ARCHIVE_BYTES,
            )
            CipherInputStream(source, cipher).use { plaintext ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = plaintext.read(buffer)
                    if (read < 0) break
                    boundedDestination.write(buffer, 0, read)
                }
            }
            boundedDestination.flush()
            AuroraBackupDecryptResult.Success(boundedDestination.written)
        } catch (_: ArchiveLimitException) {
            AuroraBackupDecryptResult.Failed(AuroraBackupFailure.LIMIT_EXCEEDED)
        } catch (_: GeneralSecurityException) {
            AuroraBackupDecryptResult.Failed(AuroraBackupFailure.CRYPTO_UNAVAILABLE)
        } catch (_: IOException) {
            // GCM tag failures and malformed ciphertext deliberately share one result.
            AuroraBackupDecryptResult.Failed(AuroraBackupFailure.AUTHENTICATION_OR_CORRUPTION)
        } catch (_: RuntimeException) {
            AuroraBackupDecryptResult.Failed(AuroraBackupFailure.AUTHENTICATION_OR_CORRUPTION)
        } finally {
            Arrays.fill(key, 0)
        }
    }

    fun validatePlaintext(source: InputStream): AuroraBackupValidationResult =
        validatePlaintext(source, validateMessageSchemas = false)

    /** Full Phase 6G validation, including exact version-one SMS/MMS/part schemas. */
    fun validateMessagePlaintext(source: InputStream): AuroraBackupValidationResult =
        validatePlaintext(source, validateMessageSchemas = true)

    private fun validatePlaintext(
        source: InputStream,
        validateMessageSchemas: Boolean,
    ): AuroraBackupValidationResult = try {
        val reader = DataInputStream(MaximumInputStream(source, MAX_PLAINTEXT_ARCHIVE_BYTES))
        if (!MessageDigest.isEqual(readExact(reader, INNER_MAGIC.size), INNER_MAGIC)) {
            return AuroraBackupValidationResult.Failed(AuroraBackupFailure.INVALID_ARCHIVE)
        }
        var expectedOrdinal = 1L
        var smsCount = 0L
        var mmsCount = 0L
        var partCount = 0L
        var totalContentBytes = 0L
        var expectedArchiveMessageId = 1L
        var activeMmsArchiveMessageId: Long? = null
        while (true) {
            if (reader.readInt() != RECORD_MARKER) throw InvalidArchiveException()
            val typeCode = reader.readUnsignedByte()
            if (typeCode == END_RECORD_CODE) {
                if (reader.readLong() != 0L || readPath(reader) != END_PATH) {
                    throw InvalidArchiveException()
                }
                val content = readRecordContent(reader, END_CONTENT_MAX_BYTES, retain = true)
                val declared = decodeEndSummary(content.bytes) ?: throw InvalidArchiveException()
                if (content.length != END_CONTENT_BYTES.toLong()) throw InvalidArchiveException()
                val observed = AuroraBackupSummary(
                    entryCount = expectedOrdinal - 1L,
                    smsCount = smsCount,
                    mmsCount = mmsCount,
                    mmsPartCount = partCount,
                    plaintextContentBytes = totalContentBytes,
                )
                if (declared != observed || reader.read() != -1) throw InvalidArchiveException()
                return AuroraBackupValidationResult.Success(observed)
            }
            val type = AuroraBackupEntryType.decode(typeCode) ?: throw InvalidArchiveException()
            val ordinal = reader.readLong()
            if (ordinal != expectedOrdinal || ordinal > MAX_RECORD_COUNT) throw InvalidArchiveException()
            if (readPath(reader) != pathFor(ordinal, type)) throw InvalidArchiveException()
            var schemaIdentity: AuroraBackupSchemaIdentity? = null
            val content = readRecordContent(
                input = reader,
                maximum = MAX_RECORD_CONTENT_BYTES,
                validate = if (validateMessageSchemas) {
                    { record ->
                        AuroraBackupMessageCodec.inspect(type, record)
                            ?.also { schemaIdentity = it } != null
                    }
                } else {
                    null
                },
            )
            if (content.firstByte != RECORD_SCHEMA_VERSION) throw InvalidArchiveException()
            if (validateMessageSchemas) {
                when (val identity = schemaIdentity ?: throw InvalidArchiveException()) {
                    is AuroraBackupSchemaIdentity.Message -> {
                        if (identity.archiveMessageId != expectedArchiveMessageId) {
                            throw InvalidArchiveException()
                        }
                        expectedArchiveMessageId += 1L
                        activeMmsArchiveMessageId = identity.archiveMessageId.takeIf { identity.isMms }
                    }
                    is AuroraBackupSchemaIdentity.MmsPart -> {
                        if (identity.parentArchiveMessageId != activeMmsArchiveMessageId) {
                            throw InvalidArchiveException()
                        }
                    }
                }
            }
            totalContentBytes = addWithinLimit(
                totalContentBytes,
                content.length,
                MAX_TOTAL_CONTENT_BYTES,
            )
            when (type) {
                AuroraBackupEntryType.SMS -> smsCount += 1L
                AuroraBackupEntryType.MMS -> mmsCount += 1L
                AuroraBackupEntryType.MMS_PART -> partCount += 1L
            }
            expectedOrdinal += 1L
        }
        @Suppress("UNREACHABLE_CODE")
        AuroraBackupValidationResult.Failed(AuroraBackupFailure.INVALID_ARCHIVE)
    } catch (_: ArchiveLimitException) {
        AuroraBackupValidationResult.Failed(AuroraBackupFailure.LIMIT_EXCEEDED)
    } catch (_: IOException) {
        AuroraBackupValidationResult.Failed(AuroraBackupFailure.INVALID_ARCHIVE)
    } catch (_: RuntimeException) {
        AuroraBackupValidationResult.Failed(AuroraBackupFailure.INVALID_ARCHIVE)
    }

    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int = KDF_ITERATIONS,
    ): ByteArray? {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
        } catch (_: GeneralSecurityException) {
            null
        } finally {
            spec.clearPassword()
        }
    }

    private inner class PlaintextWriter(
        output: OutputStream,
    ) : Closeable {
        private val output = DataOutputStream(output)
        private var closed = false

        fun write(entries: Sequence<AuroraBackupEntry>): AuroraBackupSummary {
            output.write(INNER_MAGIC)
            var ordinal = 1L
            var smsCount = 0L
            var mmsCount = 0L
            var partCount = 0L
            var totalContentBytes = 0L
            try {
                entries.forEach { entry ->
                    if (ordinal > MAX_RECORD_COUNT) throw ArchiveLimitException()
                    output.writeInt(RECORD_MARKER)
                    output.writeByte(entry.type.code)
                    output.writeLong(ordinal)
                    writePath(output, pathFor(ordinal, entry.type))
                    val record = ChunkedRecordOutput(output, MAX_RECORD_CONTENT_BYTES)
                    try {
                        entry.writeTo(record)
                    } catch (error: ArchiveLimitException) {
                        throw error
                    } catch (error: IOException) {
                        throw ArchiveSourceException(error)
                    }
                    val contentBytes = record.finish()
                    if (record.firstByte != RECORD_SCHEMA_VERSION) throw ArchiveSourceException()
                    totalContentBytes = addWithinLimit(
                        totalContentBytes,
                        contentBytes,
                        MAX_TOTAL_CONTENT_BYTES,
                    )
                    when (entry.type) {
                        AuroraBackupEntryType.SMS -> smsCount += 1L
                        AuroraBackupEntryType.MMS -> mmsCount += 1L
                        AuroraBackupEntryType.MMS_PART -> partCount += 1L
                    }
                    ordinal += 1L
                }
            } catch (error: ArchiveLimitException) {
                throw error
            } catch (error: ArchiveSourceException) {
                throw error
            } catch (error: RuntimeException) {
                throw ArchiveSourceException(error)
            }
            val summary = AuroraBackupSummary(
                entryCount = ordinal - 1L,
                smsCount = smsCount,
                mmsCount = mmsCount,
                mmsPartCount = partCount,
                plaintextContentBytes = totalContentBytes,
            )
            output.writeInt(RECORD_MARKER)
            output.writeByte(END_RECORD_CODE)
            output.writeLong(0L)
            writePath(output, END_PATH)
            val endRecord = ChunkedRecordOutput(output, END_CONTENT_MAX_BYTES)
            DataOutputStream(endRecord).apply {
                writeByte(RECORD_SCHEMA_VERSION)
                writeLong(summary.entryCount)
                writeLong(summary.smsCount)
                writeLong(summary.mmsCount)
                writeLong(summary.mmsPartCount)
                writeLong(summary.plaintextContentBytes)
                flush()
            }
            check(endRecord.finish() == END_CONTENT_BYTES.toLong())
            output.flush()
            return summary
        }

        override fun close() {
            if (!closed) {
                closed = true
                output.close()
            }
        }
    }

    private data class Header(
        val version: Int,
        val kdf: Int,
        val iterations: Int,
        val salt: ByteArray,
        val cipher: Int,
        val nonce: ByteArray,
    )

    private data class RecordContent(
        val length: Long,
        val firstByte: Int,
        val bytes: ByteArray = ByteArray(0),
    )

    companion object {
        const val FORMAT_VERSION: Int = 1
        const val KDF_ITERATIONS: Int = 300_000
        const val MINIMUM_PASSPHRASE_CHARACTERS: Int = 12
        const val MAXIMUM_PASSPHRASE_CHARACTERS: Int = 1_024
        const val MAX_RECORD_COUNT: Long = 2_000_000L
        const val MAX_RECORD_CONTENT_BYTES: Long = 64L * 1_024L * 1_024L
        const val MAX_TOTAL_CONTENT_BYTES: Long = 16L * 1_024L * 1_024L * 1_024L
        const val MAX_PLAINTEXT_ARCHIVE_BYTES: Long = MAX_TOTAL_CONTENT_BYTES + 512L * 1_024L * 1_024L
        const val MAX_ENCRYPTED_ARCHIVE_BYTES: Long = MAX_PLAINTEXT_ARCHIVE_BYTES + 1_024L

        private val OUTER_MAGIC = "AURORABK".toByteArray(StandardCharsets.US_ASCII)
        private val INNER_MAGIC = "AURSTRM1".toByteArray(StandardCharsets.US_ASCII)
        private const val KDF_ID = 1
        private const val CIPHER_ID = 1
        private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val SALT_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val HEADER_BYTES = 8 + 1 + 1 + 4 + SALT_BYTES + 1 + NONCE_BYTES
        private const val MINIMUM_ACCEPTED_ITERATIONS = 100_000
        private const val MAXIMUM_ACCEPTED_ITERATIONS = 2_000_000
        private const val RECORD_MARKER = 0x41555231
        private const val RECORD_SCHEMA_VERSION = 1
        private const val END_RECORD_CODE = 127
        private const val END_PATH = "manifest/end"
        private const val END_CONTENT_BYTES = 1 + (5 * 8)
        private const val END_CONTENT_MAX_BYTES = 128L
        private const val MAX_PATH_BYTES = 64
        private const val RECORD_CHUNK_BYTES = 64 * 1_024
        private const val DIGEST_BYTES = 32
        private const val COPY_BUFFER_BYTES = 64 * 1_024

        fun passphraseMeetsPolicy(passphrase: CharArray): Boolean =
            passphrase.size in MINIMUM_PASSPHRASE_CHARACTERS..MAXIMUM_PASSPHRASE_CHARACTERS &&
                passphrase.any { !it.isWhitespace() } &&
                passphrase.none(Char::isISOControl)

        internal fun pathFor(ordinal: Long, type: AuroraBackupEntryType): String =
            "records/${ordinal.toString().padStart(12, '0')}.${type.extension}"

        private fun encodeHeader(salt: ByteArray, nonce: ByteArray): ByteArray =
            ByteArrayOutputStream(HEADER_BYTES).use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.write(OUTER_MAGIC)
                    output.writeByte(FORMAT_VERSION)
                    output.writeByte(KDF_ID)
                    output.writeInt(KDF_ITERATIONS)
                    output.write(salt)
                    output.writeByte(CIPHER_ID)
                    output.write(nonce)
                }
                bytes.toByteArray()
            }

        private fun decodeHeader(header: ByteArray): Header? = try {
            if (header.size != HEADER_BYTES) return null
            DataInputStream(ByteArrayInputStream(header)).use { input ->
                if (!MessageDigest.isEqual(readExact(input, OUTER_MAGIC.size), OUTER_MAGIC)) return null
                Header(
                    version = input.readUnsignedByte(),
                    kdf = input.readUnsignedByte(),
                    iterations = input.readInt(),
                    salt = readExact(input, SALT_BYTES),
                    cipher = input.readUnsignedByte(),
                    nonce = readExact(input, NONCE_BYTES),
                ).takeIf { input.read() == -1 }
            }
        } catch (_: IOException) {
            null
        }

        private fun writePath(output: DataOutputStream, path: String) {
            val encoded = path.toByteArray(StandardCharsets.UTF_8)
            if (encoded.isEmpty() || encoded.size > MAX_PATH_BYTES) throw ArchiveLimitException()
            output.writeShort(encoded.size)
            output.write(encoded)
        }

        private fun readPath(input: DataInputStream): String {
            val length = input.readUnsignedShort()
            if (length !in 1..MAX_PATH_BYTES) throw InvalidArchiveException()
            val bytes = readExact(input, length)
            return try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                throw InvalidArchiveException()
            }
        }

        private fun readRecordContent(
            input: DataInputStream,
            maximum: Long,
            retain: Boolean = false,
            validate: ((InputStream) -> Boolean)? = null,
        ): RecordContent {
            val record = ChunkedRecordInputStream(input, maximum)
            val retained = if (retain) ByteArrayOutputStream() else null
            val valid = if (validate != null) {
                validate(record)
            } else {
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = record.read(buffer)
                    if (read < 0) break
                    retained?.write(buffer, 0, read)
                }
                true
            }
            if (!valid || !record.finished || record.total == 0L) throw InvalidArchiveException()
            return RecordContent(record.total, record.firstByte, retained?.toByteArray() ?: ByteArray(0))
        }

        private fun decodeEndSummary(bytes: ByteArray): AuroraBackupSummary? = try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readUnsignedByte() != RECORD_SCHEMA_VERSION) return null
                AuroraBackupSummary(
                    entryCount = input.readLong(),
                    smsCount = input.readLong(),
                    mmsCount = input.readLong(),
                    mmsPartCount = input.readLong(),
                    plaintextContentBytes = input.readLong(),
                ).takeIf { input.read() == -1 }
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun readExact(input: InputStream, size: Int): ByteArray {
            val result = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val read = input.read(result, offset, size - offset)
                if (read < 0) throw IOException("Unexpected end of archive")
                if (read == 0) continue
                offset += read
            }
            return result
        }

        private fun addWithinLimit(current: Long, added: Long, maximum: Long): Long {
            if (added < 0L || current > maximum - added) throw ArchiveLimitException()
            return current + added
        }
    }
}

private class ChunkedRecordInputStream(
    private val input: DataInputStream,
    private val maximum: Long,
) : InputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var remainingInChunk = 0
    var total: Long = 0L
        private set
    var firstByte: Int = -1
        private set
    var finished: Boolean = false
        private set

    override fun read(): Int {
        if (!prepareChunk()) return -1
        val value = input.read()
        if (value < 0) throw IOException("Unexpected end of record")
        account(byteArrayOf(value.toByte()), 0, 1)
        return value
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        destination.checkRange(offset, length)
        if (length == 0) return 0
        if (!prepareChunk()) return -1
        val requested = minOf(length, remainingInChunk)
        var readTotal = 0
        while (readTotal < requested) {
            val read = input.read(destination, offset + readTotal, requested - readTotal)
            if (read < 0) throw IOException("Unexpected end of record")
            if (read == 0) continue
            readTotal += read
        }
        account(destination, offset, readTotal)
        return readTotal
    }

    private fun prepareChunk(): Boolean {
        if (finished) return false
        if (remainingInChunk > 0) return true
        val chunkLength = input.readInt()
        if (chunkLength == 0) {
            val expected = ByteArray(DIGEST_LENGTH_BYTES)
            input.readFully(expected)
            if (!MessageDigest.isEqual(digest.digest(), expected)) throw InvalidArchiveException()
            finished = true
            return false
        }
        if (chunkLength !in 1..MAXIMUM_CHUNK_BYTES) throw InvalidArchiveException()
        if (total > maximum - chunkLength.toLong()) throw ArchiveLimitException()
        remainingInChunk = chunkLength
        return true
    }

    private fun account(bytes: ByteArray, offset: Int, length: Int) {
        if (firstByte < 0 && length > 0) firstByte = bytes[offset].toInt() and 0xff
        digest.update(bytes, offset, length)
        total += length.toLong()
        remainingInChunk -= length
    }

    private companion object {
        const val MAXIMUM_CHUNK_BYTES = 64 * 1_024
        const val DIGEST_LENGTH_BYTES = 32
    }
}

private class ChunkedRecordOutput(
    private val output: DataOutputStream,
    private val maximum: Long,
) : OutputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val buffer = ByteArray(64 * 1_024)
    private var buffered = 0
    private var total = 0L
    private var finished = false
    var firstByte: Int = -1
        private set

    override fun write(value: Int) {
        ensureCapacity(1)
        if (firstByte < 0) firstByte = value and 0xff
        buffer[buffered++] = value.toByte()
        if (buffered == buffer.size) flushChunk()
    }

    override fun write(source: ByteArray, offset: Int, length: Int) {
        source.checkRange(offset, length)
        ensureCapacity(length)
        if (length > 0 && firstByte < 0) firstByte = source[offset].toInt() and 0xff
        var position = offset
        var remaining = length
        while (remaining > 0) {
            val copied = minOf(remaining, buffer.size - buffered)
            source.copyInto(buffer, buffered, position, position + copied)
            buffered += copied
            position += copied
            remaining -= copied
            if (buffered == buffer.size) flushChunk()
        }
    }

    override fun flush() {
        // Record framing is emitted only by finish(), never by a nested writer's flush().
    }

    override fun close() {
        // The archive writer owns the record and outer cipher streams.
    }

    fun finish(): Long {
        check(!finished)
        finished = true
        flushChunk()
        output.writeInt(0)
        output.write(digest.digest())
        return total
    }

    private fun ensureCapacity(length: Int) {
        if (finished) throw IOException("Record is finished")
        if (length < 0 || total + buffered.toLong() > maximum - length.toLong()) {
            throw ArchiveLimitException()
        }
    }

    private fun flushChunk() {
        if (buffered == 0) return
        output.writeInt(buffered)
        output.write(buffer, 0, buffered)
        digest.update(buffer, 0, buffered)
        total += buffered.toLong()
        buffered = 0
    }
}

private class MaximumInputStream(
    source: InputStream,
    private val maximum: Long,
) : FilterInputStream(source) {
    private var count = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) account(1L)
        return value
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        val read = super.read(destination, offset, length)
        if (read > 0) account(read.toLong())
        return read
    }

    private fun account(read: Long) {
        if (count > maximum - read) throw ArchiveLimitException()
        count += read
    }
}

private class MaximumOutputStream(
    destination: OutputStream,
    private val maximum: Long,
) : FilterOutputStream(destination) {
    var written: Long = 0L
        private set

    override fun write(value: Int) {
        account(1L)
        out.write(value)
    }

    override fun write(source: ByteArray, offset: Int, length: Int) {
        source.checkRange(offset, length)
        account(length.toLong())
        out.write(source, offset, length)
    }

    private fun account(size: Long) {
        if (written > maximum - size) throw ArchiveLimitException()
        written += size
    }
}

private class NonClosingOutputStream(destination: OutputStream) : FilterOutputStream(destination) {
    override fun close() = flush()
}

private class ArchiveLimitException : IOException()
private class InvalidArchiveException : IOException()
private class ArchiveSourceException(cause: Throwable? = null) : IOException(cause)

private fun ByteArray.checkRange(offset: Int, length: Int) {
    if (offset < 0 || length < 0 || offset > size - length) throw IndexOutOfBoundsException()
}
