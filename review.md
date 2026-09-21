# Java Code Review

## Scope

Module reviewed: **Caregiver Delegation** (`BE/src/main/java/com/petcare/module/pet/**`, caregiver-related files), user-requested rather than derived from `git diff`. Files read in full:

- `entity/PetCaregiverDelegation.java`
- `fsm/CaregiverDelegationTransitionHandler.java`
- `service/CaregiverDelegationService.java` (interface), `CaregiverDelegationServiceImpl.java`, `CaregiverInvitationOutcome.java`, `PetAccessGuard.java`
- `controller/CaregiverDelegationController.java`
- `repository/PetCaregiverDelegationRepository.java`, `PetRepository.java` (`findAccessibleBy`)
- `mapper/CaregiverDelegationMapper.java`
- `dto/InviteCaregiverRequest.java`, `RevokeCaregiverRequest.java`, `CaregiverInvitationResponse.java`, `CaregiverDelegationResponse.java`
- `exception/CaregiverInvitationConflictException.java`, `UnauthorizedDelegatedActionException.java`
- `job/CaregiverExpiryJob.java`, `CaregiverExpiryService.java`
- `platform/enums/CaregiverStatus.java`
- `db/migration/V5__caregiver_delegations.sql`
- Tests: `CaregiverDelegationServiceImplTest`, `CaregiverDelegationTransitionHandlerTest`, `CaregiverDelegationControllerTest`, `CaregiverExceptionMappingTest`, `CaregiverExpiryServiceTest`, `CaregiverFlowIT`

Cross-checked against `docs/03-state-machines.md` §3 (FSM-3), `docs/02-business-rules.md` RULE-04-01..11, and `docs/api/customer-pet-v1.md` §C3 (contract).

**Review date:** 2026-09-20

**Validation performed:**
- `mvn -o test -Dtest=CaregiverDelegationServiceImplTest,CaregiverDelegationTransitionHandlerTest,CaregiverExpiryServiceTest,CaregiverDelegationControllerTest,CaregiverExceptionMappingTest` → **54/54 passing**, `BUILD SUCCESS`.
- `node docs/api/check-contracts.mjs` → `ALL CONTRACTS PASS` (`customer-pet-v1.yaml`: 8 paths, 10 operations, refs resolve).
- `CaregiverFlowIT` (Testcontainers/Postgres) could **not** be run — Docker daemon is not running in this environment (`docker info` failed). Read the test manually instead; it exercises the full invite → accept → view → revoke → 403 flow and the "revoke a pending invitation blocks accept" (D-04) flow, and both look correct against the service code.
- Traced FSM edges in `CaregiverDelegationTransitionHandler.allowedTransitions()` against the mermaid diagram in `docs/03-state-machines.md:93-102` — exact match (`INVITED→{ACTIVE,REJECTED,EXPIRED,REVOKED}`, `ACTIVE→{REVOKED,EXPIRED}`), and the parameterized FSM test enumerates all 6 valid and 19 invalid pairs — full coverage per project convention.
- Traced the RULE-04-01..09 guard citations in the service against `docs/02-business-rules.md` — all RULE-IDs cited match their rule text.

## Summary

This module is in good shape — **no Blocker or High findings**. The implementation is careful and unusually well self-documented: FSM edges match the spec exactly, the token-hash pattern mirrors the existing `RefreshTokenService` precedent, idempotency (D-10) and the "revoke a pending invite" edge (D-04) are both implemented and tested, the duplicate-guard-logic risk between `PetAccessGuard` and `PetRepository.findAccessibleBy` is flagged in comments on both sides, and the DB uniqueness constraint (`uq_pcd_outstanding`) correctly closes the check-then-insert race instead of relying on an application-level check. Exception subclassing follows the project's documented criteria (HTTP status / errorCode divergence) with justification comments citing the exact convention doc section.

Findings below are all **Medium/Low** — mostly missing edge-case tests and a couple of behaviors worth a second look, not defects that block shipping.

**Finding counts:** Medium: 2, Low: 3.

## Findings

### [Medium] `RevokeCaregiverRequest` has no `@Future`/expiry check against a caregiver who never had a live row
- **Location:** `BE/src/main/java/com/petcare/module/pet/service/CaregiverDelegationServiceImpl.java:172-187`
- **Category:** Improvement (test/spec gap)
- **Confidence:** Medium

**Problem**
`revokeCaregiver` looks up the latest row in `{INVITED, ACTIVE, REVOKED}` for `(petId, caregiverEmail)` and returns 200-idempotent if it's already `REVOKED`. But there is no equivalent idempotency/lookup path that also considers a delegation the job already moved to `EXPIRED` or `REJECTED` — those are excluded from the `statusIn` list, so calling `RevokeCaregiver` for an already-expired/rejected invitation correctly 404s (`ResourceNotFoundException`) rather than silently no-opping. This matches RULE-04-08's intent ("revoke applies to `INVITED`"), but it's worth confirming with product/API-contract owners that a `404` (not `409`/`200`) is the desired response for "tried to revoke something that's already terminal via a different path" — the contract doc (`docs/api/customer-pet-v1.md:58`) only documents the `ACTIVE`/idempotent-`REVOKED` cases, not this one. No test exercises the `EXPIRED`/`REJECTED` case.

