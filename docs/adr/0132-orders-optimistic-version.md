# ADR-0132: `orders.version` (`@Version`) làm lưới an toàn chống ghi đè bản cũ (trả D045)

- **Trạng thái:** Proposed
- **Ngày:** 2026-11-14
- **Liên quan:** ADR-0044 (Order lõi, khóa trước khi đọc), 0069 (hoàn tất lượt, thứ tự gọi tránh D045), 0103 (thu tiền); convention 07 §7.3 (chiến lược khóa phải có ADR trước khi cài `@Version`); 04 §4.4 (409). Nợ D045 (trả), D059 (số V chưa xác nhận với BE-2).
- **Đánh số (16/11/2026):** số 0132 từng trùng với ADR AI `0132-ai-personal-chat-t77c`; ADR AI đã đổi sang **0142** (`0142-ai-personal-chat-t77c`), ADR này giữ **0132**.
- **Triển khai:**
  - `V54__orders_version.sql` (`orders.version BIGINT NOT NULL DEFAULT 0`), `sales/entity/SalesOrder` (`@Version`). `orders` là bảng của BE-2: BE-1 làm thay như V42 (ADR-0116); số V54 chờ BE-2 xác nhận (D059).
  - Test: `SalesOrderVersionIT`; `SchemaMigrationIT` thêm V54.

## Bối cảnh

Mọi đường ghi Order khóa dòng `FOR NO KEY UPDATE` rồi mới nạp entity (ADR-0044 §2). Khóa chỉ giữ chỗ ở DB: nếu persistence context của transaction **đã có** `SalesOrder` đó (do một lần đọc không khóa trước đó, ví dụ `VisitOrderService.findServiceIds`), Hibernate trả bản cũ và flush ghi lại mọi cột, kể cả `total_amount`, nên dòng một transaction khác vừa thêm trong lúc chờ khóa mất khỏi tổng; guard `requireOpen` cũng đọc `status` cũ. `SalesOrder` không có `@Version`, và CHECK của DB chỉ kiểm từng dòng (`ck_order_lines_total`), không kiểm tổng. Hôm nay không caller nào dính (Visit#5 gọi `markPendingByVisit` trước `findServiceIds`), nhưng thứ tự gọi chỉ là quy ước; T28b, T38, T63, trả hàng, nội trú sắp gọi thêm vào Order.

## Quyết định

1. Thêm `orders.version BIGINT NOT NULL DEFAULT 0` và `@Version long version` trên `SalesOrder`. Ghi trên bản cũ thì `UPDATE … WHERE version = ?` trúng 0 dòng → `ObjectOptimisticLockingFailureException` → 409 `CONCURRENCY_CONFLICT` (`GlobalExceptionHandler` đã map `OptimisticLockingFailureException`, convention 04 §4.4). Mọi đường ghi Order đều `flush()` tường minh nên lỗi nổi lên trong service (rollback cả use case), không lúc commit.
2. **Đây là lưới an toàn, không thay khóa dòng.** Khóa vẫn xếp hàng các thao tác; version chỉ bắt vi phạm quy ước "khóa trước, đọc sau". Với code hiện tại version luôn khớp nên không đổi hành vi nào; không đổi thứ tự khóa (`visits → orders → products → lô kho`).
3. `version` không đưa ra response / OpenAPI (không đổi DTO, ghim OpenAPI vẫn 193).
4. Số V54: BE-1 viết thay BE-2 (tiền lệ V42). Migration chỉ `ADD COLUMN … DEFAULT 0` (Postgres 11+ không viết lại bảng); mọi `INSERT INTO orders` ở test / seed không nhắc `version` vẫn chạy.

## Không bảo đảm (cố ý) — rủi ro còn lại

| Khoảng hở | Vì sao chấp nhận |
|---|---|
| Đọc cũ vẫn xảy ra; A chỉ làm lỗi ồn (409, người dùng thử lại) | Không sửa gốc bằng refresh / projection (đã loại, xem dưới) |
| Chỉ đổi cột của một dòng mà không đổi tổng (`OrderLine.transferOwnerTo`) không làm bẩn dòng `orders` nên không bị kiểm | Các method đó khóa trước nên an toàn; `OrderLine` không thêm `@Version` (giá trị thấp, thêm cột + ca test) |
| Ghi bằng SQL thô / bulk JPQL không tăng version | Main không có `UPDATE orders` native hay JPQL; ai thêm phải tự `version = version + 1` |
| Vẫn dựa vào quy ước khóa trước, đọc sau | Quy ước giữ ở ADR-0044; version bắt vi phạm |

## Phương án đã loại

- **C — `findServiceIds` đọc bằng projection** (như `findServiceIdsByVisitIds`): chỉ bịt nguồn duy nhất hiện có, không bắt luồng mới; không làm cùng đợt (có thể làm sau).
- **`refresh` entity sau khóa:** thêm một SELECT mỗi lần khóa, phải làm ở mọi đường khóa, và xóa thay đổi chưa flush của caller.
- **`@DynamicUpdate`:** không giúp, `total_amount` vẫn là cột đổi.
- **Tính `total_amount` ở DB (trigger):** đi ngược ADR-0098 (đã gỡ trigger audit), không cứu `status` / `lines` cũ.
- **`@Version` cả `OrderLine`:** xem bảng trên.

## Kiểm chứng

`SalesOrderVersionIT`: (1) đọc không khóa rồi khóa trong cùng transaction trong lúc transaction khác commit sửa Order → 409, Order còn `OPEN`, tổng giữ giá trị của transaction kia; (2) thứ tự thật của `VisitCompletionService` (khóa trước) → thành công, tổng đúng; (3) thêm dòng giá 0đ (tổng không đổi) vẫn tăng `version`. Các IT của sales / visit chạy nguyên trạng.
