<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# AOSP MMS composer provenance

AuroraSMS contains a selected source subset from the official Android Open
Source Project repository:

- repository: `https://android.googlesource.com/platform/frameworks/opt/mms`;
- immutable revision: `4bfcd8501f09763c10255442c2b48fad0c796baa`;
- upstream root: `src/java/com/google/android/mms`;
- upstream license: Apache License 2.0;
- local license copy: `third_party/aosp-mms/LICENSE`.

The selected files are:

- `InvalidHeaderValueException.java`;
- `MmsException.java`;
- `pdu/CharacterSets.java`;
- `pdu/EncodedStringValue.java`;
- `pdu/GenericPdu.java`;
- `pdu/MultimediaMessagePdu.java`;
- `pdu/PduBody.java`;
- `pdu/PduComposer.java`;
- `pdu/PduContentTypes.java`;
- `pdu/PduHeaders.java`;
- `pdu/PduPart.java`; and
- `pdu/SendReq.java`.

Local copies live under
`core/telephony/src/main/java/org/aurorasms/core/telephony/codec/aosp`.
Every upstream copyright and Apache-2.0 header is retained.

AuroraSMS changes are deliberately narrow:

1. repackage the selected classes from `com.google.android.mms` to the isolated
   AuroraSMS namespace and update only their internal imports;
2. remove the upstream stack-trace side effect for malformed first-part
   metadata and return the existing composition-error result instead; and
3. normalize four whitespace-only upstream formatting defects; and
4. compile only the selected outgoing `SendReq` composition graph.

No upstream parser, incoming decoder, APN/network client, transaction service,
database, UI, or end-user messaging application source is copied. The original
Aurora wrapper and all policy, bounds, provider ownership, journal, UI, and tests
remain GPL-3.0-or-later. See ADR 0021 for the admitted surface and update rule.
