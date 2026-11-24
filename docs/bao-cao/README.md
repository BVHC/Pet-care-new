# Báo cáo đồ án tốt nghiệp

Báo cáo của đề tài **"Xây dựng hệ thống quản lý chuỗi dịch vụ và chăm sóc thú cưng"** (hàng T86 của `docs/time.md`, 14–31/12/2026). Viết bằng Markdown để theo dõi bằng git; xuất docx hoặc PDF ở phiên cuối.

- **Đối tượng đọc:** hội đồng và giảng viên hướng dẫn. Đọc để chấm, không phải để vận hành hệ thống. Viết cho người chưa biết kho mã: giải thích thuật ngữ nghiệp vụ lần đầu; không dán đường dẫn mã vào thân bài (chỉ ở khối **Nguồn** cuối mỗi chương).
- **Trạng thái:** phiên T86-1 (18/11/2026). Chương 1 và 2 là bản nháp đầy đủ; chương 3–7 là khung.
- **Ngôn ngữ:** tiếng Việt có dấu; thuật ngữ kỹ thuật giữ tiếng Anh.

## Mục lục

| Tệp | Nội dung | Trạng thái |
|---|---|---|
| [`00-so-lieu.md`](./00-so-lieu.md) | Bảng số liệu dùng chung: số đếm (S.1), trạng thái cài đặt (S.2), số kiểm thử tạm (S.3) | Đo 18/11/2026; **đo lại ở T84** |
| [`01-gioi-thieu.md`](./01-gioi-thieu.md) | Chương 1. Giới thiệu | Bản nháp đầy đủ |
| [`02-phan-tich-yeu-cau.md`](./02-phan-tich-yeu-cau.md) | Chương 2. Khảo sát và phân tích yêu cầu | Bản nháp đầy đủ |
| [`03-thiet-ke.md`](./03-thiet-ke.md) | Chương 3. Thiết kế hệ thống | Khung (T86-2) |
| [`04-cai-dat.md`](./04-cai-dat.md) | Chương 4. Cài đặt | Khung (sau T83) |
| [`05-kiem-thu.md`](./05-kiem-thu.md) | Chương 5. Kiểm thử | Khung (sau T84) |
| [`06-phan-he-ai.md`](./06-phan-he-ai.md) | Chương 6. Phân hệ trí tuệ nhân tạo | Khung (T86-3, T86-4) |
| [`07-ket-luan.md`](./07-ket-luan.md) | Kết luận và hướng phát triển | Khung |
| [`08-tai-lieu-tham-khao.md`](./08-tai-lieu-tham-khao.md) | Tài liệu tham khảo | Bản nháp |
| [`09-phu-luc.md`](./09-phu-luc.md) | Phụ lục | Khung |
| [`danh-muc-hinh.md`](./danh-muc-hinh.md) | Danh mục hình: mã, loại, nguồn, công cụ, trạng thái | 9 hình đã dựng, 11 chưa |

Chưa biết mẫu của khoa nên dùng bố cục mặc định theo hàng T86: 1 Giới thiệu; 2 Khảo sát và phân tích yêu cầu; 3 Thiết kế hệ thống; 4 Cài đặt; 5 Kiểm thử; 6 Phân hệ AI; rồi Kết luận, Tài liệu tham khảo, Phụ lục. Khi có mẫu thì đổi tên và thứ tự, nội dung giữ.

## Quy ước

1. **Số liệu.** Mọi con số trong thân bài phải có mặt ở [`00-so-lieu.md`](./00-so-lieu.md) kèm ngày đo và lệnh đếm. Ngoại lệ phải ghi ngay chỗ dùng. Không điền số ước chừng, không làm tròn, không chép số cũ như số mới.
2. **`TODO(T84)`**: chỗ cần số đo ở T84 (14–18/12/2026) hoặc cần kiểm lại sau T83. Liệt kê bằng `grep -rn "TODO(T84)" docs/bao-cao` (hoặc `git grep` sau khi các tệp đã được git theo dõi). `TODO` không kèm mã là việc viết chưa xong.
3. **Số tạm.** Số đã đo nhưng sẽ đo lại thì gắn nhãn "số tạm" kèm ngày và commit.
4. **Đánh số.** Hình `Hình C.N`, bảng `Bảng C.N` (C là số chương); bảng dùng chung là `Bảng S.N`. Chú thích hình đặt dưới hình, chú thích bảng đặt trên bảng; mỗi hình, bảng có dòng *Nguồn*.
5. **Nguồn.** Khối **Nguồn** cuối mỗi chương liệt kê tài liệu `docs/…` và ADR đã dùng. ADR có số trùng (0088, 0122, 0123, 0124) phải trích kèm **tên tệp**.
6. **Phân hệ và tác nhân.** "Phân hệ nào do ai quản lý trực tiếp" là tác nhân của hệ thống (A01–A08, S01–S03) và use case của họ. Thành viên nhóm chỉ xuất hiện ở mục Phân công (1.5), gắn nhãn "phụ trách phát triển".
7. **Không sửa** `DeCuongDoAn.md` (đã ký). Chênh lệch với đề cương ghi ở mục 1.7.
8. **Sơ đồ** viết bằng Mermaid trong chương; xem [`danh-muc-hinh.md`](./danh-muc-hinh.md).

