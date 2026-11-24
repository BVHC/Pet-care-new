# Pet Care Ecosystem — Index

> Mục lục cho agent. Đọc file này trước, tra **Bảng 2** lấy vị trí, rồi chỉ đọc đúng đoạn cần (ví dụ `05-erd.md` L77–198) thay vì cả file.
> Số dòng `L…` khớp bản **v16**. Nếu lệch, tìm theo **tiêu đề mục** hoặc theo **mã định danh** (Bảng 3).
> **5/11/2026 (T54):** tầng 3 đã được duyệt (Hiến, nhóm trưởng). Quy tắc, FSM, model, bảng và API của Nhân sự ở `02`–`06`; AI ở [`07`](./07-ai-subsystem.md); phần BE-2 ở [`08`](./08-tang3-be2-spec.md); phần BE-1 ở [`09`](./09-tang3-be1-spec.md); `02`–`05` có mục trỏ tới `08`, `09`; interface chéo gộp ở `06` §6b. Các số tổng bên dưới (10 FSM / 66 chuyển, 55 bảng, 134 BR, 232 endpoint) là của bản v16 / GĐ1; tầng 3 thêm 15 FSM (#11–#21, #23–#26), 33 bảng, 131 endpoint (xem `api/INDEX.md`). Số dòng `L…` của `02`–`05` đã dịch theo các file hiện tại; riêng "Đối chiếu model → bảng" của `erd` vốn đã lệch từ trước.
> **8/11/2026 (ADR-0122):** bỏ khiếu nại (UC85, UC86, ST16, ST17, FSM #26, BR-DG-05…09 bản khiếu nại, module `complaint`, 12 endpoint); feedback không còn trạng thái và chỉ giữ 30 ngày (BR-DG-05 mới, ST24). Các số tầng 3 ở dòng trên chưa trừ: FSM #11–#25, 31 bảng, 129 endpoint (ADR-0123 thêm 2 endpoint cho workforce).
> **9/11/2026 (ADR-0125, ADR-0124):** bỏ phẫu thuật (UC50, BR-KB-08…11, FSM #23, module `surgery`, 8 endpoint): tầng 3 còn 26 UC, FSM 14 (#11–#22, #24, #25), 30 bảng, 121 endpoint. Nội trú (UC55, UC56) chốt cách tính ngày / tiền, chuồng, y lệnh, theo dõi (ADR-0124).
> **10/11/2026 (gộp việc bỏ khám tại nhà với ADR-0122 và ADR-0125):** tầng 3 còn **22 UC** (BE-1 7: UC34–38, UC55, UC56; BE-2 15), **8 ST** (ST07, ST09–ST12, ST14, ST21, ST24), FSM 13 (#11–#21, #24, #25), 30 bảng, **113 endpoint** (tổng 344 = 231 GĐ1 + 113 tầng 3; `check-contracts` PASS 24 file). Các số ở ba dòng trên là số tại thời điểm từng quyết định.
> **14/11/2026 (đếm lại theo yaml, ADR-0148):** tầng 3 là **115 endpoint**, tổng **346** = 231 GĐ1 + 115 (identity có 39: thêm refresh #39; `ai` có 14: thêm import #14; `workforce` có 17: thêm `GET /me/shift-registrations/{weekStart}`).

## 1. Các file

Các file tham chiếu nhau bằng tên gọi tắt (cột 1).

| Gọi tắt | File | Trả lời câu hỏi | Cấu trúc · quy mô | Ưu tiên khi mâu thuẫn |
|---|---|---|---|---|
| business-rules | `02-business-rules.md` | Quy tắc là gì, vi phạm xử lý ra sao | Mục `## <số>. <Module> (<MÃ>)`, mỗi rule một dòng `BR-<MÃ>-<số>`. 139 mã (134 hiệu lực). Module ✅ đã chốt (TK, QT), 📝 bản nháp | 1 (cao nhất) |
| state-machine | `03-state-machines.md` | Trạng thái nào, chuyển khi nào, kéo theo gì | 10 đối tượng, mục `## <số>. <Đối tượng>`; chuyển trạng thái đánh số `#1…`, tham chiếu dạng `Visit#5` | 2 |
| domain-model | `04-domain-model.md` | Model nào, loại gì, quan hệ, rule chi phối | 11 nhóm `## <số>. <Nhóm> (<MÃ>)`, 52 model, mỗi model một dòng bảng | 3 |
| erd | `05-erd.md` | Bảng, cột, kiểu, khóa, ràng buộc, index | 55 bảng, mỗi bảng một mục `` ### `<bảng>` — <LOẠI> · SM #n `` | 4 |
| use-case | `01-business-operations.md` | Ai làm gì | 11 actor, UC01–UC94 (85 hiệu lực), 24 mã tác vụ ST01–ST24 (ST16, ST17 bỏ) | Phải khớp business-rules |

**Actor:** A01 GUEST · A02 CUSTOMER · A03 ADMIN · A04 SUPER_MANAGER · A05 BRANCH_MANAGER · A06 RECEPTIONIST (kiêm thu ngân) · A07 VET · A08 CARETAKER · S01 System · S02 Payment Gateway (tầng 3) · S03 Email/Push.

**Mục dùng chung:**

| Nội dung | Vị trí |
|---|---|
| Quy ước mã rule, `[CFG]` | business-rules L6–9 |
| Quy ước trạng thái, whitelist, `SYS ←`, bảng 10 đối tượng → `bảng.cột` | state-machine L5–27 |
| Chuỗi tác động liên đối tượng (sự kiện → hệ quả) | state-machine L280–-838 (*Phụ lục*) |
| Loại model ROOT/PART/REF/LOG; 10 nguyên tắc xuyên suốt | domain-model L7–30 |
| Nguồn số liệu báo cáo | domain-model L159–163 |
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
| TK | Tài khoản & xác thực (auth, login, OTP, mật khẩu, liên kết hồ sơ, khôi phục tài khoản) | 1 | L13–36 (20) ✅ | UC01–07 · L27–37 | #1 Tài khoản · L33–53 | L34–44 (chung QT) | L77–198 (chung QT) | ST01, ST02 |
| QT | Quản trị hệ thống (admin, phân quyền, khóa, audit, cấu hình) | 1 | L40–59 (16) ✅ | UC08–11 · L39–46 | #1 Tài khoản · L33–53 | L34–44 | L77–198 | — |
| CN | Chi nhánh (giờ mở cửa, ngày nghỉ, cấp cứu ngoài giờ) | 1 | L61–69 (5) | UC12–14 · L48–54 | #2 Chi nhánh · L55–68 | L46–55 | L199–284 | — |
| CK | Thông tin công khai (trang public, hiển thị giá) | 1 | L71–79 (5) | UC15–21 · L56–66 | — | L130 (PageContent) | L947–955 (`page_contents`) | — |
| KH | Khách hàng & thú cưng (customer, pet, SĐT, tra cứu, nghi trùng, cân nặng, chuyển chủ) | 1 | L81–96 (10) | UC22–27 · L68–77 | — | L57–64 | L285–357 | ST19 |
| SP | Sản phẩm & dịch vụ (product, service, loại vaccine, phác đồ, loại chuồng) | 1 | L98–110 (7) | UC28–33 · L79–88 | — | L66–75 | L358–443 | — |
| LH | Lịch hẹn (appointment, quota, khung giờ, no-show, hạn chế đặt) | 1 | L129–146 (12) | UC39–43 · L100–108 | #3 Lịch hẹn · L70–90 | L77–82 | L444–495 | ST03, ST05 |
| TN | Tiếp nhận & hàng đợi (check-in, visit, queue, gán nhân viên) | 1 | L148–162 (9) | UC44–47 · L110–117 | #4 Visit · L92–111 | L84–93 (chung KB) | L496–607 (chung KB) | — |
| KB | Khám & điều trị (bệnh án, kê đơn, tiêm chủng, tái khám) | 1 | L164–175 (6) | UC48–53 · L119–128 | #4 Visit · L92–111 | L84–93 | L496–607 | ST04, ST06 |
| LT | Lưu trú (boarding, chuồng, nhận/trả thú, nhật ký chăm sóc) | 2 | L177–194 (12) | UC54–60 · L130–140 | #6 Đặt chỗ · L138–162; #7 Chuồng · L164–181 | L95–102 | L608–704 | ST05, ST15 |
| BH | Bán hàng & đơn hàng (order, dòng order, trừ kho khi thu) | 1 | L196–207 (6) | UC61–69 · L142–154 | #5 Order · L113–136 | L104–111 (chung TG) | L705–805 (chung TG) | ST06, ST13 |
| TG | Thu ngân (payment, ca thu ngân, đối soát) | 1 | L209–219 (5) | UC70–72 · L156–162 | #8 Ca thu ngân · L183–201 | L104–111 | L705–805 | ST13 |
| KO | Kho (tồn, lô, FEFO, nhập kho, điều chỉnh) | 2 | L221–232 (8) | UC73–79 · L164–174 | #9 Phiếu nhập · L203–217 | L113–122 | L806–917 | ST06, ST08 |
| BV | Bài viết (article, chuyên mục) | 2 | L234–243 (4) | UC80–82 · L176–182 | — (vòng đời ở BR-BV-02) | L124–129 | L918–946 | — |
| DG | Feedback (khiếu nại đã bỏ, ADR-0122) | 2 | L245–254 (BR-DG-01…03, 05) | UC83–84 · L184–191 | — (không có vòng đời) | L131 (Feedback) | L956–978 | ST24 |
| TB | Chăm sóc KH & thông báo (nhắc tái chủng/tái khám, Care Task, notification) | 1 | L256–267 (6) | UC87–88 · L193–198 | #10 Care Task · L219–235 | L133–139 | L979–1042 | ST04, ST18, ST19, ST20 |
| BC | Báo cáo | 2 | L269–276 (4) | UC89 · L200–204 | — | L163 (không có model) | — (tính từ bảng nghiệp vụ) | — |
| NS | Nhân sự (ca làm, nghỉ phép) — duyệt T54 (5/11/2026) | 3 | L112–129 (BR-NS-01…12) | UC34–38 · L90–98 | #11, #12 · L239–278 | §12.1 · L147–156 | §14.1 · L1189–1247 | — |
| AI | Trí tuệ nhân tạo & đề xuất thông minh (chatbot RAG+LLM, recommendation engine) — duyệt T54 (5/11/2026); **đặc tả riêng đầy đủ: [07-ai-subsystem.md](./07-ai-subsystem.md)** | Giai đoạn 2 | 07 §4 (BR-AI-01…28) | UC90–94 · 07 §2 | #13 · 07 §9 | 07 §8 | 07 §8 | ST22, ST23 |

**Tầng 3 — Giai đoạn 2 (11–12/2026):** UC13, UC27, UC32, UC34–38, UC55, UC56, UC61–65, UC68, UC69, UC77–79, UC80, UC82 · ST07, ST09–ST12, ST14, ST21, ST24 (dọn feedback). Quy tắc nghiệp vụ, FSM, model và API đã được đặc tả bổ sung (duyệt T54, 5/11/2026): Nhân sự ở `02`–`06`; AI ở [`07-ai-subsystem.md`](./07-ai-subsystem.md); phần BE-2 ở [`08-tang3-be2-spec.md`](./08-tang3-be2-spec.md); phần BE-1 ở [`09-tang3-be1-spec.md`](./09-tang3-be1-spec.md); checklist T54 (bản ghi quyết định) và danh mục màn hình T57 ở [`10-t54-t57-checklists.md`](./10-t54-t57-checklists.md).

## 3. Tra theo mã định danh

| Gặp mã | Định nghĩa nằm ở | Cách tìm |
|---|---|---|
| `BR-<MÃ>-<số>` | business-rules, mục của module `<MÃ>` | tìm `\| BR-TK-19 \|` |
| `UC<số>` / `ST<số>` / `A<số>` | use-case mục A / mục B / mục 0 | tìm `\| UC44 \|`, `\| ST13 \|`, `\| A06 \|` |
| `<Đối tượng>#<số>` (`Visit#5`, `Đặt chỗ#3`) | state-machine, mục của đối tượng, dòng `\| 5 \|` của bảng chuyển trạng thái | cột state-machine ở Bảng 2 |
| `SM #<số>` (trong domain-model, erd) | state-machine mục `## <số>.` | |
| Tên model (PascalCase) | domain-model; tên bảng ở erd L-67–-37 | |
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
| Quyết định kỹ thuật đã chốt (ADR) | `adr/INDEX.md` là danh sách đầy đủ (mỗi ADR một dòng, số kế tiếp ở cuối file; bất biến sau khi Accepted, muốn đổi thì ADR mới thay thế). Theo nhóm — **nền tảng** 0001 audit, 0002 IP client, 0004 [CFG], 0007 job, 0015 đồng hồ, 0020 OSIV tắt, 0032 số nguyên chặt, 0040 Swagger = hợp đồng, 0098 index audit · **xác thực** 0003 JWT + phiên DB, 0005 path public, 0006 phạm vi chủ sở hữu, 0009 BCrypt, 0010 / 0011 / 0025 OTP, 0019 đăng nhập, 0021 đăng xuất, 0022 / 0023 mật khẩu, 0091 access token 10 phút + refresh token, 0100 giới hạn tần suất · **tài khoản, nhân viên, quản trị** 0024–0039 (0089, 0096, 0097 Proposed) · **thông báo và job** 0008, 0012–0017, 0099, 0111, 0112, 0114 · **lịch hẹn** 0045–0057, 0092, 0094 · **visit** 0058–0078, 0088(N5), 0093, 0095 · **care task** 0079–0084 (0081, 0083 Proposed) · **BE-2** 0041–0044, 0051, 0064, 0065, 0071, 0085–0087, 0088(kho), 0103–0107 (0090 Proposed) · **nợ đã chấp nhận** 0092 (D027), 0101 (D018), 0102 (D015), 0105, 0115 (D056) · **CI** 0121 (D060) · **bỏ khiếu nại** 0122, 0131 (V51 gỡ DB khiếu nại, chia V51 / V55; V55 đổi số từ V52 ngày 15/11) · **Nhân sự: tự đăng ký ca theo tuần, định mức 10 ca** 0123 · **nội trú: số ngày, tiền, chuồng, y lệnh** 0124 · **bỏ phẫu thuật** 0125 · **giai đoạn 2** 0108–0110 (AI, pgvector), 0113 (tầng 3 BE-1), 0116 (Order `ONLINE`), 0117 (tầng 3 BE-2). **`orders.version`** 0132 (BE-1, V54, D045; trùng số với ADR chat cá nhân của AI) · **hợp đồng Nhân sự: thứ tự lỗi 409 trước 400, ca đọc được cho A06–A08, đọc đăng ký tuần** 0148 · **AI (FE-1)** 0129, 0130, 0133–0136 (0134–0136 Accepted 18/11/2026). **gallery sản phẩm** 0151 (V62) · **nội trú** 0148 (chốt đặc tả), 0149 (nền module, V63; trùng số với ADR Nhân sự) · **AI** 0141, 0142, 0147 · **BE-2** 0137–0146. Số kế tiếp **0152** (ADR Nhân sự của BE-1 đổi từ 0137–0139 sang 0148–0150 ngày 23/11/2026 vì trùng số với ADR của BE-2) (0150: quản lý từ chối yêu cầu nghỉ / đổi ca #14 và duyệt #13 — nghỉ N3-S5a, đổi ca N3-S5b với V60 ca lúc gửi —, Proposed; 0149: nền module Nhân sự — entity, repository, FSM #11 / #12, CRUD ca, phân ca hộ #4–5, hủy #6, lịch của tôi #8, listener `StaffLeftBranchEvent` BR-NS-09, T58 bước 1–5; yêu cầu nghỉ / đổi ca của nhân viên #9–11, ghi vắng đột xuất #7 và danh sách yêu cầu của chi nhánh #12, T59 bước 1–3; đăng ký tuần #15, #17 và thống kê tuần #16, N3-S6–S8, Proposed); 0118–0120 dành cho BE-2, 0126–0128 chừa để đổi số; các số **0088, 0122, 0123, 0124, 0148, 0149 trùng** giữa các ADR |
| Sơ đồ | `diagrams/INDEX.md` *(đang trống)* |
| Trạng thái kỹ thuật thực tế của codebase | `architecture/system-overview.md` |
| Quy ước nhà tách khỏi `CLAUDE.md` (đọc khi đụng tới): thứ tự khóa / giao dịch · cô lập IT (era) và kỹ thuật test · bẫy FE và quy tắc mock | `architecture/concurrency-locking.md` · `architecture/testing-notes.md` · `architecture/frontend-notes.md` |
| Nợ code đã biết (placeholder, việc để lại, giả định tạm) | `dept/INDEX.md` — đọc trước khi động vào module liên quan |
| Ma trận test tay màn hình tầng 3 + AI trên FE (T57, chạy trên mock; cột J để Hiến điền) | `testcases/T57-fe.md` |
| Đăng ký tài khoản sandbox cổng thanh toán (T65; VNPay, phương án `FakeGatewayClient` khi chưa có) | `payment-gateway-setup.md` |
| BE đã làm đến đâu và chỗ nào FE ghép được (theo task T; thứ tự kiểm lỗi mock ↔ BE ở mục 8.3) | `be-fe-integration.md` |

`convention/backend/` đã cập nhật theo v16 (mục lục `convention/backend/INDEX.md`, gồm bảng các điểm còn TBD: tên command, cách trả cảnh báo, chiến lược khóa đồng thời).
