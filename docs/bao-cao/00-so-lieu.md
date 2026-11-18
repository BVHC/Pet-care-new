# Bảng số liệu dùng chung

> Nguồn duy nhất cho mọi con số xuất hiện trong báo cáo. Chương nào dùng một số thì số đó phải có mặt ở đây, kèm ngày đo và lệnh đếm.
> **Đo ngày 18/11/2026 trên commit `7d1e6dc` (nhánh `dev`); đo lại cùng ngày trên `8c4be77` (HEAD khi viết xong) và trên cây làm việc: các số không đổi.** (Giữa hai commit chỉ đổi 10 dòng của `docs/07`, `CLAUDE.md`, `docs/time.md` và một test frontend; không đổi hợp đồng, migration, ADR hay mã backend.) Mọi số đếm sẽ được đo lại ở T84 (14–18/12/2026); khi đó chỉ cần sửa file này rồi rà các chương.
> Chạy lại: `py D:/Do-an/.c2-tmp/t86/count.py` và `count2.py` (đọc `docs/`, `BE/`, không ghi gì).

## Bảng S.1. Số đếm: đề cương và hôm nay

| Hạng mục | Trong đề cương | Đếm lại 18/11/2026 | Lệnh đếm / nguồn |
|---|---|---|---|
| Use case hiệu lực | 85 ở mục 2.2.2 (58 giai đoạn 1 + 27 tầng 3) và 83 ở Phụ lục (58 + 25); hai chỗ của đề cương không khớp nhau | **85** = 58 giai đoạn 1 + 22 tầng 3 + 5 AI (UC90–UC94). Có 94 mã; 9 mã đã bỏ: UC18, UC19, UC20, UC41, UC43, UC50, UC51, UC85, UC86 | `grep -cE '^\| UC[0-9]+ \|' docs/01-business-operations.md` → 85 |
| Tác vụ hệ thống (ST) | 21 (12 + 9) | **22** = 12 giai đoạn 1 + 8 tầng 3 + 2 AI. Có 24 mã; ST16, ST17 đã bỏ | `grep -cE '^\| ST[0-9]+ \|' docs/01-business-operations.md` → 22 |
| Tác nhân | 8 người (A01–A08) + 3 hệ thống (S01–S03) | 8 + 3 = 11 | `docs/01` mục 0 |
| Use case theo tác nhân (A01 … A08) | 5, 23, 10, 19, 29, 29, 21, 15 | 6, 25, 10, 20, 28, 27, 20, 15 | `count.py` (đếm cột *Actor* của `docs/01`, bỏ phần ghi chú trong ngoặc). Chênh lệch so với đề cương giải thích được hết bằng các use case bỏ và UC90–UC94 |
| Phân hệ / module | 18 module nghiệp vụ; phân hệ AI "chưa có trong đặc tả" | Ba cách đếm, ghi rõ khi dùng: **19** mã phân hệ (18 mã của đề cương + AI, `docs/INDEX.md` Bảng 2); **24** module backend theo đặc tả (`docs/06` mục 2); **17** thư mục `module/` đã có mã nguồn | `docs/INDEX.md`; `docs/06-module-contracts.md`; `git ls-files BE/src/main/java/com/petcare/module` |
| Máy trạng thái (FSM) | 10 đối tượng, 66 chuyển | **23** đối tượng hiệu lực: #1–#10 (66 chuyển, đo lại khớp đề cương) + #11, #12 nhân sự (12 chuyển) + 11 FSM tầng 3 và AI (#13–#21, #24, #25; khoảng 61 chuyển). #22, #23, #26 đã bỏ | `count2.py` đếm hàng bảng chuyển. Chuyển của #13, #20, #21, #24, #25 đã đọc tay; các FSM còn lại của `docs/08` chưa đọc tay |
| Quy tắc nghiệp vụ (BR) hiệu lực | 134 | **229** mã khác nhau = 146 (`docs/02`, gồm 12 mã nhân sự) + 35 (AI, `docs/07`) + 40 (`docs/08`) + 8 (`docs/09`); không mã nào trùng giữa các file. `docs/02` còn 6 mã gạch bỏ | `grep -cE '^\| BR-[A-Z]+-[0-9]+ \|'` trên bốn file; `count.py` |
| Endpoint (hợp đồng HTTP v1) | 232, 12 module | **345** thao tác trong 24 file OpenAPI = 231 giai đoạn 1 (12 module) + 114 tầng 3 và AI (12 module). `check-contracts` báo đạt 24 file, 345 thao tác | `node docs/api/check-contracts.mjs` |
| Bảng cơ sở dữ liệu | 52 model / 55 bảng | `docs/05` có **59** mục bảng (55 bảng của V1 + `refresh_tokens` + 3 bảng nhân sự). Schema thật có **88** bảng (90 bảng được tạo, 2 bảng khiếu nại bị xóa ở V51). Trong 88 bảng có 2 bảng không còn dùng nhưng giữ lại (`surgeries`, `branch_home_visit_fees`), nên **86** bảng đang dùng. Bảng tầng 3 và AI được mô tả ở `docs/07`, `08`, `09`. Số model chưa đếm lại | `count.py`, `count2.py` (đếm `CREATE TABLE`, trừ `DROP TABLE` trong migration) |
| Migration Flyway | — | **57** file, V1–V58; V49 để trống có chủ đích. Số kế tiếp V59 | `ls BE/src/main/resources/db/migration` |
| Quyết định kiến trúc (ADR) | — | **144** file, 140 số khác nhau (các số 0088, 0122, 0123, 0124 mỗi số có hai file, nên khi trích dẫn phải ghi tên file); số lớn nhất 0146 | `ls docs/adr` |
| Handler backend | — | **263** annotation `@GetMapping`, `@PostMapping`, … trong các module | `git grep -hE '@(Get\|Post\|Put\|Patch\|Delete)Mapping' -- BE/src/main/java` |

