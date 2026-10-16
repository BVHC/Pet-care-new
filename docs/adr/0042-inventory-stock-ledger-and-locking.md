# ADR-0042: Kho — sổ cái lô, khóa lô, FEFO, phiếu nhập, điều chỉnh, xem tồn (T18)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-16
- **Liên quan:**
  - inventory-v1 #1–17 (UC73–UC76); 02 BR-KO-01…07, BR-SP-05, BR-BH-04, BR-KB-03/04, BR-CK-03, BR-QT-15; 03 #9 Phiếu nhập kho; 04 §9 và nguyên tắc 4, 5; 05 §9; 06 §4, §5, §6.
  - Convention 07 §7.3 (**chốt mục "Trừ kho khi thu tiền / khi tiêm"**), 08 §8.3 (`STOCK_RECEIPT_CANCELLED`, `STOCK_ADJUSTED`).
  - ADR-0029 (khóa trước đọc sau), 0038 (truy vấn chỉ ghép điều kiện đã gửi), 0040 (Swagger = hợp đồng), 0041 (khuôn module BE-2), 0043 (mã chứng từ).
- **Triển khai:**
  - `inventory/controller/{SupplierController, StockReceiptController, InventoryController, StockAdjustmentController}`.
  - `inventory/service/{SupplierService, StockReceiptService, StockAdjustmentService, InventoryService, StockQueryService, StockIssueService, StockLedger, FefoAllocator, InventoryAuditActions, Values}`.
  - `inventory/repository/{StockLotDao, InventoryQueryRepository, StockReceiptQueryRepository, SupplierRepository, StockReceiptRepository, StockAdjustmentRepository, StockAdjustmentLookupRepository}`, `inventory/entity/*`, `inventory/fsm/StockReceiptTransitionHandler`, `inventory/dto/*`, `inventory/mapper/*`.
  - Sửa catalog: `ProductRepository.findByIdForUpdate/ForShare`, `ProductService.updateProduct` khóa dòng trước khi đọc, `CatalogQueryApi.findProductForShare` và `findProducts`.
  - Xóa `StockQueryApiPlaceholder`. Migration V14 (index).
  - Test: unit `FefoAllocatorTest`, `StockLedgerTest`, `StockIssueServiceTest`, `StockReceiptTransitionHandlerTest`, `SupplierServiceTest`, validation test; IT `SupplierIT`, `StockReceiptIT`, `InventoryViewIT`, `StockAdjustmentIT`, `StockApiIT`, `DocumentCodeIT`, `OpenApiContractParityIT` (76 endpoint).

## Bối cảnh

