// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aurorasms.core.index.storage.IndexDatabaseFactory
import org.aurorasms.core.index.storage.IndexDatabaseOpenResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndexRecoveryIsolationTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().context
    private val stateName = "aurora_state.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(IndexDatabaseFactory.DATABASE_NAME)
        context.deleteDatabase(stateName)
    }

    @Test
    fun rebuildingKnownIndexCannotDeleteStateDatabase() {
        context.openOrCreateDatabase(stateName, Context.MODE_PRIVATE, null).use { state ->
            state.execSQL("CREATE TABLE sentinel(value TEXT NOT NULL)")
            state.execSQL("INSERT INTO sentinel(value) VALUES ('durable')")
        }
        val index = IndexDatabaseFactory.create(context)
        index.openHelper.writableDatabase
        val rebuilt = IndexDatabaseFactory.recoverKnownIndex(context, index)
        rebuilt.openHelper.writableDatabase
        rebuilt.close()

        context.openOrCreateDatabase(stateName, Context.MODE_PRIVATE, null).use { state ->
            state.rawQuery("SELECT value FROM sentinel", null).use { cursor ->
                cursor.moveToFirst()
                assertEquals("durable", cursor.getString(0))
            }
        }
    }

    @Test
    fun openDetectsCorruptKnownIndexAndRebuildsIt() {
        val path = context.getDatabasePath(IndexDatabaseFactory.DATABASE_NAME)
        val valid = IndexDatabaseFactory.create(context)
        valid.openHelper.writableDatabase
        valid.close()
        val corruptBytes = path.readBytes()
        for (index in 100 until corruptBytes.size) corruptBytes[index] = 0x7f
        path.writeBytes(corruptBytes)

        val opened = IndexDatabaseFactory.open(context)

        assertTrue(
            "result=$opened fileExists=${path.exists()} fileSize=${path.length()}",
            opened is IndexDatabaseOpenResult.Opened,
        )
        opened as IndexDatabaseOpenResult.Opened
        assertTrue(opened.recovered)
        assertEquals(0L, opened.database.indexedMessageDao().countBlockingForTest())
        opened.database.close()
    }
}

private fun org.aurorasms.core.index.storage.IndexedMessageDao.countBlockingForTest(): Long =
    kotlinx.coroutines.runBlocking { count() }
