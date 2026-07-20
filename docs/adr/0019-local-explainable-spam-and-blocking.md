<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# ADR 0019: local explainable spam and provider-preserving blocking

- Status: Accepted, implemented, and locally accepted
- Date: 2026-07-19

## Context

Spam classification is inherently fallible. A false positive must not make a
provider message disappear, while an explicit sender block must still prevent
AuroraSMS from presenting an unwanted notification or retaining a reply target.
Remote reputation services would add a network and address-disclosure boundary
that conflicts with AuroraSMS's local-only FOSS baseline.

## Decision

AuroraSMS uses a fixed, bounded, explainable local warning rule. Automatic
warning applies only to an unknown conventional phone sender when the newest
incoming snippet contains all three of: a web link, a reviewed urgency term,
and a reviewed sensitive-request term. Saved contacts are trusted by default.
If Android contact access is unavailable, automatic warnings pause because
Aurora cannot safely distinguish a saved contact from an unknown sender.
Short codes, alphanumeric senders, group conversations, outgoing messages, and
incomplete participant identities are never automatically warned. Automatic
classification is presentation-only and is not persisted.

The user may independently mark an exact verified conversation spam or not
spam and block or unblock a verified one-person sender. An explicit not-spam
choice overrides the automatic warning. Inbox and Thread show the current
reason, and the dedicated Spam & blocked route exposes independent Not spam and
Unblock recovery actions. The first safe policy never automatically hides a
conversation and never deletes a suspected message.

Room schema 12 stores at most 256 meaningful user decisions. It stores a
purpose-separated SHA-256 identity for the complete verified participant set,
an independently purpose-separated sender-block key only for a one-person
conversation, provider Thread ID, classification code, block bit, optimistic
revision, and monotonic timestamp. It stores no raw address, contact, body,
snippet, keyword, or remote score. SQLite triggers enforce the row bound, hash
format, legal transitions, one-person block requirement, and strictly
increasing revisions/timestamps. The repository never evicts an existing
decision to make room for another.

UI decisions and recovery actions are available only after the index supplies
an exact complete participant identity for the current generation. The
dedicated route re-reads and revalidates each stored decision against that
identity before displaying or mutating it. Corrupt or unavailable state
disables the controls and produces an explicit failure instead of inventing a
classification.

An explicit sender block is checked after the incoming SMS is written to the
Android provider and before Aurora performs contact display-name resolution,
notification posting, reply-target ownership, or reminder scheduling. A match
acknowledges the provider delivery without those Aurora effects. The provider
row remains authoritative, readable, and indexed. Lookup failure fails open:
Aurora continues the normal notification path rather than silently suppressing
a message. Role loss continues to fail closed at every provider boundary.

This feature adds no permission, network path, remote reputation, background
worker, provider deletion, provider-body rewrite, archive action, or automatic
send. State remains app-private and excluded from backup/device transfer.

## Consequences

- Important messages remain present in Android's provider even after a false
  positive or explicit block, and every durable action has visible recovery.
- Blocking affects Aurora notification/reply/reminder behavior, not carrier
  delivery or another SMS application's provider presentation.
- The reviewed vocabulary is intentionally fixed in this slice. User-authored
  keyword rules, automatic hiding, remote scoring, and number-list import are
  outside this decision and require a new privacy and denial-of-service review.
- Physical carrier receipt and OEM notification behavior remain separate
  destination-aware acceptance work; emulator tests use synthetic provider
  state and never submit carrier traffic.
