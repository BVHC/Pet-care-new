# ADR-0137: Hợp đồng Nhân sự — thứ tự lỗi 409 trước 400, A06–A08 đọc danh sách ca, đọc đăng ký tuần

- **Trạng thái:** Proposed (Mạnh quyết ngày 2026-11-14 trong phiên rà hợp đồng `workforce-v1`; chờ FE-1 xác nhận vì mock phải đổi)
- **Ngày:** 2026-11-14
- **Liên quan:** ADR-0123 (Nhân sự: đăng ký ca theo tuần), ADR-0049 (appointment: 409 trước 400), `docs/02` §7 (BR-NS-01…12), `docs/03` #11 #12, `docs/api/00-method.md` §3.3 và §3.5, `docs/api/workforce-v1` (A9–A12, Q1, Q4), nợ D062

## Bối cảnh

Rà `workforce-v1` (16 endpoint, ADR-0123) với `02`, `03`, ERD §14.1 và convention 04 / 06 tìm ra hai chỗ hổng chức năng của nhân viên A06–A08 và vài chỗ mô tả lỗi chưa đúng. Contract chưa có Java, nên sửa lúc này không tốn code.

## Quyết định

1. **Thứ tự kiểm lỗi ở năm thao tác chuyển trạng thái** (hủy phân ca, ghi vắng đột xuất, rút / duyệt / từ chối yêu cầu): quyền (403) → tồn tại và phạm vi (404 / 403) → **chuyển trạng thái hợp lệ (409 `INVALID_STATE_TRANSITION`)** → guard thời gian / nghiệp vụ (400 `BR-NS-02`, `BR-NS-06`, `BR-NS-07`). Khác thứ tự chung của `00-method` §3.5 (400 trước 409), theo khuôn appointment (ADR-0049). Lý do: phân ca đã `ON_LEAVE` / `CANCELLED` mà ca đã qua thì báo "không còn `SCHEDULED`" thay vì "ca đã bắt đầu, dùng ghi vắng". `POST /me/shift-requests` không đổi: "phân ca không `SCHEDULED`" là điều kiện tạo Yêu cầu#1 nên là 400 `BR-NS-04`.
2. **A06–A08 đọc được `GET /shift-definitions`** (chỉ đọc, chỉ ca đang dùng của chi nhánh mình, `activeOnly` bị bỏ qua). Nhân viên cần danh sách này để điền `items` của `PUT /me/shift-registrations/{weekStart}` và chọn ca đích của `SWAP`; trước đây chỉ BRANCH_MANAGER gọi được.
3. **Thêm `GET /me/shift-registrations/{weekStart}`** (endpoint #17, UC35): trả `ShiftRegistration` (phân ca `SCHEDULED` của tuần, `shiftCount`, `requiredShifts`). `PUT` thay cả tuần nên client phải nạp đăng ký cũ và thấy định mức trước khi sửa. Không kiểm hạn đăng ký.
4. **Phạm vi chi nhánh:** BRANCH_MANAGER gọi với id thuộc chi nhánh khác → 403 `ACCESS_DENIED_SCOPE_MISMATCH` (`BranchScope.check` trên bản ghi đã nạp); nhân viên gọi với id phân ca / yêu cầu của người khác → 404 (không lộ sự tồn tại).
5. **`weekStart` không phải thứ Hai** là 400 `BUSINESS_RULE_VIOLATION` mã `BR-NS-11` ở cả `PUT`, `GET` đăng ký và `GET /shift-weekly-summaries` (trước đây thống kê ghi nhầm `BR-NS-12`).
6. **Sửa mô tả lỗi `SWAP`** theo đúng BR-NS-04: ca đích đang dùng, cùng chi nhánh, khác ca hiện tại, không chồng giờ với các phân ca *khác* trong ngày (không tính chính ca đang đổi); bỏ cụm "ca đích khác ngày" (ca đích là định nghĩa ca, ngày lấy theo phân ca).
7. **`PATCH /shift-definitions`:** cho `active = true` (bật lại ca đã ngừng dùng; ca không xóa được và chỉ mục tên `(branch_id, lower(name))` tính cả ca đã ngừng nên không bật lại thì tên bị khóa vĩnh viễn); body không có trường nào → 400 `VALIDATION_FAILED`.
8. **`ShiftRegistration.assignments`** chỉ gồm phân ca `SCHEDULED` của tuần (khớp `shiftCount`); `items` rỗng vẫn đi qua BR-NS-12.
9. **Giữ phân trang** `GET /shift-assignments` (trần 62 ngày áp cả lịch quản lý, A9). Chi nhánh đông nhân viên gọi nhiều trang.
10. **Một transaction cho mọi hệ quả** của hủy phân ca, ghi vắng, rút, duyệt, từ chối và `PUT` đăng ký tuần: `approve` đổi yêu cầu sang `APPROVED` và phân ca (`ON_LEAVE` với `LEAVE`, đổi ca đích với `SWAP`) cùng lúc; hủy phân ca, ghi vắng và hủy mục trong `PUT` từ chối luôn yêu cầu `PENDING` của phân ca đó (Yêu cầu#5, `decision_note` do hệ thống ghi, `decided_by` rỗng); thông báo ghi outbox cùng transaction (BR-NS-08). Một bước lỗi thì rollback tất cả, không `catch` trong transaction. Nguồn: `03` mục Quy ước và Phụ lục; ADR-0123 mới nêu cho outbox và lô `PUT`.
11. **Thứ tự khóa** của hủy phân ca, ghi vắng, rút, duyệt, từ chối (đề xuất, Mạnh duyệt 2026-11-14): đọc không khóa để lấy `staffId` → khóa `accounts` của nhân viên `FOR NO KEY UPDATE` → khóa `shift_assignments` → khóa `shift_requests` → **đọc lại dưới khóa rồi mới kiểm** (trạng thái 409, rồi guard 400). Cùng thứ tự với `PUT` đăng ký và phân hộ (ADR-0123 mục 8: `accounts` → `shift_definitions FOR SHARE` → `shift_assignments`) nên không đảo khóa; `PUT` khóa `accounts` trước rồi mới hủy phân ca và từ chối yêu cầu. Duyệt `SWAP` kiểm lại chồng giờ ở bước này (BR-NS-06). Hàng "Workforce" của `concurrency-locking.md` thêm khi cài (T58–T59), gộp chuỗi này với chuỗi của ADR-0123.

## Hệ quả

| Phần | Thay đổi |
|---|---|
| Hợp đồng | `workforce-v1` 16 → 17 endpoint; thêm A9–A12, Q1 đã chốt, Q4 (trần `items`) còn mở; tổng 345 → 346 (`docs/api/INDEX.md`, `docs/INDEX.md`) |
| Code | Chưa có Java. Khi cài (T58–T59): kiểm 409 trước 400 trong service, `BranchScope.check` cho id của quản lý, `GET` đăng ký tuần dùng lại `ShiftRegistration` của `PUT`; một `@Transactional` cho mỗi use case, khóa theo mục 11; IT: ca `ON_LEAVE` đã qua → 409, id chi nhánh khác → 403, id người khác → 404 |
| Test | Unit `InOrder` khóa → đọc lại → guard → ghi cho 5 thao tác; **`ShiftRequestFsmIT` (FSM #11) và `ShiftAssignmentFsmIT` (FSM #12)** trên bean thật, kế thừa `FsmTransitionTestBase` với bảng chép độc lập từ `03`, đủ mọi cặp hợp lệ và không hợp lệ (4×4 trạng thái mỗi FSM), gồm cạnh hệ thống Yêu cầu#5, Phân ca#6 và cạnh Phân ca#7; IT `approve` song song với `withdraw` (một bên 409), `approve` song song với `cancel` / `absent` của cùng phân ca, `approve` `SWAP` song song với `PUT` đăng ký của cùng nhân viên, rollback toàn bộ khi một bước lỗi (đổi phân ca lỗi thì yêu cầu vẫn `PENDING`); đột biến: bỏ khóa `accounts`, đảo thứ tự khóa, thêm `catch` trong transaction, đổi `saveAndFlush` thành `save` phải làm test đỏ |
| FE (nợ D062, T66) | `workforce.mock.ts` hiện có #1–14 và kiểm 400 trước 409 (ghi chú W1): thêm `PUT` / `GET /me/shift-registrations`, `GET /shift-weekly-summaries`, `source`; A06–A08 gọi `GET /shift-definitions`; đổi thứ tự lỗi sang 409 trước 400 ở năm endpoint |
| `OpenApiContractParityIT` | Không đổi (`workforce` chưa có Swagger); khi gắn annotation thì pin tăng theo |

## Phương án đã loại

- **400 trước 409 theo `00-method` §3.5:** hủy phân ca `ON_LEAVE` đã qua báo "ca đã bắt đầu", dễ hiểu nhầm.
- **Endpoint riêng cho nhân viên đọc ca (`/me/shift-definitions`):** thêm đường dẫn cho cùng một dữ liệu; mở quyền đọc ở endpoint sẵn có ít phát minh hơn.
- **Không thêm `GET` đăng ký tuần, nhân viên suy từ `GET /me/shift-assignments`:** vẫn thiếu `requiredShifts`, phải đoán từ thông báo lỗi.
- **Bỏ phân trang `GET /shift-assignments` (trả mảng, trần 62 ngày):** bạn chọn giữ phân trang.
- **Đặt trần số phần tử `items`:** đặc tả không nêu con số; để Q4 (TBD, BE-1).
