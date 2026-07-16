# ADR 0007: managed private static Thread wallpapers

Status: accepted on 2026-07-14; the bounded implementation landed at source
commit `c957995e74c7ba76ed25d1b7c4d23c05f42852be`, followed by acceptance
hardening at `975009f2b2c99cf389fb8020b270fd7c5bbf0bb2` and renderer isolation
at `e5aa4dfb1c695046c136d07e6b0c549e77e278ee`; crash-safe managed-store and
quota hardening landed at source commit `f0f1ff9`. Its focused host, API 26,
API 36, physical filesystem, complete connected, release/governance,
license/SBOM, and exact APK handoff gates pass. The narrow physical
`global_thread` platform-Photo-Picker journey at
`111381dff31c46380eab969dea20234cba16fe08` also passes. Synthetic API 36
verified-conversation root pixels, focal/dim Apply, Activity recreation,
reset/identity fallback, stale-pixel clearing, and independent real-Room
close/reopen durability pass at `b9350be354991e36039e8136095bc25ebd520d60`,
and gated API 36 AOSP Photo Picker `GLOBAL_ACTION_BACK` cancellation passes twice at
`826a20dbc3e965da8f269dde1351cf4d76d28f6c`, while SAF
fallback/cancellation, cold-process renderer restart, complete picker/UI,
accessibility/form-factor, carrier, and overall acceptance remain pending

## Context

ADR 0006 established durable named-profile references for eligible screens and
verified conversations. It deliberately did not authorize media references,
picker access, decoding, assignment-local focal/dim values, or wallpaper
rendering.

The next slice must provide one real wallpaper consumer without opening the
complete artwork, animation, screen-treatment, or external-document lifecycle
surface at once. Thread is the safest first consumer: its header, message
bubbles, and composer already have opaque content surfaces, and its verified
conversation identity plus `global_thread` fallback are already enforced.
Inbox rows do not yet have the separately reviewed contrast treatment required
for a photograph behind the list.

A durable external content URI would keep Aurora dependent on another
provider after Apply. The document could move, disappear, change bytes, block
while a cloud provider fetches it, or lose its grant. Persisting and releasing
grants would also add a second crash-reconciliation protocol and retain a
sensitive provider URI. AuroraSMS instead needs a genuinely local, immutable,
bounded representation of the exact pixels it accepted.

This decision does not authorize built-in Aurora artwork, Inbox or other screen
wallpapers, GIF/animated media, live external URI references, profile
import/export media, or a third-party image library.

## Decision

### Limit the first renderer to Thread

The bounded slice admits wallpaper assignments only for:

- the `global_thread` fallback; and
- one verified conversation identity as defined by ADR 0006.

The wallpaper resolution order is:

```text
verified conversation managed wallpaper
  -> global_thread managed wallpaper
  -> accessible solid Thread fallback
```

An unavailable, incomplete, changed, or oversized conversation identity cannot
open, apply, rebind, or resolve a conversation wallpaper. It inherits
`global_thread`; a provider thread ID alone never has authority. Search,
Theme Studio, Inbox, Archive, Settings, and Spam & Blocked do not receive a
wallpaper control or renderer in this slice.

The Thread timeline owns the only production wallpaper surface. Header,
message-bubble, composer, menu, and dialog treatments remain opaque enough for
their existing semantic content. The renderer always applies the documented
contrast-preserving dim/scrim and retains the accessible solid fallback.

### Use the system picker only as a temporary capability

The user explicitly opens the system Photo Picker for one image. The existing
AndroidX Activity contract may use its platform/SAF fallback where the Photo
Picker is unavailable. AuroraSMS accepts only a returned `content:` URI with a
non-empty authority and at most 4,096 UTF-8 bytes.

