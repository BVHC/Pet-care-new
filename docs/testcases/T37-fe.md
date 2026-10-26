# T37 (FE): Khám bệnh, tiêm chủng, hoàn tất lượt, bản bổ sung (UC48, UC49, UC52, UC53)

> Phạm vi: **A. Mock bệnh án, cân nặng, hoàn tất lượt, bản bổ sung, tiêm chủng** (types, `visit.api.ts`, hooks, `visit-clinical.mock.ts`) và **B. Giao diện** bàn Phòng khám (VET) + khu Grooming (CARETAKER). Kê đơn (#16–17) là T46, không làm ở đây.
> Hợp đồng: `docs/api/visit-v1.md` + `docs/api/openapi/visit-v1.yaml` (#10, #12–15, #18–20, đọc thêm #21); ADR 0066–0070. Rule: BR-KB-01, 02, 04, 06, BR-SP-06, 07, BR-KH-04, 05, 07, BR-TN-05, 09, BR-TB-03, BR-KO-05. **Khi docs lệch BE thì theo BE** (đã đọc `MedicalRecordService`, `VisitCompletionService`, DTO, mapper, `PetWeightApiService`).
> **BE có #10, #12–15 (T27) và #18–20 (tiêm chủng, T28 giao 26/10, ADR 0072–0075).** Phần tiêm chủng của T37 dựng khi BE chưa có, nên chỉ theo hợp đồng + BR-KB-04, chỗ hợp đồng im lặng là ASSUMPTION (A.3); **chưa chạy thử với BE thật** (đối chiếu ở A.3).
> File chính: `FE/src/shared/types/medical-record.ts`, `api/visit.api.ts`, `hooks/useExam.ts`, `api/mock/visit-clinical.mock.ts` (+ test cạnh từng file). Route mock đăng ký trong `mock-api.ts` (`...visitClinicalRoutes`).

## A. Mock

### A.1 Tài khoản và dữ liệu mẫu (mật khẩu `Petcare123`)

`doctor@store1.vn` (VET #5, BS. Minh Anh), `caretaker@store1.vn` (CARETAKER #6), `reception@store1.vn`, `manager@store1.vn`, `khach@petcare.vn` (chủ thú #1 Mochi, #2 Bim, #3 Tôm). Lượt mẫu: xem `docs/testcases/T36-fe.md` A.3.

Bệnh án mẫu (đã khóa vì lượt COMPLETED, người ghi VET #5): lượt **61** Mochi hôm nay (chẩn đoán "Viêm đường hô hấp trên", hẹn tái khám sau 7 ngày, ghi chú nội bộ, một bản bổ sung không nội bộ), **58**, **40**, **2001**. Lượt đang mở (2002 Lu, 2102 Tôm…) chưa có bệnh án: VET ghi lần đầu. **Không có mũi tiêm mẫu**: dòng vaccine của Order #3 (lượt 58) là vaccine của chó còn Miu là mèo, nên không dựng mũi khớp.

Tồn vaccine mẫu (inventory.mock, chi nhánh 1): "Dại Rabisin" #5 còn 15 liều lô `RB2401` (hạn +260 ngày) và 2 liều lô `RB2309` **đã hết hạn**; "5 bệnh Vanguard" #6 còn 4 liều lô `VG2402`. Loại vaccine / phác đồ: catalog.mock (chó: Dại #1 phác đồ 3; 5 bệnh #2 phác đồ 1, 2; Ho cũi #5 chưa có phác đồ; mèo: Dại #3, 4 bệnh #4).

### A.2 Từng endpoint: quyền, thứ tự kiểm, rule (test: `visit-clinical.mock.test.ts`, 44 ca)

Thứ tự chung (chép từ BE): **đọc JSON / tham số path + Bean Validation → vai trò 403 → lượt 404 → chi nhánh 403 → nhóm Thẩm mỹ 404 (không có bệnh án) → người phụ trách 403 (ĐỨNG TRƯỚC 409, để người không phải chủ lượt không dò được trạng thái) → trạng thái 409 → rule 400**.

| # | Route mock | Quyền | Rule đã mô phỏng | Thông điệp thành công |
|---|---|---|---|---|
| 12 | `GET /api/visits/:visitId/medical-record` | VET, CARETAKER (chỉ xem) | path → vai trò → 404 → chi nhánh 403 → Thẩm mỹ 404 `Không tìm thấy bệnh án #id`. Lượt chưa ghi bệnh án trả **khung rỗng**; `locked` = lượt COMPLETED; bản bổ sung chỉ có khi đã khóa, theo thứ tự ghi | `Bệnh án của lượt` |
| 13 | `PUT …/medical-record` | VET được gán | body (JSON sai kiểu / ngày không phải `YYYY-MM-DD` → 400 MALFORMED) → path → vai trò → … → người phụ trách 403 → lượt không IN_PROGRESS 409 (`từ <trạng thái> sang IN_PROGRESS`) → BR-KB-06 (chỉ khi ngày **đổi**, sau ngày khám theo ngày check-in giờ VN, tối đa 180 ngày) → nội dung không đổi: 200, **không ghi, không đổi `lastEditedBy`** → ghi. PUT thay toàn bộ 5 trường, trường vắng / null là xóa; bỏ khoảng trắng hai đầu, chuỗi rỗng thành null | `Đã lưu bệnh án` |
| 14 | `POST …/weight` | VET được gán | `weightKg` @NotNull (400 VALIDATION_FAILED `Bắt buộc nhập cân nặng`), số hoặc chuỗi số; … → 409 → BR-KH-04 (≤ 0 `Cân nặng phải lớn hơn 0`; làm tròn 2 chữ số HALF_UP rồi phải trong 0.01–9999.99) → BR-KH-05 thú đã mất (kiểm SAU cân nặng). Mỗi lần là một bản ghi VET gắn lượt, cân nặng hiện tại của thú là lần đo gần nhất (hòa giờ thì lần ghi sau) | `Đã ghi cân nặng` (201) |
| 15 | `POST …/medical-record/addenda` | **mọi VET** của chi nhánh (không cần là người phụ trách, ADR-0070) | body (`content` @NotBlank sau khi bỏ khoảng trắng; `isInternal` vắng = false) → … → Thẩm mỹ 404 → lượt chưa COMPLETED 409 (`từ <trạng thái> sang COMPLETED`). Lượt chỉ tiêm không có dòng bệnh án vẫn thêm được (A17). Chỉ thêm, không sửa / xóa | `Đã thêm bản bổ sung` (201) |
| 10 | `POST …/complete` | VET (Khám/Tiêm) hoặc CARETAKER (Thẩm mỹ), **là người được gán** | path → vai trò → 404 → chi nhánh → người phụ trách 403 (kể cả lượt chưa gán) → **chức vụ không khớp nhóm 400 BR-TN-05 (đứng TRƯỚC 409)** → không IN_PROGRESS 409 → BR-KB-02 (chỉ nhóm Khám/Tiêm; xem dưới). Một giao dịch: Visit COMPLETED + `completedAt`, gỡ cờ `needsReassign`, lịch hẹn COMPLETED, Order PENDING (`pendingAt`), nhả khóa thú nếu hết lượt mở, bệnh án khóa | `Đã hoàn tất lượt` |
| 18 | `GET …/vaccination-options?vaccineTypeId=` | VET được gán | tham số → vai trò → … → người phụ trách 403 → loại vaccine 404 → loài không khớp 400 BR-KB-04 | — |
| 19 | `POST …/vaccinations` | VET được gán, lượt IN_PROGRESS | body → … → 409 → loại / phác đồ / sản phẩm 404 → BR-KB-04 (loài của loại; phác đồ đúng loại + loài; sản phẩm đúng loại; còn tồn khả dụng). Một giao dịch: trừ kho FEFO 1 liều NGAY, thêm dòng Order VACCINE (giá chụp), ghi mũi (ngày tiêm = hôm nay, tái chủng = ngày tiêm + khoảng cách của mũi) | `Đã ghi nhận mũi tiêm` (201, ASSUMPTION) |
| 20 | `DELETE …/vaccinations/:vaccinationId` | **VET đã ghi mũi đó** | path → vai trò → … → mũi của lượt này 404 → không phải người ghi 403 → lượt không IN_PROGRESS 409. Hoàn kho vào ĐÚNG lô đã trừ, xóa dòng Order vaccine | 204 |

**BR-KB-02 khi hoàn tất** (đọc dịch vụ từ Order LÚC NÀY vì VET đổi / xóa được dòng tự sinh): có dịch vụ Khám → `Lượt có dịch vụ khám phải có chẩn đoán trước khi hoàn tất` (chẩn đoán chỉ khoảng trắng coi như thiếu); không có Khám mà có Tiêm → `Lượt chỉ có dịch vụ tiêm phải ghi nhận ít nhất 1 mũi tiêm`; không còn dịch vụ nào (A16) → `Lượt khám/tiêm phải còn ít nhất 1 dịch vụ khám hoặc tiêm`. Mọi lỗi rule không ghi gì.

**Hồ sơ sức khỏe (#21)** giờ gộp dữ liệu mẫu T23 với dữ liệu thật của mock này: lượt đã hoàn tất kèm bệnh án và bản bổ sung, mũi tiêm tại hệ thống (kể cả mũi của lượt chưa xong), mũi "sắp tới" = mũi mới nhất mỗi loại, mũi cũ hơn cùng loại là `superseded` (BR-TB-03). Khách (chủ hiện tại) bị lọc ghi chú nội bộ và bản bổ sung nội bộ (BR-KH-07).

### A.3 ASSUMPTION cho #18–20 (dựng khi BE chưa có) và đối chiếu với BE T28

Đối chiếu ngày 26/10 bằng **đọc code** BE T28 (`VaccinationOptionService`, `VaccinationRecordService`, `MedicalRecordMapper`, `RecordVaccinationRequest`, ADR 0072–0075). **Chưa chạy FE trên BE thật; mock và FE chưa sửa.** Nhãn **KHỚP** / **LỆCH** ở cuối mỗi mục; việc FE phải sửa gom ở A.3b.

1. **Thứ tự kiểm** copy #13 (403 người phụ trách trước 409; 404 id loại / phác đồ / sản phẩm sau 409, trước rule 400). Hợp đồng chỉ liệt kê mã lỗi. **LỆCH.** BE #19: 403 người phụ trách → 409 → 404 loại vaccine → 400 BR-KB-04 (loài) → 400 BR-SP-07 (loại đã ngừng) → 400 BR-KB-04 (phác đồ, rồi sản phẩm; **id không tồn tại cũng là 400, không phải 404**) → 400 hết tồn. BE #20: **403 → 409 → 404 mũi** (mock: 404 → 403 → 409).
2. **Câu chữ lỗi tự đặt** (BE chưa có để chép): thiếu `vaccineTypeId` → 400 `MALFORMED_REQUEST` `Thiếu tham số 'vaccineTypeId'`; body #19 thiếu mã → `VALIDATION_FAILED` (`vaccineTypeId: Thiếu loại vaccine`…); `Sản phẩm không thuộc loại vaccine đã chọn`, `Mũi tiêm không thuộc phác đồ của loại vaccine và loài của thú`, `Sản phẩm vaccine không còn tồn khả dụng tại chi nhánh`; thông điệp thành công #19. Loại không đúng loài dùng đúng câu của hợp đồng `Loại vaccine không áp dụng cho loài của thú` kèm mã BR-KB-04. **LỆCH một phần:** câu khớp và câu khác chữ ở A.3b.
3. **Mũi gợi ý** = mũi kế tiếp sau mũi mới nhất cùng loại của thú tại hệ thống (chưa tiêm → mũi 1). **Hết phác đồ thì `suggestedProtocolId = null`** để VET tự chọn (hợp đồng cho phép null); mock KHÔNG suy ra "tiêm nhắc lại" vì không tài liệu nào nêu. **LỆCH.** BE (A18, ADR-0073): hết phác đồ thì gợi ý **mũi lớn nhất** (nhắc lại); `suggestedProtocolId = null` chỉ khi phác đồ rỗng.
4. **Không chặn tiêm trùng mũi** (không có luật). VET được chọn mũi khác gợi ý (BR-KB-04). **KHỚP** (`VaccinationRecordService.record` không có kiểm trùng).
5. **#20 chỉ người đã ghi mũi đó** (hợp đồng: "VET đã ghi mũi đó"). Hệ quả đã biết: sau khi gán lại lượt (BR-TN-08), VET mới không xóa được mũi VET cũ đã ghi, dù quyền xóa dòng Order khác được chuyển cho người mới. **LỆCH, BE-1 đã quyết (ADR-0075 mục 4, A24):** BE cho **người phụ trách hiện tại** xóa mũi của lượt, không xét người đã ghi; sau gán lại, VET mới xóa được mũi VET cũ ghi còn VET cũ nhận 403, ngược với mock. ADR-0075 loại phương án "quyền theo `recorded_by`".
6. **Tiêm trong lượt khám** (UC49 "tiêm ngay trong lượt khám trên cùng Visit"): cho mọi lượt Khám/Tiêm đang IN_PROGRESS, không bắt dịch vụ Tiêm. Lượt Thẩm mỹ → 404 như các endpoint bệnh án. **KHỚP** (BE không bắt dịch vụ Tiêm; lượt Thẩm mỹ → 404).
7. **`warnings`** là mã (`UNDER_MIN_AGE`), giao diện tự dịch. Chưa đủ tuổi tính theo mũi VET **chọn**, không phải mũi gợi ý. **KHỚP** (#19 `warnings` là `[]` hoặc `["UNDER_MIN_AGE"]` theo mũi chọn; #18 `underMinAge` theo mũi gợi ý).
8. `MedicalRecord.vaccinations`: **KHỚP sau T28**: BE trả các mũi của lượt (`MedicalRecordService` đọc `VaccinationRecordReader.forVisit`), như mock. **Khác:** `lotNumber` và `expiryDate` luôn `null` ở BE (`MedicalRecordMapper` bỏ qua, nợ D046) trong #19, `lastDose` và `vaccinations`; mock điền lô thật.
9. Care Task#5 (hủy nhắc tái chủng cũ khi tiêm lại) không mô phỏng. **BE đã làm** khi ghi mũi (`careTasks.cancelForRevaccination`, BR-TB-03); không lộ ra response #18–20 nên FE không phải làm gì.

### A.3b Việc phải làm trước khi nối tiêm chủng với BE thật (chưa làm)

Câu lỗi mock tự đặt so với BE (còn lại giống chữ-theo-chữ, giữ nguyên: `Thiếu loại vaccine`, `Mã loại vaccine không hợp lệ`, `Thiếu sản phẩm vaccine`, `Mã sản phẩm không hợp lệ`, `Loại vaccine không áp dụng cho loài của thú`, `Sản phẩm vaccine không còn tồn khả dụng tại chi nhánh`, `Đã ghi nhận mũi tiêm`):

| Tình huống | Mock (T37) | BE (T28) |
|---|---|---|
| Thiếu `protocolId` (#19) | `Thiếu mũi theo phác đồ` | `Thiếu mũi tiêm` |
| `protocolId` không dương | `Mã phác đồ không hợp lệ` | `Mã mũi tiêm không hợp lệ` |
| Mũi không thuộc phác đồ đang dùng của loại + loài (kể cả id không tồn tại) | 400 `Mũi tiêm không thuộc phác đồ của loại vaccine và loài của thú`; id lạ: 404 `Không tìm thấy phác đồ tiêm chủng #id` | 400 BR-KB-04 `Mũi tiêm không thuộc phác đồ đang dùng của loại vaccine cho loài này` |
| Sản phẩm không phải vaccine đang kinh doanh của loại (kể cả id không tồn tại) | 400 `Sản phẩm không thuộc loại vaccine đã chọn`; id lạ: 404 `Không tìm thấy sản phẩm #id` | 400 BR-KB-04 `Sản phẩm không phải vaccine đang kinh doanh của loại đã chọn` |
| Lượt Thẩm mỹ | 404 `Không tìm thấy bệnh án #id` | 404 `Không tìm thấy lượt khám / tiêm #id` |
| Loại vaccine đã ngừng (#18, #19) | không mô phỏng | 400 BR-SP-07 `Loại vaccine đã ngừng sử dụng` |
| Lô cạn hoặc hết hạn giữa chừng (#19) | không mô phỏng | 400 BR-KO-05 `Không còn lô chưa hết hạn nào đủ 1 để xuất (tồn khả dụng N)` hoặc `Lô … đã hết hạn, không xuất được` |
| Xóa mũi mà Care Task đang trỏ tới (#20) | không mô phỏng | 409 `CONCURRENCY_CONFLICT` (ADR-0075) |

Sửa FE / mock:

1. **Xóa mũi (#20):** nút Xóa theo người phụ trách hiện tại (`canVaccinate`), không theo `recordedBy` (`VaccinationSection.tsx`); mock đổi thứ tự kiểm sang 403 → 409 → 404 và bỏ kiểm `recordedBy`; sửa câu 403 "…hoặc đã ghi mũi tiêm này…" trong `visitErrorMessage`.
2. **Gợi ý khi hết phác đồ:** mock gợi ý mũi lớn nhất (null chỉ khi phác đồ rỗng). Dòng "Đã hết phác đồ…" trong `VaccinationSection.tsx` không bao giờ hiện với BE thật, bỏ hoặc đổi nghĩa.
3. **`products`:** sắp theo tên rồi id như BE (mặc định chọn `products[0]`; mock đang sắp theo id).
4. **`lotNumber` / `expiryDate` = `null`:** giao diện đã ẩn lô / hạn khi null nên không vỡ; mock nên trả `null` để test sát BE.
5. **Lỗi:** 400 (không 404) cho phác đồ / sản phẩm lạ; đổi câu theo bảng; thêm BR-SP-07, BR-KO-05; `CONCURRENCY_CONFLICT` của #20 hiện câu của hàng đợi ("Lượt vừa được gán lại…") trong `visitErrorMessage`, cần câu riêng.
6. **Test và chú thích lỗi thời:** sửa test mock tương ứng (`visit-clinical.mock.test.ts`); chú thích còn nói "BE chưa có (T28)" hoặc "trả `[]` tới khi T28": `medical-record.ts`, `VaccinationSection.tsx`, `visit-clinical.mock.ts` (đầu file, đoạn #18–20, `vaccinations`), `visit-clinical.mock.test.ts`, tên ca "BE thật chưa có tiêm chủng (T28)" trong `DoctorWorkspacePage.test.tsx`.
7. **Chạy thử** ghi mũi, xóa mũi, hoàn tất lượt trên BE thật (`VITE_API_MOCK=false`, không đặt các đường dẫn này vào `VITE_API_MOCK_ONLY`).

### A.4 Mock lệch BE thật

1. **Không audit** (`MEDICAL_RECORD_UPDATED`, `MEDICAL_RECORD_ADDENDUM_ADDED`), không khóa đồng thời, không thứ tự khóa.
2. BE ghi rồi kiểm BR-KB-02 và hoàn tác; mock kiểm trước khi ghi. Nhìn từ ngoài giống nhau (test "KHÔNG ghi gì").
3. Không dựng "bệnh án rỗng đã khóa" khi thêm bản bổ sung cho lượt chỉ tiêm (A17): không quan sát được qua API.
4. [CFG] `medical_record.follow_up_max_days` cố định 180 (FE và mock không đọc được [CFG]).
5. Thời điểm tới mili giây (`Instant` của BE tới µs). Id bản bổ sung và mũi tiêm dùng chung một bộ đếm (BE: mỗi bảng riêng).
6. **`busy: PENDING_ORDER`** (BR-KH-08) không mô phỏng: sau hoàn tất thú hết bị khóa `OPEN_VISIT`, nhưng BE vẫn chặn chuyển chủ / đánh dấu mất khi Order chưa thanh toán.
7. Tiền sử thú "mũi tiêm ngoài hệ thống" không có (BR-KB-05 đã bỏ): chỉ mũi do hệ thống ghi.
8. **Chế độ hỗn hợp** (`VITE_API_MOCK_ONLY`) không dùng được cho tiêm chủng: id lượt của BE khác id lượt của mock, nên `GET/POST /visits/{id}/vaccin…` của mock không biết lượt thật.

### A.5 Chỗ nối với mock khác (chỉ THÊM export)

| File | Export mới | Dùng cho |
|---|---|---|
| `mock/visit.mock.ts` | `visitRowOf`, `updateVisitRow`, `visitView`, `completedVisitsOfPet`, `hasOtherOpenVisit`, `isoOf`, `transition`, `ROLE_OF_GROUP`, helper đọc JSON (`asBoolean`, `asLong`, `asText`, `idParam`, …) | `VisitQueryApi.findVisit`, Visit#5, hồ sơ sức khỏe |
| `mock/appointment.mock.ts` | `completeAppointmentByVisit` | Lịch hẹn#7 (CHECKED_IN → COMPLETED) |
| `mock/sales.mock.ts` | `serviceIdsOfVisit`, `markOrderPendingByVisit`, `addVaccineLine`, `removeVaccineLine` | `VisitOrderApi` (đọc dịch vụ lúc hoàn tất, Order#4, dòng vaccine) |
| `mock/inventory.mock.ts` | `issueOneFefo`, `returnToLot` (+ `fefoLots` dùng chung với `issueFefo`) | `StockApi` trừ 1 liều FEFO NGAY khi tiêm, hoàn đúng lô khi xóa mũi |
| `mock/customer-pet.mock.ts` | `recordVisitWeight`, `registerHealthSource`; `petSummary` thêm `birthDate`; hòa giờ cân nặng lấy lần ghi sau | `PetApi.recordWeight`, tuổi thú, hồ sơ sức khỏe |
| `mock/catalog.mock.ts` | `catalogVaccineTypes`, `catalogProtocols` | gợi ý mũi, kiểm phác đồ |
| `mock/mock-api.ts` | `...visitClinicalRoutes` | đăng ký |

### A.6 TDD: red trước, và chỗ phải sửa test cũ

**Red trước (đúng lý do):** `visit-clinical.mock.test.ts` đăng ký `visitClinicalRoutes = {}` rỗng trước: lần lượt 9 ca #12/#13, 11 ca #10, 3 ca #18, 10 ca #19/#20, 7 ca #14/#15 đỏ với "Chưa có mock cho …" (404); các ca hồ sơ sức khỏe đỏ vì chưa có nguồn dữ liệu thật. `useExam.test.tsx` đỏ vì chưa có module; 4 ca `visitErrorMessage` mới đỏ. `DoctorWorkspacePage.test.tsx`: 14 ca bệnh án / hoàn tất / bản bổ sung đỏ trên trang cũ ("chưa nối API (T37)"); `GroomingBoard.test.tsx` + `useKanban.test.ts`: 10 ca đỏ.
**Lỗi của chính test (sửa test, không sửa code):** đánh dấu thú đã mất cần `confirmed: true`; form tiêm chỉ hiện sau khi danh mục loại vaccine tải xong nên cần `findBy`; kiểu TS của test hồ sơ sức khỏe.
**Lỗi thật do test bắt:** (1) hai lần cân cùng thời điểm thì mock chọn lần cũ làm cân nặng hiện tại → thêm tie-break theo id; (2) khi bệnh án đang tải, khung bệnh nhân mất thông tin chủ nuôi / dịch vụ → luôn hiện phần thông tin.

**Phải sửa test cũ (không tránh được):**
- `mock-api.test.ts` › "answers 404 for a path that has no mock yet": T36 báo trước, dùng `GET /api/visits/1/medical-record`; đổi sang `POST /api/visits/1/prescriptions` (kê đơn, T46), giữ mọi assertion.
- `useVisits.test.tsx` › ma trận `visitPermissions`: thêm 5 hành động mới (`viewRecord`, `writeRecord`, `addendum`, `complete`, `vaccinate`).
- `DoctorWorkspacePage.test.tsx`: khối "ghi bệnh án chờ T37" thay bằng các khối mới; ca "bác sĩ chỉ hỏi hàng đợi của tôi" nay cho phép hỏi hàng đợi chi nhánh **chỉ để lấy "Đã khám hôm nay"** (`status=COMPLETED`).
- `GroomingBoard.test.tsx`, `GroomingWorkspacePage.test.tsx`, `useKanban.test.ts`: bỏ ca "Xong tắt kèm lời nhắn T37" / `T37_PENDING`; hàng đợi chi nhánh nay chỉ được hỏi để lấy lượt Thẩm mỹ **đã xong**.
- `customer-pet.mock.test.ts` › hồ sơ sức khỏe: thú #1 giờ còn có lượt thật 61, 2001, nên tìm lượt mẫu 1002 theo mã (không theo vị trí đầu) và ghim ngày.
- **Xóa** `SoapPanel.tsx`, `PatientPanel.tsx`, `ExamPendingPanel.tsx`, `consultation.store.ts` và test của chúng (đã thay bằng `ExamPanel`). Hai ca BR-KB-02 của `SoapPanel.test.tsx` đã chuyển sang `DoctorWorkspacePage.test.tsx` (khám thiếu chẩn đoán → 400 không ghi gì; có chẩn đoán → Order PENDING), chạy trên mock thay vì `clinic-db`.

### A.7 Đột biến (sửa một chỗ, chạy test liên quan, thấy đỏ, khôi phục byte-for-byte rồi so `Buffer.compare`): 32 đột biến, 32 bị bắt

| Nhóm | Đột biến đã bị bắt |
|---|---|
| Hoàn tất (#10) | bỏ chẩn đoán bắt buộc · bỏ ≥ 1 mũi cho lượt chỉ tiêm · cho hoàn tất khi không còn dịch vụ (A16) · không gỡ `needsReassign` · không nhả khóa thú · `locked` luôn false |
| Thứ tự lỗi | 409 đứng trước 403 người phụ trách |
| BR-KB-06 | ngày tái khám = ngày khám · cho tới ngày thứ 181 |
| Bản bổ sung | thêm được khi lượt chưa hoàn tất · mặc định thành nội bộ |
| Tiêm chủng | bỏ cảnh báo `UNDER_MIN_AGE` · sai khoảng cách tái chủng · ai cũng xóa được mũi · xóa được khi đã hoàn tất · mũi cũ không `superseded` · xóa mũi không hoàn kho · hoàn kho sai lô · **FEFO lấy cả lô hết hạn** |
| Cân nặng | không làm tròn · hòa giờ lấy lần cũ |
| Giao diện | hoàn tất không lưu trước · ai cũng sửa được bệnh án · tắt Ctrl+S / Ctrl+Enter · không đưa con trỏ về ô Chẩn đoán · nút xóa mũi hiện cho mọi người · không chọn sẵn mũi gợi ý |
| Hook / câu lỗi | ghi mũi không làm mới `[inventory]`, `[pets]` · bản bổ sung gửi sai đối số · bệnh án khóa báo như hàng đợi |
| Grooming | cho báo Xong từ cột Chờ làm · Xong không hoàn tất lượt |

## B. Giao diện

> Phạm vi: bàn **Phòng khám** (`/staff/doctor`: VET) và khu **Grooming** (`/staff/grooming`: CARETAKER). Đọc / ghi qua `useExam.ts` và `useVisits.ts`, không còn đi qua `clinic-db` cho phần này.

### B.1 Màn hình và file

| Màn / khối | File (FE/src) | Ghi chú |
|---|---|---|
| Trang Phòng khám | `pages/staff/DoctorWorkspacePage.tsx` | Hàng chờ của tôi (#3) + "Đã khám hôm nay" (#2 `status=COMPLETED`) \| khung bệnh nhân. Hoàn tất xong thì chọn lượt kế tiếp |
| Hàng chờ | `components/staff/doctor/DoctorQueue.tsx` | Thêm mục **Đã khám hôm nay** (lượt Khám/Tiêm **check-in hôm nay** và đã hoàn tất, của cả chi nhánh): đường vào bệnh án đã khóa và bản bổ sung (UC53). Làm mới 10 giây như các hàng đợi khác |
| Khung bệnh nhân | `doctor/ExamPanel.tsx` | Thay `ExamPendingPanel`: thông tin lượt, gọi lượt / cấp cứu khi còn chờ, rồi bệnh án |
| Bệnh án | `doctor/MedicalRecordEditor.tsx` | 5 trường của hợp đồng, cân nặng, tiêm chủng, **Chỉ định trong lượt** (Order, chỉ đọc), lưu / hoàn tất |
| Tiêm chủng | `doctor/VaccinationSection.tsx` | Danh sách mũi trong lượt, xóa mũi ghi nhầm, form chọn loại → mũi → sản phẩm |
| Bản bổ sung | `doctor/AddendaSection.tsx` | Danh sách + form thêm (khi đã khóa) |
| Grooming | `pages/staff/GroomingWorkspacePage.tsx`, `grooming/GroomingBoard.tsx`, `shared/hooks/useKanban.ts` | "Xong" hoàn tất lượt; cột **Xong hôm nay** nạp từ API |

### B.2 Ma trận vai trò trên giao diện (từ `visitPermissions(role)`)

| Việc | VET | CARETAKER | Ghi chú |
|---|---|---|---|
| Xem bệnh án (#12) | có (được gán hoặc xem lượt đã xong) | không có màn | CARETAKER có quyền gọi #12 nhưng UC25 (`/admin/customers`, hồ sơ sức khỏe) đã là đường xem bệnh án của họ |
| Ghi bệnh án, cân nặng (#13, #14) | chỉ **người được gán**, lượt IN_PROGRESS | — | còn lại chỉ đọc kèm "Lượt của <tên>. Bạn chỉ xem được." |
| Tiêm chủng (#18–20) | người được gán, lượt IN_PROGRESS; xóa chỉ mũi mình ghi | — | |
| Hoàn tất lượt (#10) | người được gán (Khám/Tiêm) | người được gán (Thẩm mỹ) | |
| Thêm bản bổ sung (#15) | **mọi VET** của chi nhánh, lượt COMPLETED | — | |

### B.3 Hành vi

**Bệnh án (UC48).** Lượt đang khám mở sẵn form: *Khám lâm sàng, Chẩn đoán, Hướng điều trị, Hẹn tái khám, Ghi chú nội bộ* (kèm "Khách không xem được ghi chú này."). Trạng thái hiện bên cạnh tiêu đề: "Chưa có thay đổi / Có thay đổi chưa lưu / Đang lưu… / Đã lưu lúc HH:mm". **Không tự lưu**: lưu khi bấm *Lưu* hoặc `Ctrl+S` (mỗi lần lưu có thay đổi BE ghi một dòng audit, ADR-0066); không có thay đổi thì nút Lưu tắt. Ô ngày tái khám chỉ cho chọn từ ngày sau ngày khám tới tối đa 180 ngày; BE vẫn kiểm (BR-KB-06) và câu báo hiện ngay trong form.

**Hoàn tất (UC48/49/52).** *Hoàn tất, chuyển thu ngân* hoặc `Ctrl+Enter`: **lưu bệnh án trước** (dừng nếu lưu lỗi), rồi gọi #10. FE **không tự kiểm BR-KB-02**: hiện nguyên câu BE trả (bỏ mã rule) ngay trong form; riêng câu "chẩn đoán" thì đưa con trỏ về ô Chẩn đoán. Thành công: toast "Đã chuyển <thú> sang quầy thu ngân", Order sang "Chờ thu", chọn lượt kế tiếp; lượt vừa xong xem lại ở "Đã khám hôm nay".

**Cân nặng (BR-KH-04).** Ô "Cân nặng (kg)" + *Ghi cân* (kèm "Hiện tại X kg"); số không hợp lệ thì BE từ chối và câu báo hiện tại chỗ, giữ giá trị đã gõ.

**Tiêm chủng (UC49).** Chọn *Loại vaccine* (chỉ loại đúng loài của thú; loài chưa có loại nào thì nói rõ) → hệ thống gợi ý *mũi theo phác đồ* (đã chọn sẵn, đổi được) và *sản phẩm vaccine* còn tồn khả dụng (kèm số liều) → *Ghi mũi tiêm*. Loại vaccine hết sản phẩm còn tồn thì không cho chọn (nêu lý do), nút ghi tắt. Chưa đủ tuổi: cảnh báo, không chặn. Ghi mũi trừ kho FEFO ngay và dòng vaccine hiện ở "Chỉ định trong lượt". Xóa mũi ghi nhầm chỉ khi lượt còn đang khám và chỉ mũi mình ghi; sau khi hoàn tất, mũi chỉ để xem kèm lời nhắc "sai sót ghi bằng bản bổ sung" (BR-KB-04).

**Bản bổ sung (UC53).** Chọn lượt trong "Đã khám hôm nay": bệnh án khóa, chỉ đọc, kèm danh sách bản bổ sung (nội dung, người ghi, giờ, nhãn *Nội bộ*) và form *Nội dung bổ sung* + ô *Chỉ nhân viên xem* + *Thêm bổ sung* (tắt khi trống; lỗi BE hiện tại chỗ, giữ nội dung).

**Grooming (UC52).** *Xong* (hoặc kéo thẻ "Đang làm" sang "Xong hôm nay") là hoàn tất lượt; thẻ sang cột ngay, BE từ chối thì về lại cột cũ kèm toast. Cột **Xong hôm nay** (đổi tên từ "Xong, chờ đón" vì BE không có trạng thái "đã đón") lấy các lượt Thẩm mỹ đã COMPLETED trong ngày: "Của tôi" lọc theo `assigneeId`, "Cả khu" lấy cả chi nhánh; chỉ để xem.

### B.4 Ca kiểm thử

Chạy với mock: `cd FE && npm run dev`, mật khẩu `Petcare123`, nhân viên đăng nhập ở `/admin/login`. Dữ liệu mẫu tính theo giờ nạp. Đưa dữ liệu về ban đầu: xóa `petcare-mock-clinical`, `petcare-mock-visit`, `petcare-mock-sales`, `petcare-mock-inventory`, `petcare-mock-pets`, `petcare-mock-appointment` trong Local Storage. Test tự động chạy với đồng hồ ghim thứ Năm 22/10/2026 10:40.

| # | Tài khoản | Làm | Kỳ vọng | Test tự động (rút gọn) |
|---|---|---|---|---|
| 1 | doctor | Mở Phòng khám | Lu (đang khám) mở sẵn: đủ 5 ô bệnh án, "Chưa có thay đổi", "Kê đơn thuốc chưa nối API (T46)" | `DoctorWorkspacePage` › lượt đang khám mở sẵn form… |
| 2 | doctor | Chọn Tôm (đang chờ) | Không có form, chỉ "Gọi lượt để ghi bệnh án."; bấm *Gọi Tôm vào khám* thì form hiện | › lượt đang chờ chưa có form… |
| 3 | doctor | Gõ Chẩn đoán, chờ 2 giây | Vẫn "Có thay đổi chưa lưu", KHÔNG tự lưu; bấm *Lưu* → "Đã lưu lúc …" | › KHÔNG tự lưu… |
| 4 | doctor | Gõ rồi `Ctrl+S` | Lưu ngay; Lưu tắt khi không có thay đổi | › Ctrl+S lưu ngay… |
| 5 | doctor | Hẹn tái khám = ngày khám | 400 BR-KB-06 hiện trong form, vẫn chưa lưu; ngày hợp lệ lưu được; ô ngày giới hạn sau ngày khám … +180 ngày | › hẹn tái khám sai / hợp lệ |
| 6 | doctor | *Hoàn tất* khi chưa có chẩn đoán | Câu BE "Lượt có dịch vụ khám phải có chẩn đoán trước khi hoàn tất" trong form, con trỏ về Chẩn đoán, lượt vẫn đang khám | › thiếu chẩn đoán… |
| 7 | doctor | Gõ Chẩn đoán → *Hoàn tất* (hoặc `Ctrl+Enter`) | Lưu rồi hoàn tất; toast; lượt COMPLETED; Order "Chờ thu" ở quầy lễ tân; sang Tôm | › có chẩn đoán…; › Ctrl+Enter… |
| 8 | doctor | Ngày tái khám sai + *Hoàn tất* | Lưu lỗi nên KHÔNG gọi hoàn tất | › lưu trước khi hoàn tất bị BE từ chối… |
| 9 | doctor | "Đã khám hôm nay" → Mochi | Bệnh án khóa, các ô chỉ đọc, "Bệnh án đã khóa…", không có Lưu / Hoàn tất | › hàng chờ có thêm "Đã khám hôm nay"… |
| 10 | doctor | Thêm bản bổ sung (thường, rồi nội bộ) | Xuất hiện cuối danh sách kèm người ghi; bản nội bộ có nhãn *Nội bộ*; ô trống thì nút tắt; lỗi BE giữ nội dung | › hiện bản bổ sung…; › lỗi từ BE khi thêm… |
| 11 | doctor | Ghi cân 18.4 | Toast, ô được xóa, "Hiện tại 18.4 kg"; cân 0 → câu BE tại chỗ | `exam room — cân nặng` (2 ca) |
| 12 | doctor | Tiêm chủng Lu: loại *5 bệnh* | Mũi 1 (gợi ý) + "Vaccine 5 bệnh Vanguard (còn 4 liều)" chọn sẵn; *Ghi mũi tiêm* → "Mũi 1 · 5 bệnh", dòng vaccine trong Chỉ định, còn 3 liều, gợi ý sang mũi 2 | `exam room — tiêm chủng` › chọn loại vaccine… |
| 13 | doctor | Đổi sang Mũi 2 rồi ghi | Gửi đúng `protocolId` đã chọn | › VET chọn mũi khác với gợi ý… |
| 14 | doctor | *Xóa mũi 1 5 bệnh* | Mũi và dòng vaccine biến mất, kho hoàn 4 liều | › xóa mũi ghi nhầm… |
| 15 | doctor | Ghi 4 liều rồi chọn lại loại | "không còn sản phẩm nào có tồn khả dụng", nút Ghi tắt | › loại vaccine hết sản phẩm… |
| 16 | doctor | Tiêm cho thú chưa đủ tuổi | Vẫn ghi được, toast cảnh báo "chưa đủ tuổi tối thiểu" | › chưa đủ tuổi tối thiểu… |
| 17 | doctor | Tôm (hamster) | "Loài này chưa có loại vaccine nào trong danh mục", không có form | › thú nuôi loài chưa có loại vaccine… |
| 18 | doctor | Mở lượt đã hoàn tất có mũi tiêm | Chỉ xem mũi, KHÔNG có nút xóa, KHÔNG có form ghi, nhắc dùng bản bổ sung | › lượt đã hoàn tất: mũi tiêm chỉ để xem… |
| 19 | caretaker | Khu Grooming → Bắt đầu Bim → *Xong* | Thẻ sang "Xong hôm nay" ngay; lượt COMPLETED, Order chờ thu; BE từ chối thì thẻ về "Đang làm" + toast | `GroomingBoard` › "Xong" hoàn tất lượt…; hoàn tất thật…; BE từ chối hoàn tất… |
| 20 | caretaker | Kéo thẻ "Đang làm" sang "Xong hôm nay" | Hoàn tất lượt; kéo thẻ "Chờ làm" thẳng sang "Xong" thì từ chối "Bắt đầu làm trước khi báo xong." | › kéo thẻ của mình…; › kéo bỏ qua "Đang làm"… |
| 21 | caretaker | "Cả khu" | Cột "Xong hôm nay" có cả lượt của người khác, chỉ xem | › "Cả khu": cột "Xong hôm nay"… |
| 22 | khach | `/pets` → hồ sơ sức khỏe Mochi | Thấy lượt 61 (chẩn đoán, bản bổ sung công khai), KHÔNG thấy ghi chú nội bộ / bản bổ sung nội bộ; mũi vừa tiêm hiện, mũi "sắp tới" là mũi mới nhất | `visit-clinical.mock.test` › hồ sơ sức khỏe… (4 ca) |

### B.5 Giả định giao diện

1. **Không tự lưu** (ADR-0066/0067: mỗi lần lưu có thay đổi là một dòng audit); lưu khi bấm hoặc `Ctrl+S`; hoàn tất lưu trước.
2. **FE không tái hiện BR-KB-02** để luật không lệch BE. Cái giá: một vòng gọi API khi thiếu chẩn đoán (đổi lại câu báo luôn đúng với dịch vụ trong Order lúc hoàn tất).
3. **"Đã khám hôm nay"** lấy lượt Khám/Tiêm COMPLETED của **cả chi nhánh** (`GET /visits?status=COMPLETED`) vì UC53 cho mọi VET của chi nhánh thêm bản bổ sung. Theo A1 của hợp đồng đó là lượt **check-in hôm nay**: lượt check-in hôm qua mà hoàn tất hôm nay KHÔNG có trong danh sách (nhãn "hôm nay" ở đây nghĩa là ngày check-in). Lượt cũ hơn xem ở hồ sơ sức khỏe (UC25).
4. **Hẹn tái khám** làm ở T37 vì là trường của PUT #13 (T46 chỉ còn kê đơn).
5. **Chỉ định trong lượt** chỉ đọc (sales-v1 #3); thêm / đổi / xóa dòng thuộc module bán hàng (UC67), không làm ở đây. VET đổi dịch vụ tự sinh vẫn có hiệu lực ở BR-KB-02 vì BE đọc dịch vụ từ Order.
6. **"Xong hôm nay"** thay "Xong, chờ đón": BE không có trạng thái "đã đón" nên tên cũ nói sai.
7. Ngưỡng 180 ngày là hằng số FE (`FOLLOW_UP_MAX_DAYS`) vì FE không đọc được [CFG]; chỉ để giới hạn ô ngày.

### B.6 Còn lại, không làm ở T37

- **Kê đơn (#16–17)** là T46: bệnh án chỉ ghi "Kê đơn thuốc chưa nối API (T46)"; `prescriptions` có trong kiểu nhưng luôn rỗng ở mock và BE (tới T38).
- **Care Task / ST04** (T39) và **audit** không mô phỏng. **Chưa chạy thử màn hình với BE thật** (script seed chưa dựng dữ liệu lâm sàng); kiểu dữ liệu của #10, #12–15 lấy từ code BE nên khả năng lệch thấp, nhưng cần thử.
- **Trên BE thật (từ 26/10):** bệnh án, cân nặng, hoàn tất, bản bổ sung (T27) và tiêm chủng (T28) đều có endpoint thật. Tiêm chủng **chưa chạy thử**: còn các chỗ lệch ở A.3 / A.3b (xóa mũi theo người phụ trách, gợi ý khi hết phác đồ, lô / hạn `null`, câu lỗi) cần sửa mock và FE trước. Chế độ hỗn hợp không dùng được cho tiêm chủng (A.4-8).
- **Mã clinic chết sau T37** (gỡ cùng đợt dọn T57, không sửa ở đây): trong `hooks/useClinic.ts` các hook `useVisitDetail`, `useSaveRecord`, `useVisitLines`, `useCatalog`, `useMoveVisit` hết người dùng; trong `clinic.api.ts` `getVisitDetail`, `saveRecord`, `addVisitLine`, `removeVisitLine`, `replaceVisitService`, `getCatalog`, `completeVisit`, `callVisit` chỉ còn `clinic.api.test.ts` gọi; `INVALIDATE.saveRecord`, `editVisitLines`, `completeVisit` trong `queryKeys.ts`; `types/clinic.ts` (`VisitDetail`, `ClinicalRecord`…) và `clinic-db.ts` phần bệnh án; `utils/clinic-rules.ts` `completeViolation`. `AdminDashboardPage` vẫn dùng `clinic-db` cho KPI (T49).
- Phím tắt `Ctrl+S`, `Ctrl+Enter` (bàn bác sĩ) và "bấm Bắt đầu hoặc Xong" (grooming) trong `shortcuts.ts` nay đúng trở lại (T36 B.7 đã ghi là tạm sai).

### B.7 Kiểm chứng

Chạy từ `FE/` sau mọi thay đổi code:
- `npx tsc --noEmit`: sạch.
- `npx eslint src --ext ts,tsx --report-unused-disable-directives --max-warnings 0`: sạch.
- `npx vitest run --maxWorkers=4`: **88 file, 1159 test xanh** (nền 88 / 1077 trước T37: +2 file `visit-clinical.mock.test.ts` 44 ca và `useExam.test.tsx` 7 ca; −2 file `SoapPanel.test.tsx`, `consultation.store.test.ts`). Số ca các file đã sửa: `DoctorWorkspacePage.test` 38, `GroomingBoard.test` 15, `useVisits.test` 52, `useKanban.test` 9, `GroomingWorkspacePage.test` 3, `customer-pet.mock.test` 22.
- `npm run build`: thành công.
- 32 đột biến, 32 bị bắt (A.7).
