# Pet Care Ecosystem — Đặc tả bổ sung tầng 3, phần BE-1 (T51)

<<<<<<< HEAD
> **Đặc tả chính thức phần BE-1 · T51 · đã duyệt ở T54 (Hiến, nhóm trưởng, 5/11/2026).** BE-1 (Mạnh) đọc khi bắt đầu task tương ứng; muốn đổi thì qua ADR. File này gom quy tắc nghiệp vụ, máy trạng thái, mô hình dữ liệu / ERD, API và hợp đồng chéo module cho phần còn lại của T51: **UC50, UC55, UC56, UC85, UC86 · ST07, ST16, ST17** (5 UC, 3 ST). Phần Nhân sự (UC34–38) đã viết ở `02`–`06` và `api/workforce-v1`.
>
> **Vì sao đặt ở file riêng:** như `08` (BE-2) và `07` (AI): `02`–`06` đang được hai thành viên backend sửa song song. **Đây là nguồn chính thức; `02`–`05` chỉ có mục trỏ tới đây** (không chép trùng); interface chéo gộp ở `06`. **Thứ tự các mục theo hạn trong `time.md`:** B (UC50 — T61) → C (UC55, UC56 — T69) → D (UC85, UC86, ST16, ST17 — T70) → E (ST07 — T71).
> **Hợp đồng máy đọc:** [`surgery-v1.md`](./api/surgery-v1.md), [`inpatient-v1.md`](./api/inpatient-v1.md), [`complaint-v1.md`](./api/complaint-v1.md), [`materialuse-v1.md`](./api/materialuse-v1.md).
=======
> **Đặc tả chính thức phần BE-1 · T51 · đã duyệt ở T54 (Hiến, nhóm trưởng, 5/11/2026).** BE-1 (Mạnh) đọc khi bắt đầu task tương ứng; muốn đổi thì qua ADR. File này gom quy tắc nghiệp vụ, máy trạng thái, mô hình dữ liệu / ERD, API và hợp đồng chéo module cho phần còn lại của T51: **UC43, UC51, UC55, UC56 · ST07** (UC50 phẫu thuật bỏ ở ADR-0125; UC85, UC86, ST16, ST17 khiếu nại bỏ ở ADR-0122). Phần Nhân sự (UC34–38) đã viết ở `02`–`06` và `api/workforce-v1`.
>
> **Vì sao đặt ở file riêng:** như `08` (BE-2) và `07` (AI): `02`–`06` đang được hai thành viên backend sửa song song. **Đây là nguồn chính thức; `02`–`05` chỉ có mục trỏ tới đây** (không chép trùng); interface chéo gộp ở `06`. **Thứ tự các mục theo hạn trong `time.md`:** A (UC43, UC51 — T60, 23–25/11) → B (phẫu thuật, đã bỏ) → C (UC55, UC56 — T69) → D (khiếu nại, đã bỏ; thay bằng feedback không trạng thái — T70) → E (ST07 — T71).
> **Hợp đồng máy đọc:** [`api/homevisit-v1.md`](./api/homevisit-v1.md), [`inpatient-v1.md`](./api/inpatient-v1.md), [`materialuse-v1.md`](./api/materialuse-v1.md) (`surgery-v1` bỏ ở ADR-0125).
>>>>>>> 78fe062 (docs: chốt đặc tả nội trú (ADR-0124) và bỏ module phẫu thuật (ADR-0125).)

**Quy ước đánh số (đã chốt ở T54):** BR tiếp nối tiền tố hiện có (đã kiểm số lớn nhất trong `02`: BR-LH-12, BR-KB-06, BR-LT-12, BR-DG-04, BR-BH-06); FSM của BE-1 bắt đầu từ **#23** (`08` dùng #14–#21, AI #13, NS #11–#12). BE-1 thêm rule mới phải lấy số sau số lớn nhất ở **cả** `02` và file này.

**Quy ước cột (cho migration ở T55):** cột `*_at` là `TIMESTAMPTZ`; `*_date`, `*_on` là `DATE`; `*_by` và `*_id` (trừ khi ghi khác) là `BIGINT` khóa ngoại; `id` là `BIGINT` khóa chính; `quantity`, `*_qty`, `*_count` là `INT`; tiền là `BIGINT` (đồng); trạng thái và loại là `VARCHAR(30)` + `CHECK` (05 §0); `?` sau tên cột là cho phép NULL. Cột liệt kê không ghi kiểu thì theo quy ước này.

