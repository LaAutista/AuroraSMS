# Aurora artwork catalog and asset policy

Status: no-letter GPL launcher integrated and eight private canonical no-mark
wallpaper masters complete; runtime/public wallpaper ingestion remains gated;
ADR 0007 managed private user imports do not cross this artwork gate

## Handling decision

The ten supplied source PNGs remain only inside the ignored private handoff at
`AuroraSMS_Codex_Handoff_PRIVATE/artwork/source/`. Phase 0 does not copy,
convert, stage, commit, or package them.

Nine files passed their supplied SHA-256 checks on 2026-07-12; the later
uncataloged Chibi file was independently hashed on 2026-07-19 and added to the
private manifest. All eight wallpapers are 941 x 1672 RGBA PNGs; the camera
icon is 1254 x 1254 RGBA and the Chibi image is 1254 x 1254 RGB. The eight
wallpapers and camera icon have fully opaque alpha channels. The eight source
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
| `Aurora Chibi.png` | `7fb9db833d35a9ccef85f3c1d43e67923c4a897de312876c948af3945db012ab` | None | Rejected non-canon private reference; never package |

The reference chooser's label `Aurora Archive` corresponds to the supplied
Cabinet artwork; no missing `aurora_archive` file should be invented. The
reference screenshots show Bridge selected for an older/user inbox state, but
fresh AuroraSMS installs default to Night.

## AuroraSMS launcher direction and FOSS license

The owner retained launcher Variant 2's neon SMS-bubble and Aurora-portrait
composition, then superseded its interim letter-mark treatment. The canonical
launcher now contains exactly two simple parallel purple hairpins and no `A`,
letter, monogram, distribution logo, or invented brand mark anywhere on
Aurora, her clothing, the bubble, or the surrounding art. The ponytail base and
upper sleeve remain plain.

The tracked launcher foreground and launcher XML layers are first-party
AuroraSMS artwork offered under GPL-3.0-or-later. The private high-resolution
working masters remain ignored and unpackaged; that repository boundary does
not narrow the public GPL-3.0-or-later grant for the tracked launcher files.

| Public asset | SHA-256 | State and license |
|---|---|---|
| `app/src/main/res/drawable-nodpi/ic_launcher_aurora.png` | `b9a52ccc607e789182e013d77b626f339f2bb6a5a5ed16de696045a50b8cc111` | 768 x 768 RGBA no-letter runtime portrait; GPL-3.0-or-later |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | `b79e91e389ac497368462779b0788a85bd8b5bcf92a6cfcd16d2ba39e50d9d2e` | 66 dp-safe adaptive foreground inset; GPL-3.0-or-later |
| `app/src/main/res/drawable/ic_launcher_monochrome.xml` | `126c84efe81e51c147f895464518c5f54772088a3e9f6f998229a0eec4d0058b` | 66 dp-safe bubble-only themed-icon vector with no letter geometry; GPL-3.0-or-later |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | `6ca01bfef00b075c2125265163fe7c693bc8999e6bd2019027e45e879c8dec20` | Adaptive-icon resource; GPL-3.0-or-later |
| `app/src/main/res/values/colors.xml` | `fd07bf361bcd6a8f42a6028fb7973947310ef1031831c1725ee174d90fca006e` | Launcher background color resource file; GPL-3.0-or-later |

The earlier letter-bearing master, runtime export, and themed-icon vector are
retired and are not approved release assets. Their fingerprints remain only in
the private provenance record and clean-room denylist. The replacement public
hashes above were recomputed from the current workspace on 2026-07-19; no
retired hash represents an accepted launcher.

The ignored private no-letter launcher master is
`AuroraSMS Launcher Variant 2 - No Marks Master.png`, SHA-256
`892aff14e993836408bad8fd965dfdc3fe251fc18a07c71c10f22b7f8ce0684d`.
It is the lossless provenance source for the public export and is never
packaged.

The final portrait cleanup removes every letter/logo emblem rather than
replacing one emblem with another. No separate letter-mark SVG is composited.
The exterior green working screen is removed to real alpha. The runtime asset
is generated from the private master with:

```text
ImageMagick resize 768x768, metadata strip, 8-bit RGBA,
PNG compression level 9
```

