# ADR-0046: Xem khung giờ trống — cửa sổ BR-LH-04, chi nhánh DRAFT, một snapshot chỉ đọc, không khóa

- **Trạng thái:** Accepted
- **Đánh số lại:** ban đầu là ADR-0042; đổi thành số hiện tại khi gộp `dev` ngày 2026-10-20 vì trùng số với ADR-0041…0044 của BE-2 (vào `dev` trước). Mọi tham chiếu ADR-0041…0046 trong code / tài liệu lịch hẹn đã đổi theo (+4); migration `V13__appointment_code_sequence.sql` đổi thành V16.
- **Ngày:** 2026-10-15
- **Liên quan:**
  - BR-LH-01 (02 L122), 02 (L123), 03 (L124), 04 (L125); BR-CN-01 (L65); BR-TK-17, 19; BR-LH-09.
  - UC39 (01 L104); appointment-v1 #1 (`docs/api/appointment-v1.md` mục A, B, C; yaml `listAvailableSlots`, schema `Slot`); `docs/api/00-method.md` L66 (404 khi không được biết là có), L69 (thứ tự kiểm).
  - ADR-0006 §4 (khách chọn chi nhánh làm đích không đi qua `BranchScope`), ADR-0020 (OSIV tắt), ADR-0038 (readOnly + REPEATABLE READ), ADR-0045 (khóa đặt lịch, `SlotGrid`, khung transaction của phần 2–4).
- **Triển khai (T16 phần 1):**
  - `appointment/controller/AvailableSlotController`, `appointment/service/{AvailableSlotService, BookingWindow, SlotAvailability}`, `appointment/dto/SlotResponse`, `appointment/mapper/SlotMapper`, `AppointmentRepository.countActiveBySlot`.
  - `platform/config/OpenApiConfig` thêm tag `Slots`; `OpenApiContractParityIT.DOCUMENTED` thêm `appointment-v1.yaml`.
  - Hợp đồng: `docs/api/generator/m_appointment.py` op #1 (mô tả lỗi 400 / 403 / 404, ghi chú cửa sổ).
  - Test:
    - Unit: `BookingWindowTest`, `SlotMapperTest`, `AvailableSlotServiceTest`.
    - IT: `module/appointment/AvailableSlotsIT` (36 case), `OpenApiContractParityIT`.

## Bối cảnh

`GET /api/branches/{branchId}/available-slots?serviceId=&date=` là endpoint đầu tiên của module appointment. Nó trả danh sách khung 30 phút của một chi nhánh × dịch vụ × ngày, kèm quota, số đã đặt và `AVAILABLE` / `FULL`. Docs để ngỏ mấy điểm:

- BR-LH-04 "khách đặt xa nhất 30 ngày" không nói tính theo ngày hay theo thời điểm.
- Chi nhánh `DRAFT`: BR-CN-01 không cho hiển thị công khai, nhưng hợp đồng #1 chỉ ghi `400 BR-LH-01` và `404`.
- `serviceId` không tồn tại: hợp đồng có cả 400 BR-LH-01 lẫn 404.
- Khách còn cờ chờ liên kết (BR-TK-19) hoặc đang bị hạn chế online (BR-LH-09): có chặn xem khung không.
- Endpoint đọc bằng nhiều câu lệnh (chi nhánh, dịch vụ, giờ mở cửa, ngày nghỉ, số đã đặt, quota từng khung). Ở READ COMMITTED, các câu có thể thấy dữ liệu trước và sau một thay đổi đồng thời.

## Quyết định

1. **Cửa sổ BR-LH-04** (`BookingWindow`, dùng lại cho đặt / đổi lịch ở phần 2, 4):
   - Khách: `hôm nay ≤ ngày hẹn ≤ hôm nay + appointment.max_advance_days` (**theo ngày**, giờ Việt Nam: ngày thứ 30 thấy đủ khung), **và** `giờ bắt đầu ≥ now + appointment.min_lead_hours` (**theo thời điểm**, đúng 24 giờ vẫn được).
   - Lễ tân: mọi khung **chưa bắt đầu** (`giờ bắt đầu > now`; đúng giờ bắt đầu là đã bắt đầu), không giới hạn xa.
   - Ngày quá khứ: rỗng với mọi người.
   - `now` đọc một lần mỗi request. [CFG] đọc ở mỗi request (thao tác chỉ đọc, không có dòng để snapshot).
2. **Ngoài cửa sổ, ngày nghỉ, ngày không giờ mở cửa → `200` với `data: []`**, không lỗi (A1 của hợp đồng: "không hiển thị khung ngoài khoảng").
3. **Thứ tự kiểm** (thứ tự thật của Spring):
   1. 401 (filter);
   2. 400 BR-TK-17 (interceptor);
   3. 400 `MALFORMED_REQUEST` khi tham số sai kiểu / thiếu — **trước** 403 role;
   4. 403 `ACCESS_DENIED` (`@PreAuthorize` chỉ `CUSTOMER`, `RECEPTIONIST`);
   5. lễ tân: `BranchScope.check` → 403 `ACCESS_DENIED_SCOPE_MISMATCH` **trước mọi lần đọc DB**;
   6. chi nhánh không tồn tại → 404; **khách + chi nhánh `DRAFT` → cùng 404, cùng message** (BR-CN-01);
   7. dịch vụ không tồn tại → 404 "Không tìm thấy dịch vụ #id";
   8. lễ tân + chi nhánh `DRAFT` → 400 "Chi nhánh chưa nhận đặt lịch (BR-LH-01)";
   9. dịch vụ ngừng kinh doanh / nhóm `BOARDING` / không bật tại chi nhánh → 400 "Dịch vụ không đặt được tại chi nhánh này (BR-LH-01)".
