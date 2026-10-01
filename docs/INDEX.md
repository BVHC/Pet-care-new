# Pet Care Ecosystem — Index

> Mục lục cho agent. Đọc file này trước, tra **Bảng 2** để lấy vị trí, rồi chỉ đọc đúng đoạn cần thiết (ví dụ `view erd.md [78, 198]`) thay vì đọc cả file.
>
> Số dòng `L…` đúng với bản v15. Nếu tài liệu đã sửa và số dòng lệch, tìm theo **tiêu đề mục** (ghi trong ngoặc ở Bảng 1) hoặc theo **mã định danh** ở Bảng 3.

## 1. Các file

| File | Trả lời câu hỏi | Cấu trúc | Ưu tiên khi mâu thuẫn |
|---|---|---|---|
| `business-rules.md` | Quy tắc nghiệp vụ là gì, vi phạm thì xử lý ra sao | Mục `## <số>. <Module> (<MÃ>)`, mỗi rule một dòng bảng `BR-<MÃ>-<số>` | 1 (cao nhất) |
| `state-machine.md` | Đối tượng có trạng thái nào, chuyển khi nào, kéo theo gì | Mục `## <số>. <Đối tượng>`, chuyển trạng thái đánh số `#1…`; tham chiếu dạng `Visit#5` | 2 |
| `domain-model.md` | Có những model nào, quan hệ, rule nào chi phối | Mục `## <số>. <Nhóm> (<MÃ>)`, mỗi model một dòng bảng | 3 |
| `erd.md` | Bảng, cột, kiểu, khóa, ràng buộc, index | Mục `## <số>. <Nhóm>`, mỗi bảng một mục `` ### `<tên_bảng>` `` | 4 |
| `use-case.md` | Ai làm gì (actor, use case, tác vụ hệ thống) | Actor `A01…`, use case `UC01…`, tác vụ hệ thống `ST01…` | Phải khớp business-rules |

**Mục dùng chung (đọc khi cần quy ước):**

| Nội dung | Vị trí |
|---|---|
| Quy ước mã rule, [CFG], tầng phạm vi, bảng module | business-rules L19–48 |
| Quy ước trạng thái, whitelist, ký hiệu `SYS ←` | state-machine L9–34 |
| Chuỗi tác động liên đối tượng (sự kiện → hệ quả) | state-machine L241–256 (*Phụ lục*) |
| Loại model ROOT/PART/REF/LOG, 9 nguyên tắc xuyên suốt | domain-model L11–36 |
| Phần ngoài phạm vi (tầng 3), nguồn số liệu báo cáo | domain-model L146–150 |
| Kiểu dữ liệu, enum ASCII ↔ tiếng Việt, sơ đồ luồng chính | erd L9–77 |
| Đối chiếu model → bảng; nhật ký quyết định v15 | erd L1039–1082 |
| Danh sách actor | use-case L29–46 |
| Tác vụ hệ thống ST01–ST21 | use-case L230–259 |
| Quan hệ include / extend | use-case L260–273 |

## 2. Bản đồ module

Tầng: **1** làm đầy đủ · **2** bản mỏng · **3** chỉ giữ trên sơ đồ, không có rule/model. Ô `—` là file đó không có nội dung riêng cho module.

