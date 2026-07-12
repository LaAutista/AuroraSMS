// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.benchmark

import org.aurorasms.core.index.storage.IndexedMessageEntity
import org.aurorasms.core.index.sync.IndexProjectionMapper
import org.aurorasms.core.testing.SyntheticIndexFixture
import org.aurorasms.core.testing.SyntheticIndexFixtures
import org.aurorasms.core.testing.SyntheticIndexRecord
import org.aurorasms.core.testing.SyntheticIndexShape

/**
 * Benchmark adapter for Aurora's shared deterministic, synthetic-only provider fixtures.
 *
 * The shared corpus owns every generated value and edge-case distribution. This adapter adds only
 * the production provider-to-index projection and generation marker required by the Room harness.
 * No value is derived from a device, account, Telephony provider, contact, message, or private
 * reference asset.
 */
internal object DeterministicIndexFixtures {
    const val FIXED_SEED: Long = SyntheticIndexFixtures.FIXED_SEED
    const val BATCH_SIZE: Int = SyntheticIndexFixtures.MAX_WINDOW_SIZE
    const val COMMON_SEARCH_TOKEN: String = SyntheticIndexFixtures.COMMON_SEARCH_TOKEN

    val requiredMessageScales: List<FixtureShape> = listOf(
        FixtureShape.EMPTY,
        FixtureShape.SINGLE,
        FixtureShape.TEN_THOUSAND,
        FixtureShape.HUNDRED_THOUSAND,
        FixtureShape.FIVE_HUNDRED_THOUSAND,
        FixtureShape.ONE_MILLION,
    )

    val requiredDimensions: List<FixtureShape> = listOf(
        FixtureShape.SINGLE_THREAD_250K,
        FixtureShape.SHALLOW_THREADS_20K,
    )

    fun batch(
        shape: FixtureShape,
        startOrdinal: Int,
        generationId: Long,
        maximumSize: Int = BATCH_SIZE,
    ): List<IndexedMessageEntity> {
        require(startOrdinal in 0..shape.messageCount) { "Fixture batch start is outside the shape" }
        require(generationId > 0L) { "Fixture generations must be positive" }
        require(maximumSize in 1..BATCH_SIZE) { "Fixture batches must stay within the reviewed bound" }
        return fixture(shape)
            .window(startIndex = startOrdinal, limit = maximumSize)
            .map { record -> record.toIndexedEntity(generationId) }
    }

    fun rowAt(
        shape: FixtureShape,
        ordinal: Int,
        generationId: Long,
    ): FixtureRow {
        require(generationId > 0L) { "Fixture generations must be positive" }
        val record = fixture(shape).recordAt(ordinal)
        return FixtureRow(
            entity = record.toIndexedEntity(generationId),
            retainedDuringReconciliation = !record.deletedFromProvider,
        )
    }

    fun entityAt(
        shape: FixtureShape,
        ordinal: Int,
        generationId: Long,
    ): IndexedMessageEntity = rowAt(shape, ordinal, generationId).entity

    fun retainedDuringReconciliation(shape: FixtureShape, ordinal: Int): Boolean =
        !fixture(shape).recordAt(ordinal).deletedFromProvider

    fun identityAt(shape: FixtureShape, ordinal: Int): FixtureIdentity {
        val record = fixture(shape).recordAt(ordinal)
        return FixtureIdentity(
            providerKind = when (record) {
                is SyntheticIndexRecord.Sms -> SMS_KIND
                is SyntheticIndexRecord.Mms -> MMS_KIND
            },
            providerId = record.providerId.value,
            providerThreadId = record.providerThreadId.value,
        )
    }

    private fun fixture(shape: FixtureShape): SyntheticIndexFixture =
        SyntheticIndexFixtures.fixture(shape.sourceShape)

    private fun SyntheticIndexRecord.toIndexedEntity(generationId: Long): IndexedMessageEntity =
        when (this) {
            is SyntheticIndexRecord.Sms -> IndexProjectionMapper.fromSms(providerMessage, generationId)
            is SyntheticIndexRecord.Mms -> IndexProjectionMapper.fromMms(providerMessage, generationId)
        }

    private const val SMS_KIND = 1
    private const val MMS_KIND = 2
}

internal enum class FixtureShape(
    val argumentValue: String,
    val sourceShape: SyntheticIndexShape,
) {
    EMPTY("0", SyntheticIndexShape.EMPTY),
    SINGLE("1", SyntheticIndexShape.SINGLE_MESSAGE),
    TEN_THOUSAND("10000", SyntheticIndexShape.MESSAGES_10_THOUSAND),
    HUNDRED_THOUSAND("100000", SyntheticIndexShape.MESSAGES_100_THOUSAND),
    FIVE_HUNDRED_THOUSAND("500000", SyntheticIndexShape.MESSAGES_500_THOUSAND),
    ONE_MILLION("1000000", SyntheticIndexShape.MESSAGES_1_MILLION),
    SINGLE_THREAD_250K("single-thread-250k", SyntheticIndexShape.SINGLE_THREAD_250_THOUSAND),
    SHALLOW_THREADS_20K("shallow-threads-20k", SyntheticIndexShape.SHALLOW_20_THOUSAND_THREADS),
    ;

    val messageCount: Int
        get() = sourceShape.messageCount

    val threadCount: Int
        get() = sourceShape.expectedThreadCount

    fun threadIdFor(ordinal: Int): Long =
        DeterministicIndexFixtures.identityAt(this, ordinal).providerThreadId

    companion object {
        fun fromArgument(value: String?): FixtureShape? = entries.firstOrNull {
            it.argumentValue == value
        }
    }
}

internal data class FixtureRow(
    val entity: IndexedMessageEntity,
    val retainedDuringReconciliation: Boolean,
)

internal data class FixtureIdentity(
    val providerKind: Int,
    val providerId: Long,
    val providerThreadId: Long,
)
