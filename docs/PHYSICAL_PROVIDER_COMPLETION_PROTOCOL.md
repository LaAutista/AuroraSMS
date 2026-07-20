<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Physical provider-completion protocol

Status: required and not yet executed for the current release candidate

This protocol is the release gate for the owner's nonempty Pixel history. It
proves that AuroraSMS exhausts both provider cursors and reconciles a verified
generation before Inbox, Thread, search, and exact old-result navigation are
accepted. It does not authorize a carrier send, provider write, message export,
screen capture, broad log, backup, or role/permission change by automation.

## Data boundary

The runner reads only AuroraSMS-owned aggregate index metadata through
`run-as` and SQLite `PRAGMA query_only=ON`. Its fixed query returns:

- generation state, nonempty committed count, and content-free completion bits;
- SMS/MMS exhausted, committed, and verified provider counts; and
- aggregate index/conversation/FTS consistency counts.

It never selects provider IDs, thread IDs, timestamps, addresses, bodies,
subjects, attachment metadata, search terms, or contact data. It never invokes
the debug SMS-row snapshot provider. Terminal output is limited to aggregate
counts and state codes.

Role recovery is intentionally asymmetric. An incomplete paused generation
resumes its durable cursors so a large history does not restart from the newest
page. A previously complete generation starts a fresh full generation after
`ROLE_CHANGED`, because arbitrary provider changes may have occurred while
Aurora lacked authority; a bounded head check cannot prove deep history stayed
unchanged.

## Owner-visible sequence

1. Confirm there is no active call and the Pixel 8 is awake, unlocked, and
   connected through authorized ADB. Do not begin while a call or ringtone is
   active.
2. Build and install the exact reviewed debug APK separately with app data
   preserved. Record its SHA-256 and the current SMS-role holder. Installation
   does not authorize launch or provider access.
3. On the Pixel, the owner selects AuroraSMS as the default SMS app through
   normal Android UI and accepts the required SMS permission flow. The owner
   keeps AuroraSMS visible. Automation must not assign the role or grant a
   permission.
4. With the owner's separate approval for this provider-read window, run:

   ```shell
   ./scripts/run-physical-provider-completion-smoke.sh \
       --device SERIAL \
       --acknowledge-owner-visible-provider-read
   ```

5. The runner refuses emulators and non-Pixel-8 hardware, active calls, locked
   or sleeping state, a backgrounded AuroraSMS process, a non-Aurora role
   holder, missing `READ_SMS`, an APK hash mismatch, loss of `run-as`, an
   unavailable SQLite verifier, and any role or permission drift. It records
   the pre-run aggregate update marker, force-stops only AuroraSMS, confirms
   that exact process is absent, then cold-launches only `MainActivity`.
   AuroraSMS must remain alive and foreground throughout the scan. Success
   requires a later update marker and three identical verified aggregate
   snapshots, so an old complete generation cannot satisfy the current run.
6. After the runner succeeds, the owner privately verifies that Inbox reaches
   the expected oldest history, opens an old Thread, performs a known local
   search, and confirms the exact result jump. The agent does not request the
   search phrase, message, address, screenshot, or result content. A simple
   owner pass/fail is the only retained UI evidence.
7. The owner restores the preferred daily SMS app through normal Android UI.
   A content-free post-check records only the restored package name, AuroraSMS
   permission booleans, installed APK hash, stopped/running state, and absence
   of carrier operations. Automation does not restore the role or revoke a
   permission.

If the editor, ADB transport, app, or runner exits before step 5 succeeds, the
result is incomplete. Re-establish the exact prerequisites and rerun; never
infer completion from a partial count or an earlier generation.

## Acceptance and nonclaims

Provider completion passes only when the latest generation is nonempty and
`COMPLETE`, both SMS and MMS checkpoints are exhausted and carry verified
counts no smaller than their committed counts, checkpoint totals equal the
indexed generation, conversation summaries and unread totals reconcile, and
the FTS row count matches the index. The same snapshot must remain stable for
three polls after a proven cold-start refresh while the APK, role, and
permission state remain unchanged.

This gate does not prove current carrier delivery, SMS/MMS sending, group
self-line discovery, dual-SIM routing, billing/roaming behavior, OEM
notifications, lockscreen privacy, accessibility, physical performance, or
gold readiness. Those remain separate owner-approved release gates.
