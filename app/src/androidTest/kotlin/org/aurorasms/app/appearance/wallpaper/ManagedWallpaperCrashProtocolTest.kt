// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.aurorasms.app.preview.BoundedMediaDecodeGate
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedWallpaperCrashProtocolTest {
    private val applicationContext = ApplicationProvider.getApplicationContext<Context>()
    private val noBackupSandbox = File(
        applicationContext.noBackupFilesDir,
        "wallpaper-crash-protocol-sandbox",
    )
    private val context = object : ContextWrapper(applicationContext) {
        override fun getApplicationContext(): Context = this

        override fun getNoBackupFilesDir(): File = noBackupSandbox
    }
    private val root = File(noBackupSandbox, "appearance/wallpapers")
    private val externalTarget = File(noBackupSandbox, "wallpaper-crash-protocol-target")
    private val encodedWebpFixture by lazy(::encodedWebp)

    @Before
    fun prepareFilesystem() {
        deleteTreeNoFollow(noBackupSandbox.toPath())
        Files.createDirectory(noBackupSandbox.toPath())
    }

    @After
    fun cleanFilesystem() {
        deleteTreeNoFollow(noBackupSandbox.toPath())
    }

    @Test
    fun everyUnsafeEntryBlocksReconcileWithoutPartialDeletionThenExactOwnedEntriesRecover() =
        runBlocking {
            val store = store()
            val referenced = installFinal(staticWebpVariant(1))
            val unreferenced = installFinal(staticWebpVariant(2))
            val pending = installExactPending()
            val targetBytes = byteArrayOf(0x31, 0x41, 0x59, 0x26)
            Files.write(externalTarget.toPath(), targetBytes)

            val unsafeFactories = listOf<() -> Path>(
                {
                    Files.write(ensureRoot().resolve("unexpected-entry"), byteArrayOf(1))
                },
                {
                    Files.write(ensureRoot().resolve(".pending-malformed"), byteArrayOf(2))
                },
                {
                    Files.createDirectory(ensureRoot().resolve("unexpected-directory"))
                },
                {
                    val link = ensureRoot().resolve("unexpected-link")
                    Os.symlink(externalTarget.absolutePath, link.toFile().absolutePath)
                    link
                },
            )

            unsafeFactories.forEach { createUnsafeEntry ->
                val unsafeEntry = createUnsafeEntry()

                assertEquals(
                    ManagedWallpaperReconcileResult.UNSAFE_DIRECTORY,
                    store.reconcile(setOf(referenced.mediaId)),
                )
                assertTrue(existsNoFollow(referenced.file.toPath()))
                assertTrue(existsNoFollow(unreferenced.file.toPath()))
                assertTrue(existsNoFollow(pending.toPath()))
                assertArrayEquals(targetBytes, Files.readAllBytes(externalTarget.toPath()))

                deleteTreeNoFollow(unsafeEntry)
            }

            assertEquals(
                ManagedWallpaperReconcileResult.COMPLETE,
                store.reconcile(setOf(referenced.mediaId)),
            )
            assertTrue(existsNoFollow(referenced.file.toPath()))
            assertFalse(existsNoFollow(unreferenced.file.toPath()))
            assertFalse(existsNoFollow(pending.toPath()))
            assertArrayEquals(targetBytes, Files.readAllBytes(externalTarget.toPath()))

            deleteTreeNoFollow(root.toPath())
            val directoryTarget = noBackupSandbox.toPath().resolve("directory-target")
            Files.createDirectory(directoryTarget)
            val directoryTargetFile = Files.write(
                directoryTarget.resolve("target-data"),
                targetBytes,
            )
            Os.symlink(directoryTarget.toFile().absolutePath, root.absolutePath)

            assertEquals(
                ManagedWallpaperReconcileResult.UNSAFE_DIRECTORY,
                store.reconcile(emptySet()),
            )
            assertArrayEquals(targetBytes, Files.readAllBytes(directoryTargetFile))
            deleteTreeNoFollow(root.toPath())
            assertArrayEquals(targetBytes, Files.readAllBytes(directoryTargetFile))
        }

    @Test
    fun invalidReferenceAuthorityAndOverBoundedDirectoryDeleteNothing() = runBlocking {
        val store = store()
        val final = installFinal(staticWebpVariant(3))
        val pending = installExactPending()

        assertEquals(
            ManagedWallpaperReconcileResult.INVALID_REFERENCE_SET,
            store.reconcile(setOf("invalid-reference")),
        )
        assertTrue(existsNoFollow(final.file.toPath()))
        assertTrue(existsNoFollow(pending.toPath()))

        val oversizedAuthority = syntheticMediaIds(MAXIMUM_WALLPAPER_FILE_COUNT + 1)
        assertEquals(MAXIMUM_WALLPAPER_FILE_COUNT + 1, oversizedAuthority.size)
        assertEquals(
            ManagedWallpaperReconcileResult.INVALID_REFERENCE_SET,
            store.reconcile(oversizedAuthority),
        )
        assertEquals(
            DurableWallpaperQuotaResult.LIMIT_EXCEEDED,
            store.validateDurableQuota(oversizedAuthority),
        )
        assertEquals(
            DurableWallpaperQuotaResult.INVALID_STATE,
            store.validateDurableQuota(setOf("invalid-reference")),
        )
        assertTrue(existsNoFollow(final.file.toPath()))
        assertTrue(existsNoFollow(pending.toPath()))

        deleteTreeNoFollow(root.toPath())
        repeat(MAXIMUM_WALLPAPER_STAGED_FILE_COUNT + 1) { ordinal ->
            installFinal(staticWebpVariant(ordinal + 100))
        }
        assertEquals(MAXIMUM_WALLPAPER_STAGED_FILE_COUNT + 1, directEntryCount())
        assertEquals(
            ManagedWallpaperReconcileResult.UNSAFE_DIRECTORY,
            store.reconcile(emptySet()),
        )
        assertEquals(MAXIMUM_WALLPAPER_STAGED_FILE_COUNT + 1, directEntryCount())
    }

    @Test
    fun callbackPrecedesFinalPublishedFaultAndReconcileRecoversTheLeasedOrphan() = runBlocking {
        val successfulTrace = mutableListOf<PersistenceTrace>()
        var successfulCallbackCount = 0
        val successfulStore = store(
            WallpaperPersistenceProbe { checkpoint ->
                successfulTrace += checkpoint.toTrace()
            },
        )
        val successful = successfulStore.import(testUri("valid.png"), emptySet()) {
            successfulCallbackCount += 1
            successfulTrace += PersistenceTrace.CANDIDATE_CALLBACK
        }
        assertTrue(successful is WallpaperImportResult.Ready)
        assertEquals(1, successfulCallbackCount)
        assertEquals(
            listOf(
                PersistenceTrace.APPEARANCE_DIRECTORY_CREATED,
                PersistenceTrace.APPEARANCE_PARENT_SYNCED,
                PersistenceTrace.WALLPAPER_DIRECTORY_CREATED,
                PersistenceTrace.WALLPAPER_PARENT_SYNCED,
                PersistenceTrace.PENDING_FILE_SYNCED,
                PersistenceTrace.PENDING_VERIFIED,
                PersistenceTrace.CANDIDATE_CALLBACK,
                PersistenceTrace.FINAL_PUBLISHED,
                PersistenceTrace.FINAL_DIRECTORY_SYNCED,
            ),
            successfulTrace,
        )
        assertEquals(
            ManagedWallpaperReconcileResult.COMPLETE,
            successfulStore.reconcile(emptySet()),
        )

        val trace = mutableListOf<PersistenceTrace>()
        var leasedCandidate: WallpaperImportResult.Ready? = null
        val faultingStore = store(
            WallpaperPersistenceProbe { checkpoint ->
                trace += checkpoint.toTrace()
                if (checkpoint == WallpaperPersistenceCheckpoint.FINAL_PUBLISHED) {
                    throw IOException("Injected persistence checkpoint failure")
                }
            },
        )

        val result = faultingStore.import(testUri("valid.png"), emptySet()) { candidate ->
            trace += PersistenceTrace.CANDIDATE_CALLBACK
            leasedCandidate = candidate
            assertTrue(existsNoFollow(managedFile(candidate.mediaId).toPath()))
        }

        assertTrue(result is WallpaperImportResult.Failed)
        assertEquals(
            WallpaperMediaFailure.STORAGE_FAILURE,
            (result as WallpaperImportResult.Failed).reason,
        )
        assertEquals(
            listOf(
                PersistenceTrace.PENDING_FILE_SYNCED,
                PersistenceTrace.PENDING_VERIFIED,
                PersistenceTrace.CANDIDATE_CALLBACK,
                PersistenceTrace.FINAL_PUBLISHED,
            ),
            trace,
        )
        assertTrue(leasedCandidate?.created == true)
        assertEquals(1, directFinalCount())
        assertEquals(0, directPendingCount())

        assertEquals(
            ManagedWallpaperReconcileResult.COMPLETE,
            store().reconcile(emptySet()),
        )
        assertEquals(0, directEntryCount())
    }

    @Test
    fun hierarchyParentSyncFaultsPublishNothingAndRetryResyncsVisibleDirectories() = runBlocking {
        val faultPoints = listOf(
            WallpaperPersistenceFaultPoint.BEFORE_APPEARANCE_PARENT_SYNC,
            WallpaperPersistenceFaultPoint.BEFORE_WALLPAPER_PARENT_SYNC,
        )

        faultPoints.forEach { injectedPoint ->
            deleteTreeNoFollow(noBackupSandbox.toPath())
            Files.createDirectory(noBackupSandbox.toPath())
            val observedFaultPoints = mutableListOf<WallpaperPersistenceFaultPoint>()
            var injectOnce = true
            var callbackCount = 0
            val faultingStore = store(
                faultInjector = WallpaperPersistenceFaultInjector { point ->
                    observedFaultPoints += point
                    if (point == injectedPoint && injectOnce) {
                        injectOnce = false
                        throw IOException("Injected parent sync failure")
                    }
                },
            )

            val failed = faultingStore.import(testUri("valid.png"), emptySet()) {
                callbackCount += 1
            }
            assertTrue(failed is WallpaperImportResult.Failed)
            assertEquals(
                WallpaperMediaFailure.STORAGE_FAILURE,
                (failed as WallpaperImportResult.Failed).reason,
            )
            assertEquals(0, callbackCount)
            assertEquals(0, directFinalCount())
            assertEquals(0, directPendingCount())
            assertTrue(injectedPoint in observedFaultPoints)

            observedFaultPoints.clear()
            val retry = faultingStore.import(testUri("valid.png"), emptySet()) {
                callbackCount += 1
            }
            assertTrue(retry is WallpaperImportResult.Ready)
            assertEquals(1, callbackCount)
            assertEquals(1, directFinalCount())
            assertEquals(0, directPendingCount())
            assertEquals(
                listOf(
                    WallpaperPersistenceFaultPoint.BEFORE_APPEARANCE_PARENT_SYNC,
                    WallpaperPersistenceFaultPoint.BEFORE_WALLPAPER_PARENT_SYNC,
                    WallpaperPersistenceFaultPoint.BEFORE_PENDING_WRITE,
                    WallpaperPersistenceFaultPoint.BEFORE_PENDING_FLUSH,
                    WallpaperPersistenceFaultPoint.BEFORE_PENDING_FILE_SYNC,
                    WallpaperPersistenceFaultPoint.BEFORE_FINAL_PUBLISH,
                    WallpaperPersistenceFaultPoint.BEFORE_FINAL_VERIFY,
                ),
                observedFaultPoints,
            )
        }
    }

    @Test
    fun pendingWriteFlushAndFileSyncFaultsLeaveNoFilesystemLease() = runBlocking {
        val faultPoints = listOf(
            WallpaperPersistenceFaultPoint.BEFORE_PENDING_WRITE,
            WallpaperPersistenceFaultPoint.BEFORE_PENDING_FLUSH,
            WallpaperPersistenceFaultPoint.BEFORE_PENDING_FILE_SYNC,
        )

        faultPoints.forEach { injectedPoint ->
            deleteTreeNoFollow(noBackupSandbox.toPath())
            Files.createDirectory(noBackupSandbox.toPath())
            val observedFaultPoints = mutableListOf<WallpaperPersistenceFaultPoint>()
            var callbackCount = 0
            val faultingStore = store(
                faultInjector = WallpaperPersistenceFaultInjector { point ->
                    observedFaultPoints += point
                    if (point == injectedPoint) {
                        throw IOException("Injected pending persistence failure")
                    }
                },
            )

            val failed = faultingStore.import(testUri("valid.png"), emptySet()) {
                callbackCount += 1
            }
            assertTrue(failed is WallpaperImportResult.Failed)
            assertEquals(
                WallpaperMediaFailure.STORAGE_FAILURE,
                (failed as WallpaperImportResult.Failed).reason,
            )
            assertEquals(0, callbackCount)
            assertTrue(injectedPoint in observedFaultPoints)
            assertTrue(existsNoFollow(root.toPath()))
            assertEquals(0, directPendingCount())
            assertEquals(0, directFinalCount())
            assertEquals(0, directEntryCount())
        }
    }

    @Test
    fun finalPublishAndVerifyFaultsDeleteOnlyTheOwnedFile() = runBlocking {
        val faultPoints = listOf(
            WallpaperPersistenceFaultPoint.BEFORE_FINAL_PUBLISH,
            WallpaperPersistenceFaultPoint.BEFORE_FINAL_VERIFY,
        )

        faultPoints.forEach { injectedPoint ->
            deleteTreeNoFollow(noBackupSandbox.toPath())
            Files.createDirectory(noBackupSandbox.toPath())
            var callbackCount = 0
            val faultingStore = store(
                faultInjector = WallpaperPersistenceFaultInjector { point ->
                    if (point == injectedPoint) {
                        throw IOException("Injected final publication failure")
                    }
                },
            )

            val failed = faultingStore.import(testUri("valid.png"), emptySet()) {
                callbackCount += 1
            }
            assertTrue(failed is WallpaperImportResult.Failed)
            assertEquals(
                WallpaperMediaFailure.STORAGE_FAILURE,
                (failed as WallpaperImportResult.Failed).reason,
            )
            assertEquals(0, callbackCount)
            assertTrue(existsNoFollow(root.toPath()))
            assertEquals(0, directPendingCount())
            assertEquals(0, directFinalCount())
            assertEquals(0, directEntryCount())
        }
    }

    @Test
    fun finalPublicationAtomicallyRenamesTheVerifiedSingleLinkPendingInode() = runBlocking {
        val seedStore = store()
        val seeded = seedStore.import(testUri("valid.png"), emptySet())
        assertTrue(seeded is WallpaperImportResult.Ready)
        val mediaId = (seeded as WallpaperImportResult.Ready).mediaId
        assertEquals(
            ManagedWallpaperReconcileResult.COMPLETE,
            seedStore.reconcile(emptySet()),
        )

        var pendingDevice: Long? = null
        var pendingInode: Long? = null
        var finalDevice: Long? = null
        var finalInode: Long? = null
        var callbackCount = 0
        val auditedStore = store(
            faultInjector = WallpaperPersistenceFaultInjector { point ->
                when (point) {
                    WallpaperPersistenceFaultPoint.BEFORE_FINAL_PUBLISH -> {
                        val pendingPath = Files.newDirectoryStream(root.toPath()).use { entries ->
                            entries.single { path ->
                                classifyManagedWallpaperFileName(path.fileName.toString()) is
                                    ManagedWallpaperFileClassification.Pending
                            }
                        }
                        val bytes = Files.readAllBytes(pendingPath)
                        assertTrue(wallpaperDerivativeMatches(mediaId, bytes))
                        assertFalse(existsNoFollow(managedFile(mediaId).toPath()))
                        val stat = Os.lstat(pendingPath.toFile().absolutePath)
                        assertEquals(1L, stat.st_nlink)
                        pendingDevice = stat.st_dev
                        pendingInode = stat.st_ino
                    }
                    WallpaperPersistenceFaultPoint.BEFORE_FINAL_VERIFY -> {
                        assertEquals(0, directPendingCount())
                        val stat = Os.lstat(managedFile(mediaId).absolutePath)
                        assertEquals(1L, stat.st_nlink)
                        finalDevice = stat.st_dev
                        finalInode = stat.st_ino
                    }
                    else -> Unit
                }
            },
        )

        val imported = auditedStore.import(testUri("valid.png"), emptySet()) { candidate ->
            callbackCount += 1
            assertEquals(mediaId, candidate.mediaId)
            assertEquals(0, directPendingCount())
            assertTrue(existsNoFollow(managedFile(candidate.mediaId).toPath()))
        }

        assertTrue(imported is WallpaperImportResult.Ready)
        assertEquals(1, callbackCount)
        assertEquals(pendingDevice, finalDevice)
        assertEquals(pendingInode, finalInode)
        assertTrue(pendingInode != null)
    }

    @Test
    fun storeInstancesShareOneMutationMutexAcrossTheManagedNamespace() = runBlocking {
        val seedStore = store()
        val seeded = seedStore.import(testUri("valid.png"), emptySet())
        assertTrue(seeded is WallpaperImportResult.Ready)
        val mediaId = (seeded as WallpaperImportResult.Ready).mediaId
        assertEquals(
            ManagedWallpaperReconcileResult.COMPLETE,
            seedStore.reconcile(emptySet()),
        )

        val firstPendingSynced = CompletableDeferred<Unit>()
        val releaseFirstImporter = CompletableDeferred<Unit>()
        val firstStore = store(
            probe = WallpaperPersistenceProbe { checkpoint ->
                if (checkpoint == WallpaperPersistenceCheckpoint.PENDING_FILE_SYNCED) {
                    firstPendingSynced.complete(Unit)
                    releaseFirstImporter.await()
                }
            },
        )
        val secondStore = store()
        val firstResult = async {
            firstStore.import(testUri("valid.png"), emptySet())
        }
        firstPendingSynced.await()

        val secondStarted = CompletableDeferred<Unit>()
        val secondResult = async {
            secondStarted.complete(Unit)
            secondStore.import(testUri("valid.png"), setOf(mediaId))
        }
        secondStarted.await()
        repeat(8) { yield() }

        assertFalse(secondResult.isCompleted)
        assertEquals(1, directPendingCount())
        assertEquals(0, directFinalCount())

        releaseFirstImporter.complete(Unit)
        val created = firstResult.await()
        val reused = secondResult.await()
        assertTrue(created is WallpaperImportResult.Ready && created.created)
        assertTrue(reused is WallpaperImportResult.Ready && !reused.created)
        assertEquals(0, directPendingCount())
        assertEquals(1, directFinalCount())
    }

    @Test
    fun preCallbackCleanupPreservesAReplacementWithDifferentFileIdentity() = runBlocking {
        val seedStore = store()
        val seeded = seedStore.import(testUri("valid.png"), emptySet())
        assertTrue(seeded is WallpaperImportResult.Ready)
        val mediaId = (seeded as WallpaperImportResult.Ready).mediaId
        assertEquals(
            ManagedWallpaperReconcileResult.COMPLETE,
            seedStore.reconcile(emptySet()),
        )
        val replacementBytes = staticWebpVariant(8_001)
        var callbackCount = 0
        val faultingStore = store(
            faultInjector = WallpaperPersistenceFaultInjector { point ->
                if (point == WallpaperPersistenceFaultPoint.BEFORE_FINAL_VERIFY) {
                    Files.delete(managedFile(mediaId).toPath())
                    Files.write(managedFile(mediaId).toPath(), replacementBytes)
                    throw IOException("Injected final replacement race")
                }
            },
        )

        val failed = faultingStore.import(testUri("valid.png"), emptySet()) {
            callbackCount += 1
        }
        assertTrue(failed is WallpaperImportResult.Failed)
        assertEquals(
            WallpaperMediaFailure.STORAGE_FAILURE,
            (failed as WallpaperImportResult.Failed).reason,
        )
        assertEquals(0, callbackCount)
        assertArrayEquals(replacementBytes, Files.readAllBytes(managedFile(mediaId).toPath()))
        assertEquals(0, directPendingCount())
        assertEquals(1, directFinalCount())
    }

    @Test
    fun finalPublicationNeverClobbersANameCreatedAtTheExclusiveCreateBoundary() = runBlocking {
        val seedStore = store()
        val seeded = seedStore.import(testUri("valid.png"), emptySet())
        assertTrue(seeded is WallpaperImportResult.Ready)
        val mediaId = (seeded as WallpaperImportResult.Ready).mediaId
        assertEquals(
            ManagedWallpaperReconcileResult.COMPLETE,
            seedStore.reconcile(emptySet()),
        )
        val conflictingBytes = staticWebpVariant(9_001)
        var createBoundaryCount = 0
        var callbackCount = 0
        val racingStore = store(
            faultInjector = WallpaperPersistenceFaultInjector { point ->
                if (point == WallpaperPersistenceFaultPoint.BEFORE_FINAL_PUBLISH) {
                    createBoundaryCount += 1
                    Files.write(managedFile(mediaId).toPath(), conflictingBytes)
                }
            },
        )

        val failed = racingStore.import(testUri("valid.png"), emptySet()) {
            callbackCount += 1
        }
        assertTrue(failed is WallpaperImportResult.Failed)
        assertEquals(
            WallpaperMediaFailure.STORAGE_FAILURE,
            (failed as WallpaperImportResult.Failed).reason,
        )
        assertEquals(1, createBoundaryCount)
        assertEquals(0, callbackCount)
        assertArrayEquals(conflictingBytes, Files.readAllBytes(managedFile(mediaId).toPath()))
        assertEquals(0, directPendingCount())
        assertEquals(1, directFinalCount())
        assertEquals(1, directEntryCount())
    }

    @Test
    fun fullDurableMaximumRecoversPartialUnreferencedFinalAfterCrash() = runBlocking {
        val store = store()
        val references = buildSet {
            repeat(MAXIMUM_WALLPAPER_FILE_COUNT) { ordinal ->
                add(installFinal(staticWebpVariant(ordinal + 10_000)).mediaId)
            }
        }
        val candidateBytes = staticWebpVariant(20_000)
        val candidateMediaId = wallpaperMediaId(candidateBytes)
        assertFalse(candidateMediaId in references)
        val final = managedFile(candidateMediaId)
        Files.write(final.toPath(), candidateBytes.copyOf(3))

        assertEquals(MAXIMUM_WALLPAPER_STAGED_FILE_COUNT, directEntryCount())
        assertEquals(0, directPendingCount())
        assertEquals(MAXIMUM_WALLPAPER_FILE_COUNT + 1, directFinalCount())

        assertEquals(
            ManagedWallpaperReconcileResult.COMPLETE,
            store.reconcile(references),
        )
        assertEquals(MAXIMUM_WALLPAPER_FILE_COUNT, directEntryCount())
        assertEquals(0, directPendingCount())
        assertEquals(MAXIMUM_WALLPAPER_FILE_COUNT, directFinalCount())
        assertFalse(existsNoFollow(final.toPath()))
        assertTrue(references.all { mediaId -> existsNoFollow(managedFile(mediaId).toPath()) })
    }

    @Test
    fun referencedButMissingDigestCannotBeRecreatedByImport() = runBlocking {
        val store = store()
        val source = testUri("valid.png")
        val initial = store.import(source, emptySet())
        assertTrue(initial is WallpaperImportResult.Ready)
        val mediaId = (initial as WallpaperImportResult.Ready).mediaId

        assertEquals(
            ManagedWallpaperReconcileResult.COMPLETE,
            store.reconcile(emptySet()),
        )
        assertFalse(existsNoFollow(managedFile(mediaId).toPath()))

        var callbackCount = 0
        val retry = store.import(source, setOf(mediaId)) {
            callbackCount += 1
        }
        assertTrue(retry is WallpaperImportResult.Failed)
        assertEquals(
            WallpaperMediaFailure.STORAGE_FAILURE,
            (retry as WallpaperImportResult.Failed).reason,
        )
        assertEquals(0, callbackCount)
        assertFalse(existsNoFollow(managedFile(mediaId).toPath()))
        assertEquals(0, directEntryCount())
    }

    @Test
    fun deleteRequiresFreshAuthorityAndPreservesAReplacementAfterSnapshot() = runBlocking {
        val store = store()
        val imported = store.import(testUri("valid.png"), emptySet())
        assertTrue(imported is WallpaperImportResult.Ready)
        val ready = imported as WallpaperImportResult.Ready
        val file = managedFile(ready.mediaId)

        assertTrue(store.deleteIfUnreferenced(ready.mediaId, setOf(ready.mediaId)))
        assertTrue(existsNoFollow(file.toPath()))

        assertFalse(store.deleteIfUnreferenced(ready.mediaId, setOf("invalid-reference")))
        assertTrue(existsNoFollow(file.toPath()))

        assertFalse(store.deleteIfUnreferenced("invalid-media", emptySet()))
        assertTrue(existsNoFollow(file.toPath()))

        assertFalse(
            store.deleteIfUnreferenced(
                ready.mediaId,
                syntheticMediaIds(MAXIMUM_WALLPAPER_FILE_COUNT + 1),
            ),
        )
        assertTrue(existsNoFollow(file.toPath()))

        val replacementBytes = staticWebpVariant(30_001)
        var replacementInjected = false
        val racingStore = store(
            faultInjector = WallpaperPersistenceFaultInjector { point ->
                if (
                    point == WallpaperPersistenceFaultPoint.BEFORE_MANAGED_ENTRY_DELETE &&
                    !replacementInjected
                ) {
                    replacementInjected = true
                    Files.delete(file.toPath())
                    Files.write(file.toPath(), replacementBytes)
                }
            },
        )
        assertFalse(racingStore.deleteIfUnreferenced(ready.mediaId, emptySet()))
        assertTrue(replacementInjected)
        assertArrayEquals(replacementBytes, Files.readAllBytes(file.toPath()))

        assertTrue(store.deleteIfUnreferenced(ready.mediaId, emptySet()))
        assertFalse(existsNoFollow(file.toPath()))
        assertEquals(0, directEntryCount())
    }

    @Test
    fun exactDurableMaximumPermitsOneLeaseButRejectsASecondCandidate() = runBlocking {
        val store = store()
        val references = buildSet {
            repeat(MAXIMUM_WALLPAPER_FILE_COUNT) { ordinal ->
                add(installFinal(staticWebpVariant(ordinal + 1_000)).mediaId)
            }
        }
        assertEquals(MAXIMUM_WALLPAPER_FILE_COUNT, references.size)
        assertTrue(references.all { mediaId -> existsNoFollow(managedFile(mediaId).toPath()) })
        assertEquals(MAXIMUM_WALLPAPER_FILE_COUNT, directEntryCount())
        assertEquals(
            DurableWallpaperQuotaResult.WITHIN_LIMIT,
            store.validateDurableQuota(references),
        )

        var firstCallbackCount = 0
        val first = store.import(testUri("valid.png"), references) {
            firstCallbackCount += 1
        }
        assertTrue(first is WallpaperImportResult.Ready)
        assertTrue((first as WallpaperImportResult.Ready).created)
        assertEquals(1, firstCallbackCount)
        assertEquals(MAXIMUM_WALLPAPER_STAGED_FILE_COUNT, directEntryCount())

        var secondCallbackCount = 0
        val second = store.import(testUri("rotated.jpeg"), references) {
            secondCallbackCount += 1
        }
        assertTrue(second is WallpaperImportResult.Failed)
        assertEquals(
            WallpaperMediaFailure.STORAGE_FAILURE,
            (second as WallpaperImportResult.Failed).reason,
        )
        assertEquals(0, secondCallbackCount)
        assertEquals(MAXIMUM_WALLPAPER_STAGED_FILE_COUNT, directEntryCount())
        assertEquals(0, directPendingCount())
    }

    private fun store(
        probe: WallpaperPersistenceProbe = WallpaperPersistenceProbe {},
        faultInjector: WallpaperPersistenceFaultInjector = WallpaperPersistenceFaultInjector {},
    ): ManagedWallpaperStore = ManagedWallpaperStore(
        context = context,
        decodeGate = BoundedMediaDecodeGate(),
        persistenceProbe = probe,
        persistenceFaultInjector = faultInjector,
    )

    private fun testUri(path: String) =
        android.net.Uri.parse("content://org.aurorasms.app.wallpaper.testprovider/$path")

    private fun ensureRoot(): Path {
        Files.createDirectories(root.toPath())
        return root.toPath()
    }

    private fun installFinal(bytes: ByteArray): FinalFixture {
        check(bytes.isStaticWebp()) { "Invalid derivative fixture" }
        val mediaId = wallpaperMediaId(bytes)
        val fileName = wallpaperDerivativeFileName(mediaId)
            ?: throw AssertionError("Unable to derive managed fixture name")
        val file = ensureRoot().resolve(fileName).toFile()
        Files.write(file.toPath(), bytes)
        return FinalFixture(mediaId, file)
    }

    private fun installExactPending(): File {
        val file = ensureRoot()
            .resolve(".pending-00000000-0000-0000-0000-000000000001")
            .toFile()
        Files.write(file.toPath(), byteArrayOf(7))
        return file
    }

    private fun managedFile(mediaId: String): File {
        val fileName = wallpaperDerivativeFileName(mediaId)
            ?: throw AssertionError("Unable to derive managed file")
        return File(root, fileName)
    }

    private fun syntheticMediaIds(count: Int): Set<String> = buildSet(count) {
        repeat(count) { ordinal ->
            add(WALLPAPER_MEDIA_ID_PREFIX + ordinal.toString(16).padStart(64, '0'))
        }
    }

    private fun staticWebpVariant(ordinal: Int): ByteArray {
        val encoded = encodedWebpFixture
        val result = encoded.copyOf(encoded.size + 12)
        "JUNK".encodeToByteArray().copyInto(result, encoded.size)
        writeLittleEndianInt(result, encoded.size + 4, 4)
        writeLittleEndianInt(result, encoded.size + 8, ordinal)
        writeLittleEndianInt(result, 4, result.size - 8)
        check(result.isStaticWebp()) { "Unable to construct valid derivative fixture" }
        return result
    }

    private fun encodedWebp(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { output ->
                val format = if (Build.VERSION.SDK_INT >= 30) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                check(bitmap.compress(format, 90, output)) { "Unable to encode derivative fixture" }
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeLittleEndianInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    private fun directEntryCount(): Int {
        if (!existsNoFollow(root.toPath())) return 0
        return Files.newDirectoryStream(root.toPath()).use { entries ->
            entries.count()
        }
    }

    private fun directFinalCount(): Int = directClassifiedCount {
        it is ManagedWallpaperFileClassification.Final
    }

    private fun directPendingCount(): Int = directClassifiedCount {
        it is ManagedWallpaperFileClassification.Pending
    }

    private fun directClassifiedCount(
        predicate: (ManagedWallpaperFileClassification) -> Boolean,
    ): Int {
        if (!existsNoFollow(root.toPath())) return 0
        return Files.newDirectoryStream(root.toPath()).use { entries ->
            entries.count { entry -> predicate(classifyManagedWallpaperFileName(entry.fileName.toString())) }
        }
    }

    private fun deleteTreeNoFollow(path: Path) {
        if (!existsNoFollow(path)) return
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        if (attributes.isDirectory && !attributes.isSymbolicLink) {
            Files.newDirectoryStream(path).use { entries ->
                entries.forEach(::deleteTreeNoFollow)
            }
        }
        Files.deleteIfExists(path)
    }

    private fun existsNoFollow(path: Path): Boolean = Files.exists(path, NOFOLLOW_LINKS)

    private fun WallpaperPersistenceCheckpoint.toTrace(): PersistenceTrace = when (this) {
        WallpaperPersistenceCheckpoint.APPEARANCE_DIRECTORY_CREATED -> {
            PersistenceTrace.APPEARANCE_DIRECTORY_CREATED
        }
        WallpaperPersistenceCheckpoint.APPEARANCE_PARENT_SYNCED -> {
            PersistenceTrace.APPEARANCE_PARENT_SYNCED
        }
        WallpaperPersistenceCheckpoint.WALLPAPER_DIRECTORY_CREATED -> {
            PersistenceTrace.WALLPAPER_DIRECTORY_CREATED
        }
        WallpaperPersistenceCheckpoint.WALLPAPER_PARENT_SYNCED -> {
            PersistenceTrace.WALLPAPER_PARENT_SYNCED
        }
        WallpaperPersistenceCheckpoint.PENDING_FILE_SYNCED -> PersistenceTrace.PENDING_FILE_SYNCED
        WallpaperPersistenceCheckpoint.PENDING_VERIFIED -> PersistenceTrace.PENDING_VERIFIED
        WallpaperPersistenceCheckpoint.FINAL_PUBLISHED -> PersistenceTrace.FINAL_PUBLISHED
        WallpaperPersistenceCheckpoint.FINAL_DIRECTORY_SYNCED -> PersistenceTrace.FINAL_DIRECTORY_SYNCED
    }

    private class FinalFixture(
        val mediaId: String,
        val file: File,
    ) {
        override fun toString(): String = "FinalFixture(<redacted>)"
    }

    private enum class PersistenceTrace {
        APPEARANCE_DIRECTORY_CREATED,
        APPEARANCE_PARENT_SYNCED,
        WALLPAPER_DIRECTORY_CREATED,
        WALLPAPER_PARENT_SYNCED,
        PENDING_FILE_SYNCED,
        PENDING_VERIFIED,
        CANDIDATE_CALLBACK,
        FINAL_PUBLISHED,
        FINAL_DIRECTORY_SYNCED,
    }
}
