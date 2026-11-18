# Chương 4. Cài đặt

> Trạng thái: **khung** (T86-1, 18/11/2026). Mã tầng 3 còn thay đổi tới khoảng 10/12/2026 (hạn M5 là 11/12), nên chương này hoàn thiện sau T83 (phiên T86-5). Các mục dưới chỉ ghi dự kiến.

## 4.1. Công nghệ sử dụng

*Sẽ viết:* bảng công nghệ theo lớp (backend, frontend, cơ sở dữ liệu và hạ tầng, tích hợp, AI, kiểm thử) kèm phiên bản **đọc từ tệp cấu hình của dự án**, không chép từ đề cương. Nguồn khởi đầu: đề cương mục 2.3 và `docs/architecture/system-overview.md` (Java 21, Spring Boot 3.5, PostgreSQL 17 kèm pgvector, Redis 7, Flyway, React, Vite, Tailwind, TanStack Query…). Giải thích lựa chọn khi có ADR.

## 4.2. Cấu trúc mã nguồn

*Sẽ viết:* cây thư mục của kho mã nguồn (backend, frontend, tài liệu, tập lệnh); cấu trúc một module backend (controller, service, repository, dto, api, job); cấu trúc frontend (`app`, `pages`, `components`, `shared`).

## 4.3. Các cơ chế cài đặt đáng kể

*Sẽ viết (mỗi cơ chế một mục nhỏ, kèm đoạn mã ngắn khi cần):* lớp cơ sở FSM và bảng chuyển trạng thái; ghi audit tường minh; hàng đợi outbox thông báo; sổ kho `StockLedger` và trừ kho theo FEFO; thứ tự khóa và khóa tư vấn; bộ lọc giới hạn tần suất; trình sinh hợp đồng OpenAPI; module `ai` (client Gemini, chỉ mục pgvector, định tuyến ý định). Chỉ nêu cơ chế **đã có trong mã**.

## 4.4. Frontend: từ dữ liệu mô phỏng đến backend thật

*Sẽ viết:* quy trình mock trước, nối thật sau, và quy tắc mock phải khớp backend; các lần nối đã làm (giai đoạn 1 theo cụm; thương mại điện tử, phí vận chuyển, AI ở giai đoạn 2); lỗi phát hiện khi nối và cách xử lý. Nguồn: `docs/be-fe-integration.md`, `docs/testcases/`.

## 4.5. Trạng thái cài đặt

Dùng Bảng S.2 ([`00-so-lieu.md`](./00-so-lieu.md)) đã đo lại ở T84. Mỗi dòng phải ghi ngày đo và cách đo. **Không viết "đã hoàn thành" cho module không có mã.**

## 4.6. Hạn chế và nợ kỹ thuật

*Sẽ viết:* các nợ kỹ thuật nhóm đã chấp nhận (có ADR), phần đặc tả chưa cài (Bảng S.2), phần chưa chạy thử với backend thật. Danh sách nợ của nhóm nằm ở `docs/dept/` (thư mục không được git theo dõi); khi trích dẫn phải đọc lại và ghi ngày.

## Nguồn

`DeCuongDoAn.md` mục 2.3; `docs/architecture/{system-overview,frontend-notes}.md`; `docs/be-fe-integration.md`; `docs/convention/backend/`; `docs/api/`; `CLAUDE.md` (mục Backend architecture, Platform toolbox); mã nguồn trong `BE/` và `FE/`; ADR theo từng cơ chế.

## TODO

- `TODO(T84)`: toàn bộ mục 4.5 (đo lại Bảng S.2).
- Viết mục 4.1–4.4, 4.6 sau T83 (11/12/2026).
- Phiên bản thư viện: lấy từ `BE/pom.xml` và `FE/package.json` tại ngày viết.
- Hình cần dựng: H4.1 cây module backend; H4.2 chuỗi gọi một use case ghi.
