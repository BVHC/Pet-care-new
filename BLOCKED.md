# Blocked acceptance criteria

| Slice | Evidence and dependency |
| --- | --- |
| L2 · T137 approval and stock | ADR-0138 R1 requires `SALE_RETURN` and `return_requests` for movements. `SchemaMigrationIT` pins the exact Flyway versions and the exact movement and source enums. The add-only migration required by the ADR would fail those unmodified tests. |
| L5 · T135 ST11 | The verified-success transaction shared with callback is still absent from T127. The current wiring integration test explicitly requires the existing `OnlineOrderApi.markPaid` placeholder to throw. Expiring a transaction when the gateway reports success is unsafe. |
| L6 · T135 ST14 live report | `VnpayGatewayClient.fetchSettlementReport` throws `UnsupportedOperationException`. The local reconciliation flow may use the fake adapter. Live verification needs an approved report source and sandbox access. |
| L10 · T138 ST21 live refunds | A confirmed sandbox account is absent from the available evidence. The current VNPay adapter sends a full-refund transaction type, while returns can refund a partial amount. Verify the provider semantics before accepting the live path. |
| L11 · T138 paid cancellation | Requires a successful T127 payment and stock restoration on the approved ledger path. The shop contract gate pins 237 documented operations, including the existing shop module, so adding `cancel-failed` conflicts with its unchanged count. |
| L12 · T138 complete FSM | Approval and post-approval edges cannot all be exercised by invoking the public API while L2 is blocked. |