## Quy tắc trung thực (giữ ở mọi phiên)

- Trạng thái cài đặt **đo bằng lệnh, ghi ngày**; không viết "đã hoàn thành" cho thứ chưa có mã (Bảng S.2).
- Người rà soát kho tri thức AI là **nhãn giả định** (quyết định 9/11/2026). Tuyệt đối không viết rằng kho đã được bác sĩ thú y thật duyệt (xem câu giữ nguyên ở đầu chương 6).
- Không bịa: không số hiệu năng chưa đo, không "kết quả khảo sát người dùng", không ảnh chụp màn hình chưa chạy thật, không tài liệu tham khảo chưa đọc. Hình dựng từ đặc tả.
- Số đếm phải đếm lại, không chép từ đề cương.

## Mục báo cáo ↔ tài liệu gốc

| Mục báo cáo | Tài liệu gốc trong repo |
|---|---|
| 1.1–1.2 Bối cảnh, mục tiêu | `DeCuongDoAn.md` 2.1; ADR-0122, ADR-0125 |
| 1.3 Phạm vi | `DeCuongDoAn.md` 2.2.2, 2.4.5; `docs/INDEX.md`; `docs/time.md`; `00-so-lieu.md` |
| 1.4 Phương pháp | `DeCuongDoAn.md` 2.3, 2.4.1; `docs/02` (đầu tệp); `docs/06` |
| 1.5 Phân công | `DeCuongDoAn.md` 2.4.2; `docs/06` mục 2 |
| 1.7 Điều chỉnh | `DeCuongDoAn.md` Phụ lục; ADR 0091, 0098, 0100, 0109, 0110, 0122, 0123, 0124, 0125, 0134–0136 |
| 2.1–2.4 Tác nhân, use case, tác vụ | `docs/01`; `DeCuongDoAn.md` 2.2.1–2.2.3, 2.2.6 |
| 2.5 Luồng nghiệp vụ | `DeCuongDoAn.md` 2.2.7; `docs/03` phụ lục |
| 2.6 Quy tắc nghiệp vụ | `docs/02`; `docs/07` mục 4; `docs/08` |
| 2.7 Máy trạng thái | `docs/03`; `docs/07` mục 9; `docs/08`; `docs/09` |
| 2.8 Mô hình miền | `docs/04`; `docs/05` |
| 2.9 Phi chức năng | `docs/architecture/`; ADR 0001, 0003, 0009, 0015, 0040, 0091, 0098, 0100 |
| 3 Thiết kế | `docs/architecture/`; `docs/05`, `06`; `docs/api/`; `docs/convention/backend/` |
| 4 Cài đặt | `DeCuongDoAn.md` 2.3; `docs/architecture/`; `docs/be-fe-integration.md`; mã nguồn |
| 5 Kiểm thử | `docs/architecture/testing-notes.md`; `docs/testcases/`; kết quả T84 |
| 6 Phân hệ AI | `docs/07`; ADR 0109, 0110, 0122 (ai-spike), 0129, 0130, 0133–0136, 0141, 0142 |

## Cách chạy lại số đếm

Các tập lệnh nằm ngoài repo (`D:/Do-an/.c2-tmp/t86/`). `count.py` và `count2.py` chỉ đọc `docs/` và `BE/`; `gen-ch2.py` **ghi** vào `02-phan-tich-yeu-cau.md` (chỉ phần giữa cặp dấu `<!--…:begin-->` và `<!--…:end-->`). Tập lệnh chưa nằm trong git, nên ở T84 người khác chưa có chúng:

