# Permission and default-SMS role ledger

Status: Phase 1 source, merged manifests, and APKs locally verified against
official Android documentation on 2026-07-12; ADR 0007 records a managed-
wallpaper decision with no new permission on 2026-07-14; commit `7c9d848`
adds the ledgered debug snapshot boundary and durable-message hardening on
2026-07-18; ADR 0011 activates the conditional boot and exact-alarm entries for
bounded scheduled SMS with a visible inexact fallback on 2026-07-19

This ledger is the allowlist enforced against the Phase 1 source manifest,
merged debug/release manifests, and packaged APKs. A permission not listed
here is denied until this document and the threat model are reviewed together.

## Telephony hardware declaration

AuroraSMS's core purpose depends on device SMS/MMS telephony, so Phase 1 will
declare:

```xml
<uses-feature
    android:name="android.hardware.telephony"
    android:required="true" />
```

This intentionally filters devices that cannot provide the product's core
default-SMS function. Runtime code still checks `FEATURE_TELEPHONY` because OEM
capability/role availability and SIM presence can differ. A future read-only
companion/non-telephony distribution would require a separate product variant
and review; it is not implied by this ledger.

For API 26-32, capability gating uses `FEATURE_TELEPHONY` plus role/SIM checks.
On API 33+, AuroraSMS additionally requires
`PackageManager.FEATURE_TELEPHONY_MESSAGING` before using `SmsManager`; the API
may otherwise throw `UnsupportedOperationException`, and it checks
`FEATURE_TELEPHONY_SUBSCRIPTION` before subscription APIs. Subscription-level
SMS capability is also revalidated where the modern platform exposes it.

## Default-SMS component surface

Official Android documentation requires a qualifying SMS-role application to
declare four externally invoked component capabilities. Phase 1 will implement
the following with original AuroraSMS classes and explicit exported state.

### 1. Compose-message activity

- Action: `android.intent.action.SENDTO`
- Category: `android.intent.category.DEFAULT`
- Schemes: `sms`, `smsto`, `mms`, and `mmsto`
- Exported: `true`
- Behavior: validate the URI, normalize recipients off the main thread, reject
  malformed/unsafe input, and open an Aurora-owned composer without sending.

The current AndroidX role eligibility reference shows `smsto` as the minimum
qualifier. The Telephony API reference requires all four schemes for a complete
default SMS/MMS handler, so AuroraSMS will declare all four.

### 2. Respond-via-message service

- Action: `android.intent.action.RESPOND_VIA_MESSAGE`
- Category: `android.intent.category.DEFAULT`
- Schemes: `sms`, `smsto`, `mms`, and `mmsto`
- Component guard:
  `android.permission.SEND_RESPOND_VIA_MESSAGE`
- Exported: `true`
- Behavior: accept only validated system-authorized requests, resolve the
  selected subscription explicitly, and return quickly after handing bounded
  work to the transport layer.

### 3. SMS delivery receiver

- Action: `android.provider.Telephony.SMS_DELIVER`
- Component guard: `android.permission.BROADCAST_SMS`
- Exported: `true`
- App permission required to receive: `android.permission.RECEIVE_SMS`
- Behavior: only the default SMS app receives this delivery broadcast; it must
  persist the message through the Telephony provider and notify the user. PDU
  parsing and storage must not block the receiver deadline.

### 4. MMS/WAP delivery receiver

- Action: `android.provider.Telephony.WAP_PUSH_DELIVER`
- MIME type: `application/vnd.wap.mms-message`
- Component guard: `android.permission.BROADCAST_WAP_PUSH`
- Exported: `true`
- App permission required to receive:
  `android.permission.RECEIVE_MMS` and/or
  `android.permission.RECEIVE_WAP_PUSH` as required by the payload/platform
- Behavior: validate metadata and subscription, persist through the Telephony
  provider, and notify without decoding large media on the main thread.

`BROADCAST_SMS`, `BROADCAST_WAP_PUSH`, and
`SEND_RESPOND_VIA_MESSAGE` protect components. AuroraSMS must not request them
as ordinary `<uses-permission>` capabilities.

### Supporting broadcasts and visibility

AuroraSMS will also handle, where applicable:

- `android.provider.action.DEFAULT_SMS_PACKAGE_CHANGED`, using
  `EXTRA_IS_DEFAULT_SMS_APP` to enter or leave write-capable state safely;
- `android.provider.action.EXTERNAL_PROVIDER_CHANGE`, to schedule bounded
  reconciliation after another process changes the provider;
- app-private, explicit sent/delivered result broadcasts protected from other
  apps;
