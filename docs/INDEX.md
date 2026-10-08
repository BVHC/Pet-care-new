# Pet Care Ecosystem — Index

> Mục lục cho agent. Đọc file này trước, tra **Bảng 2** lấy vị trí, rồi chỉ đọc đúng đoạn cần (ví dụ `05-erd.md` L77–198) thay vì cả file.
> Số dòng `L…` khớp bản **v16**. Nếu lệch, tìm theo **tiêu đề mục** hoặc theo **mã định danh** (Bảng 3).

## 1. Các file

Các file tham chiếu nhau bằng tên gọi tắt (cột 1).

| Gọi tắt | File | Trả lời câu hỏi | Cấu trúc · quy mô | Ưu tiên khi mâu thuẫn |
|---|---|---|---|---|
| business-rules | `02-business-rules.md` | Quy tắc là gì, vi phạm xử lý ra sao | Mục `## <số>. <Module> (<MÃ>)`, mỗi rule một dòng `BR-<MÃ>-<số>`. 139 mã (134 hiệu lực). Module ✅ đã chốt (TK, QT), 📝 bản nháp | 1 (cao nhất) |
| state-machine | `03-state-machines.md` | Trạng thái nào, chuyển khi nào, kéo theo gì | 10 đối tượng, mục `## <số>. <Đối tượng>`; chuyển trạng thái đánh số `#1…`, tham chiếu dạng `Visit#5` | 2 |
| domain-model | `04-domain-model.md` | Model nào, loại gì, quan hệ, rule chi phối | 11 nhóm `## <số>. <Nhóm> (<MÃ>)`, 52 model, mỗi model một dòng bảng | 3 |
| erd | `05-erd.md` | Bảng, cột, kiểu, khóa, ràng buộc, index | 55 bảng, mỗi bảng một mục `` ### `<bảng>` — <LOẠI> · SM #n `` | 4 |
| use-case | `01-business-operations.md` | Ai làm gì | 11 actor, UC01–UC89 (85 hiệu lực), 21 tác vụ hệ thống ST01–ST21 | Phải khớp business-rules |

**Actor:** A01 GUEST · A02 CUSTOMER · A03 ADMIN · A04 SUPER_MANAGER · A05 BRANCH_MANAGER · A06 RECEPTIONIST (kiêm thu ngân) · A07 VET · A08 CARETAKER · S01 System · S02 Payment Gateway (tầng 3) · S03 Email/Push.

**Mục dùng chung:**

| Nội dung | Vị trí |
|---|---|
| Quy ước mã rule, `[CFG]` | business-rules L6–9 |
| Quy ước trạng thái, whitelist, `SYS ←`, bảng 10 đối tượng → `bảng.cột` | state-machine L5–27 |
| Chuỗi tác động liên đối tượng (sự kiện → hệ quả) | state-machine L237–253 (*Phụ lục*) |
| Loại model ROOT/PART/REF/LOG; 10 nguyên tắc xuyên suốt | domain-model L7–30 |
| Nguồn số liệu báo cáo | domain-model L143–147 |
| Kiểu dữ liệu, cột chung, quy tắc xóa | erd L8–41 |
| Enum tiếng Việt → mã ASCII trong DB/code | erd L28–37 |
| Sơ đồ ER luồng chính (mermaid) | erd L45–73 |
| Đối chiếu model → bảng | erd L1043–1073 |
| Nhật ký quyết định v15–v16 và migration V1 | erd L1077–1092 |
| Danh sách actor | use-case L7–21 |
| Tác vụ hệ thống ST01–ST21 | use-case L208–234 |
| Quan hệ include / extend | use-case L238–251 |

## 2. Bản đồ module

Tầng: **1** làm đầy đủ · **2** bản mỏng · **3** chỉ giữ trên sơ đồ use case, không có rule/model. `—` là file đó không có nội dung riêng cho module. Cột business-rules ghi `(số mã)`.

