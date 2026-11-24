# ADR-0149: Nội trú — nền module `inpatient` (T69 N1)

- **Trạng thái:** Proposed (BE-1, 2026-11-22). Cộng dồn mục theo từng bước N của T69, như ADR-0138 của workforce; N1 = mục 1–10; N2 (2026-11-23, interface chéo của BE-1) = mục 11–15; N3 (2026-11-23, inpatient-v1 #1–#3 + guard Visit#5) = mục 16–21; N4 (2026-11-24, xuất viện #4) = mục 22–25; N5 (2026-11-24, y lệnh #5–#8) = mục 26–30; N6 (theo dõi #9–#10) = mục 31–36; N7 (2026-11-24, guard lưu trú BR-LT-17, chặn dịch vụ nội trú BR-LT-18, chuỗi thú mất) = mục 37–41.
- **Ngày:** 2026-11-22
- **Liên quan:** ADR-0148 (chốt đặc tả nội trú; không sửa), ADR-0124, `docs/09` §C (BR-LT-13…18, FSM #24, #25), `docs/api/inpatient-v1`, V38, V63, ADR-0107 (`DocumentCodeGenerator`), ADR-0138 (khuôn entity + FSM), nợ D078, D079, D080, D064

> Khi ghép PR #34, migration nội trú đổi từ V62 sang V63 vì V62 đã dành cho ảnh sản phẩm (ADR-0151). Nội dung SQL giữ nguyên. DB riêng đã áp migration nội trú dưới số V62 cần khởi tạo lại từ dump V61 trước khi dùng nhánh này.

## Bối cảnh

Schema nội trú đã có từ V38, đặc tả đã chốt ở ADR-0148, nhưng chưa có Java (D064). N1 dựng phần nền để N2+ (nhập / xuất viện, y lệnh, theo dõi, 10 endpoint) chỉ còn viết service: entity + repository cho 3 bảng, enum, mã ca, hai FSM, cặp mẫu thông báo. **Không có** endpoint, service, audit hay thông báo gửi đi ở N1.

## Quyết định

1. **V63 thêm `ck_admissions_source`:** `CHECK ((boarding_booking_id IS NULL) <> (kennel_id IS NULL))` — đúng một nguồn (BR-LT-13: nhập từ khám có chuồng điều trị, nhập từ lưu trú có đặt chỗ). V38 chưa có; bảng rỗng ở mọi DB nên không cần dọn dữ liệu. `Admission.admit` kiểm cùng điều kiện; DB là lớp chặn khi service sót. Lệch so với `09` §C, ghi ở `05` §13 mục 27.
2. **Hủy y lệnh không lưu người hủy và lúc hủy (nợ D078):** V38 chỉ có `cancel_reason`; ADR-0148 cố ý chưa mở rộng audit (BR-QT-15) cho nội trú. Chấp nhận: người hủy suy ra từ VET đang được gán Visit (BR-LT-15); gán lại Visit (BR-TN-08) làm mất dấu người hủy cũ; `updated_at` là mốc gần đúng. Không đổi schema, `09`, `inpatient-v1`.
3. **Mẫu thông báo `INPATIENT_OBSERVATION_ABNORMAL` (EMAIL) / `_APP` (IN_APP), V63:** biến bắt buộc `ten_thu_cung`, `chi_nhanh`, `ma_noi_tru`, `ngay_ghi` (dd/MM/yyyy); `ten_khach` tùy chọn (khuôn `CARE_LOG_ABNORMAL`, V24). Giả định của BE-1: ADR-0148 mục 4 chưa nói biến nào bắt buộc. Bản `_APP` viết trung tính vì khách và VET phụ trách cùng đọc. Hằng ở `NotificationTemplateCode`.
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
| Đúng một nguồn; giá ≥ 0; mã ≤ 20 ký tự; cột nhập viện không đổi | ✔ | `ck_admissions_source` (V63), `ck_admissions_unit_price` | 400 `BR-LT-13` thiếu / thừa `kennelId` |
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
- Sửa test có sẵn vì V63: `Tier3Be1SchemaIT` (ba fixture `admissions` thêm chuồng; thêm `admissionHasExactlyOneSource`), `SchemaMigrationIT` (danh sách version, danh sách mẫu).
- Đã chạy 22/11/2026: 58 unit nội trú, `AdmissionFsmIT` 12, `AdmissionOrderFsmIT` 14, `SchemaMigrationIT` 29, `Tier3Be1SchemaIT` 13, `NotificationTemplateSeedIT` 1, `NotificationTemplateAdminIT` 32 — xanh.

## Quyết định N2 — interface chéo của BE-1 (2026-11-23, Mạnh chốt Q1–Q3)

11. **Visit#7 trong FSM:** `VisitTransitionHandler.initialStates() = {WAITING, IN_PROGRESS}` (khuôn Order#5, convention 05 L59). Mỗi đường tự `validateInitial` trạng thái của mình: `VisitCheckInService` chỉ `WAITING`, `TreatmentVisitService` chỉ `IN_PROGRESS`.
12. **`visit.api.VisitApi.startTreatmentVisit(petId, customerId, branchId, vetId)` → `TreatmentVisitRef(visitId, orderId)`** (`MANDATORY`, lớp cài `TreatmentVisitService`). Chữ ký khác `06` §6b hàng 10 cũ `(petId, vetId, boardingBookingId)`:
    - bỏ `boardingBookingId` vì `visits` không có cột nào cho nó;
    - inpatient (N3) đã đọc đặt chỗ dưới khóa và tự kiểm BR-LT-13b, nên visit **không kiểm lại** (một nơi giữ luật, khuôn `openTreatmentOrder` của N0); visit chỉ kiểm null và FSM.

    Lượt tạo ra (`Visit.startTreatment`, giả định của BE-1):
    - `MEDICAL` (V41), `WALK_IN` (không bao giờ ở `WAITING` nên hạng không ảnh hưởng hàng đợi);
    - `queue_sort_key = checked_in_at = called_at = now` (µs);
    - `checked_in_by = assignee_id = vetId`, `appointment_id` NULL.

    Sau đó gọi `VisitOrderApi.openTreatmentOrder` (Order#14, không dòng). Không audit, không thông báo; bệnh án tạo khi VET ghi lần đầu, như lượt thường.

    Khóa: chuỗi Visit sẵn có, `document_counters` (visits → ORDER) → `orders`; bên gọi đã giữ `pets FOR SHARE`.
13. **`inpatient.api.AdmissionQueryApi`:** `hasActiveAdmission(petId)` (boarding, BR-LT-17) và `hasActiveAdmissionForVisit(visitId)` (Visit#5, BR-LT-14).
    - Lớp cài `AdmissionQueryService`; `MANDATORY`, đọc không khóa, READ COMMITTED.
    - Câu JPQL viết literal `ADMITTED` để dùng `ux_admissions_pet_admitted` / `ix_admissions_visit` (V38).
    - Khai báo cả hai ngay để interface chỉ đổi một lần; **chưa gắn vào luồng nào** (N3).
14. **Không thêm placeholder (Q3):** mọi method BE-2 mà nội trú cần đã là bản thật từ N0. Hai interface mới là bản thật, không có `default`. *(Cập nhật N3, mục 21)* Guard Visit#5 làm ở N3; guard 409 `BR-LT-17` ở `BoardingCheckoutService.handOver` / `endDeceased` (kèm 409 trong `boarding-v1`) để bước sau, trong PR riêng cho BE-2 duyệt như N0. *(Đã làm ở N7, mục 37–39.)*
15. **Race nhập viện từ lưu trú ↔ giao thú / thú mất:** đã chốt ở mục 16 (khóa đặt chỗ `FOR SHARE`).

## Quyết định N3 — nhập viện và đọc ca (inpatient-v1 #1–#3) + guard Visit#5 (2026-11-23, Mạnh chốt)

16. **Thứ tự khóa của nhập viện** (cả hai đường lấy `pets FOR SHARE` đầu tiên; bộ đếm mã sau cùng, mục 5):
    - (a) từ lượt khám: `pets FOR SHARE → visits FOR NO KEY UPDATE` (`VisitApi.lockVisitForAdmission`, mới) `→ kennels → document_counters(admissions) → admissions`;
    - (b) từ lưu trú: `pets FOR SHARE → boarding_bookings FOR SHARE` (`BoardingQueryApi.findBookingForShare`, mới, BE-2) `→ document_counters(visits) → visits → document_counters(ORDER) → orders → document_counters(admissions) → admissions`.

    Hệ quả:
    - Khóa đặt chỗ xung đột với `FOR NO KEY UPDATE` của giao thú / thú mất / gia hạn / hủy, nên guard BR-LT-17 (bước sau) không bị chen.
    - Khóa `visits` trùng khóa của hoàn tất (Visit#5) và gán lại (Visit#4).
    - ST15 (`SKIP LOCKED`) bỏ qua đặt chỗ đang bị khóa và xử lý ở lượt sau.
    - Không có vòng khóa với lưu trú, visit hay customer.
17. **Server lấy mọi cột trừ nguồn / dịch vụ / chuồng:**
    - thú, chi nhánh, khách lấy từ dòng đã khóa;
    - `unit_price` = giá dịch vụ lúc nhập (BR-BH-03);
    - người nhập = người gọi;
    - lúc nhập = đồng hồ cắt µs.

    Response ghép tên thú / dịch vụ / mã chuồng **hiện tại** (chỉ giá là snapshot, A6 của inpatient-v1). `billedDays` / `estimatedAmount` tính bằng một `now` cho cả request.
18. **Thứ tự lỗi và 409 trùng ca:** 400 hình thức → 403 vai trò → 400 `BR-LT-13` nguồn (chưa chạm DB) → 404 lượt / đặt chỗ → 403 chi nhánh → khóa → 409 trùng ca → 400 `BR-LT-13` điều kiện → 404 / 400 dịch vụ → 404 / 400 chuồng.
    - Khuôn "409 trước 400" của lưu trú (ADR-0085) và visit (ADR-0069).
    - Trùng ca tuần tự: `INVALID_STATE_TRANSITION`.
    - Hai nhập viện đồng thời thật: bên thua chờ ở `ux_admissions_pet_admitted` rồi nhận `CONCURRENCY_CONFLICT` (khuôn D070); đúng một bên thành công, không thêm khóa.
    - "Không phải lượt của mình" là 400 `BR-LT-13` theo hợp đồng (visit #10 dùng 403).
19. **Giả định của BE-1 (ghi ở inpatient-v1 A5, A6):**
    - dịch vụ phải `active` và đang bật tại chi nhánh của ca (cùng nghĩa với walk-in; BR-LT-13 chỉ nói loại `INPATIENT`);
    - đường (a) chỉ nhận lượt nhóm `MEDICAL`;
    - danh sách #2 lấy `kennelCode` bằng hai hàm đọc theo lô mới ở `KennelApi` (BE-2): theo `kennelId` (a) và theo `bookingId` (b). Cả hai `MANDATORY` như mọi hàm của `KennelApi`.

    Thiếu tên thú / dịch vụ / mã chuồng của một dòng là dữ liệu hỏng → `IllegalStateException`: không có xóa cứng, và `kennel_id` của đặt chỗ không bao giờ bị xóa.
20. **Phân quyền:** #1 chỉ `VET`; #2, #3 `RECEPTIONIST`, `VET`, `CARETAKER` (BRANCH_MANAGER chưa được xem, ADR-0148).
    - `@PreAuthorize` là hàng rào vai trò duy nhất, vì `BranchScope` cho ADMIN / SUPER_MANAGER đi qua.
    - Chi nhánh khác: 403 (`check` sau khi nạp dòng, trước khi khóa).
    - Danh sách luôn là chi nhánh của người gọi (`resolve(null)`, không có tham số chi nhánh).
    - Không audit, không thông báo khi nhập viện (ADR-0148 mục 8).
21. **Guard Visit#5 (BR-LT-14):** `VisitCompletionService` thêm hai thay đổi:
    - 409 khi còn ca `ADMITTED` của lượt. Đọc sau khóa `visits`, sau 409 trạng thái, trước mọi lệnh ghi.
    - Nhánh "mọi dịch vụ là `INPATIENT`" không cần chẩn đoán hay mũi tiêm. Lượt `EXAM + INPATIENT` vẫn cần chẩn đoán.

    *(N7, mục 37–40)* Guard BR-LT-17 ở boarding và chặn `INPATIENT` ở đặt lịch / tiếp nhận / đổi loại dịch vụ đã có; chỉ còn rủi ro của D082 và D079.

Race đã chấp nhận: nhập (a) không khóa "vắng mặt đặt chỗ", nên lễ tân nhận thú lưu trú cùng lúc với nhập viện từ khám có thể để thú giữ hai chuồng (nợ D079, khuôn ADR-0092).

## Quyết định N4 — xuất viện (inpatient-v1 #4, Nhập viện#2) (2026-11-24, Mạnh chốt)

22. **Luồng (`AdmissionDischargeService`, một transaction READ COMMITTED):** 400 `BR-LT-14` tóm tắt rỗng (chưa chạm DB) → khóa `admissions FOR NO KEY UPDATE` rồi **mới** đọc (404) → 403 chi nhánh → 403 người phụ trách Visit → 409 trạng thái → đếm y lệnh `ORDERED` (400 `BR-LT-14` nếu còn) → `VisitOrderApi.addServiceLine(visitId, serviceId, billedDays, unitPrice đã chốt)` → `KennelApi.release` (chỉ đường a, Chuồng#7) → UPDATE `admissions`. Không audit, không thông báo (như nhập viện). `DECEASED` chỉ ghi kết cục; đánh dấu thú mất và kết thúc lưu trú là việc lễ tân làm sau (BR-LT-14).
23. **Chỉ khóa `admissions`, không khóa `visits`** (phương án chọn; phương án kia là `visits → admissions` như nhập viện (a) và Visit#5, thêm một khóa chồng lên). Lý do: người phụ trách Visit chỉ đổi qua gán lại lượt đã gọi (Visit#4, BR-TN-08), chỉ làm được khi VET cũ đã bị khóa tài khoản, tức phiên đã bị hủy; nên VET cũ gần như không thể đang bấm xuất viện cùng lúc. Chấp nhận, nợ **D080** (khuôn D079, ADR-0092). Hai xuất viện cùng ca: bên sau chờ khóa ca rồi thấy `DISCHARGED` → 409, tiền chỉ tính một lần. Thứ tự khóa: `admissions → (đếm admission_orders, không khóa) → orders → kennels`; không luồng nào khóa `orders` / `kennels` rồi mới khóa `admissions`, nên không chu trình. **Ràng buộc cho N5 (ra y lệnh) và N6:** khóa `admissions` **trước** khi INSERT `admission_orders`, nếu không phép đếm `ORDERED` có thể bị vượt mặt.
24. **Người phụ trách và lỗi:** VET khác (kể cả cùng chi nhánh, không phải người đang được gán Visit) → 403 `ACCESS_DENIED_SCOPE_MISMATCH`, thứ tự **trước** 409 (khuôn `VisitCompletionService`); hợp đồng #4 đã có sẵn 403. Tóm tắt `@Size(max = 2000)` đếm trên văn bản thô (hợp đồng sinh lại: `string:2000`); rỗng được kiểm bằng `strip()` ở service thay vì `@NotBlank` (D072-h). Hạn chế đã biết: `strip()` của Java không coi U+00A0 (NBSP) là khoảng trắng, nên tóm tắt chỉ gồm NBSP vẫn qua (entity `Admission.discharge` cùng hành vi); không xử lý.
25. **Dòng nội trú không sửa / xóa tay (BE-2 duyệt, `docs/06` §6b):** sau xuất viện dòng nằm trong Order `OPEN` và là dòng tay của chính VET (`OrderLinePolicy` cho sửa / xóa), nên VET giảm được số ngày hoặc xóa dòng (FK `admissions.order_line_id` RESTRICT chỉ chặn xóa bằng 409 khó hiểu). `OrderLineService.requireNotInpatientLine` (sau `requireAllowed`, cho `updateLine` và `deleteLine`; chỉ dòng `SERVICE` không tự sinh, tra `catalog.findService`): dịch vụ `INPATIENT` → 400 `BR-LT-18`. Số ngày = `Admission.billedDays(now)` (ngày lịch giờ VN, gồm hai đầu, tối thiểu 1); đơn giá = `admissions.unit_price`, không phải giá danh mục lúc xuất; `addServiceLine` không kiểm "đang kinh doanh / bật tại chi nhánh" nên ca nhập trước khi dịch vụ ngừng vẫn xuất viện được.

## Quyết định N5 — y lệnh nội trú (inpatient-v1 #5–#8, Y lệnh nội trú#1–#3) (2026-11-24, Mạnh chốt Q1–Q3)

26. **Luồng và thứ tự lỗi (`AdmissionOrderService`, mỗi hàm một transaction READ COMMITTED, không audit, không thông báo):** 404 → 403 chi nhánh → 403 người phụ trách (#5, #8) hoặc 403 A08 + y lệnh thuốc (#7) → **409 trạng thái → 400 `BR-LT-15`** (mọi lỗi rule, kể cả chuỗi rỗng sau `strip()`). Khác #4 ở chỗ này: xuất viện chặn tóm tắt rỗng *trước* khi chạm DB, y lệnh để rỗng *sau* 409 (khuôn "409 trước 400" của ADR-0069, 0137). Hình thức (null, độ dài, id ≤ 0, kiểu) là 400 `VALIDATION_FAILED` / `MALFORMED_REQUEST` ở Bean Validation, chạy trước `@PreAuthorize`. Response không có người ra / người làm / mốc tạo (hợp đồng không có); `content`, `doneNote`, `cancelReason` trả bản đã `strip()`; `scheduledAt` cắt µs trước khi lưu để khớp DB.
27. **Ba quyết định nghiệp vụ (Mạnh chốt 24/11, hợp đồng `inpatient-v1` giả định A7, bổ sung vào BR-LT-15 ở `09` §C):**
    - *Q1* y lệnh `MEDICATION` chỉ liên kết dòng đơn thuốc **của phòng khám** (có dòng Order): dòng **mua ngoài** (`is_external_purchase`) bị 400 `BR-LT-15`. Lý do: phòng khám chỉ cho uống thuốc nó cấp; thuốc chủ tự mua không có tồn / dòng tiền nên không thuộc y lệnh. Đánh đổi: ca thuốc mua ngoài không có lịch cho uống trong hệ thống (VET ghi bằng loại `OTHER`).
    - *Q2* y lệnh khác `MEDICATION` mà gửi `prescriptionItemId` → 400 `BR-LT-15` (khuôn "cấm `kennelId` khi nhập từ lưu trú"); tránh y lệnh cho ăn chặn xóa dòng đơn qua khóa ngoại `RESTRICT` (visit-v1 #17 trả 409 khó hiểu).
    - *Q3* `scheduledAt` không giới hạn (cho ghi bù y lệnh đã làm, cả quá khứ lẫn xa). Chấp nhận nhập nhầm ngày; không có chuyển `ORDERED → sửa`, cách sửa là hủy rồi ra lại.
    - Dòng đơn không thuộc Visit của ca hoặc không tồn tại → cũng 400 `BR-LT-15` (message "không thuộc lượt khám").
28. **Khóa:**
    - *Ra y lệnh (#5):* `admissions FOR NO KEY UPDATE` (đọc ca **sau** khóa) → `prescription_items FOR KEY SHARE` (`PrescriptionApi.findItemOfVisitForKeyShare`, chỉ `MEDICATION`) → INSERT `admission_orders`. Khóa ca **trước** INSERT là ràng buộc của mục 23: xuất viện đếm `ORDERED` dưới khóa ca nên không thể bị vượt mặt. `FOR KEY SHARE` cùng sức khóa với kiểm tra khóa ngoại nên không thêm xung đột; xung đột với `FOR UPDATE` của xóa dòng đơn (visit-v1 #17) làm bên ra y lệnh chờ rồi thấy "không có" → 400 rõ ràng thay vì 409 `CONCURRENCY_CONFLICT`. Hai lần ra y lệnh cùng ca xếp hàng ở khóa ca, cả hai thành công.
    - *Thực hiện / hủy (#7, #8):* chỉ khóa **dòng y lệnh** `FOR NO KEY UPDATE`; ca đọc không khóa (chi nhánh của ca không đổi). Phương án kia (`admissions → admission_orders`) bị bỏ vì hai lệnh này chỉ làm **giảm** số `ORDERED` nên không phá phép đếm của xuất viện (tệ nhất xuất viện thấy bản cũ → 400, an toàn); không luồng nào khóa `admission_orders` rồi `admissions` nên không có vòng. Ít khóa, điều dưỡng bấm nhiều y lệnh không xếp hàng nhau trên ca.
    - Cả ba chạy READ COMMITTED: sau khi chờ khóa, câu khóa đọc bản mới đã commit nên bên thua nhận 409 `INVALID_STATE_TRANSITION` đúng nghiệp vụ (REPEATABLE READ sẽ ra 409 `CONCURRENCY_CONFLICT` chung chung). `saveAndFlush` là lệnh ghi cuối nên lỗi CHECK / FK hiện ra trong service, trước khi map response.
29. **Phân quyền:** `@PreAuthorize` là hàng rào vai trò **duy nhất** (ADMIN / SUPER_MANAGER đi qua `BranchScope`, mục 20): #5, #8 chỉ `VET`; #7 `VET` + `CARETAKER`; #6 `RECEPTIONIST` + `VET` + `CARETAKER`; BRANCH_MANAGER chưa được xem (ADR-0148). Người phụ trách Visit (#5, #8) và "A08 không thực hiện y lệnh thuốc" (#7, `AdmissionOrder.requiresVet()`) do service kiểm. **Mọi VET của chi nhánh** thực hiện được (#7) kể cả không phải người phụ trách (FSM #25 cạnh #2 ghi "A07; A08 (không phải thuốc)", không ghi "phụ trách Visit" như #1, #3). Luật "VET được gán Visit" tách thành `@Component AdmissionAssignees` dùng chung cho xuất viện (#4) và hai lệnh trên, để một nguồn. Đọc người phụ trách không khóa `visits` nên chịu cùng cửa sổ hẹp của **D080** (mở rộng phạm vi từ xuất viện sang ra và hủy y lệnh, không nợ mới).
30. **Interface và tên:** `visit.api.PrescriptionApi.findItemOfVisitForKeyShare(itemId, visitId)` → `Optional<PrescriptionItemRef(itemId, externalPurchase)>` (BE-1 cả hai phía, `MANDATORY`, một lớp cài, đã grep không có lớp ẩn danh; lọc theo `medical_record_id` nên dòng của lượt khác là "không có"). Schema lý do hủy đổi tên `ReasonRequest` → `AdmissionOrderReasonRequest` vì springdoc gộp schema theo tên đơn giản và `identity.dto.ReasonRequest`, `shop.dto.ReasonRequest` đã có (khuôn `ShiftReasonRequest`); hợp đồng sinh lại. Không migration, không [CFG], không mẫu thông báo.

## Quyết định N6 — theo dõi nội trú (inpatient-v1 #9–#10, BR-LT-16) (2026-11-24, Mạnh chốt Q1–Q4)

31. **Cân nặng vào hồ sơ cân với nguồn `VET` + Visit của ca** (*Q1*; cả hai đường nhập viện; người ghi là VET hay CARETAKER). `weight_records.source` chỉ có `VET` / `INTAKE` / `OWNER` (V1, `ck_weight_records_source`) và bảng, enum thuộc BE-2: thêm `INPATIENT` cần V64 + sửa code BE-2, nên không làm. Đánh đổi: CARETAKER đo vẫn hiện nhãn FE "Bác sĩ đo khi khám" (`FE/src/domains/pets/types.ts`); `recorded_by` vẫn đúng người. FE nên đổi nhãn thành "Đo tại phòng khám" (D064). `PetApi.recordWeight` chạy cùng transaction, `pets FOR SHARE`, kiểm thú còn sống.
32. **Mục bất thường báo khách và VET phụ trách, trừ khi chính VET đó là người ghi** (*Q2*). Khách theo BR-TB-02 (tài khoản không `PENDING` → IN_APP + EMAIL; hồ sơ quầy có email → EMAIL; không kênh → log `INPATIENT_OBSERVATION_ABNORMAL_NO_CHANNEL`), người nhận là chủ hiện tại của thú; link khách `/pets` (khách chưa có màn hình nội trú, 09 Q4), link VET `/admin/inpatient`. Quy tắc kênh là bản sao thứ tư (`AdmissionObservationNotices`), vì `BoardingRecipients` là package-private và module không import `service` của module khác. Không chống trùng: mỗi mục bất thường gửi một lượt (như `CARE_LOG_ABNORMAL`). Đọc người phụ trách Visit không khóa: cùng cửa sổ hẹp D080.
33. **Thứ tự lỗi #9** (*Q3*, khuôn y lệnh mục 26): 400 hình thức → 403 vai trò → 404 → 403 chi nhánh → **409 ca không `ADMITTED`** → 400 `BR-LT-16`. Ca đã xuất viện luôn báo 409 dù dữ liệu cũng sai. Không đòi người phụ trách Visit: mọi VET / CARETAKER của chi nhánh ghi được (hợp đồng §B). `abnormal` là `Boolean` + `@NotNull` (thiếu trường = 400, không âm thầm `false`).
34. **Khóa #9** (*Q4*): `admissions FOR NO KEY UPDATE` (đọc ca **sau** khóa) → INSERT `admission_observations` → (có cân nặng) `pets FOR SHARE` + INSERT `weight_records` → INSERT outbox. Khóa ca trước mọi ghi là ràng buộc của mục 23 (không lọt mục theo dõi sau xuất viện). Không chu trình: không luồng nào khóa `pets` rồi mới khóa `admissions` (nhập viện chỉ `FOR SHARE` thú rồi INSERT ca mới). Đánh đổi: hai người ghi cùng ca xếp hàng vài ms. Đọc #10 `readOnly` + REPEATABLE READ, một câu tra tên người ghi theo lô (≤ 100 id), đọc được cả ca đã xuất viện.
35. **Một nguồn cho giới hạn chỉ số: `AdmissionObservation.invalidReason(...)`** (static, trả `Optional<String>`; service ném 400 `BR-LT-16`, entity ném `IllegalArgumentException`). So biên bằng `compareTo` **trước** `setScale`: JSON `1e-100000000` ngắn nhưng `setScale(2)` của nó chạy hàng giây (`1e-10000000` mất ~4 s; đo 24/11) hoặc không xong, `1e-999999999` ném `ArithmeticException` → 500. Biên: cân `0.005 ≤ w < 9999.995` (sau làm tròn 0.01…9999.99, khớp `PetWeightApiService`, nên `BR-KH-04` không lọt ra), nhiệt độ `|t| < 999.95`, `|t| < 0.05` thành `0.0` không `setScale`, nhịp 1…32 767. Khoảng lâm sàng vẫn TBD (A4, mục 8). Lỗi cùng gốc ở `PetWeightApiService.normalize` (BE-2) ghi thành **D081**; N6 không sửa file của BE-2.
36. **`createdAt` của response #9 = giá trị DB** (`AdmissionObservationRepository.findCreatedAt`): `CreatedAtEntity` lấy `Instant.now(clock)` đủ nano giây (`Clock.system`), cột `TIMESTAMPTZ` chỉ giữ micro giây; map thẳng từ entity thì #9 lệch #10. Một `SELECT` theo PK sau `saveAndFlush`, cùng transaction. Mapper nhận `createdAt` và tên người ghi qua tham số. Chuỗi số trong ngoặc kép (`"weightKg": "5"`) được nhận (Jackson mặc định, hành vi chung mọi endpoint, không sửa ở đây). Không migration, không [CFG], không mẫu thông báo mới, không audit (mục 8 của ADR-0148).

## Quyết định N7 — nội trú chạm sang lưu trú, đặt lịch, tiếp nhận, danh mục (2026-11-24, Mạnh chốt Q1–Q4)

37. **Guard 409 `BR-LT-17` ở boarding** (`BoardingCheckoutService.requireNotAdmitted`, gọi `AdmissionQueryApi.hasActiveAdmission(petId)`): bắt đầu trả thú (#16), giao thú (#17), kết thúc do thú mất (#18). Đặt **sau** khóa đặt chỗ `FOR NO KEY UPDATE` và sau 409 trạng thái ("không còn trong chuồng" / FSM), **trước** mọi rule 400 (`BR-LT-04`, `BR-LT-09`) và trước khi khóa chuồng hay mở Order, nên lỗi này không ghi gì. Exception là `BoardingConflictException` (409 `INVALID_STATE_TRANSITION`, message kết thúc "(BR-LT-17)", khuôn `CareLogService`): `BusinessRuleViolationException` luôn 400 còn hợp đồng ghi 409, `InvalidStateTransitionException` có message cố định không mang mã rule. Đọc không khóa `admissions`: nhập viện từ lưu trú giữ `boarding_bookings FOR SHARE` tới commit (mục 16) nên không chen được; xuất viện chỉ khóa `admissions`, tệ nhất boarding thấy bản `ADMITTED` cũ → 409, gọi lại thì qua.
38. **Q1 — chặn cả bước "bắt đầu trả thú"** (docs/09 BR-LT-17 chỉ ghi trả thú và thú mất): nếu không, khách trả tiền lưu trú trong lúc thú còn điều trị, đến lúc thú về chuồng thì Order lưu trú đã `PAID` (`startCheckout` trả 409 "đã thu tiền") nên những đêm sau không tính thêm được. Chấp nhận **D082**: Order lưu trú đã mở **trước** lúc nhập viện vẫn thu được tiền (thu tiền là `sales` của BE-2, không đụng); quy trình tay: lễ tân hủy phiên trả thú (sales-v1 #9) khi thú nhập viện.
39. **Q4 — chặn nhận thú vào lưu trú khi còn ca `ADMITTED`** (`BoardingCheckInService.checkIn`, 409 `BR-LT-17`; quy tắc **tự đề xuất**, suy từ mục đích của ADR-0148 mục 2: một thú không giữ hai chuồng, không bị tính cả hai loại phí). Thứ tự: sau 409 FSM, trước `BR-LT-06`, `BR-LT-05`, `BR-LT-08` và trước khi ghi cân nặng. `check-in-readiness` (#13) **không đổi** (hợp đồng không có trường lý do; `checkIn` kiểm lại dưới khóa). D079 còn lại phần race vài mili giây (nhập viện (a) ∥ nhận thú).
40. **`BR-LT-18` ở bốn chỗ còn thiếu** (400): `AppointmentBookingService` (đặt lịch) và `AvailableSlotService` (xem khung trống, để màn hình không hiện khung cho dịch vụ mà bước đặt sẽ từ chối), đặt sau kiểm chi nhánh `DRAFT` và trước kiểm `BR-LH-01` của dịch vụ; `VisitCheckInService.checkService` (tiếp nhận), sau `BR-CN-01` / `BR-CN-05`, trước `BR-LH-01`; `ServiceCatalogService.updateService` (đổi loại dịch vụ). **Q2:** lịch hẹn mang dịch vụ nội trú cũng bị từ chối ở tiếp nhận (ngoại lệ của ADR-0088, vốn cho lịch đã đặt luôn được tiếp nhận): nếu cho qua, dòng tự sinh là dịch vụ nội trú, VET sửa / xóa được và lượt hoàn tất không cần chẩn đoán (lách BR-KB-02); lễ tân hủy lịch rồi tiếp nhận walk-in. **Q3:** `updateService` từ chối đổi `medicalType` sang hoặc khỏi `INPATIENT` (đổi Khám → Nội trú làm lượt đang khám hoàn tất không cần chẩn đoán; đổi ngược lại làm dòng nội trú của ca đã xuất viện sửa tay được); gửi lại đúng loại cũ vẫn qua; muốn đổi thì tạo dịch vụ mới. Nhờ đó dòng tự sinh không bao giờ là `INPATIENT`, đúng với ghi chú ở `OrderLineService.requireNotInpatientLine`. Không migration, không [CFG], không mẫu thông báo, không endpoint mới (parity vẫn 247).
41. **Khóa:** không khóa mới, không vòng. Boarding thêm một câu `SELECT EXISTS` (partial index `ux_admissions_pet_admitted`) sau khóa đặt chỗ; không luồng nào khóa `admissions` rồi mới khóa đặt chỗ. Phần BE-2 (boarding, catalog) gom riêng để duyệt như N0.

## Quyết định N8 — hoàn thiện (2026-11-24, Mạnh chốt)

42. **Bỏ `default` của `VisitOrderApi.openTreatmentOrder` / `addServiceLine`**: hai method thành trừu tượng. Lý do: `VisitOrderService` cài thật từ N0, và tìm khắp local, `origin/dev`, `origin/dung` đều không thấy stand-in `new VisitOrderApi() {…}` / `implements VisitOrderApi` nào khác, nên `default` chỉ còn là placeholder đã có bản thật (CLAUDE.md *Unbuilt interface*). Đã báo BE-2 ở `docs/06` §6b hàng 6. Swagger 4xx của 10 endpoint được đối chiếu với `inpatient-v1.yaml`: khớp, không sửa. ADR này vẫn Proposed.

## Thử nghiệm (N2)

- **Unit:**
  - `VisitTransitionHandlerTest`: bảng có #7; message 409 chuyển sang dùng `COMPLETED`.
  - `VisitTest`: `startTreatment` ghi đủ cột; thiếu từng tham số → `IllegalArgumentException`.
  - `TreatmentVisitServiceTest`: `InOrder` validate → mã → lưu → Order; tham số null hoặc FSM từ chối thì không ghi gì.
  - `AdmissionQueryServiceTest`.
- **IT:**
  - `VisitFsmIT` (era 2028): Visit#7 qua bean → `IN_PROGRESS`, Order `OPEN` 0 dòng; gọi / gán / hủy lượt điều trị → 409, không ghi gì; ngoài transaction → `IllegalTransactionStateException`.
  - `AdmissionFsmIT` (era 2093): hai câu đọc chỉ tính `ADMITTED`.
  - `Tier3Be1ContractWiringIT` (không ghi DB): một bean thật cho mỗi interface, `MANDATORY`.

## Thử nghiệm (N3)

- **Unit:**
  - `AdmissionAdmitServiceTest`: `InOrder` khóa → guard → ghi cho hai đường; mỗi nhánh lỗi không chiếm chuồng / không tạo Visit / không lưu.
  - `AdmissionReadServiceTest`: mặc định `ADMITTED`, kẹp trang, ghép theo lô, dữ liệu hỏng.
  - `AdmissionMapperTest`: thứ tự trường của hợp đồng.
  - `VisitCompletionServiceTest`: 409 còn ca; chỉ `INPATIENT` qua.
  - `TreatmentVisitServiceTest`, `BoardingQueryServiceCurrentStayTest`, `KennelTreatmentServiceTest`: hàm mới.
- **IT:**
  - `AdmissionAdmitIT` 2095, `AdmissionSecurityIT` 2096, `AdmissionReadIT` 2098.
  - `AdmissionLockIT` 2097: hai nhập viện đồng thời → một 201 / một 409; chờ khóa đặt chỗ / lượt rồi đọc lại; guard Visit#5 qua HTTP.
  - `AdmissionRollbackIT` 2099: lỗi sau khi đã ghi → hoàn tác trọn; unique index là lưới đỡ.
  - `Tier3Be1ContractWiringIT`: hàm mới là `MANDATORY`.
  - `OpenApiContractParityIT`: pin 240, thêm `inpatient-v1.yaml`.
- **Đã chạy 23/11/2026, xanh:**
  - toàn bộ unit 6966;
  - nhóm IT `inpatient` 77;
  - nhóm IT `visit` + parity + `Tier3Be{1,2}ContractWiringIT` 542;
  - nhóm IT `boarding` + `MainFlowE2EIT` / `MainFlowExamIT` / `RetailSaleFlowIT` 170.
- **Một bẫy khi viết test:** spy của `@MockitoSpyBean` trên repository nằm sau proxy giao dịch (method `MANDATORY`), nên phải stub trên `AopTestUtils.getTargetObject(...)`. Stub qua proxy sẽ ném giữa chừng và để stub dang dở rơi vào lần gọi sau (`AdmissionRollbackIT`).

## Thử nghiệm (N4)

- **Unit:** `AdmissionDischargeServiceTest` (17): tóm tắt rỗng gồm khoảng trắng Unicode chưa chạm DB; mỗi nhánh lỗi không thêm dòng / không giải phóng chuồng / không lưu; `InOrder` khóa → đếm → dòng Order → chuồng → lưu; đường (b) không đụng chuồng; số lượng và đơn giá lấy từ ca; lỗi ở `addServiceLine` hoặc `release` dừng trước bước sau. `OrderLineServiceTest` (+2): dòng nội trú không sửa / xóa; chính sách chặn trước guard.
- **IT:**
  - `AdmissionDischargeIT` 2100 (19): ngày biên (cùng ngày = 1; nhập 23:50 xuất 00:10 hôm sau = 2; 4 ngày = 4); giá danh mục đổi + dịch vụ tắt vẫn dùng giá đã chốt; hai đường; `DECEASED` không đánh dấu thú mất / không kết thúc lưu trú; `ORDERED` chặn, `DONE` / `CANCELLED` không; 409 lần hai; hoàn tất Visit sau xuất viện (Order `PENDING` đúng tổng); sales PATCH / DELETE dòng nội trú → 400; vai trò, đầu vào, thứ tự lỗi.
  - `AdmissionLockIT` 2097 (+2): hai xuất viện đồng thời → 200 / 409, đúng một dòng; chờ khóa ca rồi đọc lại → 409.
  - `AdmissionRollbackIT` 2099 (+1): lỗi lưu ca hoàn tác dòng Order và chuồng, gọi lại tính một lần.
  - `OpenApiContractParityIT`: pin **241**.
- **Kiểm đột biến một lần (24/11/2026):** đổi `findByIdForUpdate` thành `findById` trong service → `AdmissionLockIT.dischargeWaitsForTheAdmissionLock…` đỏ (trả 200, tính tiền hai lần); hoàn lại thì xanh. Test "hai xuất viện đồng thời" vẫn xanh khi bỏ khóa vì phụ thuộc thời điểm, nên chứng cứ là test chờ khóa tất định.
- **Đã chạy 24/11/2026, xanh:** unit 6985; nhóm IT `inpatient` 99; parity + `Tier3Be{1,2}ContractWiringIT` + `Tier3Be1SchemaIT`; nhóm IT `sales` + `MainFlowE2EIT` / `MainFlowExamIT` / `RetailSaleFlowIT`.

## Thử nghiệm (N5)

- **Unit:**
  - `AdmissionOrderServiceTest` (39): mỗi nhánh lỗi không `saveAndFlush`; `InOrder` khóa → kiểm → ghi cuối; 403 chi nhánh / người phụ trách / A08 + thuốc trước 409, 409 trước 400 (nội dung / ghi chú / lý do rỗng kể cả U+3000); Q1 / Q2 / dòng của lượt khác; mọi hàm ghi `REQUIRED`, READ COMMITTED, không `noRollbackFor`.
  - `AdmissionOrderMapperTest` (5, thứ tự trường hợp đồng, cột `done_*` / `cancel_reason` đúng trạng thái), `AdmissionReadServiceTest` (+4 `listOrders`), `PrescriptionApiServiceTest` (+4), `AdmissionDischargeServiceTest` (đổi cách dựng `AdmissionAssignees`, không đổi khẳng định).
- **IT (era 2101–2104, nền chung `AdmissionOrderItBase`; ca dựng bằng API nhập viện thật):**
  - `AdmissionOrderIT` 2101 (39): bốn endpoint, response khớp từng cột DB (kể cả µs), Q1–Q3, 409 trước 400 khi mọi quy tắc đều vi phạm, xuất viện chặn / thả theo y lệnh, ma trận đầu vào với đúng `errorCode` (gồm 415).
  - `AdmissionOrderSecurityIT` 2102 (13): 401, `BR-TK-17`, mọi vai trò, chi nhánh khác, gán lại lượt chuyển quyền, điều chuyển / đổi chức vụ cùng token, `created_by` / `done_by` là người gọi.
  - `AdmissionOrderRollbackIT` 2103 (5): lưu lỗi → không dòng / y lệnh vẫn `ORDERED`, gọi lại thành công, dòng đơn xóa được ngay (`lock_timeout`, khóa không rò).
  - `AdmissionOrderLockIT` 2104 (10): ra y lệnh chờ khóa ca rồi thấy `DISCHARGED` → 409; xuất viện ∥ ra y lệnh không bao giờ để `ORDERED` trên ca đã xuất viện (6 vòng); thực hiện / hủy chờ khóa dòng rồi 409 `INVALID_STATE_TRANSITION`; xóa dòng đơn đang chờ → 400 `BR-LT-15`.
  - `Tier3Be1ContractWiringIT`: hàm mới `MANDATORY`. `OpenApiContractParityIT`: pin **245**.
- **Đã chạy 24/11/2026, xanh:** toàn bộ unit 7037 (gồm `PetcareApplicationTests`); nhóm IT `inpatient` 166 (12 lớp); `OpenApiContractParityIT` (245) + `Tier3Be{1,2}ContractWiringIT` + `Tier3Be1SchemaIT`; nhóm IT `visit` (vì `PrescriptionApi` đổi). Chưa chạy lại nhóm `sales` / `boarding`: không đổi code ở đó trong N5. **Chưa kiểm đột biến** cho N5 (chỉ làm ở N4).

## Thử nghiệm (N6)

- **Unit:**
  - `AdmissionObservationServiceTest`: `InOrder` khóa → chi nhánh → tên người ghi → lưu → đọc `created_at` → cân nặng → thông báo; mọi nhánh lỗi không ghi gì; 409 trước 400; cân nặng gọi `recordWeight(VET, visitId của ca, người gọi)`; `REQUIRED`, không `noRollbackFor`.
  - `AdmissionObservationNoticesTest`: ba loại khách, VET tự ghi không tự nhận, payload đủ biến, `ngay_ghi` theo giờ Việt Nam, thiếu biến → `IllegalStateException`.
  - `AdmissionObservationTest` (+ `invalidReason`, biên làm tròn, số mũ cực lớn trong 2 s), `AdmissionObservationMapperTest`, `AdmissionReadServiceTest` (+ `listObservations`).
- **IT** (era 2105–2108, nền `AdmissionObservationItBase`, ca dựng bằng API nhập viện thật):
  - `AdmissionObservationIT` 2105: response khớp từng cột DB, `createdAt` nano → khớp DB và #10, cân nặng → `weight_records` (`VET`, Visit của ca), outbox đúng mẫu / kênh / người nhận / đủ biến bắt buộc đọc từ `notification_templates`, ma trận 400, `1e-100000000` trả lời nhanh, 409 trước 400, #10 mới nhất trước và phân trang.
  - `AdmissionObservationSecurityIT` 2106: 401, `BR-TK-17` trước validation, mọi vai trò, chi nhánh khác, điều chuyển / đổi chức vụ cùng token; mọi lời gọi bị từ chối giữ nguyên DB và outbox.
  - `AdmissionObservationLockIT` 2107: chờ khóa ca rồi thấy `DISCHARGED` → 409 và không để lại gì; xuất viện ∥ ghi theo dõi chỉ có hai kết quả hợp lệ (6 vòng); hai người ghi cùng ca đều thành công; chờ `pets FOR NO KEY UPDATE` rồi cả ba bản ghi cùng commit.
  - `AdmissionObservationRollbackIT` 2108: lỗi lưu mục / dòng cân (trigger) / outbox (trigger) → không còn gì và khóa ca, khóa thú nhả (`FOR UPDATE NOWAIT`), gọi lại thành công.
  - `OpenApiContractParityIT`: pin **247**.
- **Chưa kiểm đột biến** cho N6 (chỉ làm ở N4).

## Thử nghiệm (N7)

- **Unit:** `BoardingCheckoutServiceTest` (+5: mỗi hàm `ADMITTED` → 409 "(BR-LT-17)" không ghi gì, đọc sau khóa đặt chỗ và trước `hasPaidOrder`; 409 trạng thái thắng guard), `BoardingCheckInServiceTest` (+2), `AppointmentBookingServiceTest` (+3), `AvailableSlotServiceTest` (+2), `VisitCheckInServiceTest` (+2: walk-in và lịch hẹn, thứ tự sau `BR-CN-01` / `BR-CN-05`), `ServiceCatalogServiceTest` (+3: sang / khỏi nội trú, gửi lại đúng loại).
- **IT:** `AdmissionBoardingGuardIT` 2109 (4: ba bước trả thú bị chặn và không ghi gì, guard thắng `BR-LT-09`, xuất viện rồi trả thú bình thường, nhận thú khi nằm chuồng điều trị), `AdmissionDeceasedChainIT` 2110 (2: chuỗi thú mất bốn bước đường (a) và (b), mọi thứ tự sai bị từ chối), `AdmissionBoardingLockIT` 2111 (4: chờ khóa đặt chỗ rồi 409; rollback thì đi tiếp); ca HTTP thêm vào `BookAppointmentIT`, `AvailableSlotsIT`, `VisitCheckInIT`, `CatalogAdminIT`. `OpenApiContractParityIT`: pin **247** (không endpoint mới).
- **Đã chạy 24/11/2026, xanh:** unit của 6 module bị đụng 2472; nhóm IT `inpatient` 218, `boarding` 167, `appointment` 393, `visit` 528 (gồm `MainFlowE2EIT` / `MainFlowExamIT`), `catalog` 26; `OpenApiContractParityIT` + `Tier3Be{1,2}ContractWiringIT` + `Tier3Be1SchemaIT` + `RetailSaleFlowIT` + `OrderLineIT` 57. Chưa chạy lại toàn bộ unit (chỉ các module trên) và chưa kiểm đột biến.

## Thử nghiệm (N8)

- Không thêm test: `VisitOrderService` đã có test từ N0; `mvn clean compile` và `test-compile` sạch chứng minh không còn ai dựa vào `default`. Swagger 4xx so bằng script (yaml ↔ `@ApiErrorDoc`, 10 / 10 khớp).
- **Đã chạy 24/11/2026, xanh:** unit `inpatient` / `sales` / `visit` 1845; `OpenApiContractParityIT` (247) + `Tier3Be{1,2}ContractWiringIT` 15; nhóm IT `inpatient` 218, `sales` 196, `boarding` 167, `visit` 528, `appointment` 393, `catalog` 26.

## Hệ quả

- Số Flyway kế tiếp V64, ADR kế tiếp 0150, era IT kế tiếp 2112 (N3 dùng 2095–2099, N4 dùng 2100, N5 dùng 2101–2104, N6 dùng 2105–2108, N7 dùng 2109–2111).
- N2 phải kiểm trước mọi điều kiện ở cột "Service" để trả đúng 400 / 403 / 409; lỗi của entity (`IllegalArgumentException`, `IllegalStateException`, NPE) lọt ra HTTP sẽ là 500.
- Phần FE (D064, D065): V63 thêm 2 mẫu thông báo, seed FE cần thêm khi cập nhật.
