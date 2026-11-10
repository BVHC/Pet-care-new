# ADR-0123: Nhân sự — nhân viên tự đăng ký ca theo tuần, định mức 10 ca, ca không ảnh hưởng gán Visit

- **Trạng thái:** Accepted (Mạnh quyết ngày 2026-11-08 trong phiên đặc tả, duyệt bản tổng hợp cuối cùng)
- **Ngày:** 2026-11-08
- **Liên quan:** `docs/02` §7 (BR-NS-01…12), `docs/03` #11 #12, `docs/05` §13 mục 24 và §14.1, `docs/api/workforce-v1`, ADR-0113 (V36, khóa [CFG] `workforce.*`, mẫu `SHIFT_*`), ADR-0122 (V51), ADR-0057 và BR-QT-13 ([CFG] áp dụng cho việc sau), ADR-0014 (luồng IN_APP)

## Bối cảnh

Đặc tả T54 cho BRANCH_MANAGER phân ca; nhân viên chỉ xem lịch và xin nghỉ / đổi ca. Khi rà lại cho phạm vi đồ án (không có người dùng thật), Mạnh chọn mô hình **nhân viên tự đăng ký ca theo tuần**. petcare.vn (bệnh viện thú y tham chiếu) chỉ công bố giờ mở cửa (T2–T7 8h–12h và 14h–19h, Chủ nhật và lễ 8h–12h) và cấp cứu 24/7; không công bố ca làm, lịch trực hay quy định nghỉ phép, nên các quy tắc dưới đây là quyết định của đồ án, không sao chép từ nguồn đó.

## Quyết định

1. **Ca không ảnh hưởng gán Visit.** Kể cả phân ca `ON_LEAVE` / `ABSENT`: nhân viên đang nghỉ vẫn được gán Visit. BR-TN-05 không đổi. Lịch chỉ ghi nhận việc đi làm tại chi nhánh.
2. **Không có ca đêm.** Giữ BR-NS-01 (ca không qua nửa đêm). Đồ án không mô hình trực đêm; cấp cứu ngoài giờ vẫn là giả định vận hành của BR-CN-05.
3. **Nhân viên tự đăng ký ca theo tuần (BR-NS-11).** Tuần là thứ Hai–Chủ nhật, giờ Việt Nam. Một lần `PUT /me/shift-registrations/{weekStart}` gửi toàn bộ (ngày, ca) mong muốn và **thay cả tuần** các phân ca `SELF` của chính nhân viên; có hiệu lực ngay, không duyệt. Hạn đến 00:00 thứ Hai của tuần đó; không giới hạn xa. **Một lỗi thì hủy cả lô**, thông báo lỗi liệt kê mọi mục lỗi. Không thông báo, không audit khi đăng ký.
4. **Định mức 10 ca mỗi tuần (BR-NS-12).** Khóa [CFG] chung `workforce.weekly_required_shifts` (10, khoảng 1–30), ADMIN đổi qua UC10. Chỉ chặn lúc đăng ký. Sau đó duyệt nghỉ, hủy, vắng làm tuần dưới định mức thì không chặn và không cảnh báo trong phản hồi; BRANCH_MANAGER xem `GET /shift-weekly-summaries`. Không có job nhắc.
5. **BRANCH_MANAGER vẫn phân hộ** (`POST /shift-assignments`, `source = MANAGER`) làm đường dự phòng, và là người duy nhất hủy ca `MANAGER`. Nhân viên không hủy được ca `MANAGER`. Quản lý không nằm trong lịch.
6. **Nghỉ / đổi ca chỉ với chính mình.** `LEAVE` / `SWAP` giữ nguyên (BR-NS-04, báo trước ≥ 24 giờ); `SWAP` là đổi sang ca khác của chính mình trong cùng ngày, không đổi với đồng nghiệp. Nghỉ nhiều ca trong một ngày là nhiều yêu cầu `LEAVE`.
7. **Không giới hạn số người một ca**, không định biên, không seed ca trong code (ca mẫu "Ca sáng 08–12", "Ca chiều 14–19" chỉ nằm ở dữ liệu demo).
8. **Chồng giờ chỉ kiểm trong service**, không dùng `EXCLUDE USING gist`: khóa `accounts` của nhân viên `FOR NO KEY UPDATE` (không dùng `FOR SHARE`, vì hai khóa chia sẻ không loại trừ nhau), rồi `shift_definitions FOR SHARE`, rồi `shift_assignments`. Thêm `UNIQUE (staff_id, shift_definition_id, work_date) WHERE status = 'SCHEDULED'` đã có ở V36. Luồng vô hiệu hóa nhân viên (BR-NS-09) cũng khóa `accounts` trước nên không đảo thứ tự.
9. **Thông báo cho BRANCH_MANAGER không dùng WebSocket.** `NotificationApi.enqueue` trong cùng transaction với mẫu `SHIFT_REQUEST_SUBMITTED_APP` tới `StaffDirectoryApi.findActiveStaffIds(branchId, BRANCH_MANAGER)`; `InAppNotificationJob` (5 giây) ghi vào `notifications`; chuông FE hỏi `unread-count` mỗi 60 giây (trễ tối đa khoảng 65 giây); trang duyệt dùng `liveQuery` 5 giây. BE chưa có hạ tầng WebSocket và FE đã chọn polling trước.