| Mã | Module (từ khóa tìm kiếm) | Tầng | business-rules | use-case | state-machine | domain-model | erd | Tác vụ hệ thống |
|---|---|---|---|---|---|---|---|---|
| TK | Tài khoản & xác thực (auth, login, OTP, mật khẩu, liên kết hồ sơ, khôi phục tài khoản) | 1 | L13–36 (20) ✅ | UC01–07 · L27–37 | #1 Tài khoản · L31–51 | L34–44 (chung QT) | L77–198 (chung QT) | ST01, ST02 |
| QT | Quản trị hệ thống (admin, phân quyền, khóa, audit, cấu hình) | 1 | L40–59 (16) ✅ | UC08–11 · L39–46 | #1 Tài khoản · L31–51 | L34–44 | L77–198 | — |
| CN | Chi nhánh (giờ mở cửa, ngày nghỉ, cấp cứu ngoài giờ) | 1 | L61–69 (5) | UC12–14 · L48–54 | #2 Chi nhánh · L53–66 | L46–55 | L199–284 | — |
| CK | Thông tin công khai (trang public, hiển thị giá) | 1 | L71–79 (5) | UC15–21 · L56–66 | — | L130 (PageContent) | L947–955 (`page_contents`) | — |
| KH | Khách hàng & thú cưng (customer, pet, SĐT, tra cứu, nghi trùng, cân nặng, chuyển chủ) | 1 | L81–96 (10) | UC22–27 · L68–77 | — | L57–64 | L285–357 | ST19 |
| SP | Sản phẩm & dịch vụ (product, service, loại vaccine, phác đồ, loại chuồng) | 1 | L98–110 (7) | UC28–33 · L79–88 | — | L66–75 | L358–443 | — |
| LH | Lịch hẹn (appointment, quota, khung giờ, no-show, hạn chế đặt) | 1 | L116–133 (12) | UC39–43 · L100–108 | #3 Lịch hẹn · L68–88 | L77–82 | L444–495 | ST03, ST05 |
| TN | Tiếp nhận & hàng đợi (check-in, visit, queue, gán nhân viên) | 1 | L135–149 (9) | UC44–47 · L110–117 | #4 Visit · L90–109 | L84–93 (chung KB) | L496–607 (chung KB) | — |
| KB | Khám & điều trị (bệnh án, kê đơn, tiêm chủng, tái khám) | 1 | L151–162 (6) | UC48–53 · L119–128 | #4 Visit · L90–109 | L84–93 | L496–607 | ST04, ST06 |
| LT | Lưu trú (boarding, chuồng, nhận/trả thú, nhật ký chăm sóc) | 2 | L164–181 (12) | UC54–60 · L130–140 | #6 Đặt chỗ · L136–160; #7 Chuồng · L162–179 | L95–102 | L608–704 | ST05, ST15 |
| BH | Bán hàng & đơn hàng (order, dòng order, trừ kho khi thu) | 1 | L183–194 (6) | UC61–69 · L142–154 | #5 Order · L111–134 | L104–111 (chung TG) | L705–805 (chung TG) | ST06, ST13 |
| TG | Thu ngân (payment, ca thu ngân, đối soát) | 1 | L196–206 (5) | UC70–72 · L156–162 | #8 Ca thu ngân · L181–199 | L104–111 | L705–805 | ST13 |
| KO | Kho (tồn, lô, FEFO, nhập kho, điều chỉnh) | 2 | L208–219 (8) | UC73–79 · L164–174 | #9 Phiếu nhập · L201–215 | L113–122 | L806–917 | ST06, ST08 |
| BV | Bài viết (article, chuyên mục) | 2 | L221–230 (4) | UC80–82 · L176–182 | — (vòng đời ở BR-BV-02) | L124–129 | L918–946 | — |
| DG | Feedback & khiếu nại | 2 | L232–241 (4) | UC83–86 · L184–191 | — (vòng đời ở BR-DG-04) | L131 (Feedback) | L956–978 | — |
| TB | Chăm sóc KH & thông báo (nhắc tái chủng/tái khám, Care Task, notification) | 1 | L243–254 (6) | UC87–88 · L193–198 | #10 Care Task · L217–233 | L133–139 | L979–1042 | ST04, ST18, ST19, ST20 |
| BC | Báo cáo | 2 | L256–263 (4) | UC89 · L200–204 | — | L147 (không có model) | — (tính từ bảng nghiệp vụ) | — |
| NS | Nhân sự (ca làm, nghỉ phép) | 3 | L112–114 | UC34–38 · L90–98 | — | — | — | — |
| AI | Trí tuệ nhân tạo & đề xuất thông minh (chatbot RAG+LLM, recommendation engine) | Giai đoạn 2 | — | — | — | — | — |

**Tầng 3 — Giai đoạn 2 (11–12/2026):** UC13, UC27, UC32, UC34–38, UC43, UC50, UC51, UC55, UC56, UC61–65, UC68, UC69, UC77–79, UC80, UC82, UC85, UC86 · ST07, ST09–ST12, ST14, ST16, ST17, ST21. Quy tắc nghiệp vụ, FSM, model và API được đặc tả bổ sung đầu Giai đoạn 2.

## 3. Tra theo mã định danh

