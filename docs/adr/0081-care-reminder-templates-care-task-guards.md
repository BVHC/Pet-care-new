# ADR-0081: V22 — mẫu nhắc tái chủng / tái khám, index và CHECK của `care_tasks`

- **Trạng thái:** Proposed — test đã sửa và chạy xanh ở T39 phần 3 (ADR-0082, 2026-10-27); chuyển Accepted khi BE-2 xác nhận số V22.
- **Ngày:** 2026-10-27
- **Liên quan:** BR-QT-14, BR-TB-01, 02, 05, 06, BR-KB-04; 06 §8 Q4; ADR-0014 (`_APP`), 0037 (ngữ pháp `{var}`), 0056 (khuôn V18), 0079 (ý nghĩa cột xử lý). Nợ: D049 (trả bằng V22), D043 (giữ V23).
- **Triển khai (T39 phần 2):** chỉ `BE/src/main/resources/db/migration/V22__care_reminder_templates_care_task_guards.sql`. Không đổi Java ngoài chú thích; test đã sửa ở phần 3 (danh sách ở *Hệ quả*).

## Quyết định

1. **Bốn mẫu**, mỗi mã một kênh (Q4), `body = default_body`, `subject = default_subject`:

   | Mã | Bắt buộc | Không bắt buộc |
   |---|---|---|
   | `VACCINE_REMINDER` (EMAIL), `_APP` | `ten_thu_cung`, `loai_vaccine`, `ngay_tai_chung`, `chi_nhanh`, `so_dien_thoai_chi_nhanh` | `ten_khach` |
   | `FOLLOW_UP_REMINDER` (EMAIL), `_APP` | `ten_thu_cung`, `ngay_tai_kham`, `chi_nhanh`, `so_dien_thoai_chi_nhanh` | `ten_khach` |

   `loai_vaccine` = tên `VaccineType` (BR-SP-07). Ngày dạng `dd/MM/yyyy`. `chi_nhanh` = chi nhánh phụ trách BR-TB-02 (tái chủng: chi nhánh mũi gần nhất cùng loại; tái khám: chi nhánh Visit có hẹn). Mỗi mũi / mỗi bệnh án một thông báo (cờ `due_reminded_at`, `follow_up_reminded_at`). **Link đặt lịch điền sẵn chỉ ở bản `_APP`, qua `NotificationRequest.link`, không phải biến**; email không link (tiền lệ V18; hồ sơ tại quầy không đăng nhập được). Bên gửi (ST04) tự kiểm `required_vars` vì `enqueue` không kiểm.
2. **Index không lọc `status` / `task_type`:** `ix_care_tasks_vaccination_id (vaccination_id) WHERE vaccination_id IS NOT NULL`, `ix_care_tasks_medical_record_id (medical_record_id) WHERE medical_record_id IS NOT NULL`. Ngoài hai câu hủy hệ thống, Postgres kiểm FK `RESTRICT` khi xóa mũi ghi nhầm (#20) bằng `WHERE vaccination_id = $1`; `uq_care_tasks_vaccine_overdue` (partial theo `task_type`) không dùng được cho câu đó.
3. **Ba CHECK** chép bất biến `CareTaskItSupport.invariantViolations` (`result` đã do `ck_care_tasks_done_result` giữ):
   - `ck_care_tasks_open_unhandled`: `OPEN` → `note`, `cancel_reason`, `handled_by`, `handled_at` NULL.
   - `ck_care_tasks_done_handled`: `DONE` → có `note`, `handled_by`, `handled_at`; không `cancel_reason`.
   - `ck_care_tasks_cancelled_reason`: `CANCELLED` → có `cancel_reason`, `handled_at`; `handled_by` NULL được (hệ thống hủy).
   Vi phạm → `DataIntegrityViolationException` → 409 chung. Luồng ứng dụng không chạm (`CareTask.complete / cancel`, `cancelOpen*` ghi đủ cột).

## Hệ quả

- **Đã sửa ở phần 3 (cùng PR), xanh:** `SchemaMigrationIT` (thêm `"22"`, 4 mã mẫu; kiểm index / constraint); `CareTaskConstraintIT` (INSERT thêm `note, handled_by, handled_at`; ca cho từng nhánh CHECK); `CareTaskPersistenceIT` (CANCELLED đặt `handledAt`); `visit/VaccinationItSupport.careTask` (DONE: `note`, `handled_by = v.recorded_by`, `handled_at`; CANCELLED: lý do + `handled_at`) → `VaccinationRecordIT`, `VaccinationDeleteIT`. `EXPLAIN` hai câu hủy + câu kiểm FK trong `CareTaskApiIT` (helper `explain`).
- **DB dev có dòng ghi tay sai bất biến** → V22 lỗi khi khởi động. Tìm bằng câu bất biến (bỏ lọc `pet_id`), sửa / xóa dòng đó (hỏi trước), khởi động lại.
- Câu quét của ST04 cần index mới thì thêm **vào chính V22** trước khi gộp (cùng PR, chưa chạy ở DB chung).

## Phương án đã loại

- **Index partial `WHERE status = 'OPEN'`:** nhỏ hơn nhưng không phục vụ kiểm FK khi xóa mũi.
- **Gộp CHECK `prescription_items` (D043):** khác module, có test riêng (`knownGapD043…`) → V23.
- **Link trong email:** cần URL gốc của FE ở BE, vô dụng với hồ sơ tại quầy.