<<<<<<< HEAD
**Chủ sở hữu (đã chốt ở T54, Hiến quyết 5/11/2026):** khiếu nại (`complaint`) và nội trú (`inpatient`) là package **mới của BE-1**, cùng `surgery`, `materialuse`. Lý do: đề cương 2.4.4 ghi "LT (nội trú) – Mạnh", `time.md` giao T69, T70 cho BE-1, và để BE-1 không sửa module của BE-2. Feedback (UC83, 84) vẫn ở `content` của BE-2. Chỗ nào cần dữ liệu của `boarding`, `inventory`, `sales` thì gọi interface của BE-2 (ở `06`).
=======
**Chủ sở hữu (đã chốt ở T54, Hiến quyết 5/11/2026):** nội trú (`inpatient`) là package **mới của BE-1** (khiếu nại đã bỏ, ADR-0122), cùng `homevisit`, `materialuse` (`surgery` bỏ ở ADR-0125). Lý do: đề cương 2.4.4 ghi "LT (nội trú) – Mạnh", `time.md` giao T69 cho BE-1, và để BE-1 không sửa module của BE-2. Feedback (UC83, 84) vẫn ở `content` của BE-2. Chỗ nào cần dữ liệu của `boarding`, `inventory`, `sales` thì gọi interface của BE-2 (ở `06`).
>>>>>>> 78fe062 (docs: chốt đặc tả nội trú (ADR-0124) và bỏ module phẫu thuật (ADR-0125).)


---

## B. Phẫu thuật — đã bỏ (ADR-0125, 2026-11-09)

UC50, BR-KB-08…11, FSM #23, module `surgery`, `surgery-v1` (8 endpoint) và bảng `surgeries` **không còn**. Thủ thuật nhỏ ghi trong bệnh án như dịch vụ khám thông thường; ca cần nằm lại dùng nội trú (§C). Lượt khám không còn điều kiện "không hoàn tất khi còn phẫu thuật mở" và không còn hủy theo phẫu thuật.

Lý do và phạm vi sửa: [ADR-0125](./adr/0125-drop-surgery-module.md). Mã UC / BR / FSM giữ nguyên (gạch bỏ) để không lệch số. V37 (bảng `surgeries`) và V41 (giá trị `SURGERY` của `services.medical_type`) còn trong DB cho tới migration riêng của D063.

---

## C. Nội trú: nhập / xuất viện và điều trị (UC55, UC56 · T69)

> *(ADR-0124, 2026-11-09)* Sửa cách tính ngày và tiền, chuồng, y lệnh, theo dõi, người phụ trách và thú mất. Trạng thái và mã viết bằng tiếng Anh (`ADMITTED`, `DISCHARGED`, `ORDERED`, `DONE`, `CANCELLED`, `HOME`, `DECEASED`; kết thúc lưu trú `RETURNED` / `PET_DECEASED`). Không còn "hậu phẫu" (ADR-0125).

| Mã | Use case | Actor |
|---|---|---|
| UC55 | Nhập / xuất viện (gồm chuyển thú từ lưu trú sang điều trị) | A07 |
| UC56 | Điều trị nội trú: ra y lệnh, theo dõi nội trú | A07 (A08 thực hiện việc không phải thuốc, ghi theo dõi) |

### Quy tắc

