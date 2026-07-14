# ADR 0004: AuroraMaterial profile and token engine

Status: accepted as the Phase 4 foundation control, 2026-07-14

## Context

Phase 3 deliberately uses one static, opaque Material 3 theme. Phase 4 must add
palette, density, avatar, bubble, navigation, wallpaper, motion, accessibility,
and named-profile choices without spreading appearance conditionals through
the inbox/thread implementation or coupling cosmetic changes to private
message/provider state.

The final appearance surface also has unusual safety constraints. AuroraSMS is
offline, hot messaging surfaces cannot depend on blur or unbounded decode, GIF
playback must be lifecycle-owned, and private/copyright-gated reference material
cannot enter source or the APK. An attractive preview is not sufficient if it
weakens route, back, contrast, memory, privacy, or clean-room guarantees.

## Decision

### Dedicated declarative module

Create `:core:designsystem` as a leaf Android/Compose library. It owns immutable
appearance vocabulary, validated profiles, semantic tokens, palette selection,
and original shapes. It has no dependency on app navigation, databases,
Telephony, contacts, notifications, media providers, or transport.

The app root consumes `AuroraMaterialTheme`. The initial default profile uses
the exact Phase 3 key colors and does not change routes or messaging behavior.
Feature code may consume semantic tokens through composition locals; it must
not copy profile conditionals or query persistence during composition.

### Versioned input and stable storage boundary

`AuroraMaterialProfile` has an explicit schema version and constructor bounds.
Hue is restricted to 0..359 and wallpaper dim to the documented accessible
range. Density, mask, navigation, palette, and bubble choices use named enum
values in memory, but later persistence/export uses explicit stable storage
codes rather than enum names or ordinals.

The design-system module does not persist. A later repository behind the
existing Aurora-owned state boundary validates/migrates stored data and emits a
single immutable active snapshot. Unsupported newer schemas and hostile fields
fail closed to an accessible default; they are never partially applied.

### Deterministic inheritance

Appearance resolution is one-way:

```text
conversation -> eligible screen -> named profile -> canonical default -> solid
```

Every assignment can reset to inherited. A missing/revoked/corrupt media
reference advances to the next fallback without mutating another scope. Theme
changes do not query Telephony, rebuild the index, or reconstruct a thread
route.

### Accessibility and lifecycle are tokens, not patches

The engine defines a 48 dp minimum target, density-specific row dimensions,
reduced-motion behavior, and concrete avatar shapes centrally. Dark, AMOLED,
Light, and system-dynamic palettes have deterministic local behavior. System
dynamic color uses Android's platform implementation only where available and
falls back locally elsewhere.

Wallpaper contrast, focal crop, GIF ownership, high-contrast adjustments, and
all final 4.5:1/3:1 checks remain acceptance-gated follow-on work. A profile
field existing does not itself claim that every consuming surface is complete.

### Artwork stays behind its independent rights gate

No source artwork or derivative is admitted until the written record in
`docs/ARTWORK_CATALOG.md` covers the exact publication, derivative,
distribution, promotional, redistribution, and attribution uses. General
permission to implement AuroraSMS does not substitute for those terms.

Private screenshots remain black-box references only. Camera ICON, raw source
art, and private materials are forbidden from runtime/test assets. Approved
derivatives later require reproducible source/derivative hashes and visual
review.

### No new dependency for the foundation

The module reuses the exact already-admitted Kotlin, coroutine, AndroidX, and
Compose graph. It adds no image/GIF loader, navigation library, DataStore, icon
pack, font, native component, initializer, permission, or network path.

## Consequences

- The Phase 3 default stays visually stable while appearance work moves behind
  one testable API.
- Host tests can validate profile bounds, token floors, palettes, and shapes
  without a provider or database.
- Theme Studio, persistence, navigation variants, and media can land as
  independently reviewable slices instead of one risky UI rewrite.
- Some profile fields are vocabulary before every consumer exists. The test
  matrix remains explicit about which surfaces are not yet integrated.
- Adding a new token requires a schema/storage/export decision and migration
  behavior, not an ad hoc preference.
- Artwork and animated-wallpaper progress can remain blocked without blocking
  palette/density/accessibility engineering.

## Rejected alternatives

- Theme conditionals in each screen: duplicates policy and makes parity,
  accessibility, and restoration hard to prove.
- Passing a database/preferences object into Compose theme code: couples
  rendering to I/O and weakens deterministic previews/tests.
- Treating enum ordinals as storage schema: reordering source would silently
  reinterpret user state.
- Copying private/reference visuals into resources early: violates the
  clean-room and artwork-rights gates.
- Adding a general image/GIF loader before lifecycle and hostile-media review:
  expands network, decoder, manifest, memory, and supply-chain surface before
  the actual contract is accepted.
- Live blur on inbox/search/thread surfaces: conflicts with the opaque,
  predictable hot-path and performance requirements.