| Gặp mã | Định nghĩa nằm ở | Cách tìm |
|---|---|---|
| `BR-<MÃ>-<số>` | business-rules, mục của module `<MÃ>` | tìm `\| BR-TK-19 \|` |
| `UC<số>` / `ST<số>` / `A<số>` | use-case mục A / mục B / mục 0 | tìm `\| UC44 \|`, `\| ST13 \|`, `\| A06 \|` |
| `<Đối tượng>#<số>` (`Visit#5`, `Đặt chỗ#3`) | state-machine, mục của đối tượng, dòng `\| 5 \|` của bảng chuyển trạng thái | cột state-machine ở Bảng 2 |
| `SM #<số>` (trong domain-model, erd) | state-machine mục `## <số>.` | |
| Tên model (PascalCase) | domain-model; tên bảng ở erd L1043–1073 | |
| Tên bảng (snake_case) | erd, mục `` ### `<bảng>` `` | |
| `ROOT` / `PART` / `REF` / `LOG` | loại model (domain-model L9–13) | |
| `[CFG]` | tham số cấu hình; số ghi kèm là mặc định, ADMIN đổi qua UC10 (BR-QT-13) | |
| `[ERD]` | quyết định riêng của erd, tầng trên chưa quy định | |
| `🆕` | rule/đối tượng đề xuất, chưa duyệt riêng | |
| `(v16)` | thay đổi ở v16: email là định danh duy nhất, SĐT không duy nhất, không lưu CCCD (erd *Nhật ký quyết định* mục 5) | |
| `~~…~~` | đã bỏ, giữ mã để không lệch số: UC18, UC19, UC20, UC41; BR-CK-04, BR-CK-05, BR-KH-09, BR-KB-05, BR-KO-08 | bỏ qua (riêng BR-KB-05 vẫn ghi nguyên tắc "chỉ tin mũi tiêm tại hệ thống") |

## 4. Cách tra một chủ đề

1. Bảng 2 → dòng module → đọc business-rules để nắm quy tắc.
2. use-case → actor và UC liên quan.
3. state-machine → trạng thái, điều kiện, hệ quả (nếu module có SM).
4. domain-model → erd → model và bảng.
5. Rule trỏ sang module khác (ví dụ BR-TK-19 → BR-KH-01, BR-KH-10) → tra tiếp theo Bảng 3.

Câu hỏi chạm nhiều đối tượng (ví dụ "hoàn tất lượt khám kéo theo gì") → đọc *Phụ lục* state-machine L237–253 trước.

## 5. Tài liệu khác trong `docs/`

| Cần | Mở |
|---|---|
| Quy ước code backend (package, layering/DTO, naming, exception, FSM, validation, transaction, logging/audit, testing) | `convention/backend/01…09-*.md` |
| Hợp đồng FE ↔ BE (HTTP) | `api/INDEX.md` — 12 contract v1, sinh từ `api/generator/` |
| Hợp đồng giữa module BE | `06-module-contracts.md` |
| Quyết định kỹ thuật đã chốt (ADR) | `adr/INDEX.md` — ADR-0001 ghi audit, 0002 IP client sau proxy, 0003 xác thực JWT + phiên / phân quyền / phạm vi chi nhánh, 0004 đọc [CFG], 0005 path public luôn ẩn danh, 0006 phạm vi dữ liệu của khách (CUSTOMER), 0007 job định kỳ `@Scheduled`, 0008 dọn phiên hết hạn, 0009 băm mật khẩu & OTP, 0010 nhập sai OTP vẫn lưu bộ đếm, 0011 khóa dòng `accounts` cho luồng OTP, 0012 worker ST20 gửi email từ `notification_outbox`, 0013 ST02 dọn tài khoản `PENDING` quá hạn, 0014 ST20 giao thông báo kênh IN_APP, 0015 `created_at`/`updated_at` theo bean `Clock`, 0016 dọn `notification_outbox` đã xử lý, 0017 luồng NORMAL ST20 gửi theo lô một kết nối SMTP, 0018 index cho FK trỏ tới `accounts`, 0019 đăng nhập sai — bộ đếm, khóa tạm, BCrypt ngoài transaction, audit, 0020 tắt Open Session In View, 0021 đăng xuất — thứ tự khóa `accounts → sessions`, ghi `last_seen_at` chỉ khi phiên còn hiệu lực |
| Sơ đồ | `diagrams/INDEX.md` *(đang trống)* |
| Trạng thái kỹ thuật thực tế của codebase | `architecture/system-overview.md` |
| Nợ code đã biết (placeholder, việc để lại, giả định tạm) | `dept/INDEX.md` — đọc trước khi động vào module liên quan |

`convention/backend/` đã cập nhật theo v16 (mục lục `convention/backend/INDEX.md`, gồm bảng các điểm còn TBD: tên command, cách trả cảnh báo, chiến lược khóa đồng thời).
