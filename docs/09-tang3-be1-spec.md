# Pet Care Ecosystem — Đặc tả bổ sung tầng 3, phần BE-1 (T51)

> **Đặc tả chính thức phần BE-1 · T51 · đã duyệt ở T54 (Hiến, nhóm trưởng, 5/11/2026).** BE-1 (Mạnh) đọc khi bắt đầu task tương ứng; muốn đổi thì qua ADR. File này gom quy tắc nghiệp vụ, máy trạng thái, mô hình dữ liệu / ERD, API và hợp đồng chéo module cho phần còn lại của T51: **UC43, UC50, UC51, UC55, UC56, UC85, UC86 · ST07, ST16, ST17** (7 UC, 3 ST). Phần Nhân sự (UC34–38) đã viết ở `02`–`06` và `api/workforce-v1`.
>
> **Vì sao đặt ở file riêng:** như `08` (BE-2) và `07` (AI): `02`–`06` đang được hai thành viên backend sửa song song. **Đây là nguồn chính thức; `02`–`05` chỉ có mục trỏ tới đây** (không chép trùng); interface chéo gộp ở `06`. **Thứ tự các mục theo hạn trong `time.md`:** A (UC43, UC51 — T60, 23–25/11) → B (UC50 — T61) → C (UC55, UC56 — T69) → D (UC85, UC86, ST16, ST17 — T70) → E (ST07 — T71).
> **Hợp đồng máy đọc:** [`api/homevisit-v1.md`](./api/homevisit-v1.md), [`surgery-v1.md`](./api/surgery-v1.md), [`inpatient-v1.md`](./api/inpatient-v1.md), [`complaint-v1.md`](./api/complaint-v1.md), [`materialuse-v1.md`](./api/materialuse-v1.md).

**Quy ước đánh số (đã chốt ở T54):** BR tiếp nối tiền tố hiện có (đã kiểm số lớn nhất trong `02`: BR-LH-12, BR-KB-06, BR-LT-12, BR-DG-04, BR-BH-06); FSM của BE-1 bắt đầu từ **#22** (`08` dùng #14–#21, AI #13, NS #11–#12). BE-1 thêm rule mới phải lấy số sau số lớn nhất ở **cả** `02` và file này.

**Quy ước cột (cho migration ở T55):** cột `*_at` là `TIMESTAMPTZ`; `*_date`, `*_on` là `DATE`; `*_by` và `*_id` (trừ khi ghi khác) là `BIGINT` khóa ngoại; `id` là `BIGINT` khóa chính; `quantity`, `*_qty`, `*_count` là `INT`; tiền là `BIGINT` (đồng); trạng thái và loại là `VARCHAR(30)` + `CHECK` (05 §0); `?` sau tên cột là cho phép NULL. Cột liệt kê không ghi kiểu thì theo quy ước này.

**Chủ sở hữu (đã chốt ở T54, Hiến quyết 5/11/2026):** khiếu nại (`complaint`) và nội trú (`inpatient`) là package **mới của BE-1**, cùng `homevisit`, `surgery`, `materialuse`. Lý do: đề cương 2.4.4 ghi "LT (nội trú) – Mạnh", `time.md` giao T69, T70 cho BE-1, và để BE-1 không sửa module của BE-2. Feedback (UC83, 84) vẫn ở `content` của BE-2. Chỗ nào cần dữ liệu của `boarding`, `inventory`, `sales` thì gọi interface của BE-2 (ở `06`).

**Đặt tên path:** đã grep `docs/api/openapi/*.yaml`. Khám tại nhà dùng `/home-visit-appointments`, không sửa `/appointments` của appointment-v1; hủy và đổi giờ dùng lại `POST /appointments/{appointmentId}/cancel` và `…/reschedule` sẵn có.

---

## A. Khám tại nhà (UC43, UC51 · T60)

| Mã | Use case | Actor |
|---|---|---|
| UC43 | Đặt lịch khám tại nhà (khách đặt, hoặc lễ tân đặt hộ) | A02, A06 |
| UC51 | Khám tại nhà (bác sĩ thực hiện lịch khám tại nhà) | A07 |

**Cách tiếp cận ít phát minh nhất:** khám tại nhà là **một lịch hẹn nhóm Khám (loại `EXAM`) có cờ tại nhà**, nên dùng lại khung giờ, quota, nhắc lịch, hủy / đổi giờ và hạn chế đặt online của lịch hẹn (BR-LH-01…12). Chỉ thêm: địa chỉ, phí khám tại nhà chốt lúc đặt (BR-SP-09, BR-SP-11) và cách bác sĩ **tự bắt đầu** lượt khám tại địa chỉ mà không qua tiếp nhận và hàng đợi.

### Quy tắc

