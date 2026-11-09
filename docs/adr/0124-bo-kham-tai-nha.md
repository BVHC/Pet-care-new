# ADR-0124: Bỏ khám tại nhà (UC43, UC51) khỏi tầng 3

- **Trạng thái:** Accepted (Hiến, nhóm trưởng, quyết 9/11/2026)
- **Ngày:** 2026-11-09
- **Liên quan:** ADR-0113 §1 (V37 cột khám tại nhà, V41 `line_type` thêm `HOME_FEE`) và ADR-0117 (V43 `branch_home_visit_fees`): **thay đổi một phần** (phần khám tại nhà thành "không dùng"); **không sửa nội dung** hai ADR đó. ADR-0116 không đổi.
- **Triển khai:** FE gỡ toàn bộ màn hình, API, kiểu, hook, mock khám tại nhà (danh sách ở mục 3); tài liệu `docs/01`–`04`, `06`, `08`–`10`, `docs/api` (sinh lại), `docs/testcases/T57-fe.md`, `docs/time.md`, `DeCuongDoAn.md`, `CLAUDE.md`. Không đụng BE (code, migration, test).

## Bối cảnh

Tầng 3 của giai đoạn 2 có 27 use case (BE-1 12, BE-2 15). Khám tại nhà (UC43 đặt lịch, UC51 bác sĩ thực hiện) là hai trong 12 use case của BE-1 (`docs/09` §A, task T60, 23–25/11). BE chưa có Java cho module `homevisit`; schema đã có từ T55 (V37, V41, V43).

## Quyết định

1. **Bỏ hẳn UC43 và UC51.** Không cài BE, không làm UI. Tầng 3 còn **25 UC** (BE-1 còn 10: UC34–38, UC50, UC55, UC56, UC85, UC86; BE-2 vẫn 15), 9 ST không đổi.
2. **T60 bỏ.** T62 vẫn làm (phí vận chuyển và định mức vật tư vẫn cần), chỉ bỏ nửa phí khám tại nhà; lý do "làm trước" của T62 còn mỗi T71 (ST07). T68 chỉ còn giao đơn và phẫu thuật. Khoảng trống của BE-1 (23–25/11) chưa giao việc khác, Hiến quyết sau.
3. **Xóa khỏi tài liệu và FE**, không gạch, không ghi chú; các mã còn lại không đánh số lại. Gồm UC43, UC51, BR-LH-13, BR-LH-14, BR-KB-07, BR-BH-16, BR-SP-09, FSM #22, `docs/09` mục A, các interface chéo `CatalogQueryApi.findHomeVisitFee`, `VisitOrderApi.addHomeFeeLine`, `VisitApi.startHomeVisit`. Quy tắc dùng chung với phí vận chuyển (BR-SP-08, 10, 11) chỉ sửa chữ.
4. **Hợp đồng HTTP:** bỏ `homevisit-v1` (6 endpoint) và hai endpoint `GET`/`PUT /branches/{branchId}/home-visit-fee` của `fees-v1` (#3, #4). Material-norms từ #5, #6 thành **#3, #4**. `generate.py` bỏ `homevisit` khỏi `MODULES`. `check-contracts`: 27 file / 371 operation thành 26 file / 363 operation (232 GĐ1 + 131 tầng 3).

## Không dùng nhưng giữ trong schema

Migration đã áp dụng, sửa nội dung là đổi checksum Flyway; các cột mặc định `false` hoặc NULL, nên không gây hại. **Không tạo migration, không xóa cột, không sửa test schema** (`SchemaMigrationIT`, `Tier3Be1SchemaIT`, `Tier3Be2SchemaIT`).

| Phần schema | Migration | Tình trạng |
|---|---|---|
| `appointments.is_home_visit`, `home_address`, `home_contact_phone`, `home_fee` + 3 CHECK; `visits.is_home_visit` | V37 | không dùng |
| `order_lines.line_type = 'HOME_FEE'` | V41 | không dùng |
| bảng `branch_home_visit_fees` | V43 | không dùng |

ERD (`docs/05`) giữ nguyên các cột và bảng này vì ERD phải khớp DB.

## Hệ quả

- **FE:** không vai trò nào còn thấy khám tại nhà. Các trường `isHomeVisit`, `home*` không có trong hợp đồng GĐ1 (`appointment-v1`, `visit-v1`, `sales-v1`), nên bỏ khỏi kiểu FE cùng chỗ dùng. URL cũ của khám tại nhà rơi vào trang 404 của route `*`.
- **Tài liệu:** số tầng 3 là 25 UC; tổng UC hiệu lực của v16 từ 85 xuống 83; số module tầng 3 giảm một (`homevisit`).
- **Đề cương (`DeCuongDoAn.md`)** đã sửa cho khớp.
- **Chưa làm:** nếu sau này cần khám tại nhà trở lại, cần ADR mới thay ADR này; schema đã sẵn nhưng hợp đồng, quy tắc, FSM đều phải viết lại; bản cũ còn trong lịch sử git trước commit này.

## Phương án bị loại

- Xóa cột và bảng bằng migration mới: đổi schema đã áp, nhóm đã dùng DB demo, không có lợi ích so với giữ cột mặc định.
- Sửa nội dung ADR-0113 / ADR-0117: ADR đã duyệt thay bằng ADR mới, không sửa tại chỗ.
- Chỉ ẩn UI, giữ BE và hợp đồng: vẫn phải làm T60, không giảm phạm vi.
