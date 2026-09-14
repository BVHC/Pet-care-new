# Task 3 Report — DTO record + MapStruct mapper (TDD unit)

- **Status:** DONE
- **Commit:** `3ce1d28 feat(pet): add pet DTOs and mapper` (không push)
- **Files:**
  - `BE/src/main/java/com/petcare/module/pet/dto/CreatePetRequest.java`
  - `BE/src/main/java/com/petcare/module/pet/dto/UpdatePetRequest.java`
  - `BE/src/main/java/com/petcare/module/pet/dto/PetResponse.java`
  - `BE/src/main/java/com/petcare/module/pet/mapper/PetMapper.java`
  - `BE/src/test/java/com/petcare/module/pet/mapper/PetMapperTest.java`

## TDD đỏ → xanh

1. **RED:** viết `PetMapperTest` trước (đúng plan Step 1, `Mappers.getMapper`), chạy
   `mvn test -Dtest=PetMapperTest` → FAIL biên dịch
   (`package com.petcare.module.pet.dto does not exist`, `cannot find symbol PetMapper`),
   đúng plan Step 2.
2. **GREEN:** viết 3 record + `PetMapper` tối thiểu đúng plan Step 3 → PASS
   (`Tests run: 1, Failures: 0, Errors: 0`), BUILD SUCCESS.

## Sai khác so với plan

- Không có. DTO/mapper verbatim plan Step 3: không `ownerId`/`status` trong request,
  `componentModel="spring"`, chỉ `toResponse`.

## Test summary

- `PetMapperTest.toResponse_mapsIdAndOwner` — PASS (unit, không Spring context).
- Compile main+test: PASS (bao trong test run).

## Concerns

- **Lệnh plan dùng `-pl BE` không chạy được:** repo không có root aggregator pom
  (`BE/pom.xml` là module độc lập) → chạy `mvn test -Dtest=PetMapperTest` từ thư mục `BE/`.
- Không thêm scope. Không subagent khác.
