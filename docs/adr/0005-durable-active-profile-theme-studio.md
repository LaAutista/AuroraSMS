# ADR 0005: durable active profile and transactional Theme Studio

Status: accepted and implemented for the bounded Phase 4 persistence/UI slice;
implementation commit `325f2ce` verified 2026-07-14

## Context

ADR 0004 established an immutable, schema-versioned AuroraMaterial profile and
token engine, but intentionally left selection and persistence outside the
design-system module. AuroraSMS now needs a user-reachable way to create, edit,
select, and retain one active named profile without making an uncommitted
preview the application theme or creating a second durable state authority.

The existing private Aurora state database already owns durable app-authored
state such as drafts. Its version-1 contents must survive this change. The
slice must also fail safely when stored appearance data is missing, corrupt, or
newer than the code understands, and it must not turn enum declaration order
into an accidental on-disk schema.

This decision is deliberately smaller than the complete Phase 4 appearance
requirements. Screen and conversation overrides, import/export, navigation
variants, wallpapers, animated media, approved artwork, and the full
accessibility/performance matrix remain separate acceptance slices.

## Decision

### Extend the existing durable state boundary

Migrate the private Aurora state Room database from version 1 to version 2 with
an explicit, non-destructive migration and exported schema. The new tables own
bounded named appearance profiles and the active-profile selection. Draft rows
and their identity enforcement remain intact.

The code-owned canonical values, mapped to `AuroraMaterialProfile.Default` at
the app boundary, remain the fallback; they are not duplicated as a mutable
database row. An absent active selection, deleted active profile, invalid stored
value, unsupported appearance schema, or storage read failure resolves to that
canonical profile. Recovery must not silently destructively recreate the
durable state database.

The state repository exposes validated immutable snapshots rather than Room
entities or database handles. Profile names and profile count are bounded.
Palette, density, avatar-mask, navigation, and bubble choices use explicit
stable string codes. Enum ordinals and declaration names are not storage
contracts. Numeric and boolean fields are validated at the repository boundary
before they can reach Compose.

### Make activation transactional

Creating or updating a profile with `Apply` and making it active occur in one
Room transaction. Updating and deleting use an expected revision so a stale
editor cannot silently overwrite or delete a newer durable value. Name
conflicts, bounds failures, stale writes, corrupt data, and storage failures
remain typed outcomes.

Resetting the active selection chooses the canonical code-owned default without
deleting named profiles. Deleting the active named profile atomically falls
back to the canonical default. No intermediate snapshot may advertise an
active profile that does not exist.

### Keep preview state app-owned and local to Theme Studio

Theme Studio is a normal app-owned destination in the existing route model; it
does not introduce a navigation library or a second production Activity. A
non-exported debug-only Activity hosts instrumentation and is absent from
release/benchmark outputs. The editor owns an immutable draft and renders it
with an in-memory `AuroraMaterialTheme` preview. While the Appearance route is
visible, that preview may temporarily recompose its route/root theme subtree so
the controls and preview surface are truthful. It never writes the repository
or escapes to another route.

Selecting controls or another profile changes only that transient Appearance
preview. It does not persist, query Telephony, invalidate the message index, or
reconstruct the current messaging route. `Cancel`, system Back, and Appearance
route disposal clear the in-memory preview and immediately restore the durable
active profile.

Only a successful atomic `Apply` or confirmed revision-checked `Delete` changes
durable appearance state. Deleting the active profile selects the canonical
fallback in that same transaction. The app root then observes the repository
snapshot and renders it after the transient preview is cleared. Saveable editor
state may restore a bounded draft after configuration or process recreation,
but an in-flight database operation or confirmation dialog is never resumed as
though it completed.

This slice exposes only controls whose consumers are ready for the bounded
profile path. Persisting a stable navigation code does not enable bottom-bar or
adaptive-rail navigation. Those variants remain fixed behind their later route
parity gate. Reduced motion remains a validated, round-tripping foundation
token but has no editor control until a production animation consumes it.

### Do not add DataStore

The Aurora state Room database is the sole durable owner for named profiles and
the active selection in this slice. DataStore is not added, and it is not a
shadow cache or competing source of truth. Any future lightweight-preference
artifact requires a measured ADR with disjoint field ownership and the full
dependency-admission review.

### Preserve privacy and dependency boundaries

The slice reuses the already admitted Room/KSP and Compose graph. It adds no
external coordinate, repository, permission, exported component, initializer,
native library, network path, artwork, media reference, or decoder. Appearance
state contains no message body, address, contact identifier, provider ID,
attachment byte, search term, or carrier state.

## Consequences

- A named profile and its activation survive process death in the same durable
  boundary as other Aurora-authored state.
- Database version 2 requires a checked-in schema and migration tests proving
  version-1 draft preservation and version-2 appearance behavior.
- The canonical profile always yields a usable app even before the first named
  profile exists or when durable appearance data cannot be trusted.
- Preview remains responsive and cancellable because it is in-memory app state
  scoped to the Appearance route; durable state changes only at the explicit
  transaction boundary.
- Stable codes and optimistic revisions make later schema evolution explicit.
- Overrides, import/export, navigation variants, media/artwork, and broad
  accessibility/performance claims are not implied by this slice.

## Rejected alternatives

- Add DataStore for the active profile: creates overlapping ownership with the
  existing durable state boundary and adds a dependency without a measured
  need.
- Persist only a serialized active profile preference: weakens referential,
  migration, transaction, uniqueness, and stale-write guarantees.
- Store enum ordinals or declaration names: source refactoring could silently
  reinterpret durable user state.
- Let preview state escape the Appearance route or survive its disposal: Cancel
  and Back would no longer have reliable rollback semantics.
- Store the canonical default as an editable row: allows code and database
  defaults to drift and makes recovery ambiguous.
- Include overrides, import/export, navigation variants, or media in the same
  migration: expands the trust, routing, URI, decoder, and test surface beyond
  this bounded slice.