The URI is transient process memory. It is never written to Room, a file,
preferences, logs, analytics, `SavedState`, or an exception/toString payload.
AuroraSMS does not call `takePersistableUriPermission`. Cancel, Back, target
loss, source loss, configuration/process loss of the staged candidate, or a
failed Apply leaves no durable URI or grant. The user may need to pick again
after staged state is lost.

Picker return only creates an in-memory, bounded static preview. It does not
change the assignment or create a managed file. Explicit Apply rechecks the
exact target and expected assignment revision, reopens and revalidates the
source, and only then begins the managed import. If temporary URI access is no
longer available, Apply fails without changing the current assignment.

### Admit only bounded baseline JPEG and static PNG input

The source contract is fixed for this slice:

| Boundary | Limit |
|---|---:|
| Encoded source bytes | 16 MiB |
| Source width or height | 8,192 pixels |
| Source pixel count | 40,000,000 pixels |
| Full decoded derivative edge | 2,048 pixels |
| Full decoded derivative pixels | 4,194,304 pixels |
| Full decoded allocation | 16 MiB |
| Chooser preview edge | 512 pixels |
| Chooser preview pixels | 262,144 pixels |
| Chooser preview allocation | 1 MiB |
| Encoded managed derivative | 8 MiB |
| Concurrent wallpaper import/decode | 1 |
| Concurrent app media decodes, including MMS | 2 |

Reported size and MIME are advisory. AuroraSMS performs an authoritative
bounded read and accepts only 8-bit Huffman baseline sequential-DCT JPEG
(`SOF0`) with at most four components and complete scan coverage, or a CRC-valid
non-APNG PNG with at most 4,096 chunks, no `iCCP`/`zTXt`/`iTXt` ancillary
chunks, and a zlib stream containing every required scanline. A specific
declared MIME that contradicts the header fails closed.
Progressive, extended sequential, arithmetic, lossless,
differential/hierarchical, and non-8-bit JPEG are unsupported. GIF, every WebP
source, HEIF, AVIF, unknown input, and malformed/truncated input are rejected.
PNG chunk inspection validates structure and CRCs, rejects `acTL`, `fcTL`,
`fdAT`, `iCCP`, `zTXt`, and `iTXt`, caps the chunk walk at 4,096, and requires
the complete bounded zlib scanline stream before decode.

Bounds are inspected before full decode and rechecked by the decoder. Decode,
orientation normalization, resampling, hashing, and compression run off the
main thread. API 26-27 uses a small in-app parser that reads only the bounded
accepted-JPEG APP1 or PNG `eXIf` TIFF orientation scalar from the
already-bounded in-memory bytes and handles all eight orientation forms; newer
platform decode must produce the same oriented result. The parser bounds every
segment, chunk, IFD, entry, and offset before access and admits no general
metadata surface.
Output is software sRGB/ARGB_8888 and must pass the edge, pixel, and allocation
bounds after every transform. I/O, security, malformed-data, runtime,
cancellation, and allocation failures are typed failures with redacted
diagnostics.

### Sanitize into an app-private static WebP

Apply removes source metadata by rendering the accepted, oriented, bounded
bitmap and encoding a new static sRGB WebP derivative at quality 95. API 26-29
uses the platform WebP encoder and API 30+ uses its explicit lossy-WebP form.
The derivative is rejected if it exceeds 8 MiB.

Aurora computes SHA-256 over the completed derivative. The private media token
is `sha256-v1:` followed by exactly 64 lowercase hexadecimal characters; its
`toString` and all containing models are redacted. The stored media-kind code is
the explicit `static_raster_v1`. The corresponding file name is derived by
trusted code as `v1-<digest>.webp`. Room stores the kind/token, never a path,
file name, source URI, MIME string, or source metadata. The renderer derives the
one fixed path and rechecks the file name, header, hash, dimensions, and
allocation before publishing pixels.

Managed files live only under:

```text
noBackupFilesDir/appearance/wallpapers/
```

The fixed directory and derived name are not caller-controlled. Symlinks,
subdirectories, unexpected names, and non-regular files are never opened as
wallpaper media.

