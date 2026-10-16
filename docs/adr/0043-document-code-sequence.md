# ADR-0043: Mã chứng từ liên tục bằng bảng đếm khóa dòng (T18)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-16
- **Liên quan:**
  - 02 BR-KO-03 ("dãy số phiếu liên tục"); 05 `stock_receipts.code`, `stock_adjustments.code` (`VARCHAR(20)`, UQ); erd §13 mục 18.
  - Các module sau cần cùng cơ chế: lịch hẹn, Visit, Order, phiếu thu, đặt chỗ lưu trú (cũng có cột `code`).
  - ADR-0042 (kho dùng cơ chế này).
- **Triển khai:** `platform/sequence/DocumentCodeGenerator`; migration `V14__inventory_counters_indexes.sql` (bảng `document_counters`); test `DocumentCodeIT` (6 case), `SchemaMigrationIT`.

## Bối cảnh

BR-KO-03 yêu cầu phiếu nhập không bị xóa cứng "để dãy số phiếu liên tục". Docs không nói số phiếu sinh thế nào. PostgreSQL `SEQUENCE` và cột `IDENTITY` không hoàn tác khi transaction rollback nên để lại lỗ trong dãy; lấy `MAX(code) + 1` thì hai transaction song song cùng ra một số.

## Quyết định

1. **Bảng đếm** `document_counters(doc_type, period, last_value)`, khóa chính `(doc_type, period)`, `last_value > 0`. Không có trong ERD v16 — ghi ở erd §13 mục 18.
2. **Một câu duy nhất:** `INSERT … VALUES (type, period, 1) ON CONFLICT (doc_type, period) DO UPDATE SET last_value = last_value + 1 RETURNING last_value`. Dòng bị khóa tới hết transaction của bên gọi, nên: (a) transaction rollback thì số cũng rollback — dãy liên tục tuyệt đối; (b) hai chứng từ cùng loại trong cùng tháng tạo tuần tự, không trùng số.
3. **Định dạng** `{tiền tố}-{yyMM}-{5 chữ số}`, ví dụ `PN-2610-00001` (phiếu nhập), `DC-2610-00001` (điều chỉnh): 13 ký tự, vừa `VARCHAR(20)`. Dãy **toàn chuỗi** (cột `code` unique toàn bảng), reset mỗi tháng; kỳ theo **ngày nghiệp vụ lúc tạo** (`LocalDate.now(clock)`, Asia/Ho_Chi_Minh), không theo ngày ghi trên chứng từ.
4. **Giới hạn** 99 999 chứng từ mỗi loại mỗi tháng; vượt thì `IllegalStateException` (lỗi 500, transaction rollback). Con số rộng hơn nhiều so với quy mô một chuỗi phòng khám; muốn mở rộng thì đổi `%05d` và độ dài cột.
5. **`platform` không biết module nào:** `next(docType, prefix, date)` nhận loại và tiền tố từ bên gọi. `MANDATORY` — phải nằm trong transaction tạo chứng từ.
6. **Lấy mã sau cùng** trong transaction tạo (sau mọi kiểm tra) để giữ khóa bộ đếm càng ngắn càng tốt: mọi lần tạo phiếu cùng loại trong tháng xếp hàng sau transaction đang giữ khóa.

## Hệ quả

- Tạo phiếu cùng loại là điểm tuần tự hóa (một dòng khóa) — chấp nhận với tần suất tạo phiếu của phòng khám; `DocumentCodeIT` chứng minh 20 lần tạo song song cho 20 số liên tục, không trùng.
- Phiếu hủy vẫn giữ mã (không xóa) nên dãy không lỗ.
- Module khác dùng lại bằng cách gọi `DocumentCodeGenerator.next` với `docType` / tiền tố riêng; không cần migration.

## Phương án đã loại

- **`SEQUENCE` / `IDENTITY`:** rollback để lại lỗ, trái "liên tục".
- **`MAX(code) + 1`:** trùng số khi song song, hoặc phải khóa cả bảng.
- **Suy mã từ `id`:** `id` identity cũng có lỗ khi rollback.
