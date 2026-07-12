# Aurora artwork catalog and asset policy

Status: integrity and canonical-mapping verification complete; licensing and
runtime ingestion deferred

## Handling decision

The nine supplied source PNGs remain only inside the ignored private handoff at
`AuroraSMS_Codex_Handoff_PRIVATE/artwork/source/`. Phase 0 does not copy,
convert, stage, commit, or package them.

The files passed their supplied SHA-256 checks on 2026-07-12. All eight
wallpapers are 941 x 1672 RGBA PNGs; the camera icon is 1254 x 1254 RGBA. A
visual/metadata audit found the alpha channels fully opaque. The eight source
wallpapers total about 19.8 MiB as PNG and would require roughly 48 MiB if all
were decoded simultaneously, which reinforces the lazy-loading requirement.

## Canonical mappings

| Supplied source | SHA-256 | Planned runtime name | Role |
|---|---|---|---|
| `Aurora Night(2).png` | `f9b5ed440ceb98505c0d978b343cf027565a9ceb068c08571975b7871e38af15` | `aurora_night.webp` | Canonical Inbox default |
| `Aurora Cabinet(2).png` | `f572774b32c9f9e1693125bc9fad62b0bff952756c858abd8fa6245a016537e0` | `aurora_cabinet.webp` | Canonical Archive default |
| `Aurora Office(2).png` | `11cbb54d07190541603b83f2f647197d0087bf9ff0868296cc84f546d52fbb90` | `aurora_office.webp` | Canonical Settings default |
| `Aurora Spam & Block(2).png` | `61a894d8ffb7fe4a525e0dd956a72fc5be0d62fa9cad849b7cfb9fa53c7a6533` | `aurora_spam_block.webp` | Canonical Spam & Blocked default |
| `Aurora Bridge(2).png` | `62dea6e2fcfd766fd3e25bdbb2360958c3d7e7aa30e54aea989511cced6c7450` | `aurora_bridge.webp` | Optional offline preset |
| `Aurora City(2).png` | `0ddf280e1cda1ef190293569aa1d611d43bfa5ae31103d0e86ca0b93d0278266` | `aurora_city.webp` | Optional preset/global thread fallback choice |
| `Aurora Cat(1).png` | `360654e09ad880ba9e381d2ddb550a655c3ef6db26cdd9f9cac287c0e790ae19` | `aurora_cat.webp` | Optional offline preset |
| `Aurora Cherry Blossom.png` | `24e4049935482fbf56d848167d16a1b495c7fe2469be314943d5967ea5f84924` | `aurora_cherry_blossom.webp` | Optional light/day preset |
| `Aurora Camera ICON.png` | `2ae8cd0e4c42c02e7b39d69bff8d04d6ecd67543126146b33df071b26c7123a6` | None | Provenance only; never an AuroraSMS runtime asset/icon |

The reference chooser's label `Aurora Archive` corresponds to the supplied
Cabinet artwork; no missing `aurora_archive` file should be invented. The
reference screenshots show Bridge selected for an older/user inbox state, but
fresh AuroraSMS installs default to Night.

## Visual use notes

- Night has bright aurora/star detail and needs a strong inbox/list scrim plus
  banding/detail checks after conversion.
- Cabinet has a large dark upper region and a strong semantic Archive mapping.
- Office has useful dark negative space but detailed lower workstation content
  still needs opaque-enough row/content surfaces or a deterministic scrim while
  retaining the artwork; dialogs and menus may be fully opaque.
- Spam & Block contains red warning motifs; live error/destructive UI must not
  be conveyed by wallpaper alone.
- Bridge and City have bright rain/city detail that competes with list rows and
  bubbles; focal point and dim controls are essential.
- Cat is a dark general-purpose optional preset.
- Cherry Blossom is the brightest preset and requires adaptive/strong dark
  scrims for light text.
- Camera ICON is square, opaque, and identity-specific to AuroraCamera. It is
  neither a transparent adaptive-icon source nor approval for AuroraSMS reuse.

At a tall 921 x 2048 display ratio, a 941 x 1672 source used with cover scaling
must enlarge and crop horizontal content. Runtime presentation therefore uses
a non-destructive saved focal point rather than baking one crop into the source.

## Phase 4 derivative pipeline

No derivative is generated until the artwork license is approved. The Phase 4
pipeline must then:

1. preserve the original PNG bytes unchanged outside APK packaging; keep them
   private/outside Git unless the approved license explicitly permits source
   redistribution, and use a licensed tracked source-art area only when it does;
2. generate screen-sized static WebP derivatives without redrawing, recoloring,
   destructive cropping, or changing visible composition/color character;
3. treat wallpapers as density-independent resources and crop at runtime from
   a saved focal point;
4. test conversion quality for dark-gradient banding and fine stars, rain,
   hair, blossoms, and city detail;
5. keep the complete packaged built-in wallpaper set at or below 12 MiB
   compressed unless the owner approves a measured quality exception;
6. decode only the active wallpaper or selected preview, never the whole set at
   startup;
7. generate cached thumbnails/static preview frames separately from full
   surfaces;
8. add golden tests for the four canonical screen mappings;
9. inventory the release APK to prove that raw sources, Camera ICON, private
   screenshots, and the private PDF are absent.

## Runtime wallpaper acceptance

- Every canonical default is replaceable and resettable.
- All eight wallpapers are selectable for eligible screens/conversations.
- Inbox, Archive, Settings, Spam & Blocked, global thread fallback, and each
  conversation retain independent assignments.
- Static image and GIF user media use picker/SAF or explicit managed import,
  bounded metadata/decode, focal crop, dim, and reset-to-inherited.
- Each assignment persists its own adjustable dim value, shows a live preview,
  enforces a contrast-preserving minimum scrim, and resets dim with the scoped
  assignment rather than changing other surfaces.
- Body text reaches at least 4.5:1 contrast and important non-text affordances
  at least 3:1 over each built-in.
- Only the visible animated surface runs; inactive previews remain static.
- Short/tall phones, landscape, split screen, tablets, and 200% font scale keep
  actions and focal content usable.

## Licensing gate

Artwork copyright/license and required attribution have not been supplied in a
form this repository may publish. Do not assume GPL-3.0-or-later applies to the
artwork. Before any source or derivative is committed or distributed, the
owner must provide a written artwork license/permission that covers:

- source-repository publication, if desired;
- modification needed for non-destructive runtime derivatives;
- APK, F-Droid, GitHub release, and optional store distribution;
- promotional/public screenshot use;
- attribution text and placement;
- whether redistribution of original high-resolution PNGs is allowed.

Until then, Phase 1 uses an original non-Aurora diagnostic placeholder and
Phase 4 artwork ingestion is blocked.

The eventual approval record must identify the rights holder, evidence date
and secure evidence location, per-asset license/attribution terms, permitted
uses, and whether original redistribution is allowed. The Phase 4 derivative
manifest must add each generated asset's source hash, derivative hash, encoder
settings, dimensions, and visual-review result.

## Private visual-reference inventory

The handoff contains nineteen screenshot filenames at 921 x 2048. Integrity
verification passed, and two lower-settings filenames are byte-identical, so
the set contains eighteen unique pixel payloads. This is an inventory fact only:
the screenshots remain private black-box references and never become artwork,
fixtures, resources, or goldens.
