# ADR-0038: Xem nhật ký audit qua API (T15 phần 3)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-14
- **Liên quan:**
  - identity-v1 #36 `GET /audit-logs` (UC11).
  - BR-QT-15 (02 L58), BR-QT-16 (L59); 01 L13 (A03), L46 (UC11); 04 L42 (AuditLog); erd §1 `audit_logs` (L154–170).
  - Convention 02 (repository), 04, 06, 07, 08 §8.2.
  - ADR-0001 (ghi audit), 0003 (xác thực), 0020 (giữ connection / reset trạng thái connection), 0028 (Bean Validation trước `@PreAuthorize`), 0035, 0036, 0037 (khuôn endpoint chỉ ADMIN). Nợ mới D020, D021.
- **Triển khai:**
  - `identity/controller/AuditLogController`.
  - `identity/service/AuditLogQueryService`.
  - `identity/repository/{AuditLogQuery, AuditLogQueryRepository, AuditLogRow}`.
  - `identity/dto/{AuditLogFilter, ValidAuditLogFilter, AuditLogFilterValidator, AuditLogEntryResponse}`; `identity/mapper/AuditLogMapper`.
  - `platform/audit/AuditRecorder.snapshot`: snapshot phải là JSON object.
  - Test:
    - Unit: `AuditLogFilterValidationTest`, `AuditLogQueryTest`, `AuditLogQueryServiceTest`, `AuditLogMapperTest`, `AuditLogControllerSecurityTest`, `AuditRecorderTest` (hai case mới).
    - IT: `module/identity/AuditLogViewIT`.
  - Không migration.

## Bối cảnh

`audit_logs` chỉ có đường ghi: `AuditLogEntity` là `@Immutable`, `AuditLogRepository` chỉ có `save`, trigger chặn UPDATE / DELETE / TRUNCATE (BR-QT-16). Convention 08 §8.2 hẹn màn hình xem (UC11) là repository đọc riêng của module QT.

Contract #36 có 6 bộ lọc (`actorAccountId`, `action`, `entityType`, `entityId`, `from`, `to`), phân trang `page` / `size`, trả `AuditLogEntry` 11 khóa.

Bảng có 3 index: `(entity_type, entity_id)`, `(actor_account_id, created_at)`, `(created_at)`. **Không** có index trên `action`. Bảng chỉ tăng: mỗi lần đăng nhập, mỗi thao tác quản trị, mỗi lần đổi giá đều thêm dòng, và phải giữ ≥ 2 năm.

## Quyết định

