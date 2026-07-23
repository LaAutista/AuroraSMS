# Aurora character consistency guide

Status: owner-selected no-letter launcher integrated; canonical full-body VNCCS
reference and eight private replacement wallpaper masters completed

This guide records Aurora's repeatable visual markers without copying the
private source illustrations into the repository. It applies to launcher
icons, wallpapers, screenshots, promotional art, and any future generated
derivative.

## Identity invariants

- Aurora is an adult anime-styled woman with violet eyes and long, near-black
  hair that may catch blue or violet rim light.
- Her silhouette uses one high side ponytail. Do not substitute twin tails,
  buns, a low ponytail, loose-only hair, cat ears, or horns.
- Exactly two short, simple purple hairpins sit parallel on the loose front
  bangs, opposite the ponytail. They must remain separate, aligned, visible,
  and free of crossings, forks, charms, or extra strokes. They are Aurora's
  only fixed hair ornaments.
- The ponytail base is plain. It has no letter clip, monogram, logo, rune, or
  other emblem.
- In the standard three-quarter composition, the bangs pins appear on the
  viewer's left and the ponytail appears on the viewer's right. A mirrored
  composition is acceptable only when both side cues mirror together.
- Her core outfit is a black hooded technical jacket with restrained violet
  hardware or edge light. Her hair, jacket, sleeves, straps, footwear, and
  accessories carry no letters, monograms, distribution logos, or invented
  brand marks. In particular, Aurora has no `A` marks anywhere.
- Her normal expression is calm, focused, or quietly confident. Avoid
  childlike proportions and exaggerated mascot features unless a separately
  approved chibi variant is requested.

Accessories such as Tux, a generic penguin charm, mug, earrings, bag, or
computer belong to specific scenes. They are optional and must never replace
the core hair and eye markers. When the accessory is recognizably Tux rather
than a generic penguin, retain the attribution recorded in
`THIRD_PARTY_NOTICES.md`.

## AuroraSMS icon language

- Combine Aurora with one unmistakable SMS speech-bubble silhouette.
- Use deep navy or near-black only inside the speech bubble, with violet,
  magenta, and cyan aurora light. The selected launcher has genuine alpha
  outside the neon bubble; do not bake an exterior blue tile into the raster.
- Keep the face, both bangs pins, violet eyes, and ponytail base inside the
  adaptive icon's central safe zone.
- Prefer a bold silhouette and limited background detail that remain readable
  at 48 px. Stars and glow are supporting texture, not the primary mark.
- Do not add the app name, any letter, monogram, watermark, distribution mark,
  or the AuroraCamera icon. The SMS bubble is the icon's only graphic symbol
  outside Aurora's portrait.
- The final adaptive icon requires separate foreground, background, and a
  simple bubble-only monochrome layer; a flattened concept preview is not a
  production asset.

## Review checklist

Before accepting any Aurora render, verify all of the following at full size
and at launcher-thumbnail size:

1. One high side ponytail is present.
2. Exactly two simple parallel purple bangs pins are present and are not fused,
   crossed, forked, decorated, or misplaced.
3. The ponytail base, jacket, sleeves, straps, footwear, and accessories are
   free of letters, monograms, and logo-like emblems.
4. Eyes are violet; hair is near-black; the technical jacket is black.
5. No `A`, monogram, or invented brand mark appears on Aurora or the speech
   bubble; no unrequested ears, horns, or hair ornaments appear. Intentional
   scene props, environmental display detail, and properly attributed Tux may
   remain.
6. All side-specific markers mirror together if the composition is mirrored.
7. For AuroraSMS icons, the speech bubble is immediately recognizable.
8. Cropping and launcher masks do not remove any core identity marker.

## Provenance boundary

The defining details above were derived from private, owner-supplied visual
references. Those source images and the high-resolution selected launcher
master remain outside the public repository and APK. The owner retained the
neon SMS-bubble and portrait composition from launcher Variant 2, superseded
its letter-mark treatment, and directed that the final launcher contain no `A`
or other letter marks. The tracked launcher artwork is offered under
GPL-3.0-or-later as recorded in `docs/ARTWORK_CATALOG.md`. This license decision
does not unlock either the inconsistent supplied wallpaper originals or the
completed private replacement masters, and it grants no publication rights to
their raw source files.
