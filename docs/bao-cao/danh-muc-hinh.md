# Danh mục hình

> Cập nhật 18/11/2026 (T86-1). Mặc định các hình viết bằng **Mermaid** ngay trong tệp Markdown của chương (GitHub và VS Code hiển thị được). Khi xuất docx, Mermaid không tự thành ảnh: phải xuất PNG hoặc SVG trước và đặt vào `docs/bao-cao/hinh/` (thư mục này chưa tạo vì chưa có ảnh nào). Hình lớn (ERD đầy đủ, sơ đồ chuỗi) có thể dựng bằng skill `diagram-design` để ra SVG và PNG; chưa dùng ở phiên này.
>
> **Đã kiểm cú pháp, chưa xem hình.** Cả 9 khối Mermaid đã qua trình phân tích của Mermaid 11 (chạy bằng Node, 18/11/2026; 9 qua, 0 lỗi). Phiên T86-1 không có công cụ hiển thị, nên chưa ai nhìn thấy hình đã vẽ; cần mở thử trên GitHub hoặc VS Code trước khi dùng (nhãn dài hoặc tiếng Việt có thể bị cắt).

| Mã | Nội dung | Loại | Nguồn đặc tả | Công cụ | Trạng thái | Vị trí |
|---|---|---|---|---|---|---|
| H1.1 | Lịch hai giai đoạn và hoàn thiện | Gantt | Đề cương 2.4.5 | Mermaid | **Đã dựng** | `01-gioi-thieu.md` 1.3.1 |
| H2.1 | Luồng nghiệp vụ chính | Lưu đồ | Đề cương 2.2.7; `docs/01`; `docs/03` phụ lục | Mermaid | **Đã dựng** | `02-phan-tich-yeu-cau.md` 2.5 |
| H2.2 | Máy trạng thái Visit (#4) | Trạng thái | `docs/03` mục 4 | Mermaid | **Đã dựng** | 2.7.1 |
| H2.3 | Máy trạng thái Order (#5) | Trạng thái | `docs/03` mục 5 | Mermaid | **Đã dựng** | 2.7.2 |
| H2.4 | Máy trạng thái đặt chỗ lưu trú (#6) | Trạng thái | `docs/03` mục 6 | Mermaid | **Đã dựng** | 2.7.3 |
| H2.5 | Máy trạng thái đơn hàng online (#14) | Trạng thái | `docs/08` mục B | Mermaid | **Đã dựng** | 2.7.4 |
| H2.6 | Máy trạng thái tài liệu tri thức AI (#13) | Trạng thái | `docs/07` mục 9 | Mermaid | **Đã dựng** | 2.7.5 |
| H2.7 | ER rút gọn của luồng chính | ER | `docs/05` "Sơ đồ luồng chính" | Mermaid | **Đã dựng** (chép từ `docs/05`) | 2.8 |
| H3.1 | Kiến trúc khối | Khối | `docs/architecture/system-overview.md` mục 2 | Mermaid | **Đã dựng** | `03-thiet-ke.md` 3.1 |
| H3.2 | Đồ thị phụ thuộc giữa module | Lưu đồ | `docs/06` mục 4 | Mermaid hoặc `diagram-design` | Chưa dựng | 3.2 |
| H3.3 | ERD đầy đủ theo nhóm nghiệp vụ | ER | `docs/05`; `docs/07` mục 8; `docs/08`; `docs/09` | Mermaid (chia nhiều hình) hoặc `diagram-design` | Chưa dựng | 3.3 |
| H3.4 | Chuỗi hoàn tất lượt khám | Trình tự | `docs/03` phụ lục | Mermaid | Chưa dựng | 3.6 |
| H3.5 | Chuỗi thu tiền Order (khóa và trừ kho) | Trình tự | `docs/architecture/concurrency-locking.md`; BR-BH-04 | Mermaid | Chưa dựng | 3.6 |
| H3.6 | Luồng xử lý một request | Lưu đồ | Đề cương 2.3; `docs/convention/backend/` | Mermaid | Chưa dựng | 3.1 |
| H3.7 | Kiến trúc frontend | Khối | `docs/architecture/frontend-notes.md` | Mermaid | Chưa dựng | 3.8 |
| H4.1 | Cấu trúc module backend | Khối | `CLAUDE.md` (Backend architecture); mã nguồn | Mermaid | Chưa dựng | 4.2 |
| H4.2 | Chuỗi gọi một use case ghi | Trình tự | `docs/convention/backend/`; mã nguồn | Mermaid | Chưa dựng | 4.3 |
| H6.1 | Kiến trúc RAG | Khối | `docs/07` mục 5, 6 | Mermaid | Chưa dựng | `06-phan-he-ai.md` 6.2 |
| H6.2 | Chuỗi các lớp chặn của chatbot | Lưu đồ | `docs/07` mục 0, 5 | Mermaid | Chưa dựng | 6.2 |
| H6.3 | Mô hình dữ liệu AI | ER | `docs/07` mục 8 | Mermaid | Chưa dựng | 6.5 |

Quy ước: mã hình `H<chương>.<số>`; chú thích hình đặt **dưới** hình, dạng "Hình C.N. Tên." kèm dòng nguồn. Ảnh chụp màn hình giao diện (nếu cần ở chương 4) chỉ chụp từ bản chạy thật và ghi ngày chụp; chưa có ảnh nào.