- package visibility for the SMS-delivery intent only if
  `Telephony.Sms.getDefaultSmsPackage()` is used on Android 11 or newer.

These supporting declarations do not replace the four role-qualifying
components.

## Role acquisition and permission order

1. Check `FEATURE_TELEPHONY`; on API 33+ also check
   `FEATURE_TELEPHONY_MESSAGING` and `FEATURE_TELEPHONY_SUBSCRIPTION`; show an
   honest unsupported/no-SIM state.
2. On API 29+, obtain `RoleManager`, call `isRoleAvailable(ROLE_SMS)`, check
   `isRoleHeld(ROLE_SMS)`, and launch
   `createRequestRoleIntent(ROLE_SMS)` only from an explicit user action.
3. On API 26-28, use `Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT` with
   `EXTRA_PACKAGE_NAME`.
4. Respect cancellation. Do not trap the user in repeated role dialogs.
5. Ask to become the default handler before requesting SMS runtime permissions.
6. Request only the permissions required for the next explained operation.
7. Verify the role again immediately before provider writes or transport
   actions, and transition safely when it is lost.

Role acquisition does not silently authorize optional contacts, notifications,
microphone, or exact-alarm features. Each optional capability keeps its own
just-in-time explanation and denial behavior.

## Core Phase 1 permissions

| Permission | Need | Request policy | Denial behavior |
|---|---|---|---|
| `READ_SMS` | Read and index provider SMS history | After default-role request; runtime | Show role/permission recovery; do not claim complete history |
| `SEND_SMS` | Send SMS and use platform messaging transport | After role; just before enabled transport flow | Keep drafts; show actionable disabled-send state |
| `RECEIVE_SMS` | Receive `SMS_DELIVER` | After role; runtime | Explain that incoming SMS cannot be handled |
| `RECEIVE_MMS` | Receive incoming MMS delivery | After role; runtime where required | Explain MMS receive limitation |
| `RECEIVE_WAP_PUSH` | Receive MMS WAP push | After role; runtime where required | Explain MMS receive limitation |
| `READ_PHONE_STATE` | Enumerate active subscriptions for mandatory dual-SIM selection | After role with a dual-SIM explanation; runtime | Disable subscription listing/remembering; never silently choose a different SIM |

These permissions are sensitive and may be hard restricted by the installer.
The build, distribution metadata, and onboarding must remain consistent with
AuroraSMS's actual default-SMS core purpose.

## Approved conditional permissions

Conditional permissions are absent until the named feature is implemented and
tested. `RECEIVE_BOOT_COMPLETED` and `SCHEDULE_EXACT_ALARM` are now active under
ADR 0011; the other entries remain conditional.

| Permission | Phase/feature | Rule |
|---|---|---|
| `READ_CONTACTS` | Phase 1 contact labels/photos | Optional; numbers/initials remain usable when denied |
| `POST_NOTIFICATIONS` | API 33+ messaging notifications | Request after role with privacy choice; foreground app remains usable when denied |
| `READ_PHONE_NUMBERS` | Own-number display only | Not approved for core transport; add only with a user-visible need |
| `VIBRATE` | Notification behavior | Normal permission; honor user/channel settings |
| `RECEIVE_BOOT_COMPLETED` | Phase 5 scheduled-SMS recovery — active under ADR 0011 | Re-arm only Aurora-owned durable operations; past-due reboot state pauses for review; no message content in alarms or logs |
| `SCHEDULE_EXACT_ALARM` | Phase 5 scheduled sending — active under ADR 0011 | User-facing special-access route; check capability before every exact arm; retain and label the honest inexact fallback. ADR 0012's short Undo timer reuses this already-declared capability only when available and remains safe with its private inexact recovery alarm. |
| `RECORD_AUDIO` | Phase 6 voice memo | Request only after tapping Record; no background recording |
| `USE_BIOMETRIC` | Optional app lock | App lock is not database encryption |
| Foreground-service permission/type | A measured long-running user-visible operation only | Add exact subtype and notification design before use |

Photo Picker and the Storage Access Framework are the default attachment,
wallpaper, import, and export paths. They do not justify broad media or storage
permissions.

ADR 0007's first static Thread-wallpaper slice uses the system picker only for
one user-selected temporary `content:` read. It does not request
`READ_MEDIA_IMAGES`, `READ_MEDIA_VISUAL_USER_SELECTED`, legacy storage access,
or any other permission, and it does not take a persistable URI grant. Cancel,
Back, lost target/source, and failed Apply retain no URI. Successful Apply
sanitizes bounded 8-bit Huffman baseline sequential-DCT (`SOF0`) JPEG with at
most four components and complete scan coverage, or CRC-valid non-APNG PNG with
at most 4,096 chunks, no `iCCP`/`zTXt`/`iTXt` ancillary chunks, and a complete
zlib scanline stream, into an app-private static
WebP under `noBackupFilesDir`; Room retains only a redacted content digest. No
Photo Picker backport-install service/component is added. A future live
external-media reference requires a separate URI/grant lifecycle review before
implementation.

