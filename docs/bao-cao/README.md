# Báo cáo đồ án tốt nghiệp

Báo cáo của đề tài **"Xây dựng hệ thống quản lý chuỗi dịch vụ và chăm sóc thú cưng"** (hàng T86 của `docs/time.md`, 14–31/12/2026). Viết bằng Markdown để theo dõi bằng git; xuất docx hoặc PDF ở phiên cuối.

- **Đối tượng đọc:** hội đồng và giảng viên hướng dẫn. Đọc để chấm, không phải để vận hành hệ thống. Viết cho người chưa biết kho mã: giải thích thuật ngữ nghiệp vụ lần đầu; không dán đường dẫn mã vào thân bài (chỉ ở khối **Nguồn** cuối mỗi chương).
- **Trạng thái:** phiên T86-1 (18/11/2026): chương 1 và 2 là bản nháp đầy đủ. Phiên T86-2, T86-3, T86-4, T86-5 một phần (25/11/2026, prompt P4): chương 3 và 6 có bản nháp đầy đủ, chương 4 có bản nháp phần đã có thật. Chương 5 và 7 vẫn là khung (cần số đo T83, T84).
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
| [`pdf/`](./pdf/) | Bản PDF (A4) của từng tệp `.md` ở trên, dựng ngày 27/11/2026; không sửa tay, dựng lại bằng `node D:/Do-an/.c2-tmp/t86/pdf/make-pdf.mjs` sau khi sửa md | Sinh tự động |
| [`10-bieu-do-tuan-tu.md`](./10-bieu-do-tuan-tu.md) | Biểu đồ tuần tự cho mục 3.3.3 của `D:/Do-an/BAO CAO.docx` (10 hình mới, 3 hình dùng lại); PNG ở `hinh/` | Bản nháp 26/11/2026 |
| [`danh-muc-hinh.md`](./danh-muc-hinh.md) | Danh mục hình: mã, loại, nguồn, công cụ, trạng thái | 9 hình đã dựng, 11 chưa |

Bố cục file hiện giữ theo hàng T86: 1 Giới thiệu; 2 Khảo sát và phân tích yêu cầu; 3 Thiết kế hệ thống; 4 Cài đặt; 5 Kiểm thử; 6 Phân hệ AI; rồi Kết luận, Tài liệu tham khảo, Phụ lục. Thứ tự và tên chương sẽ đổi theo mẫu khi xuất docx (mục dưới).

## Đối chiếu hai đồ án mẫu của khoa (25/11/2026)

Hai tệp PDF trong `docs/`: `02 Đồ án tốt nghiệp-Nhóm 2-Thầy Thỏa.pdf` (website quản lý sân thể thao, 2025, khoảng 90 trang) và `Đồ-án-tốt-nghiệp-Nhóm-9-Thầy-Thỏa_1.pdf` (ứng dụng dinh dưỡng di động, 2026, khoảng 115 trang), cùng giảng viên hướng dẫn TS. Vũ Văn Thỏa. Đây là đồ án của nhóm khác dùng làm mẫu, **không phải văn bản quy định của khoa**: chưa có quy định về số trang tối đa hay kiểu trích dẫn. Bản chữ trích ra nằm ngoài repo (`D:/Do-an/.c2-tmp/t86/mau/`).

**Điểm chung của hai mẫu.** Trang đầu: bìa; lời cảm ơn; nhận xét của giảng viên phản biện; nhận xét của giảng viên hướng dẫn; mục lục; danh mục hình; danh mục bảng biểu; danh mục từ viết tắt (bảng STT, ký hiệu, cụm từ đầy đủ, ý nghĩa). Thân bài: *Mở đầu* (không đánh số chương: lý do chọn đề tài, mục tiêu, bố cục, phân công thành viên); *Chương 1 Cơ sở lý thuyết* (tổng quan lĩnh vực, ứng dụng tương tự đã triển khai, giới thiệu từng công nghệ); *Chương Phân tích, thiết kế* (xác định yêu cầu, use case và kịch bản chuẩn và ngoại lệ, biểu đồ lớp thực thể, biểu đồ trạng thái, biểu đồ tuần tự, thiết kế cơ sở dữ liệu gồm mô tả cấu trúc và sơ đồ); *Chương Cài đặt* (yêu cầu hệ thống, kết quả cài đặt bằng ảnh giao diện, đánh giá); *Kết luận* (kết quả đạt được, hạn chế, hướng phát triển); *Tài liệu tham khảo* (đánh số [n], gồm sách và tài liệu chính thức). Nhóm 9 có thêm một chương riêng cho mô hình AI (nhiệm vụ, dữ liệu, mô hình, kết quả huấn luyện, đánh giá chi tiết, tích hợp vào ứng dụng). Mỗi chương kết thúc bằng mục **Kết luận chương**. **Không có chương Kiểm thử riêng** (nhóm 9 gộp vào mục kết quả cài đặt và đánh giá).