| Mã | Tên | Mô tả | UC | Xử lý khi vi phạm |
|---|---|---|---|---|
| BR-LH-13 | Đặt lịch khám tại nhà | Khách hoặc lễ tân (đặt hộ) tạo lịch hẹn `MEDICAL` loại `EXAM` kèm cờ tại nhà: chọn thú, chi nhánh **đã bật khám tại nhà** (BR-SP-09), dịch vụ khám đang bật ở chi nhánh, ngày và khung 30 phút theo BR-LH-02…05 (*ASSUMPTION A1:* tính vào quota nhóm Khám / Tiêm của chi nhánh), địa chỉ và SĐT liên hệ. Khách chọn địa chỉ từ **sổ địa chỉ của chính mình** (BR-TK-18); lễ tân đặt hộ chọn hoặc nhập địa chỉ cho khách đó. **Phí khám tại nhà và địa chỉ được chốt (snapshot) lúc đặt** (BR-SP-11). Không giới hạn vùng phục vụ (*ASSUMPTION A2*, Q3). | UC43 | 400 `BR-LH-13`: chi nhánh chưa bật, địa chỉ sai, vi phạm BR-LH-04, 05, 09. |
| BR-LH-14 | Hủy, đổi giờ, không gặp được | Hủy và đổi giờ như BR-LH-06, 07 (hủy muộn tính như lịch thường). Phí khám tại nhà **chỉ phát sinh khi thực hiện** (thành dòng Order ở BR-KB-07), hủy không mất phí. ST05 **bỏ qua** lịch tại nhà (bác sĩ có thể đến muộn); nếu đến nơi mà không gặp được khách, bác sĩ ghi **không gặp được** → `NO_SHOW` (tính vào hạn chế đặt online, BR-LH-09). *ASSUMPTION A3.* | UC43, UC51 | 409 `INVALID_STATE_TRANSITION`. |
| BR-KB-07 | Thực hiện khám tại nhà | VET **của chi nhánh** tự bắt đầu lịch `BOOKED` tại nhà, **trong ngày hẹn**, không qua check-in của lễ tân và không vào hàng đợi (BR-TN): hệ thống tạo Visit `IN_PROGRESS` **gán đúng VET đó** (BR-TN-05, `is_home_visit = true`), lịch hẹn → `CHECKED_IN`, mở Order nguồn `VISIT` (BR-BH-01) với dòng dịch vụ khám và **dòng phí khám tại nhà** (snapshot). Từ đó bệnh án, kê đơn, tiêm, hoàn tất theo UC48 (BR-KB-01…04, 06). | UC51 | 400 `BR-KB-07`: không phải lịch tại nhà của chi nhánh mình, ngoài ngày hẹn, thú đã mất, Visit mở khác của thú (BR-TN-01). |
| BR-BH-16 | Thu tiền khám tại nhà | Order của lượt khám tại nhà vào `PENDING` khi hoàn tất (BR-KB-02) và **thu tại chi nhánh qua lễ tân** (UC70, ca thu ngân của chi nhánh), khách đến quầy hoặc chuyển khoản. Chưa thu tại nhà (*ASSUMPTION A4*, Q4). | UC51 | — |

### Máy trạng thái #22 — Lịch khám tại nhà (bổ sung cho `03` #3)

Dùng lại bảng trạng thái của lịch hẹn (`BOOKED`, `CHECKED_IN`, `COMPLETED`, `CANCELLED`, `NO_SHOW`); chỉ khác **người kích hoạt và điều kiện** của ba chuyển:

