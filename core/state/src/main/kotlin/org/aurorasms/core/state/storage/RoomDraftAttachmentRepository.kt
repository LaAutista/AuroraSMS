// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import org.aurorasms.core.state.DraftAttachment
import org.aurorasms.core.state.DraftAttachmentRepository
import org.aurorasms.core.state.DraftId
import org.aurorasms.core.state.DraftRepositoryResult
import org.aurorasms.core.state.DraftRevision
import org.aurorasms.core.state.DraftStorageOperation

class RoomDraftAttachmentRepository(
    private val database: AuroraStateDatabase,
) : DraftAttachmentRepository {
    private val draftDao = database.draftDao()
    private val attachmentDao = database.draftAttachmentDao()

    override suspend fun read(
        draftId: DraftId,
    ): DraftRepositoryResult<List<DraftAttachment>> = try {
        database.withTransaction {
            if (draftDao.findById(draftId.value) == null) {
                DraftRepositoryResult.NotFound
            } else {
                attachmentDao.findByDraftId(draftId.value).toDomainList()
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SQLiteException) {
        DraftRepositoryResult.StorageFailure(DraftStorageOperation.READ)
    } catch (_: IllegalArgumentException) {
        DraftRepositoryResult.CorruptData
    } catch (_: IllegalStateException) {
        DraftRepositoryResult.CorruptData
    }

    override suspend fun replace(
        draftId: DraftId,
        expectedRevision: DraftRevision,
        attachments: List<DraftAttachment>,
    ): DraftRepositoryResult<List<DraftAttachment>> {
        val frozen = attachments.toList()
        if (!DraftAttachment.isValidSet(frozen)) return DraftRepositoryResult.CorruptData
        return try {
            database.withTransaction {
                val draft = draftDao.findById(draftId.value)
                    ?: return@withTransaction DraftRepositoryResult.NotFound
                if (draft.updatedTimestampMillis != expectedRevision.updatedTimestampMillis) {
                    return@withTransaction DraftRepositoryResult.StaleWrite
                }
                attachmentDao.deleteByDraftId(draftId.value)
                if (frozen.isNotEmpty()) {
                    attachmentDao.insertAll(
                        frozen.mapIndexed { index, attachment ->
                            attachment.toEntity(draftId.value, index)
                        },
                    )
                }
                DraftRepositoryResult.Success(frozen)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SQLiteConstraintException) {
            DraftRepositoryResult.CorruptData
        } catch (_: SQLiteException) {
            DraftRepositoryResult.StorageFailure(DraftStorageOperation.UPDATE)
        } catch (_: IllegalArgumentException) {
            DraftRepositoryResult.CorruptData
        } catch (_: IllegalStateException) {
            DraftRepositoryResult.CorruptData
        }
    }
}

private fun List<DraftAttachmentEntity>.toDomainList(): DraftRepositoryResult<List<DraftAttachment>> {
    if (size > DraftAttachment.MAX_ATTACHMENTS) return DraftRepositoryResult.CorruptData
    val attachments = mapIndexed { index, entity -> entity.toDomain(index) }
    if (!DraftAttachment.isValidSet(attachments)) return DraftRepositoryResult.CorruptData
    return DraftRepositoryResult.Success(attachments)
}