**Định dạng đo bằng công cụ** (pdfminer, chỉ trang thân bài): A4; lề trái 30 mm, lề phải khoảng 19–20 mm; Times New Roman cỡ 13 (nhóm 9; nhóm 2 đo ra cỡ 14); chú thích và đầu, chân trang cỡ 11; khoảng cách dòng khoảng 1,3; đầu trang "Đồ án tốt nghiệp đại học" kèm tên chương; chân trang "SV:" kèm tên các thành viên và số trang. Số đo này chỉ để dựng docx ở T86-7, chưa phải quy định.

**Ánh xạ sang báo cáo này** (quyết định ngày 25/11/2026, Hiến chọn làm theo gợi ý): giữ tên và thứ tự file trong lúc viết để số chương trong thân bài không đổi; xếp lại khi xuất docx. Mỗi chương đã viết (3, 4, 6) có mục *Kết luận chương*.

| Mục trong mẫu | Nơi trong báo cáo này | Việc còn lại (T86-7 trừ khi ghi khác) |
|---|---|---|
| Bìa, lời cảm ơn, hai trang nhận xét | chưa có | tạo; hai trang nhận xét để trống theo mẫu |
| Danh mục hình | `danh-muc-hinh.md` | xuất danh sách kèm số trang |
| Danh mục bảng biểu | chưa có | sinh từ chú thích "Bảng C.N" |
| Danh mục từ viết tắt | chưa có | tạo (UC, ST, BR, FSM, RAG, JWT, ADR, CFG…) |
| Mở đầu | 1.1, 1.2, 1.5 (phân công), 1.6 (bố cục) | tách ra khỏi chương 1 |
| Chương 1 Cơ sở lý thuyết | chưa có chương này; vật liệu ở 1.1 và Bảng 4.1 | viết mục giới thiệu công nghệ. **Mục "ứng dụng tương tự" cần nguồn thật đã đọc**; không bịa (câu hỏi mở) |
| Chương AI riêng | chương 6 | đặt trước hay sau chương thiết kế: chọn lúc xếp |
| Phân tích, thiết kế | 1.3, 1.4, 1.7 + chương 2 + chương 3 | gộp thành một chương; mẫu có **kịch bản chuẩn và ngoại lệ** cho từng use case chính: kiểm chương 2, bổ sung cho 8–10 use case tiêu biểu nếu thiếu |
| Biểu đồ lớp thực thể | chưa có dạng lớp UML; có mô hình miền (2.8, H2.7) và ER (H3.3) | quyết định có vẽ thêm lớp UML không |
| Mô tả cấu trúc cơ sở dữ liệu (mẫu: bảng từng bảng) | chương 3 chỉ nêu theo nhóm | **Phụ lục**: 88 bảng, sinh từ `docs/05`, `07`–`09` |
| Cài đặt (yêu cầu hệ thống, kết quả bằng ảnh) | chương 4 | ảnh chụp từ bản chạy thật: `TODO(T84)` (danh sách màn hình ở mục 4.6) |
| Đánh giá | chương 5 (kiểm thử) | gộp vào chương cài đặt |
| Kết luận (đạt được, hạn chế, hướng phát triển) | chương 7 | theo T84 |
| Tài liệu tham khảo | chương 8 | đổi sang kiểu [n]; chỉ nguồn đã đọc |

