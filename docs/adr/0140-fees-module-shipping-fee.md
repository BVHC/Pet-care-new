# ADR-0140: Module `fees`: cấu hình phí vận chuyển và ST09 (task 115, BE-2)

- **Trạng thái:** Accepted (autopilot BE-2, 15/11/2026)
- **Ngày:** 2026-11-15
- **Liên quan:** `docs/08-tang3-be2-spec.md` §A (BR-SP-08, BR-SP-11, A1), `docs/api/fees-v1.md` (#1, #2), `V43__fees_and_material_norms.sql`, ADR-0117 (A-1), ADR-0137, `docs/06-module-contracts.md` §6b, `docs/superpowers/specs/2026-11-15-task115-shipping-fee-design.md`.
- **Triển khai:** `com.petcare.module.fees` (api / controller / service / repository / entity / dto / mapper). Không migration (V43 đã có bảng và dòng gieo `id = 1`, 0 / 0).

## 1. Quyết định

1. **Module mới `fees`** (owner BE-2). Phần cài ở task này: fees-v1 #1 `GET` và #2 `PUT /api/shipping-fee-config`, chỉ SUPER_MANAGER. Định mức vật tư (#3, #4) KHÔNG ở task này (không task nào trong sheet nhận; ghi nợ cho ST07 của BE-1).
2. **`fees.api.ShippingFeeApi.quote(long subtotal)` → `ShippingFeeQuote(fee, baseFee, freeThreshold)`** là bản cài của ST09: `fee = 0` khi `freeThreshold > 0` và `subtotal >= freeThreshold`, ngược lại `baseFee`. Bên gọi là `shop` (báo giá và đặt đơn, task 117 / 125); bên gọi tự chốt `fee` vào `online_orders.shipping_fee` (BR-SP-11, BR-QT-13). `subtotal < 0` là `IllegalArgumentException` (lỗi lập trình của bên gọi).
3. **Phí cố định toàn chuỗi (A1 thắng chữ "theo địa chỉ giao" của sheet)**: địa chỉ không có quận / toạ độ nên `quote` không nhận địa chỉ. Đổi sang tính theo vùng = ADR mới.
4. **Không bộ nhớ đệm**: bảng một dòng, đọc hiếm; `quote` là `@Transactional(readOnly, SUPPORTS)` nên đọc trong transaction của bên gọi (READ COMMITTED): đơn đặt sau một PUT đã commit dùng giá trị mới; đơn đã đọc trước khi PUT commit giữ giá trị cũ ("chỉ áp dụng cho việc tạo sau thay đổi").
5. **PUT ghi đè cả hai giá trị** (cả hai bắt buộc, số nguyên ≥ 0). Số âm → 400 `BR-SP-08` (service, nêu giá trị sai); thiếu trường → 400 `VALIDATION_FAILED`; số thực → 400 `MALFORMED_REQUEST` (ADR-0032). PUT giống hệt hiện có vẫn ghi `updated_at` / `updated_by` và audit (đơn giản, truy vết được).
6. **Khóa**: một transaction cho cả use case: kiểm giá trị → khóa dòng 1 `FOR NO KEY UPDATE` (phương thức repository `MANDATORY`, đọc dưới khóa, không `findById` trước) → `saveAndFlush` → audit. Chỉ một khóa nên không có thứ tự khóa với bảng khác.
7. **Audit** `SHIPPING_FEE_CONFIG_UPDATED`, `entity_type = shipping_fee_configs`, id 1, before / after `{baseFee, freeThreshold}` (BR-SP-11, mở rộng BR-QT-15).
8. Dòng gieo mất (xoá tay) là lỗi cấu hình máy chủ: `IllegalStateException` → 500.

## 2. Kiểm thử và đột biến

- Unit `ShippingFeeServiceTest` (13): biên dưới / bằng / trên ngưỡng, ngưỡng 0, phí 0, subtotal âm, dòng gieo mất; `InOrder` khoá → ghi → audit, giá trị before / after, PUT giống hệt, số âm không ghi gì.
- IT `ShippingFeeConfigIT` (9) trên Postgres thật: GET, PUT + audit, 403 / 401, 400 (âm, thiếu, số thực), `ShippingFeeApi.quote` thấy giá trị mới, PUT chờ khóa dòng 1 (giữ `FOR NO KEY UPDATE` ở luồng khác, đợi `pg_stat_activity`) rồi audit `before` bằng giá trị luồng kia vừa commit.
- **Đột biến đã chạy:** thay `findForUpdate()` bằng `findById(1)` (bỏ khoá) → `putWaitsForTheRowLockAndAuditsTheValuesCommittedMeanwhile` ĐỎ; hoàn nguyên → xanh. Đột biến `save` thay `saveAndFlush` không chạy (không có lỗi DB nào để lộ ra ở đây).
- `OpenApiContractParityIT`: thêm `fees-v1.yaml`, pin 200 → 202.

## 3. Hệ quả

`shop` (task 117 / 125) gọi `ShippingFeeApi.quote`; `GET /me/online-orders/quote` thuộc shop-v1, không thuộc fees. Nợ: fees-v1 #3, #4 (định mức vật tư) chưa cài; `docs/api/fees-v1.md` còn ghi "chưa có cài đặt".
