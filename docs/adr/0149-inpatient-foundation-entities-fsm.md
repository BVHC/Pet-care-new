# ADR-0149: Nội trú — nền module `inpatient` (T69 N1)

- **Trạng thái:** Proposed (BE-1, 2026-11-22). Cộng dồn mục theo từng bước N của T69, như ADR-0138 của workforce; N1 = mục 1–10.
- **Ngày:** 2026-11-22
- **Liên quan:** ADR-0148 (chốt đặc tả nội trú; không sửa), ADR-0124, `docs/09` §C (BR-LT-13…18, FSM #24, #25), `docs/api/inpatient-v1`, V38, V62, ADR-0107 (`DocumentCodeGenerator`), ADR-0138 (khuôn entity + FSM), nợ D078, D064

## Bối cảnh

Schema nội trú đã có từ V38, đặc tả đã chốt ở ADR-0148, nhưng chưa có Java (D064). N1 dựng phần nền để N2+ (nhập / xuất viện, y lệnh, theo dõi, 10 endpoint) chỉ còn viết service: entity + repository cho 3 bảng, enum, mã ca, hai FSM, cặp mẫu thông báo. **Không có** endpoint, service, audit hay thông báo gửi đi ở N1.

## Quyết định

1. **V62 thêm `ck_admissions_source`:** `CHECK ((boarding_booking_id IS NULL) <> (kennel_id IS NULL))` — đúng một nguồn (BR-LT-13: nhập từ khám có chuồng điều trị, nhập từ lưu trú có đặt chỗ). V38 chưa có; bảng rỗng ở mọi DB nên không cần dọn dữ liệu. `Admission.admit` kiểm cùng điều kiện; DB là lớp chặn khi service sót. Lệch so với `09` §C, ghi ở `05` §13 mục 27.
2. **Hủy y lệnh không lưu người hủy và lúc hủy (nợ D078):** V38 chỉ có `cancel_reason`; ADR-0148 cố ý chưa mở rộng audit (BR-QT-15) cho nội trú. Chấp nhận: người hủy suy ra từ VET đang được gán Visit (BR-LT-15); gán lại Visit (BR-TN-08) làm mất dấu người hủy cũ; `updated_at` là mốc gần đúng. Không đổi schema, `09`, `inpatient-v1`.
3. **Mẫu thông báo `INPATIENT_OBSERVATION_ABNORMAL` (EMAIL) / `_APP` (IN_APP), V62:** biến bắt buộc `ten_thu_cung`, `chi_nhanh`, `ma_noi_tru`, `ngay_ghi` (dd/MM/yyyy); `ten_khach` tùy chọn (khuôn `CARE_LOG_ABNORMAL`, V24). Giả định của BE-1: ADR-0148 mục 4 chưa nói biến nào bắt buộc. Bản `_APP` viết trung tính vì khách và VET phụ trách cùng đọc. Hằng ở `NotificationTemplateCode`.
4. **Chỗ ghi quyết định code:** ADR này (không thêm vào ADR-0148, vốn là chốt đặc tả). Mục mới của N2+ thêm vào cuối file này.
5. **Mã ca:** `Admission.CODE_DOC_TYPE = "admissions"`, `CODE_PREFIX = "NT"` → `NT-yyMM-xxxxx` (ADR-0148 mục 7, ADR-0107). Entity nhận `code` đã sinh; service N2 gọi `DocumentCodeGenerator.next(...)` **sau cùng** trong transaction. Không thêm bean bọc generator.
6. **Bảng LOG `admission_observations`:** extends `CreatedAtEntity`, mọi cột `updatable = false`, không method đổi (khuôn `CareLogAddendum`). Repository vẫn là `JpaRepository` (quy ước "không gọi `delete`", test chặn method public lạ trên entity).
7. **Repository tối thiểu:** chỉ `findByIdForUpdate` (`FOR NO KEY UPDATE`, `MANDATORY`) cho `Admission` và `AdmissionOrder`. Câu đọc (theo thú, theo Visit, danh sách, `hasActiveAdmission`) do N2 thêm cùng test của nó.
8. **Chỉ số theo dõi không có khoảng lâm sàng (TBD):** inpatient-v1 #9 trả 400 "giá trị ngoài khoảng" nhưng không tài liệu nào ghi khoảng. Entity chỉ chặn giới hạn có nguồn: cân nặng &gt; 0 sau làm tròn (BR-KH-04) và vừa `NUMERIC(6,2)`; nhiệt độ vừa `NUMERIC(4,1)`; nhịp tim / thở 1…32 767 (`SMALLINT`). Nhiệt độ 90 °C vẫn lưu được. Muốn chặn theo lâm sàng thì cần PO chốt khoảng (`inpatient-v1` §E).
9. **`Admission.discharge(…, orderedCount)`:** điều kiện "còn y lệnh `ORDERED`" (BR-LT-14) là nhiều dòng; service N2 đếm dưới khóa (`admissions` → `admission_orders`) rồi ném 400 `BR-LT-14`, entity chặn thêm một lớp (`IllegalStateException`).
10. **Bản ghi con nhận entity cha:** `AdmissionOrder.order(Admission, …)` và `AdmissionObservation.record(Admission, …)` gọi `admission.requireAdmitted()` và cần `admission.getId()` (ca phải đã lưu) — không tạo được y lệnh hay chỉ số cho ca đã xuất viện kể cả khi service quên (409 của #5, #9).

## Ai giữ bất biến nào

| Bất biến | Entity (N1) | DB | Service (N2) |
|---|---|---|---|
| Đúng một nguồn; giá ≥ 0; mã ≤ 20 ký tự; cột nhập viện không đổi | ✔ | `ck_admissions_source` (V62), `ck_admissions_unit_price` | 400 `BR-LT-13` thiếu / thừa `kennelId` |
| Xuất viện chỉ từ `ADMITTED`, một lần; tóm tắt không rỗng (strip); lúc xuất ≥ lúc nhập; không còn y lệnh `ORDERED` | ✔ | `ck_admissions_discharge_*` | 400 `BR-LT-14`, 409 |
| Số ngày tính tiền (ngày VN, gồm hai đầu, tối thiểu 1, đứng yên sau xuất viện), tiền tạm tính (`multiplyExact`) | ✔ `billedDays`, `estimatedAmount` | — | dùng cùng hàm cho dòng Order lúc xuất và API đọc |
| Mỗi thú một ca `ADMITTED` | — | `ux_admissions_pet_admitted` | 409 |
| Visit `IN_PROGRESS` của đúng VET, đặt chỗ `CHECKED_IN` / `OVERDUE`, chuồng `AVAILABLE` cùng chi nhánh, dịch vụ `INPATIENT`, thú chưa mất | — | — | ✔ qua `*Api`, dưới khóa |
| Y lệnh: `MEDICATION` ⇒ có dòng đơn thuốc; nội dung 1–500; ghi chú / lý do 1–300; hoàn thành / hủy chỉ từ `ORDERED`; chỉ tạo khi ca `ADMITTED` | ✔ | `ck_admission_orders_*` | 400 / 409; dòng đơn thuộc Visit của ca (qua `PrescriptionApi`) |
| Y lệnh thuốc chỉ VET thực hiện | ✔ `requiresVet()` (một nguồn) | — | 403 theo vai trò |
| Chỉ số: ≥ 1 chỉ số hoặc ghi chú; cân &gt; 0; vừa cột; ghi chú ≤ 500; chỉ ghi khi ca `ADMITTED`; chỉ thêm | ✔ | kiểu cột, `ck_admission_observations_appetite` | 400 `BR-LT-16`; thông báo khi `abnormal`; ghi hồ sơ cân |

## Thử nghiệm (N1)

- Unit: `AdmissionTransitionHandlerTest`, `AdmissionOrderTransitionHandlerTest` (`FsmTransitionTestBase`, mọi cặp và mọi trạng thái khởi tạo, bản sao độc lập của `09` §C); `AdmissionTest`, `AdmissionOrderTest`, `AdmissionObservationTest` (một ca đúng và một ca sai cho mỗi bất biến cột "Entity").
- IT bean thật: `AdmissionFsmIT` (era 2093), `AdmissionOrderFsmIT` (era 2094): cạnh hợp lệ qua handler + entity + DB; cặp sai bị từ chối, dòng không đổi; `ck_admissions_source`, `ux_admissions_pet_admitted`, CHECK của y lệnh, mã `NT-yyMM-xxxxx` liên tục và trả lại khi rollback, khóa ngoài transaction bị từ chối.
- Sửa test có sẵn vì V62: `Tier3Be1SchemaIT` (ba fixture `admissions` thêm chuồng; thêm `admissionHasExactlyOneSource`), `SchemaMigrationIT` (danh sách version, danh sách mẫu).
- Đã chạy 22/11/2026: 58 unit nội trú, `AdmissionFsmIT` 12, `AdmissionOrderFsmIT` 14, `SchemaMigrationIT` 29, `Tier3Be1SchemaIT` 13, `NotificationTemplateSeedIT` 1, `NotificationTemplateAdminIT` 32 — xanh.

## Hệ quả

- Số Flyway kế tiếp V63, ADR kế tiếp 0150, era IT kế tiếp 2095.
- N2 phải kiểm trước mọi điều kiện ở cột "Service" để trả đúng 400 / 403 / 409; lỗi của entity (`IllegalArgumentException`, `IllegalStateException`, NPE) lọt ra HTTP sẽ là 500.
- Phần FE (D064, D065): V62 thêm 2 mẫu thông báo, seed FE cần thêm khi cập nhật.
