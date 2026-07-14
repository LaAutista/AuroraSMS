// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.AppearanceOverride
import org.aurorasms.core.state.AppearanceOverrideRevision
import org.aurorasms.core.state.AppearanceParticipantSetKey
import org.aurorasms.core.state.AppearanceProfileId
import org.aurorasms.core.state.AppearanceScope
import org.aurorasms.core.state.AppearanceScreenScope

@Entity(
    tableName = "appearance_screen_overrides",
    foreignKeys = [
        ForeignKey(
            entity = AppearanceProfileEntity::class,
            parentColumns = ["profile_id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["profile_id"])],
)
internal data class AppearanceScreenOverrideEntity(
    @PrimaryKey
    @ColumnInfo(name = "screen_code")
    val screenCode: String,
    @ColumnInfo(name = "profile_id")
    val profileId: Long,
    @ColumnInfo(name = "revision")
    val revision: Long,
) {
    init {
        requireNotNull(AppearanceScreenScope.fromStorageCode(screenCode)) {
            "Unknown stored appearance screen scope"
        }
        AppearanceProfileId(profileId)
        AppearanceOverrideRevision(revision)
    }

    override fun toString(): String = "AppearanceScreenOverrideEntity(REDACTED)"
}

@Entity(
    tableName = "appearance_conversation_overrides",
    foreignKeys = [
        ForeignKey(
            entity = AppearanceProfileEntity::class,
            parentColumns = ["profile_id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["provider_thread_id"]),
        Index(value = ["profile_id"]),
    ],
)
internal data class AppearanceConversationOverrideEntity(
    @PrimaryKey
    @ColumnInfo(name = "participant_set_key")
    val participantSetKey: String,
    @ColumnInfo(name = "provider_thread_id")
    val providerThreadId: Long,
    @ColumnInfo(name = "profile_id")
    val profileId: Long,
    @ColumnInfo(name = "revision")
    val revision: Long,
) {
    init {
        AppearanceParticipantSetKey.fromStorageValue(participantSetKey)
        ProviderThreadId(providerThreadId)
        AppearanceProfileId(profileId)
        AppearanceOverrideRevision(revision)
    }

    override fun toString(): String = "AppearanceConversationOverrideEntity(REDACTED)"
}

internal fun AppearanceScreenOverrideEntity.toDomain(): AppearanceOverride = AppearanceOverride(
    scope = AppearanceScope.Screen(
        checkNotNull(AppearanceScreenScope.fromStorageCode(screenCode)),
    ),
    profileId = AppearanceProfileId(profileId),
    revision = AppearanceOverrideRevision(revision),
)

internal fun AppearanceConversationOverrideEntity.toDomain(
    requestedScope: AppearanceScope.Conversation? = null,
): AppearanceOverride {
    val storedKey = AppearanceParticipantSetKey.fromStorageValue(participantSetKey)
    require(requestedScope == null || requestedScope.participantSetKey == storedKey) {
        "A requested appearance conversation scope does not match its stored participant key"
    }
    return AppearanceOverride(
        scope = requestedScope ?: AppearanceScope.Conversation(
            participantSetKey = storedKey,
            providerThreadId = ProviderThreadId(providerThreadId),
        ),
        profileId = AppearanceProfileId(profileId),
        revision = AppearanceOverrideRevision(revision),
    )
}
