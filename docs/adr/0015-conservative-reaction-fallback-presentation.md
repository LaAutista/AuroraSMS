<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# ADR 0015: conservative reaction fallback presentation

- Status: Accepted and implemented
- Date: 2026-07-19

## Context

Some messaging clients represent a reaction over SMS as a new plain-text
message such as `Liked “original text”`. AuroraSMS must make common forms easier
to read without pretending ambiguous prose is protocol metadata or changing the
authoritative provider message.

The fallback is not a standardized reaction envelope. Locale, punctuation,
quoting, truncation, duplicate quoted text, and client wording vary. Guessing
can hide a real message or attach a reaction to the wrong content.

## Decision

Phase 6A recognizes only an exact whole-message allowlist of common English add
and remove forms. The quoted target must use a matched straight or curly quote
pair, be nonblank, single-line, control-free, and at most 4,096 characters. The
complete visible message must be available; truncated previews are never parsed.

An exact match is rendered in its own bounded reaction card with the action and
quoted target. Parsing occurs only in the feature presentation layer. Provider,
index, timeline, search, draft, and backup models retain the original SMS body.
Malformed, unknown, differently cased, multiline, trailing, truncated, or
oversized input remains the ordinary raw message.

Phase 6A does not hide the provider row, associate it with another row, alter
search results, infer a target from partial history, or send a reaction.

## Consequences

- Recognized fallbacks are clearer while remaining truthful SMS messages.
- Ambiguity fails open to raw content rather than a possibly false reaction.
- The parser and model redact target content from `toString`.
- Locale-specific wording, row association, native reactions, and outgoing
  reaction transport remain future work and must preserve these conservative
  boundaries.