Module `inventory` chỉ có một placeholder. Sales (Order#8) và visit (tiêm) sẽ trừ kho đồng thời; nếu khóa sai thì bán quá tồn hoặc deadlock. Convention 07 §7.3 yêu cầu ADR trước khi cài. `stock_lots.quantity ≥ 0` chỉ là lưới an toàn cuối: lỗi nghiệp vụ phải trả đúng mã rule, không phải lỗi ràng buộc DB.

## Quyết định

### 1. Nguyên tắc khóa

1. **Khóa mọi lô cần đụng bằng một câu, id lô tăng dần** (`… WHERE id IN (…) ORDER BY id FOR NO KEY UPDATE`), đọc số dư từ chính câu khóa, rồi mới ghi. Mọi luồng (xác nhận / hủy phiếu, điều chỉnh, bán, tiêm) dùng chung thứ tự này nên không deadlock với nhau, kể cả khi chạm nhiều lô theo thứ tự khác nhau ở tầng ứng dụng (`StockApiIT.salesAndAdjustments…`). Bán nhiều sản phẩm khóa lô của **mọi** sản phẩm trong một câu, không theo từng sản phẩm.
2. **Không dùng `LIMIT 1 … FOR UPDATE`**: PostgreSQL kiểm lại `WHERE` sau khi chờ khóa và có thể trả 0 dòng dù còn lô khác đủ. Chọn lô trong Java, trên các lô đã khóa (`FefoAllocator`).
3. **Thứ tự toàn module:** bên gọi khóa chứng từ của mình (Order, Visit) → chứng từ kho (`stock_receipts`) → `suppliers` `FOR SHARE` → `products` `FOR SHARE` → tạo dòng tồn / lô → khóa lô (id ↑) → ghi `stock_movements`.
4. **Tạo dòng tồn và lô không đua:** `INSERT … ON CONFLICT DO NOTHING` rồi SELECT (không `DO UPDATE`, để không khóa dòng đã có và đảo thứ tự khóa); gọi theo thứ tự (sản phẩm, số lô, hạn) cố định. Lô mới có số dư 0 rồi mới cộng qua sổ cái để luôn có movement.
5. **`StockLedger` là nơi duy nhất đổi `stock_lots.quantity`** (04 nguyên tắc 5): nhận `LockedLot` đã khóa, kiểm số dư không âm và không tràn `int` (BR-KO-01), UPDATE lô + INSERT `stock_movements` (`balance_after`) cùng transaction. `MANDATORY`.
6. Lô và dòng tồn không map thành entity JPA (SQL qua `StockLotDao`) để không có bản sao cũ trong persistence context.

### 2. Hết hạn và FEFO (BR-KO-01, 05)

- Hết hạn khi `expiry_date < hôm nay` (`LocalDate.now(clock)`, Asia/Ho_Chi_Minh): lô hết hạn **hôm nay** vẫn bán / tiêm được; lô mặc định không bao giờ hết hạn. **Tồn khả dụng** = tổng số dư lô chưa hết hạn. `hasStockAnywhere` (BR-SP-05) tính cả lô hết hạn còn số dư.
- FEFO: hạn tăng dần, lô mặc định sau cùng, cùng hạn id nhỏ trước. **Không bao giờ xuất lô hết hạn.** Chia theo `FefoAllocator` (hàm thuần, thiếu thì không chia một phần).
- **`StockApi`** (`StockIssueService`, mọi method `MANDATORY`): `issueForSale` gom dòng cùng sản phẩm, thiếu bất kỳ dòng nào thì **không trừ gì** và trả `List<Shortage>` (không ném — 06 §1); `lockFefoLot` chọn lô FEFO đầu tiên đủ trong **một** lô (một mũi tiêm từ đúng một lô) và ném `BR-KO-05` nếu không có; `issueForVaccination` từ chối lô hết hạn (`BR-KO-05`); `revertVaccination` hoàn đúng lô kể cả đã hết hạn. Đầu vào sai là `IllegalArgumentException`. Mã `BR-KO-05` cho "không còn lô đủ" là giả định.
- Sales và visit phải khóa chứng từ của mình **trước** khi gọi `StockApi` (06 §5) và truyền `actorId`.

### 3. Cờ quản lý hạn dùng đổi giữa chừng (BR-SP-05)

Xác nhận phiếu đọc sản phẩm qua `CatalogQueryApi.findProductForShare` (`FOR SHARE`); catalog sửa sản phẩm khóa `FOR NO KEY UPDATE` dòng **trước** khi đọc (`ProductService.updateProduct`). Hai bên xếp hàng: tắt cờ xong thì phiếu có số lô bị từ chối (BR-KO-03); phiếu xác nhận trước thì việc tắt cờ thấy lô vừa tạo và bị 400 BR-SP-05. Trước T18 tắt cờ trả 500 trên app thật vì placeholder.

### 4. Nhà cung cấp (BR-KO-02)

BRANCH_MANAGER nào cũng quản lý, dùng chung toàn chuỗi (contract A1). Xóa: `SELECT … FOR UPDATE` dòng nhà cung cấp (khóa duy nhất xung đột với `FOR KEY SHARE` của FK phiếu đang chèn) → đã có **bất kỳ** phiếu nào (nháp, đã xác nhận, đã hủy) → 400 BR-KO-02. Ngừng hợp tác: phiếu nháp cũ giữ nguyên; tạo / sửa / xác nhận phiếu với nhà cung cấp ngừng hợp tác → 400 BR-KO-02 (đọc `FOR SHARE` dưới khóa phiếu). Không audit.

### 5. Phiếu nhập (BR-KO-03, 04; FSM 03 #9)

- Chi nhánh = chi nhánh người gọi; phiếu chi nhánh khác → 403 (`BranchScope.check`), không có → 404. `StockReceiptTransitionHandler` copy nguyên bảng 03 #9.
- **Tạo / sửa nháp** ("sửa tự do", PUT thay toàn bộ — A2): số lượng 1…1 000 000, giá nhập 0…1 tỷ, 1–200 dòng; số lô / hạn chưa bắt buộc. Mã phiếu lấy sau cùng (ADR-0043).
- **Xác nhận:** nhà cung cấp còn hợp tác; ngày nhập không ở tương lai (giả định); sản phẩm quản lý hạn dùng bắt buộc số lô + hạn **sau ngày nhập**; sản phẩm không quản lý hạn dùng mà dòng có số lô / hạn → 400 (cờ có thể đổi sau khi lập nháp). **Mọi lỗi dòng gom vào một message BR-KO-03.** Nhiều dòng cùng sản phẩm + số lô + hạn, hoặc trùng lô cũ, cộng vào cùng một lô (một movement mỗi lô). Gán `stock_lot_id` cho dòng.
- **Hủy:** nháp → không đổi tồn, không audit; đã xác nhận → gom số lượng theo lô, lô nào không đủ → 400 `BR-KO-04` nêu đủ lô (hướng dẫn dùng điều chỉnh), trừ qua sổ cái (`RECEIPT_CANCEL`, kể cả lô đã hết hạn), audit `STOCK_RECEIPT_CANCELLED`. Lý do hủy tùy chọn (A3).

### 6. Xem tồn, tồn tối thiểu (BR-KO-01, 07)

- A05–A08 xem (lô: A05, A06), chỉ A05 đặt tồn tối thiểu; chi nhánh = của người gọi.
- **Đọc catalog bằng SQL JOIN bảng `products`** trong `InventoryQueryRepository` — lệch có chủ đích khỏi ranh giới module (chỉ đọc, chỉ các cột `id, sku, name, unit, product_type, tracks_expiry, is_active`) để lọc tên / SKU / loại và phân trang trong một câu; cả hai module cùng BE-2; `InventoryViewIT` bắt khi cột đổi.
- `GET /inventory` liệt kê sản phẩm đang kinh doanh **và** sản phẩm đã ngừng nhưng chi nhánh còn số dư. `belowMin` = khả dụng < tồn tối thiểu (tối thiểu 0 thì không bao giờ dưới); `expiringWithinDays` = có lô còn số dư với hạn ≤ hôm nay + N (gồm lô đã hết hạn). Lô số dư 0 ẩn khỏi danh sách lô.

### 7. Điều chỉnh tồn (BR-KO-01, 06)

Chỉ BRANCH_MANAGER; `OTHER` thiếu ghi chú → 400 BR-KO-06; lô thuộc chi nhánh khác hoặc không tồn tại → 404 (không lộ); giảm quá số dư → 400 BR-KO-06 nêu đủ lô, kiểm trước mọi lệnh ghi; tràn `int` → BR-KO-01; mỗi lô một dòng; khóa lô id ↑; mã `DC-…` lấy sau cùng; audit `STOCK_ADJUSTED` (after = lý do + các dòng). Lô hết hạn điều chỉnh được. **Giới hạn đã biết:** sản phẩm chưa từng nhập chưa có lô nên chưa điều chỉnh tăng được (nợ).

### 8. Migration V14

`document_counters` (ADR-0043) và index: `inventory_items(product_id)`, `stock_receipts(branch_id, receipt_date DESC, id DESC)`, `stock_receipts(supplier_id)`, `stock_receipt_lines(stock_receipt_id)`, `(product_id)`, `(stock_lot_id) WHERE NOT NULL`, `stock_adjustments(branch_id, created_at DESC, id DESC)`, `stock_adjustment_lines(stock_adjustment_id)`, `(stock_lot_id)`.

## Hệ quả

- Sales (Order#8, T-bán lẻ) và visit (tiêm) gọi `StockApi` được ngay; app thật hết 500 khi tắt cờ hạn dùng (BR-SP-05).
- `StockReportApi` (tồn dưới ngưỡng, lô sắp / đã hết hạn) và ST08 để T44.
- Mọi code đổi số dư lô phải đi qua `StockLedger`; thêm đường ghi khác là phá nguyên tắc 5.

## Phương án đã loại

- **Khóa theo sản phẩm rồi từng lô:** thứ tự khóa khác thứ tự `id` toàn cục của luồng nhập / điều chỉnh → có thể deadlock.
- **`@Version` optimistic cho lô:** bán đồng thời món cuối thành retry / lỗi thay vì kết quả `Shortage` sạch.
- **Gọi catalog qua API cho danh sách tồn:** lọc và phân trang phải làm ở Java.