| Mã | Tên | Mô tả | UC | Xử lý khi vi phạm |
|---|---|---|---|---|
<<<<<<< HEAD
| BR-LT-13 | Nhập viện | VET nhập viện một thú vào điều trị nội trú từ **(a) lượt khám đang `IN_PROGRESS`** của mình, hoặc **(b) đặt chỗ lưu trú đang `CHECKED_IN`** (chuyển từ lưu trú sang điều trị): với (b) hệ thống tạo một **Visit điều trị** `IN_PROGRESS` gán VET đó (không qua hàng đợi) rồi nhập viện vào Visit này, nên mọi khoản phí vẫn vào Order `VISIT` (BR-BH-01). Mỗi thú tối đa **một** ca nội trú `ADMITTED` (BR-TN-01). Chọn dịch vụ nội trú (loại `INPATIENT`, *ASSUMPTION A1*, giá **theo ngày**, chốt lúc nhập viện) và chuồng `AVAILABLE` của chi nhánh (*ASSUMPTION A2:* dùng chung chuồng với lưu trú). | UC55 | 400 `BR-LT-13`: thú đã nội trú, đặt chỗ không `CHECKED_IN`, hết chuồng, thú đã mất. |
| BR-LT-14 | Xuất viện | VET xuất viện kèm **tóm tắt điều trị** và kết cục (`HOME` về nhà / tiếp tục lưu trú, `DECEASED` thú mất). Điều kiện: không còn y lệnh `ORDERED` (phải xong hoặc hủy). Hệ thống thêm dòng dịch vụ nội trú vào Order của Visit với số lượng = **số ngày** (từ ngày nhập đến ngày xuất, tối thiểu 1) × giá đã chốt (UC55 «extend» UC67); giải phóng chuồng nếu chuồng được cấp riêng cho nội trú. Kết cục `DECEASED` yêu cầu VET đánh dấu thú đã mất theo BR-KH-05 (xác nhận hai bước). Visit hoàn tất theo UC48 sau xuất viện; không hoàn tất Visit khi còn ca nội trú `ADMITTED`. | UC55 | 400 `BR-LT-14`. |
| BR-LT-15 | Y lệnh nội trú | VET ra y lệnh cho ca `ADMITTED`: loại `MEDICATION`, `FEEDING`, `MONITORING`, `PROCEDURE`, `OTHER`; nội dung (≤ 500 ký tự) và giờ thực hiện dự kiến. Y lệnh `MEDICATION` liên kết một dòng đơn thuốc của Visit (kê qua visit-v1 #16, kiểm tồn BR-KB-03). Việc **không phải thuốc** do VET hoặc CARETAKER thực hiện; **thuốc** chỉ VET. Thực hiện → `DONE` kèm ghi chú; VET hủy y lệnh chưa làm kèm lý do. | UC56 | 403 / 409. |
| BR-LT-16 | Theo dõi hậu phẫu | VET hoặc CARETAKER ghi **chỉ số theo dõi** (nhiệt độ, nhịp tim, nhịp thở, cân nặng, ăn uống, ghi chú, cờ **bất thường**) — chỉ thêm, không sửa, không xóa (LOG). Mục đánh dấu bất thường báo VET phụ trách và khách theo cách của nhật ký lưu trú (BR-LT-12, `NotificationApi`). Cân nặng ghi vào hồ sơ cân của thú (BR-KH-04). | UC56 | — |
| BR-LT-17 | Phạm vi và liên kết lưu trú | Nhân viên chỉ thấy ca nội trú của chi nhánh mình (BR-QT-03). Thú nhập viện **từ lưu trú**: đặt chỗ giữ nguyên `CHECKED_IN`; lễ tân **không trả thú** (BR-LT-09) khi còn ca `ADMITTED` (guard ở boarding); phí lưu trú theo đêm vẫn tính theo đặt chỗ và phí nội trú tính riêng theo ngày (*ASSUMPTION A3*). Khách chưa có màn hình xem nội trú (Q4). | UC55 | 409 `BR-LT-17` khi trả thú còn nội trú. |
=======
| BR-LT-13 | Nhập viện | VET nhập viện một thú vào điều trị nội trú từ **(a) lượt khám `IN_PROGRESS`** mà mình được gán, hoặc **(b) đặt chỗ lưu trú `CHECKED_IN`**. Với (b), hệ thống tạo một **Visit điều trị** `IN_PROGRESS` gán VET đó (không qua hàng đợi, như BR-KB-07; Order `VISIT` mở **không có dòng dịch vụ tự sinh**, dòng nội trú chỉ có lúc xuất viện — ngoại lệ của BR-TN-01) rồi nhập viện vào Visit này, nên mọi khoản phí vẫn vào Order `VISIT` (BR-BH-01). Mỗi thú tối đa **một** ca `ADMITTED`. Chọn dịch vụ nội trú (loại `INPATIENT`, *ASSUMPTION A1*, giá **theo ngày**, chốt vào `admissions.unit_price`, BR-BH-03). **Chuồng:** (a) VET chọn một chuồng `AVAILABLE` bất kỳ của chi nhánh (chuồng → `OCCUPIED`; không kiểm loài, cân nặng hay sức chứa đặt chỗ); (b) không chọn chuồng, thú ở nguyên chuồng của đặt chỗ. **Người phụ trách:** mọi việc của VET trên ca (nhập, xuất, y lệnh) thuộc VET đang được gán Visit (BR-KB-01); gán lại Visit (BR-TN-08) chuyển quyền sang người mới, ca không lưu bác sĩ riêng. | UC55 | 400 `BR-LT-13`: thú đã nội trú, Visit không `IN_PROGRESS` hoặc không do mình phụ trách, đặt chỗ không `CHECKED_IN`, dịch vụ không phải `INPATIENT`, chuồng không `AVAILABLE` hoặc có `kennelId` khi nhập từ lưu trú / thiếu `kennelId` khi nhập từ khám, thú đã mất. |
| BR-LT-14 | Xuất viện | VET phụ trách xuất viện kèm **tóm tắt điều trị** và kết cục (`HOME` về nhà hoặc trở lại lưu trú; `DECEASED` thú mất). Điều kiện: không còn y lệnh `ORDERED` (phải xong hoặc hủy). Trong **một transaction**, hệ thống thêm **một** dòng dịch vụ nội trú vào Order `OPEN` của Visit: số lượng = **ngày xuất − ngày nhập + 1** (ngày lịch giờ Việt Nam, gồm cả hai đầu; cùng ngày = 1), đơn giá = `unit_price` đã chốt (UC55 «extend» UC67), rồi giải phóng chuồng nếu chuồng cấp riêng (a). Nhập viện không thêm dòng. Trong lúc `ADMITTED`, API đọc trả `billedDays` (đến hôm nay) và `estimatedAmount` = `billedDays × unitPrice` để báo khách (**tính toán, không lưu**); sau xuất viện tính đến ngày xuất. **Hoàn tất Visit:** không hoàn tất khi còn ca `ADMITTED`; lượt chỉ có dịch vụ `INPATIENT` (nhập từ lưu trú) hoàn tất mà không cần chẩn đoán hay mũi tiêm của BR-KB-02 (tóm tắt xuất viện thay chẩn đoán). Khách không trả: Order `PENDING` đi theo BR-BH-05. **Thú mất (`DECEASED`)**, bốn bước theo thứ tự: (1) VET xuất viện `DECEASED` (vẫn tính tiền đến ngày xuất); (2) VET hoàn tất Visit; (3) nếu nhập từ lưu trú, lễ tân kết thúc lưu trú `PET_DECEASED` (BR-LT-12); (4) lễ tân đánh dấu thú đã mất (BR-KH-05, hai bước). Xuất viện **không** tự đánh dấu mất hay tự kết thúc lưu trú. | UC55 | 400 `BR-LT-14`: còn y lệnh `ORDERED`, thiếu tóm tắt. 409 khi hoàn tất Visit còn ca `ADMITTED`. |
| BR-LT-15 | Y lệnh nội trú | VET phụ trách ra y lệnh cho ca `ADMITTED`: loại `MEDICATION`, `FEEDING`, `MONITORING`, `PROCEDURE`, `OTHER`; nội dung (≤ 500 ký tự) và giờ thực hiện dự kiến. **Một y lệnh là một lần thực hiện**: đợt thuốc nhiều lần là nhiều y lệnh; nhiều y lệnh có thể liên kết cùng một dòng đơn thuốc, hệ thống không đối chiếu số lần với số lượng đã kê. Y lệnh `MEDICATION` liên kết một dòng đơn thuốc của Visit (kê qua visit-v1 #16, kiểm tồn BR-KB-03). Việc **không phải thuốc** do VET hoặc CARETAKER thực hiện; **thuốc** chỉ VET. Thực hiện → `DONE` kèm ghi chú; VET hủy y lệnh chưa làm kèm lý do. | UC56 | 403 / 409. |
| BR-LT-16 | Theo dõi nội trú | VET hoặc CARETAKER ghi **chỉ số theo dõi** (nhiệt độ, nhịp tim, nhịp thở, cân nặng, ăn uống, ghi chú, cờ **bất thường**) — chỉ thêm, không sửa, không xóa (LOG). Mục đánh dấu bất thường báo VET phụ trách và khách theo cách của nhật ký lưu trú (BR-LT-12, `NotificationApi`). Cân nặng ghi vào hồ sơ cân của thú (BR-KH-04). | UC56 | — |
| BR-LT-17 | Phạm vi và liên kết lưu trú | Nhân viên chỉ thấy ca nội trú của chi nhánh mình (BR-QT-03). Thú nhập viện **từ lưu trú**: đặt chỗ giữ nguyên `CHECKED_IN`; khi còn ca `ADMITTED`, lễ tân **không kết thúc lưu trú** — cả trả thú (`RETURNED`, BR-LT-09) lẫn thú mất (`PET_DECEASED`, BR-LT-12) (guard ở boarding). Phí lưu trú theo đêm vẫn tính theo đặt chỗ và phí nội trú tính riêng theo ngày (*ASSUMPTION A3*, ADR-0124: thú bị tính cả hai cho những ngày điều trị, chấp nhận có chủ đích). Khách chưa có màn hình xem nội trú (T54 Q4). | UC55 | 409 `BR-LT-17` khi kết thúc lưu trú còn nội trú. |
>>>>>>> 78fe062 (docs: chốt đặc tả nội trú (ADR-0124) và bỏ module phẫu thuật (ADR-0125).)

### Máy trạng thái #24 — Nhập viện

`admissions.status` · LT (nội trú) · Tầng 3.

| Trạng thái | Loại | Ý nghĩa |
|---|---|---|
| `ADMITTED` | INIT | Đang điều trị nội trú |
| `DISCHARGED` | FINAL | Đã xuất viện (kết cục `HOME` hoặc `DECEASED`) |

| # | Từ → Sang | Sự kiện | Người kích hoạt | Điều kiện | Hệ quả | Nguồn |
|---|---|---|---|---|---|---|
| 1 | — → `ADMITTED` | Nhập viện | A07 (phụ trách Visit) | BR-LT-13 | (a) chuồng → `OCCUPIED`; (b) tạo Visit điều trị, thú giữ chuồng cũ; chốt `unit_price` | UC55 |
| 2 | `ADMITTED` → `DISCHARGED` | Xuất viện | A07 (phụ trách Visit) | Không còn y lệnh `ORDERED`; có tóm tắt | Thêm một dòng dịch vụ nội trú vào Order (số ngày × `unit_price`); (a) giải phóng chuồng; `DECEASED` → bốn bước ở BR-LT-14 | BR-LT-14 · UC55 |

### Máy trạng thái #25 — Y lệnh nội trú

`admission_orders.status` · LT (nội trú) · Tầng 3.

| Trạng thái | Loại | Ý nghĩa |
|---|---|---|
| `ORDERED` | INIT | Đã ra y lệnh, chờ thực hiện |
| `DONE` | FINAL | Đã thực hiện (có ghi chú) |
| `CANCELLED` | FINAL | Đã hủy (có lý do) |

| # | Từ → Sang | Sự kiện | Người kích hoạt | Điều kiện | Hệ quả | Nguồn |
|---|---|---|---|---|---|---|
| 1 | — → `ORDERED` | Ra y lệnh | A07 (phụ trách Visit) | Ca `ADMITTED`; `MEDICATION` có dòng đơn thuốc | — | BR-LT-15 · UC56 |
| 2 | `ORDERED` → `DONE` | Thực hiện | A07; A08 (không phải thuốc) | Có ghi chú | — | BR-LT-15 · UC56 |
| 3 | `ORDERED` → `CANCELLED` | Hủy y lệnh | A07 (phụ trách Visit) | Có lý do | — | BR-LT-15 · UC56 |

### Dữ liệu (05 bổ sung)

- `admissions` — ROOT · SM #24: `id`, `code VARCHAR(20) UQ` (`NT-yyMMdd-xxxx`), `visit_id FK NN`, `pet_id FK NN`, `branch_id FK NN`, `boarding_booking_id FK→boarding_bookings?`, `kennel_id FK→kennels?` (**chỉ có khi nhập từ lượt khám**), `service_id FK→services NN` (loại `INPATIENT`), `unit_price BIGINT NN` (snapshot / ngày), `status`, `admitted_by FK NN`, `admitted_at NN`, `discharge_outcome VARCHAR(20)? CHECK ('HOME','DECEASED')`, `discharge_summary TEXT?`, `discharged_by FK?`, `discharged_at?`, `order_line_id FK→order_lines? UQ`. `UNIQUE (pet_id) WHERE status = 'ADMITTED'`; `CHECK` xuất viện ↔ cột kết cục (khuôn `care_tasks`); Index `(branch_id, status)`. `billedDays` và `estimatedAmount` **không có cột** (tính khi đọc). Schema đã có ở V38.
- `admission_orders` — ROOT · SM #25: `id`, `admission_id FK NN`, `order_type VARCHAR(20) NN CHECK ('MEDICATION','FEEDING','MONITORING','PROCEDURE','OTHER')`, `content VARCHAR(500) NN`, `scheduled_at TIMESTAMPTZ NN`, `status`, `prescription_item_id FK→prescription_items?` (bắt buộc với `MEDICATION`, **không unique**), `created_by FK NN`, `done_by FK?`, `done_at?`, `done_note VARCHAR(300)?`, `cancel_reason VARCHAR(300)?`. Index `(admission_id, status, scheduled_at)`.
- `admission_observations` — LOG: `id`, `admission_id FK NN`, `recorded_by FK NN`, `temperature_c NUMERIC(4,1)?`, `heart_rate SMALLINT?`, `respiratory_rate SMALLINT?`, `weight_kg NUMERIC(6,2)?`, `appetite VARCHAR(10)? CHECK ('NORMAL','REDUCED','NONE')`, `note VARCHAR(500)?`, `abnormal BOOLEAN NN DEFAULT false`; chỉ `created_at`. Index `(admission_id, created_at DESC)`.
- `services.medical_type` thêm `INPATIENT` (BE-2); `visits.is_inpatient_origin` không cần.

### API (`inpatient-v1`, 10 endpoint)

Nhập / xuất: `POST /admissions` (A07; thân có đúng một trong `visitId` hoặc `boardingBookingId`; `kennelId` bắt buộc với `visitId`, cấm với `boardingBookingId`) · `GET /admissions` (A06 xem, A07, A08; lọc `status`) · `GET /admissions/{admissionId}` (có `billedDays`, `estimatedAmount`) · `POST /admissions/{admissionId}/discharge` (A07). Y lệnh: `POST /admissions/{admissionId}/orders` (A07) · `GET /admissions/{admissionId}/orders` · `POST /admission-orders/{admissionOrderId}/complete` (A07; A08 với việc không phải thuốc) · `POST /admission-orders/{admissionOrderId}/cancel` (A07). Theo dõi: `POST /admissions/{admissionId}/observations` (A07, A08) · `GET /admissions/{admissionId}/observations`.

### Hợp đồng chéo

| Interface | Owner | Dùng cho |
|---|---|---|
| `BoardingQueryApi.findCheckedInStay(petId)` | BE-2 (boarding) | BR-LT-13b |
| `AdmissionQueryApi.hasActiveAdmission(petId)` (guard kết thúc lưu trú — trả thú và thú mất — ở boarding gọi, BR-LT-09, BR-LT-12 + BR-LT-17) | BE-1 (inpatient) | BR-LT-17 |
| `KennelApi.occupyForTreatment(kennelId)`, `release(kennelId)` (chỉ khi nhập từ khám) | BE-2 (boarding) | BR-LT-13a, BR-LT-14 |
| `VisitOrderApi.addServiceLine(visitId, serviceId, quantity, unitPrice, actorId)` — nhận **đơn giá** đã chốt, không tự tra giá hiện hành (ADR-0124) | BE-2 (sales) | BR-LT-14 |
| `PetApi.recordWeight` (sẵn có) | BE-2 (customer) | BR-LT-16 |
| `VisitApi.startTreatmentVisit(petId, vetId, boardingBookingId)` (Order mở không dòng tự sinh) | BE-1 (visit) | BR-LT-13b |

`PetApi.markDeceased` không còn do nội trú gọi: lễ tân đánh dấu mất bằng `POST /pets/{petId}/mark-deceased` (customer-v1) ở bước 4 của BR-LT-14.

**Giả định:** A1 thêm loại `INPATIENT`; A2 dùng chung chuồng với lưu trú (nhập từ lưu trú giữ chuồng cũ, nhập từ khám lấy chuồng `AVAILABLE`); A3 phí lưu trú theo đêm và phí nội trú theo ngày tính riêng. **Đã đóng:** Q2 package `inpatient` thuộc BE-1 (T54); Q3 một y lệnh một lần thực hiện (ADR-0124); Q4 khách chưa xem nội trú ở GĐ2. **Đã đóng (2026-11-09, BE-2 xác nhận bằng lời qua Mạnh, 2026-11-09, chưa có văn bản của Dũng):** Q1 `INPATIENT` trong `services.medical_type`, dùng chung chuồng, chữ ký `addServiceLine(visitId, serviceId, quantity, unitPrice, actorId)` và guard kết thúc lưu trú cả `RETURNED` lẫn `PET_DECEASED`. **Dịch vụ nội trú** (loại `INPATIENT`, giá theo ngày) do SUPER_MANAGER tạo qua UC30, **không seed**; dữ liệu demo tạo bằng script seed-dev của FE.

---

## D. Khiếu nại — đã bỏ (ADR-0122, 2026-11-08)

UC85, UC86, ST16, ST17, FSM #26, BR-DG-05…09 (bản khiếu nại), module `complaint` và `complaint-v1` **không còn**. Feedback (UC83, UC84; `02` §16) đảm nhiệm vai trò đó: khách đăng nhập gửi (tối đa 5 mỗi ngày), BRANCH_MANAGER (chi nhánh mình) và SUPER_MANAGER đọc, **không trạng thái**, giữ 30 ngày rồi xóa (BR-DG-05 mới, ST24 `FeedbackCleanupJob`).

Lý do và phạm vi sửa: [ADR-0122](./adr/0122-drop-complaint-feedback-stateless-retention.md). Mã UC / ST / FSM giữ nguyên (gạch bỏ) để không lệch số. V39 (bảng), V40 (6 khóa `complaint.*`, 7 mẫu `COMPLAINT_*`) còn trong DB cho tới migration V51 (nợ D061).

---

## E. Tự trừ vật tư tiêu hao (ST07 · T71)

| Mã | Tác vụ | Kích hoạt |
|---|---|---|
| ST07 | Trừ vật tư tiêu hao theo định mức khi hoàn tất khám (UC48) hoặc dịch vụ thẩm mỹ (UC52) | SYS ← Visit#5 (hoàn tất lượt) |

| Mã | Tên | Mô tả | UC | Xử lý khi vi phạm |
|---|---|---|---|---|
| BR-KB-12 | Trừ vật tư theo định mức | Khi lượt hoàn tất (BR-KB-02) hoặc thẩm mỹ hoàn tất (UC52), hệ thống đọc **định mức** của từng dịch vụ trên Visit (BR-SP-10, fees-v1) và **xuất kho vật tư** tại chi nhánh theo FEFO (qua `StockApi`, BE-2) **trong cùng transaction** với hoàn tất; cộng dồn mọi dịch vụ của Visit (kể cả nội trú). Vật tư tiêu hao **không** thành dòng Order (đã nằm trong giá dịch vụ). | ST07 | — |
| BR-KB-13 | Thiếu tồn | **Không chặn hoàn tất lượt**: thiếu bao nhiêu thì xuất phần có, ghi **thiếu hụt** (`visit_material_usages.shortage_qty`) và báo BRANCH_MANAGER (thông báo `MATERIAL_SHORTAGE`); BRANCH_MANAGER xử lý (điều chỉnh tồn UC76 hoặc nhập thêm) rồi đánh dấu đã xử lý. *ASSUMPTION A1.* | ST07 | — |
| BR-KB-14 | Một lần mỗi Visit | Mỗi Visit chỉ trừ vật tư **một lần** (khóa duy nhất theo Visit, dịch vụ, sản phẩm); không hoàn tác vì bệnh án đã khóa (BR-KB-01). | ST07 | — |

Dữ liệu: `visit_material_usages` — LOG: `id`, `visit_id FK NN`, `service_id FK NN`, `product_id FK NN`, `branch_id FK NN`, `norm_qty INT NN CHECK > 0`, `issued_qty INT NN CHECK ≥ 0`, `shortage_qty INT NN CHECK ≥ 0`, `CHECK (norm_qty = issued_qty + shortage_qty)`, `resolved_by FK?`, `resolved_at?`, `UNIQUE (visit_id, service_id, product_id)`; Index `(branch_id) WHERE shortage_qty > 0 AND resolved_at IS NULL`. **Không có máy trạng thái và không có endpoint kích hoạt** (hệ quả của Visit#5).

**API `materialuse-v1` (3):** `GET /visits/{visitId}/material-usages` (A05, A07 phụ trách) · `GET /material-shortages` (A05, chưa xử lý) · `POST /material-shortages/{usageId}/resolve` (A05, ghi chú).

Hợp đồng chéo: `StockApi.issueMaterials(branchId, lines)` trả danh sách **thiếu** thay vì ném lỗi (như `StockApi.issueForPayment`, `06` §1) — BE-2; `CatalogQueryApi.findMaterialNorms(serviceIds)` — BE-2 (fees-v1). **Câu hỏi:** Q6 báo thiếu vật tư vào ST08 hiện có hay thông báo riêng.

---

## G. Tổng hợp T51 và việc khi duyệt

| Nhóm | UC / ST | FSM | Bảng mới / cột | Module API (endpoint) |
|---|---|---|---|---|
| Nhân sự (đã làm ở `02`–`06`) | UC34–38 | #11, #12 | 3 | `workforce` (14) |
<<<<<<< HEAD
| B. Phẫu thuật | UC50 | #23 | 1 | `surgery` (8) |
=======
| A. Khám tại nhà | UC43, UC51 | #22 | cột mới ở `appointments`, `visits`, `order_lines` | `homevisit` (6) |
| ~~B. Phẫu thuật~~ (bỏ, ADR-0125) | ~~UC50~~ | ~~#23~~ | — | — |
>>>>>>> 78fe062 (docs: chốt đặc tả nội trú (ADR-0124) và bỏ module phẫu thuật (ADR-0125).)
| C. Nội trú | UC55, UC56 | #24, #25 | 3 | `inpatient` (10) |
| E. Vật tư tiêu hao | ST07 | — | 1 | `materialuse` (3) |

<<<<<<< HEAD
**Đủ phạm vi T51:** 10 UC (UC34–38 ở NS, UC50, UC55, UC56, UC85, UC86) và 3 ST (ST07, ST16, ST17) đều có quy tắc, máy trạng thái (nơi có chuyển trạng thái), dữ liệu và API.
=======
**Đủ phạm vi T51:** 9 UC (UC34–38 ở NS, UC43, UC51, UC55, UC56) và 1 ST (ST07) đều có quy tắc, máy trạng thái (nơi có chuyển trạng thái), dữ liệu và API.
>>>>>>> 78fe062 (docs: chốt đặc tả nội trú (ADR-0124) và bỏ module phẫu thuật (ADR-0125).)

### Cấu hình [CFG] mới (thêm vào `ConfigKey` ở T55)

*(ADR-0122)* Không còn khóa `complaint.*` (6 khóa đã seed ở V40 sẽ gỡ ở V51). Khóa mới ở phần feedback (BE-2): `feedback.retention_days` (30, khoảng 7–365).

### Thay đổi cần BE-2 (catalog, boarding, sales) xác nhận

<<<<<<< HEAD
`services.medical_type` thêm `SURGERY`, `INPATIENT`; chuồng dùng chung cho nội trú; guard trả thú khi còn ca nội trú; `StockApi.issueMaterials`.
=======
`services.medical_type` thêm `INPATIENT` (`SURGERY` đã bỏ, ADR-0125); `order_lines.line_type` thêm `HOME_FEE`; chuồng dùng chung cho nội trú; guard kết thúc lưu trú (trả thú, thú mất) khi còn ca nội trú; `VisitOrderApi.addServiceLine` có `unitPrice` (ADR-0124); `StockApi.issueMaterials`.
>>>>>>> 78fe062 (docs: chốt đặc tả nội trú (ADR-0124) và bỏ module phẫu thuật (ADR-0125).)

### Quyết định T54 (Hiến duyệt 5/11/2026) cho các câu hỏi của BE-1

| Câu hỏi | Quyết định |
|---|---|
<<<<<<< HEAD
| Q1 `medical_type` thêm `SURGERY`, `INPATIENT` | Chốt theo đề xuất; BE-2 xác nhận khi làm T55 phần BE-2 |
| Q2 package `inpatient`, `complaint` | **Thuộc BE-1** (xem đầu file) |
| Q4 khách xem nội trú | Khách **chưa** xem nội trú ở GĐ2 |
| Q5 ảnh đính kèm khiếu nại | Chưa có ở GĐ2 |
=======
| Q1 `medical_type` thêm `SURGERY`, `INPATIENT` | Chốt theo đề xuất; BE-2 xác nhận khi làm T55 phần BE-2. `SURGERY` bỏ ở ADR-0125 |
| Q2 package `inpatient` | **Thuộc BE-1** (xem đầu file) |
| Q3 bán kính khám tại nhà | Không giới hạn (A2) |
| Q4 thu tiền tại nhà; khách xem nội trú | Thu tại chi nhánh (A4); khách **chưa** xem nội trú ở GĐ2 |
>>>>>>> 78fe062 (docs: chốt đặc tả nội trú (ADR-0124) và bỏ module phẫu thuật (ADR-0125).)
| Q6 báo thiếu vật tư | Thông báo riêng `MATERIAL_SHORTAGE`, không đưa vào ST08 |
| Còn lại (A1…A4) | **Chốt theo đề xuất**; BE-1 muốn đổi thì qua ADR |

### Liên kết với `02`–`06` (đã làm ở T54)

| File | Tình trạng |
|---|---|
<<<<<<< HEAD
| `02` | Mục 19 trỏ tới BR-KB-08…14, BR-LT-13…17, BR-DG-05…09 ở file này |
| `03` | Mục trỏ tới FSM #23…#26 |
| `04`, `05` | Mục trỏ tới model, bảng và cột mới ở file này |
| `06` | Interface chéo gộp vào bảng tầng 3 kèm hạn giao; `ConfigKey` mới làm ở T55 |
| `api/INDEX.md` | Đã thêm 4 module `surgery`, `inpatient`, `complaint`, `materialuse` |
=======
| `02` | Mục 19 trỏ tới BR-LH-13, 14, BR-KB-07…14, BR-BH-16, BR-LT-13…17 ở file này |
| `03` | Mục trỏ tới FSM #22…#25 (#23 bỏ ở ADR-0125, #26 bỏ ở ADR-0122) |
| `04`, `05` | Mục trỏ tới model, bảng và cột mới ở file này |
| `06` | Interface chéo gộp vào bảng tầng 3 kèm hạn giao; `ConfigKey` mới làm ở T55 |
| `api/INDEX.md` | Đã thêm 4 module `homevisit`, `surgery`, `inpatient`, `materialuse` (`complaint` bỏ ở ADR-0122, `surgery` bỏ ở ADR-0125) |
>>>>>>> 78fe062 (docs: chốt đặc tả nội trú (ADR-0124) và bỏ module phẫu thuật (ADR-0125).)

