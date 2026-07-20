# ADR 0001: bounded MMS PDU boundary

- Status: accepted for the Phase 1 foundation
- Date: 2026-07-12
- Scope: `:core:telephony`

## Context

Android's public `SmsManager` API sends and downloads MMS through a caller-
supplied `content://` URI. It does not expose a public API that constructs a
carrier-compatible MMS/WSP PDU or decodes an incoming WAP PDU into all of the
rows required by the Telephony provider. A default-SMS application must not
pretend that staging bytes is equivalent to correctly implementing those
formats.

AuroraSMS also requires a clean-room implementation, no general Internet
permission, bounded hostile-input handling, narrow URI grants, and no source
or dependency from an end-user messaging application.

## Considered strategies

### Original full PDU codec now

An original implementation could encode and decode the MMS and WSP structures
needed by known carriers. Doing this safely requires a separately specified
format surface, conformance corpus, carrier matrix, malformed-input fuzzing,
and physical-device evidence. Phase 1 has none of that evidence yet. A partial
codec that silently produces plausible-looking bytes would create a false
transport claim and is rejected.

### Official Android framework material

Official Android platform/framework material is an allowed future design
input when it is not sourced from an end-user messaging application. No
framework implementation source is copied or adapted by this decision. Before
using any such material, a follow-up ADR must record the exact files and
revision, license notices, modifications, supported formats, test corpus, and
removal/update plan.

### Maintained permissive codec library

No permissively licensed codec is currently admitted by the dependency policy.
Admitting one would require exact version/repository provenance, transitive and
manifest review, GPL compatibility, maintenance evidence, byte-size impact,
carrier coverage, and a fuzzing plan. There is no dependency exception for
cached or convenient artifacts.

### Public platform transport plus an original bounded byte boundary

AuroraSMS can independently implement the security-sensitive operation layer:
validate an already encoded payload, copy it into one private cache file,
expose one operation-specific URI, grant only read access for a send, invoke
the subscription-specific public `SmsManager` API, handle its asynchronous
result, revoke the grant, and delete the file. Download follows the inverse
contract with a new empty target and write-only access.

This strategy does not supply an MMS codec. Higher-level compose/decode calls
without a separately audited codec return an explicit typed unsupported
result.

## Decision

Phase 1 uses only Android's public `SmsManager.sendMultimediaMessage` and
`downloadMultimediaMessage` operation APIs plus an original bounded staging
implementation. No third-party MMS dependency is added.

The accepted boundary is deliberately narrow:

- an encoded send payload is at most 1 MiB in this foundation slice;
- staging copies caller bytes and never retains a caller-owned mutable array;
- send and download receive distinct random file names under separate private
  cache subdirectories;
- send URIs can be opened read-only and download URIs write-only;
- the provider is non-exported and grants only the exact operation URI;
- callbacks are explicit and immutable, and cleanup is idempotent;
- an invalid subscription, missing role/permission/feature, malformed URI,
  oversized payload, platform exception, or unavailable codec is a typed
  failure;
- there is no SMS fan-out fallback for group MMS.

Incoming WAP-PDU decoding, outgoing high-level PDU construction, SMIL/part
serialization, carrier configuration overrides, and broad carrier support are
not claimed by this ADR. The receiver hands a bounded raw event to the app
boundary; without an admitted decoder the event must fail visibly rather than
be partially persisted or reported as successful.

## Provenance and licensing

The implementation is original AuroraSMS GPL-3.0-or-later code. Its only MMS
runtime surface is the Android SDK API. There are no new runtime dependencies,
transitives, startup initializers, manifest permissions, native libraries, or
network permissions. The provider and receivers add only the components in the
approved permission ledger.

Primary API references:

- <https://developer.android.com/reference/android/telephony/SmsManager>
- <https://developer.android.com/reference/androidx/core/content/FileProvider>
- <https://developer.android.com/reference/android/provider/Telephony>

## Verification and follow-up gate

Synthetic tests cover byte limits and defensive copies, unique directions and
URIs, canonical cache confinement, traversal rejection, grant modes, result
mapping, repeated cleanup, invalid subscriptions, and the no-fan-out policy.
Device tests must additionally cover process death and every terminal platform
result without reading real contacts or message history.

Before AuroraSMS claims user-composed or received MMS support, a follow-up ADR
must admit a codec strategy and provide:

1. exact implementation provenance and license obligations;
2. supported WSP/MMS headers, PDU kinds, address/part/SMIL behavior, and hard
   parsing limits;
3. malformed and mutation fuzzing with a public synthetic corpus;
4. measured dependency and APK-size cost;
5. physical one-to-one and group MMS evidence across the approved carrier and
   device matrix;
6. a removal/update plan for any admitted external material.

Until that gate passes, `CodecUnavailable` is the correct result for high-level
MMS encoding or decoding. It must not be converted into SMS sends.

Consequently, the Phase 1 foundation cannot claim end-to-end MMS support until
both the codec gate and the required physical-device/carrier evidence pass.

## Phase 6F narrow amendment

ADR 0021 partially satisfies the follow-up source/provenance and synthetic
composition gates for exactly one outgoing one-person voice-memo `SendReq`.
That later decision pins and notices the selected official-AOSP source, fixes
the SMIL/text/audio shape and bounds, and adds golden/corpus, provider,
journal, callback, UI, permission, and API 26/API 36 recording evidence.

This does not amend the incoming-decoder, group/general-composer, arbitrary
attachment, or physical carrier/OEM requirements above. `CODEC_UNAVAILABLE`
remains correct for every high-level payload outside ADR 0021, and AuroraSMS
still cannot claim general end-to-end MMS until those remaining gates pass.