| Mã | Module (từ khóa tìm kiếm) | Tầng | business-rules | use-case | state-machine | domain-model | erd | Tác vụ hệ thống |
|---|---|---|---|---|---|---|---|---|
| TK | Tài khoản & xác thực (auth, login, OTP, mật khẩu, liên kết hồ sơ) | 1 | L49–75 | UC01–07 · L49–60 | #1 Tài khoản · L35–56 | L37–48 (chung QT) | L78–198 (chung QT) | ST01, ST02 |
| QT | Quản trị hệ thống (admin, phân quyền, khóa, audit, cấu hình) | 1 | L76–96 | UC08–11 · L61–69 | #1 Tài khoản · L35–56 | L37–48 | L78–198 | — |
| CN | Chi nhánh (giờ mở cửa, ngày nghỉ, cấp cứu ngoài giờ) | 1 | L97–106 | UC12–14 · L70–77 | #2 Chi nhánh · L57–71 | L49–59 | L199–284 | — |
| CK | Thông tin công khai (trang public, hiển thị giá) | 1 | L107–116 | UC15–21 · L78–89 | — | L127–135 (PageContent) | L943–951 (`page_contents`) | — |
| KH | Khách hàng & thú cưng (customer, pet, cân nặng, chuyển chủ) | 1 | L117–132 | UC22–27 · L90–100 | — | L60–68 | L285–353 | ST19 |
| SP | Sản phẩm & dịch vụ (product, service, loại vaccine, phác đồ, loại chuồng) | 1 | L133–146 | UC28–33 · L101–111 | — | L69–79 | L354–439 | — |
| NS | Nhân sự (ca làm, nghỉ phép) | 3 | L147–150 | UC34–38 · L112–121 | — | — | — | — |
| LH | Lịch hẹn (appointment, quota, khung giờ, no-show, hạn chế đặt) | 1 | L151–169 | UC39–43 · L122–131 | #3 Lịch hẹn · L72–93 | L80–86 | L440–491 | ST03, ST05 |
| TN | Tiếp nhận & hàng đợi (check-in, visit, queue, gán nhân viên) | 1 | L170–185 | UC44–47 · L132–140 | #4 Visit · L94–114 | L87–97 (chung KB) | L492–603 (chung KB) | — |
| KB | Khám & điều trị (bệnh án, kê đơn, tiêm chủng, tái khám) | 1 | L186–198 | UC48–53 · L141–151 | #4 Visit · L94–114 | L87–97 | L492–603 | ST04, ST06 |
| LT | Lưu trú (boarding, chuồng, nhận/trả thú, nhật ký chăm sóc) | 2 | L199–217 | UC54–60 · L152–163 | #6 Đặt chỗ · L140–165; #7 Chuồng · L166–184 | L98–106 | L604–700 | ST05, ST15 |
| BH | Bán hàng & đơn hàng (order, dòng order, trừ kho khi thu) | 1 | L218–230 | UC61–69 · L164–177 | #5 Order · L115–139 | L107–115 (chung TG) | L701–801 (chung TG) | ST06, ST13 |
| TG | Thu ngân (payment, ca thu ngân, đối soát) | 1 | L231–242 | UC70–72 · L178–185 | #8 Ca thu ngân · L185–204 | L107–115 | L701–801 | ST13 |
| KO | Kho (tồn, lô, FEFO, nhập kho, điều chỉnh) | 2 | L243–255 | UC73–79 · L186–197 | #9 Phiếu nhập · L205–220 | L116–126 | L802–913 | ST06, ST08 |
| BV | Bài viết (article, chuyên mục) | 2 | L256–266 | UC80–82 · L198–205 | — (trạng thái trong BR-BV-02) | L127–135 | L914–951 | — |
| DG | Feedback & khiếu nại | 2 | L267–277 | UC83–86 · L206–214 | — (trạng thái trong BR-DG-04) | L127–135 | L952–974 | — |
| TB | Chăm sóc KH & thông báo (nhắc tái chủng/tái khám, Care Task, notification) | 1 | L278–290 | UC87–88 · L215–221 | #10 Care Task · L221–240 | L136–145 | L975–1038 | ST04, ST18, ST19, ST20 |
| BC | Báo cáo | 2 | L291–298 | UC89 · L222–229 | — | L150 (không có model) | — (tính từ bảng nghiệp vụ) | — |

Tác vụ hệ thống thuộc tầng 3 (không cần cài): ST07, ST09–ST12, ST14, ST16, ST17, ST21.

## 3. Tra theo mã định danh

| Gặp mã | Định nghĩa nằm ở | Cách tìm |
|---|---|---|
| `BR-<MÃ>-<số>` | business-rules, mục của module `<MÃ>` | tìm chuỗi `\| BR-TK-19 \|` |
| `UC<số>` | use-case, Bảng 2 cột use-case | tìm `\| UC44 \|` |
| `ST<số>` | use-case L230–259 | tìm `\| ST13 \|` |
| `A<số>` | use-case L29–46 | tìm `\| A06 \|` |
| `<Đối tượng>#<số>` (ví dụ `Visit#5`, `Đặt chỗ#3`) | state-machine, mục của đối tượng, dòng `\| 5 \|` trong bảng chuyển trạng thái | xem cột state-machine ở Bảng 2 |
| `SM #<số>` trong domain-model, erd | state-machine mục `## <số>.` | |
| Tên model (PascalCase) | domain-model; tên bảng tương ứng ở erd L1039–1072 | |
| Tên bảng (snake_case) | erd, mục `` ### `<tên_bảng>` `` | |
| `[CFG]` | tham số cấu hình, giá trị trong ngoặc là mặc định (BR-QT-13) | |
| `[ERD]` | quyết định riêng của erd, tài liệu tầng trên chưa quy định | |
| `🆕` | rule đề xuất, chưa duyệt riêng | |
| `~~BR-…~~`, `~~UC…~~` | đã bỏ, giữ mã để không lệch số | bỏ qua |

## 4. Cách tra một chủ đề

Ví dụ cần hiểu **module xác thực**:

1. Bảng 2, dòng TK → business-rules L49–75 để nắm quy tắc.
2. Use-case L49–60 để biết actor và use case liên quan (UC01–07).
3. State-machine L35–56 để biết trạng thái tài khoản.
4. Domain-model L37–48 và erd L78–198 để biết model và bảng (`accounts`, `sessions`, `otp_tokens`…).
5. Rule trỏ sang module khác (ví dụ BR-TK-19 → BR-KH-01) thì tra tiếp mã đó theo Bảng 3.

Câu hỏi chạm nhiều đối tượng (ví dụ "hoàn tất lượt khám kéo theo gì") thì đọc *Phụ lục* state-machine L241–256 trước.