4. **Phân biệt khách / lễ tân bằng `switch` trên đúng hai role, `default` ném `IllegalStateException`.** Nới `@PreAuthorize` mà quên service thì ra 500 (fail-closed), không lọt vào nhánh của role khác. Khách không đi qua `BranchScope` (ADR-0006 §4).
5. **BR-TK-19, BR-LH-09 không kiểm ở đây**; chặn ở đặt lịch (#2). Endpoint không gọi `CustomerQueryApi` (placeholder, D010), nên chạy được trên app thật.
6. **Một transaction `readOnly = true` + `REPEATABLE_READ`, không khóa:**
   - Mọi câu đọc cùng snapshot chụp ở câu lệnh đầu tiên.
   - Transaction chỉ đọc ở RR không gặp 40001, không cần retry.
   - `BranchQueryService`, `CatalogQueryService` tham gia transaction này (`REQUIRED`).
   - Không gọi `lockPet`, `lockForBooking`, `nextCode`, không ghi (ADR-0045 §1 "Xem khung trống — không khóa").
   - **Hiển thị không giữ chỗ:** khung `AVAILABLE` vẫn có thể bị 400 BR-LH-03 lúc đặt; phần 2 kiểm lại dưới khóa (ADR-0045 §4).
7. **Đếm:** một câu native `GROUP BY slot_start` trên `appointments`, trạng thái `BOOKED`, `CHECKED_IN`, `COMPLETED` để **literal** (planner dùng được partial index `ix_appointments_quota`). Giờ trả dạng số giây trong ngày để không phụ thuộc kiểu Java của cột `TIME` trong native query.
8. **Quota:** `BranchQueryApi.quotaFor` cho từng khung (quota riêng → mặc định → 1). `FULL` khi `booked ≥ quota` (quota 0 luôn `FULL`). `booked` là số thật, không cắt về quota.
9. **Response:** `SlotResponse` dựng qua MapStruct (`unmappedTargetPolicy = ERROR`); giờ `HH:mm` bằng `@JsonFormat` (mặc định Jackson in `HH:mm:ss`). Cùng DTO cho khách và lễ tân (không có trường chỉ nhân viên).

## Lý do và phương án bị loại

- **30 ngày theo thời điểm** (`giờ bắt đầu ≤ now + 30×24h`): đối xứng với mốc 24 giờ, nhưng ngày thứ 30 chỉ thấy các khung trước giờ hiện tại, khó giải thích cho khách chọn lịch theo ngày. Người dùng chọn theo ngày.
- **Mọi người 400 BR-LH-01 với chi nhánh DRAFT:** khách biết được id chi nhánh nháp tồn tại (trái BR-CN-01). **Mọi người 404:** lễ tân của chính chi nhánh nháp nhận "không tồn tại" cho chi nhánh mình — sai sự thật.
- **Dịch vụ lạ → 400 BR-LH-01:** lẫn "không tồn tại" với "không đặt được"; 00-method xếp tồn tại trước guard.
- **Chặn BR-TK-19 ở #1:** phải gọi `CustomerQueryApi` (placeholder) → khách 500 tới T17; FE đã ẩn `/booking` khi còn cờ.
- **READ COMMITTED** (như ADR-0039): danh sách có thể ghép dữ liệu trước / sau một lần sửa quota, giờ mở cửa hoặc hủy hàng loạt. RR không tốn gì với transaction chỉ đọc. Người dùng chọn RR.
- **Thêm `BranchQueryApi.quotasOn(branch, group, date)`** (một câu cho cả ngày): sửa module của BE-2 chỉ để tối ưu; số câu hiện tại ≤ 2 mỗi khung, tra theo khóa. Để lại tới khi đo thấy chậm.
- **Mở cho BRANCH_MANAGER:** hợp đồng #1 chỉ có A02, A06; muốn mở phải đổi hợp đồng trước.

## Hệ quả

- **Tải DB một request:** `findBranch` 1 + `findService` 1 + `isServiceEnabled` 1 + giờ mở cửa / ngày nghỉ 2 + đếm 1 + `quotaFor` ≤ 2 mỗi khung. Ngày 24 khung ≤ 54 câu tra khóa; tối đa lý thuyết 48 khung ≤ 102. Không cache trong bộ nhớ.
- **Không khóa:** không chặn và không bị chặn bởi luồng ghi của branch, quản trị nhân viên hay đặt lịch.
- **Connection trả về pool đã reset** isolation và read-only (đã kiểm trong IT, như ADR-0038).
- **Phần 2 (đặt lịch) phải dùng `BookingWindow`**, cùng công thức với #1. Khách thấy khung nào thì đặt được khung đó, trừ khi hết chỗ trong lúc chờ.
- Không nợ mới.
