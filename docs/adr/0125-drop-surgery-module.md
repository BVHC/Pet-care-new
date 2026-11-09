# ADR-0125: Bỏ module phẫu thuật

- **Trạng thái:** Accepted (Mạnh quyết ngày 2026-11-09 trong phiên đặc tả nội trú; chưa có văn bản của Hiến và Dũng — phần của BE-2 chỉ là giá trị enum `SURGERY`, xem Hệ quả)
- **Ngày:** 2026-11-09
- **Liên quan:** `docs/09` §B (đã thay bằng mục "Bỏ"), `docs/01` UC50, `docs/03` #23, `docs/05` `surgeries`, `docs/api/surgery-v1`, ADR-0113 (V37, V41), ADR-0124 (nội trú), ADR-0122 (mẫu bỏ module)

## Bối cảnh

T54 duyệt phẫu thuật (UC50) thành module `surgery` của BE-1: 4 quy tắc (BR-KB-08…11), FSM #23 (5 trạng thái, 6 chuyển), bảng `surgeries`, `surgery-v1` (8 endpoint), cam kết bằng giấy do lễ tân ghi nhận, và phần FE ở bàn bác sĩ và bàn lễ tân (T61, T68). Khi rà lại phạm vi đồ án (không có người dùng thật):

- petcare.vn (tham chiếu) chỉ liệt kê phẫu thuật điều trị và theo yêu cầu như dịch vụ, không công bố quy trình cam kết, vòng đời hay cách tính; không có gì để đối chiếu.
- Phần đặc thù (cam kết ghi nhận trên giấy, phụ phẫu chưa quyết ở Q5, dừng phẫu thuật không tính tiền) là nhiều giả định mà không tạo thêm hành vi cốt lõi so với Visit khám thường.
- Nội trú (ADR-0124) không phụ thuộc phẫu thuật: không có khóa ngoại hay interface nào từ `admissions` tới `surgeries`; nó nhận ca từ lượt khám hoặc đặt chỗ lưu trú.

## Quyết định

1. **Bỏ phẫu thuật.** UC50, BR-KB-08…11, FSM #23, module `surgery`, `surgery-v1` (8 endpoint), bảng `surgeries`, giá trị `SURGERY` của `services.medical_type`, T61 và phần phẫu thuật của T68. Giữ nguyên các mã UC / BR / FSM (gạch bỏ) để không lệch số.
2. **Theo dõi sau phẫu thuật không còn là một tính năng.** Thủ thuật nhỏ (nếu có) ghi trong bệnh án như dịch vụ khám thông thường; ca cần nằm lại dùng nội trú (UC55, UC56) với theo dõi nội trú chung (ADR-0124 mục 7).
3. **Lượt khám không còn điều kiện "không hoàn tất khi còn phẫu thuật mở"** (BR-KB-10) và hủy Visit không còn hủy theo phẫu thuật (BR-KB-11). BR-LH-01 bỏ mệnh đề "phẫu thuật chỉ phát sinh từ khám (UC50)".
4. **ST07** cộng dồn vật tư cho mọi dịch vụ của Visit, chỉ còn "kể cả nội trú".

## Hệ quả

| Phần | Thay đổi |
|---|---|
| Số liệu tầng 3 | UC 27 → 26; FSM #11–#25 (15) → 14; bảng 31 → 30; endpoint 129 → 121 (`surgery-v1` −8); `api/INDEX` tổng 360 → 352 |
| `docs/06` | Bỏ dòng module `surgery`; dòng 6 của §6b chỉ còn `inpatient` (ADR-0124) |
| `docs/time.md` | T61 bỏ (không dùng số); T68 chỉ còn đơn online và khám tại nhà; T83 bỏ "phẫu thuật" khỏi demo |
| DB | **Migration riêng (chưa viết; số lấy lúc viết sau khi hỏi BE-2; mỗi nợ D061, D062, D063 một migration, không gộp):** `DROP TABLE surgeries` (V37 đã áp dụng); bỏ `SURGERY` khỏi `ck_services_medical_type`. Trong lúc chờ, bảng và giá trị enum còn trong DB, không dùng |
| Code | Chưa có Java cho phẫu thuật; `Tier3Be1SchemaIT`, `SchemaMigrationIT` (`ERD_TABLES` 67 → 66 sau migration này) còn tham chiếu `surgeries` |
| FE | Gỡ `surgery.api.ts`, `useSurgery`, `surgery.mock`, `components/staff/surgery`, `SurgerySection`, `SurgeryConsentList` và các chỗ nối ở bàn bác sĩ / bàn lễ tân / catalog mock |
| BE-2 | Giá trị `SURGERY` trong `medical_type` (V41, mục 7 của `docs/06` §6b) không còn dùng; BE-2 không phải làm gì thêm, nhưng cần biết để không thêm `MedicalType.SURGERY` vào Java (ADR-0117) |

## Phương án đã loại

- **Giữ module, làm bản rút gọn** (bỏ cam kết, chỉ ghi nhận có phẫu thuật): gần như một ghi chú trong bệnh án, thêm bảng, 4–5 endpoint và FE mà không thêm hành vi nào.
- **Giữ nguyên spec T54 nhưng hoãn làm:** để nợ mơ hồ ở tài liệu, FE và schema; vẫn phải nhớ nó ở T83.
- **Giữ chỉ giá trị enum `SURGERY` cho dịch vụ bán được:** không có hành vi nào dùng nó.

## Nợ ghi nhận

Spec và hợp đồng API đã sửa, code và FE chưa theo (schema V37 và V41, IT, mock và màn hình FE): D063.