## Bảng S.2. Trạng thái cài đặt tại ngày viết

Cách đo: cột **BE** là số handler đếm được trong `BE/src/main/java/com/petcare/module/<module>` chia cho số thao tác trong file OpenAPI của module (18/11/2026, `7d1e6dc`); đây là đếm annotation, chưa phải đo hành vi. Cột **FE** lấy từ tài liệu theo dõi của nhóm (`docs/be-fe-integration.md`, `CLAUDE.md`) và **chưa được đo lại bằng lệnh**. Bảng sẽ được cập nhật ở T84.

**Giai đoạn 1 (tầng 1 và tầng 2)**

| Module | Phân hệ | BE | FE | Ghi chú |
|---|---|---|---|---|
| identity | TK, QT | 39 / 39 | Chạy thật với BE | Theo tài liệu nhóm (mốc 4/11 và 10/11): UC22 (đổi email, liên kết hồ sơ tại quầy) có ở BE nhưng chưa gọi thử trên BE thật; làm mới token (refresh) đã nối ở FE, chưa chạy với BE thật |
| appointment | LH | 9 / 9 | Chạy thật với BE | — |
| visit | TN, KB | 21 / 21 | Chạy thật với BE | Gồm hồ sơ sức khỏe thú cưng (#21) |
| care | TB | 8 / 10 | Chạy thật với BE | Thiếu #9–#10 (cài đặt nhận thông báo, chờ chủ sản phẩm) |
| branch | CN | 21 / 21 | Chạy thật với BE | — |
| catalog | SP | 22 / 22 | Mở trang với BE thật | — |
| customer | KH | 21 / 21 | Chạy thật với BE | — |
| inventory | KO | 17 / 17 | Chạy thật với BE | — |
| sales | BH, TG | 21 / 21 | Chạy thật với BE | — |
| boarding | LT | 22 / 22 | Chạy thật với BE | — |
| content | CK, BV, DG | 21 / 21 | Chạy thật với BE | Đã bỏ khiếu nại và trạng thái feedback (ADR-0122) |
| report | BC | 7 / 7 | Chạy thật với BE | — |
| **Tổng** | | **229 / 231** | | |

**Giai đoạn 2 (tầng 3 và AI)**

| Module | Use case | BE | FE | Ghi chú |
|---|---|---|---|---|
| shop | UC61, UC62, UC64, UC65 | 18 / 19 | Nối BE thật cho #1–#18 | #19 (hủy đơn sau giao thất bại) chờ cài đặt phía BE; FE chạy mock |
| fees | UC32 | 2 / 4 | Nối BE thật cho #1–#2 | #3–#4 (định mức vật tư) chưa có BE; FE chạy mock |
| ai | UC90–UC94 | 14 / 14 | Nối BE thật | Chi tiết đo ở chương 6 |
| payment | UC63 | 0 / 6 | Mock | Chỉ có interface giữa các module, chưa có controller |
| returns | UC68, UC69 | 0 / 9 | Mock | Chỉ có interface giữa các module, chưa có controller |
| workforce | UC34–UC38 | 0 / 16 | Mock | Chưa có mã Java |
| inpatient | UC55, UC56 | 0 / 10 | Mock | Chưa có mã Java |
| materialuse | ST07 | 0 / 3 | Mock | Chưa có mã Java |
| stockops | UC77–UC79 | 0 / 13 | Mock | Chưa có mã Java |
| branchlife | UC13 | 0 / 4 | Mock | Chưa có mã Java |
| custmerge | UC27 | 0 / 2 | Mock | Chưa có mã Java |
| articlex | UC80, UC82 | 0 / 14 | Mock | Chưa có mã Java |
| **Tổng** | | **34 / 114** | | |

**Tác vụ hệ thống** (đếm theo lớp job hoặc service có mặt trong mã, chưa kiểm hành vi): giai đoạn 1 có đủ 12 / 12 (ST01–ST06, ST08, ST13, ST15, ST18–ST20). Giai đoạn 2 có 6 / 10 (ST09, ST10, ST12, ST24 ở nghiệp vụ; ST22, ST23 ở AI); chưa có ST07, ST11, ST14, ST21.

> Nhãn bắt buộc khi trích bảng này vào chương: "tình trạng ngày 18/11/2026, sẽ cập nhật ở T84".

## Bảng S.3. Số kiểm thử (số tạm)

Chỉ dùng ở chương 5 và mục 1.3. Số tạm, **ghi nhận từ các lần đo trước, không đo lại trong lần viết này**.

| Lớp | Số tạm | Nguồn, ngày, commit | Số chính thức |
|---|---|---|---|
| Backend, unit test | 6417 ca | Hồi quy 17/11/2026, `d1e116c` (`ket-qua-hoi-quy-17-11.md`) | `TODO(T84)` |
| Backend, integration test | 233 lớp, 3266 ca, 0 ca đỏ | cùng nguồn | `TODO(T84)` |
| Frontend, Vitest | 231 tệp, 5326 ca, đạt ở hai lượt chạy liền | 18/11/2026, sau `ce9ec0b` (T68) | `TODO(T84)` |
| Số tệp test (đếm tệp, không phải số ca) | 378 tệp `*Test.java`, 233 tệp `*IT.java`, 231 tệp `*.test.ts(x)` | `git ls-files` ngày 18/11/2026, `7d1e6dc` | — |
| End-to-end luồng chính giai đoạn 1 và 2 | — | — | `TODO(T84)` |
| Ma trận test tay | — | `docs/testcases/` | `TODO(T84)` |
| Phân hệ AI (bộ câu hỏi) | Xem chương 6 | `docs/07` mục 13, ADR-0136 mục "Điều tra T92b" | — |