Phân công theo thành viên chỉ xuất hiện ở mục *Mở đầu* (mẫu có mục này), đúng quy ước 6.

## Quy ước

1. **Số liệu.** Mọi con số trong thân bài phải có mặt ở [`00-so-lieu.md`](./00-so-lieu.md) kèm ngày đo và lệnh đếm. Ngoại lệ phải ghi ngay chỗ dùng. Không điền số ước chừng, không làm tròn, không chép số cũ như số mới.
2. **`TODO(T84)`**: chỗ cần số đo ở T84 (14–18/12/2026) hoặc cần kiểm lại sau T83. Liệt kê bằng `grep -rn "TODO(T84)" docs/bao-cao` (hoặc `git grep` sau khi các tệp đã được git theo dõi). `TODO` không kèm mã là việc viết chưa xong.
3. **Số tạm.** Số đã đo nhưng sẽ đo lại thì gắn nhãn "số tạm" kèm ngày và commit.
4. **Đánh số.** Hình `Hình C.N`, bảng `Bảng C.N` (C là số chương); bảng dùng chung là `Bảng S.N`. Chú thích hình đặt dưới hình, chú thích bảng đặt trên bảng; mỗi hình, bảng có dòng *Nguồn*.
5. **Nguồn.** Khối **Nguồn** cuối mỗi chương liệt kê tài liệu `docs/…` và ADR đã dùng. ADR có số trùng (0088, 0122, 0123, 0124, 0148, 0149; đo 25/11/2026) phải trích kèm **tên tệp**.
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

- **Quy định của khoa:** hai đồ án mẫu (mục *Đối chiếu* ở trên) chưa phải quy định. Còn thiếu: số trang tối đa, kiểu trích dẫn bắt buộc, nộp docx hay PDF, có in không, số lượng bản. Hỏi giảng viên hướng dẫn trước T86-7.
- **Ứng dụng tương tự (mục của chương Cơ sở lý thuyết):** cả hai mẫu đều có mục này. Báo cáo không có khảo sát thật; chỉ viết nếu nhóm đã đọc và dùng thử các ứng dụng đó, có nguồn cụ thể. Nhóm có danh sách nào không?
- Mạnh và Dũng có tự viết phần nào không? Nếu có thì khung cần chừa chỗ điền riêng (chưa có).
- Công cụ xuất docx: chưa kiểm (có skill `docx` trong phiên Claude nhưng chưa dùng).
- **Đọc DB demo cho số kho tri thức AI và trạng thái công tắc** (mục 6.3, 6.5): ngày 25/11/2026 Docker tắt và chưa được phép bật; dùng số có ngày (Bảng S.5) kèm `TODO(T84)`.

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

### Bổ sung ngày 25/11/2026 (T86-2, T86-3, T86-5; chưa sửa, báo cho nhóm)

