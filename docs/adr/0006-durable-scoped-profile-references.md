# ADR 0006: durable scoped profile references and deterministic inheritance

Status: accepted and implemented for the bounded Phase 4 slice; host,
governance, emulator, and physical install/package/role/permission verification
passed on 2026-07-14; real-root live/restoration acceptance and the final frozen
APK's Inbox-only physical modal-focus, exact copy/hash, and cold-launch gates also
passed on 2026-07-14; full process-death end-to-end and physical eligible-Thread
modal coverage are not claimed

## Context

ADR 0005 made the Aurora state Room database the sole durable owner of named
appearance profiles and one active-profile selection. It deliberately excluded
screen and conversation overrides, wallpaper/media references, and the broader
Phase 4 accessibility and performance matrix.

The next safe step is smaller than the complete scoped-wallpaper requirement.
AuroraSMS needs a durable way for an eligible screen or conversation to select
an existing named profile, inherit when no scoped selection exists, and reset
only that selection. It must not copy a profile into every scope, key a
conversation only by an unstable provider thread ID, retain raw participant
addresses in appearance storage, or make an appearance action query Telephony
or mutate the rebuildable index.

Only Inbox and Thread are currently reachable production surfaces for scoped
selection. Archive, Settings, and Spam & Blocked remain future routes. Search
must remain an opaque hot path, and the Appearance/Theme Studio route edits
profiles rather than becoming an override target.

This decision does not authorize artwork, user-media URIs, image/GIF decoding,
assignment-local focal points or dim values, wallpaper rendering, navigation
variants, or final surface accessibility/performance claims.

## Decision

### Extend the existing state database with references, not profile copies

Use one explicit, non-destructive Aurora state database migration from version
2 to version 3. Version 3 adds separate screen-profile and
conversation-profile assignment rows. Each assignment references one existing
named appearance profile by foreign key; it never serializes or copies that
profile's token values.

Eligible screen targets use these exact stable storage codes:

| Target | Storage code | Current UI status |
|---|---|---|
| Inbox | `inbox` | Reachable |
| Archive | `archive` | Model only; no control until its route exists |
| Settings | `settings` | Model only; Theme Studio is not this target |
| Spam & Blocked | `spam_blocked` | Model only; no control until its route exists |
| Global thread fallback | `global_thread` | Reachable as `Conversation defaults` from Inbox More |

Search and Theme Studio have no storage codes and cannot receive scoped
assignments. Unknown target codes fail validation and advance to inherited
appearance; they are never partially interpreted.

Each assignment has a positive optimistic revision. Version 3 also owns one
private singleton revision sequence shared by screen and conversation
assignments. Every actual create or update allocates the next positive value in
the same transaction; deletion, cascade deletion, reset, database reopen, and
later recreation never decrement or reuse an allocated value. This prevents a
stale pre-deletion revision from updating or deleting a recreated assignment
(the delete/recreate ABA case). A missing, malformed, or exhausted sequence
fails closed without changing the assignment.

Version-3 create, migration, and open paths install physical SQLite triggers
that admit only the singleton row at revision zero, require every update to be
exactly `old + 1`, and reject deletion. Repository validation also requires the
sequence to be at least the maximum revision of every live screen/conversation
row. Trigger tampering or rollback below that live-row floor therefore fails
closed before an assignment write.

An `Apply` request carries the expected assignment revision, or an explicit
must-be-absent expectation for creation. Choosing `Inherited` only stages that
choice in the modal. Applying the staged inherited choice requires the current
expected revision and deletes only that target row. A stale apply or inherited
reset has a typed stale outcome and makes no durable change; Cancel, Back, and
dismissal never write.

Deleting a named profile remains revision checked. Foreign keys cascade-delete
screen and conversation assignments that reference the deleted profile in the
same transaction, so every affected target immediately inherits its next
fallback. The canonical code-owned default is still not a mutable profile row.

### Use a precise pseudonymous conversation fingerprint

A conversation assignment stores both its current positive provider thread ID
and a versioned participant-set fingerprint. It stores no raw participant
address, display name, message content, contact identifier, or participant-set
serialization.

Fingerprint version 1 is computed only from a verified-complete,
non-truncated set of 1 through 100 participants:

1. Start with each already-validated `ParticipantAddress.value`. Normalize it to
   Unicode NFC without case folding, telephone rewriting, or an E.164 claim.
2. Remove exact duplicates after NFC normalization.
3. Encode each value as UTF-8 and sort the byte strings in unsigned
   lexicographic order.
4. Feed SHA-256 the ASCII domain separator
   `AuroraSMS.ConversationAppearanceParticipantSet.v1`, one zero byte, a
   four-byte unsigned big-endian participant count, and then, for each sorted
   value, its four-byte unsigned big-endian byte length followed by its bytes.
5. Persist only `sha256-v1:` followed by the 64 lowercase hexadecimal digest
   characters.

The fingerprint is the stable Aurora-owned lookup identity; the provider thread
ID is a current routing hint and may be rebound when the same verified
fingerprint appears under a recreated provider thread. A thread-ID match never
authorizes an assignment when a verified fingerprint differs. When participants
are incomplete, truncated, malformed, or unavailable, AuroraSMS does not create,
rebind, or resolve a conversation assignment and instead uses global-thread
inheritance. This safely prefers a fallback over leaking one conversation's
appearance into another.

