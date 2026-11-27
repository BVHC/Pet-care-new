# Biểu đồ tuần tự (kho nội dung cho mục 3.3.3 của `BAO CAO.docx`)

> Trạng thái: bản nháp 26/11/2026. Mười hình mới (TT.1, TT.3, TT.4, TT.6 đến TT.10, TT.12, TT.13) cùng ba hình đã có ở [`03-thiet-ke.md`](./03-thiet-ke.md) (TT.2, TT.5, TT.11). **Số hình `TT.n` là số tạm**: đánh lại theo chương khi xuất docx. Cú pháp và hình render ghi ở mục cuối.
>
> Cách vẽ: cùng mức trừu tượng với Hình 3.5 (người dùng, dịch vụ của module, module khác qua `api`, PostgreSQL; thêm bên ngoài chỉ khi use case có: SMTP, Redis, Gemini, cổng thanh toán). Mỗi nhánh `alt` gắn mã quy tắc nghiệp vụ. Thứ tự thao tác lấy từ phần mô tả đầu lớp dịch vụ trong mã nguồn ngày 26/11/2026; **chưa chạy thử từng luồng để quan sát**, nên đây là sơ đồ thiết kế đối chiếu với mã, không phải kết quả đo.

**Bảng. Ba hình đã có, dùng lại**

| Hình tạm | Use case | Hình hiện có | Vị trí |
|---|---|---|---|
| TT.2 | UC03 Đăng nhập | Hình 3.5 | `03-thiet-ke.md` mục 3.6 |
| TT.5 | UC48 Khám bệnh (hoàn tất lượt, khóa bệnh án) | Hình 3.6 | `03-thiet-ke.md` mục 3.7 |
| TT.11 | UC70 Thu tiền tại quầy | Hình 3.7 | `03-thiet-ke.md` mục 3.7 |

## TT.1. Đăng ký tài khoản (UC01, gồm UC02)

```mermaid
sequenceDiagram
    autonumber
    participant K as Khách vãng lai
    participant S as Dịch vụ đăng ký (identity)
    participant D as PostgreSQL
    participant Q as Hàng đợi thông báo (ST20)
    participant M as SMTP
    K->>S: POST đăng ký, kèm xác nhận 18 tuổi
    alt thiếu xác nhận, mật khẩu yếu, email đã dùng
        S-->>K: 400 (BR-TK-02, 03, 01)
    else hợp lệ
        S->>D: một transaction: tài khoản PENDING, hồ sơ khách, OTP đã băm (BR-TK-07), thư OTP
        S-->>K: chờ xác thực OTP
        Q->>M: ST20 gửi email chứa mã OTP
    end
    K->>S: POST xác thực OTP
    S->>D: khóa tài khoản PENDING, kiểm mã
    alt sai mã (BR-TK-05, 06)
        S-->>K: từ chối, ghi số lần sai, đủ 5 lần thì hủy mã
    else đúng mã
        S->>D: tài khoản thành ACTIVE
        S-->>K: SĐT trùng hồ sơ tại quầy thì hỏi liên kết (BR-TK-19)
    end
    Note over K,S: Đăng ký bằng Google (ADR-0152): vào thẳng ACTIVE, không OTP, chưa kiểm tay
```

**Hình TT.1. Chuỗi đăng ký và xác thực OTP.** *Nguồn: `RegistrationService.registerAccount`, `verifyAccount`; BR-TK-01 đến BR-TK-08; ADR-0009, ADR-0010, ADR-0011, ADR-0152. Việc gửi thư do ST20 làm sau, ngoài transaction đăng ký (mục 3.8).*

## TT.2. Đăng nhập và làm mới token (UC03): dùng lại Hình 3.5

Mã Mermaid nằm ở [`03-thiet-ke.md`](./03-thiet-ke.md) mục 3.6, không chép lại ở đây để khỏi lệch.

**Hình TT.2. Chuỗi đăng nhập và làm mới token xoay vòng (UC03).** *Nguồn: Hình 3.5 của `03-thiet-ke.md`; ADR-0003, ADR-0091; `docs/api/00-method.md` mục 3.1.*

## TT.3. Đặt lịch hẹn (UC39)