- `py D:/Do-an/.c2-tmp/t86/count.py` và `count2.py`: số use case, tác vụ, BR, endpoint, bảng, migration, ADR, số chuyển FSM.
- `node docs/api/check-contracts.mjs`: số thao tác của hợp đồng (cần mạng lần đầu).
- `py D:/Do-an/.c2-tmp/t86/gen-ch2.py`: sinh lại Bảng 2.3 và 2.4 của chương 2 từ `docs/01`.

## Việc còn lại và lộ trình các phiên

| Phiên | Nội dung | Phụ thuộc | Khi nào |
|---|---|---|---|
| T86-1 | Khung, bảng số liệu, chương 1 và 2 (phiên này) | — | Xong 18/11 |
| T86-2 | Chương 3 và các hình lớn | Chỉ đặc tả | Ngay |
| T86-3 | Chương 6, phần thiết kế AI | `docs/07`, ADR 0129–0136 | Ngay |
| T86-4 | Chương 6, phần đánh giá AI | Số đo T77, T92, T92b | Ngay; cập nhật nếu kho đổi |
| T86-5 | Chương 4 | Mã tầng 3 (đến 10/12) | Sau T83 (11/12) |
| T86-6 | Chương 5 | **T84** (14–18/12) | Sau T84 |
| T86-7 | Kết luận, tài liệu tham khảo, phụ lục, xuất docx hoặc PDF, đối chiếu mẫu của khoa | Các phiên trên | 21–28/12 |

## Câu hỏi còn mở

- **Mẫu của khoa:** có file mẫu docx không? Chương bắt buộc, số trang tối đa, font, lề, kiểu trích dẫn, bìa và các trang đầu (lời cảm ơn, tóm tắt, mục lục, danh mục hình, bảng, từ viết tắt), nộp docx hay PDF, có in không? Chưa có thông tin (không thấy file mẫu trong `D:/Do-an` ở độ sâu 2).
- Mạnh và Dũng có tự viết phần nào không? Nếu có thì khung cần chừa chỗ điền riêng (chưa có).
- Công cụ xuất docx: chưa kiểm (có skill `docx` trong phiên Claude nhưng chưa dùng).

## Chỗ các tài liệu của dự án mâu thuẫn nhau (phát hiện ở T86-1, **chưa sửa**)

Ghi cho nhóm; báo cáo dùng số đo lại, không dùng các số cũ này. Đo ngày 18/11/2026; một phiên khác đang sửa `docs/INDEX.md` và `docs/adr/INDEX.md`, nên kiểm lại trước khi trích.

1. Đề cương tự mâu thuẫn: 85 use case (58 + 27) ở mục 2.2.2, 83 (58 + 25) ở Phụ lục. Hàng T84 của `docs/time.md` còn ghi "đủ 83 UC + 21 ST".
2. `docs/INDEX.md`: ghi "12 contract v1" (thực tế 24); ghi số ADR kế tiếp 0126 (thực tế 0147); mô tả tầng 3 là "không có rule/model" ở Bảng 2.
3. `docs/01`: sáu tác nhân (A02, A04–A08) còn ghi "Hướng phát triển (tầng 3)", trái với phạm vi hiện hành (tầng 3 và AI nằm trong phạm vi giai đoạn 2).
4. `docs/api/INDEX.md`: tổng 344, cộng cột 343, hợp đồng thật 345 (identity 39 chứ không phải 38, ai 14 chứ không phải 13); còn câu "chưa cài đặt".
5. `docs/architecture/system-overview.md` (mốc 10/11) và `CLAUDE.md` mục *Current state*: "15 thư mục module", "236 handler", "chưa có Java cho shop, payment"; hiện là 17 thư mục, 263 handler, shop có 18 handler.
6. ADR trùng số: 0088, 0122, 0123, 0124 mỗi số có hai tệp.
7. Đề cương mục 2.3: "trigger bảo đảm nhật ký audit chỉ được thêm mới"; V31 đã gỡ (ADR-0098). Ghi ở Bảng 1.3.
8. `docs/05`: 59 mục bảng, trong khi `docs/INDEX.md` nói 55 bảng (số của v16 trước khi thêm `refresh_tokens` và bảng nhân sự).
