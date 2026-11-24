# Phase C review

An independent reviewer with clean context inspected `9d0ec506..25cc8398`. The review found that the protected paths and existing tests were preserved. Concurrent return claims, decisions, shift close, reconciliation and duplicate workers use row locks or guarded updates. The review identified four issues beyond the initial local fixtures.

| Finding | Risk | Correction |
| --- | --- | --- |
| Refund confirmation authorizes against the refund row alone. | A schema-valid cross-branch link could let staff change another branch's return. | Check matching branch ownership against the linked Order and return or online Order. |
| The fake provider caches temporary refund errors by code. | A retry with the same refund code cannot recover after the simulated provider recovers. | Cache only terminal outcomes and test the real fake adapter. |
| The sales contract constrains expected cash and transfer to nonnegative values. | A valid refund-only shift may produce a negative net total. | Declare signed totals and exercise a refund-only shift. |
| Escaped NUL characters reach PostgreSQL in user note and reason fields. | A storage error replaces the expected validation response. | Reject NUL in DTO validation and cover the affected fields. |
| The ST21 fake worker checks the refund and online Order branches but only checks the linked return's status. | Inconsistent source rows can lead to a provider call or a notice to the wrong branch. | Validate the sales Order and linked return before either effect. Fail an inconsistent refund without a notice or a change to the linked return. |

The follow-up reviewer confirmed the first four corrections. The independent Terra review found no blocker in the ST21 correction and confirmed that it preserves the online Order, refund and return lock order. The focused test reproduced calls to the fake provider for the two mismatched scopes before the fix and passed after it.

The initial review also noted that the generated Swagger descriptions and required-field metadata for return creation differ from the checked-in YAML. The tested return behavior follows the checked-in contract.

See [BLOCKED.md](BLOCKED.md) for acceptance criteria that still require a compatible migration/test baseline, the T127 success flow or a verified VNPay integration.