### Store assignments directly; do not add a media catalog

The Aurora state database advances through one explicit migration for direct
wallpaper assignment rows. A global-thread row stores its screen code, managed
media token, focal X/Y, dim, and optimistic revision. A conversation row stores
the ADR 0006 participant fingerprint, current provider-thread hint, managed
media token, focal X/Y, dim, and optimistic revision.

Focal X and Y are independently bounded to 0 through 1,000 permill. Dim is
bounded to the existing accessible 350 through 900 permill range. These fields
land only with the real Thread renderer and editor consumers; they are not
added to named profiles or the existing profile-reference rows.

There is no `appearance_media` catalog table. Assignment rows are authoritative,
and more than one row may reference the same content-addressed file. Repository
queries expose one target-specific assignment flow plus a bounded distinct
media-token snapshot for quota and garbage collection. The distinct query uses
129 as an overflow sentinel and fails closed rather than returning an unbounded
set.

Wallpaper creates and actual updates allocate from the existing protected,
non-reusing appearance-assignment revision sequence in the same transaction as
the target write. The live-revision floor expands to include profile-reference
and wallpaper rows. Reset, replacement, database reopen, and delete/recreate
never let a stale pre-deletion wallpaper revision regain authority.

### Bound the managed store and make file/Room ordering recoverable

The durable logical store admits at most 128 distinct managed wallpaper files
and 256 MiB of distinct encoded derivative bytes. Reusing the same derivative
hash consumes neither another file slot nor duplicate bytes. Quota failure is a
typed outcome and never evicts an assigned wallpaper. The implementation does
not use an LRU to silently break older conversations.

To make replacement possible when that durable assigned set is already full,
the serialized importer may temporarily stage exactly one unassigned sanitized
candidate no larger than 8 MiB before the Room compare-and-set. The private
directory may therefore momentarily contain at most 129 managed files and 264
MiB, but this is atomic-staging headroom, not a higher durable quota. A second
unassigned candidate is never admitted concurrently.

The namespace is app-private and single-process. Every store instance shares
one process-wide mutation mutex, so import, deletion, and reconciliation cannot
interleave through separately constructed stores. The fixed appearance and
wallpaper components must be no-follow directories; creating either component
is followed by parent-directory synchronization before publication continues.

One serialized importer owns mutation ordering:

1. Obtain a fresh bounded authoritative Room reference set, validate the
   namespace, enforce durable quota, and produce the bounded derivative.
2. Create a generated same-directory `.pending-*` leaf with
   `O_EXCL|O_NOFOLLOW`, write and flush all bytes, sync it, then verify its
   digest, exact device/inode identity, regular-file type, and single link.
3. Immediately recheck that the derived final is absent, publish it atomically
   with same-directory `Os.rename`, and reverify the same identity and complete
   content. The private namespace and process-wide mutex make this
   absence-check/publication sequence exclusive to AuroraSMS.
4. Deliver the exact candidate cleanup lease synchronously before any
   suspendable post-publication checkpoint, then sync the wallpaper directory
   before returning the import for the Room compare-and-set.
5. In one Room transaction, revision-check the exact target and insert or
   replace its assignment. After success, delete the old final only when a fresh
   bounded authoritative snapshot proves it unreferenced. Failure, staleness,
   rejection, or cancellation removes only the leased exact candidate when
   fresh authority still proves it unreferenced.

A crash may leave a pending file or one unassigned staged final, but not a
committed assignment to an unfinished file. Startup reconciliation first scans
and validates every direct entry without deleting anything. Symlinks,
subdirectories, unexpected names, malformed candidates, multiple links, too
many entries, invalid references, or storage failure make the pass fail closed
with zero partial deletion. Only a successful first pass permits removal of
exact pending leaves and conforming unreferenced finals, followed by
leaf-directory synchronization. Database unavailable/corrupt/over-limit or an
unsafe namespace likewise deletes nothing.

