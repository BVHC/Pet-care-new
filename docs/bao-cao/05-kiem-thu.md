# Chương 5. Kiểm thử

> Trạng thái: **khung** (T86-1, 18/11/2026). Số liệu chính thức lấy từ T84 (14–18/12/2026); chương hoàn thiện ở phiên T86-6. Mọi số bên dưới là **số tạm có nhãn ngày**; không điền số ước chừng.

## 5.1. Chiến lược kiểm thử

*Sẽ viết:* các lớp kiểm thử và mục đích từng lớp; nguyên tắc viết test trước; môi trường (PostgreSQL thật qua Testcontainers, migration thật, múi giờ cố định); cách cô lập test tích hợp bằng "kỷ nguyên" đồng hồ riêng (`docs/architecture/testing-notes.md`).

## 5.2. Các lớp kiểm thử

| Lớp | Công cụ | Nội dung |
|---|---|---|
| Unit test backend | JUnit 5, Mockito | Logic nghiệp vụ trong service |
| Test tích hợp backend | Spring, Testcontainers | Luồng đầy đủ trên PostgreSQL thật, gồm đua khóa, rollback, chỉ mục |
| Test máy trạng thái | Bộ test theo bảng chuyển | Mọi chuyển hợp lệ và không hợp lệ |
| Kiểm tra hợp đồng | `check-contracts`, `OpenApiContractParityIT` | Hợp đồng HTTP bằng Swagger sinh từ mã |
| Test frontend | Vitest, Testing Library | Màn hình, hook, dữ liệu mô phỏng |
| End-to-end | Playwright | Luồng chính (đo ở T84) |
| Ma trận kiểm thử tay | Bảng tính | Ca theo use case và màn hình (`docs/testcases/`) |

*Bảng này là dự kiến theo đề cương và `docs/architecture/testing-notes.md`; khi viết chính thức phải đối chiếu với mã test thật.*

## 5.3. Kết quả

Dùng Bảng S.3 ([`00-so-lieu.md`](./00-so-lieu.md)).

| Lớp | Số tạm (đã ghi nhận, không đo lại) | Nguồn | Số chính thức |
|---|---|---|---|
| Backend, unit | 6417 ca | hồi quy 17/11/2026, `d1e116c` | `TODO(T84)` |
| Backend, tích hợp | 233 lớp, 3266 ca, 0 đỏ | hồi quy 17/11/2026, `d1e116c` | `TODO(T84)` |
| Frontend, Vitest | 231 tệp, 5326 ca, đạt hai lượt liền | 18/11/2026, sau `ce9ec0b` | `TODO(T84)` |
| End-to-end | — | — | `TODO(T84)` |
| Ma trận kiểm thử tay | — | — | `TODO(T84)` |

## 5.4. Đối chiếu use case và quy tắc với kiểm thử

*Sẽ viết:* bảng ánh xạ use case, quy tắc BR, máy trạng thái với ca kiểm thử (`docs/testcases/ket-qua-tu-dong-anh-xa-ma-tran.md`); phần nào chưa có ca thì nói rõ.

## 5.5. Kiểm thử phân hệ AI

Xem chương 6, mục đánh giá. Chương 5 chỉ tham chiếu.

## 5.6. Lỗi phát hiện và xử lý

*Sẽ viết:* các lỗi tiêu biểu tìm được khi kiểm thử và nối frontend với backend thật, cách sửa (lấy từ `docs/testcases/`, `docs/be-fe-integration.md`, ADR). Chỉ ghi lỗi có tài liệu.

## 5.7. Giới hạn của việc kiểm thử

*Sẽ viết:* chưa có người dùng thật; chưa đo tải; dữ liệu thử là dữ liệu giả; bộ câu hỏi AI không phải bằng chứng an toàn tuyệt đối (ADR-0136 D6). Chỉ ghi giới hạn đã xác nhận.

## Nguồn

`docs/architecture/testing-notes.md`; `docs/testcases/`; `docs/convention/backend/09-testing.md`; `docs/api/check-contracts.mjs`; kết quả hồi quy `ket-qua-hoi-quy-17-11.md` (ngoài repo: `D:/Do-an/.c2-tmp/final2/`); [`00-so-lieu.md`](./00-so-lieu.md).

## TODO

- `TODO(T84)`: toàn bộ số ở 5.3 và ví dụ ở 5.4.
- Kiểm tra nguồn `ket-qua-hoi-quy-17-11.md` còn đúng tại thời điểm trích; nếu cần đưa số vào phụ lục thì sao chép bảng kết quả kèm ngày.
- Kiểm thử đột biến (mutation) do nhóm chạy thủ công tại một số tính năng: nếu đưa vào báo cáo, phải nêu cách làm và số đã đo ở T84, không viết chung chung.
