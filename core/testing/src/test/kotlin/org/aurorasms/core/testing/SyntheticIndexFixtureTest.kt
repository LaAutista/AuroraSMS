// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.testing

import org.aurorasms.core.model.MmsAttachmentSummary
import org.aurorasms.core.model.ProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyntheticIndexFixtureTest {
    @Test
    fun declaredShapes_coverEveryRequiredScaleAndDistribution() {
        assertEquals(
            listOf(0, 1, 10_000, 100_000, 500_000, 1_000_000, 250_000, 20_000),
            SyntheticIndexFixtures.declaredShapes.map(SyntheticIndexShape::messageCount),
        )
        assertEquals(
            1,
            SyntheticIndexShape.SINGLE_THREAD_250_THOUSAND.expectedThreadCount,
        )
        assertEquals(
            20_000,
            SyntheticIndexShape.SHALLOW_20_THOUSAND_THREADS.expectedThreadCount,
        )
    }

    @Test
    fun millionRowFixture_isDeterministicAtRepresentativeBoundaries() {
        val first = SyntheticIndexFixtures.fixture(SyntheticIndexShape.MESSAGES_1_MILLION)
        val second = SyntheticIndexFixtures.fixture(SyntheticIndexShape.MESSAGES_1_MILLION)
        val sampleIndices = listOf(0, 1, 2, 31, 499, 500, 499_999, 999_999)

        assertEquals(
            sampleIndices.map(first::recordAt),
            sampleIndices.map(second::recordAt),
        )
        assertNotEquals(
            first.recordAt(499_999),
            SyntheticIndexFixtures.fixture(
                shape = SyntheticIndexShape.MESSAGES_1_MILLION,
                seed = SyntheticIndexFixtures.FIXED_SEED + 1L,
            ).recordAt(499_999),
        )
    }

    @Test
    fun millionRowFixture_remainsLazyAndWindowsStayBounded() {
        val fixture = SyntheticIndexFixtures.fixture(SyntheticIndexShape.MESSAGES_1_MILLION)
        var observedRows = 0
        val records = fixture.records().map { record ->
            observedRows += 1
            record
        }

        assertEquals(0, observedRows)
        assertEquals(3, records.take(3).count())
        assertEquals(3, observedRows)
        assertEquals(500, fixture.window(startIndex = 999_250).size)
        assertEquals(250, fixture.window(startIndex = 999_750).size)
        assertTrue(fixture.window(startIndex = 1_000_000).isEmpty())
        expectIllegalArgument { fixture.window(startIndex = 0, limit = 501) }
        expectIllegalArgument { fixture.recordAt(index = 1_000_000) }
    }

    @Test
    fun corpus_containsCollisionTieReconciliationAndTextEdgeCases() {
        val fixture = SyntheticIndexFixtures.fixture(SyntheticIndexShape.MESSAGES_10_THOUSAND)
        val sample = fixture.window(startIndex = 0, limit = 500)
        val firstSms = sample[0]
        val firstMms = sample[1]

        assertEquals(ProviderKind.SMS, firstSms.providerId.kind)
        assertEquals(ProviderKind.MMS, firstMms.providerId.kind)
        assertEquals(firstSms.providerId.value, firstMms.providerId.value)
        assertEquals(firstSms.timestampMillis, firstMms.timestampMillis)
        assertEquals(firstSms.timestampMillis, sample[3].timestampMillis)
        assertTrue(sample[0].providerId.value > sample[2].providerId.value)
        assertTrue(sample.any { it.sender == null })
        assertTrue(sample.any { it.resolvedContactName == null })
        assertTrue(sample.any(SyntheticIndexRecord::deletedFromProvider))
        assertEquals(setOf(0, 1), sample.mapNotNull { it.subscriptionId?.value }.toSet())

        val bodies = sample.mapNotNull(SyntheticIndexRecord::body)
        assertTrue(bodies.any { it.contains("🚀") })
        assertTrue(bodies.any { it.contains('\u0301') })
        assertTrue(bodies.any { body -> body.any { it in '\u0600'..'\u06ff' } })
        assertTrue(bodies.any { it.length == SyntheticIndexFixtures.LONG_BODY_CHARACTERS })
        assertTrue(bodies.all { it.contains(SyntheticIndexFixtures.COMMON_SEARCH_TOKEN, ignoreCase = true) })

        val mmsRows = sample.filterIsInstance<SyntheticIndexRecord.Mms>()
        assertTrue(mmsRows.any { it.providerMessage.subject == null })
        assertTrue(mmsRows.any { it.providerMessage.body == null })
        assertTrue(
            mmsRows.any {
                it.providerMessage.attachments.attachmentCount ==
                    MmsAttachmentSummary.MAX_ATTACHMENT_COUNT &&
                    it.providerMessage.attachments.metadataTruncated
            },
        )
    }

    @Test
    fun singleThreadAndShallowThreadShapes_haveExactDeclaredTopology() {
        val singleThread = SyntheticIndexFixtures.fixture(
            SyntheticIndexShape.SINGLE_THREAD_250_THOUSAND,
        )
        assertEquals(
            1,
            listOf(0, 125_000, 249_999)
                .map(singleThread::recordAt)
                .map(SyntheticIndexRecord::providerThreadId)
                .toSet()
                .size,
        )

        val shallow = SyntheticIndexFixtures.fixture(
            SyntheticIndexShape.SHALLOW_20_THOUSAND_THREADS,
        )
        assertEquals(
            20_000,
            shallow.records().map(SyntheticIndexRecord::providerThreadId).toSet().size,
        )
    }

    @Test
    fun generatedValues_areClearlySyntheticAndStringOutputIsContentRedacted() {
        val rows = SyntheticIndexFixtures.fixture(SyntheticIndexShape.MESSAGES_10_THOUSAND)
            .window(startIndex = 0, limit = 500)

        rows.forEach { row ->
            row.sender?.let { sender ->
                assertTrue(
                    sender.value.startsWith("+120255501") ||
                        sender.value.endsWith(".example.invalid"),
                )
            }
            row.resolvedContactName?.let { assertTrue(it.startsWith("Synthetic Fixture Contact")) }
            row.subject?.let { assertTrue(it.startsWith("Synthetic fixture subject")) }
            val rendered = row.toString()
            assertTrue(rendered.contains("content=REDACTED"))
            row.body?.let { assertFalse(rendered.contains(it)) }
            row.sender?.let { assertFalse(rendered.contains(it.value)) }
            row.subject?.let { assertFalse(rendered.contains(it)) }
            row.resolvedContactName?.let { assertFalse(rendered.contains(it)) }
            val fingerprint = when (row) {
                is SyntheticIndexRecord.Sms -> row.providerMessage.syncFingerprint
                is SyntheticIndexRecord.Mms -> row.providerMessage.syncFingerprint
            }
            assertFalse(rendered.contains(fingerprint.toStorageToken()))
        }

        val mms = rows.filterIsInstance<SyntheticIndexRecord.Mms>()
            .first { it.providerMessage.attachments.attachmentCount > 0 }
        mms.providerMessage.attachments.contentTypes.forEach { attachmentType ->
            attachmentType.displayName?.let { displayName ->
                assertFalse(attachmentType.toString().contains(displayName))
            }
        }
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected bounded-input rejection.
        }
    }
}