A referenced file that is missing, malformed, renamed, or hash-mismatched is
not silently replaced and does not cause another assignment to be selected.
That target renders its next fallback and exposes explicit reset/reselect
recovery. Successful reset eventually makes the unreferenced bad file eligible
for cleanup.

### Prevent stale pixels and unbounded decode work

The wallpaper loader has one current target/request epoch and at most one
wallpaper decode/import. It shares an app-wide two-permit full-media decode gate
with the existing MMS preview loader so a Thread wallpaper does not create an
unreviewed third concurrent full decode.

Before any suspend/read/decode for a changed target, media token, or request
epoch, Compose synchronously publishes the accessible solid state and releases
the old wallpaper reference. A late result is accepted only when its target,
token, revision, and epoch still match. A previous conversation's bitmap may
never remain visible while a new target loads or fails.

The full wallpaper cache holds at most the current 16-MiB allocation. The
chooser holds at most one 1-MiB static preview. Leaving Thread, switching the
resolved assignment, background disposal, or explicit clear releases their
owned entries. Static media has no background worker, animation, or network
fetcher.

### Preserve permission, dependency, artwork, and messaging boundaries

The slice adds no manifest permission, exported component, service, provider,
receiver, initializer, native library, repository, or network path. In
particular it adds no storage permission, `READ_MEDIA_*`, persistent URI grant,
or Photo Picker backport-install component. Existing `allowBackup=false` and
the complete backup/data-extraction exclusions remain in force; managed files
also live under `noBackupFilesDir`.

Implementation uses only the already admitted Activity/Compose/coroutine/Room
graph, Android platform `BitmapFactory`, `ImageDecoder`, color-space and WebP
APIs, `java.security.MessageDigest`, and original bounded parsers for baseline
JPEG entropy completeness, static-PNG structure/zlib completeness, and JPEG
APP1/PNG `eXIf` TIFF orientation described above. It has no
`android.media.ExifInterface` dependency, and no image or GIF library is
admitted.

Private user imports are not built-in Aurora artwork. No handoff artwork,
Camera ICON, screenshot, PDF, source PNG, or derivative enters source or the
APK. The independent written-rights gate in `docs/ARTWORK_CATALOG.md` remains
blocked, and animated media remains behind its separate decoder/lifecycle gate.

## Consequences

- The first real wallpaper is genuinely local after Apply and is unaffected by
  a moved source document, provider revocation, cloud availability, or future
  changes to the source bytes.
- Aurora retains a private sanitized copy of user-selected pixels until the
  last referencing assignment is reset/replaced or the app is uninstalled.
- A staged candidate is intentionally less durable than an applied assignment;
  configuration/process loss may require another pick.
- The new state migration, direct assignment rows, shared revision floor,
  decoder, renderer, picker/UI, accessibility/form-factor, and broader physical
  user journeys still require their named gates before an implementation-
  complete claim. The managed-file crash/quota protocol passed its focused host
  and device matrix, and the dedicated narrow platform-picker runner passed 1/1
  in 7.107s on Pixel 8 Android 16/API 36 serial `192.168.68.55:43069`: Cancel
  and wallpaper Back preserved the empty baseline, Apply created one
  `global_thread` assignment and one conforming managed file. Reset restored
  the baseline, and the exact synthetic Downloads fixture was deleted. Post-run
  database/file counts were `0/0` and `0`; the test package was absent; the
  target, SMS role, and all seven grants were preserved; and the local,
  installed, and Downloads APKs each matched 13,993,426 bytes and SHA-256
  `5c4c7255396f6a5676eaf7da3e617a045ecfc9b6e5e3ded7551990eb5f5267d1`.
  That physical runner does not prove SAF/system-picker cancel,
  verified-conversation rendering, restart persistence, performance, the
  complete lifecycle, or gold readiness.
