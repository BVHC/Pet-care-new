# Customer & Pet API v1 — Pet Care v16

> **Module:** KH (và phần hồ sơ khách của UC06) · **Owner:** BE-2. Hồ sơ khách (online và tại quầy), sổ địa chỉ, tra cứu, thú cưng, cân nặng, đánh dấu đã mất, chuyển chủ.
> **Nguồn chân lý:** 01 UC06, UC22–UC26; 02 BR-KH-01…10, BR-TK-15, 18, 19; 04 §3; 05 §3 (customers, addresses, pets, weight_records).
> **Contract máy đọc:** [`./openapi/customer-v1.yaml`](./openapi/customer-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- UC27 gộp hồ sơ khách trùng là tầng 3, không có endpoint.
- Hồ sơ sức khỏe (chẩn đoán, đơn thuốc, mũi tiêm) thuộc module visit: `GET /pets/{petId}/health-record`. File này chỉ có lịch sử cân nặng.
- Sửa email tài khoản khách và liên kết hồ sơ tại quầy thuộc module identity (`/customers/{customerId}/account-email-change`, `/customers/{customerId}/link-account`).
- Cân nặng do VET ghi khi khám và do người nhận thú ghi khi nhận lưu trú đi qua module visit / boarding (gọi `PetApi.recordWeight`); ở đây chỉ có cân nặng chủ tự khai.
- ST19 (tự hủy Care Task khi thú mất) chạy trong cùng transaction đánh dấu đã mất qua `PetDeceasedEvent`, không có endpoint riêng.

---

## A. Danh sách endpoint (21)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `GET /me/customer-profile` | Hồ sơ khách của tôi | UC06 | BR-KH-01, BR-TK-15 |
| 2 | `PATCH /me/customer-profile` | Sửa hồ sơ khách của tôi | UC06 | BR-TK-15, BR-KH-01 |
| 3 | `GET /me/addresses` | Sổ địa chỉ | UC06 | BR-TK-18 |
| 4 | `POST /me/addresses` | Thêm địa chỉ | UC06 | BR-TK-18 |
| 5 | `PATCH /me/addresses/{addressId}` | Sửa địa chỉ | UC06 | BR-TK-18 |
| 6 | `DELETE /me/addresses/{addressId}` | Xóa địa chỉ | UC06 | BR-TK-18 |
| 7 | `POST /me/addresses/{addressId}/set-default` | Đặt địa chỉ mặc định | UC06 | BR-TK-18 |
| 8 | `GET /customers` | Tra cứu khách & thú cưng | UC23 | BR-KH-01, 10 |
| 9 | `POST /customers` | Tạo hồ sơ khách tại quầy | UC22, UC44 | BR-KH-01, 10 |
| 10 | `GET /customers/{customerId}` | Chi tiết hồ sơ khách kèm thú cưng | UC22, UC23 | BR-KH-01 |
| 11 | `PATCH /customers/{customerId}` | Sửa hồ sơ khách | UC22 | BR-KH-01, 10, BR-TK-16 |
| 12 | `POST /customers/{customerId}/pets` | Lễ tân thêm thú cưng cho khách | UC24 | BR-KH-02 |
| 13 | `GET /me/pets` | Thú cưng của tôi | UC24 | BR-KH-05, BR-TK-19 |
| 14 | `POST /me/pets` | Thêm thú cưng | UC24 | BR-KH-02, BR-TK-19 |
| 15 | `GET /pets/{petId}` | Chi tiết thú cưng | UC24 | BR-KH-03, 06, 08 |
| 16 | `PATCH /pets/{petId}` | Sửa thông tin thú cưng | UC24 | BR-KH-02, 03, 05 |
| 17 | `POST /pets/{petId}/mark-deceased` | Đánh dấu thú đã mất | UC24, ST19 | BR-KH-05, BR-LT-12, BR-TB-03 |
| 18 | `DELETE /pets/{petId}` | Xóa thú cưng chưa phát sinh giao dịch | UC24 | BR-KH-06 |
| 19 | `POST /pets/{petId}/transfer-owner` | Chuyển chủ thú cưng | UC26 | BR-KH-08 |
| 20 | `GET /pets/{petId}/weights` | Lịch sử cân nặng | UC24, UC25 | BR-KH-04, 07 |
| 21 | `POST /pets/{petId}/weights` | Chủ tự khai cân nặng | UC24 | BR-KH-04 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Hồ sơ khách của tôi | A02 | Khách đang đăng nhập | — | — | — |
| Sửa hồ sơ khách của tôi | A02 | Khách đang đăng nhập | — | — | Không trả cảnh báo nghi trùng cho khách (A1). |
| Sổ địa chỉ | A02 | Khách đang đăng nhập | — | — | — |
| Thêm địa chỉ | A02 | Khách đang đăng nhập | — | — | — |
| Sửa địa chỉ | A02 | Chủ địa chỉ | — | — | — |
| Xóa địa chỉ | A02 | Chủ địa chỉ | — | — | — |
| Đặt địa chỉ mặc định | A02 | Chủ địa chỉ | — | — | Bỏ cờ mặc định của địa chỉ cũ trong cùng transaction. |
| Tra cứu khách & thú cưng | A06, A07, A08 | Nhân viên mọi chi nhánh | — | — | Cần ít nhất một điều kiện tìm. |
| Tạo hồ sơ khách tại quầy | A06 | Lễ tân | — | — | SĐT trùng hồ sơ khác: vẫn tạo, trả danh sách nghi trùng (BR-KH-10). |
| Chi tiết hồ sơ khách kèm thú cưng | A06, A07, A08 | Nhân viên | — | — | — |
| Sửa hồ sơ khách | A06 | Lễ tân | — | — | — |
| Lễ tân thêm thú cưng cho khách | A06 | Lễ tân | — | — | — |
| Thú cưng của tôi | A02 | Khách đang đăng nhập | — | — | — |
| Thêm thú cưng | A02 | Khách đang đăng nhập | — | — | — |
| Chi tiết thú cưng | A02, A06, A07, A08 | Chủ hiện tại; nhân viên mọi chi nhánh | — | — | — |
| Sửa thông tin thú cưng | A02, A06 | Khách là chủ hiện tại · A06 | — | — | — |
| Đánh dấu thú đã mất | A02, A06 | Khách là chủ hiện tại · A06 | Phát PetDeceasedEvent → Lịch hẹn#5, Đặt chỗ#5, Care Task#4 | — | — |
| Xóa thú cưng chưa phát sinh giao dịch | A02, A06 | Khách là chủ hiện tại · A06 | — | — | — |
| Chuyển chủ thú cưng | A06 | Lễ tân | Phát PetOwnerTransferredEvent → Lịch hẹn#5, Đặt chỗ#5; Care Task OPEN sang chủ mới | — | Order cũ giữ chủ cũ; ghi audit. |
| Lịch sử cân nặng | A02, A06, A07, A08 | Chủ hiện tại; nhân viên | — | — | Mới nhất trước. |
| Chủ tự khai cân nặng | A02 | Khách là chủ hiện tại | — | — | source = OWNER (đánh dấu chủ tự khai). |

---

## C. Chi tiết endpoint

### My profile

- **`GET /me/customer-profile`** — Hồ sơ khách của tôi. Response `200` `CustomerProfile`. Lỗi: `401` · `403`.
- **`PATCH /me/customer-profile`** — Sửa hồ sơ khách của tôi. Request `UpdateMyCustomerProfileRequest`. Response `200` `CustomerProfile`. Lỗi: `400` `BR-TK-15` cố sửa email · dữ liệu không hợp lệ · `401` · `403`.
- **`GET /me/addresses`** — Sổ địa chỉ. Response `200` mảng `Address`. Lỗi: `401` · `403`.
- **`POST /me/addresses`** — Thêm địa chỉ. Request `AddressRequest`. Response `201` `Address`. Lỗi: `400` `BR-TK-18` đã đủ 5 địa chỉ [CFG] · `401` · `403`.
- **`PATCH /me/addresses/{addressId}`** — Sửa địa chỉ. Request `UpdateAddressRequest`. Response `200` `Address`. Lỗi: `400` · `401` · `403` · `404`.
- **`DELETE /me/addresses/{addressId}`** — Xóa địa chỉ. Response `204` rỗng. Lỗi: `400` `BR-TK-18` không xóa địa chỉ mặc định khi còn địa chỉ khác · `401` · `403` · `404`.
- **`POST /me/addresses/{addressId}/set-default`** — Đặt địa chỉ mặc định. Response `200` `Address`. Lỗi: `401` · `403` · `404`.
### Customers

- **`GET /customers`** — Tra cứu khách & thú cưng. Query: `phone?`, `name?`, `email?`, `petName?`, `page?`, `size?`. Response `200` trang `CustomerSearchItem`. Lỗi: `400` Không có điều kiện tìm nào · `401` · `403`.
- **`POST /customers`** — Tạo hồ sơ khách tại quầy. Request `CreateCounterCustomerRequest`. Response `201` `CustomerWriteResult`. Lỗi: `400` · `401` · `403`.
- **`GET /customers/{customerId}`** — Chi tiết hồ sơ khách kèm thú cưng. Response `200` `CustomerDetail`. Lỗi: `401` · `403` · `404`.
- **`PATCH /customers/{customerId}`** — Sửa hồ sơ khách. Request `UpdateCustomerRequest`. Response `200` `CustomerWriteResult`. Lỗi: `400` Sửa email khi hồ sơ đã gắn tài khoản (dùng identity, A5) · thiếu SĐT với hồ sơ tại quầy (BR-KH-01) · `401` · `403` · `404`.
### Pets

- **`POST /customers/{customerId}/pets`** — Lễ tân thêm thú cưng cho khách. Request `CreatePetRequest`. Response `201` `PetWriteResult`. Lỗi: `400` · `401` · `403` · `404`.
- **`GET /me/pets`** — Thú cưng của tôi. Response `200` mảng `PetResponse`. Lỗi: `400` `BR-TK-19` hồ sơ còn chờ quyết định liên kết · `401` · `403`.
- **`POST /me/pets`** — Thêm thú cưng. Request `CreatePetRequest`. Response `201` `PetWriteResult`. Lỗi: `400` Ngày sinh ở tương lai · thiếu trường bắt buộc (BR-KH-02) · `BR-TK-19` hồ sơ còn chờ quyết định liên kết · `401` · `403`.
- **`GET /pets/{petId}`** — Chi tiết thú cưng. Response `200` `PetResponse`. Lỗi: `401` · `403` · `404`.
- **`PATCH /pets/{petId}`** — Sửa thông tin thú cưng. Request `UpdatePetRequest`. Response `200` `PetWriteResult`. Lỗi: `400` `BR-KH-03` đổi loài khi đã có bệnh án / mũi tiêm · `BR-KH-05` thú đã mất (chỉ đọc) · `401` · `403` · `404`.
- **`POST /pets/{petId}/mark-deceased`** — Đánh dấu thú đã mất. Request `MarkDeceasedRequest`. Response `200` `PetResponse`. Lỗi: `400` `BR-KH-05` thú có Visit mở hoặc đang lưu trú (kết thúc lưu trú trước theo BR-LT-12) · chưa xác nhận · `401` · `403` · `404` · `409` Thú đã được đánh dấu đã mất (không hoàn tác).
- **`DELETE /pets/{petId}`** — Xóa thú cưng chưa phát sinh giao dịch. Response `204` rỗng. Lỗi: `400` `BR-KH-06` đã có lịch hẹn, đặt chỗ, Visit hoặc Order — dùng đánh dấu đã mất · `401` · `403` · `404`.
- **`POST /pets/{petId}/transfer-owner`** — Chuyển chủ thú cưng. Request `TransferOwnerRequest`. Response `200` `PetResponse`. Lỗi: `400` `BR-KH-08` thú có Visit mở, Order PENDING hoặc đang lưu trú · chưa xác nhận chủ cũ · `BR-KH-05` thú đã mất · `401` · `403` · `404`.
### Weights

- **`GET /pets/{petId}/weights`** — Lịch sử cân nặng. Response `200` mảng `WeightRecord`. Lỗi: `401` · `403` · `404`.
- **`POST /pets/{petId}/weights`** — Chủ tự khai cân nặng. Request `AddWeightRequest`. Response `201` `WeightRecord`. Lỗi: `400` `BR-KH-04` cân nặng ≤ 0 · `BR-KH-05` thú đã mất · `401` · `403` · `404`.

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`Species`** — enum: `DOG`, `CAT`, `OTHER`. Chó, Mèo, Khác (BR-KH-02)
- **`CustomerProfile`** — {`customerId`: int64, `fullName`: string, `phone?`: string, `email?`: email, `avatarUrl?`: string, `createdChannel`: ONLINE|COUNTER, `hasAccount`: boolean, `linkDecisionPending`: boolean}
- **`UpdateMyCustomerProfileRequest`** — {`fullName?`: string, `phone?`: string, `avatarUrl?`: string} — Email không tự sửa được (BR-TK-15)
- **`Address`** — {`addressId`: int64, `receiverName`: string, `receiverPhone`: string, `addressLine`: string, `ward?`: string, `province`: string, `isDefault`: boolean}
- **`AddressRequest`** — {`receiverName`: string, `receiverPhone`: string, `addressLine`: string, `ward?`: string, `province`: string, `isDefault?`: boolean}
- **`UpdateAddressRequest`** — {`receiverName?`: string, `receiverPhone?`: string, `addressLine?`: string, `ward?`: string, `province?`: string}
- **`PetBrief`** — {`petId`: int64, `name`: string, `species`: Species, `deceased`: boolean}
- **`CustomerSearchItem`** — {`customerId`: int64, `fullName`: string, `phone?`: string, `maskedEmail?`: string, `createdChannel`: ONLINE|COUNTER, `hasAccount`: boolean, `pets`: [PetBrief]} — Một SĐT có thể ra nhiều hồ sơ; nhân viên chọn theo họ tên, email che và thú cưng (BR-KH-10)
- **`CreateCounterCustomerRequest`** — {`fullName`: string, `phone`: string, `email?`: email}
- **`UpdateCustomerRequest`** — {`fullName?`: string, `phone?`: string, `email?`: email}
- **`CustomerWriteResult`** — {`customer`: CustomerProfile, `duplicatePhoneProfiles`: [CustomerSearchItem]}
- **`CustomerDetail`** — {`customer`: CustomerProfile, `pets`: [PetResponse]}
- **`PetResponse`** — {`petId`: int64, `customerId`: int64, `name`: string, `species`: Species, `sex`: MALE|FEMALE|UNKNOWN, `breed?`: string, `birthDate?`: date, `birthDateEstimated`: boolean, `color?`: string, `isNeutered?`: boolean, `photoUrl?`: string, `deceasedOn?`: date, `speciesLocked`: boolean, `currentWeightKg?`: number, `deletable`: boolean}
- **`CreatePetRequest`** — {`name`: string, `species`: Species, `sex`: MALE|FEMALE|UNKNOWN, `breed?`: string, `birthDate?`: date, `birthDateEstimated?`: boolean, `color?`: string, `isNeutered?`: boolean, `photoUrl?`: string}
- **`UpdatePetRequest`** — {`name?`: string, `species?`: Species, `sex?`: MALE|FEMALE|UNKNOWN, `breed?`: string, `birthDate?`: date, `birthDateEstimated?`: boolean, `color?`: string, `isNeutered?`: boolean, `photoUrl?`: string}
- **`PetWriteResult`** — {`pet`: PetResponse, `duplicateNameWarning`: boolean}
- **`MarkDeceasedRequest`** — {`deceasedOn`: date, `confirmed`: boolean}
- **`TransferOwnerRequest`** — {`newCustomerId`: int64, `previousOwnerConfirmed`: boolean}
- **`WeightRecord`** — {`weightRecordId`: int64, `weightKg`: number, `source`: VET|INTAKE|OWNER, `visitId?`: int64, `boardingBookingId?`: int64, `measuredAt`: date-time}
- **`AddWeightRequest`** — {`weightKg`: number, `measuredAt?`: date-time}

---

## D. Bảo mật & độ tin cậy

1. Khách chỉ thao tác hồ sơ và thú cưng của chính mình (chủ hiện tại). Sau khi chuyển chủ, chủ cũ nhận 404 với thú đó (BR-KH-08).
2. Hồ sơ online còn cờ `linkDecisionPending`: mọi thao tác thú cưng của khách trả 400 `BUSINESS_RULE_VIOLATION`, message kèm `BR-TK-19` (BR-TK-19 ẩn quản lý thú cưng).
3. Thú đã mất là chỉ đọc: mọi thao tác ghi trả 400 `BR-KH-05` (A2).
4. Tra cứu (UC23) trả email đã che; nhân viên mọi chi nhánh đều tra được vì hồ sơ dùng chung toàn chuỗi (BR-KH-01, 04 nguyên tắc 8).
5. Đánh dấu đã mất và chuyển chủ phát sự kiện đồng bộ; hủy lịch hẹn / đặt chỗ / Care Task chạy trong cùng transaction (06-module-contracts §3). Chuyển chủ ghi audit.

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Khách tự sửa SĐT không nhận cảnh báo nghi trùng; cảnh báo BR-KH-10 chỉ trả cho nhân viên | Trả danh sách hồ sơ khác cho khách làm lộ dữ liệu người khác |
| A2 | Thao tác ghi trên thú đã mất trả 400 `BR-KH-05` | BR-KH-05: hồ sơ giữ ở dạng chỉ đọc |
| A3 | Xác nhận 2 bước khi đánh dấu đã mất làm ở giao diện; API yêu cầu `confirmed = true` | BR-KH-05 'xác nhận 2 bước' |
| A4 | Chuyển chủ yêu cầu `previousOwnerConfirmed = true` do lễ tân tích | BR-KH-08 'khi chủ cũ có mặt hoặc đã xác nhận' — hệ thống không có kênh xác nhận riêng |
| A5 | Email hồ sơ chỉ sửa trực tiếp được khi hồ sơ chưa gắn tài khoản; hồ sơ có tài khoản thì email theo tài khoản (sửa ở identity) | BR-TK-16, BR-TK-19: email hồ sơ cập nhật theo email tài khoản |

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Ảnh đại diện / ảnh thú cưng tải lên bằng cơ chế nào (upload trực tiếp, presigned URL)? Contract chỉ nhận URL. | TBD (BE + FE) | 05 chỉ có cột `*_url` |

---

## F. OpenAPI 3.1

[`./openapi/customer-v1.yaml`](./openapi/customer-v1.yaml)
