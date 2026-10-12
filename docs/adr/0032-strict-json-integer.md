# ADR-0032: Không ép số thực thành số nguyên khi đọc JSON

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-12
- **Liên quan:** convention 06 (validation), `GlobalExceptionHandler.handleUnreadableBody`; identity-v1 #16, #19 (`branchId`); ADR-0031.
- **Triển khai:** `BE/src/main/resources/application.yml` — `spring.jackson.deserialization.accept-float-as-int: false`. Test: `StaffRoleTransferIT.malformedBodiesFloatsPathAndMediaType` (#16, #19).

## Bối cảnh

Jackson mặc định bật `DeserializationFeature.ACCEPT_FLOAT_AS_INT`; repo chưa cấu hình Jackson. Vì vậy body `{"branchId": 1.9}` được đọc thành `1` mà không báo lỗi: `POST /api/staff/{id}/transfer` sẽ điều chuyển nhầm sang chi nhánh 1, `POST /api/staff` tạo nhân viên ở chi nhánh 1. Bean Validation không thấy gì vì giá trị đã bị cắt trước khi validate. Phát hiện khi soát validate của T14 phần 2 (2026-10-12).

## Quyết định

Tắt `ACCEPT_FLOAT_AS_INT` **toàn cục** (người dùng chọn). Số thực (kể cả `1.0`) vào trường `int` / `long` / `Integer` / `Long` → `HttpMessageNotReadableException` → 400 `MALFORMED_REQUEST` "Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu". Trường `BigDecimal` / `double` (tọa độ chi nhánh, cân nặng, tiền nếu có) không bị ảnh hưởng.

## Lý do và phương án bị loại

- **Chỉ chặn ở DTO của T14 (deserializer riêng):** các endpoint khác (của cả BE-2) vẫn âm thầm cắt id, số lượng, quota. Không chọn.
- **Để nguyên, ghi nợ:** FE gửi số nguyên nên rủi ro thấp, nhưng lỗi là âm thầm và chạm dữ liệu sai (chi nhánh khác). Không chọn.

## Hệ quả

- Ảnh hưởng mọi module (BE-2): client gửi `5.0` vào trường số nguyên giờ nhận 400 thay vì được chấp nhận. Ghi ở `docs/06-module-contracts.md` §8 để BE-2 thấy. `mvn clean verify` toàn bộ xanh sau thay đổi (IT hiện có chỉ gửi số thực vào trường `BigDecimal`, ví dụ `BranchAdminIT` tọa độ).
- Chuỗi số (`"5"`) vẫn được Jackson ép thành số (coercion vô hướng mặc định); không đổi ở ADR này.