- Source commit `b9350be354991e36039e8136095bc25ebd520d60` adds synthetic API 36
  verified-conversation evidence. Root timeline pixel captures prove
  conversation-over-global precedence, applied dim and equivalent pixels after
  Activity recreation, reset-to-global, identity-loss fallback without
  cross-target mutation, and stale-pixel clearing. Editor and repository
  assertions prove focal/dim values survive Apply plus recreation. Separate
  managed-surface and real-Room close/reopen/reset cases pass; the Room case
  proves exact global and conversation rows survive database close and reopen
  and reset independently. The full API
  36 connected matrix passed in 1m15s with 456 tasks, the 886-task offline
  host/release/governance/license gate passed in 15s, CycloneDX passed in 7s,
  and the debug APK remained 13,993,426 bytes with SHA-256
  `5c4c7255396f6a5676eaf7da3e617a045ecfc9b6e5e3ded7551990eb5f5267d1`.
  Activity recreation and Room reopen are separate evidence; this does not
  prove a cold-process root renderer plus managed-file restart, a physical
  verified-conversation journey, SAF/system-picker cancellation, carrier
  behavior, the complete lifecycle, or gold readiness.
- Source commit `826a20dbc3e965da8f269dde1351cf4d76d28f6c` adds a gated API
  36 AOSP Photo Picker cancellation journey using the accessibility global Back
  action. With normal emulator SMS-role preconditions, the exact method passed
  independently twice in 12s and 11s. It waits for `StateStorageStatus.Ready`,
  published after the startup reconciliation attempt, opens the real
  `MainActivity` global-thread editor, focuses MediaProvider, invokes
  `GLOBAL_ACTION_BACK` without creating a synthetic picker fixture or inspecting
  picker content, and proves the usable dialog returns with Pick enabled, Apply
  disabled, no loading/error, and the exact
  global assignment, managed-file name set, and persisted-grant count unchanged.
  The follow-on full connected matrix passed in 1m19s with 456 tasks; the
  886-task offline host/release/governance/license gate passed in 17s;
  CycloneDX passed in 7s; and the unchanged 13,993,426-byte debug APK retained
  SHA-256
  `5c4c7255396f6a5676eaf7da3e617a045ecfc9b6e5e3ded7551990eb5f5267d1`.
  This proves only API 36 AOSP Photo Picker accessibility global-Back
  cancellation, not SAF fallback/cancellation, OEM picker behavior, any explicit
  Photo Picker Cancel control, selected/staged candidate handling, cold-process
  behavior, the complete lifecycle, or gold readiness. The compound picker/SAF
  gate remains open.
- Inbox treatment, canonical built-ins, GIF lifecycle, live URI references,
  and import/export media remain independently reviewable slices.

## Rejected alternatives

- Persist the picker URI and take a durable read grant: retains sensitive
  provider identity and adds revocation, provider mutation/blocking, grant-cap,
  reference-count, and crash-reconciliation behavior without a product need
  for live linkage.
- Copy the original encoded source unchanged: keeps metadata and makes every
  render re-enter the hostile source decoder path instead of consuming a
  bounded sanitizer output.
- Add a media catalog table: duplicates ownership for a content-addressed store;
  direct assignments plus one bounded distinct-token snapshot are sufficient
  for this slice.
- Add Inbox at the same time: transparent list treatment needs its own measured
  contrast and readability review.
- Accept progressive, extended sequential, arithmetic, lossless,
  differential/hierarchical, or non-8-bit JPEG, or accept GIF, animated WebP,
  HEIF, AVIF, or APNG: expands format, lifecycle, parser, frame/duration,
  API-26, and memory behavior beyond the reviewed static contract.
- Import built-in Aurora artwork: remains prohibited until the exact written
  rights record is accepted.
- Auto-evict the oldest wallpaper at quota: silently changes another target's
  appearance and violates independent reset semantics.