The digest remains sensitive pseudonymous metadata. It is private, excluded
from backup, never logged or exposed through `toString`, and is not treated as
anonymous or suitable for telemetry/export.

The fingerprint contract supports 1 through 100 participants, matching the
bounded indexed identity model. The current production Thread UI intentionally
derives identity only from `ConversationSummary`, whose participant preview is
capped at 8. It exposes `Conversation appearance` only when that preview is the
verified complete participant set (`indexedParticipantCount == participants.size`)
and is not truncated. Conversations with 9 through 100 indexed participants
therefore inherit `global_thread` in this slice. A later bounded full-identity
query may make those targets available without weakening the fingerprint or
using a thread ID alone.

### Invalidate pre-version-3 participant completeness semantically

The rebuildable Aurora index also advances from schema version 2 to version 3.
Its tables do not need a destructive structural rewrite, but version-2
generations predate the stricter missing/malformed participant completeness
rules and cannot remain trusted. The explicit migration preserves searchable
rows while marking every generation paused and pending, clears completion and
failure markers, and advances its signal sequence. A pending paused latest
generation starts a fresh scan from empty checkpoints instead of resuming a
version-2 cursor. Until that reconciliation completes, `verifiedComplete` is
false and conversation appearance safely inherits `global_thread`.

New SMS projections treat a missing sender as incomplete. New MMS projections
retain valid addresses but taint completeness when a malformed row is dropped,
when the actual participant set is empty, or when the bounded set overflows.
Android's intentional MMS insert-address placeholder is ignored only when a
real bounded address set remains. The compatibility message-only projection
also never claims complete participant identity.

### Resolve one target at a time

The state repository exposes validated, target-specific flows rather than an
unbounded snapshot of every conversation assignment:

- one flow for a requested eligible screen code;
- one flow for a requested verified conversation fingerprint; and
- the existing bounded named-profile/active-selection snapshot.

The first Room value for a requested assignment is authoritative row-or-null;
the repository does not synthesize an inherited sentinel. The app controller
owns an explicit typed loading state, waits for a positive durable profile
snapshot revision, and accepts assignment readiness only from the exact current
target. A loading or stale-target observation cannot enable the editor.

The application layer combines those flows without passing provider IDs,
fingerprints, addresses, database handles, or repositories into
`:core:designsystem`. A target update invalidates only its small Room query and
the profile snapshot it references; it does not reload a message page, rebuild
an index generation, or recreate a route/state holder.

Profile resolution is one-way and first-valid-value wins:

```text
conversation:
  conversation profile reference
    -> global_thread profile reference
    -> active named profile
    -> canonical code-owned profile
    -> accessible solid renderer fallback

eligible screen:
  target screen profile reference
    -> active named profile
    -> canonical code-owned profile
    -> accessible solid renderer fallback

Search:
  active named profile
    -> canonical code-owned profile
    -> accessible solid renderer fallback

Theme Studio:
  existing route-local transient preview [only while present]
    -> active named profile
    -> canonical code-owned profile
    -> accessible solid renderer fallback
```

A missing profile, unsupported code/schema, invalid field, stale thread hint,
or unreadable assignment advances without mutating another scope. The later
wallpaper resolver will insert global-thread, canonical artwork, active-theme
wallpaper, media-revocation, and safe-solid rules independently; this slice
does not claim them.

### Keep assignment UI modal and route preserving

The currently reachable Inbox and Thread surfaces may open an app-owned profile
assignment modal over the existing route. Inbox More exposes the Inbox target
and a separate `Conversation defaults` action for `global_thread`; Thread More
exposes `Conversation appearance` only for that verified current conversation.
The modal lists validated named profiles plus `Inherited`, identifies its fixed
target without exposing private conversation data, and performs no write until
explicit revision-checked `Apply`. Selecting `Inherited` is a staged reset; it
is not an immediate durable action.

While the current Activity/root composition remains alive, opening the modal,
changing its selection, canceling, applying a named choice, applying an
inherited reset, pressing Back, or dismissing it must preserve the same app
route, route stack, screen state-holder instance,
in-memory paged window, visible scroll anchor, retained Search route/query,
draft, and composer state. None of those live modal actions may issue a
provider/index presentation reload solely because the modal opened or closed.
Cancel, Back, and dismissal discard the staged profile/inherited choice and
leave durable state unchanged. The modal must not push Theme Studio, create a
production Activity, or reconstruct the Thread route. A separate non-exported
debug-only Activity may host synthetic instrumentation and must be absent from
release and benchmark products.

Configuration or process restoration has a different contract. It necessarily
may reconstruct Compose state holders and must not serialize or claim identity
for an in-memory page object. Instead, it restores the logical route stack, the
exact modal target and bounded draft, a retained Search query, and the Thread
draft/composer, then permits the existing ADR 0003 bounded presentation
re-query. When the route already owns an exact saved Thread anchor, that bounded
re-query must restore the same stable anchor. This decision does not
claim exact-anchor reconstruction for a normal Inbox or an unanchored Thread;
that requires a separately reviewed bounded anchor API. Restoration must not
turn the modal into Theme Studio or a different Thread route.