## Explicitly forbidden or rejected permissions

| Permission/capability | Decision |
|---|---|
| `android.permission.INTERNET` | Forbidden in every FOSS manifest and merged variant |
| `android.permission.ACCESS_NETWORK_STATE` | Rejected unless a future non-FOSS design is separately approved; not needed for local indexing |
| `MANAGE_EXTERNAL_STORAGE` | Forbidden |
| Legacy `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` | Rejected; use picker/SAF |
| Broad `READ_MEDIA_*` | Rejected for ordinary attachments/wallpapers |
| `READ_MEDIA_VISUAL_USER_SELECTED` | Rejected for ADR 0007; direct system-picker use needs no media permission |
| `READ_CALL_LOG` / `WRITE_CALL_LOG` | Outside product scope |
| `CALL_PHONE` | Rejected; use a user-confirmed system dial intent |
| `QUERY_ALL_PACKAGES` | Forbidden |
| `REQUEST_INSTALL_PACKAGES` | Outside product scope |
| Location permissions | No SMS/MMS product need |
| Camera permission | Not required for Phase 1; prefer system capture/picker unless an in-app camera is later approved |
| Device-admin/accessibility/VPN permissions | Outside product scope |

## Backup and data-extraction declarations

The Phase 1 application manifest must set `android:allowBackup="false"`. Modern
backup/data-extraction rule files must exclude:

- index and durable-state databases, journals, and schema exports containing
  user data;
- DataStore/preferences;
- message/attachment thumbnails and wallpaper previews;
- temporary imports, exports, shares, decoded media, and managed wallpaper
  derivatives;
- pending-send, pending-delete, schedule, and notification state.

A later user-initiated Aurora backup is an application feature, not permission
for OS/cloud backup. Its format, encryption/authentication, limits, and recovery
must be threat-modeled before implementation.

## Component exposure rules

- Every component has explicit `android:exported` state.
- Only the four documented role entry points and separately reviewed share/deep
  links may be exported.
- Exported receivers/services require the official guarding permission where
  specified and validate action, scheme, MIME type, extras, URIs, and caller-
  controlled sizes.
- Internal sent/delivered callbacks use explicit app-scoped intents and
  immutable/update-current `PendingIntent` flags appropriate to the payload.
- The sole mutability exception is the explicit inline-reply `PendingIntent`:
  on API 31+ it uses `FLAG_MUTABLE` because `RemoteInput` must attach results.
  It targets one explicit Aurora receiver, carries no implicit component, and
  validates the conversation/role/subscription before send. Content, tap,
  SMS/MMS result, alarm, and other pending intents remain immutable.
- File sharing uses a narrow `FileProvider` path allowlist and temporary URI
  grants; no filesystem paths are exposed.

## Expected AndroidX manifest contribution

Approved AndroidX Core 1.19.0 contributes these non-dangerous, app-scoped
merged-manifest entries:

- a signature permission named
  `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`;
- a matching `<uses-permission>` used by AndroidX to protect compatible
  not-exported dynamic receivers;
- the `androidx.core.app.CoreComponentFactory` application component-factory
  attribute.

These exact entries are ledgered and do not grant network or user-data access.
The permission name must resolve under the AuroraSMS application ID; any other
AndroidX or transitive manifest addition remains unapproved.

The debug build explicitly removes AndroidX Startup and ProfileInstaller
components. Release keeps exactly one non-exported
`androidx.startup.InitializationProvider` containing only
`ProfileInstallerInitializer`, plus one exported
`ProfileInstallReceiver` guarded by the platform signature/privileged DUMP
permission. These components install the checked-in Baseline Profile for local
and older-device delivery; they add no app permission or network path.

The debug variant additionally exports exactly one Aurora-owned provider at
`org.aurorasms.app.debug.sms_snapshot`. The provider is guarded by the platform
`android.permission.DUMP` permission and independently requires
`Binder.getCallingUid() == Process.SHELL_UID`; AuroraSMS does not request
`DUMP` as a `<uses-permission>`. It is a disposable-emulator, read-only probe
that returns only SMS `_id`, `thread_id`, and `type`, rejects selection,
selection arguments, sorting, inserts, updates, deletes, calls, and file opens,
and exposes no body, address, date, or attachment field. The merged-manifest
verifier requires exactly this boundary in debug and rejects its provider class
or authority in release, benchmark, and every other non-debug app variant.

