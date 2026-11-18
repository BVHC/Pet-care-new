# Chương 6. Phân hệ trí tuệ nhân tạo

> Trạng thái: **khung** (T86-1, 18/11/2026). Phần thiết kế viết ở phiên T86-3, phần đánh giá ở phiên T86-4; cả hai làm được ngay vì đã có số đo (T77, T92, T92b). Cập nhật nếu kho tri thức đổi.

> **Câu giữ nguyên ở mọi bản của chương này (quy tắc trung thực).** Người rà soát kho tri thức trong hệ thống là một **nhãn giả định** ("BS. Nguyễn Văn Nam", quyết định ngày 9/11/2026) dùng cho bản demo. Nội dung y khoa trong kho **chưa được bác sĩ thú y thật xác nhận**; tài liệu có mức rủi ro cao cần được bác sĩ thật duyệt trước khi dùng ngoài demo. Đạt bộ câu hỏi kiểm thử **không** phải bằng chứng an toàn tuyệt đối (ADR-0136, D6). Không được viết ở bất cứ đâu rằng kho đã được bác sĩ thú y duyệt.

## 6.1. Mục tiêu và phạm vi

*Sẽ viết:* hai khả năng (chatbot tư vấn chăm sóc cơ bản; gợi ý cá nhân hóa dịch vụ và sản phẩm); năm use case UC90–UC94 và hai tác vụ ST22, ST23; chatbot **không** thay bác sĩ, không chẩn đoán, không kê thuốc; điều chỉnh so với đề cương (Bảng 1.3, dòng 8 và 9).

## 6.2. Kiến trúc và luồng xử lý

*Sẽ viết:* kiến trúc truy hồi kết hợp sinh câu trả lời (RAG): chia đoạn, nhúng vector, kho vector pgvector, truy hồi có lọc ngay trong câu SQL; các lớp chặn theo thứ tự (từ khóa cấp cứu, hồ sơ bệnh án bị từ chối, ý định cá nhân, ngưỡng truy hồi, cờ phạm vi do mô hình trả, hậu kiểm liều thuốc); bảng "chatbot trả lời gì cho ai" (khách vãng lai và khách đăng nhập). *Hình cần dựng:* H6.1 kiến trúc RAG; H6.2 chuỗi các lớp chặn.

## 6.3. Ngữ cảnh thú cưng và kiến thức chung có kiểm soát (bản mở rộng v2)

*Sẽ viết:* nạp tài liệu từ tệp và quản trị nguồn kho (ADR-0134); ngữ cảnh thú cưng trong chat cá nhân và gói dịch vụ (ADR-0135); kiến thức chung có kiểm soát, nhãn nguồn câu trả lời, đánh giá hồi quy (ADR-0136). Nêu **trạng thái công tắc cấu hình tại ngày viết** (đọc từ DB và ADR, ghi ngày).

## 6.4. An toàn và quyền riêng tư

*Sẽ viết:* BR-AI-03 (cấp cứu), BR-AI-12 (che dữ liệu cá nhân), BR-AI-25 (khóa API và gói dịch vụ), BR-AI-10 (AI lỗi không chặn trang); dữ liệu cá nhân và nhà cung cấp bên ngoài; hạn mức dùng gói miễn phí và hệ quả.

## 6.5. Quản trị kho tri thức

*Sẽ viết:* UC93; hai lớp kiểm soát của BR-AI-14 (nguồn và người rà soát); máy trạng thái #13; tác vụ ST22 (nạp lại chỉ mục, đồng bộ phác đồ tiêm và bài viết); tài liệu từ danh mục phác đồ (`CATALOG_PROTOCOL`) và từ bài viết. Số tài liệu theo trạng thái: **đọc từ DB và ghi ngày**.

## 6.6. Gợi ý cá nhân hóa

*Sẽ viết:* gợi ý bằng chấm điểm theo luật, không huấn luyện mô hình; hồ sơ gợi ý của dịch vụ và sản phẩm (UC94); điểm yếu về dữ liệu (`docs/07` mục 7, ADR-0133).

## 6.7. Đánh giá

*Sẽ viết:* phương pháp (bộ câu hỏi, số lượt, chỉ tiêu đạt); kết quả spike T56 (ADR-0122, `docs/07` mục 13); kết quả đo sau khi cài (T77, T92) và điều tra T92b (ADR-0136, mục "Điều tra T92b"); độ trễ (p95); tỷ lệ lỗi nhà cung cấp; các câu trượt và nguyên nhân. **Mọi số kèm tệp nguồn, ngày, commit.** Không làm tròn; không so sánh các lần đo khác kho.

## 6.8. Giới hạn

*Sẽ viết:* người rà soát giả định (câu giữ nguyên ở đầu chương); phạm vi tri thức nhỏ; phụ thuộc nhà cung cấp bên ngoài và hạn mức; chưa có đánh giá bởi người dùng hay chuyên gia; những gì cố ý không làm (chẩn đoán, kê thuốc).

## Nguồn

`docs/07-ai-subsystem.md` (mục 0, 4–10, 12–16); ADR 0109, 0110, 0122 (`0122-ai-spike-t56-decisions`), 0129, 0130, 0133, 0134, 0135, 0136, 0141, 0142; `docs/api/ai-v1.md`; `docs/docs_RAG/` (kho tri thức nguồn); `BE/scripts/spike/` (bộ câu hỏi và mã đo); `D:/Do-an/.c2-tmp/final2/ket-qua-t92b-dieu-tra.md` (ngoài repo; bản chính ở ADR-0136).

## TODO

- Phiên T86-3: viết 6.1–6.6. Phiên T86-4: viết 6.7–6.8.
- `TODO(T84)`: số đo lại nếu T84 chạy lại bộ câu hỏi; trạng thái kho tri thức và công tắc tại ngày chốt.
- Số liệu kho tri thức (số tài liệu, theo trạng thái) phải **đọc lại từ DB demo (chỉ đọc)** ở ngày viết, không chép từ tài liệu cũ: ADR-0136 và `docs/07` có số tại các ngày khác nhau (28, 35, 39 tài liệu xuất bản).
- Hình cần dựng: H6.1, H6.2, H6.3 (mô hình dữ liệu AI).
