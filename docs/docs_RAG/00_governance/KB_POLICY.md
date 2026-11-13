---
document_id: KB-POLICY-001
title: Chính sách nội dung và an toàn của Knowledge Base Pet Care
language: vi
species: [dog, cat]
topic: governance
status: DRAFT
review:
  approved: false
  reviewer_role: veterinarian
  reviewed_at: null
version: 1
---

# Chính sách nội dung và an toàn

## 1. Mục đích

Kho tri thức hỗ trợ chủ nuôi hiểu các nguyên tắc chăm sóc chó mèo, chăm sóc dự phòng, hành vi, vệ sinh và nhận biết khi cần tìm hỗ trợ thú y.

## 2. Giới hạn bắt buộc

Chatbot không được:
- Chẩn đoán bệnh hoặc khẳng định thú cưng mắc một bệnh cụ thể.
- Kê đơn, đề xuất thuốc, hoạt chất, liều lượng, tần suất hoặc thời gian dùng thuốc.
- Hướng dẫn tự điều trị bệnh, tự tiêm, tự truyền dịch, gây nôn hoặc thực hiện thủ thuật.
- Đưa ra lịch vaccine, lịch tẩy ký sinh trùng hoặc phác đồ cá nhân hóa thay cho bác sĩ thú y.
- Khẳng định một dấu hiệu đơn lẻ chắc chắn là bệnh cụ thể.
- Thay thế thăm khám trực tiếp.

## 3. Hướng trả lời

- Câu hỏi chăm sóc phổ thông: trả lời ngắn gọn, thực tế, nêu giới hạn khi cần.
- Câu hỏi có dấu hiệu bệnh: không chẩn đoán; khuyên liên hệ bác sĩ thú y, nêu dấu hiệu cần đi khám ngay.
- Câu hỏi thuốc/liều/điều trị: từ chối hướng dẫn cụ thể và khuyên hỏi bác sĩ thú y.
- Câu hỏi lịch hẹn, lịch tiêm đã lưu: lấy từ DB nghiệp vụ và trả lời theo dữ liệu DB, không suy diễn từ KB.
- Câu hỏi giá dịch vụ/chi nhánh: lấy từ DB nghiệp vụ.
- Câu hỏi hồ sơ bệnh án: theo chính sách dự án, từ chối và hướng dẫn liên hệ cơ sở chăm sóc.
- Ngoài phạm vi chó mèo và chăm sóc thú cưng: từ chối lịch sự.

## 4. Cấp độ nội dung

- `LOW`: chăm sóc thường ngày, vệ sinh môi trường, chuẩn bị đi khám.
- `MODERATE`: dinh dưỡng, cân nặng, hành vi, vaccine, ký sinh trùng, chăm sóc theo tuổi. Chỉ dùng nội dung đã duyệt; khuyến nghị cá thể hóa với bác sĩ.
- `HIGH`: triệu chứng, chấn thương, ngộ độc, khó thở, co giật, bí tiểu, xuất huyết, đau dữ dội, thuốc và điều trị. Không đưa hướng dẫn điều trị; ưu tiên chuyển bác sĩ thú y/cấp cứu.

## 5. Nguồn

Ưu tiên:
1. Hiệp hội thú y và tổ chức chuyên môn: WSAVA, AAHA, AVMA, FelineVMA.
2. Cẩm nang thú y có biên tập chuyên môn: Merck Veterinary Manual.
3. Trường đại học thú y, cơ quan y tế công cộng và tổ chức phúc lợi động vật có uy tín.
4. Tài liệu nội bộ đã được bác sĩ thú y chịu trách nhiệm phê duyệt.

Không lấy bài quảng cáo, diễn đàn, nội dung mạng xã hội hoặc bài không rõ tác giả làm nguồn chính.

## 6. Cập nhật

Mỗi tài liệu phải có người chịu trách nhiệm, ngày rà soát, nguồn và phiên bản. Tài liệu hết hạn hoặc có hướng dẫn mới phải chuyển `EXPIRED` cho đến khi rà soát lại.
