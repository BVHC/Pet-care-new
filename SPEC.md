# BE-2 API specification

Implement the work that can be verified for T135–T138 on top of local T127. Follow payment-v1, returns-v1, shop-v1, ADR-0137 and ADR-0138.

## Acceptance criteria

| Slice | Observable behavior | Command from the root |
| --- | --- | --- |
| L1 · T136 create return | Receptionist creates a `PENDING` request with selected goods from a paid Order in their branch and queues the seeded manager notice. Check paid/delivered dates, non-returnable goods, invalid IDs/quantities and concurrent over-return. | `bash verify.sh ReturnRequestCreateIT` |
| L2 · T137 approve | Manager approves within branch, records audit, restores requested stock to original lots only when selected and records `SALE_RETURN`. Create the appropriate refund for a positive amount. Complete zero-amount returns without a refund row. | `bash verify.sh ReturnRequestApproveIT` |
| L3 · T137 reject | Manager rejects a pending request with a reason, audit and a notice to the requester without touching stock or refunds. Concurrent or repeated decisions return a conflict. | `bash verify.sh ReturnRequestRejectIT` |
| L4 · T137 confirm | Receptionist with their own open shift confirms a cash/transfer refund once and records audit. Cash refunds reduce expected cash and transfer refunds use the transfer bucket. Verify manager/manual confirmation with a seeded failed gateway refund. | `bash verify.sh RefundConfirmIT` |
| L5 · T135 expire | Expire eligible transactions only after querying the gateway. Leave the Order payable through its own deadline. Apply successes through the common T127 path and preserve retry on transport errors. | `bash verify.sh PaymentExpiryIT` |
| L6 · T135 reconcile | Run daily after 02:00 Vietnam time for the previous business date. Record one run and classify `MISSING_LOCAL`, `MISSING_GATEWAY`, `AMOUNT_DIFF`, and `STATUS_DIFF` without changing payment or Order state. A failed provider read must never leave a successful or partial run and can be retried. | `bash verify.sh PaymentReconciliationJobIT` |
| L7–L9 · T135 reconciliation APIs | A super manager lists runs, lists items and marks an item resolved with a note. Repeated resolution returns 409 and retains the original note and actor. Exercise authentication, role, invalid input and missing rows. | `bash verify.sh PaymentReconciliationListIT,PaymentReconciliationItemsIT,PaymentReconciliationResolveIT` |
| L10 · T138 ST21 | Process eligible gateway refunds against the original payment, using the stable refund code across attempts. Verify success, temporary retries, final failure and duplicate-worker behavior. | `bash verify.sh GatewayRefundJobIT` |
| L11 · T138 cancel paid online Order | Allowed cancellation produces one full gateway refund and passes the existing cancellation/state rules. Test cancellation after delivery failure wherever supported by the unchanged parity checks. | `bash verify.sh PaidOnlineOrderCancelIT` |
| L12 · T138 return FSM | Verify creation, rejection, approval with and without a refund, cashier confirmation, gateway success/failure, an invalid edge, and rollback. The separate request-withdrawal and manual retry routes are outside this batch. | `bash verify.sh ReturnRefundFsmIT` |

Each command must exit zero and run the named tests. The verification script also checks contract consistency, existing contract wiring and API parity. Phase C records actual local HTTP requests for success, bad input, and denied access with a disposable or test database. Live VNPay verification requires sandbox access. Reconciliation also needs a supported report source. Live refunds need a verified original payment and support for the requested refund type.

On this Windows host use `& 'C:/Program Files/Git/bin/bash.exe' ./verify.sh <classes>` from PowerShell. The system Bash launcher targets WSL.

For a disposable local environment using the fake adapter, enable the ST21 schedule at launch with `--app.jobs.gateway-refund.cron="0 */5 * * * *"`. The gateway worker leaves real provider handling gated pending sandbox verification.

## Scope and constraints

Write workflow files in the repo root, Java backend code under the assigned module paths, new tests, and a new Flyway migration if needed. In each brief narrow the write scope to named files. Preserve existing tests and migrations, dependency manifests, frontend, `app/auth/`, `app/payments/`, `app/core/config.py`, `.env*`, `.github/`, and all Dockerfiles. Preserve existing local edits. No worktree, push, merge, deployment, or skipped commit hooks.

Additional returns read APIs, new payment providers, live account setup, and the remainder of UC63 are outside this batch.

Phase C may correct a checked-in API contract where a required behavior changes the response. The cashier shift contract uses signed totals after refunds. Review regressions must pass through `verify.sh` alongside the existing checks.
