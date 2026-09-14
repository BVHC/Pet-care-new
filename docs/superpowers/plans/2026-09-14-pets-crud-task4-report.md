# Task 4 Report — PetService (create/read/update + owner-guard)

- Status: DONE (không push, commit riêng `93db745`)
- Commits: `93db745 feat(pet): add PetService with owner guard`

## Files

- `BE/src/main/java/com/petcare/module/pet/service/PetService.java` (create/detail/list/update)
- `BE/src/main/java/com/petcare/module/pet/service/PetServiceImpl.java` (khung AuthServiceImpl)
- `BE/src/test/java/com/petcare/module/pet/service/PetServiceImplTest.java` (3 behavior)

## TDD

- RED: `PetServiceImpl` chưa tồn tại → compilation failure (đúng kỳ vọng plan Step 2).
- GREEN: implement tối thiểu đúng plan Step 3 → PASS.

## Test summary

- `mvn test -Dtest=PetServiceImplTest` (từ `BE/`): **3/3 PASS**.
- Hồi quy unit-only: `PetServiceImplTest + PetMapperTest + AuthServiceImplTest` → **35/35 PASS**.
- 3 behavior: `create_setsOwnerToSelf_andEmitsPetAdded` (owner=self + Outbox `PetAdded` cùng tx);
  `detail_otherOwnersPet_forbidden` (403 cross-owner); `update_nonOwner_forbidden` (403 cross-owner).
- `UpdatePetRequest` không có field status → chọn nhánh plan cho phép: test thứ 3 là
  `update_nonOwner_forbidden`, không test chết. Guard RULE-04-11 giữ ở impl
  (`status != ACTIVE` → `BusinessRuleViolationException RULE-04-11`), phục vụ Task 6 E2E.

## Ghi chú implement

- `PetRepository` đã có `Page<Pet> findByOwnerId(UUID, Pageable)` từ trước → không sửa Task 2.
- Owner-check qua `UserProvisioningService.findById` (404 nếu không tồn tại), không import entity module khác.
- Exception mapping: `DataIntegrityViolationException → BusinessRuleViolationException(RULE-04-01)`,
  `ObjectOptimisticLockingFailureException → ConcurrencyConflictException`, cross-owner → `AccessDeniedScopeException("PET","OWNER")`.

## Concerns

- Máy build yếu RAM (~366MB free lúc chạy): surefire fork mặc định OOM (`paging file too small`).
  Workaround chỉ cho verify cục bộ: `-DargLine="-Xmx128m -Xms16m -XX:MaxMetaspaceSize=96m -XX:+UseSerialGC"`.
  Không đổi `pom.xml`; CI/RAM đủ lớn chạy lệnh gốc của plan là đủ.
- Full suite (`PetRepositoryTest`, `AuthFlowIT`, Task 6 `PetFlowIT`) cần Testcontainers/Docker + RAM —
  chưa chạy ở Task 4; giao Task 6 verify E2E.
- `MAVEN_OPTS` sót trong session từng làm Maven OOM — đã clear; các Task sau chú ý.