```mermaid
sequenceDiagram
    autonumber
    participant U as Khách hoặc lễ tân
    participant S as Dịch vụ đặt lịch (appointment)
    participant C as Chi nhánh và danh mục (api)
    participant D as PostgreSQL
    U->>S: POST đặt lịch: thú, dịch vụ, chi nhánh, ngày, khung giờ
    S->>S: kiểm phạm vi: khách là chủ thú, lễ tân đúng chi nhánh
    S->>D: khóa thú (advisory), rồi khóa chi nhánh
    S->>C: đọc thú, dịch vụ, giờ mở cửa, quota của khung
    S->>D: đếm lịch đang giữ chỗ trong khung và của thú
    alt vi phạm BR-LH-01 đến 05, 09, BR-KH-05, BR-TK-19 hoặc BR-LT-18
        S-->>U: 400 kèm mã quy tắc, không ghi gì
    else hợp lệ
        S->>D: INSERT lịch hẹn BOOKED, sinh mã lịch
        opt dịch vụ nhóm Khám
            S->>D: hủy nhắc tái khám còn chờ của thú (BR-TB-06)
        end
        S-->>U: trả lịch hẹn, không audit, không thông báo
    end
```

**Hình TT.3. Chuỗi đặt lịch hẹn (Lịch hẹn#1).** *Nguồn: `AppointmentBookingService.bookAppointment`; BR-LH-01 đến BR-LH-05, BR-LH-09, BR-KH-05, BR-TK-19, BR-LT-18; ADR-0045, ADR-0047. Quota (BR-LH-03) đọc sau khi đã khóa thú và chi nhánh, nên hai người đặt cùng khung không vượt chỗ.*

## TT.4. Tiếp nhận khách (UC44)

```mermaid
sequenceDiagram
    autonumber
    participant L as Lễ tân
    participant S as Dịch vụ tiếp nhận (visit)
    participant P as Lịch hẹn (AppointmentApi)
    participant O as Bán hàng (VisitOrderApi)
    participant D as PostgreSQL
    Note over L,S: Khách mới: tạo hồ sơ khách và thú trước bằng UC22, UC24 (extend)
    L->>S: POST tiếp nhận: thú, lịch hẹn hoặc dịch vụ, cờ cấp cứu
    S->>D: khóa thú FOR SHARE, đọc chi nhánh của lễ tân
    alt vi phạm BR-TN-01, 02, BR-CN-01, 05, BR-LH-01 hoặc BR-LT-18
        S-->>L: 404 hoặc 400 kèm mã quy tắc, không ghi gì
    else hợp lệ
        opt có lịch hẹn
            S->>P: checkIn
            P->>D: khóa lịch, BOOKED thành CHECKED_IN
        end
        S->>S: xếp hàng: cấp cứu, lịch hẹn theo giờ, walk-in (BR-TN-03, 04)
        S->>D: INSERT lượt Visit ở WAITING
        S->>O: openForVisit
        O->>D: INSERT Order OPEN kèm dòng dịch vụ tự sinh
        opt dịch vụ loại Khám
            S->>D: hủy nhắc tái khám còn chờ của thú
        end
        S-->>L: trả lượt tiếp nhận kèm mã Order
    end
```

**Hình TT.4. Chuỗi tiếp nhận khách (Visit#1).** *Nguồn: `VisitCheckInService.checkInVisit`; BR-TN-01 đến BR-TN-04, BR-CN-01, BR-CN-05; ADR-0058, ADR-0088; `docs/01` mục C (UC44 extend UC22). Mọi hệ quả liên module nằm trong một transaction.*

## TT.5. Hoàn tất lượt khám và khóa bệnh án (UC48): dùng lại Hình 3.6

Mã Mermaid nằm ở [`03-thiet-ke.md`](./03-thiet-ke.md) mục 3.7. Khi vẽ PNG, tên ba cột `AdmissionQueryApi`, `AppointmentApi`, `VisitOrderApi` được rút còn "(api)" để nhãn không bị cắt giữa từ.

**Hình TT.5. Chuỗi hoàn tất lượt khám và khóa bệnh án (UC48, Visit#5).** *Nguồn: Hình 3.6 của `03-thiet-ke.md`; `docs/06-module-contracts.md` mục 4.1; ADR-0069; ADR-0149 mục 21.*

## TT.6. Tiêm chủng (UC49)

```mermaid
sequenceDiagram
    autonumber
    participant V as Bác sĩ (VET)
    participant S as Dịch vụ tiêm chủng (visit)
    participant O as Bán hàng (VisitOrderApi)
    participant K as Kho (StockApi)
    participant D as PostgreSQL
    V->>S: POST ghi mũi tiêm: loại vaccine, mũi phác đồ, sản phẩm
    S->>D: khóa thú FOR SHARE, khóa advisory liều theo thú, khóa lượt
    S->>S: kiểm người phụ trách và lượt đang IN_PROGRESS
    S->>D: đọc loại vaccine, mũi trong phác đồ, sản phẩm FOR SHARE, tồn khả dụng
    alt sai phác đồ, sai loại sản phẩm hoặc hết tồn (BR-KB-04, BR-SP-07)
        S-->>V: 400, không ghi gì
    else hợp lệ
        S->>O: addVaccineLine
        O->>D: thêm dòng vaccine vào Order của lượt
        S->>K: lockFefoLot
        K->>D: khóa lô còn hạn gần nhất
        S->>D: INSERT mũi tiêm kèm ngày tái chủng kế tiếp
        S->>K: issueForVaccination
        K->>D: trừ lô, ghi biến động kho
        S->>D: mũi cũ cùng loại thành bị thay thế, hủy Care Task tái chủng cũ (BR-TB-03)
        S-->>V: trả mũi tiêm, kèm cảnh báo nếu dưới tuổi tối thiểu
    end
```

**Hình TT.6. Chuỗi ghi nhận mũi tiêm.** *Nguồn: `VaccinationRecordService.record`; BR-KB-04, BR-KO-05, BR-TB-03; ADR-0074. Vaccine được trừ kho ngay lúc ghi mũi, không đợi thu tiền (BR-BH-04). Khóa advisory 1004 theo thú giữ cho hai bác sĩ không ghi trùng liều.*

## TT.7. Nhận và trả thú lưu trú (UC59)

```mermaid
sequenceDiagram
    autonumber
    participant L as Lễ tân
    participant S as Dịch vụ lưu trú (boarding)
    participant O as Bán hàng (sales)
    participant D as PostgreSQL
    Note over L,D: Nhận thú (chuyển 3 của máy trạng thái Đặt chỗ)
    L->>S: POST nhận thú: cân nặng, chuồng, tình trạng
    S->>D: khóa thú, chi nhánh, đặt chỗ, chuồng
    alt vi phạm BR-LT-17, 06, 04, 05 hoặc 08
        S-->>L: 409 hoặc 400, không ghi gì
    else đủ điều kiện: tiêm đủ mũi bắt buộc, chuồng đúng loại và trống
        S->>D: đặt chỗ CHECKED_IN, chuồng OCCUPIED, ghi cân nặng và phiếu nhận
        S-->>L: trả đặt chỗ
    end
    Note over L,D: Trả thú (chuyển 9 và 10 của máy trạng thái Đặt chỗ)
    L->>S: POST bắt đầu trả thú
    S->>O: mở Order lưu trú PENDING theo số đêm
    S-->>L: số đêm và số tiền
    L->>O: thu tiền (include UC70, Hình TT.11)
    L->>S: POST giao thú
    alt Order lưu trú chưa PAID
        S-->>L: 400 BR-LT-09
    else đã PAID
        S->>D: đặt chỗ CHECKED_OUT, chuồng AVAILABLE, đóng Care Task đón quá hạn
        S-->>L: trả đặt chỗ
    end
```

**Hình TT.7. Chuỗi nhận và trả thú lưu trú.** *Nguồn: `BoardingCheckInService.checkIn`, `BoardingCheckoutService.startCheckout`, `handOver`; BR-LT-04 đến BR-LT-09, BR-LT-17; ADR-0085, ADR-0086, ADR-0149. Thu tiền trước, giao thú sau, không ngoại lệ (BR-LT-09).*

## TT.8. Đặt hàng online (UC62)

```mermaid
sequenceDiagram
    autonumber
    participant K as Khách hàng
    participant S as Dịch vụ đặt hàng online (shop)
    participant I as Kho (inventory)
    participant D as PostgreSQL
    K->>S: GET danh sách chi nhánh xử lý và báo giá (chỉ đọc)
    K->>S: POST đặt hàng: chi nhánh, địa chỉ giao
    S->>D: khóa dòng giỏ của khách, đọc chi nhánh ACTIVE và địa chỉ
    S->>D: khóa sản phẩm FOR SHARE theo id tăng dần, chốt giá
    S->>I: lockAvailableQuantity, một câu cho mọi lô của mọi sản phẩm
    alt có dòng thiếu hàng
        S-->>K: 400 BR-BH-08, chưa ghi gì
    else đủ hàng
        S->>D: sinh hai mã chứng từ
        S->>D: INSERT Order và dòng Order (sales), INSERT đơn online kèm phí vận chuyển (ST09)
        S->>I: reserve, giữ hàng theo lô
        S->>D: xóa đúng các dòng giỏ đã đặt, ghi thư ONLINE_ORDER_PLACED vào hàng đợi
        S-->>K: đơn PENDING_PAYMENT, chờ thanh toán (UC63)
    end
```

**Hình TT.8. Chuỗi đặt hàng online từ giỏ.** *Nguồn: `OnlineOrderService.place`; BR-BH-08, BR-BH-09, BR-SP-08; ADR-0144. Đã cài đặt và nối giao diện với backend thật (hợp đồng `shop` #1 đến #18, tình trạng 25/11/2026). Không audit (khách tự phục vụ).*

## TT.9. Thanh toán online (UC63): đặc tả, chưa cài đặt

```mermaid
sequenceDiagram
    autonumber
    participant K as Khách hàng
    participant S as Dịch vụ thanh toán (payment)
    participant G as Cổng thanh toán (S02, VNPay)
    participant D as PostgreSQL
    K->>S: POST bắt đầu thanh toán đơn PENDING_PAYMENT
    S->>D: kiểm đơn chưa quá hạn, chưa có giao dịch PENDING (BR-TG-06)
    S->>D: INSERT giao dịch PENDING, hạn 15 phút
    S->>G: tạo phiên thanh toán
    G-->>S: địa chỉ chuyển hướng
    S-->>K: địa chỉ chuyển hướng
    K->>G: nhập thông tin và thanh toán tại cổng
    G->>S: callback công khai kèm chữ ký
    alt chữ ký sai
        S-->>G: 400, chỉ ghi log (BR-TG-07)
    else chữ ký đúng, số tiền lệch
        S->>D: giao dịch FAILED, thêm mục đối soát, đơn không đổi (BR-TG-08)
    else chữ ký đúng, số tiền khớp
        S->>D: giao dịch SUCCEEDED, đơn PAID, đổi hàng giữ thành xuất kho FEFO, cùng một transaction (BR-BH-09)
    end
    Note over S,G: Callback gọi lại cùng kết quả không đổi gì (idempotent). Quá 15 phút chưa có kết quả thì ST11 hỏi cổng rồi mới chuyển EXPIRED (BR-TG-09)
```

**Hình TT.9. Chuỗi thanh toán online qua cổng (UC63): đặc tả, chưa cài đặt.** *Nguồn: `docs/08` mục C (Giao dịch#1, #2, #3; BR-TG-06 đến BR-TG-11, máy trạng thái #15); `docs/payment-gateway-setup.md`. **Đặc tả, chưa cài đặt tại ngày 25/11/2026**: ba thao tác bắt đầu thanh toán, tra trạng thái giao dịch và callback chưa có handler, ST11 chưa có (Bảng S.2). Phần đối soát (ST14) đã có mã nhưng không nằm trong hình.*

## TT.10. Bán hàng và chốt Order tại quầy (UC66)

```mermaid
sequenceDiagram
    autonumber
    participant L as Lễ tân
    participant S as Dịch vụ Order tại quầy (sales)
    participant I as Kho (StockQueryApi)
    participant D as PostgreSQL
    L->>S: POST tạo Order bán lẻ cho một khách
    S->>D: kiểm khách tồn tại, sinh mã, INSERT Order OPEN của chi nhánh
    loop mỗi dòng hàng hoặc dịch vụ
        L->>S: POST thêm dòng
        S->>D: khóa dòng Order, chốt đơn giá lúc thêm (BR-BH-03)
        S->>I: đọc tồn khả dụng của dòng hàng
        S-->>L: dòng đã thêm, thiếu hàng chỉ cảnh báo (BR-BH-04), thuốc kê đơn bị chặn (BR-SP-01)
    end
    alt chốt Order
        L->>S: POST chốt Order
        S->>D: khóa dòng Order, phải OPEN và có ít nhất một dòng (BR-BH-01)
        S->>D: Order OPEN thành PENDING
        S-->>L: chờ thu tiền, chưa giao hàng (BR-BH-06, tiếp UC70)
    else hủy Order chưa chốt
        L->>S: POST hủy Order
        S->>D: Order thành CANCELLED, giữ dòng làm lịch sử, kho không đổi
    end
```

**Hình TT.10. Chuỗi bán lẻ tại quầy (Order#2, #5, #7).** *Nguồn: `RetailOrderService`, `OrderLineService`; BR-BH-01, BR-BH-03, BR-BH-04, BR-BH-06, BR-SP-01; ADR-0044, ADR-0051. Kho chỉ cảnh báo khi thêm dòng; trừ kho xảy ra lúc thu tiền (Hình TT.11).*

## TT.11. Thu tiền tại quầy (UC70): dùng lại Hình 3.7

Mã Mermaid nằm ở [`03-thiet-ke.md`](./03-thiet-ke.md) mục 3.7.

**Hình TT.11. Chuỗi thu tiền Order tại quầy (UC70, Order#8).** *Nguồn: Hình 3.7 của `03-thiet-ke.md`; `docs/06-module-contracts.md` mục 5; `docs/architecture/concurrency-locking.md`; ADR-0064.*

## TT.12. Hỏi đáp chatbot (UC90)

```mermaid
sequenceDiagram
    autonumber
    participant K as Khách
    participant S as Dịch vụ chatbot (ai)
    participant D as PostgreSQL (pgvector)
    participant G as Gemini
    K->>S: POST câu hỏi kèm tối đa 3 câu trước
    alt quá hạn mức theo IP (Redis: 10 câu mỗi phút, 60 mỗi giờ) hoặc câu quá 500 ký tự
        S-->>K: 429 hoặc 400
    else hợp lệ
        S->>S: che email, SĐT, số giấy tờ
        alt cấp cứu, bệnh án của thú, ý định cá nhân: không gọi Gemini
            S-->>K: EMERGENCY, MEDICAL_REFUSAL, NEED_LOGIN hoặc PERSONAL
        else đi tiếp
            S->>G: embedding câu hỏi
            S->>D: tìm 5 đoạn gần nhất, tài liệu PUBLISHED
            alt không đoạn nào đạt cosine 0,60, công tắc kiến thức chung tắt
                S-->>K: OUT_OF_SCOPE, câu mẫu, không gọi mô hình
            else có đoạn đạt ngưỡng, hoặc công tắc kiến thức chung bật
                S->>G: sinh câu trả lời từ các đoạn
                S->>S: hậu kiểm: cờ ngoài phạm vi, lộ lời nhắc, liều thuốc
                S-->>K: ANSWERED kèm nguồn, hoặc OUT_OF_SCOPE, MEDICAL_REFUSAL
            end
        end
    end
    S->>D: ghi nhật ký đã che dữ liệu
    Note over S,G: Gemini lỗi, quá 15 giây hoặc hết hạn mức: UNAVAILABLE, HTTP 200 (BR-AI-10)
```

**Hình TT.12. Chuỗi hỏi đáp của chatbot.** *Nguồn: `docs/07` mục 5.2; BR-AI-03 đến BR-AI-10, BR-AI-12; ADR-0136, ADR-0141, ADR-0157; thứ tự lớp chặn đã nêu ở Hình 6.2 và Bảng 6.2 của `06-phan-he-ai.md`. Hình bỏ các công tắc nghiệp vụ, lời chào, ngữ cảnh thú và chế độ mở (mặc định tắt) và bước phân loại câu hỏi cá nhân mơ hồ của chat đăng nhập (công tắc `intent_llm_enabled`, mặc định bật, ADR-0157), nên "ý định cá nhân" trong hình là loại nhận ra bằng luật từ khóa; công tắc kiến thức chung bật hay tắt là trạng thái cấu hình, đọc lại ở ngày chốt (Bảng S.5).*

## TT.13. Quản lý tri thức chatbot (UC93)

```mermaid
sequenceDiagram
    autonumber
    participant Q as Quản lý chuỗi (A04)
    participant S as Dịch vụ tri thức (ai)
    participant D as PostgreSQL
    participant J as Tác vụ ST22
    participant G as Gemini
    Q->>S: POST tạo tài liệu
    S->>D: INSERT DRAFT, ghi audit
    Q->>S: POST xuất bản
    alt thiếu trường bắt buộc (BR-AI-14)
        S-->>Q: 400, vẫn DRAFT
    else đủ trường
        S->>D: PUBLISHED, chỉ mục PENDING, ghi audit
    end
    J->>D: chọn tài liệu chưa nạp phiên bản hiện tại
    J->>G: chia đoạn, embedding theo lô nhỏ (BR-AI-15)
    alt Gemini lỗi
        J->>D: chỉ mục FAILED, thử lại lần chạy sau
    else thành công
        J->>D: một transaction: đoạn mới, xóa đoạn cũ, INDEXED (BR-AI-16)
    end
    Q->>S: POST lưu trữ
    S->>D: ARCHIVED, ghi audit
    Note over S,D: Truy hồi lọc status ngay trong SQL, ngừng phục vụ ngay khi commit
```

**Hình TT.13. Vòng đời tài liệu tri thức: tạo, xuất bản, nạp chỉ mục, lưu trữ.** *Nguồn: `docs/07` mục 5.3, mục 9 (máy trạng thái #13, các chuyển #1, #2, #4); BR-AI-14 đến BR-AI-17; ADR-0130. Hình không vẽ các đường do ST22 tự chuyển trạng thái (chuyển #5, #7, #8).*

## Kiểm cú pháp và render

Đo ngày 26/11/2026 bằng Mermaid 11.17.2 chạy trong Chrome: **13/13 khối qua trình phân tích và dựng được SVG**. PNG ở [`hinh/TT.1.png` đến `hinh/TT.13.png`](./hinh/), tỷ lệ 2x. **Đã xem cả 13 hình PNG sau lần dựng cuối** (nhãn đọc được, không bị cắt; còn vài nhãn dài trùm lên đường sống của cột khác, vẫn đọc được).

Thiết lập dựng PNG, khác bản xem trực tiếp trên GitHub hoặc VS Code:

- Chủ đề `neutral` (đen trắng, hợp bản in), chữ Times New Roman 14 px, **không đánh số tự động** (số thứ tự đè lên nhãn dài).
- Chiều rộng mỗi hình chia theo số cột (khoảng 800 px) để khi vào trang Word rộng 15,5 cm, cỡ chữ ra khoảng **7,5 đến 7,7 pt** và hình cao không quá 21 cm. Cỡ chữ này nhỏ: cột nhiều hơn hoặc nhãn dài hơn thì chữ nhỏ thêm, nên giữ mỗi hình dưới sáu cột và dưới mười hai thông điệp.
- TT.5 (hình cũ có sáu cột): khi vẽ PNG, tên `AdmissionQueryApi`, `AppointmentApi`, `VisitOrderApi` rút còn "(api)".
- Mermaid coi `#` là mã thực thể: không dùng `#` trong nhãn (viết "chuyển 3", không viết "Đặt chỗ#3").

Tập lệnh dựng (ngoài kho): `D:/Do-an/.c2-tmp/t86/seq/render.mjs` (cần Chrome; Mermaid cài trong thư mục đó). Chạy lại sau khi sửa khối Mermaid rồi chép PNG vào `hinh/`. Hình trong `BAO CAO.docx` là bản chèn một lần (`insert_figs.py`), không tự cập nhật.

**Chưa làm:** chưa chạy thử từng luồng để quan sát (xem đầu tệp); các cờ cấu hình của chatbot (TT.12) và trạng thái cài đặt (TT.9) phải đọc lại ở ngày chốt.