The adaptive foreground and monochrome layer use a 21 dp inset, placing their
content inside Android's 66 x 66 dp guaranteed safe zone, and the resource
background is transparent, so no dark-blue exterior tile is baked into the
artwork. Android surfaces may choose their own color when compositing
transparent adaptive-icon space. The earlier API 36 review covered the retired
export; repeat App Info, launcher-drawer, adaptive-mask, and themed-icon review
after the no-letter foreground and bubble-only monochrome layer are installed.

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
- Chibi fails the adult primary canon, two-hairpin rule, and no-letter rule. It
  remains a private rejected reference unless the owner separately requests a
  corrected chibi variant.

At a tall 921 x 2048 display ratio, a 941 x 1672 source used with cover scaling
must enlarge and crop horizontal content. Runtime presentation therefore uses
a non-destructive saved focal point rather than baking one crop into the source.

## Canonical no-mark wallpaper masters (private)

The private replacement pass is complete for all eight wallpaper scenes. The
accepted no-letter full-body reference is `Aurora Full Body - VNCCS Canonical
v2 - No Marks.png`, SHA-256
`5b9e4179f5662257eb38d799d93d38ba1b11f35c3b1151382ca406b98e3fd695`.
The superseded letter-mark reference remains private and rejected, SHA-256
`bd707eabca51ff0d7a78ce7f832e705c26d546a44635eb2e2f1c4399ca4cc870`.

Each 1152 x 2048 master was produced locally with the source-preserving VNCCS
workflow: `Qwen-Image-Edit-2511-NVFP4`, 20 steps, CFG 1, Euler/simple sampling,
Lightning disabled, and reference weight 0.35. An exact-source mask composite
retained unedited source pixels and scene content, followed by deterministic
bounded cleanup and a pin overlay. On Aurora herself, the accepted outputs have
no `A`, monogram, invented brand mark, or clothing emblem and exactly two
simple parallel purple bangs pins. Original scene props, Tux, and incidental
environmental display detail remain intact.

| Private ignored master | Seed | SHA-256 |
|---|---:|---|
| `Aurora Bridge - VNCCS Canonical No Marks.png` | `711904` | `359baf526047822f172ff1a124ed3db767ddd143227dd272e4dbfc3becfd8865` |
| `Aurora Cabinet - VNCCS Canonical No Marks.png` | `712001` | `4e65cae6b5183172a293099a409e3d2759f76aa40117e8b52852aec47d425449` |
| `Aurora Cat - VNCCS Canonical No Marks.png` | `712002` | `01094403495964f2143360f8eef6ee35d052c54a2889d5704e1d24bf525d7803` |
| `Aurora Cherry Blossom - VNCCS Canonical No Marks.png` | `712003` | `ea90eae72585345a008a5aca4a3111087e94cfef17e5ea4de551e882144cdb44` |
| `Aurora City - VNCCS Canonical No Marks.png` | `712004` | `6b45a468fcf43cb079eb0114afd7f9efadbe09fb0aee39b1aaaf7156430a67d8` |
| `Aurora Night - VNCCS Canonical No Marks.png` | `712005` | `cf20ae093f1db33c14dc67e20eb8252ba09b9966020f0c26c0aef0e4eab66dfd` |
| `Aurora Office - VNCCS Canonical No Marks.png` | `712006` | `2fef79182af6c01cfba57f7553cd4efdea840d005027c154e12451cdaa98d547` |
| `Aurora Spam & Block - VNCCS Canonical No Marks.png` | `712007` | `3c8d5ee5361e45463034ea38e034e0595d509f5d1e736150f0684959151416a0` |

These are visual-consistency approvals only. The files stay inside the ignored
`AuroraSMS_Codex_Handoff_PRIVATE/artwork/regenerated/` archive, are not
packaged, and do not cross the public/runtime licensing gate below.

## Consistent wallpaper replacement pipeline

The supplied wallpapers and canonical no-mark masters are private working
inputs, not publishable runtime derivatives. The private consistency pass is
complete. Any future public/runtime derivative pipeline must:

1. preserve the original PNG bytes unchanged and private outside Git/APKs;
2. establish one accepted full-body Aurora canon through the local VNCCS
   consistency workflow before regenerating any wallpaper;
3. regenerate each scene from that canon, enforcing the character guide and
   exactly two simple parallel purple hairpins while removing every `A`,
   monogram, clothing emblem, or invented brand mark from Aurora; preserve
   intentional scene props, Tux, and environmental display detail;
4. record every accepted replacement's prompt/workflow version, seed, model
   identifiers, source-reference hashes, output hash, dimensions, and visual
   checklist result;