The Phase 3 app benchmark target is a separate, local, non-debuggable,
profileable build identity. It alone declares the signature permission
`org.aurorasms.app.permission.BENCHMARK_CONTROL`, exposes
`org.aurorasms.app.benchmark.fixture` behind that permission, and uses the same
audited ProfileInstaller components as release.
The same-signed macrobenchmark test APK requests the control permission. Its
Benchmark/Perfetto tooling also declares `INTERNET` for a localhost-only trace
processor, package query, legacy report-storage, task-reordering, and its
AndroidX Core package-private signature receiver permission. All inherited SMS,
phone, contacts, notification, vibration, and telephony-feature declarations
are explicitly stripped from the macrobenchmark manifest. The runner
self-instruments its separate test package and controls
the app only through Macrobenchmark shell APIs and the signature-protected
fixture provider; it never executes inside the SMS app process. None of these
test-only permissions or benchmark controls is allowed in the normal app APK;
merged-manifest and build-identity APK checks enforce the split.

The MMS PDU staging provider is an Aurora-owned `FileProvider` subclass,
exported `false`, with `grantUriPermissions=true` and cache-only paths limited
to short-lived MMS PDU operations. A send stages one bounded source PDU and
grants the platform operation read access only. A download pre-creates one
dedicated empty target and grants write access only. Each URI is unique to its
operation; grants go only to the platform telephony operation, are revoked and
cleaned after result or timeout, and never expose a broad directory or the
opposite access mode.

Incoming-delivery replay fingerprints and inline-reply target, claim,
operation, and incoming-notification-generation stores use bounded app-private
shared preferences. They are excluded from OS backup and device transfer and
use versioned canonical encodings, synchronous security-boundary commits, and
checksums. The reply target retains the exact validated recipient required for
cold-process routing but no body; the claim retains a recipient digest; the
operation and generation stores retain only bounded provider-qualified IDs,
lifecycle/progress/status, and notification ordering evidence. The incoming
journal v4 additionally binds its delivery key to the canonical payload and a
redacted provider-content digest; checksummed, key-bound `Q1` quarantine
tombstones retain ownership of malformed entries without displacing unrelated
valid recovery state. Role loss serializes authoritative role reconciliation,
cancels and joins pending recovery work, performs exact-generation notification
cleanup, and clears reply targets. A `goAsync()` lease timeout does not cancel
already accepted process-local sibling work, but no component claims that work
survives Android process death. Reply-failure notifications are generic and do
not include reply text, recipient, address, carrier error, or message body.

## Verification gate

Phase 1 CI/device checks must:

1. inspect the merged manifest, not only the source manifest;
2. fail if `INTERNET`, `ACCESS_NETWORK_STATE`, or an unlisted permission
   appears;
   the exact package-scoped AndroidX Core dynamic-receiver permission above is
   listed and expected;
3. assert all four default-SMS role component filters, schemes, MIME type,
   guards, and exported values;
4. assert the required telephony feature declaration and runtime unsupported/
   no-SIM behavior, including API 33+ messaging/subscription features;
5. verify `allowBackup=false` plus exclusion rules;
6. use package/role inspection to confirm availability, grant, cancellation,
   loss, and reacquisition on supported devices;
7. confirm only the default holder writes incoming messages and posts the user
   notification;
8. test malformed implicit intents and oversized/untrusted URI inputs;
9. test the narrow mutable inline-reply exception and immutable flags on every
   other pending-intent path;
10. test that the MMS provider is non-exported, confines canonical cache paths,
    grants read-only for send and write-only for download, rejects traversal,
    and revokes both modes on every terminal result;
11. require the exact `DUMP`-guarded, shell-UID-checked snapshot provider only
    in debug, exercise its read-only/column boundary, and reject its class or
    authority from every non-debug merged manifest;
12. record the exact device/API and merged permission list as gate evidence.

## Official references

- [Android Telephony: creating an SMS app](https://developer.android.com/reference/android/provider/Telephony)
- [AndroidX role eligibility and required SMS components](https://developer.android.com/reference/androidx/core/role/RoleManagerCompat)
- [RoleManager and `ROLE_SMS`](https://developer.android.com/reference/android/app/role/RoleManager)
- [Telephony SMS intent broadcasts](https://developer.android.com/reference/android/provider/Telephony.Sms.Intents)
- [Permissions used only in default handlers](https://developer.android.com/guide/topics/permissions/default-handlers)
- [Manifest permission reference](https://developer.android.com/reference/android/Manifest.permission)
- [Common text-messaging intents](https://developer.android.com/guide/components/intents-common#Messaging)
