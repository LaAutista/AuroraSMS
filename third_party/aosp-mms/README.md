<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# AOSP MMS codec provenance

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
- `ContentType.java`;
- `pdu/AcknowledgeInd.java`;
- `pdu/Base64.java`;
- `pdu/CharacterSets.java`;
- `pdu/DeliveryInd.java`;
- `pdu/EncodedStringValue.java`;
- `pdu/GenericPdu.java`;
- `pdu/MultimediaMessagePdu.java`;
- `pdu/NotificationInd.java`;
- `pdu/NotifyRespInd.java`;
- `pdu/PduBody.java`;
- `pdu/PduComposer.java`;
- `pdu/PduContentTypes.java`;
- `pdu/PduHeaders.java`;
- `pdu/PduPart.java`;
- `pdu/PduParser.java`;
- `pdu/QuotedPrintable.java`;
- `pdu/ReadOrigInd.java`;
- `pdu/ReadRecInd.java`;
- `pdu/RetrieveConf.java`;
- `pdu/SendConf.java`; and
- `pdu/SendReq.java`.

Local copies live under
`core/telephony/src/main/java/org/aurorasms/core/telephony/codec/aosp`.
Every upstream copyright and Apache-2.0 header is retained.

AuroraSMS changes are deliberately bounded:

1. repackage the selected classes from `com.google.android.mms` to the isolated
   AuroraSMS namespace and update only their internal imports;
2. remove the upstream stack-trace side effect for malformed first-part
   metadata and return the existing composition-error result instead;
3. normalize four whitespace-only upstream formatting defects;
4. copy parser input defensively and reject an empty PDU or one larger than
   1,048,576 bytes;
5. cap a parsed PDU at 25 parts, each part header at 8,192 bytes, each WAP
   string at 2,048 bytes, and nested multipart-alternative bodies at depth 8;
6. validate declared lengths, unsigned-integer width, array bounds, and all
   end-of-input reads before allocation or use;
7. skip unrecognized bounded values without allocating attacker-sized arrays;
8. move parser `start` and `type` parameters from static process state to each
   parser instance;
9. replace clock-derived anonymous part names with deterministic local part
   identifiers and correct multipart-alternative first-part selection;
10. remove parser logging of message-derived data and remove the private
    framework-resource dependency around content-disposition parsing; and
11. compile only the selected `SendReq` composer plus the parser dependency
    closure needed for notification and retrieved-message decoding.

No upstream APN/network client, persister, transaction service, database, UI,
or end-user messaging application source is copied. The original Aurora
wrappers and all policy, additional bounds, provider ownership, journals, UI,
and tests remain GPL-3.0-or-later. See ADR 0021 for the outgoing surface and
ADR 0024 for the incoming decoder boundary and update rule.
