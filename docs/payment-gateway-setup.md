# Pet Care Ecosystem — Cài tài khoản sandbox cổng thanh toán (T65)

> Việc của **Hiến** (nhóm trưởng, đăng ký và chuyển khóa) và **Dũng** (BE-2, đặt khóa và cài), làm trước **24/11/2026** để BE-2 làm T65 (thanh toán online, ST11, ST14) và T73 (hoàn tiền qua cổng, ST21). Đặc tả cổng trung lập ở [`08-tang3-be2-spec.md`](./08-tang3-be2-spec.md) §C (BR-TG-11); file này chỉ nói cách có tài khoản và cách đặt khóa.
> **Không bao giờ** ghi khóa bí mật vào repo, tài liệu, chat với AI hay ADR. Khóa chuyển cho người cài qua kênh riêng, người đó đặt vào biến môi trường (mục 3) rồi báo "đã đặt xong".

## 1. Chọn cổng: VNPay

Đề xuất **VNPay**: có sandbox đăng ký tự phục vụ, tài liệu công khai, có thẻ test. MoMo và ZaloPay cũng được (BR-TG-11 không phụ thuộc cổng), nhưng chưa kiểm chứng được quy trình đăng ký sandbox của hai cổng này, nên không chọn.

Nguồn đã đọc (5/11/2026):
- Đăng ký sandbox: [sandbox.vnpayment.vn/devreg](https://sandbox.vnpayment.vn/devreg/)
- Tài liệu tích hợp: [sandbox.vnpayment.vn/apis/docs/huong-dan-tich-hop](https://sandbox.vnpayment.vn/apis/docs/huong-dan-tich-hop/) và [trang thanh toán (pay)](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html)

## 2. Hiến cần làm

Người cài cổng là **Dũng (BE-2)**, trên máy của Dũng. Vì vậy khóa phải đến tay Dũng, không phải chỉ nằm trên máy Hiến.

1. Vào [sandbox.vnpayment.vn/devreg](https://sandbox.vnpayment.vn/devreg/), điền thông tin đăng ký (dùng email thật, nhận được thư).
2. Sau khi đăng ký, VNPay gửi thông tin sandbox: **mã website (`vnp_TmnCode`)**, **chuỗi bí mật (`HashSecret`)** và đường dẫn đăng nhập trang quản trị merchant. *(Chưa kiểm chứng: đây là mô tả chung của các hướng dẫn tích hợp, chưa tự đăng ký để xem thư thật. Nếu thư khác, ghi lại cho Dũng.)*
3. **Gửi `vnp_TmnCode` và `HashSecret` cho Dũng qua kênh riêng** (tin nhắn trực tiếp hoặc trình quản lý mật khẩu). Đây là khóa sandbox, không phải khóa tiền thật, nhưng **không** đưa vào repo, tài liệu, ADR, ảnh chụp màn hình công khai hay chat với AI. Dũng đặt vào biến môi trường ở mục 3 trên máy mình (file `.env.local` hoặc biến hệ thống, **không commit**), rồi báo "đã đặt xong".
4. **Đăng ký IPN URL:** tài liệu chính thức nói gửi IPN URL cho VNPay khi hoàn tất tích hợp ([trang thanh toán](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html)); trang quản trị merchant có thể có ô cấu hình IPN URL trong mục sửa terminal (`TerminalEdit`), theo tài liệu thư viện cộng đồng, *chưa kiểm chứng trực tiếp*. Làm theo cách nào có trên tài khoản, ghi lại vào ADR của T65. Vì phải đăng ký địa chỉ với VNPay, dùng **tên miền tunnel cố định** (mục 4), không dùng địa chỉ đổi mỗi lần chạy.

**Cần gửi thêm cho Dũng khi:** (a) thư VNPay khác mô tả trên (gửi ảnh chụp đã che `HashSecret`); (b) VNPay yêu cầu giấy tờ doanh nghiệp để cấp sandbox (khi đó dừng và hỏi Hiến, đừng tự nộp).

## 3. Biến môi trường (BE-2 đọc ở T65)

| Biến | Ví dụ giá trị (không phải khóa thật) | Ghi chú |
|---|---|---|
| `PAYMENT_GATEWAY` | `VNPAY` | Chọn cài đặt `PaymentGatewayClient`; đổi cổng chỉ đổi biến này và lớp cài |
| `PAYMENT_MERCHANT_CODE` | `<vnp_TmnCode>` | Mã website tại cổng |
| `PAYMENT_SECRET` | `<HashSecret>` | **Bí mật.** Ký và kiểm chữ ký |
| `PAYMENT_BASE_URL` | `https://sandbox.vnpayment.vn/paymentv2/vpcpay.html` | URL tạo thanh toán của sandbox |
| `PAYMENT_RETURN_URL` | `http://localhost:5173/checkout/result` | Trang FE nhận khách quay về; đổi theo đường dẫn FE thực tế ở T67 |
| `PAYMENT_IPN_URL` | `https://<tunnel-công-khai>/api/public/payment-gateway/callback` | Cổng gọi server-to-server; đường dẫn thật là `POST /api/public/payment-gateway/callback` ([`payment-v1`](./api/payment-v1.md)); thêm `/api/` vì BE có tiền tố `/api` |

## 4. IPN cần địa chỉ công khai

Cổng gọi IPN từ internet về BE, nên `localhost` không nhận được. Khi làm và demo trên máy cá nhân, dùng một tunnel có **tên miền cố định** (tên miền tĩnh của ngrok, hoặc Cloudflare Tunnel đặt tên), đặt địa chỉ đó làm `PAYMENT_IPN_URL` và đăng ký với VNPay (mục 2, bước 4). Tên miền đổi thì phải đăng ký lại. *Chưa chạy thử; BE-2 kiểm ở T65 và ghi kết quả vào ADR của T65.*

## 5. Điều BE-2 cần biết về VNPay (theo tài liệu sandbox)

- Chữ ký: sắp tên tham số tăng dần, nối thành chuỗi query rồi **HMAC-SHA512** với `HashSecret` (cổng còn hỗ trợ SHA256; dùng HMAC-SHA512).
- `vnp_Amount` = số tiền × 100. `vnp_CreateDate` theo `yyyyMMddHHmmss`, múi giờ GMT+7. `vnp_TxnRef` không trùng trong ngày (dùng mã giao dịch của hệ thống).
- IPN: cổng thử lại tối đa 10 lần, cách nhau 5 phút nếu BE không trả đúng phản hồi. BE xử lý **idempotent** theo mã giao dịch (BR-TG-07, `08` §C) và trả đúng định dạng phản hồi IPN mà tài liệu VNPay quy định (lớp tích hợp VNPay ánh xạ từ `CallbackAck`).
- **Cần kiểm ở T65:** theo mã mẫu công khai, VNPay gọi IPN bằng `GET` kèm tham số trên query, còn `payment-v1` khai báo `POST /public/payment-gateway/callback`. Chưa kiểm chứng với tài liệu chính thức. Nếu đúng là GET, BE-2 thêm bộ nhận GET trong lớp tích hợp VNPay (ánh xạ về `GatewayCallbackRequest`), ghi ADR, không đổi BR-TG-07.
- Thẻ test: ngân hàng NCB trong sandbox; số thẻ và OTP lấy từ trang tài liệu sandbox, không chép vào repo.
- Hoàn tiền qua cổng (T73): API hoàn tiền của VNPay có tài liệu riêng; chưa đọc. BE-2 đọc ở T73, nếu sandbox không hỗ trợ hoàn tiền thì ghi ADR và để hoàn tiền thủ công cho đơn online.

## 6. Nếu chưa có sandbox đến 20/11

Đề xuất (chưa được duyệt riêng): BE-2 cài `FakeGatewayClient` sau interface `PaymentGatewayClient` (tạo giao dịch, giả lập IPN thành công / thất bại / hết hạn) để làm trọn FSM, ST11, ST14 và test. Tiêu chí "thanh toán sandbox thành công" của T65 và E2E của T76 chỉ đạt khi có sandbox thật; ghi rõ trong `time.md`, không báo đạt.
