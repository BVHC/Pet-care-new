# ADR-0138: Nhân sự — nền module `workforce` (entity, repository, FSM #11 / #12)

- **Trạng thái:** Proposed (Mạnh quyết ngày 2026-11-16, T58 bước 1; phần khóa của CRUD ca bổ sung ở bước 2)
- **Ngày:** 2026-11-16
- **Liên quan:** ADR-0123 (Nhân sự: đăng ký ca theo tuần, thứ tự khóa mục 8), ADR-0137 (hợp đồng `workforce-v1`), ADR-0079 / 0080 / 0081 (khuôn CareTask: entity, khóa, CHECK), ADR-0113 (V36), `docs/02` §7, `docs/03` #11 #12, `docs/05-erd.md` §14.1, nợ D062

## Bối cảnh

Module `workforce` mới có hợp đồng và schema (V36, V55), chưa có Java. T58 chia hai bước: bước 1 (ADR này) dựng entity, repository, hai máy trạng thái và test; bước 2 làm CRUD ca (#1–3, Swagger, ghim OpenAPI). Các điểm dưới đây là chỗ đặc tả chưa nói rõ khi viết entity.

## Quyết định

1. **Khung module:** `module/workforce/{entity,repository,fsm}` (service, controller, dto, mapper đến ở bước 2 và T59). Chưa có gói `api/`: chưa có interface chéo module nào ở T58 (`StaffLeftBranchEvent` là của T59).
2. **Entity theo khuôn `CareTask`:** factory cho cạnh khởi tạo, mỗi cạnh chuyển trạng thái là một method ghi đủ cột theo CHECK của V36. Method **không** khóa và **không** kiểm quyền, nhưng gọi `requireScheduled()` / `requirePending()` (`IllegalStateException`) làm lớp chặn cuối vì mọi cạnh của #11 / #12 đều xuất phát từ đúng một trạng thái không-FINAL. Đây là điểm khác `CareTask`; handler vẫn là nguồn duy nhất của bảng cạnh và chạy trước để ra 409.
3. **Bất biến tự thân nằm ở entity** (`IllegalArgumentException`, lỗi lập trình): tên ca không rỗng, ≤ 60 ký tự, lưu bản đã `strip`; giờ bắt đầu < giờ kết thúc; `reason` / `decision_note` không rỗng, ≤ 300; `SWAP` có ca đích khác ca hiện tại, `LEAVE` không có ca đích. Mọi thứ cần Clock, [CFG] hoặc dòng khác (trùng tên, chồng giờ, ca chưa bắt đầu, hạn ghi vắng, định mức, phạm vi chi nhánh) là việc của service.
4. **`ON_LEAVE` ghi người duyệt và lý do:** Phân ca#3 đặt `status_changed_by` = BRANCH_MANAGER duyệt, `status_changed_at` = lúc duyệt, `reason` = lý do của yêu cầu nghỉ. `ERD §14.1` bổ sung ghi chú cột `reason`. Cột `reason` đã có ở V36, không cần migration.
5. **Phân ca#5 (đổi ca) không để lại dấu vết trên phân ca:** chỉ đổi `shift_definition_id`; `ck_shift_assignments_status_changed` buộc `status_changed_at` NULL khi còn `SCHEDULED`. Lịch sử nằm ở `shift_requests` (`decided_by`, `closed_at`, `target_shift_definition_id`).
6. **Cạnh `X → X` chỉ có ở #12** (Phân ca#5). Bảng #11 không có cạnh nào từ `PENDING` về `PENDING`.
7. **Yêu cầu#5 (hệ thống từ chối) là method entity `rejectBySystem`** cho các luồng một-phân-ca đã khóa phân ca rồi yêu cầu (hủy, ghi vắng, sửa đăng ký tuần): unique `ux_shift_requests_pending` bảo đảm tối đa một yêu cầu chờ cho mỗi phân ca. **Nghĩa vụ cho T59:** BR-NS-09 (nhân viên rời chi nhánh, nhiều phân ca) dùng UPDATE hàng loạt `WHERE status = 'PENDING'` đúng bằng cạnh `PENDING → REJECTED`, tự ghi đủ cột (`decision_note`, `closed_at`, `updated_at`), khuôn ADR-0079.
8. **Cột `updatable = false`:** phân ca: `branch_id`, `staff_id`, `work_date`, `source` (`source` quyết định ca nào nhân viên được tự hủy ở BR-NS-11, nên không đổi được; `shift_definition_id` đổi được vì #5); yêu cầu: `assignment_id`, `requester_id`, `request_type`, `target_shift_definition_id`, `reason`; ca: `branch_id`. Đổi một cột này về sau phải dùng UPDATE riêng như `customer_id` của CareTask (ADR-0079).
9. **Truy vấn cho bước 2:** `existsAnyForDefinition(branchId, definitionId)` thêm `branch_id` để đi qua `ix_shift_assignments_branch_date` (V36 chỉ có index riêng cho `SCHEDULED`), **không thêm V57**; `countScheduledFrom` đi qua `ix_shift_assignments_definition_scheduled`. `existsByBranchAndName` dùng `lower(name)` cùng biểu thức với `ux_shift_definitions_branch_name` và `CAST(:excludeId AS bigint)` để PostgreSQL suy kiểu khi tham số NULL.
10. **Nghĩa vụ cho T59 về khóa:** luồng phân ca / đăng ký tuần phải khóa `shift_definitions` bằng `FOR SHARE` theo `id` tăng dần rồi **đọc lại `is_active`** (ADR-0123 mục 8). `FOR SHARE` tường minh là bắt buộc: lúc chèn phân ca, kiểm FK chỉ lấy `FOR KEY SHARE`, không xung đột với `FOR NO KEY UPDATE` của `PATCH` ca. Repository bước 1 chưa có phương thức này (YAGNI, T59).

## Hệ quả và kiểm chứng

- `ShiftAssignmentFsmIT` (era 2042), `ShiftRequestFsmIT` (2043), `WorkforceRepositoryIT` (2044): mọi cạnh hợp lệ đi qua handler của context rồi entity và **DB chấp nhận**; mọi cặp không hợp lệ bị từ chối, dòng không đổi. Khác `CareTaskFsmIT`: không đi qua endpoint (chưa có), trạng thái nguồn chèn bằng SQL.
- Lần đầu `ddl-auto=validate` kiểm V36 + V55 với entity (cột `source`).
- Chưa quyết ở đây: cách khóa của `POST` / `PATCH /shift-definitions` (khóa dòng ca `FOR NO KEY UPDATE`, hướng A hay A′ dùng `FOR UPDATE`; không khóa `branches`). Chốt ở bước 2, bổ sung vào ADR này.
