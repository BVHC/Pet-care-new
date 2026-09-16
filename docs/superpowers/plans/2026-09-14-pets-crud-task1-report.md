# Task 1 Report — Migration audit cho `pets` (V4)

## Đã làm gì
- Tạo `BE/src/main/resources/db/migration/V4__pets_audit_columns.sql`: 1 câu `ALTER TABLE pets`
  bổ sung 5 cột (`updated_at`, `created_by`, `updated_by`, `deleted_at`, `version`),
  verbatim theo plan, kiểu khớp `accounts`/`users` ở V1, FK `created_by`/`updated_by → accounts(id)`.
- Không sửa `V1__init_schema.sql`. Không tạo subagent, không mở rộng scope.
- Skills: `ponytail` full (shortest diff, reuse pattern accounts) + `spring-boot` (Flyway versioned migration).

## Verify thế nào
- Đọc lại file sau khi tạo: đúng 7 dòng, đúng nội dung plan.
- Static check bằng script: 5× `ADD COLUMN`, kiểu `TIMESTAMPTZ`/`UUID REFERENCES accounts(id)`/`BIGINT`
  khớp V1 `accounts` (dòng 72–77), `ALTER TABLE pets` đúng bảng.
- Đối chiếu `BaseEntity.java`: đủ 5 field (`updatedAt/createdBy/updatedBy/deletedAt/version`).
- Không chạy migrate live (không có Postgres/psql khả dụng trong môi trường này).

## Concerns
- Migrate live chưa chạy ở Task 1 — dồn verify vào IT Task 6 (context load = Flyway migrate pass), đúng như plan cho phép.