5. generate screen-sized static WebP runtime derivatives without destructive
   cropping and retain a private lossless approved master;
6. treat wallpapers as density-independent resources and crop at runtime from
   a saved focal point;
7. test conversion quality for dark-gradient banding and fine stars, rain,
   hair, blossoms, and city detail;
8. keep the complete packaged built-in wallpaper set at or below 12 MiB
   compressed unless the owner approves a measured quality exception;
9. decode only the active wallpaper or selected preview, never the whole set at
   startup;
10. generate cached thumbnails/static preview frames separately from full
    surfaces;
11. add golden tests for the four canonical screen mappings;
12. inventory the release APK to prove that raw sources, Camera ICON, Chibi,
    all private high-resolution working masters, private screenshots, and the
    private PDF are absent.

## Runtime wallpaper acceptance

- Every canonical default is replaceable and resettable.
- All eight wallpapers are selectable for eligible screens/conversations.
- Inbox, Archive, Settings, Spam & Blocked, global thread fallback, and each
  conversation retain independent assignments.
- ADR 0007's first user-media slice uses the system picker as a temporary read,
  accepts only bounded 8-bit Huffman baseline sequential-DCT (`SOF0`) JPEG with
  at most four components and complete scan coverage, or CRC-valid non-APNG PNG
  with at most 4,096 chunks, no `iCCP`/`zTXt`/`iTXt` ancillary chunks, and a
  complete zlib scanline stream. It strips metadata into a
  private static WebP and stores no source URI or grant. It applies only to
  `global_thread` and verified conversations.
- GIF, live external URI, and other-format user media remain deferred and
  require their own bounded metadata/frame/duration/decode, lifecycle, and
  URI/grant decisions.
- Each assignment persists its own adjustable dim value, shows a live preview,
  enforces a contrast-preserving minimum scrim, and resets dim with the scoped
  assignment rather than changing other surfaces.
- Body text reaches at least 4.5:1 contrast and important non-text affordances
  at least 3:1 over each built-in.
- Only the visible animated surface runs; inactive previews remain static.
- Short/tall phones, landscape, split screen, tablets, and 200% font scale keep
  actions and focal content usable.

## Licensing gate

The owner directed that the tracked launcher raster and launcher XML layers be
offered under GPL-3.0-or-later. That FOSS grant covers those final no-letter
launcher files; it is not a general license for the ten private source PNGs or
replacement wallpapers. Do not assume GPL-3.0-or-later applies to those other
private or generated raster assets.

Before any replacement wallpaper is committed or distributed, its approval
record must cover:

- source-repository publication, if desired;
- modification needed for non-destructive runtime derivatives;
- APK, F-Droid, GitHub release, and optional store distribution;
- promotional/public screenshot use;
- attribution text and placement;
- whether redistribution of original high-resolution PNGs is allowed.

Until then, the selected launcher may ship, but built-in wallpaper ingestion
remains blocked.

An owner-selected private wallpaper imported under ADR 0007 is neither a
repository asset nor approval to publish Aurora artwork. Its sanitized
derivative remains in that installation's `noBackupFilesDir`, is never packaged
or committed, and does not alter this licensing gate.

Each wallpaper approval record must identify the rights holder, evidence date
and secure evidence location, per-asset license/attribution terms, permitted
uses, and whether original redistribution is allowed. The derivative manifest
must add each generated asset's source hashes, output and runtime-derivative
hashes, workflow/model/seed/encoder settings, dimensions, and visual-review
result.

## Private visual-reference inventory

The handoff manifest records nineteen screenshot filenames at 921 x 2048. One
recorded file, `Screenshot_20260712-010742_Messages_debug.png`, is no longer
present locally. The remaining eighteen files pass their recorded checksums;
two lower-settings filenames are byte-identical, leaving seventeen unique
present payloads. The missing file's historical SHA-256 remains in the
clean-room denylist. These are inventory facts only: screenshots remain private
black-box references and never become artwork, fixtures, resources, or goldens.

The clean-room denylist additionally covers the private PDF, all ten private
source-art files, two launcher masters, two full-body masters, and eight
canonical wallpaper masters. Together with the seventeen present screenshot
payloads and one retained historical screenshot fingerprint, that is exactly
41 unique private-input fingerprints. The final
accepted public 768 px launcher derivative has a different hash and is the only
selected portrait raster packaged by the app; runtime/public wallpaper
ingestion remains gated.
