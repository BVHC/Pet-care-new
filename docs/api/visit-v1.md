# Visit & Clinical API v1 — Pet Care v16

> **Module:** TN, KB · **Owner:** BE-1. Tiếp nhận, hàng đợi, gán / gọi / gán lại lượt, hoàn tất / hủy lượt; bệnh án, bản bổ sung, kê đơn, tiêm chủng; hồ sơ sức khỏe thú cưng.
> **Nguồn chân lý:** 01 UC25, UC44–UC49, UC52, UC53; 02 BR-TN-01…09, BR-KB-01…06, BR-KH-04, 05, 07, BR-CN-05, BR-BH-02; 03 #4 Visit (+ Lịch hẹn#3, #7, #8, Order#1, #4, #6, Care Task#5, #6); 04 §6; 05 §6 (visits, visit_assignments, medical_records, medical_record_addenda, prescription_items, vaccinations).
> **Contract máy đọc:** [`./openapi/visit-v1.yaml`](./openapi/visit-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- UC50 phẫu thuật, UC51 khám tại nhà, UC55–UC56 nội trú là tầng 3.
- Thêm / đổi / xóa dòng dịch vụ, hàng của Order nguồn VISIT (UC67) thuộc module sales: `/orders/{orderId}/lines`.
- ST04 nhắc tái chủng / tái khám, ST06 trừ kho khi tiêm (chạy trong `POST …/vaccinations`) và ST07 (tầng 3) không có endpoint riêng.
- Lịch sử cân nặng đọc ở module customer: `GET /pets/{petId}/weights`.

---

## A. Danh sách endpoint (21)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `POST /visits` | Tiếp nhận (check-in lịch hẹn / walk-in / cấp cứu) | UC44, UC47 | BR-TN-01…04, BR-CN-05, BR-LH-01, BR-KH-05 |
| 2 | `GET /visits` | Hàng đợi của chi nhánh | UC45 | BR-TN-04, 09 |
| 3 | `GET /me/queue` | Hàng đợi của tôi | UC46 | BR-TN-05 |
| 4 | `GET /visits/{visitId}` | Chi tiết lượt | UC45, UC46 | — |
| 5 | `POST /visits/{visitId}/assign` | Gán / gán lại nhân viên (lượt chưa gọi) | UC45 | BR-TN-05, 06, 08 |
| 6 | `POST /visits/{visitId}/move` | Sắp lại thứ tự hàng đợi | UC45 | BR-TN-04 |
| 7 | `POST /visits/{visitId}/mark-emergency` | Xác định ca cấp cứu | UC47 | BR-TN-04 |
| 8 | `POST /visits/{visitId}/call` | Gọi lượt | UC46 | BR-TN-05, 09 |
| 9 | `POST /visits/{visitId}/reassign` | Gán lại lượt đã gọi | UC45 | BR-TN-05, 08, BR-QT-15 |
| 10 | `POST /visits/{visitId}/complete` | Hoàn tất lượt | UC48, UC49, UC52 | BR-KB-01, 02, BR-SP-06, BR-LH-11 |
| 11 | `POST /visits/{visitId}/cancel` | Hủy lượt khách bỏ về | UC45 | BR-TN-07 |
| 12 | `GET /visits/{visitId}/medical-record` | Xem bệnh án của lượt | UC48, UC53 | BR-KB-01 |
| 13 | `PUT /visits/{visitId}/medical-record` | Ghi bệnh án / hẹn tái khám | UC48 | BR-KB-01, 06 |
| 14 | `POST /visits/{visitId}/weight` | Ghi cân nặng khi khám | UC48 | BR-KH-04 |
| 15 | `POST /visits/{visitId}/medical-record/addenda` | Thêm bản bổ sung bệnh án | UC53 | BR-KB-01, BR-QT-15 |
| 16 | `POST /visits/{visitId}/prescriptions` | Kê đơn một dòng thuốc | UC48, UC67 | BR-KB-03, BR-SP-01, BR-BH-02, BR-QT-15 |
| 17 | `DELETE /visits/{visitId}/prescriptions/{itemId}` | Xóa dòng đơn thuốc | UC48 | BR-KB-03, BR-BH-02, BR-QT-15 |
| 18 | `GET /visits/{visitId}/vaccination-options` | Gợi ý mũi và sản phẩm vaccine | UC49 | BR-KB-04, BR-SP-02, 07, BR-KO-01 |
| 19 | `POST /visits/{visitId}/vaccinations` | Ghi nhận mũi tiêm | UC49, ST06 | BR-KB-04, BR-KO-05, BR-TB-03 |
| 20 | `DELETE /visits/{visitId}/vaccinations/{vaccinationId}` | Xóa mũi tiêm ghi nhầm | UC49 | BR-KB-04, BR-TB-03 |
| 21 | `GET /pets/{petId}/health-record` | Hồ sơ sức khỏe thú cưng | UC25, UC53 | BR-KH-07, BR-KB-01, 04, 06 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Tiếp nhận (check-in lịch hẹn / walk-in / cấp cứu) | A06 | Lễ tân; chi nhánh của lễ tân | — → WAITING (Visit#1) · Lịch hẹn#3 · Order#1 (OPEN + dòng dịch vụ tự sinh) · có dịch vụ Khám → Care Task#6 | — | Trễ quá `visit.late_priority_minutes` (15 phút) [CFG] xếp như walk-in. A9, A10, A11, A12. Thứ tự lỗi: 404 → 400 → 409 (docs/adr/0059). Có dịch vụ Khám: hủy nhắc tái khám đang chờ của thú và Care Task tái khám tương ứng (BR-TB-06, docs/adr/0084). Khách mang nhiều thú thì tạo nhiều Visit. |
| Hàng đợi của chi nhánh | A05, A06, A07, A08 | Nhân viên chi nhánh | — | — | A1, A13. |
| Hàng đợi của tôi | A07, A08 | VET, CARETAKER | — | — | Visit WAITING / IN_PROGRESS gán cho tôi. |
| Chi tiết lượt | A04–A08 | Nhân viên chi nhánh; SUPER_MANAGER toàn chuỗi | — | — | A13, A14. |
| Gán / gán lại nhân viên (lượt chưa gọi) | A06 | Lễ tân chi nhánh | WAITING → WAITING (Visit#2) | — | Dòng dịch vụ tự sinh và các dòng người được gán cũ đã thêm chuyển quyền cho người được gán (docs/adr/0061). Gán đúng người đang được gán: 200, không ghi gì. |
| Sắp lại thứ tự hàng đợi | A06 | Lễ tân chi nhánh | — | — | A3. Trả hàng đợi sau khi sắp. |
| Xác định ca cấp cứu | A06, A07 | Nhân viên chi nhánh | — | — | Lượt lên đầu hàng đợi (A2). |
| Gọi lượt | A07, A08 | Người được gán lượt | WAITING → IN_PROGRESS (Visit#3) | — | Người gọi được kiểm lại dưới khóa tài khoản của chính mình (docs/adr/0062). |
| Gán lại lượt đã gọi | A05 (A04 khi chi nhánh không còn BRANCH_MANAGER ACTIVE) | BRANCH_MANAGER chi nhánh; SUPER_MANAGER theo điều kiện | IN_PROGRESS → IN_PROGRESS (Visit#4) | — | A15. Thứ tự lỗi: 404 → 403 → 409 → 400 (docs/adr/0063). Gỡ cờ needsReassign. Bệnh án giữ nguyên kèm tên người ghi; quyền xóa dòng Order người cũ đã thêm chuyển cho người mới; ghi audit. |
| Hoàn tất lượt | A07, A08 | Người được gán lượt | IN_PROGRESS → COMPLETED (Visit#5) · Lịch hẹn#7 · Order#4 (→ PENDING) · bệnh án khóa | — | A16. Thứ tự lỗi: 403 vai trò → 404 → 403 chi nhánh → 403 người phụ trách → 400 BR-TN-05 → 409 → 400 BR-KB-02 (docs/adr/0069). Gỡ cờ needsReassign. Lỗi BR-KB-02 hoàn tác cả lịch hẹn, Order và bệnh án. |
| Hủy lượt khách bỏ về | A06 | Lễ tân chi nhánh | WAITING → CANCELLED (Visit#6) · Lịch hẹn#8 · Order#6 | — | Lịch hẹn → CANCELLED, không tính hủy muộn. Không gửi body: hủy không lý do (A4). |
| Xem bệnh án của lượt | A07, A08 | VET, CARETAKER chi nhánh (CARETAKER chỉ xem) | — | — | Thứ tự lỗi: 403 vai trò → 404 → 403 chi nhánh → 404 GROOMING (docs/adr/0067). Lượt MEDICAL chưa ghi bệnh án trả khung rỗng; locked = Visit COMPLETED. |
| Ghi bệnh án / hẹn tái khám | A07 | VET được gán lượt | — | — | Thứ tự lỗi: 400 body → 403 vai trò → 404 → 403 chi nhánh → 404 GROOMING → 403 người phụ trách → 409 → 400 BR-KB-06 (docs/adr/0067). PUT thay toàn bộ 5 trường; lần ghi đầu tạo bệnh án; ngày tái khám chỉ kiểm khi đổi; nội dung không đổi: 200, không ghi, không audit. |
| Ghi cân nặng khi khám | A07 | VET được gán lượt | — | — | source = VET (A8). Thứ tự lỗi như #13: 400 body → 403 vai trò → 404 → 403 chi nhánh → 404 GROOMING → 403 người phụ trách → 409 → 400 BR-KH-04 (docs/adr/0068). Mỗi lần gọi là một bản ghi; không audit. |
| Thêm bản bổ sung bệnh án | A07 | VET chi nhánh nơi khám (A7) | — | — | Thứ tự lỗi: 400 body → 403 vai trò → 404 → 403 chi nhánh → 404 GROOMING → 409 (docs/adr/0070). Mọi VET của chi nhánh nơi khám ghi được, không cần là người phụ trách. Lượt chưa có bệnh án: tạo bệnh án rỗng đã khóa (A17). Không sửa / xóa nội dung cũ; ghi audit. |
| Kê đơn một dòng thuốc | A07 | VET được gán lượt | IN_PROGRESS giữ nguyên · dòng Order DRUG (đủ tồn / giảm số lượng) · bệnh án rỗng nếu chưa có | — | A5, A26, A27, A28, A29. Thứ tự lỗi: 400 body → 403 vai trò → 404 → 403 chi nhánh → 404 GROOMING → 403 người phụ trách → 409 → 400 BR-KB-03 (thuốc) → 400 BR-KB-03 (tồn) (docs/adr/0077). Đủ tồn → 1 dòng Order DRUG giá snapshot; giảm số lượng → dòng DRUG bằng tồn; mua ngoài → không sinh dòng, giữ số lượng; không tách dòng. Kho trừ lúc thu tiền (BR-BH-04). Ghi audit. |
| Xóa dòng đơn thuốc | A07 | VET phụ trách hiện tại (A30) | IN_PROGRESS giữ nguyên · xóa dòng Order DRUG đi kèm (trừ dòng mua ngoài) · bệnh án giữ nguyên | — | A30. Thứ tự lỗi: 400 id → 403 vai trò → 404 lượt → 403 chi nhánh → 404 GROOMING → 403 người phụ trách → 409 → 404 dòng đơn (docs/adr/0078). Xóa dòng đơn rồi xóa dòng Order DRUG tương ứng trong cùng transaction; dòng mua ngoài chỉ xóa dòng đơn. Kho không đổi (thuốc chỉ trừ lúc thu tiền). Ghi audit. |
| Gợi ý mũi và sản phẩm vaccine | A07 | VET được gán lượt | — | — | A18, A19, A20. Thứ tự lỗi: 400 tham số → 403 vai trò → 404 → 403 chi nhánh → 404 GROOMING → 403 người phụ trách → 404 loại vaccine → 400 BR-KB-04 → 400 BR-SP-07 (docs/adr/0073). Chỉ đọc, không khóa. |
| Ghi nhận mũi tiêm | A07 | VET được gán lượt | IN_PROGRESS giữ nguyên · dòng Order VACCINE · trừ kho FEFO · Care Task#5 | — | A6, A21, A22, A23. Thứ tự lỗi: 400 body → 403 vai trò → 404 → 403 chi nhánh → 404 GROOMING → 403 người phụ trách → 409 → 404 loại vaccine → 400 BR-KB-04 → 400 BR-SP-07 → 400 BR-KB-04 (mũi, sản phẩm, tồn) (docs/adr/0074). Ngày tiêm = hôm nay (A6). Chưa đủ tuổi: cảnh báo trong warnings, không chặn. Không audit. |
| Xóa mũi tiêm ghi nhầm | A07 | VET phụ trách hiện tại (A24) | IN_PROGRESS giữ nguyên · hoàn kho đúng lô · xóa dòng Order VACCINE · mũi cũ mới nhất hết `superseded` (A25) | — | A24, A25, Q4. Thứ tự lỗi: 400 id → 403 vai trò → 404 lượt → 403 chi nhánh → 404 GROOMING → 403 người phụ trách → 409 → 404 mũi (docs/adr/0075). Hoàn kho đúng lô (kể cả lô đã hết hạn), xóa dòng Order vaccine trong cùng transaction. Không audit. Care Task đã hủy của mũi cũ không mở lại (Q4). |
| Hồ sơ sức khỏe thú cưng | A02, A06, A07, A08 | Khách là chủ hiện tại; nhân viên | — | — | Khách không nhận internalNote và bản bổ sung nội bộ. Chỉ mũi tiêm tại hệ thống. |

---

## C. Chi tiết endpoint

### Queue

- **`POST /visits`** — Tiếp nhận (check-in lịch hẹn / walk-in / cấp cứu). Request `CreateVisitRequest`. Response `201` `Visit`. Lỗi: `400` `BR-TN-01` walk-in thiếu dịch vụ / thú không khớp lịch hẹn / thú đã mất · `BR-TN-02` lịch hẹn của chi nhánh khác hoặc ngoài cửa sổ check-in · `BR-CN-01` chi nhánh chưa ACTIVE · `BR-CN-05` ngoài giờ mà chi nhánh không có cờ hoặc chưa đánh dấu cấp cứu · `BR-LH-01` walk-in: dịch vụ đã ngừng, đang tắt tại chi nhánh hoặc không thuộc MEDICAL / GROOMING; có lịch: dịch vụ đã ngừng kinh doanh · body sai (`VALIDATION_FAILED`, `MALFORMED_REQUEST`); BR-TK-17 · `401` · `403` · `404` · `409` Lịch hẹn không còn BOOKED (đã check-in / NO_SHOW: tiếp nhận như walk-in, BR-TN-03).
- **`GET /visits`** — Hàng đợi của chi nhánh. Query: `date?`, `status?`, `serviceGroup?`, `assigneeId?`, `needsReassign?`. Response `200` mảng `Visit`. Lỗi: `400` Tham số sai kiểu (`MALFORMED_REQUEST`); BR-TK-17 · `401` · `403`.
- **`GET /me/queue`** — Hàng đợi của tôi. Response `200` mảng `Visit`. Lỗi: `400` BR-TK-17 · `401` · `403`.
- **`GET /visits/{visitId}`** — Chi tiết lượt. Response `200` `Visit`. Lỗi: `400` Tham số sai kiểu (`MALFORMED_REQUEST`); BR-TK-17 · `401` · `403` Sai vai trò · nhân viên xem lượt của chi nhánh khác · `404`.
- **`POST /visits/{visitId}/assign`** — Gán / gán lại nhân viên (lượt chưa gọi). Request `AssignRequest`. Response `200` `AssignResult`. Lỗi: `400` `BR-TN-05` nhân viên không ACTIVE, khác chi nhánh hoặc sai chức vụ · body sai (`VALIDATION_FAILED`, `MALFORMED_REQUEST`); BR-TK-17 · `401` · `403` Sai vai trò · lượt của chi nhánh khác · `404` · `409` Lượt đã được gọi.
- **`POST /visits/{visitId}/move`** — Sắp lại thứ tự hàng đợi. Request `MoveVisitRequest`. Response `200` mảng `Visit`. Lỗi: `400` `BR-TN-04` lượt đích là chính lượt này, thuộc chi nhánh khác, hoặc sắp vượt ranh giới ca cấp cứu · body sai (`VALIDATION_FAILED`, `MALFORMED_REQUEST`); BR-TK-17 · `401` · `403` Sai vai trò · lượt của chi nhánh khác · `404` Không có lượt hoặc lượt đích · `409` Lượt hoặc lượt đích không còn WAITING.
- **`POST /visits/{visitId}/mark-emergency`** — Xác định ca cấp cứu. Response `200` `Visit`. Lỗi: `400` Tham số sai kiểu (`MALFORMED_REQUEST`); BR-TK-17 · `401` · `403` Sai vai trò · lượt của chi nhánh khác · `404` · `409` Lượt đã được gọi hoặc đã là cấp cứu.
- **`POST /visits/{visitId}/call`** — Gọi lượt. Response `200` `Visit`. Lỗi: `400` `BR-TN-05` người gọi không còn hoạt động tại chi nhánh hoặc sai chức vụ với nhóm dịch vụ (đổi chức vụ sau khi được gán) · tham số sai kiểu (`MALFORMED_REQUEST`); BR-TK-17 · `401` · `403` Sai vai trò · lượt của chi nhánh khác · lượt không gán cho người gọi, kể cả lượt chưa gán (BR-TN-05, 09) · `404` · `409` Lượt không còn WAITING.
- **`POST /visits/{visitId}/reassign`** — Gán lại lượt đã gọi. Request `ReassignRequest`. Response `200` `Visit`. Lỗi: `400` `BR-TN-08` người phụ trách hiện tại vẫn đủ điều kiện (A15) · `BR-TN-05` người mới không ACTIVE, khác chi nhánh hoặc sai chức vụ · thiếu lý do / body sai (`VALIDATION_FAILED`, `MALFORMED_REQUEST`); BR-TK-17 · `401` · `403` Sai vai trò · BRANCH_MANAGER của chi nhánh khác · SUPER_MANAGER khi chi nhánh còn BRANCH_MANAGER hoạt động · `404` · `409` Lượt không IN_PROGRESS · lượt vừa được gán lại bởi thao tác khác (`CONCURRENCY_CONFLICT`).
### Completion

- **`POST /visits/{visitId}/complete`** — Hoàn tất lượt. Response `200` `Visit`. Lỗi: `400` `BR-KB-02` có dịch vụ loại Khám mà chưa có chẩn đoán · chỉ có dịch vụ loại Tiêm mà chưa ghi mũi nào · lượt Khám/Tiêm không còn dịch vụ Khám hoặc Tiêm nào (A16) · `BR-TN-05` chức vụ hiện tại không phụ trách được nhóm dịch vụ của lượt (đổi chức vụ sau khi được gán) · tham số sai kiểu (`MALFORMED_REQUEST`); BR-TK-17 · `401` · `403` Sai vai trò · lượt của chi nhánh khác · không phải người phụ trách hiện tại, kể cả lượt chưa gán · `404` · `409` Lượt không IN_PROGRESS.
- **`POST /visits/{visitId}/cancel`** — Hủy lượt khách bỏ về. Request `CancelVisitRequest`. Response `200` `Visit`. Lỗi: `400` Lý do quá 300 ký tự (`VALIDATION_FAILED`) · body / tham số sai (`MALFORMED_REQUEST`); BR-TK-17 · `401` · `403` Sai vai trò · lượt của chi nhánh khác · `404` · `409` Lượt đã được gọi (BR-TN-07 — nhân viên hoàn tất theo BR-KB-02) hoặc đã kết thúc.
### Medical record

- **`GET /visits/{visitId}/medical-record`** — Xem bệnh án của lượt. Response `200` `MedicalRecord`. Lỗi: `400` Tham số sai kiểu (`MALFORMED_REQUEST`); BR-TK-17 · `401` · `403` Sai vai trò · lượt của chi nhánh khác · `404` Không có Visit hoặc Visit nhóm GROOMING (không có bệnh án).
- **`PUT /visits/{visitId}/medical-record`** — Ghi bệnh án / hẹn tái khám. Request `UpdateMedicalRecordRequest`. Response `200` `MedicalRecord`. Lỗi: `400` `BR-KB-06` ngày tái khám không sau ngày khám hoặc quá 180 ngày [CFG] · body sai (`MALFORMED_REQUEST`); BR-TK-17 · `401` · `403` Sai vai trò · lượt của chi nhánh khác · không phải VET phụ trách hiện tại · `404` · `409` Lượt không IN_PROGRESS — bệnh án đã khóa, dùng bản bổ sung.
- **`POST /visits/{visitId}/weight`** — Ghi cân nặng khi khám. Request `RecordWeightRequest`. Response `201` `WeightRecordRef`. Lỗi: `400` `BR-KH-04` cân nặng ≤ 0 hoặc vượt 9999.99 kg · thiếu `weightKg` · body sai (`MALFORMED_REQUEST`); BR-TK-17 · `401` · `403` Sai vai trò · lượt của chi nhánh khác · không phải VET phụ trách hiện tại · `404` Không có Visit hoặc Visit nhóm GROOMING (không có bệnh án) · `409` Lượt không IN_PROGRESS.
- **`POST /visits/{visitId}/medical-record/addenda`** — Thêm bản bổ sung bệnh án. Request `AddendumRequest`. Response `201` `Addendum`. Lỗi: `400` Thiếu `content` · body sai (`MALFORMED_REQUEST`); BR-TK-17 · `401` · `403` Sai vai trò · lượt của chi nhánh khác · `404` Không có Visit hoặc Visit nhóm GROOMING (không có bệnh án) · `409` Visit chưa COMPLETED — sửa trực tiếp bệnh án.
### Prescriptions

- **`POST /visits/{visitId}/prescriptions`** — Kê đơn một dòng thuốc. Request `AddPrescriptionRequest`. Response `201` `PrescriptionItem`. Lỗi: `400` `BR-KB-03` thuốc không tồn tại, không phải thuốc kê đơn hoặc đã ngừng kinh doanh · thiếu tồn khả dụng (message ghi số tồn hiện có) — gửi lại kèm shortageResolution · `REDUCE_TO_AVAILABLE` khi tồn bằng 0 (A27) · body sai (`VALIDATION_FAILED`, `MALFORMED_REQUEST`); BR-TK-17 · `401` · `403` Sai vai trò · lượt của chi nhánh khác · không phải VET phụ trách hiện tại · `404` Không có Visit hoặc Visit nhóm GROOMING · `409` Lượt không IN_PROGRESS.
- **`DELETE /visits/{visitId}/prescriptions/{itemId}`** — Xóa dòng đơn thuốc. Response `204` rỗng. Lỗi: `400` `MALFORMED_REQUEST` id sai kiểu; BR-TK-17 · `401` · `403` Sai vai trò · lượt của chi nhánh khác · không phải VET phụ trách hiện tại (A30) · `404` Không có Visit, Visit nhóm GROOMING hoặc dòng đơn thuốc không thuộc lượt này · `409` Lượt không IN_PROGRESS — sau COMPLETED, sai sót ghi bằng bản bổ sung.
### Vaccinations

- **`GET /visits/{visitId}/vaccination-options`** — Gợi ý mũi và sản phẩm vaccine. Query: `vaccineTypeId`. Response `200` `VaccinationOptions`. Lỗi: `400` `BR-KB-04` loại vaccine không áp dụng cho loài của thú · `BR-SP-07` loại vaccine đã ngừng sử dụng · thiếu / sai kiểu `vaccineTypeId` (`MALFORMED_REQUEST`); BR-TK-17 · `401` · `403` Sai vai trò · lượt của chi nhánh khác · không phải VET phụ trách hiện tại · `404` Không có Visit, Visit nhóm GROOMING hoặc không có loại vaccine.
- **`POST /visits/{visitId}/vaccinations`** — Ghi nhận mũi tiêm. Request `RecordVaccinationRequest`. Response `201` `RecordVaccinationResult`. Lỗi: `400` `BR-KB-04` loại vaccine không áp dụng cho loài của thú · mũi không thuộc phác đồ đang dùng của loại và loài · sản phẩm không phải vaccine đang kinh doanh của loại đã chọn · sản phẩm không còn tồn khả dụng · `BR-SP-07` loại vaccine đã ngừng sử dụng · `BR-KO-05` hết lô chưa hết hạn khi đang ghi (A22) · body sai (`VALIDATION_FAILED`, `MALFORMED_REQUEST`); BR-TK-17 · `401` · `403` Sai vai trò · lượt của chi nhánh khác · không phải VET phụ trách hiện tại · `404` Không có Visit, Visit nhóm GROOMING hoặc không có loại vaccine · `409` Lượt không IN_PROGRESS.
- **`DELETE /visits/{visitId}/vaccinations/{vaccinationId}`** — Xóa mũi tiêm ghi nhầm. Response `204` rỗng. Lỗi: `400` `MALFORMED_REQUEST` id sai kiểu; BR-TK-17 · `401` · `403` Sai vai trò · lượt của chi nhánh khác · không phải VET phụ trách hiện tại (A24) · `404` Không có Visit, Visit nhóm GROOMING hoặc mũi tiêm không thuộc lượt này · `409` Lượt không IN_PROGRESS — sau COMPLETED, sai sót ghi bằng bản bổ sung.
### Health record

- **`GET /pets/{petId}/health-record`** — Hồ sơ sức khỏe thú cưng. Response `200` `HealthRecord`. Lỗi: `400` `BR-TK-19` hồ sơ còn chờ quyết định liên kết · `401` · `403` Không phải chủ hiện tại · `404`.

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`VisitStatus`** — enum: `WAITING`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED`. 03 #4
- **`PriorityClass`** — enum: `EMERGENCY`, `APPOINTMENT`, `WALK_IN`. BR-TN-03, 04. Hạng trong hàng đợi; lễ tân sắp lại / xác định cấp cứu có thể đổi (A2, A3) — có lịch hẹn hay không xem `appointmentId`
- **`CreateVisitRequest`** — {`petId`: int64, `appointmentId?`: int64, `serviceId?`: int64, `isEmergency?`: boolean}
- **`Visit`** — {`visitId`: int64, `code`: string, `branchId`: int64, `customerId`: int64, `customerName`: string, `petId`: int64, `petName`: string, `species`: DOG|CAT|OTHER, `appointmentId?`: int64, `serviceGroup`: MEDICAL|GROOMING, `status`: VisitStatus, `priorityClass`: PriorityClass, `queueSortKey`: date-time, `assigneeId?`: int64, `assigneeName?`: string, `needsReassign`: boolean, `orderId`: int64, `checkedInAt`: date-time, `calledAt?`: date-time, `completedAt?`: date-time, `cancelledAt?`: date-time, `cancelReason?`: string}
- **`AssignRequest`** — {`assigneeId`: int64}
- **`AssignResult`** — {`visit`: Visit, `assigneeOffline`: boolean}
- **`MoveVisitRequest`** — {`placeBeforeVisitId`: int64}
- **`ReassignRequest`** — {`assigneeId`: int64, `reason`: string}
- **`CancelVisitRequest`** — {`reason?`: string}
- **`PrescriptionItem`** — {`itemId`: int64, `productId`: int64, `productName`: string, `quantity`: int32, `dosageInstructions`: string, `isExternalPurchase`: boolean, `externalReason?`: OUT_OF_STOCK_AT_PRESCRIBE|OUT_OF_STOCK_AT_PAYMENT, `orderLineId?`: int64}
- **`Addendum`** — {`addendumId`: int64, `content`: string, `isInternal`: boolean, `createdBy`: int64, `createdByName`: string, `createdAt`: date-time}
- **`VaccinationRecord`** — {`vaccinationId`: int64, `visitId`: int64, `branchId`: int64, `vaccineTypeId`: int64, `vaccineTypeName`: string, `protocolId`: int64, `doseNumber`: int32, `productId`: int64, `productName`: string, `lotNumber?`: string, `expiryDate?`: date, `administeredOn`: date, `nextDueDate`: date, `superseded`: boolean, `recordedBy`: int64, `orderLineId`: int64}
- **`MedicalRecord`** — {`visitId`: int64, `examination?`: string, `diagnosis?`: string, `treatmentPlan?`: string, `internalNote?`: string, `followUpDate?`: date, `locked`: boolean, `lastEditedBy?`: int64, `prescriptions`: [PrescriptionItem], `vaccinations`: [VaccinationRecord], `addenda`: [Addendum]}
- **`UpdateMedicalRecordRequest`** — {`examination?`: string, `diagnosis?`: string, `treatmentPlan?`: string, `internalNote?`: string, `followUpDate?`: date}
- **`RecordWeightRequest`** — {`weightKg`: number}
- **`WeightRecordRef`** — {`weightRecordId`: int64, `weightKg`: number, `measuredAt`: date-time}
- **`AddendumRequest`** — {`content`: string, `isInternal?`: boolean}
- **`AddPrescriptionRequest`** — {`productId`: int64, `quantity`: int32, `dosageInstructions`: string, `shortageResolution?`: REDUCE_TO_AVAILABLE|EXTERNAL_PURCHASE}
- **`VaccineProductOption`** — {`productId`: int64, `name`: string, `availableQuantity`: int32}
- **`VaccinationOptions`** — {`vaccineTypeId`: int64, `protocol`: [ProtocolDoseOption], `suggestedProtocolId?`: int64, `lastDose?`: VaccinationRecord, `products`: [VaccineProductOption], `underMinAge`: boolean}
- **`ProtocolDoseOption`** — {`protocolId`: int64, `doseNumber`: int32, `intervalDays`: int32, `minAgeWeeks`: int32, `requiredForBoarding`: boolean}
- **`RecordVaccinationRequest`** — {`vaccineTypeId`: int64, `protocolId`: int64, `productId`: int64}
- **`RecordVaccinationResult`** — {`vaccination`: VaccinationRecord, `warnings`: [string]}
- **`HealthVisitEntry`** — {`visitId`: int64, `visitDate`: date, `branchName`: string, `serviceGroup`: MEDICAL|GROOMING, `examination?`: string, `diagnosis?`: string, `treatmentPlan?`: string, `internalNote?`: string, `followUpDate?`: date, `prescriptions`: [PrescriptionItem], `addenda`: [Addendum]}
- **`UpcomingVaccination`** — {`vaccineTypeId`: int64, `vaccineTypeName`: string, `nextDueDate`: date}
- **`HealthRecord`** — {`petId`: int64, `visits`: [HealthVisitEntry], `vaccinations`: [VaccinationRecord], `upcomingVaccinations`: [UpcomingVaccination], `upcomingFollowUpDate?`: date}

---

## D. Bảo mật & độ tin cậy

1. Nhân viên chỉ thấy và thao tác Visit của chi nhánh mình. Chỉ người được gán gọi lượt, ghi bệnh án, kê đơn, tiêm, hoàn tất (BR-TN-05, BR-KB-01).
2. Tiếp nhận tạo Visit, chuyển lịch hẹn và mở Order trong một transaction (03 Phụ lục, 06 §4.1). Hoàn tất / hủy lượt tương tự.
3. Ghi mũi tiêm: sinh dòng Order, khóa lô FEFO, ghi mũi, trừ kho, đánh dấu mũi cũ và hủy Care Task trong một transaction theo thứ tự ở 06-module-contracts §3 (docs/adr/0074). Xóa mũi ghi nhầm đi ngược lại.
4. `internalNote` và bản bổ sung `isInternal` không bao giờ trả cho khách (BR-KB-01, BR-KH-07).
5. Gán lại lượt đã gọi, bản bổ sung bệnh án ghi audit (BR-TN-08, BR-KB-01, BR-QT-15).
6. Thú đã mất không được tiếp nhận (BR-TN-01, BR-KH-05).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Danh sách hàng đợi trả theo thứ tự ưu tiên của BR-TN-04 (`priorityClass` EMERGENCY → APPOINTMENT → WALK_IN, rồi `queueSortKey`, rồi `visitId`). Không gửi `date`: lượt WAITING / IN_PROGRESS của mọi ngày (lượt dở hôm trước không bị giấu); `status` COMPLETED / CANCELLED thì chỉ lượt check-in hôm nay. Không gửi `status`: chỉ WAITING + IN_PROGRESS. Gửi `date`: lượt check-in trong ngày đó (giờ Việt Nam), không gửi `status` thì mọi trạng thái (docs/adr/0060) | BR-TN-04; BR-QT-08 (lượt dở phải tìm được) |
| A2 | UC47 'Xác định ca cấp cứu' sau khi đã tiếp nhận: `POST …/mark-emergency` khi Visit còn WAITING; hạng → EMERGENCY, `queueSortKey` = thời điểm đánh dấu (như A10). VET nào của chi nhánh cũng đánh dấu được (docs/adr/0061) | UC47 có actor A07, A06; tiếp nhận chỉ do A06 |
| A3 | Sắp lại thứ tự hàng đợi bằng cách đặt Visit trước một Visit khác; server tính lại `queue_sort_key`. Lượt được sắp nhận hạng (`priorityClass`) của lượt đích, khóa = khóa đích − 1 µs, các lượt cùng hạng phía sau bị trùng thì dồn +1 µs. Ca cấp cứu chỉ sắp giữa các ca cấp cứu (vào nhóm cấp cứu dùng mark-emergency). Đã đứng ngay trước lượt đích: 200, không đổi gì (docs/adr/0061) | 05 `visits.queue_sort_key`: lễ tân sắp lại thì sửa cột này; BR-TN-04 |
| A4 | Lý do hủy lượt không bắt buộc | BR-TN-07 không yêu cầu; `visits.cancel_reason` cho phép null |
| A5 | Thiếu tồn khi kê đơn: request đầu trả 400 `BR-KB-03` kèm số tồn trong message; FE gửi lại với `shortageResolution` | BR-KB-03: báo số tồn rồi VET chọn giảm số lượng hoặc mua ngoài |
| A6 | Ngày tiêm = ngày hiện tại theo giờ Việt Nam, không cho chọn | BR-KB-04 trừ kho ngay khi ghi nhận; 05 `administered_on` không ở tương lai |
| A7 | Bản bổ sung bệnh án do VET của chi nhánh nơi khám ghi, sau khi Visit COMPLETED | BR-KB-01: chỉ VET; sau khi khóa mới có bản bổ sung |
| A8 | Cân nặng khi khám ghi bằng endpoint riêng, mỗi lần là một bản ghi | 05 `weight_records` là LOG |
| A9 | Ngoài giờ mở cửa (trước giờ mở, giờ nghỉ giữa ca, ngày nghỉ) chỉ tiếp nhận ca đánh dấu cấp cứu ở chi nhánh có cờ, kể cả check-in lịch hẹn đến sớm trong cửa sổ BR-TN-02 | 03 #1 Visit liệt kê cả điều kiện giờ mở cửa / cấp cứu lẫn cửa sổ check-in (docs/adr/0059 Q1) |
| A10 | `isEmergency` = true → hạng `EMERGENCY`, `queueSortKey` = giờ check-in, cả trong giờ và khi có lịch hẹn | Endpoint gộp UC47 cho A06 (docs/adr/0059 Q2) |
| A11 | Có `appointmentId` thì `serviceId` bị bỏ qua (dịch vụ của lịch); `petId` phải là thú của lịch, khác → 400 `BR-TN-01` | BR-TN-01: 1 Visit cho đúng 1 thú; BR-LH-01: lịch có 1 thú, 1 dịch vụ |
| A12 | Lịch hẹn mà dịch vụ bị tắt tại chi nhánh sau khi đặt vẫn tiếp nhận được; chỉ dịch vụ đã ngừng kinh doanh mới bị từ chối (400 `BR-LH-01`) | BR-LH-01 là điều kiện đặt lịch (UC39); 03 #1 không liệt kê (docs/adr/0059 Q3) |
| A13 | CARETAKER xem hàng đợi chung của chi nhánh (#2) và chi tiết mọi lượt của chi nhánh (#4); 01 không có use case cho A08 xem lượt của người khác (docs/adr/0060) | BR-TN-04 'một hàng đợi chung'; UC23 cho A08 tra khách / thú toàn chuỗi; schema `Visit` không có dữ liệu y tế |
| A14 | SUPER_MANAGER xem chi tiết lượt (#4) của mọi chi nhánh, để xem lượt cần gán lại khi chi nhánh không còn BRANCH_MANAGER hoạt động; `visitId` lấy từ thông báo khóa tài khoản gửi SUPER_MANAGER. Hàng đợi chi nhánh (#2) vẫn chỉ cho A05–A08 (docs/adr/0063) | 03 Visit#4; BR-QT-11 (thông báo kèm việc dở); trả Q2 |
| A15 | Gán lại lượt đã gọi (#9) khi người phụ trách hiện tại không còn đủ điều kiện phụ trách lượt: không ACTIVE hoặc bị khóa, khác chi nhánh, hoặc sai chức vụ với nhóm dịch vụ (đổi chức vụ khi còn lượt, docs/adr/0031 Q4). Không xét cờ `needsReassign`; gán lại thì gỡ cờ (docs/adr/0063) | BR-TN-08 'không còn ACTIVE (bị khóa)'; BR-TN-05 |
| A16 | Lượt Khám/Tiêm (MEDICAL) hoàn tất khi Order không còn dòng dịch vụ loại Khám hoặc Tiêm nào (VET xóa dòng tự sinh) thì bị từ chối 400 `BR-KB-02`; 03 Visit#5 chỉ nêu điều kiện cho 'có Khám' và 'chỉ có Tiêm' (docs/adr/0069) | BR-KB-02; tránh Order PENDING không có dịch vụ |
| A17 | Bản bổ sung (#15) cho lượt MEDICAL đã COMPLETED chưa có dòng bệnh án (lượt chỉ tiêm): tạo bệnh án rỗng đã khóa rồi thêm bản bổ sung; thú đã mất hoặc đã chuyển chủ vẫn thêm được (BR-KH-05 'chỉ đọc' hiểu là hồ sơ thú) (docs/adr/0070) | BR-KB-01, BR-KB-04: sai sót sau COMPLETED ghi bằng bản bổ sung; FK addenda cần dòng bệnh án |
| A18 | Mũi gợi ý (#18): chưa từng tiêm loại đó → mũi nhỏ nhất đang dùng; đã tiêm → mũi đang dùng nhỏ nhất lớn hơn số mũi đã chụp của mũi gần nhất; không còn mũi lớn hơn → mũi lớn nhất (nhắc lại định kỳ); phác đồ rỗng → null (docs/adr/0073) | BR-KB-04; BR-SP-02 mọi mũi có khoảng cách tới mũi kế tiếp > 0; erd `vaccinations.next_due_date` NOT NULL |
| A19 | #18 chỉ đọc: VET đang phụ trách lượt xem được ở mọi trạng thái; ghi mũi (#19) mới chặn lượt không IN_PROGRESS (docs/adr/0073) | Contract #18 không có 409; khuôn #12 |
| A20 | #18: `products` chỉ gồm sản phẩm đang kinh doanh có tồn khả dụng > 0 tại chi nhánh của lượt, theo tên (rỗng vẫn 200); `lastDose` là mũi gần nhất ở mọi chi nhánh, kể cả mũi của chính lượt; thú không có ngày sinh → `underMinAge` false (docs/adr/0073) | BR-KB-04, BR-KO-01; 04 nguyên tắc 8 (thú dùng chung toàn chuỗi) |
| A21 | Ghi mũi (#19): mũi phải thuộc phác đồ đang dùng của đúng loài và đúng loại đã chọn; sản phẩm phải là vaccine đang kinh doanh của loại đó và còn tồn khả dụng tại chi nhánh của lượt; cùng thú, cùng loại: mọi mũi cũ chưa bị thay thế được đánh dấu `superseded` và Care Task `VACCINE_*` còn OPEN của chúng bị hủy (docs/adr/0074) | BR-KB-04, BR-TB-03; 03 Care Task#5 |
| A22 | Hết tồn: kiểm trước nên trả `BR-KB-04`; nếu lô bị lấy hết giữa lúc kiểm và lúc ghi thì trả `BR-KO-05` (vẫn 400, không ghi gì) (docs/adr/0074) | BR-KB-04, BR-KO-05; exception qua callee MANDATORY đã đánh rollback-only |
| A23 | Gửi lại cùng request ghi thêm một mũi nữa (không chống trùng); ghi nhầm thì xóa bằng #20 khi lượt còn IN_PROGRESS (docs/adr/0074) | Docs không có rule chặn trùng mũi trong một lượt |
| A24 | Xóa mũi (#20): người gọi phải là VET phụ trách **hiện tại** của lượt, không cần là người đã ghi mũi. Chỉ người phụ trách ghi được mũi (#19) và gán lại lượt đã gọi chuyển quyền của người cũ sang người mới (BR-TN-08), nên 'VET đã ghi mũi đó' trùng với người phụ trách hiện tại; sau khi gán lại, người cũ không xóa được nữa (docs/adr/0075) | BR-KB-04 'VET xóa được mũi mình đã ghi nhận'; BR-TN-08 |
| A25 | Sau khi xóa mũi X chưa bị thay thế, mũi còn lại mới nhất cùng thú và cùng loại vaccine (ngày tiêm mới nhất, cùng ngày thì ghi sau) hết `superseded` để nhắc tái chủng tính lại theo nó; xóa mũi đã bị thay thế thì mũi khác không đổi. Care Task đã hủy khi ghi X không mở lại (docs/adr/0075) | BR-TB-03 'mũi đã tiêm lại khi có mũi mới hơn cùng loại'; 03 Care Task CANCELLED là trạng thái cuối |
| A26 | Kê đơn (#16) gửi `shortageResolution` nhưng tồn khả dụng lúc ghi đã đủ: bỏ qua lựa chọn, sinh dòng Order DRUG với số lượng yêu cầu (docs/adr/0077) | BR-KB-03 'Đủ tồn: sinh 1 dòng Order'; mua ngoài chỉ khi thiếu tồn |
| A27 | `REDUCE_TO_AVAILABLE` khi tồn khả dụng bằng 0: 400 `BR-KB-03`, không ghi gì — chỉ chọn được mua ngoài (docs/adr/0077) | 05 `prescription_items.quantity > 0` |
| A28 | Tồn khả dụng khi kê đơn = tổng các lô chưa hết hạn tại chi nhánh của lượt, không trừ thuốc đã kê chưa thu; thiếu thật sẽ bị chặn lúc thu (BR-BH-04) (docs/adr/0077) | BR-KO-01; tầng 1 không giữ hàng |
| A29 | Gửi lại cùng request kê thêm một dòng nữa (không chống trùng); lượt chưa có bệnh án thì tạo bệnh án rỗng trong cùng transaction; thuốc không tồn tại, không phải thuốc kê đơn hoặc đã ngừng kinh doanh là 400 `BR-KB-03`, kể cả khi chọn mua ngoài (docs/adr/0077) | Docs không có rule chặn trùng (khuôn A23); FK `prescription_items.medical_record_id`; BR-SP-01 |
| A30 | Xóa dòng đơn thuốc (#17): người gọi phải là VET phụ trách **hiện tại**, không cần là người đã kê. Dòng mua ngoài cũng xóa được (chỉ xóa dòng đơn, không có dòng Order). Bệnh án rỗng do #16 tạo được giữ nguyên khi xóa dòng cuối, nên thú vẫn bị khóa đổi loài. Xóa cứng, ghi audit `PRESCRIPTION_ITEM_REMOVED` (docs/adr/0078) | BR-BH-02 'chỉ người thêm được xóa dòng khi OPEN', BR-TN-08; contract #17 'xóa dòng đơn thuốc'; erd §0 / §13 mục 21; không có rule xóa bệnh án |

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Dòng dịch vụ tự sinh khi tiếp nhận có thể bị VET đổi (BR-TN-01). Nếu đổi từ dịch vụ thường sang dịch vụ loại Khám thì có hủy nhắc tái khám như Visit#1 có dịch vụ Khám không? | TBD (PO), tạm chỉ xét tại Visit#1 (06 G5) | 03 Care Task#6 |
| Q2 | Visit#4 cho SUPER_MANAGER gán lại lượt đã gọi khi chi nhánh không còn BRANCH_MANAGER ACTIVE, nhưng #2 / #4 chỉ cho A05–A08 xem lượt. SUPER_MANAGER tìm lượt cần gán lại bằng cách nào? | Đã chốt: A14 (docs/adr/0063) | 03 Visit#4; 00-method §3.3 |
| Q3 | Khóa tài khoản đánh dấu lượt IN_PROGRESS của người bị khóa là cần gán lại (needsReassign). Mở khóa có gỡ cờ này không, khi người phụ trách đã ACTIVE trở lại? | TBD (PO); tạm không gỡ (docs/adr/0062 Q5) | 03 Tài khoản#7, #8; BR-QT-12; erd visits.needs_reassign |
| Q4 | Xóa mũi ghi nhầm (#20) có mở lại Care Task tái chủng đã bị hủy khi ghi mũi đó (Care Task#5) không? | TBD (PO); tạm không mở lại — CANCELLED là trạng thái cuối (03 §10), mũi cũ hết `superseded` nên ST04 nhắc lại được (docs/adr/0075) | 03 Care Task#5; BR-TB-03 |

---

## F. OpenAPI 3.1

[`./openapi/visit-v1.yaml`](./openapi/visit-v1.yaml)
