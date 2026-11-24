# BE-2 implementation plan

Use one checkout and a separate `codex/be2-<slice>` branch for each API or job. Start each branch from the latest verified dependency tip. Verify before every commit. Exact file allowlists are supplied in implementation briefs.

| Slice | Branch suffix | Files / pattern | Dependency | Status |
| --- | --- | --- | --- | --- |
| L1 | return-create | `returns/{controller,dto,repository,service}`, `sales/api` and its Order lock/read implementation, new `ReturnRequestCreateIT`. Follow shop's place-order transaction and workforce controller validation. | T127 baseline; V46 schema | Verified |
| L2 | return-approve | Returns decisions/refund persistence, inventory ledger path, new Flyway migration, new `ReturnRequestApproveIT`. | L1 and schema compatibility | Blocked |
| L3 | return-reject | Returns decision path, no ledger/refund mutation; new `ReturnRequestRejectIT`. | L1 | Verified |
| L4 | refund-confirm | Returns controller/service/repository, sales shift lock and cashier totals, new `RefundConfirmIT`. Follow cashier shift ownership and transactional collection. | L1 for shared code; fixture refund rows while L2 is blocked | Verified with fixtures |
| L5 | payment-expire | `payment/{service,repository,job}`, new `PaymentExpiryIT`. Follow `OnlineOrderPaymentExpiryService` locking and scheduling. | Common UC63 outcome service | Blocked |
| L6 | payment-reconcile-job | `payment/{repository,service,job}`, new `PaymentReconciliationJobIT`. Follow existing job scheduling/transaction style. | V45, fake report client; real source for sandbox acceptance | Verified locally |
| L7 | payment-reconcile-list | Payment controller/DTO/read query, new `PaymentReconciliationListIT`. | L6 | Verified locally |
| L8 | payment-reconcile-items | Payment controller/DTO/read query, new `PaymentReconciliationItemsIT`. | L6, shared L7 controller | Verified locally |
| L9 | payment-reconcile-resolve | Payment controller/DTO/write service, new `PaymentReconciliationResolveIT`. | L6, shared L7 controller | Verified locally |
| L10 | gateway-refund-job | Returns worker/service/repository and existing payment gateway interface, new `GatewayRefundJobIT`. | L2 and T127 for reachable flow; scripted adapter and fixture rows for worker tests | Verified locally; sandbox blocked |
| L11 | paid-online-cancel | Existing shop cancellation routes/services and returns refund creation, new `PaidOnlineOrderCancelIT`. | T127, schema compatibility and immutable shop parity | Blocked |
| L12 | return-refund-fsm | New `ReturnRefundFsmIT` using PostgreSQL and fixed clock. | L1–L4, L10 for documented edges | In progress for reachable edges; full coverage blocked by L2 |

Run each slice command from SPEC.md, check tracked and untracked paths against the brief and inspect the diff for audit, transaction boundaries and branch permissions. Record command output in PROGRESS.md. If a slice remains blocked, record the exact failing dependency and continue with independent slices. Phase C runs local HTTP probes against a disposable database and an independent review.
