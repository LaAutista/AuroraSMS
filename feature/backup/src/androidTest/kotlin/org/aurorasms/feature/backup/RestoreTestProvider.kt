// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.backup

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/** Stateful synthetic SMS/MMS provider. It never proxies or reads device Telephony data. */
class RestoreTestProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val requested = projection ?: throw IllegalArgumentException("projection required")
        val rows = synchronized(lock) {
            queryCount += 1
            rowsFor(uri)
                .filter { row -> matches(row, selection, selectionArgs) }
                .sortedBy { row -> (row[ID_COLUMN] as Number).toLong() }
                .map(::ContentValues)
        }
        return MatrixCursor(requested).apply {
            rows.forEach { values ->
                addRow(Array(requested.size) { index -> values[requested[index]] })
            }
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val supplied = ContentValues(values ?: ContentValues())
        return synchronized(lock) {
            mutationCount += 1
            when (val route = route(uri)) {
                Route.SmsCollection -> {
                    val id = nextMessageId++
                    supplied.put(ID_COLUMN, id)
                    sms[id] = supplied
                    uri.buildUpon().appendPath(id.toString()).build()
                }
                Route.MmsCollection -> {
                    val id = nextMessageId++
                    supplied.put(ID_COLUMN, id)
                    mms[id] = supplied
                    uri.buildUpon().appendPath(id.toString()).build()
                }
                is Route.MmsAddressCollection -> {
                    require(mms.containsKey(route.parentId))
                    val id = nextAddressId++
                    supplied.put(ID_COLUMN, id)
                    addresses.getOrPut(route.parentId, ::linkedMapOf)[id] = supplied
                    uri.buildUpon().appendPath(id.toString()).build()
                }
                is Route.MmsPartCollection -> {
                    require(mms.containsKey(route.parentId))
                    val id = nextPartId++
                    supplied.put(ID_COLUMN, id)
                    supplied.put(MMS_PART_PARENT, route.parentId)
                    val file = File(fixtureContext().cacheDir, "restore-test-part-$id.bin")
                    file.delete()
                    parts[id] = PartRow(supplied, file)
                    baseUri(uri).buildUpon().appendPath(PART_PATH).appendPath(id.toString()).build()
                }
                else -> throw IllegalArgumentException("unsupported insert URI $uri")
            }
        }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = synchronized(lock) {
        mutationCount += 1
        val changes = values ?: ContentValues()
        val routed = route(uri)
        if (
            rejectNextSmsBoxCommit &&
            routed is Route.ExactSms &&
            changes.size() == 1 &&
            changes.containsKey("type")
        ) {
            rejectNextSmsBoxCommit = false
            return@synchronized 0
        }
        val rows = mutableRowsFor(uri).filter { row -> matches(row, selection, selectionArgs) }
        rows.forEach { row ->
            row.putAll(changes)
            if (normalizeNextMmsSubject && routed is Route.ExactMms && changes.containsKey("sub")) {
                row.put("sub", "provider-normalized-subject")
                normalizeNextMmsSubject = false
            }
        }
        rows.size
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = synchronized(lock) {
        mutationCount += 1
        when (val route = route(uri)) {
            Route.SmsCollection -> removeMatching(sms, selection, selectionArgs)
            is Route.ExactSms -> removeExact(sms, route.id, selection, selectionArgs)
            Route.MmsCollection -> removeMatchingMms(selection, selectionArgs)
            is Route.ExactMms -> removeExactMms(route.id, selection, selectionArgs)
            is Route.MmsAddressCollection -> {
                addresses.remove(route.parentId)?.size ?: 0
            }
            is Route.MmsPartCollection -> {
                val owned = parts.filterValues {
                    it.values.getAsLong(MMS_PART_PARENT) == route.parentId
                }.keys
                owned.forEach { id -> parts.remove(id)?.file?.delete() }
                owned.size
            }
            is Route.ExactPart -> {
                val removed = parts.remove(route.id)
                removed?.file?.delete()
                if (removed == null) 0 else 1
            }
            else -> 0
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val route = route(uri) as? Route.ExactPart ?: throw FileNotFoundException("unknown part")
        return synchronized(lock) {
            val row = parts[route.id] ?: throw FileNotFoundException("missing part")
            when {
                mode.startsWith("w") -> {
                    row.values.put(MMS_PART_DATA, row.file.absolutePath)
                    ParcelFileDescriptor.open(
                        row.file,
                        ParcelFileDescriptor.MODE_CREATE or
                            ParcelFileDescriptor.MODE_TRUNCATE or
                            ParcelFileDescriptor.MODE_READ_WRITE,
                    )
                }
                mode.startsWith("r") && row.values.getAsString(MMS_PART_DATA) != null -> {
                    ParcelFileDescriptor.open(row.file, ParcelFileDescriptor.MODE_READ_ONLY)
                }
                else -> throw FileNotFoundException("part mode")
            }
        }
    }

    override fun getType(uri: Uri): String? = null

    private fun rowsFor(uri: Uri): List<ContentValues> = when (val route = route(uri)) {
        Route.SmsCollection -> sms.values.toList()
        is Route.ExactSms -> listOfNotNull(sms[route.id])
        Route.MmsCollection -> mms.values.toList()
        is Route.ExactMms -> listOfNotNull(mms[route.id])
        is Route.MmsAddressCollection -> addresses[route.parentId]?.values?.toList().orEmpty()
        is Route.MmsPartCollection -> parts.values
            .filter { it.values.getAsLong(MMS_PART_PARENT) == route.parentId }
            .map(PartRow::values)
        is Route.ExactPart -> listOfNotNull(parts[route.id]?.values)
        else -> emptyList()
    }

    private fun mutableRowsFor(uri: Uri): List<ContentValues> = rowsFor(uri)

    private fun removeMatching(
        source: MutableMap<Long, ContentValues>,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        val ids = source.filterValues { row -> matches(row, selection, selectionArgs) }.keys
        ids.forEach(source::remove)
        return ids.size
    }

    private fun removeExact(
        source: MutableMap<Long, ContentValues>,
        id: Long,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        val row = source[id] ?: return 0
        if (!matches(row, selection, selectionArgs)) return 0
        source.remove(id)
        return 1
    }

    private fun removeMatchingMms(
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        val ids = mms.filterValues { row -> matches(row, selection, selectionArgs) }.keys.toList()
        ids.forEach(::removeMmsCascade)
        return ids.size
    }

    private fun removeExactMms(
        id: Long,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        val row = mms[id] ?: return 0
        if (!matches(row, selection, selectionArgs)) return 0
        removeMmsCascade(id)
        return 1
    }

    private fun removeMmsCascade(id: Long) {
        mms.remove(id)
        addresses.remove(id)
        val partIds = parts.filterValues { it.values.getAsLong(MMS_PART_PARENT) == id }.keys.toList()
        partIds.forEach { partId -> parts.remove(partId)?.file?.delete() }
    }

    private fun matches(
        row: ContentValues,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Boolean {
        if (selection == null) return true
        val arguments = selectionArgs.orEmpty().iterator()
        for (rawClause in selection.split(" AND ")) {
            val clause = rawClause.trim()
            when {
                clause.endsWith(" IS NULL") -> {
                    val column = clause.removeSuffix(" IS NULL").trim()
                    if (row[column] != null) return false
                }
                clause.endsWith(" = ?") -> {
                    val column = clause.removeSuffix(" = ?").trim()
                    if (!arguments.hasNext() || row[column]?.toString() != arguments.next()) return false
                }
                else -> throw IllegalArgumentException("unsupported selection $clause")
            }
        }
        return !arguments.hasNext()
    }

    private fun route(uri: Uri): Route {
        val path = uri.pathSegments
        return when {
            path == listOf(SMS_PATH) -> Route.SmsCollection
            path.size == 2 && path[0] == SMS_PATH -> Route.ExactSms(path[1].toLong())
            path == listOf(MMS_PATH) -> Route.MmsCollection
            path.size == 2 && path[0] == MMS_PATH -> Route.ExactMms(path[1].toLong())
            path.size == 3 && path[0] == MMS_PATH && path[2] == ADDRESS_PATH -> {
                Route.MmsAddressCollection(path[1].toLong())
            }
            path.size == 3 && path[0] == MMS_PATH && path[2] == PART_PATH -> {
                Route.MmsPartCollection(path[1].toLong())
            }
            path.size == 2 && path[0] == PART_PATH -> Route.ExactPart(path[1].toLong())
            else -> Route.Unknown
        }
    }

    private fun baseUri(uri: Uri): Uri = uri.buildUpon().path(null).build()

    private fun fixtureContext() = context ?: throw IllegalStateException("provider context")

    private sealed interface Route {
        data object SmsCollection : Route
        data class ExactSms(val id: Long) : Route
        data object MmsCollection : Route
        data class ExactMms(val id: Long) : Route
        data class MmsAddressCollection(val parentId: Long) : Route
        data class MmsPartCollection(val parentId: Long) : Route
        data class ExactPart(val id: Long) : Route
        data object Unknown : Route
    }

    private data class PartRow(val values: ContentValues, val file: File)

    companion object {
        const val AUTHORITY = "org.aurorasms.feature.backup.test.restore.provider"
        private const val SMS_PATH = "sms"
        private const val MMS_PATH = "mms"
        private const val ADDRESS_PATH = "addr"
        private const val PART_PATH = "part"
        private const val ID_COLUMN = "_id"
        private const val MMS_PART_PARENT = "mid"
        private const val MMS_PART_DATA = "_data"
        private val lock = Any()
        private val sms = linkedMapOf<Long, ContentValues>()
        private val mms = linkedMapOf<Long, ContentValues>()
        private val addresses = linkedMapOf<Long, LinkedHashMap<Long, ContentValues>>()
        private val parts = linkedMapOf<Long, PartRow>()
        private var nextMessageId = 1L
        private var nextAddressId = 1L
        private var nextPartId = 1L
        private var queryCount = 0
        private var mutationCount = 0
        private var normalizeNextMmsSubject = false
        private var rejectNextSmsBoxCommit = false

        fun reset() = synchronized(lock) {
            parts.values.forEach { it.file.delete() }
            sms.clear()
            mms.clear()
            addresses.clear()
            parts.clear()
            nextMessageId = 1L
            nextAddressId = 1L
            nextPartId = 1L
            queryCount = 0
            mutationCount = 0
            normalizeNextMmsSubject = false
            rejectNextSmsBoxCommit = false
        }

        fun smsCount(): Int = synchronized(lock) { sms.size }
        fun mmsCount(): Int = synchronized(lock) { mms.size }
        fun partCount(): Int = synchronized(lock) { parts.size }
        fun queryCount(): Int = synchronized(lock) { queryCount }
        fun mutationCount(): Int = synchronized(lock) { mutationCount }
        fun normalizeNextMmsSubject() = synchronized(lock) { normalizeNextMmsSubject = true }
        fun rejectNextSmsBoxCommit() = synchronized(lock) { rejectNextSmsBoxCommit = true }

        fun smsValue(id: Long, column: String): Any? = synchronized(lock) { sms[id]?.get(column) }
        fun mmsValue(id: Long, column: String): Any? = synchronized(lock) { mms[id]?.get(column) }
        fun binaryPayloads(): List<ByteArray> = synchronized(lock) {
            parts.values.filter { it.values.getAsString(MMS_PART_DATA) != null }
                .map { it.file.readBytes() }
        }

        fun mutateSms(id: Long, column: String, value: Any?) = synchronized(lock) {
            val row = sms.getValue(id)
            if (value == null) row.putNull(column) else row.put(column, value.toString())
        }

        fun mutateMms(id: Long, column: String, value: Any?) = synchronized(lock) {
            val row = mms.getValue(id)
            if (value == null) row.putNull(column) else row.put(column, value.toString())
        }
    }
}