| # | Từ → Sang | Sự kiện | Người kích hoạt | Điều kiện | Hệ quả | Nguồn |
|---|---|---|---|---|---|---|
| 1 | — → `BOOKED` | Đặt lịch khám tại nhà | A02, A06 | BR-LH-13 | Chốt phí và địa chỉ; nhắc lịch ST03 như lịch thường | UC43 |
| 2 | `BOOKED` → `CHECKED_IN` | Bắt đầu khám tại nhà | A07 | BR-KB-07 | Tạo Visit `IN_PROGRESS` gán VET; mở Order `VISIT` + dòng phí (Visit#1 biến thể) | UC51 |
| 3 | `BOOKED` → `NO_SHOW` | Không gặp được khách | A07 | Ngày hẹn; có ghi chú | Tính vào BR-LH-09; thông báo khách | BR-LH-14 · UC51 |

Các chuyển còn lại (`CHECKED_IN` → `COMPLETED`, `BOOKED` → `CANCELLED`) giữ nguyên `03` #3 (hoàn tất khi lượt khám hoàn tất, BR-LH-11). ST05 không áp dụng cho lịch tại nhà.

### Dữ liệu (05 bổ sung)

- `appointments` thêm: `is_home_visit BOOLEAN NN DEFAULT false`, `home_address VARCHAR(300)?`, `home_contact_phone VARCHAR(15)?`, `home_fee BIGINT?`. `CHECK ((is_home_visit) = (home_address IS NOT NULL AND home_fee IS NOT NULL))`; `CHECK (NOT is_home_visit OR service_group = 'MEDICAL')`; `home_fee ≥ 0`.
- `visits` thêm `is_home_visit BOOLEAN NN DEFAULT false` (để hàng đợi bỏ qua và báo cáo phân biệt).
- `order_lines.line_type` thêm `HOME_FEE` (không có `service_id`, `product_id`; cập nhật hai `CHECK` hiện có ở `05` §8). `orders.source` giữ `VISIT`.

### API (`homevisit-v1`, 6 endpoint)

`POST /home-visit-appointments` (A02, A06) · `GET /me/home-visit-appointments` (A02) · `GET /home-visit-appointments` (A06, A07 — chi nhánh của mình, lọc `date`, `status`) · `GET /home-visit-appointments/{appointmentId}` · `POST /home-visit-appointments/{appointmentId}/start` (A07, trả `visitId`, `orderId`) · `POST /home-visit-appointments/{appointmentId}/mark-unreachable` (A07, ghi chú). Xem phí: `GET /branches/{branchId}/home-visit-fee` (fees-v1, mở thêm cho A02). Khung giờ trống: `GET /branches/{branchId}/available-slots` (appointment-v1). Hủy / đổi giờ: endpoint sẵn có.

### Hợp đồng chéo

| Interface | Owner | Dùng cho |
|---|---|---|
| `CatalogQueryApi.findHomeVisitFee(branchId)` (bật / tắt + phí) | BE-2 (catalog / fees) | BR-LH-13, BR-SP-09 |
| `VisitOrderApi.addHomeFeeLine(visitId, amount)` | BE-2 (sales) | BR-KB-07 |
| `VisitApi.startHomeVisit(appointmentId, vetId)` (nội bộ BE-1) | BE-1 (visit) | #2 |
| ST05 loại trừ `is_home_visit` | BE-1 (appointment) | BR-LH-14 |

**Giả định:** A1 dùng chung quota nhóm Khám / Tiêm; A2 không giới hạn vùng; A3 ST05 bỏ qua lịch tại nhà; A4 thu tiền tại chi nhánh. **Câu hỏi:** Q3 có cần giới hạn bán kính phục vụ / phí theo khoảng cách không (đã ghi ở fees-v1 Q1); Q4 có cần thu tiền tại nhà (tiền mặt / chuyển khoản do bác sĩ nhận) không.

---

## B. Phẫu thuật (UC50 · T61)

| Mã | Use case | Actor |
|---|---|---|
| UC50 | Phẫu thuật: lập cam kết, thực hiện (phát sinh từ lượt khám, UC48 «extend» UC50) | A07, A06 |

### Quy tắc

| Mã | Tên | Mô tả | UC | Xử lý khi vi phạm |
|---|---|---|---|---|
| BR-KB-08 | Phẫu thuật phát sinh từ lượt khám | VET đang phụ trách một Visit `IN_PROGRESS` tạo **yêu cầu phẫu thuật** gắn Visit đó, chọn một dịch vụ thuộc nhóm Khám / Tiêm có **loại `SURGERY`** (*ASSUMPTION A1:* mở rộng thuộc tính loại của BR-SP-06 từ `EXAM`, `VACCINE` thêm `SURGERY`; BE-2 xác nhận, Q1), kèm ghi chú kế hoạch. Phẫu thuật **không** thành lịch hẹn riêng (BR-LH-01: phẫu thuật chỉ phát sinh từ khám). | UC50 | 400 `BR-KB-08`: Visit không `IN_PROGRESS`, không phải VET phụ trách, dịch vụ không phải `SURGERY`. |
| BR-KB-09 | Cam kết phẫu thuật | Trước khi thực hiện phải có **cam kết** do lễ tân lập với khách: nội dung (rủi ro, chi phí dự kiến, phương pháp), họ tên và quan hệ của người ký (chủ thú hoặc người đại diện), người lập và thời điểm. Khách ký **trên giấy**; hệ thống chỉ **ghi nhận đã có cam kết** (`CONSENTED`), không ký điện tử (*ASSUMPTION A2*). Nội dung cam kết là bản chụp, không sửa sau khi lập. | UC50 | 400 / 409. |
| BR-KB-10 | Thực hiện và hoàn tất | VET bắt đầu khi `CONSENTED`; hoàn tất kèm ghi chú kết quả (biến chứng nếu có). **Khi hoàn tất**, hệ thống thêm dịch vụ phẫu thuật vào **Order của Visit** với giá chốt lúc đó (UC50 «extend» UC67, BR-BH-03). Visit **không hoàn tất** (BR-KB-02) được khi còn phẫu thuật `PLANNED` / `CONSENTED` / `IN_PROGRESS`. | UC50 | 409 `INVALID_STATE_TRANSITION`; 400 `BR-KB-10` khi hoàn tất Visit còn phẫu thuật mở. |
| BR-KB-11 | Hủy hoặc dừng | VET hoặc lễ tân hủy phẫu thuật `PLANNED` / `CONSENTED` (lý do bắt buộc); VET **dừng** phẫu thuật `IN_PROGRESS` (lý do và ghi chú bắt buộc), không tính dịch vụ (*ASSUMPTION A3*). Hủy Visit (BR-TN-07) tự hủy phẫu thuật chưa `COMPLETED`. | UC50 | 400 / 409. |

### Máy trạng thái #23 — Phẫu thuật

`surgeries.status` · KB · Tầng 3.

| Trạng thái | Loại | Ý nghĩa |
|---|---|---|
| `PLANNED` | INIT | VET đã lập kế hoạch, chờ cam kết |
| `CONSENTED` | MID | Đã có cam kết của khách |
| `IN_PROGRESS` | MID | Đang phẫu thuật |
| `COMPLETED` | FINAL | Đã xong, dịch vụ đã vào Order |
| `CANCELLED` | FINAL | Đã hủy hoặc dừng (có lý do) |

| # | Từ → Sang | Sự kiện | Người kích hoạt | Điều kiện | Hệ quả | Nguồn |
|---|---|---|---|---|---|---|
| 1 | — → `PLANNED` | Lập kế hoạch | A07 (phụ trách Visit) | BR-KB-08 | Thông báo lễ tân chi nhánh | UC50 |
| 2 | `PLANNED` → `CONSENTED` | Lập cam kết | A06 (chi nhánh của Visit) | Đủ nội dung và người ký | Ghi audit | BR-KB-09 · UC50 |
| 3 | `CONSENTED` → `IN_PROGRESS` | Bắt đầu | A07 | Visit còn `IN_PROGRESS` | `started_at` | BR-KB-10 · UC50 |
| 4 | `IN_PROGRESS` → `COMPLETED` | Hoàn tất | A07 | Có ghi chú kết quả | Thêm dòng dịch vụ vào Order của Visit (`VisitOrderApi`) | BR-KB-10 · UC50 |
| 5 | `PLANNED` / `CONSENTED` / `IN_PROGRESS` → `CANCELLED` | Hủy hoặc dừng | A07; A06 (chỉ `PLANNED` / `CONSENTED`) | Có lý do | Không tính dịch vụ | BR-KB-11 · UC50 |
| 6 | `PLANNED` / `CONSENTED` / `IN_PROGRESS` → `CANCELLED` | Hủy lượt khám | SYS ← Visit#6 | — | Lý do hệ thống | BR-KB-11, BR-TN-07 |

### Dữ liệu (05 bổ sung)

- `surgeries` — ROOT · SM #23: `id`, `visit_id FK NN`, `pet_id FK NN`, `branch_id FK NN`, `service_id FK→services NN` (loại `SURGERY`, kiểm trong service), `status VARCHAR(30) NN CHECK`, `planned_by FK NN`, `plan_note VARCHAR(500)?`, `consent_text TEXT?`, `consent_signer_name VARCHAR(100)?`, `consent_signer_relation VARCHAR(50)?`, `consented_by FK?`, `consented_at?`, `started_at?`, `completed_at?`, `result_note TEXT?`, `cancel_reason VARCHAR(300)?`, `cancelled_by FK?` (NULL = hệ thống), `order_line_id FK→order_lines? UQ`. `CHECK` cột ↔ trạng thái (cùng khuôn `care_tasks`, ADR-0081): `CONSENTED` trở đi có `consent_*`; `COMPLETED` có `result_note`, `order_line_id`; `CANCELLED` có `cancel_reason`. Index `(visit_id)`, `(branch_id, status)`.
- `services.medical_type` thêm `SURGERY` (BE-2, `05` §4) và `INPATIENT` (mục C).

### API (`surgery-v1`, 8 endpoint)

`POST /visits/{visitId}/surgeries` (A07) · `GET /visits/{visitId}/surgeries` (A07, A06, A08 chỉ xem) · `GET /surgeries` (A06, A07 — chi nhánh của mình, lọc `status`; để lễ tân thấy việc chờ cam kết) · `GET /surgeries/{surgeryId}` · `POST /surgeries/{surgeryId}/consent` (A06) · `POST …/start` (A07) · `POST …/complete` (A07) · `POST …/cancel` (A07, A06).

### Hợp đồng chéo

`VisitOrderApi.addServiceLine(visitId, serviceId, quantity)` (sẵn có hoặc thêm, BE-2); `CatalogQueryApi` trả `medicalType = SURGERY` (BE-2); guard "Visit không hoàn tất khi còn phẫu thuật mở" (nội bộ BE-1, `visit` gọi `surgery`).

**Giả định:** A1 thêm loại `SURGERY`; A2 cam kết ghi nhận, không ký điện tử; A3 dừng phẫu thuật không tính dịch vụ. **Câu hỏi:** Q1 BE-2 xác nhận `SURGERY` / `INPATIENT` trong `medical_type`; Q5 có cần phụ phẫu (nhiều bác sĩ) không.

---

## C. Nội trú: nhập / xuất viện và điều trị (UC55, UC56 · T69)

| Mã | Use case | Actor |
|---|---|---|
| UC55 | Nhập / xuất viện (gồm chuyển thú từ lưu trú sang điều trị) | A07 |
| UC56 | Điều trị nội trú: ra y lệnh, theo dõi hậu phẫu | A07 (A08 thực hiện việc không phải thuốc, ghi theo dõi) |

### Quy tắc

| Mã | Tên | Mô tả | UC | Xử lý khi vi phạm |
|---|---|---|---|---|
| BR-LT-13 | Nhập viện | VET nhập viện một thú vào điều trị nội trú từ **(a) lượt khám đang `IN_PROGRESS`** của mình, hoặc **(b) đặt chỗ lưu trú đang `CHECKED_IN`** (chuyển từ lưu trú sang điều trị): với (b) hệ thống tạo một **Visit điều trị** `IN_PROGRESS` gán VET đó (không qua hàng đợi, như BR-KB-07) rồi nhập viện vào Visit này, nên mọi khoản phí vẫn vào Order `VISIT` (BR-BH-01). Mỗi thú tối đa **một** ca nội trú `ADMITTED` (BR-TN-01). Chọn dịch vụ nội trú (loại `INPATIENT`, *ASSUMPTION A1*, giá **theo ngày**, chốt lúc nhập viện) và chuồng `AVAILABLE` của chi nhánh (*ASSUMPTION A2:* dùng chung chuồng với lưu trú). | UC55 | 400 `BR-LT-13`: thú đã nội trú, đặt chỗ không `CHECKED_IN`, hết chuồng, thú đã mất. |
| BR-LT-14 | Xuất viện | VET xuất viện kèm **tóm tắt điều trị** và kết cục (`HOME` về nhà / tiếp tục lưu trú, `DECEASED` thú mất). Điều kiện: không còn y lệnh `ORDERED` (phải xong hoặc hủy). Hệ thống thêm dòng dịch vụ nội trú vào Order của Visit với số lượng = **số ngày** (từ ngày nhập đến ngày xuất, tối thiểu 1) × giá đã chốt (UC55 «extend» UC67); giải phóng chuồng nếu chuồng được cấp riêng cho nội trú. Kết cục `DECEASED` yêu cầu VET đánh dấu thú đã mất theo BR-KH-05 (xác nhận hai bước). Visit hoàn tất theo UC48 sau xuất viện; không hoàn tất Visit khi còn ca nội trú `ADMITTED`. | UC55 | 400 `BR-LT-14`. |
| BR-LT-15 | Y lệnh nội trú | VET ra y lệnh cho ca `ADMITTED`: loại `MEDICATION`, `FEEDING`, `MONITORING`, `PROCEDURE`, `OTHER`; nội dung (≤ 500 ký tự) và giờ thực hiện dự kiến. Y lệnh `MEDICATION` liên kết một dòng đơn thuốc của Visit (kê qua visit-v1 #16, kiểm tồn BR-KB-03). Việc **không phải thuốc** do VET hoặc CARETAKER thực hiện; **thuốc** chỉ VET. Thực hiện → `DONE` kèm ghi chú; VET hủy y lệnh chưa làm kèm lý do. | UC56 | 403 / 409. |
| BR-LT-16 | Theo dõi hậu phẫu | VET hoặc CARETAKER ghi **chỉ số theo dõi** (nhiệt độ, nhịp tim, nhịp thở, cân nặng, ăn uống, ghi chú, cờ **bất thường**) — chỉ thêm, không sửa, không xóa (LOG). Mục đánh dấu bất thường báo VET phụ trách và khách theo cách của nhật ký lưu trú (BR-LT-12, `NotificationApi`). Cân nặng ghi vào hồ sơ cân của thú (BR-KH-04). | UC56 | — |
| BR-LT-17 | Phạm vi và liên kết lưu trú | Nhân viên chỉ thấy ca nội trú của chi nhánh mình (BR-QT-03). Thú nhập viện **từ lưu trú**: đặt chỗ giữ nguyên `CHECKED_IN`; lễ tân **không trả thú** (BR-LT-09) khi còn ca `ADMITTED` (guard ở boarding); phí lưu trú theo đêm vẫn tính theo đặt chỗ và phí nội trú tính riêng theo ngày (*ASSUMPTION A3*). Khách chưa có màn hình xem nội trú (Q4). | UC55 | 409 `BR-LT-17` khi trả thú còn nội trú. |

### Máy trạng thái #24 — Nhập viện

`admissions.status` · LT (nội trú) · Tầng 3.

| Trạng thái | Loại | Ý nghĩa |
|---|---|---|
| `ADMITTED` | INIT | Đang điều trị nội trú |
| `DISCHARGED` | FINAL | Đã xuất viện (kết cục `HOME` hoặc `DECEASED`) |

| # | Từ → Sang | Sự kiện | Người kích hoạt | Điều kiện | Hệ quả | Nguồn |
|---|---|---|---|---|---|---|
| 1 | — → `ADMITTED` | Nhập viện | A07 | BR-LT-13 | Chiếm chuồng; (b) tạo Visit điều trị; chốt giá nội trú | UC55 |
| 2 | `ADMITTED` → `DISCHARGED` | Xuất viện | A07 | Không còn y lệnh `ORDERED`; có tóm tắt | Thêm dòng dịch vụ nội trú vào Order; giải phóng chuồng; `DECEASED` → quy trình BR-KH-05 | BR-LT-14 · UC55 |

### Máy trạng thái #25 — Y lệnh nội trú

`admission_orders.status` · LT (nội trú) · Tầng 3.

| Trạng thái | Loại | Ý nghĩa |
|---|---|---|
| `ORDERED` | INIT | Đã ra y lệnh, chờ thực hiện |
| `DONE` | FINAL | Đã thực hiện (có ghi chú) |
| `CANCELLED` | FINAL | Đã hủy (có lý do) |

| # | Từ → Sang | Sự kiện | Người kích hoạt | Điều kiện | Hệ quả | Nguồn |
|---|---|---|---|---|---|---|
| 1 | — → `ORDERED` | Ra y lệnh | A07 | Ca `ADMITTED`; `MEDICATION` có dòng đơn thuốc | — | BR-LT-15 · UC56 |
| 2 | `ORDERED` → `DONE` | Thực hiện | A07; A08 (không phải thuốc) | Có ghi chú | — | BR-LT-15 · UC56 |
| 3 | `ORDERED` → `CANCELLED` | Hủy y lệnh | A07 | Có lý do | — | BR-LT-15 · UC56 |

### Dữ liệu (05 bổ sung)

- `admissions` — ROOT · SM #24: `id`, `code VARCHAR(20) UQ` (`NT-yyMMdd-xxxx`), `visit_id FK NN`, `pet_id FK NN`, `branch_id FK NN`, `boarding_booking_id FK→boarding_bookings?`, `kennel_id FK→kennels?`, `service_id FK→services NN` (loại `INPATIENT`), `unit_price BIGINT NN` (snapshot / ngày), `status`, `admitted_by FK NN`, `admitted_at NN`, `discharge_outcome VARCHAR(20)? CHECK ('HOME','DECEASED')`, `discharge_summary TEXT?`, `discharged_by FK?`, `discharged_at?`, `order_line_id FK→order_lines? UQ`. `UNIQUE (pet_id) WHERE status = 'ADMITTED'`; `CHECK` xuất viện ↔ cột kết cục (khuôn `care_tasks`); Index `(branch_id, status)`.
- `admission_orders` — ROOT · SM #25: `id`, `admission_id FK NN`, `order_type VARCHAR(20) NN CHECK ('MEDICATION','FEEDING','MONITORING','PROCEDURE','OTHER')`, `content VARCHAR(500) NN`, `scheduled_at TIMESTAMPTZ NN`, `status`, `prescription_item_id FK→prescription_items?` (bắt buộc với `MEDICATION`), `created_by FK NN`, `done_by FK?`, `done_at?`, `done_note VARCHAR(300)?`, `cancel_reason VARCHAR(300)?`. Index `(admission_id, status, scheduled_at)`.
- `admission_observations` — LOG: `id`, `admission_id FK NN`, `recorded_by FK NN`, `temperature_c NUMERIC(4,1)?`, `heart_rate SMALLINT?`, `respiratory_rate SMALLINT?`, `weight_kg NUMERIC(6,2)?`, `appetite VARCHAR(10)? CHECK ('NORMAL','REDUCED','NONE')`, `note VARCHAR(500)?`, `abnormal BOOLEAN NN DEFAULT false`; chỉ `created_at`. Index `(admission_id, created_at DESC)`.
- `services.medical_type` thêm `INPATIENT` (BE-2); `visits.is_inpatient_origin` không cần.

### API (`inpatient-v1`, 10 endpoint)

Nhập / xuất: `POST /admissions` (A07; thân có đúng một trong `visitId` hoặc `boardingBookingId`) · `GET /admissions` (A06 xem, A07, A08; lọc `status`) · `GET /admissions/{admissionId}` · `POST /admissions/{admissionId}/discharge` (A07). Y lệnh: `POST /admissions/{admissionId}/orders` (A07) · `GET /admissions/{admissionId}/orders` · `POST /admission-orders/{admissionOrderId}/complete` (A07; A08 với việc không phải thuốc) · `POST /admission-orders/{admissionOrderId}/cancel` (A07). Theo dõi: `POST /admissions/{admissionId}/observations` (A07, A08) · `GET /admissions/{admissionId}/observations`.

### Hợp đồng chéo

| Interface | Owner | Dùng cho |
|---|---|---|
| `BoardingQueryApi.findCheckedInStay(petId)` | BE-2 (boarding) | BR-LT-13b |
| `AdmissionQueryApi.hasActiveAdmission(petId)` (guard trả thú ở boarding gọi, BR-LT-09 + BR-LT-17) | BE-1 (inpatient) | BR-LT-17 |
| `KennelApi.occupyForTreatment(kennelId)`, `release(kennelId)` | BE-2 (boarding) | BR-LT-13, BR-LT-14 (A2) |
| `VisitOrderApi.addServiceLine(visitId, serviceId, quantity)` | BE-2 (sales) | BR-LT-14 |
| `PetApi.recordWeight` (sẵn có) và `PetApi.markDeceased` (sẵn có) | BE-2 (customer) | BR-LT-16, BR-LT-14 |
| `VisitApi.startTreatmentVisit(petId, vetId, boardingBookingId)` | BE-1 (visit) | BR-LT-13b |

**Giả định:** A1 thêm loại `INPATIENT`; A2 dùng chung chuồng với lưu trú; A3 tính phí lưu trú theo đêm và phí nội trú theo ngày tách riêng. **Câu hỏi:** Q1 BE-2 xác nhận `INPATIENT` và dùng chung chuồng; Q2 package `inpatient` thuộc BE-1 (đề xuất) hay `boarding` của BE-2; Q4 khách có xem được tình trạng nội trú không (đề xuất có, một màn hình đọc, GĐ2 hoặc sau).

---

## D. Khiếu nại (UC85, UC86 · ST16, ST17 · T70)

| Mã | Use case | Actor |
|---|---|---|
| UC85 | Gửi và theo dõi khiếu nại (tạo, xem, đóng, mở lại; lễ tân tạo thay khách) | A02, A06 |
| UC86 | Xử lý khiếu nại | A05, A04 |

Khác feedback (UC83–84, BR-DG-01…04): feedback **một chiều, không theo dõi xử lý**; khiếu nại có **vòng đời, người xử lý, trao đổi hai chiều và hạn xử lý**.

### Quy tắc

| Mã | Tên | Mô tả | UC | Xử lý khi vi phạm |
|---|---|---|---|---|
| BR-DG-05 | Gửi khiếu nại | CUSTOMER gửi khiếu nại gồm: chủ đề (`SERVICE_QUALITY`, `GROOMING`, `BOARDING`, `PURCHASE`, `STAFF`, `OTHER`), chi nhánh (bắt buộc trừ chủ đề `OTHER`), đối tượng liên quan tùy chọn (một Order, đơn online, Visit hoặc đặt chỗ **của chính khách**), nội dung 10–2 000 ký tự. Lễ tân **tạo thay khách** với cùng dữ liệu, chọn khách ở chi nhánh mình và ghi nhận người tạo. Khách có tối đa 10 khiếu nại `OPEN` / `IN_PROGRESS` cùng lúc **[CFG]** `complaint.max_open`. | UC85 | 400 `BR-DG-05`. |
| BR-DG-06 | Phạm vi xem và xử lý | Khách xem và trao đổi khiếu nại **của mình**. BRANCH_MANAGER xử lý khiếu nại gắn **chi nhánh mình**; SUPER_MANAGER xử lý **mọi** khiếu nại, gồm đã leo thang và khiếu nại không gắn chi nhánh. Lễ tân chỉ xem khiếu nại do mình tạo thay khách. Khiếu nại là dữ liệu nhạy: chi nhánh khác không thấy (BR-QT-03). | UC85, UC86 | 403 / 404. |
| BR-DG-07 | Xử lý và trao đổi | Người xử lý **nhận** (`IN_PROGRESS`, gán chính mình), trao đổi qua **tin nhắn** hai chiều với khách, rồi đánh dấu **đã giải quyết** (`RESOLVED`) kèm kết luận. Khách **đóng** khi hài lòng hoặc **rút** khi còn xử lý (`CLOSED`). Khách **mở lại** trong 7 ngày **[CFG]** `complaint.reopen_days` kể từ lúc `RESOLVED`, tối đa 2 lần **[CFG]** `complaint.max_reopen`; khiếu nại bị rút thì không mở lại. | UC85, UC86 | 409 `INVALID_STATE_TRANSITION`. |
| BR-DG-08 | Hạn xử lý, leo thang (ST16) | Khiếu nại `OPEN` chưa được nhận quá 24 giờ **[CFG]** `complaint.sla_open_hours`, hoặc `IN_PROGRESS` quá 72 giờ **[CFG]** `complaint.sla_resolve_hours` kể từ hoạt động cuối, thì ST16 **leo thang**: đặt `escalated_at`, thông báo SUPER_MANAGER, hiện ở danh sách "quá hạn". Chỉ leo thang **một lần**; leo thang **không đổi trạng thái** (cờ). | ST16 | — |
| BR-DG-09 | Tự đóng (ST17) | Khiếu nại `RESOLVED` quá 7 ngày **[CFG]** `complaint.auto_close_days` mà khách không phản hồi thì ST17 chuyển `CLOSED` (người đóng: hệ thống). Mở lại sau tự đóng vẫn trong hạn BR-DG-07. | ST17 | — |

### Máy trạng thái #26 — Khiếu nại

`complaints.status` (+ cờ `escalated_at`) · DG · Tầng 3.

| Trạng thái | Loại | Ý nghĩa |
|---|---|---|
| `OPEN` | INIT | Đã gửi, chờ nhận xử lý |
| `IN_PROGRESS` | MID | Đã có người xử lý |
| `RESOLVED` | MID | Người xử lý đã kết luận, chờ khách phản hồi |
| `CLOSED` | FINAL | Đã đóng (khách đóng, khách rút, hoặc tự đóng) |

| # | Từ → Sang | Sự kiện | Người kích hoạt | Điều kiện | Hệ quả | Nguồn |
|---|---|---|---|---|---|---|
| 1 | — → `OPEN` | Gửi khiếu nại | A02; A06 (thay khách) | BR-DG-05 | Thông báo BRANCH_MANAGER (hoặc SUPER_MANAGER nếu không gắn chi nhánh) | UC85 |
| 2 | `OPEN` → `IN_PROGRESS` | Nhận xử lý | A05, A04 | Trong phạm vi BR-DG-06 | Gán `handler_id`; thông báo khách | BR-DG-07 · UC86 |
| 3 | `IN_PROGRESS` → `RESOLVED` | Kết luận | A05, A04 (người xử lý) | Có kết luận | `resolved_at`; thông báo khách | BR-DG-07 · UC86 |
| 4 | `OPEN` / `IN_PROGRESS` → `CLOSED` | Rút khiếu nại | A02 | — | `closed_reason = WITHDRAWN` | BR-DG-07 · UC85 |
| 5 | `RESOLVED` → `CLOSED` | Khách đóng | A02 | — | `closed_reason = CUSTOMER` | BR-DG-07 · UC85 |
| 6 | `RESOLVED` → `OPEN` | Khách mở lại | A02 | Trong 7 ngày; `reopen_count` < tối đa | `reopen_count` + 1; xóa người xử lý; thông báo | BR-DG-07 · UC85 |
| 7 | `CLOSED` → `OPEN` | Mở lại sau đóng | A02 | `closed_reason` ≠ `WITHDRAWN`; trong hạn như #6 | Như #6 | BR-DG-07 · UC85 |
| 8 | `RESOLVED` → `CLOSED` | Tự đóng | ST17 | Quá hạn BR-DG-09 | `closed_reason = AUTO` | BR-DG-09 |
| 9 | `OPEN` / `IN_PROGRESS` → cùng trạng thái | Leo thang | ST16 | Quá hạn BR-DG-08; chưa leo thang | `escalated_at`; thông báo SUPER_MANAGER | BR-DG-08 |

### Dữ liệu (05 bổ sung)

- `complaints` — ROOT · SM #26: `id`, `code VARCHAR(20) UQ` (`KN-yyMMdd-xxxx`), `customer_id FK NN`, `branch_id FK?`, `topic VARCHAR(20) NN CHECK`, `related_type VARCHAR(20)? CHECK ('ORDER','ONLINE_ORDER','VISIT','BOARDING')`, `related_id BIGINT?` (cả hai cùng NULL hoặc cùng có; kiểm thuộc khách trong service), `content VARCHAR(2000) NN`, `status`, `created_by_staff_id FK?`, `handler_id FK?`, `resolution TEXT?`, `resolved_at?`, `closed_at?`, `closed_reason VARCHAR(20)? CHECK ('CUSTOMER','WITHDRAWN','AUTO')`, `reopen_count SMALLINT NN DEFAULT 0`, `last_activity_at TIMESTAMPTZ NN`, `escalated_at?`. `CHECK` trạng thái ↔ cột (khuôn `care_tasks`). Index: `(status, last_activity_at)` cho ST16 / ST17; `(branch_id, status)`; `(customer_id, created_at DESC)`; `(handler_id) WHERE handler_id IS NOT NULL`.
- `complaint_messages` — LOG: `id`, `complaint_id FK NN`, `author_type VARCHAR(10) CHECK ('CUSTOMER','STAFF','SYSTEM')`, `author_id FK→accounts?`, `kind VARCHAR(10) CHECK ('MESSAGE','STATUS')`, `body VARCHAR(2000) NN`; chỉ `created_at`. Index `(complaint_id, created_at)`. Mỗi chuyển trạng thái ghi một dòng `STATUS` (vết xử lý).
- `CustomerMergedEvent` (`08` §F.2): `complaints.customer_id` đổi theo hồ sơ đích.

### API (`complaint-v1`, 12 endpoint)

Khách (A02): `POST /me/complaints`, `GET /me/complaints`, `GET /me/complaints/{complaintId}`, `POST …/messages`, `POST …/close` (đóng hoặc rút), `POST …/reopen`. Nhân viên: `POST /complaints` (A06 tạo thay khách), `GET /complaints` (A05, A04; A06 chỉ cái mình tạo; lọc `status`, `escalated`), `GET /complaints/{complaintId}`, `POST …/take` (A05, A04), `POST …/messages` (A05, A04, A06 của cái mình tạo), `POST …/resolve` (A05, A04).

### Hợp đồng chéo

`CustomerQueryApi` (chọn khách khi lễ tân tạo thay, sẵn có); kiểm đối tượng liên quan thuộc khách: `OrderQueryApi`, `VisitQueryApi`, `BoardingQueryApi` (BE-2, BE-1); `NotificationApi` (mã mẫu `COMPLAINT_*`, `06` §8 Q4). ST16, ST17 theo khuôn ADR-0007.

**Câu hỏi:** Q2 package `complaint` thuộc BE-1 (đề xuất) hay `content` của BE-2; Q5 có cần đính kèm ảnh vào khiếu nại không.

---

## E. Tự trừ vật tư tiêu hao (ST07 · T71)

| Mã | Tác vụ | Kích hoạt |
|---|---|---|
| ST07 | Trừ vật tư tiêu hao theo định mức khi hoàn tất khám (UC48) hoặc dịch vụ thẩm mỹ (UC52) | SYS ← Visit#5 (hoàn tất lượt) |

| Mã | Tên | Mô tả | UC | Xử lý khi vi phạm |
|---|---|---|---|---|
| BR-KB-12 | Trừ vật tư theo định mức | Khi lượt hoàn tất (BR-KB-02) hoặc thẩm mỹ hoàn tất (UC52), hệ thống đọc **định mức** của từng dịch vụ trên Visit (BR-SP-10, fees-v1) và **xuất kho vật tư** tại chi nhánh theo FEFO (qua `StockApi`, BE-2) **trong cùng transaction** với hoàn tất; cộng dồn mọi dịch vụ của Visit (kể cả phẫu thuật, nội trú). Vật tư tiêu hao **không** thành dòng Order (đã nằm trong giá dịch vụ). | ST07 | — |
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
| A. Khám tại nhà | UC43, UC51 | #22 | cột mới ở `appointments`, `visits`, `order_lines` | `homevisit` (6) |
| B. Phẫu thuật | UC50 | #23 | 1 | `surgery` (8) |
| C. Nội trú | UC55, UC56 | #24, #25 | 3 | `inpatient` (10) |
| D. Khiếu nại | UC85, UC86 · ST16, ST17 | #26 | 2 | `complaint` (12) |
| E. Vật tư tiêu hao | ST07 | — | 1 | `materialuse` (3) |

**Đủ phạm vi T51:** 12 UC (UC34–38 ở NS, UC43, UC50, UC51, UC55, UC56, UC85, UC86) và 3 ST (ST07, ST16, ST17) đều có quy tắc, máy trạng thái (nơi có chuyển trạng thái), dữ liệu và API.

### Cấu hình [CFG] mới (thêm vào `ConfigKey` ở T55)

`complaint.max_open` (10), `complaint.reopen_days` (7), `complaint.max_reopen` (2), `complaint.sla_open_hours` (24), `complaint.sla_resolve_hours` (72), `complaint.auto_close_days` (7).

### Thay đổi cần BE-2 (catalog, boarding, sales) xác nhận

`services.medical_type` thêm `SURGERY`, `INPATIENT`; `order_lines.line_type` thêm `HOME_FEE`; chuồng dùng chung cho nội trú; guard trả thú khi còn ca nội trú; `StockApi.issueMaterials`.

### Quyết định T54 (Hiến duyệt 5/11/2026) cho các câu hỏi của BE-1

| Câu hỏi | Quyết định |
|---|---|
| Q1 `medical_type` thêm `SURGERY`, `INPATIENT` | Chốt theo đề xuất; BE-2 xác nhận khi làm T55 phần BE-2 |
| Q2 package `inpatient`, `complaint` | **Thuộc BE-1** (xem đầu file) |
| Q3 bán kính khám tại nhà | Không giới hạn (A2) |
| Q4 thu tiền tại nhà; khách xem nội trú | Thu tại chi nhánh (A4); khách **chưa** xem nội trú ở GĐ2 |
| Q5 ảnh đính kèm khiếu nại | Chưa có ở GĐ2 |
| Q6 báo thiếu vật tư | Thông báo riêng `MATERIAL_SHORTAGE`, không đưa vào ST08 |
| Còn lại (A1…A4) | **Chốt theo đề xuất**; BE-1 muốn đổi thì qua ADR |

### Liên kết với `02`–`06` (đã làm ở T54)

| File | Tình trạng |
|---|---|
| `02` | Mục 19 trỏ tới BR-LH-13, 14, BR-KB-07…14, BR-BH-16, BR-LT-13…17, BR-DG-05…09 ở file này |
| `03` | Mục trỏ tới FSM #22…#26 |
| `04`, `05` | Mục trỏ tới model, bảng và cột mới ở file này |
| `06` | Interface chéo gộp vào bảng tầng 3 kèm hạn giao; `ConfigKey` mới làm ở T55 |
| `api/INDEX.md` | Đã thêm 5 module `homevisit`, `surgery`, `inpatient`, `complaint`, `materialuse` |

