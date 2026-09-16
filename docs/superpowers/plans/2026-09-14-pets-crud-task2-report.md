# Task 2 Report — `Pet` entity + repository (TDD slice)

- **Status:** DONE (xanh trên Postgres thật via Testcontainers, không H2)
- **Commit:** `fa9b112 feat(pet): add Pet entity and repository` (không push)
- **Files:**
  - `BE/src/main/java/com/petcare/module/pet/entity/Pet.java`
  - `BE/src/main/java/com/petcare/module/pet/repository/PetRepository.java`
  - `BE/src/test/java/com/petcare/module/pet/repository/PetRepositoryTest.java`

## TDD đỏ → xanh

1. **RED:** viết `PetRepositoryTest` trước, chạy `mvn test -Dtest=PetRepositoryTest` →
   FAIL biên dịch (`package com.petcare.module.pet.entity does not exist`), đúng plan Step 2.
2. **GREEN:** viết `Pet` + `PetRepository` tối thiểu theo plan Step 3 → PASS
   (`Tests run: 1, Failures: 0, Errors: 0`), BUILD SUCCESS.

## Sai khác so với plan (có chủ đích, tối thiểu)

1. **Test tạo owner thật thay vì `UUID.randomUUID()`:** `pets.owner_id` có FK →
   `users(id)` (`V1__init_schema.sql:292`) nên save với owner ngẫu nhiên dính FK
   violation. Test save `Account` → `User` trước rồi mới save `Pet`
   (dùng `AccountRepository`/`UserRepository` sẵn có trong slice, không code mới).
2. **Repository thêm `Page<Pet> findByOwnerId(UUID, Pageable)`** (yêu cầu verbatim
   của Task 2 — cần cho Task 4 `list`; plan Step 3 chỉ liệt kê bản `List`).
   Test assert cả hai bản.
3. **Wiring slice:** `@DataJpaTest + Replace.NONE + @ServiceConnection postgres:17`
   (copy `AuthFlowIT`), cộng `@TestPropertySource(flyway.enabled=true,
   ddl-auto=validate)` để dùng schema Flyway thật V1..V4 (có `pet_status_enum`),
   cộng `@Import(JpaAuditingConfig.class)` vì slice loại `@Configuration` nên
   `BaseEntity.createdAt/updatedAt NOT NULL` không được fill nếu thiếu.

## Test summary

- `PetRepositoryTest.saveAndFindByOwnerId` — PASS, real Postgres (Testcontainers).
- Compile main+test: PASS (bao trong test run).

## Concerns / lưu ý môi trường

- **Docker daemon ban đầu chưa chạy** → đã khởi động Docker Desktop, pull
  `postgres:17` lần đầu (~1 phút).
- **Quirk timezone (ngoài scope Task 2, ảnh hưởng mọi Testcontainers test):**
  JVM trên máy này default TZ `Asia/Saigon`, pgjdbc gửi làm session `TimeZone`
  và `postgres:17` từ chối (`FATAL: invalid value for parameter "TimeZone"`).
  Chạy test với `-DargLine="-Duser.timezone=UTC"` thì xanh. Không sửa production
  code vì đây là vấn đề môi trường chung (các IT cũ như `AuthFlowIT` dùng cùng
  wiring sẽ gặp y hệt). Đề xuất Task 6/document chung xử lý (vd.统一 surefire
  `argLine` hoặc nâng image có tzdata đủ) thay vì vá từng test.
- Không thêm scope (không transfer/search/status-transition). Không subagent khác.
