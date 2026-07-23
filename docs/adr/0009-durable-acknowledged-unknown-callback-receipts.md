# ADR 0009: Durable acknowledged-unknown callback receipts

- Status: Accepted
- Date: 2026-07-19
- Scope: Phase 5B existing-Thread one-unit SMS lifecycle

## Context

Phase 5A keeps `SUBMISSION_UNKNOWN` locked until an exact callback supplies proof
or the user explicitly chooses “Keep as draft.” That acknowledgement preserves the
draft and removes the active operation so a later explicit send can proceed. It
also removed the only durable owner of the exact provider row. A later callback
was correctly consumed by the composer route but could no longer reconcile that
row after acknowledgement or process death.

The fix must retain no message content, must not keep the composer locked, must
not clear the user-preserved draft, must never resend, and must not broaden
provider mutation beyond the exact message-and-conversation binding.

## Decision

### Schema 6 owns a separate content-free receipt

`AuroraStateDatabase` schema 6 adds
`acknowledged_composer_sms_receipts`. Each row contains only:

- the existing composer operation ID;
- exact provider message ID, provider conversation ID, and one-unit count;
- callback proof: `AWAITING_CALLBACK`, `SENT`, or `FAILED`; and
- acknowledgement/update timestamps used for optimistic concurrency.

The table has no thread, draft, subscription, recipient, body, subject, digest,
or attachment content. Provider message IDs and operation IDs are unique. Physical
triggers enforce valid insert/transition shapes and cap the table at 128 rows.
At the cap, acknowledgement fails closed and leaves the active unknown operation
locked rather than discarding callback authority.

### Acknowledgement is one atomic ownership transfer

For `SUBMISSION_UNKNOWN`, one Room transaction inserts an `AWAITING_CALLBACK`
receipt and deletes the exact active operation under its revision. Any insert,
capacity, compare-and-swap, or delete failure rolls back the whole transaction.
The draft is untouched. `KNOWN_UNSENT` acknowledgement still removes only the
active operation because there is no possible successful late callback to own.

The active operation observation becomes empty after commit, so the existing UI
reopens the preserved draft. Receipts are deliberately not queried by Thread and
therefore cannot lock a new explicit send.

### Exact late callbacks checkpoint proof before provider mutation

Composer callback routing checks the active operation first and then the receipt
with the same operation ID. A callback with malformed or mismatched binding is
consumed without mutation. An exact late successful or failed SENT callback first
transitions `AWAITING_CALLBACK` to durable `SENT` or `FAILED` proof.

Only after that checkpoint does the coordinator conditionally set the exact
provider row to `COMPLETE` or `FAILED`. `APPLIED`, `ROW_ABSENT`, and
`OWNERSHIP_CONFLICT` are terminal dispositions; access/storage failure retains the
receipt. After a terminal disposition, the exact proof row is removed. No receipt
path invokes transport, clears a draft, publishes sent-completion clearance, or
changes another provider row.

### Recovery is non-sending and process-safe

Startup recovery reads active operations and acknowledged receipts before making
the composer ready. `AWAITING_CALLBACK` remains passive. `SENT` and `FAILED` proof
retries only exact provider reconciliation and receipt removal. Unreadable receipt
storage blocks readiness; provider unavailability defers only that receipt and
does not brick unrelated Threads.

## Consequences

- The Phase 5A manual-unknown late-provider cleanup residual is closed locally.
- “Keep as draft” still warns about duplicate risk and still requires a separate
  explicit tap for any later send.
- A late callback can repair its old provider row across process death without
  changing or clearing the preserved draft.
- Repeated callbacks are idempotent, and the first durable exact callback proof
  wins if contradictory callback input is ever observed.
- Schema 5 migrates explicitly to schema 6 without modifying existing operation,
  draft, or appearance rows.
- This closes no physical SIM, carrier, billing, roaming, OEM, or live process-
  death acceptance gate. AuroraSMS is still incomplete and not gold.

## Rejected alternatives

### Keep the acknowledged operation in the active table

Rejected because the table enforces one active operation per Thread. Retaining it
would keep the composer locked or require weakening active-operation invariants.

### Retain callback authority only in memory

Rejected because process death between callback receipt and provider mutation
would recreate the same unreconciled-row gap.

### Reconcile from callback fields without a durable receipt

Rejected because explicit origin and numeric operation range are routing hints,
not ownership authority. Provider mutation requires exact durable ownership.

### Clear the draft after a late successful callback

Rejected because “Keep as draft” explicitly transferred the text back to the user.
The receipt deliberately carries no draft identity and has no clearance authority.