**Suggested fix**
Add a unit test `revokeCaregiver_alreadyExpired_notFound` (or `_alreadyRejected_notFound`) asserting the current 404 behavior so it's a locked-in contract rather than an untested side effect. If product intends a different status code for that case, encode it explicitly rather than relying on the query's `statusIn` list omission.

---

### [Medium] `InviteCaregiverRequest` allows a Primary Owner to invite themself
- **Location:** `BE/src/main/java/com/petcare/module/pet/service/CaregiverDelegationServiceImpl.java:75-117`, `BE/src/main/java/com/petcare/module/pet/dto/InviteCaregiverRequest.java`
- **Category:** Bug (edge case) / test gap
- **Confidence:** Low

**Problem**
Nothing in `inviteCaregiver` rejects `req.caregiverEmail()` equal to the owner's own account email. `accessGuard.requirePrimaryOwner` only checks that the caller owns the pet, not that the invitee differs from the caller. Self-invite would create a `PetCaregiverDelegation` row where `primaryOwnerId == caregiverUserId` after acceptance, which is semantically meaningless (the owner already has full rights) and isn't covered by RULE-04-04..09 or by any test. It's unlikely to cause a crash, but `PetAccessGuard.requireCanViewPet` short-circuits on `pet.getOwnerId().equals(userId)` before ever consulting the delegation table, so the self-delegation would just sit inert — low real-world impact, but it's unvalidated input reaching persistence.

