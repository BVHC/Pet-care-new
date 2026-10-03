# Phương pháp & quy ước chung của API contract

> Áp dụng cho mọi file trong `docs/api/`. Contract là hợp đồng **FE ↔ BE qua HTTP**. Hợp đồng **BE ↔ BE** (gọi chéo giữa module trong cùng ứng dụng) nằm ở [`../06-module-contracts.md`](../06-module-contracts.md).
> Nguồn chân lý duy nhất: `docs/01`–`05` + `INDEX` bản **v16**. Không suy từ code hay tài liệu cũ.

## 1. Nguyên tắc thiết kế

1. **Đi từ nghiệp vụ, không từ bảng.** Thứ tự suy luận: use case (`01`) → rule (`02`) → chuyển trạng thái (`03`) → model (`04`) → bảng (`05`) → endpoint. Bảng DB không tự thành resource, cột không tự thành field.
2. **Mỗi endpoint có use case đứng sau.** `description` của mỗi operation ghi mã UC (hoặc ST) và mã rule. Không có use case thì không có endpoint.
3. **Mỗi chuyển trạng thái do người kích hoạt có đúng một endpoint**; chuyển do hệ thống (`ST…`, `SYS ← …`) không có endpoint.
4. **Không phát minh hành vi.** Thiếu thông tin thì chọn phương án ít phát minh nhất, ghi `ASSUMPTION (A#)` kèm lý do; không chọn được thì ghi `TBD (Q#)`. Đánh số A#, Q# riêng trong từng file.
5. **YAGNI.** Chỉ có bộ lọc, phân trang, field mà use case cần. Tầng 3 không có endpoint.

## 2. Cách làm và cập nhật

Mỗi module có **một khai báo duy nhất** `generator/m_<module>.py`. Từ khai báo đó sinh ra cả `<module>-v1.md` (người đọc) và `openapi/<module>-v1.yaml` (máy đọc), nên hai file luôn khớp nhau. **Không sửa tay file sinh ra.**

```bash
# 1. sửa docs/api/generator/m_<module>.py
python docs/api/generator/generate.py            # sinh lại mọi module (hoặc: generate.py visit sales)
node docs/api/check-contracts.mjs                # V1 + V4, phải ra ALL CONTRACTS PASS
npx --yes @redocly/cli@1 lint docs/api/openapi/*.yaml   # kiểm tra chuẩn OpenAPI 3.1 đầy đủ (khuyến nghị)
```

## 3. Quy ước chung (áp cho mọi file)

### 3.1 Địa chỉ và xác thực
- `servers: /api`. Path trong yaml **không** có tiền tố `/api`; gọi thật là `/api/<path>`. Phiên bản nằm ở tên file (`-v1`), không nằm trong path.
- Bearer token trả về từ `POST /auth/login`, gắn 1-1 với một phiên (`sessions`). Phiên bị hủy (khóa, vô hiệu hóa, đổi / đặt lại mật khẩu) thì token hết hiệu lực ngay ở request kế tiếp.
- Không cần đăng nhập: `/auth/register…`, `/auth/login`, `/auth/password/…` và mọi `/public/…`.