The completed acceptance extension exercises the real `AuroraSmsRoot` with
deterministic synthetic services. Live Inbox/global and exact-anchor Thread
modal operations retained their route and visible state, Search query, draft,
and composer with zero modal-caused provider/index reload. An
`ActivityScenario` recreation rebuilt the holder, performed exactly one bounded
anchor query, and restored the exact modal target/selection, stable visible
`ProviderMessageId` plus offset, Search query, draft, and composer. A new entry
into that same Thread received a distinct route-state identity and its own exact
jump. SavedState for popped or evicted route entries is removed, keeping
retention bounded to `MAXIMUM_RETAINED_ROUTES`. The separate Pixel check covers
only real MainActivity/Inbox modal focus and Cancel. The final frozen artifact
passed that unlocked check using package/view IDs and accessibility window
metadata: a distinct focused scoped dialog opened over MainActivity/Inbox, and
Cancel returned to the same window without opening a Thread or applying an
assignment. Exact Download-copy/hash and cold-launch diagnostics also passed.
This does not extend these conclusions to physical Thread behavior or full
process death.

Restorable modal state is bounded to a schema version, the selected and
baseline profile IDs, expected revision, and one private target token. For a
screen that token contains only its stable code; for a conversation it contains
the current provider thread hint and versioned participant fingerprint. It is
app-private pseudonymous `SavedState`, not a label, and must never be logged,
displayed, analyzed, or exported. Restoration validates the stored token against
the current target before rendering or enabling Apply and discards a draft from
any other target. During process loading, profile selection, reset, and Apply
remain disabled until both the first validated durable profile snapshot and
the exact target-assignment query arrive.
In-flight state, transient errors, dropdown state, and dismissal state are not
restored as completed work. Only Inbox, Inbox-owned Conversation defaults, and
verified current-conversation appearance controls are exposed now; future
stable screen codes do not justify dead controls for routes that do not exist.

### Preserve privacy, dependency, and transport boundaries

The slice reuses the admitted Room/KSP, Kotlin, coroutine, and Compose graph. It
adds no DataStore owner, external production coordinate, repository,
permission, exported component, initializer, native library, network path,
media reference, picker, decoder, artwork, or background service. The direct
Android-test Espresso declaration exposes an already-resolved transitive
artifact only to instrumentation compile. SHA-256 uses the platform Java
cryptography API.

The override repository accepts validated app-layer identity and profile IDs;
it does not query Telephony or the rebuildable message index. Provider and
index recovery may supply a newly verified identity to the app layer, but
cannot delete, rewrite, or globally invalidate durable appearance assignments.
No SMS/MMS provider write, carrier send, notification action, or role behavior
changes in this slice.

## Consequences

- A scoped choice follows edits to its referenced named profile without a
  duplicated durable token snapshot.
- Profile deletion, scoped reset, missing state, and unsupported state have one
  deterministic inherited result.
- Provider thread recreation can retain an assignment only when the complete
  participant fingerprint matches; ambiguous identity fails safely.
- A version-2 index can preserve its searchable rows during upgrade without
  preserving an obsolete verified-complete claim; conversation assignment stays
  unavailable until a fresh version-3 reconciliation succeeds.
- Assignment revisions never repeat after delete/recreate, so a stale modal
  cannot acquire authority over a newer assignment through ABA reuse.
- Restored modal drafts remain bound to their exact private target and cannot be
  applied while durable profile state is still loading.
- Target-specific flows keep memory and invalidation bounded even when many
  conversations eventually have assignments.
- The current `ConversationSummary` preview limits UI assignment to verified
  sets of at most 8 participants; the core identity contract remains bounded at
  100 and larger current UI conversations inherit safely.
- Archive, Settings, and Spam & Blocked can later consume already-versioned
  target codes without exposing controls early.
- Assignment-local focal/dim values, wallpaper/media references, canonical
  artwork, GIF lifecycle, and full accessibility/performance acceptance remain
  explicit future work.

## Rejected alternatives

- Copy complete profile values into every assignment: duplicates the active
  profile source of truth and makes profile edits, migrations, and reset
  semantics drift.
- Key conversation state only by provider thread ID: can apply private styling
  to the wrong conversation after provider recreation.
- Persist a raw joined participant set: retains addresses unnecessarily in the
  appearance tables and increases disclosure risk.
- Resolve by thread ID when the fingerprint is unavailable: turns uncertainty
  into cross-conversation appearance leakage.
- Expose all assignments as one collection flow: makes per-thread rendering and
  invalidation grow with total customized conversations.
- Make Search or Theme Studio an override scope: conflicts with opaque search
  presentation and confuses route-local profile preview/editing with target
  assignment.
- Add focal/dim fields or media references now without a real renderer and
  user-visible consumer: creates dead state and falsely claims part of the
  separately gated wallpaper slice.
- Push a new route or Activity for the picker: risks losing the existing paged
  screen state and reloading provider/index presentation.
