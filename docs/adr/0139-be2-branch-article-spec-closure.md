# ADR-0139: Chốt đặc tả vòng đời chi nhánh và bài viết duyệt, bình luận (BE-2, đóng TBD-4)

- **Trạng thái:** Accepted (autopilot BE-2, 15/11/2026)
- **Ngày:** 2026-11-15
- **Liên quan:** `docs/08-tang3-be2-spec.md` §F.1, §F.3, §G, ADR-0117 mục 5 (TBD-4), `V48__branch_lifecycle_customer_merge_article_comments.sql`, BR-CN-01, BR-CN-09, BR-CN-10, BR-QT-03, BR-QT-04, BR-BV-02, BR-BV-08, `docs/03-state-machines.md` §2, `docs/06-module-contracts.md` §6b.
- **Triển khai:** chỉ tài liệu. Không mã Java, không migration. Task 142 (tạm ngừng / đóng chi nhánh), 143 (duyệt bài), 144 (bình luận) dùng ADR này.

## 1. Bối cảnh

Rà soát đặc tả F trước task 142–144 thấy: TBD-4 (mở lại chi nhánh có xóa `suspended_at` không), BR-CN-09 nói "chọn hủy hàng loạt" nhưng không có lựa chọn thay thế, không có bảng nói chi nhánh `SUSPENDED` / `CLOSED` thì từng luồng ra sao, FSM #21 thiếu các chuyển của BR-BV-02, Q3 / Q4 của `articlex` còn ghi TBD dù `08` §G đã "chốt theo đề xuất", và chi tiết bình luận (thứ tự, tên rút gọn, sửa / xóa bình luận ẩn) chưa định nghĩa. `02` chưa sửa BR-CN-01, BR-BV-02, BR-QT-03, BR-QT-04.

## 2. Quyết định

**B1. Mở lại chi nhánh (TBD-4).** `SUSPENDED → ACTIVE` đặt `suspended_at`, `suspend_reason` và `expected_resume_on` về NULL; lịch sử tạm ngừng nằm ở `audit_logs`. Các CHECK của V48 cho phép: `ck_branches_suspended` chỉ buộc khi `status = 'SUSPENDED'`, `ck_branches_draft_not_suspended` chỉ cho `DRAFT`, không CHECK nào buộc `suspended_at` khi `ACTIVE`. Chuyển #1 đặt `suspended_at` và `suspend_reason`; #3, #4 đặt `closed_at` và `close_reason` (V48 buộc).

**B2. BR-CN-09.** Còn lịch hẹn hoặc đặt chỗ lưu trú `BOOKED` thì tạm ngừng / đóng cửa **bắt buộc** hủy hàng loạt; không có đường giữ lại. `cancelBookings = false` mà còn lịch → 400 `BR-CN-09`.

**B3. Hiệu lực theo luồng.** Bảng mới "Hiệu lực của trạng thái chi nhánh theo luồng" ở `08` §F.1, 22 dòng × `ACTIVE` / `SUSPENDED` / `CLOSED`. `SUSPENDED` = hoàn tất việc dở, không bắt đầu việc mới; `CLOSED` = chỉ đọc, đóng chỉ được khi tồn đọng BR-CN-07 / 08 bằng 0. Thao tác ghi bị chặn trả 400 `BR-CN-10`. BR-CN-08 thắng: các luồng mà backlog phải rỗng trước khi tạm ngừng (Visit mở, thú trong chuồng, ca `OPEN`) ở cột `SUSPENDED` là "không xảy ra (BR-CN-08)"; Order `PENDING` được phép còn lại nên mở ca mới để thu chúng là "cho phép".

**B4. FSM #21.** Thêm #7 `PUBLISHED → HIDDEN` và #8 `HIDDEN → PUBLISHED` (A04), #9 `DRAFT → PUBLISHED` (A04, bài của SUPER_MANAGER, không duyệt, BR-BV-07). Tổng 9 chuyển; trạng thái đúng giá trị V48.

**B5. Bài viết và bình luận.** Q3: VET đề xuất chuyên mục, SUPER_MANAGER chọn và được đổi khi duyệt: request duyệt (`approveArticle`) có `categoryId` tùy chọn, vắng = giữ chuyên mục VET đề xuất, id không tồn tại → 404. Q4: không duyệt trước, bình luận hiện ngay, SUPER_MANAGER ẩn sau. Danh sách bình luận cũ nhất trước, 20 / trang. Tên tác giả rút gọn: mọi từ trừ từ cuối viết tắt chữ cái đầu kèm dấu chấm, từ cuối giữ nguyên ("Nguyễn Văn An" → "N. V. An"). Tác giả không sửa bình luận `HIDDEN` (409) nhưng xóa được (xóa mềm → `DELETED`, trạng thái cuối); ẩn bình luận `DELETED` → 409; đăng vào bài không `PUBLISHED` → 404 cho khách.

**B6. Sửa `02` tại chỗ** (đánh dấu "sửa ở T106"): BR-CN-01 (4 trạng thái theo #20), BR-BV-02 (trạng thái duyệt theo #21), BR-QT-03 và BR-QT-04 (chi nhánh `SUSPENDED` / `CLOSED` không nhận nhân viên, 400 `BR-CN-10`).

**B7.** Bảng #20 (4 chuyển) và #21 (9 chuyển) vẫn ở `08`; `03` §2 có con trỏ, chỉ mục ghi số chuyển.

**B8.** Đồng bộ hợp đồng: `m_branchlife.py`, `m_articlex.py` ghi các quyết định trên, đóng các câu hỏi TBD cũ; `06` §6b #13 ghi các method số đếm của BE-1 cần cho task 142.

## 3. Hệ quả

- Task 142 không còn TBD chặn; vẫn phụ thuộc các method số đếm của BE-1 (placeholder nếu chưa có) và UC77 / hoàn tiền để đóng cửa.
- Task 143, 144 không còn thiếu chuyển FSM hay câu hỏi mở; `05` vẫn chưa ghi giá trị mới của `branches.status`, `articles.status` (việc riêng).
- ADR-0117 là Proposed; TBD-4 đánh dấu "Đã chốt ở ADR-0139".
