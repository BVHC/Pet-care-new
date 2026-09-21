# Fix F1 Report — Pets `update()`/`create()` flush-time exception mapping

## Status: DONE

## Commit
- `fix(pet): map flush-time optimistic-lock/DIV in update/create (F1)` (a6dfb79, không push)

## Finding
- Final review F1 (`2026-09-14-pets-crud-final-review.md`): `update()` catch `ObjectOptimisticLockingFailureException` quanh `pets.save()` nhưng `save()` trong cùng persistence context thường chỉ flush/commit sau khi method return → exception ở commit-time thoát khỏi `try` → 500 thay vì `ConcurrencyConflictException` (409). Cùng method thiếu catch `DataIntegrityViolationException` như `create()`. `create()` cùng lỗi tiềm ẩn với DIV (constraint phát sinh ở flush).

## Fix (ponytail: 1 cách duy nhất, không refactor lớn)
- `PetServiceImpl.java`:
  - `create()`: `save` → `saveAndFlush` trong `try` hiện có (DIV → RULE-04-01 giữ nguyên).
  - `update()`: `save` → `saveAndFlush` + thêm `catch DataIntegrityViolationException → BusinessRuleViolationException("RULE-04-01")` bên cạnh catch OOLFE → `ConcurrencyConflictException("Pet", id)` hiện có.
- Không đụng F2–F5, AuthFlowIT, yaml.

## TDD evidence
- RED: thêm `update_optimisticLockAtFlush_mapsTo409` (mock `saveAndFlush` ném `ObjectOptimisticLockingFailureException` → expect `ConcurrencyConflictException`) → FAIL đúng kỳ vọng `Expecting code to raise a throwable` (impl cũ gọi `save` nên mock không kích hoạt).
- GREEN: sau fix + cập nhật stub `create_...` từ `save` → `saveAndFlush` (impl đổi contract mock, bắt buộc).
- `mvn test "-Dtest=PetServiceImplTest"` từ `BE/` → **Tests run: 4, Failures: 0, Errors: 0 — BUILD SUCCESS**.

## Scope guard
- `git status`: chỉ 2 file đổi (`PetServiceImpl.java`, `PetServiceImplTest.java`). Không chạm `AuthFlowIT`, openapi yaml, F2–F5.
