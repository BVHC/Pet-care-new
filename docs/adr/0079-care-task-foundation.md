# ADR-0079: Nền Care Task (`CareTask`, FSM, `CareTaskApi` đủ) — chống trùng quá hạn, hủy do hệ thống, nghĩa vụ bên gọi

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-27
- **Liên quan:**
  - BR-TB-02…06, BR-LT-10, BR-KH-05, 08; 03 §10 (Care Task#1–6); erd §11 `care_tasks`; 06 §8 Q1.
  - ADR-0033 (tên FSM trong 409), 0074 (`cancelForRevaccination`), 0075 (task trỏ tới mũi qua FK), 0076 (khuôn entity), `06-module-contracts` §6.
  - Nợ: D047 (trả), D049 (mới), D029.
- **Triển khai (T39 phần 0):** `care/entity/{CareTask, CareTaskStatus, CareTaskResult}`, `care/fsm/CareTaskTransitionHandler`, `care/repository/CareTaskRepository` (thay `CareTaskWriteRepository`), `CareTaskService` đủ ba method của `CareTaskApi`. Không endpoint, không migration, không job, không listener. Test: `CareTaskTransitionHandlerTest` (13), `CareTaskTest` (5), `CareTaskServiceTest` (10), `CareTaskApiIT` (14), `CareTaskConstraintIT` (9), `CareTaskPersistenceIT` (2).
- **Chưa làm (T39 phần sau):** care-v1 #1–4 (lễ tân xem / hoàn tất / hủy), ST04, ST18, ST19, listener thú mất / đổi chủ phía care, `FollowUpApi` (D029).

## Quyết định

1. **Entity `CareTask` (ROOT).** Chỉ giữ id, không `@ManyToOne`. Nhóm cột định danh (`branch_id`, `customer_id`, `pet_id`, `task_type`, 3 cột nguồn, `due_date`) là `updatable = false`: Hibernate UPDATE mọi cột còn lại, nên một lần lưu entity không được ghi đè `customer_id` mà luồng chuyển chủ (06 §8 Q1) đổi bằng UPDATE riêng. Chỉ 6 cột xử lý (`status`, `result`, `note`, `cancel_reason`, `handled_by`, `handled_at`) đổi được qua entity (care-v1 #3 / #4, phần sau). Một đường tạo duy nhất `CareTask.open(NewCareTask)`: đặt `sourceId` vào đúng một cột theo loại (khớp `ck_care_tasks_target`), `OPEN`, 5 cột xử lý NULL.
2. **FSM** `CareTaskTransitionHandler`: `— → OPEN`; `OPEN → DONE` (#2); `OPEN → CANCELLED` (#3 lễ tân, #4 thú mất, #5 tiêm lại, #6 nhắc tái khám bị hủy). `DONE` / `CANCELLED` là FINAL, không cạnh `X → X`. Message 409 dùng tên nghiệp vụ "việc chăm sóc khách".
3. **Hủy do hệ thống (#4–#6) là một câu UPDATE có điều kiện** `WHERE status = 'OPEN' AND task_type … AND <cột nguồn> IN (:ids)`; điều kiện đúng bằng cạnh `OPEN → CANCELLED`. Câu tự khóa dòng; chạy song song với lễ tân hoàn tất thì READ COMMITTED kiểm lại `status` sau khi chờ khóa, task đã `DONE` không bị hủy. Ghi `cancel_reason` (câu tiếng Việt + mã rule), `handled_at` = lúc hủy, `updated_at`; `handled_by` **NULL = hệ thống**. Ba câu: `"Đã tiêm lại (BR-TB-03)"` (giữ nguyên chữ của ADR-0074), `"Đã có lịch khám / lượt khám mới (BR-TB-06)"`, `"Thú cưng đã mất (BR-KH-05)"` (phần ST19 thêm hằng). `cancelForRevaccination` thêm `handled_at`.
4. **`create` chống trùng bằng `INSERT … ON CONFLICT (…) WHERE task_type = '…' DO NOTHING` rồi SELECT id** cho `VACCINE_OVERDUE` (tối đa 1 / mũi, BR-TB-04) và `PICKUP_OVERDUE` (tối đa 1 / đặt chỗ, erd [ERD]). Trả **id cũ dù task cũ ở trạng thái nào** (unique index không phân biệt trạng thái; 06 §8 Q1). Không dùng `save` rồi bắt lỗi trùng: vi phạm unique làm PostgreSQL hủy cả transaction của bên gọi. `VACCINE_DUE` và `FOLLOW_UP_DUE` **không** chống trùng ở DB (erd không có unique): ST04 tự chống bằng `due_reminded_at` / `follow_up_reminded_at` đặt cùng transaction. Hai loại này chèn bằng `saveAndFlush` (lỗi FK / CHECK nổi ngay trong lời gọi).
5. **Care không kiểm gì thuộc module khác** (06 §1: không đọc bảng của module khác). Nghĩa vụ bên gọi `create` (javadoc `CareTaskApi`):
   - **Khóa thú:** `pets FOR SHARE` (`PetQueryApi.findPetForShare`) **trước** dòng nguồn; kiểm thú chưa mất và `customerId` là chủ hiện tại. `PICKUP_OVERDUE` miễn (thú đang lưu trú không đánh dấu mất / chuyển chủ được).
   - **Isolation:** READ COMMITTED; ở REPEATABLE READ, `ON CONFLICT` gặp dòng vừa commit sẽ ném lỗi serialization.
   - **Ý nghĩa cột:** `branchId` (BR-TB-02, BR-LT-10), `petId` / `customerId` khớp nguồn, `dueDate` (erd §11); điều kiện sinh theo kênh (DUE chỉ khi không email; OVERDUE khi quá hạn 7 ngày, chưa tiêm lại, có SĐT; PICKUP_OVERDUE khi `OVERDUE` ≥ 1 ngày); `VACCINE_*` chỉ cho mũi của lượt `COMPLETED` (ADR-0075).
   - Không `catch`; mọi method `MANDATORY`.
6. **Không `create` tự khóa thú:** khóa `pets` sau dòng nguồn mà ST04 đã giữ sẽ đảo chiều với luồng thú mất (`pets → medical_records`) và gây deadlock.
7. **Không phân trang, không N+1:** không method nào trả danh sách. `create` = 1 câu (DUE) hoặc 2 (OVERDUE); mỗi `cancel*` = 1 câu, 0 khi tập rỗng. Danh sách `GET /care-tasks` thuộc phần sau (REPEATABLE READ cho count + page, sắp `due_date, id`, index `ix_care_tasks_branch_status_due` đã khớp).
8. **Không audit, không outbox** (BR-QT-15 không liệt kê Care Task).

## Ràng buộc của `care_tasks` (ai giữ)

| Nhóm | Ràng buộc | DB giữ? | Ứng dụng giữ / test |
|---|---|---|---|
| Ca | NN 6 cột; đúng 1 nguồn theo loại; `DONE ⇔ result`; unique 2 loại quá hạn; giá trị enum ⊆ CHECK; `cancel_reason` ≤ 300 | ✅ V1:1025-1057 | Factory + `CareTaskConstraintIT` (kể cả: tập giá trị CHECK = enum Java) |
| Cb | `OPEN` không có dữ liệu xử lý; `CANCELLED` có lý do + `handled_at`; `DONE` có kết quả, ghi chú, người xử lý; FINAL không mở lại | ❌ (chỉ `DONE ⇔ result`) | `WHERE status = 'OPEN'` trong mọi UPDATE; câu bất biến cuối mỗi ca `CareTaskApiIT`. **D049** |
| Cc | `branch_id`, `pet_id` / `customer_id` khớp nguồn, `due_date`, điều kiện sinh theo kênh, không sinh cho thú đã mất, lượt `COMPLETED`, lễ tân thuộc `branch_id` | ❌ | Bên gọi (phần ST04 / ST18 / ST19, T43) và care-v1 #1–4 (lễ tân) |

## Bảng đảm bảo

| # | Đảm bảo | Test |
|---|---|---|
| G1 | FSM đúng mọi cặp 03 §10 | `CareTaskTransitionHandlerTest` |
| G2 | `create` ghi đúng cột 4 loại, `created_at` / `updated_at` = Clock, native và entity ghi cùng cột | `CareTaskApiIT` `createsAnOpenTask…`, `theNativeInsert…` |
| G3 | Chống trùng: OVERDUE trả id cũ ở cả 3 trạng thái, không UPDATE (`xmin`); DUE không chống trùng | `CareTaskApiIT` `anExistingOverdueTask…`, `dueTypesAreNotDeduplicated` |
| G4 | Hai bên cùng `create` OVERDUE: một dòng, cùng id (bên sau chờ INSERT bên trước) | `CareTaskApiIT` `twoCallers…` |
| G5 | Ma trận trạng thái × loại × nguồn của hai câu hủy: chỉ đúng ô chuyển, ô khác giữ `xmin` | `CareTaskApiIT` `revaccinationCancels…`, `followUpCancellation…` |
| G6 | Rollback theo bên gọi; lỗi FK hủy cả transaction; ngoài transaction bị từ chối | `CareTaskApiIT` |
| G7 | Tra id sau `ON CONFLICT` đi qua unique index | `CareTaskApiIT` `theLookupAfterTheInsert…` |
| G8 | Ánh xạ 17 cột; lưu entity không ghi đè `customer_id` | `CareTaskPersistenceIT` |
| G9 | Ràng buộc DB còn hiệu lực với entity mới | `CareTaskConstraintIT` |
| G10 | #19 vẫn hủy task tiêm lại, chữ lý do cũ, rollback trigger | `VaccinationRecordIT`, `VaccinationDeleteIT` (không sửa) |

## Hệ quả

- **BE-2 làm được ST15** (T43): `create(PICKUP_OVERDUE)` có thật. Nghĩa vụ ở `06-module-contracts` §6 và javadoc.
- **Hành vi hiện có không đổi** ngoài `handled_at` trên task bị hủy khi tiêm lại.
- **Phần sau:** care-v1 #3 / #4 nạp entity dưới khóa dòng **trong transaction riêng** (câu UPDATE hàng loạt không đồng bộ persistence context); phần thú mất / đổi chủ khóa `pets` trước khi UPDATE và chứng minh không deadlock với `cancelFollowUpTasks`; sửa erd §11 `customer_id` ("chủ tại thời điểm sinh task" → chủ hiện tại, 06 §8 Q1).
- **Nợ mới D049:** thiếu index `care_tasks(vaccination_id)` (cho `VACCINE_DUE`) và `(medical_record_id)`; thiếu CHECK trạng thái ↔ cột xử lý.

## Đã cân nhắc

- **`save` rồi bắt lỗi trùng** — vi phạm unique làm transaction của bên gọi `aborted`; `ON CONFLICT DO NOTHING` không có hiệu ứng đó.
- **Hủy qua entity từng dòng** (khóa, `validateTransition`, `cancel()`) — N+1 câu, thêm khóa cần chứng minh với luồng ghi mũi tiêm; câu UPDATE có điều kiện + ma trận IT cho cùng đảm bảo.
- **`create` tự khóa thú** — deadlock như mục 6.
- **Mã máy cho `cancel_reason`** (`SYS_REVACCINATED`…) — cột còn chứa lý do tự do của lễ tân; trộn hai kiểu dữ liệu, phải sửa 2 IT và FE phải map.
- **Migration index / CHECK ngay** — cần chốt V22 với BE-2; để D049.