**Suggested fix**
If self-invitation is intentionally out of scope, add a guard in `inviteCaregiver` (e.g. compare `req.caregiverEmail()` against the owner's account email via `authService`) that throws a `BusinessRuleViolationException` citing RULE-04-04, plus a unit test. If it's considered harmless/acceptable, note that decision in the class-level Javadoc the same way other edge cases (D-02, D-04, D-08, D-10) are documented, so it reads as a decision rather than an oversight.

---

### [Low] `writeOutbox` payload is hand-built JSON string, not serialized
- **Location:** `BE/src/main/java/com/petcare/module/pet/service/CaregiverDelegationServiceImpl.java:189-196`, `BE/src/main/java/com/petcare/module/pet/job/CaregiverExpiryService.java:42-54`
- **Category:** Improvement
- **Confidence:** Low

**Problem**
Both call sites build the outbox event payload via string concatenation: `"{\"petId\":\"" + petId + "\",\"delegationId\":\"" + delegationId + "\"}"`. Since `petId`/`delegationId` are always `UUID.toString()` values (no user-controlled characters, no quotes/backslashes possible), this isn't currently exploitable as injection, and it matches an existing pattern elsewhere in the codebase (not introduced by this module). Still, it's duplicated in two places with no shared helper, so a future field addition (e.g. a caregiver email, which *can* contain arbitrary characters submitted by the user, if ever added to the payload) would need to remember to escape/serialize properly at both sites.

**Suggested fix**
Not blocking; only worth doing if this pattern is extended to carry more fields, in which case switch to `ObjectMapper` and factor the payload construction into one shared method (e.g. on `OutboxEvent` or a small builder) used by both `CaregiverDelegationServiceImpl` and `CaregiverExpiryService`.

---

### [Low] No test for `AcceptCaregiverInvitation` racing an in-flight `RevokeCaregiver` at the DB level
- **Location:** `BE/src/main/java/com/petcare/module/pet/service/CaregiverDelegationServiceImpl.java:119-163`
- **Category:** Improvement (test gap)
- **Confidence:** Low

**Problem**
`respondToInvitation` and `revokeCaregiver` both read-then-write the same `PetCaregiverDelegation` row without any optimistic-lock retry handling visible at the call site, relying purely on `BaseEntity`'s `@Version` column to throw `ObjectOptimisticLockingFailureException` on a genuine concurrent write. That failure isn't mapped to any of the 5 base exceptions in `GlobalExceptionHandler` for this module specifically (there's a general `ConcurrencyConflictException` in the platform, but nothing here traps a raw Hibernate `OptimisticLockException`/`ObjectOptimisticLockingFailureException` and rewraps it), so a true concurrent accept-vs-revoke race would currently surface as an unmapped 500 rather than a clean `409`. This is a narrow window (two requests hitting the exact same row within the same transaction commit gap) and is a pre-existing platform-wide pattern, not unique to this module, so it's Low rather than Medium.

**Suggested fix**
Confirm whether `GlobalExceptionHandler` has a catch-all mapping for `ObjectOptimisticLockingFailureException` → `ConcurrencyConflictException` (409) somewhere platform-wide; if not, that's a cross-module concern worth its own ticket rather than a caregiver-specific fix. If it does exist, add one integration-style test proving the 409 path for this module specifically, since RULE-04-08 promises "immediate effect" and a silent 500 on a race would violate that promise from the caller's perspective.

---

### [Low] `CaregiverExpiryService.expire()` calls `transitions.validateTransition` per row inside the loop, which will hard-fail the whole batch on one bad row
- **Location:** `BE/src/main/java/com/petcare/module/pet/job/CaregiverExpiryService.java:42-54`
- **Category:** Improvement
- **Confidence:** Medium

**Problem**
`expire()` iterates rows returned by `findExpiredInvitations`/`findExpiredDelegations` (which already filter by `status = INVITED`/`ACTIVE`, so in practice `validateTransition(status, EXPIRED)` should always succeed) and calls `transitions.validateTransition(...)` before mutating each row, inside a single `@Transactional` method with no per-row try/catch. If a future change to the queries (or a data anomaly) ever returns a row not in the expected status, `validateTransition` throws `InvalidStateTransitionException`, which propagates and rolls back the *entire* scheduled batch — including any other rows in the batch that were legitimately expirable. Given the queries are tightly scoped (`WHERE status = ...`), this is unlikely to trigger today, but it means one bad row silently blocks the whole cron tick's cleanup with no partial progress and, depending on how `@Scheduled` exceptions are surfaced, may not page anyone.

**Suggested fix**
Not urgent given the current query scoping, but worth a defensive per-row `try { ... } catch (InvalidStateTransitionException e) { log.warn(...) }` inside the loop (skip and continue) so one anomalous row can't block the rest of the batch — mirrors how `OtpExpiryJob` handles similar cleanup, per the file's own reference comment to that job.

## Positive observations

- FSM edges exactly mirror the mermaid spec, and the parameterized test enumerates every (from, to) pair, valid and invalid — matches `FsmTransitionTestBase` convention with zero omissions.
- Idempotency (spec D-10) is implemented consistently for both `accept`/`reject` and `revoke`, with explicit tests for "already at target state, same actor → 200, no duplicate event" and covered on both service and IT levels.
- Token handling follows the `RefreshTokenService` precedent precisely: only SHA-256 hash persisted, raw token returned exactly once and only when the invitee has no existing account (D-02), verified by `inviteCaregiver_storesHashNotRawToken`.
- The check-then-insert duplicate-invite race is closed at the DB layer via the partial unique index `uq_pcd_outstanding` rather than an application-level check, and the service correctly catches `DataIntegrityViolationException` and rewraps it as a typed 409 (`CaregiverInvitationConflictException`).
- The two places that duplicate "is this delegation currently valid" logic (`PetAccessGuard.requireCanViewPet` and `PetRepository.findAccessibleBy`) both carry explicit, mutually-referencing comments warning that the condition must be kept in sync — a good defense against silent drift, and a real historical bug (documented inline) about Hibernate's `NAMED_ENUM` cast syntax was captured as a comment to prevent regression.
- `RevokeCaregiver` correctly covers both the `ACTIVE→REVOKED` and `INVITED→REVOKED` edges (spec D-04), each with its own unit + IT test.
- Business-guard-before-transition-validation ordering (per `CLAUDE.md`: RULE-ID checks before `validateTransition()`) is followed correctly in `respondToInvitation`.
- Exception subclassing (`CaregiverInvitationConflictException`, `UnauthorizedDelegatedActionException`) both cite the specific criterion from `docs/convention/backend/04-exception-handling.md §4.2` that justifies a subclass rather than reusing the base exception — makes future review of "should this be a new exception class" trivial.
- API contract (`docs/api/customer-pet-v1.md` §C3) and OpenAPI yaml are consistent with the actual controller paths/status codes; `check-contracts.mjs` passes.

## Validation

- `mvn -o test -Dtest=CaregiverDelegationServiceImplTest,CaregiverDelegationTransitionHandlerTest,CaregiverExpiryServiceTest,CaregiverDelegationControllerTest,CaregiverExceptionMappingTest` — **54 tests, 0 failures, 0 errors**, `BUILD SUCCESS`.
- `node docs/api/check-contracts.mjs` — `ALL CONTRACTS PASS` for all 25 module specs including `customer-pet-v1.yaml`.
- `CaregiverFlowIT` — not run (Docker daemon unavailable in this environment: `docker info` returned an error). Reviewed the test source manually instead; the two scenarios (full accept/revoke lifecycle, and revoke-blocks-accept for a pending invitation) align with the service implementation's actual behavior as traced through the code.
- Did not run `mvn verify` (full Failsafe IT suite) for the same Docker-availability reason.
