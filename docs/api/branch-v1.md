# Branch API v1 — Pet Care v16

> **Module:** CN (và cấu hình quota UC42, bật/tắt dịch vụ UC33) · **Owner:** BE-2. Chi nhánh, giờ mở cửa, ngày nghỉ (kèm hủy hàng loạt), bật/tắt dịch vụ, quota lịch hẹn, trang công khai chi nhánh.
> **Nguồn chân lý:** 01 UC12, UC14, UC15, UC33, UC42; 02 BR-CN-01…05, BR-LH-01, 03, 10, BR-QT-04, BR-CK-01; 03 #2 Chi nhánh; 04 §2; 05 §2 (branches, opening_hours, holidays, branch_services, branch_quota_defaults, slot_quotas).
> **Contract máy đọc:** [`./openapi/branch-v1.yaml`](./openapi/branch-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- UC13 tạm ngừng / đóng cửa chi nhánh là tầng 3, không có endpoint.
- Khung giờ trống để đặt lịch (BR-LH-02) thuộc module appointment: `GET /branches/{branchId}/available-slots`.
- Hủy hàng loạt chỉ phát `BranchClinicCancellationEvent`; module appointment / boarding tự hủy và thông báo khách (06-module-contracts §3).

---

## A. Danh sách endpoint (21)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `GET /branches` | Danh sách chi nhánh | UC12 | BR-CN-01 |
| 2 | `POST /branches` | Tạo chi nhánh | UC12 | BR-CN-01, 05 |
| 3 | `GET /branches/{branchId}` | Chi tiết chi nhánh | UC12 | — |
| 4 | `PATCH /branches/{branchId}` | Sửa chi nhánh / bật cờ cấp cứu ngoài giờ | UC12 | BR-CN-01, 05 |
| 5 | `POST /branches/{branchId}/activate` | Kích hoạt chi nhánh | UC12 | BR-CN-01, BR-QT-04 |
| 6 | `GET /branches/{branchId}/opening-hours` | Giờ mở cửa (bản đang áp dụng và các bản tương lai) | UC14 | BR-CN-02, 04 |
| 7 | `POST /branches/{branchId}/opening-hours/impact` | Xem trước lịch hẹn / đặt chỗ bị ảnh hưởng khi đổi giờ | UC14 | BR-CN-04, BR-LH-10 |
| 8 | `PUT /branches/{branchId}/opening-hours` | Đặt giờ mở cửa từ một ngày hiệu lực | UC14 | BR-CN-02, 04, BR-LH-10 |
| 9 | `GET /branches/{branchId}/holidays` | Danh sách ngày nghỉ | UC14 | BR-CN-03 |
| 10 | `POST /branches/{branchId}/holidays/impact` | Xem trước lịch hẹn / đặt chỗ bị ảnh hưởng của một ngày nghỉ | UC14 | BR-CN-03, BR-LH-10 |
| 11 | `POST /branches/{branchId}/holidays` | Thêm ngày nghỉ | UC14 | BR-CN-03, BR-LH-10, BR-LT-04 |
| 12 | `DELETE /branches/{branchId}/holidays/{holidayId}` | Xóa ngày nghỉ tương lai | UC14 | BR-CN-03 |
| 13 | `GET /branches/{branchId}/services` | Dịch vụ tại chi nhánh (kể cả loại chuồng) | UC33 | BR-LH-01, BR-SP-04 |
| 14 | `PUT /branches/{branchId}/services/{serviceId}` | Bật / tắt dịch vụ tại chi nhánh | UC33 | BR-LH-01, BR-SP-04 |
| 15 | `GET /branches/{branchId}/quota-defaults` | Quota mặc định theo nhóm dịch vụ | UC42 | BR-LH-03 |
| 16 | `PUT /branches/{branchId}/quota-defaults/{serviceGroup}` | Đặt quota mặc định | UC42 | BR-LH-03 |
| 17 | `GET /branches/{branchId}/slot-quotas` | Quota riêng của các khung | UC42 | BR-LH-03 |
| 18 | `PUT /branches/{branchId}/slot-quotas` | Đặt quota riêng cho một khung (0 = khóa khung) | UC42 | BR-LH-02, 03 |
| 19 | `DELETE /branches/{branchId}/slot-quotas/{slotQuotaId}` | Bỏ quota riêng, về quota mặc định | UC42 | BR-LH-03 |
| 20 | `GET /public/branches` | Chi nhánh công khai | UC15 | BR-CK-01, BR-CN-05 |
| 21 | `GET /public/branches/{branchId}` | Chi tiết chi nhánh công khai | UC15 | BR-CK-01 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Danh sách chi nhánh | A03–A08 | SUPER_MANAGER, ADMIN: tất cả · nhân viên chi nhánh: chi nhánh mình | — | — | — |
| Tạo chi nhánh | A04 | Chỉ SUPER_MANAGER | — → DRAFT (Chi nhánh#1) | — | — |
| Chi tiết chi nhánh | A03–A08 | Như listBranches | — | — | — |
| Sửa chi nhánh / bật cờ cấp cứu ngoài giờ | A04 | Chỉ SUPER_MANAGER | — | — | — |
| Kích hoạt chi nhánh | A04 | Chỉ SUPER_MANAGER | DRAFT → ACTIVE (Chi nhánh#2) | — | Từ đây hiển thị công khai và sinh khung giờ đặt lịch. |
| Giờ mở cửa (bản đang áp dụng và các bản tương lai) | A04–A08 | Nhân viên chi nhánh; SUPER_MANAGER | — | — | — |
| Xem trước lịch hẹn / đặt chỗ bị ảnh hưởng khi đổi giờ | A05 | BRANCH_MANAGER của chi nhánh | — | — | Không ghi gì (A1). |
| Đặt giờ mở cửa từ một ngày hiệu lực | A05 | BRANCH_MANAGER của chi nhánh | Nếu cancelAffected: phát BranchClinicCancellationEvent → Lịch hẹn#5, Đặt chỗ#5 | — | Ghi đè bản cùng ngày hiệu lực nếu đã có. |
| Danh sách ngày nghỉ | A04–A08 | Nhân viên chi nhánh; SUPER_MANAGER | — | — | — |
| Xem trước lịch hẹn / đặt chỗ bị ảnh hưởng của một ngày nghỉ | A05 | BRANCH_MANAGER của chi nhánh | — | — | Không ghi gì (A1). |
| Thêm ngày nghỉ | A05 | BRANCH_MANAGER của chi nhánh | Nếu cancelAffected: phát BranchClinicCancellationEvent → Lịch hẹn#5, Đặt chỗ#5 | — | Lịch bị hủy không tính hủy muộn, không tính lần đổi; khách được thông báo. |
| Xóa ngày nghỉ tương lai | A05 | BRANCH_MANAGER của chi nhánh | — | — | Không khôi phục lịch đã hủy (A3). |
| Dịch vụ tại chi nhánh (kể cả loại chuồng) | A04–A08 | Nhân viên chi nhánh; SUPER_MANAGER | — | — | — |
| Bật / tắt dịch vụ tại chi nhánh | A05 | BRANCH_MANAGER của chi nhánh | — | — | Tắt dịch vụ chỉ chặn đặt mới; lịch và đặt chỗ đã có giữ nguyên. |
| Quota mặc định theo nhóm dịch vụ | A05, A06 | Nhân viên chi nhánh | — | — | — |
| Đặt quota mặc định | A05 | BRANCH_MANAGER của chi nhánh | — | — | Giảm quota không ảnh hưởng lịch đã đặt, chỉ chặn đặt mới. |
| Quota riêng của các khung | A05, A06 | Nhân viên chi nhánh | — | — | — |
| Đặt quota riêng cho một khung (0 = khóa khung) | A05 | BRANCH_MANAGER của chi nhánh | — | — | Tạo mới hoặc ghi đè quota riêng của khung. |
| Bỏ quota riêng, về quota mặc định | A05 | BRANCH_MANAGER của chi nhánh | — | — | A4. |
| Chi nhánh công khai | A01, A02 | Public | — | — | — |
| Chi tiết chi nhánh công khai | A01, A02 | Public | — | — | Chi nhánh DRAFT trả 404. |

---

## C. Chi tiết endpoint

### Branches

- **`GET /branches`** — Danh sách chi nhánh. Query: `status?`. Response `200` mảng `Branch`. Lỗi: `401` · `403`.
- **`POST /branches`** — Tạo chi nhánh. Request `CreateBranchRequest`. Response `201` `Branch`. Lỗi: `400` `BR-CN-01` thiếu tên, địa chỉ, SĐT hoặc tọa độ · tên trùng · `401` · `403`.
- **`GET /branches/{branchId}`** — Chi tiết chi nhánh. Response `200` `Branch`. Lỗi: `401` · `403` · `404`.
- **`PATCH /branches/{branchId}`** — Sửa chi nhánh / bật cờ cấp cứu ngoài giờ. Request `UpdateBranchRequest`. Response `200` `Branch`. Lỗi: `400` · `401` · `403` · `404`.
- **`POST /branches/{branchId}/activate`** — Kích hoạt chi nhánh. Response `200` `Branch`. Lỗi: `400` `BR-QT-04` chưa có BRANCH_MANAGER · `BR-CN-01` chưa cấu hình giờ mở cửa (message ghi điều kiện còn thiếu) · `401` · `403` · `404` · `409` Chi nhánh đã ACTIVE.
### Opening hours

- **`GET /branches/{branchId}/opening-hours`** — Giờ mở cửa (bản đang áp dụng và các bản tương lai). Response `200` mảng `OpeningHoursVersion`. Lỗi: `401` · `403` · `404`.
- **`POST /branches/{branchId}/opening-hours/impact`** — Xem trước lịch hẹn / đặt chỗ bị ảnh hưởng khi đổi giờ. Request `OpeningHoursImpactRequest`. Response `200` `ScheduleImpact`. Lỗi: `400` · `401` · `403` · `404`.
- **`PUT /branches/{branchId}/opening-hours`** — Đặt giờ mở cửa từ một ngày hiệu lực. Request `SetOpeningHoursRequest`. Response `200` `OpeningHoursVersion`. Lỗi: `400` `BR-CN-02` khoảng chồng nhau, giờ mở ≥ giờ đóng, quá 2 khoảng · `BR-CN-04` ngày hiệu lực trước ngày mai, hoặc có lịch / đặt chỗ bị ảnh hưởng mà chưa chọn hủy hàng loạt · `401` · `403` · `404`.
- **`GET /branches/{branchId}/holidays`** — Danh sách ngày nghỉ. Query: `from?`, `to?`. Response `200` mảng `Holiday`. Lỗi: `401` · `403` · `404`.
- **`POST /branches/{branchId}/holidays/impact`** — Xem trước lịch hẹn / đặt chỗ bị ảnh hưởng của một ngày nghỉ. Request `HolidayImpactRequest`. Response `200` `ScheduleImpact`. Lỗi: `400` · `401` · `403` · `404`.
- **`POST /branches/{branchId}/holidays`** — Thêm ngày nghỉ. Request `CreateHolidayRequest`. Response `201` `Holiday`. Lỗi: `400` `BR-LH-10` ngày còn lịch BOOKED / nhận-trả lưu trú BOOKED mà chưa chọn hủy hàng loạt · ngày đã là ngày nghỉ · ngày trong quá khứ · `401` · `403` · `404`.
- **`DELETE /branches/{branchId}/holidays/{holidayId}`** — Xóa ngày nghỉ tương lai. Response `204` rỗng. Lỗi: `400` Ngày nghỉ đã qua hoặc là hôm nay · `401` · `403` · `404`.
### Branch services

- **`GET /branches/{branchId}/services`** — Dịch vụ tại chi nhánh (kể cả loại chuồng). Response `200` mảng `BranchServiceItem`. Lỗi: `401` · `403` · `404`.
- **`PUT /branches/{branchId}/services/{serviceId}`** — Bật / tắt dịch vụ tại chi nhánh. Request `SetBranchServiceRequest`. Response `200` `BranchServiceItem`. Lỗi: `400` Dịch vụ đã ngừng kinh doanh · `401` · `403` · `404`.
### Quotas

- **`GET /branches/{branchId}/quota-defaults`** — Quota mặc định theo nhóm dịch vụ. Response `200` mảng `QuotaDefault`. Lỗi: `401` · `403` · `404`.
- **`PUT /branches/{branchId}/quota-defaults/{serviceGroup}`** — Đặt quota mặc định. Request `SetQuotaDefaultRequest`. Response `200` `QuotaDefault`. Lỗi: `400` · `401` · `403` · `404`.
- **`GET /branches/{branchId}/slot-quotas`** — Quota riêng của các khung. Query: `from`, `to`, `serviceGroup?`. Response `200` mảng `SlotQuota`. Lỗi: `400` · `401` · `403` · `404`.
- **`PUT /branches/{branchId}/slot-quotas`** — Đặt quota riêng cho một khung (0 = khóa khung). Request `SetSlotQuotaRequest`. Response `200` `SlotQuota`. Lỗi: `400` `BR-LH-02` khung không nằm trong giờ mở cửa ngày đó · quota < 0 · `401` · `403` · `404`.
- **`DELETE /branches/{branchId}/slot-quotas/{slotQuotaId}`** — Bỏ quota riêng, về quota mặc định. Response `204` rỗng. Lỗi: `401` · `403` · `404`.
### Public

- **`GET /public/branches`** — Chi nhánh công khai. Public. Query: `acceptsAfterHoursEmergency?`. Response `200` mảng `PublicBranch`. Lỗi: `400`.
- **`GET /public/branches/{branchId}`** — Chi tiết chi nhánh công khai. Public. Response `200` `PublicBranch`. Lỗi: `404`.

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`BranchStatus`** — enum: `DRAFT`, `ACTIVE`. 03 #2
- **`Branch`** — {`branchId`: int64, `name`: string, `address`: string, `phone`: string, `latitude`: number, `longitude`: number, `status`: BranchStatus, `acceptsAfterHoursEmergency`: boolean, `activatedAt?`: date-time}
- **`CreateBranchRequest`** — {`name`: string, `address`: string, `phone`: string, `latitude`: number, `longitude`: number, `acceptsAfterHoursEmergency?`: boolean}
- **`UpdateBranchRequest`** — {`name?`: string, `address?`: string, `phone?`: string, `latitude?`: number, `longitude?`: number, `acceptsAfterHoursEmergency?`: boolean}
- **`TimeRange`** — {`open`: string, `close`: string}
- **`DayHours`** — {`dayOfWeek`: int32, `ranges`: [TimeRange]}
- **`OpeningHoursVersion`** — {`effectiveFrom`: date, `days`: [DayHours]}
- **`SetOpeningHoursRequest`** — {`effectiveFrom`: date, `days`: [DayHours], `cancelAffected?`: boolean}
- **`OpeningHoursImpactRequest`** — {`effectiveFrom`: date, `days`: [DayHours]}
- **`AffectedAppointment`** — {`appointmentId`: int64, `code`: string, `slotDate`: date, `slotStart`: string, `petName`: string, `customerName`: string, `customerPhone?`: string}
- **`AffectedBoarding`** — {`bookingId`: int64, `code`: string, `checkInDate`: date, `checkOutDate`: date, `petName`: string, `customerName`: string, `customerPhone?`: string}
- **`ScheduleImpact`** — {`appointments`: [AffectedAppointment], `boardingBookings`: [AffectedBoarding]} — Danh sách hiển thị trước khi quyết định; khách không có email để lễ tân gọi điện (06 Q4)
- **`Holiday`** — {`holidayId`: int64, `holidayDate`: date, `reason?`: string}
- **`HolidayImpactRequest`** — {`holidayDate`: date}
- **`CreateHolidayRequest`** — {`holidayDate`: date, `reason?`: string, `cancelAffected?`: boolean}
- **`BranchServiceItem`** — {`serviceId`: int64, `name`: string, `group`: MEDICAL|GROOMING|BOARDING, `enabled`: boolean}
- **`SetBranchServiceRequest`** — {`enabled`: boolean}
- **`QuotaDefault`** — {`serviceGroup`: MEDICAL|GROOMING, `defaultQuota?`: int32}
- **`SetQuotaDefaultRequest`** — {`defaultQuota`: int32}
- **`SlotQuota`** — {`slotQuotaId`: int64, `serviceGroup`: MEDICAL|GROOMING, `slotDate`: date, `slotStart`: string, `quota`: int32}
- **`SetSlotQuotaRequest`** — {`serviceGroup`: MEDICAL|GROOMING, `slotDate`: date, `slotStart`: string, `quota`: int32}
- **`PublicBranch`** — {`branchId`: int64, `name`: string, `address`: string, `phone`: string, `latitude`: number, `longitude`: number, `acceptsAfterHoursEmergency`: boolean, `weeklyHours`: [DayHours]}

---

## D. Bảo mật & độ tin cậy

1. Tạo, sửa, kích hoạt chi nhánh và cờ cấp cứu ngoài giờ: chỉ SUPER_MANAGER (BR-CN-05).
2. Giờ mở cửa, ngày nghỉ, dịch vụ tại chi nhánh, quota: chỉ BRANCH_MANAGER của chính chi nhánh đó.
3. Thêm ngày nghỉ / đổi giờ có ảnh hưởng mà chưa chọn hủy hàng loạt thì từ chối (BR-CN-04, BR-LH-10). Kiểm tra ảnh hưởng và lưu chạy trong cùng transaction để không lọt lịch đặt chen giữa hai bước.
4. API public chỉ trả chi nhánh ACTIVE (BR-CK-01).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Xem trước ảnh hưởng là endpoint riêng (`…/impact`) không ghi gì; lưu thật mới kiểm tra lại | BR-CN-04 / BR-LH-10 yêu cầu hiển thị danh sách bị ảnh hưởng trước khi quản lý quyết định |
| A2 | Chi nhánh DRAFT được đặt giờ mở cửa hiệu lực từ hôm nay; ràng buộc 'sớm nhất là ngày mai' áp dụng khi đổi giờ của chi nhánh đã có giờ | BR-CN-04 nói về thay đổi; chi nhánh mới cần giờ để kích hoạt (BR-CN-01) |
| A3 | Được xóa ngày nghỉ trong tương lai; lịch đã bị hủy hàng loạt không khôi phục | UC14 'cấu hình ngày nghỉ'; docs không có rule khôi phục |
| A4 | Xóa quota riêng của một khung để quay về quota mặc định | BR-LH-03: quota riêng là ngoại lệ của quota mặc định |
| A5 | PUT giờ mở cửa gửi đủ 7 ngày của một bản hiệu lực; ngày không gửi khoảng nào là nghỉ cố định | 05 `opening_hours`: mỗi dòng một thứ trong tuần theo ngày hiệu lực |

Không có câu hỏi mở.

---

## F. OpenAPI 3.1

[`./openapi/branch-v1.yaml`](./openapi/branch-v1.yaml)