1. **Quyền và audit.**
   - Chỉ ADMIN (BR-QT-16 "Chỉ ADMIN được xem"; UC11 actor A03): `@PreAuthorize("hasRole('ADMIN')")` trên mọi handler. Role khác → 403 `ACCESS_DENIED`, **không audit** (BR-QT-01 / 07 chỉ áp cho vượt phân cấp nhân viên; cùng cách với #28, #31, #33).
   - ADMIN thấy **mọi dòng**, kể cả của ADMIN khác và của chính mình. Không qua `BranchScope`: bảng không có `branch_id`.
   - **Xem không ghi audit** (Q5). BR-QT-15 nói về thao tác làm thay đổi; ghi audit cho lần xem thì mỗi lần xem lại đẻ thêm dòng.
   - Chỉ có GET. Method khác trên `/api/audit-logs` → 405; đường dẫn con → 404 (BR-QT-16: không có chức năng sửa / xóa).

2. **Đường đọc thuộc identity, viết bằng NamedParameterJdbcTemplate + SQL builder** — lệch có chủ đích khỏi convention 02 (Repository = `JpaRepository`).
   - `AuditLogQuery` chỉ ghép điều kiện của tham số có giá trị.
   - **Không** dùng `:x IS NULL OR …`: khi PgJDBC chuyển sang server-prepared statement (sau 5 lần), generic plan của Postgres không dùng được index cho điều kiện đó.
   - SELECT đúng 11 cột, không nạp entity; `AuditLogEntity` vẫn chỉ để ghi.
   - IT EXPLAIN đúng chuỗi SQL do builder sinh, có bind tham số.
   - Phương án bị loại:
     - JPA Specification (tiền lệ catalog): nạp cả entity, và muốn EXPLAIN thì phải bắt SQL Hibernate sinh ra.
     - Một câu `@Query` native với `IS NULL OR`: mất index khi dùng generic plan.

3. **Ngữ nghĩa lọc.**
   - So khớp chính xác. Giá trị lạ → trang rỗng, **không bao giờ 404**.
   - Khoảng thời gian **đóng** `[from, to]`. `from` / `to` là ISO 8601 có offset; `+` phải URL-encode thành `%2B`.
   - `action` / `entityType` rỗng hoặc toàn khoảng trắng → coi như không gửi. Spring truyền `""` cho `String` khi URL có `?action=`; `Long` / `OffsetDateTime` rỗng đã tự thành `null`.
   - **R1** `from > to` → 400 (lỗi ở `to`).
   - **R2** `entityId` không kèm `entityType` → 400 (lỗi ở `entityType`). Lý do: id không có nghĩa khi thiếu tên bảng, và index là `(entity_type, entity_id)` — Postgres 17 không có skip scan.
   - **R3** `action` không có mốc chọn lọc — không có `actorAccountId`, cũng không có cặp `entityType + entityId` — thì phải có cả `from` lẫn `to`, cách nhau ≤ 31 ngày (lỗi ở `from`).
     - 31 ngày là hằng kỹ thuật `AuditLogFilter.MAX_ACTION_WINDOW`, **không phải [CFG]**: đặc tả không có số này.
     - Nó chỉ giới hạn số dòng phải quét khi không có index trên `action` (Q6, nợ D021).
   - `page < 0` → 0; `size` kẹp về 1–100 (như #15, #28). Tham số lạ (`sort`…) bị bỏ qua.
   - Sắp cố định `created_at DESC, id DESC`.

4. **Thứ tự kiểm, và R1–R3 không phải rule `BR-…`.**
   - Thứ tự: 401 → BR-TK-17 → hình thức (sai kiểu → `MALFORMED_REQUEST`; `action` / `entityType` > 50 ký tự → `VALIDATION_FAILED`) → 403 → R1–R3 → 200.
   - Lỗi hình thức chạy **trước** `@PreAuthorize` như mọi endpoint (ADR-0028). Không lộ dữ liệu: message là chuỗi cố định.
   - R1–R3 là kiểm tra hình thức, không có trong 02, nên trả `VALIDATION_FAILED` (convention 04), không đặt mã `BR-QT-xx`.
   - Cài đặt: constraint mức lớp `@ValidAuditLogFilter` trên record `AuditLogFilter`, gắn lỗi vào đúng tên tham số để FE hiện dưới ô lọc. Service gọi `Validator` trước mọi câu SQL, ném `ConstraintViolationException`.
     - Chọn cách này vì `@AssertTrue` cho tên node `rangeOrdered`…; còn `@ModelAttribute` để lộ message tiếng Anh khi sai kiểu.
     - Message nối các lỗi theo thứ tự cố định R1 → R2 → R3. Constructor chuẩn của `ConstraintViolationException` chép tập lỗi sang `HashSet` nên làm mất thứ tự; vì vậy service dùng một lớp con private chỉ trả lại đúng tập đã sắp.
   - Phương án bị loại: luật URL `/api/audit-logs/** hasRole('ADMIN')` trong `SecurityConfig` để 403 đứng trước mọi 400. Nó đưa đường dẫn của module vào `platform/` và tạo hai nơi khai báo quyền có thể lệch nhau.

5. **Phân trang OFFSET và đếm chính xác.** Contract `PageResponse` cần `totalElements`, nên mỗi request chạy `count(*)` cùng `WHERE` rồi mới lấy trang. Chi phí khi không lọc / trang sâu → nợ D020.

6. **Index theo tổ hợp, không migration.**

   | Tổ hợp | Câu trang | Câu đếm |
   |---|---|---|
   | Không lọc | `ix_audit_logs_created_at` (quét ngược + incremental sort) | không kiểm (D020) |
   | `from` / `to` | `ix_audit_logs_created_at` | `ix_audit_logs_created_at` |
   | `actorAccountId` (± khoảng) | `ix_audit_logs_actor_created_at` | như câu trang |
   | `entityType` + `entityId` | `ix_audit_logs_entity` | như câu trang |
   | `action` + khoảng ≤ 31 ngày | `ix_audit_logs_created_at` + Filter `action` | như câu trang |
   | chỉ `entityType` | không Seq Scan | không kiểm |

   `AuditLogViewIT.queriesUseExpectedIndex` chứng minh bảng này trên 60 000 dòng chèn trong một transaction rollback. Index trên `action` để sau (nợ D021).

7. **Response = đúng thứ đã ghi.**
   - Không join, không làm giàu dữ liệu: `actorEmail` là email lúc ghi; `actorAccountId` vẫn trả khi tài khoản đã bị xóa (cột không có FK).
   - `beforeData` / `afterData` là text `jsonb` chèn **nguyên văn** (`@JsonRawValue`), không parse lại, nên không mất độ chính xác số.
     - `jsonb` tự sắp lại thứ tự khóa và bỏ khoảng trắng: nghĩa không đổi, FE không được dựa vào thứ tự khóa.
     - Cột NULL → `null`.
   - `AuditRecorder` từ nay **chỉ nhận snapshot là JSON object** (record / Map). Mảng, chuỗi, số → `IllegalArgumentException` ở cả `record` lẫn `recordIndependently`, trước khi lưu. Nhờ vậy contract `object?` đúng cho mọi dòng. 21 chỗ gọi lúc quyết định (identity, catalog) đều truyền record / Map.
   - Không lọc lại khóa bí mật khi đọc: `AuditRecorder` đã chặn lúc ghi. Không có DTO riêng cho khách vì chỉ ADMIN xem.
   - `createdAt` là `Instant` (UTC `Z`, offset hợp lệ theo `00-method` §3), giữ độ chính xác micro giây.
   - Envelope: `ApiResponse.ok(PageResponse)`; `page` / `size` là giá trị sau khi kẹp.
   - MapStruct `AuditLogMapper` với `unmappedTargetPolicy = ERROR`, khai báo đủ 11 `@Mapping`.

8. **Transaction: `@Transactional(readOnly = true, isolation = REPEATABLE_READ)`** trên `listAuditLogs` — lần đầu repo dùng isolation khác mặc định.
   - **Vì sao:** ở READ COMMITTED mỗi câu có snapshot riêng. Một dòng audit commit giữa câu đếm và câu trang làm `totalElements` lệch `content`. Ở REPEATABLE READ hai câu dùng cùng một snapshot. Transaction chỉ đọc nên Postgres không bao giờ trả lỗi serialization.
   - **Không ghi được:** `readOnly` làm PgJDBC mở transaction bằng `BEGIN READ ONLY`; mọi lệnh ghi lỡ chạy trong đó bị Postgres từ chối.
   - **Cơ chế:** `HibernateJpaDialect` (spring-orm 6.2.19) đặt isolation / readOnly lên connection rồi reset khi kết thúc. Điều này chỉ chạy được khi Hibernate giữ connection tới lúc đóng (ON_CLOSE) — đúng với cấu hình ADR-0020 đã chọn để Spring reset trạng thái connection. Hikari cũng tự trả isolation / readOnly về mặc định khi nhận lại connection.
   - **Các bảo đảm có IT:**
     - Hai câu chạy trong cùng một transaction `repeatable read` / read-only trên cùng backend.
     - Dòng commit giữa hai câu không lọt vào trang.
     - Mọi connection trong pool trả về `read committed` / read-write.
     - Lỗi DB → 500 và connection được trả.
   - Không ghi gì, nên không có gì phải commit cùng nhau. Lần ghi duy nhất trong request là `accounts.last_seen_at` của filter xác thực (BR-TN-06), chạy trong transaction riêng và đã commit trước khi vào controller.

## Không ghi nợ (để không ai tìm lại)

- **Lọc theo `actor_email`:** dòng `LOGIN_FAILED` với email không tồn tại có `actor_account_id` NULL, nên chưa lọc được theo email. Contract #36 không có tham số này; muốn có thì đổi contract.
- **Giữ audit ≥ 2 năm (BR-QT-16):** đã thỏa, vì không có gì xóa được.

## Hệ quả

- FE (T22) lọc theo `action` phải gửi khoảng ≤ 31 ngày hoặc kèm người / đối tượng, và phải encode `+` trong `from` / `to`.
- BE-2: `AuditRecorder` từ chối snapshot không phải object (convention 08 §8.2).
- Nợ D020 (đếm + OFFSET), D021 (không có index `action`).