## Hệ quả

| Phần | Thay đổi khi cài đặt (T58–T59) |
|---|---|
| Migration (V36 đã áp dụng, không sửa) | Cột `shift_assignments.source VARCHAR(30) NOT NULL CHECK IN ('SELF','MANAGER')`; khóa [CFG] `workforce.weekly_required_shifts`. **Số migration chưa chốt:** V51 đang được ADR-0122 giữ cho phần gỡ khiếu nại (T70), nhưng T58 đến trước. Ai viết trước thì lấy V51, bên kia lấy số kế tiếp; số vượt V51 phải báo BE-2 trước (D059) |
| Hợp đồng | `workforce-v1` 14 → 16 endpoint (thêm `PUT /me/shift-registrations/{weekStart}`, `GET /shift-weekly-summaries`); `ShiftAssignment.source`; tầng 3 127 → 129, tổng 358 → 360. `OpenApiContractParityIT` chưa đổi vì `workforce` chưa có Swagger; khi gắn annotation thì pin tăng theo |
| FSM #12 | Thêm Phân ca#7 (nhân viên sửa đăng ký, `SCHEDULED → CANCELLED`); Phân ca#1 có thêm người kích hoạt A06–A08; Yêu cầu#5 nhận thêm nguồn Phân ca#7. `FsmTransitionTestBase` của #12 phải thêm cạnh #7 |
| Khóa | Thêm hàng "Workforce" vào bảng lock order của `docs/architecture/concurrency-locking.md` khi cài đặt: `accounts (nhân viên) FOR NO KEY UPDATE → shift_definitions FOR SHARE → shift_assignments` |
| Test | Unit `InOrder` khóa → guard → ghi; IT hai `PUT` song song cho cùng nhân viên, `PUT` không đụng ca `MANAGER`, hạn 00:00 thứ Hai, định mức, hủy cả lô, thống kê tuần cả người chưa có ca |
| FE | `workforce.mock.ts` và màn hình T57-fe cần: màn đăng ký tuần, thống kê tuần của quản lý, trường `source`, nhãn "đổi sang ca khác"; ghi nợ D062 |
| `CLAUDE.md` | Số ADR kế tiếp 0124 |

## Phương án đã loại

- **Ca ảnh hưởng gán Visit** (gợi ý mềm, chặn hẹp theo nghỉ, chặn cứng): thêm phụ thuộc `visit` → `workforce`, chặn cấp cứu và walk-in khi chi nhánh chưa xếp ca. Mạnh chọn không.
- **Nhân viên đăng ký rồi quản lý duyệt:** thêm trạng thái và một luồng duyệt thứ hai ngoài nghỉ / đổi ca.
- **`POST` hàng loạt kèm endpoint hủy từng ca:** nhân viên rút ca xuống dưới 10 mà không bị chặn; `PUT` thay cả tuần kiểm được tập kết quả.
- **Bỏ qua mục lỗi, lưu phần còn lại:** cần `catch` trong transaction hoặc `REQUIRES_NEW`, quy tắc 10 ca mơ hồ, và dạng phản hồi chưa có trong convention.
- **Định mức tính theo giờ (36 giờ):** đổi sang đếm số ca theo yêu cầu của Mạnh.
- **Định mức riêng từng chi nhánh** (cột ở `branches` của BE-2 hoặc bảng `branch_workforce_settings`): chưa cần cho đồ án; có thể nâng lên sau mà không đổi API đăng ký.
- **Ca qua nửa đêm, đổi ca với đồng nghiệp, cảnh báo định biên, job nhắc đăng ký, `EXCLUDE USING gist`, WebSocket:** ngoài phạm vi đồ án.

## Nợ ghi nhận

Code, migration và FE chưa theo quyết định này: D062 trong `docs/dept`.
