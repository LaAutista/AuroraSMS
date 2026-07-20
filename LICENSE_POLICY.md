# AuroraSMS source licensing policy

Status: owner accepted for Phase 1 on 2026-07-12

Unless a file clearly states otherwise, original AuroraSMS source code and
project documentation are offered under the GNU General Public License,
version 3 or (at the recipient's option) any later version
(`GPL-3.0-or-later`). The unmodified license text is in `LICENSE`.

Copyright in each contribution remains with its contributor or the party to
which that contributor has validly assigned it. The repository does not invent
a legal owner name. Before the first public release, project governance may add
accurate owner/contributor copyright notices without changing the license
grant.

Production source files created after the Phase 0 gate use:

```text
SPDX-License-Identifier: GPL-3.0-or-later
```

Generated files use the license required by their generator/input and are
identified separately. Third-party material keeps its original compatible
license and notices under `THIRD_PARTY_NOTICES.md` and the generated license
report.

The twelve modified official-AOSP MMS composer source files under
`core/telephony/src/main/java/org/aurorasms/core/telephony/codec/aosp` retain
their Apache-2.0 headers and upstream copyright notices. Their immutable source
revision, exact file list, change notices, and complete Apache-2.0 text are in
`third_party/aosp-mms/`. They are not relicensed by the default GPL source-file
rule; the original Aurora wrapper and surrounding policy remain
GPL-3.0-or-later.

The owner-selected AuroraSMS launcher raster and its launcher XML layers are
offered under GPL-3.0-or-later. They are first-party project artwork, not a
restricted distribution exception. The canonical launcher retains the neon SMS
bubble, Aurora portrait, and exactly two simple purple hairpins while containing
no `A`, monogram, letter mark, or distribution logo. Asset provenance and the
accepted public hashes are recorded in `docs/ARTWORK_CATALOG.md`.

This policy does not license or redistribute:

- the private screenshots or illustrated private handoff PDF;
- any reference-application material;
- the ten private supplied Aurora source-art files or any private
  high-resolution working masters, whose separate publication and attribution
  gate is defined in `docs/ARTWORK_CATALOG.md`;
- user messages, contacts, attachments, wallpapers, exports, or other user
  data.

The Phase 0 owner review accepted the source-license selection on 2026-07-12,
before Phase 1 production code began. The owner separately granted the tracked
no-letter launcher artwork under GPL-3.0-or-later on 2026-07-19. A later change
after accepting outside contributions or publishing binaries requires a legal
and provenance review.