9. **Thứ tự kiểm lỗi:** `docs/api/00-method.md` mục 3.5 ghi 400 rồi 409; ADR workforce (`0148-workforce-contract-error-order-staff-reads`, `0149-workforce-foundation-entities-fsm`) và nội trú (`0149-inpatient-foundation-entities-fsm`) đảo thành 409 rồi 400, và Bean Validation chạy trước `@PreAuthorize` (400 trước 403). Mục 3.8 của `00-method` còn ghi "chưa chốt cách khóa" dù quy ước 07 đã chốt. Chương 3 ghi quy tắc chung cùng ngoại lệ có ADR.
10. **Số handler `payment` và `returns`:** `docs/architecture/system-overview.md` mục 1 ghi `payment-v1` #1–#3 và `returns-v1` #1–#3 đã có controller. Khớp theo (method, path): `payment` có #4–#6 (đối soát), thiếu `payOnlineOrder`, `getMyPaymentTransaction`, `paymentGatewayCallback`; `returns` có #1, #6, #8. Số đếm 3 / 6 và 3 / 9 đúng, danh sách thao tác sai.
11. **Gói Gemini:** `docs/07` Q1 và ADR-0135 D1 ghi "đã chốt 13/11: chuyển gói trả phí"; quyết định 16/11 trong ADR-0135 dùng khóa miễn phí cho khách demo giả và sáu ô xác minh thanh toán còn trống. Báo cáo ghi "khóa miễn phí, chưa xác minh thanh toán".
12. **Đánh số `ai-v1`:** `docs/api/ai-v1.md` đánh nạp tệp là #7, hồ sơ gợi ý #13/#14; `docs/07` mục 10 và các ADR đánh nạp tệp #14, hồ sơ gợi ý #12/#13. Header `ai-v1.md` còn ghi "chưa có cài đặt" và "BR-AI-01…28". Tiêu đề mục 10 của `docs/07` ghi "13 endpoint", bảng có 14 dòng.
13. **Số tài liệu kho AI:** 28, 35, 39 xuất hiện ở các chỗ khác nhau của `docs/07` và ADR-0134, 0136; mỗi số thuộc một ngày (Bảng S.5). Ghi chú trong ADR-0136 (header: "tắt trước khi đo baseline") không khớp bản ghi của T92b (baseline chạy ở BE cổng 8089 trên DB tạm; DB demo giữ công tắc kiến thức chung bật).
14. **`docs/07`:** BR-AI-34 ghi `intent` "chưa cài (nợ)" trong khi mục 8 và BR-AI-33 ghi đã cài ở V57; `source_type` là `VARCHAR(40)` ở mục 8 và `VARCHAR(30)` ở BR-AI-34 và ADR-0136; ADR-0122 dặn tài liệu thật tránh câu phủ định "không có thông tin về giá" nhưng tài liệu id 8 chứa đúng câu đó.
15. **`docs/05`:** mục 1 ghi trigger chặn UPDATE/DELETE của `audit_logs`, mục 13 (#6) và ADR-0098 ghi đã gỡ ở V31; mục 12 ghi 55 bảng trong khi có 59 mục; tên `document_counters` và `document_code_sequences` cùng chỉ một bảng (mục 13 #18 và #26); mục 8 liệt kê `orders.source` chỉ `VISIT`, `RETAIL`, `BOARDING` trong khi V42 thêm `ONLINE`.
16. **`docs/06`:** mục 1 nói ranh giới là 11 nhóm miền, mục 2 có 24 module; mục 4 không phải đồ thị mà là hai bảng "ai gọi ai"; mục 6b đánh số 1–26 nhưng thiếu #5. `docs/convention/backend/07` liệt kê 4 sự kiện, `docs/06` mục 3 có 7. ADR-0007 ghi bộ lập lịch 1 luồng, ADR-0012 ghi 3, hiện là 4 (ADR-0114).
17. **Tài liệu cũ so với mã:** `README.md` gốc lỗi thời hoàn toàn (PostgreSQL 16, 22 module, 9 vai trò, lệnh chạy cũ); `application.yml` còn chú thích "không có refresh token"; `CLAUDE.md` còn nói `payment`, `returns`, `inpatient` chưa có Java; hàng T86 của `docs/time.md` vẫn `NOT_STARTED` và bảng Milestone ghi M5 "Chưa bắt đầu"; `docs/be-fe-integration.md` mục 1 và 4 còn trạng thái T40 / T45 cũ.
18. **CI:** tài liệu nói "tích hợp liên tục"; thực tế PR chỉ chạy kiểm marker, biên dịch, unit test backend, kiểm kiểu và build frontend; không chạy Vitest hay Playwright; `npm run lint || true`; kiểm thử tích hợp chỉ khi đẩy nhánh chính hoặc chạy tay; hai job triển khai chỉ `echo`.
19. **Số tệp test:** `git ls-files` (25/11) ra 430 / 317 / 236 (backend unit, integration, frontend); `CLAUDE.md` và `system-overview.md` (23/11) ra 403 / 284; lần đo 18/11 ra 378 / 233. Chưa giải; xem ghi chú dưới Bảng S.3.
20. **Pin của `OpenApiContractParityIT`:** `CLAUDE.md` ghi 248 và 247, `docs/time.md` (T94) ghi 238, `system-overview.md` ghi 217 ở một chỗ và 248 ở chỗ khác. Không dùng số nào trong báo cáo.