### 3.2 Đặt tên đường dẫn
- Resource số nhiều, kebab-case: `/boarding-bookings`, `/stock-receipts`.
- **Chuyển trạng thái = `POST /<resource>/{id}/<hành-động>`**, ví dụ `POST /visits/{visitId}/call` (Visit#3). Không đổi trạng thái qua PATCH.
- `/me/…`: dữ liệu của chính người đang đăng nhập. `/public/…`: trang công khai.
- Tên tham số path là `<resource>Id` (`visitId`, `orderId`…), riêng identity dùng `id` cho nhân viên / tài khoản.
- Thao tác xem trước không ghi gì đặt tên `…/impact`, `…/preview`, `…/readiness`.

### 3.3 Phạm vi chi nhánh
Nhân viên A05–A08 chỉ làm việc trên chi nhánh của mình (04 nguyên tắc 8). Endpoint của dữ liệu theo chi nhánh **tự lấy chi nhánh của người gọi**; tham số `branchId` (nếu có) chỉ có tác dụng với SUPER_MANAGER / ADMIN. Truyền chi nhánh khác trả 403. Customer, Pet, danh mục, nhà cung cấp dùng chung toàn chuỗi.

### 3.4 Response thành công
Khớp `platform/model/ApiResponse`, `PageResponse`:
```json
{ "data": { ... }, "message": "success", "code": 200 }
```
- `code` là HTTP status dạng số (200, 201).
- Danh sách có phân trang: `data = { content: [...], page, size, totalElements, totalPages }`. Query `page` bắt đầu từ 0, `size` mặc định 20, tối đa 100.
- Danh sách nhỏ, cố định (dịch vụ, chuồng, hàng đợi trong ngày…) trả mảng trong `data`, không phân trang.
- `201` cho tạo mới, `202` cho yêu cầu đã nhận nhưng không tiết lộ kết quả (quên mật khẩu), `204` không có body.

### 3.5 Lỗi
Khớp `platform/model/ErrorResponse` và `platform/exception/ErrorCode`. Mọi lỗi dùng một envelope, đủ 6 trường:
```json
{ "success": false, "errorCode": "BUSINESS_RULE_VIOLATION", "message": "Thú cưng đã có 2 lịch hẹn đang chờ (BR-LH-05)",
  "statusCode": 400, "timestamp": "…", "traceId": "…" }
```

| HTTP | `errorCode` | Khi nào |
|---|---|---|
| 400 | `VALIDATION_FAILED`, `MALFORMED_REQUEST` | Sai định dạng, thiếu trường, sai kiểu |
| 400 | `BUSINESS_RULE_VIOLATION` | Vi phạm business rule. `message` tiếng Việt hiển thị được, **kết thúc bằng mã rule** `(BR-…)`; FE cần phân biệt rule (ví dụ `BR-TK-08` để chuyển sang màn OTP) thì đọc mã ở cuối `message` |
| 401 | `UNAUTHENTICATED` | Chưa đăng nhập, token sai / hết hạn / phiên bị hủy |
| 403 | `ACCESS_DENIED` | Sai vai trò (`@PreAuthorize`) |
| 403 | `ACCESS_DENIED_SCOPE_MISMATCH` | Ngoài phạm vi: chi nhánh khác, không phải chủ, rule ghi rõ "từ chối (403)" (BR-QT-01, BR-QT-07, BR-BH-02, BR-BC-01). Message chung, không lộ cấu trúc phân quyền |
| 404 | `RESOURCE_NOT_FOUND` | Không có, hoặc người gọi không được biết là có (thú của chủ khác, bài viết ẩn) |
| 409 | `INVALID_STATE_TRANSITION` | Trạng thái hiện tại không cho phép (không có trong bảng chuyển trạng thái của 03) |
| 409 | `CONCURRENCY_CONFLICT` | Cập nhật đồng thời, vi phạm ràng buộc DB do tranh chấp |

**Không dùng 422.** Rule mà FE cần phân biệt (chưa xác thực, khóa tạm, bắt đổi mật khẩu, chờ liên kết hồ sơ…) trả 400 `BUSINESS_RULE_VIOLATION`, không trả 403, vì 403 chỉ có message chung. Thứ tự kiểm tra: quyền → tồn tại → guard nghiệp vụ (400) → chuyển trạng thái hợp lệ (409). Trong từng file contract, mục lỗi ghi `` `400` `BR-…` …`` nghĩa là `BUSINESS_RULE_VIOLATION` với mã rule đó trong `message`.

### 3.6 Kiểu dữ liệu (theo `05` §0)

| Dữ liệu | Kiểu JSON | Ví dụ |
|---|---|---|
| id | integer int64 | `1024` |
| Mã chứng từ hiển thị | string | `"LH-260115-0007"` |
| Tiền | integer int64, đơn vị đồng | `150000` |
| Ngày nghiệp vụ | string `date`, theo giờ Việt Nam | `"2026-10-05"` |
| Giờ trong ngày | string `HH:mm` | `"08:30"` |
| Thời điểm | string `date-time` ISO 8601 có offset | `"2026-10-05T08:30:00+07:00"` |
| Cân nặng | number, kg | `4.25` |
| SĐT | string `0xxxxxxxxx` | `"0901234567"` |
| Trạng thái, loại | string enum **mã ASCII** theo `05` §0 | `VACCINE_DUE` (không dùng `TÁI_CHỦNG`) |

Field JSON đặt camelCase. Trường không bắt buộc có thể vắng hoặc `null`.

### 3.7 Tệp, ảnh
Contract chỉ nhận / trả URL (`avatarUrl`, `photoUrl`, `photoUrls`…). Cơ chế tải lên là TBD chung (Q ở customer, catalog, boarding).

### 3.8 Điểm PROPOSED, chờ ADR
`docs/convention/backend` còn để TBD hai điểm dưới đây và yêu cầu có ADR trước khi cài. Contract đang dùng phương án đề xuất; viết ADR rồi sửa contract nếu nhóm chọn khác.

| Điểm | Phương án contract đang dùng | Chỗ dùng |
|---|---|---|
| Trả cảnh báo "cảnh báo, không chặn" về client (convention 06) | Trường riêng trong `data` của response thành công, đặt tên theo nghiệp vụ | `stockWarning`, `duplicateNameWarning`, `duplicatePhoneProfiles`, `vaccineWarnings`, `warnings`, `assigneeOffline`, `shortageBookings`, `underMinAge` |
| Cách khóa khi tranh chấp quota, sức chứa chuồng, tồn kho (convention 07 §7.3) | Mục D của các file chỉ yêu cầu "kiểm và ghi trong cùng transaction có khóa", không chốt cách khóa | appointment, boarding, sales, visit |

## 4. Checklist kiểm chứng (chạy sau mỗi lần sửa)

### V1 — File hoạt động được (máy kiểm, `check-contracts.mjs`)
- [ ] Parse được; `servers` là `/api`; path không có `/api`.
- [ ] Mọi `$ref` resolve; `operationId` duy nhất; mỗi operation có response 2xx; endpoint cần đăng nhập có 401.
- [ ] Mọi path param được khai báo; không có 422.
- [ ] Mỗi operation có mã UC / ST trong `description`.
- [ ] Bảng A của file `.md` khớp đúng các endpoint trong yaml.
- [ ] Không trùng `METHOD path` giữa các file.

### V2 — Đúng với tài liệu (người kiểm)
- [ ] Mã rule trích dẫn tồn tại trong `02` và đúng số; mã đã bỏ (`~~…~~`) không được dùng.
- [ ] Chuyển trạng thái khớp **đúng** bảng của `03` — không thêm cạnh. Mỗi chuyển do người kích hoạt có endpoint; chuyển `ST…` / `SYS ←` không có.
- [ ] Enum khớp `05` (mã ASCII); trường bắt buộc khớp `NN` của bảng.
- [ ] Không có endpoint cho use case tầng 3 (INDEX Bảng 2).

### V3 — Không thừa
- [ ] Mỗi endpoint có use case; không CRUD theo bảng.
- [ ] Mỗi giả định là phương án ít phát minh nhất và có A#.

### V4 — Nhất quán liên file
- [ ] Envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3.
- [ ] Mỗi resource chỉ một module sở hữu endpoint; module khác tham chiếu, không định nghĩa lại (đối chiếu `06-module-contracts.md` §2).

### V5 — Bằng chứng trước khẳng định
- [ ] Dán output `ALL CONTRACTS PASS` (và Redocly nếu chạy) vào PR rồi mới báo xong.

## 5. Danh sách contract

Xem [`INDEX.md`](./INDEX.md).
