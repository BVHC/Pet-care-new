# Task 6 Report — PetFlowIT + verify

## Status: DONE (test riêng PASS; full suite còn lỗi pre-existing ngoài scope)

## Commits
- `test(pet): add PetFlowIT owner-guard E2E` (3aa42ae, không push)

## Files
- `BE/src/test/java/com/petcare/module/pet/PetFlowIT.java` (146 dòng) — `@SpringBootTest` +
  `@AutoConfigureMockMvc` + Testcontainers Postgres `@ServiceConnection` (copy khung `AuthFlowIT`:
  profile `test`, `flyway.enabled=true` + `ddl-auto=validate`, `@MockitoBean EmailGateway`,
  register → đọc OTP → verify → login lấy JWT thật, gọi API qua `Authorization: Bearer`).
  Một `@Test` duy nhất `ownerCrudAndCrossOwnerForbidden`: POST 201 (`ownerId == me`) → GET list
  thấy đúng 1 pet → GET detail 200 → PATCH đổi name 200 → user B GET/PATCH pet của A đều 403.
  Không field ngày sinh nên miễn nhiễm quirk TZ Asia/Saigon.

## Test summary
- `mvn verify "-Dtest=PetFlowIT" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=PetFlowIT" "-DfailIfNoTests=false" "-Duser.timezone=UTC"` (từ `BE/`) →
  **Tests run: 1, Failures: 0, Errors: 0 — BUILD SUCCESS** (~52s, gồm Flyway V1→V4 migrate thật).
- Full `mvn verify` không lọc (chạy 1 lần để lấy bằng chứng): surefire 83 tests / 9 errors —
  toàn bộ là `Failed to load ApplicationContext` ở `PetcareApplicationTests` (1),
  `JwtAuthenticationFilterTest` (5), `TraceIdCorsTest` (3); root cause trong surefire report:
  `FATAL: password authentication failed for user "postgres"` (localhost:5432/pass local khác
  `application-test.yml`). Pre-existing, không liên quan pet — không sửa.
- `AuthFlowIT` chạy riêng: **5/5 ERROR** cùng root cause (xem Concerns) — pre-existing, file
  không thuộc scope pet, không sửa.

## Deviations khỏi plan (có lý do)
1. Một `@Test` flow duy nhất thay vì "mỗi assert 1 test": share state qua nhiều test cần
   `@Order` + static field, giòn; single-method giữ đúng thứ tự register→CRUD→403 theo yêu cầu verbatim.
2. Thêm `@Transactional` trên test method (`PetFlowIT.java:95`): `OtpRepository.findTop…` mang
   `@Lock(PESSIMISTIC_WRITE)` nên gọi ngoài tx ném `TransactionRequiredException`; MockMvc cùng
   thread join tx này nên behavior HTTP không đổi. Đây là fix tối thiểu trong scope Task 6 (Step 3).
3. Flag `-Dtest/-Dsurefire.failIfNoSpecifiedTests=false/-DfailIfNoTests=false` phải quote trong
   PowerShell (arg `-D…` không quote bị Maven parse thành lifecycle phase lạ) và `-Dtest=…` để
   surefire bỏ qua 9 test hỏng pre-existing khi chạy targeted.
4. Không sửa `pom.xml` (không heap-fork quirk nào gặp; heap surefire trong ledger không tái hiện).

## Concerns
- **AuthFlowIT đỏ 5/5 (pre-existing, nghiêm trọng hơn)**: cùng lỗi `Query requires transaction
  be in progress` tại mọi lần đọc OTP trực tiếp từ test (dòng 93/122/146/182). `@Lock(PESSIMISTIC_WRITE)`
  được thêm cho plan §5.2 Concurrency nhưng phá vỡ mọi caller ngoài-tx của method này — team auth
  nên fix (VD: `@Transactional` trên IT hoặc service method đọc OTP), Task pet không đụng.
- Redis localhost:6379 không chạy → `TokenBlacklistService` fail-open (đúng ADR-0002), test vẫn xanh
  nhưng log WARN `RedisConnectionFailureException`; IT blacklist thật cần Testcontainers Redis.
- `docs/api/openapi/customer-pet-v1.yaml` (`isActive`) vẫn chưa đồng bộ (kế thừa từ Task 5 report).
