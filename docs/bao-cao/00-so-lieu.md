# Bảng số liệu dùng chung

> Nguồn duy nhất cho mọi con số xuất hiện trong báo cáo. Chương nào dùng một số thì số đó phải có mặt ở đây, kèm ngày đo và lệnh đếm.
> **Hai lần đo.** (1) 18/11/2026 trên commit `7d1e6dc` (nhánh `dev`; đo lại cùng ngày trên `8c4be77` và trên cây làm việc: không đổi). Chương 1 và 2 dùng số của lần này. (2) **25/11/2026 trên `037141c2`** (nhánh `dev`, cây sạch, `origin/dev` đã nằm trong HEAD): chương 3, 4, 6 dùng số của lần này. Hai lần đo cách nhau 7 ngày nên có số đã đổi (Bảng S.1, cột cuối); chương 1 và 2 sẽ được làm mới ở T84 (14–18/12/2026). Mọi số đếm sẽ được đo lại ở T84; khi đó chỉ cần sửa file này rồi rà các chương.
> Chạy lại (chỉ đọc `docs/`, `BE/`): `py D:/Do-an/.c2-tmp/t86/count.py`, `count2.py`, `count-ops.py` (khớp thao tác hợp đồng với handler theo method và path), `node docs/api/check-contracts.mjs`. Các tập lệnh nằm ngoài repo.

## Bảng S.1. Số đếm: đề cương, 18/11 và 25/11

| Hạng mục | Trong đề cương | Đo 18/11/2026 (`7d1e6dc`) | **Đo 25/11/2026 (`037141c2`)** | Lệnh đếm / nguồn |
|---|---|---|---|---|
| Use case hiệu lực | 85 ở mục 2.2.2 (58 + 27) và 83 ở Phụ lục (58 + 25); hai chỗ của đề cương không khớp nhau | **85** = 58 giai đoạn 1 + 22 tầng 3 + 5 AI (UC90–UC94). Có 94 mã; 9 mã đã bỏ: UC18, UC19, UC20, UC41, UC43, UC50, UC51, UC85, UC86 | **85**, không đổi | `grep -cE '^\| UC[0-9]+ \|' docs/01-business-operations.md` → 85; `count.py` |
| Tác vụ hệ thống (ST) | 21 (12 + 9) | **22** = 12 giai đoạn 1 + 8 tầng 3 + 2 AI. Có 24 mã; ST16, ST17 đã bỏ | **22**, không đổi | `grep -cE '^\| ST[0-9]+ \|' docs/01-business-operations.md` → 22 |
| Tác nhân | 8 người (A01–A08) + 3 hệ thống (S01–S03) | 8 + 3 = 11 | không đo lại | `docs/01` mục 0 |
| Use case theo tác nhân (A01 … A08) | 5, 23, 10, 19, 29, 29, 21, 15 | 6, 25, 10, 20, 28, 27, 20, 15 | **7, 25, 10, 20, 28, 28, 20, 16** (A01, A06, A08 tăng 1: `docs/01` được sửa sau 18/11 cho đăng nhập Google và nội trú) | `count.py` (đếm cột *Actor* của `docs/01`, bỏ phần ghi chú trong ngoặc). Bảng 2.3 của chương 2 còn dùng số 18/11; làm mới ở T84 |
| Phân hệ / module | 18 module nghiệp vụ; phân hệ AI "chưa có trong đặc tả" | Ba cách đếm: **19** mã phân hệ (18 + AI, `docs/INDEX.md` Bảng 2); **24** module backend theo đặc tả (`docs/06` mục 2); **17** thư mục `module/` có mã | **19** mã phân hệ (không đo lại); **24** dòng ở `docs/06` mục 2 (đo lại); **19** thư mục `module/` có mã (tên ở Bảng S.4) | `docs/INDEX.md`; `docs/06-module-contracts.md`; `ls BE/src/main/java/com/petcare/module` → 19 |
| Máy trạng thái (FSM) | 10 đối tượng, 66 chuyển | **23** đối tượng hiệu lực: #1–#10 (66 chuyển) + #11, #12 nhân sự (12 chuyển) + 11 FSM tầng 3 và AI (#13–#21, #24, #25; khoảng 61 chuyển) | **23** đối tượng (#1–#21, #24, #25; #22, #23, #26 đã bỏ). Chuyển #1–#10: **70** (hàng bảng; `docs/03` thêm 4 hàng sau 18/11: Visit #7, Order #14, Chuồng #6 và #7); #11–#12: 12 (5 + 7); #13–#21, #24, #25: khoảng 61 | `count2.py` đếm hàng bảng chuyển; #20 (4) và #21 (9) theo ghi chú của `docs/03`. Các FSM tầng 3 ngoài #13, #20, #21, #24, #25 chưa đọc tay |
| Quy tắc nghiệp vụ (BR) hiệu lực | 134 | **229** = 146 + 35 + 40 + 8 | **230** mã khác nhau = 146 (`docs/02`, gồm 12 mã nhân sự) + 35 (AI, `docs/07`) + 40 (`docs/08`) + 9 (`docs/09`); đã kiểm không mã nào trùng giữa các file. `docs/02` còn 6 mã gạch bỏ | `cat` bốn file `\| grep -oE '^\| BR-[A-Z]+-[0-9]+[a-z]? \|' \| sort \| uniq \| wc -l` → 230; `count.py` |
| Endpoint (hợp đồng HTTP v1) | 232, 12 module | **345** thao tác trong 24 file = 231 giai đoạn 1 + 114 tầng 3 và AI | **347** trong 24 file = **232** giai đoạn 1 (12 module) + **115** tầng 3 và AI (12 module). Thêm `identity` #40 (đăng nhập Google) và `workforce` #17 | `node docs/api/check-contracts.mjs` → "ALL CONTRACTS PASS — 24 file, 347 operations"; `count.py` cùng ra 347 |
| Handler backend | — | **263** annotation | **297** annotation; **297 / 297** khớp một-một với thao tác của hợp đồng theo (method, path) | `git grep -hE '@(Get\|Post\|Put\|Patch\|Delete)Mapping' -- BE/src/main/java \| wc -l` → 297; `count-ops.py` → "khớp 297 / 347; không có trong hợp đồng: 0" |
| Bảng cơ sở dữ liệu | 52 model / 55 bảng | `docs/05` có **59** mục bảng. Schema thật có **88** bảng (90 tạo, 2 bị xóa ở V51); 2 bảng giữ lại không dùng (`surgeries`, `branch_home_visit_fees`), nên **86** đang dùng | **88** (90 − 2), không đổi; `docs/05` **59** mục. 29 bảng của schema chưa có mục trong `docs/05` (nằm ở `docs/07`, `08`, `09`) | `count.py`, `count2.py` (đếm `CREATE TABLE`, trừ `DROP TABLE`) |
| Migration Flyway | — | **57** file, V1–V58; V49 để trống | **64** file, V1–V64 và V66 (V66 viết 27/11/2026, ADR-0157, chưa áp DB demo); V49 vẫn trống có chủ đích (ADR-0114); V65 sẽ bỏ trống mãi như V49: trigger khóa bệnh án (ADR-0153) được viết với số V65 nhưng khi vào cây mã sẽ lấy số V67, vì V66 đã đi trước và Flyway chạy với `outOfOrder = false`. Số kế tiếp V67 | `ls BE/src/main/resources/db/migration \| wc -l` → 64 (27/11/2026) |
| Quyết định kiến trúc (ADR) | — | **144** file, 140 số khác nhau; số lớn nhất 0146; 4 số trùng | **152** file (không tính `INDEX.md`), **146** số khác nhau; số lớn nhất 0152. **6 số trùng**, mỗi số hai file nên khi trích dẫn phải ghi tên file: 0088, 0122, 0123, 0124, 0148, 0149. Trạng thái: 129 Accepted, 18 Proposed, 5 chưa phân loại (dòng trạng thái không đọc được bằng biểu thức chính quy) | `ls docs/adr`; `count.py` |
| Số thư mục `module/` đã có mã | — | 17 | **19** | `ls BE/src/main/java/com/petcare/module` |

> Ghi chú: README quy ước 5 ghi bốn số ADR trùng (0088, 0122, 0123, 0124); thực tế sáu (thêm 0148, 0149). Đã nêu ở danh sách mâu thuẫn của README.

## Bảng S.2. Trạng thái cài đặt tại ngày 25/11/2026

**Cách đo (đổi so với 18/11).** Cột **BE** là số thao tác của hợp đồng đã có handler, **khớp theo cặp (method, path)** giữa file OpenAPI của module và annotation trong controller (`count-ops.py`, 25/11/2026, `037141c2`). Cách này thay cho cách ở lần đo 18/11 (chia số handler cho số thao tác, mặc nhiên cho rằng các thao tác có mã là những thao tác đứng đầu danh sách); cách cũ ghi sai `payment` (thực tế có #4–#6, không phải #1–#3) và `returns` (có #1, #6, #8). Đây vẫn là kiểm tra có tồn tại handler, **chưa phải đo hành vi**. Cột **FE** lấy từ tài liệu theo dõi của nhóm (`docs/be-fe-integration.md`, `docs/time.md`) và **chưa được đo lại bằng lệnh**.

**Giai đoạn 1 (tầng 1 và tầng 2)**

| Module | Phân hệ | BE (đã có / hợp đồng) | FE (theo tài liệu nhóm) | Thao tác chưa có handler |
|---|---|---|---|---|
| identity | TK, QT | 40 / 40 | Chạy thật với BE | — (gồm #40 đăng nhập Google, T94; kiểm tay chưa có kết quả) |
| appointment | LH | 9 / 9 | Chạy thật với BE | — |
| visit | TN, KB | 21 / 21 | Chạy thật với BE | — |
| care | TB | 8 / 10 | Chạy thật với BE | `getMyNotificationSettings`, `updateMyNotificationSettings` (cài đặt nhận thông báo, chờ chủ sản phẩm) |
| branch | CN | 21 / 21 | Chạy thật với BE | — |
| catalog | SP | 22 / 22 | Mở trang với BE thật | — |
| customer | KH | 21 / 21 | Chạy thật với BE | — |
| inventory | KO | 17 / 17 | Chạy thật với BE | — |
| sales | BH, TG | 21 / 21 | Chạy thật với BE | — |
| boarding | LT | 22 / 22 | Chạy thật với BE | — |
| content | CK, BV, DG | 21 / 21 | Chạy thật với BE | — |
| report | BC | 7 / 7 | Chạy thật với BE | — |
| **Tổng** | | **230 / 232** | | |

**Giai đoạn 2 (tầng 3 và AI)**

| Module | Use case | BE (đã có / hợp đồng) | FE (theo tài liệu nhóm) | Thao tác chưa có handler |
|---|---|---|---|---|
| ai | UC90–UC94 | 14 / 14 | Nối BE thật (T82, 11/11) | — |
| workforce | UC34–UC38 | 17 / 17 | Nối BE thật (T66, 23/11), chỉ kiểm trên bản sao DB | — |
| inpatient | UC55, UC56 | 10 / 10 | Nối BE thật (T79, 25/11), chỉ kiểm trên bản sao DB | — |
| shop | UC61, UC62, UC64, UC65 | 18 / 19 | Nối BE thật #1–#18 (T67 17/11, T68 18/11; kiểm lại trên DB demo 25/11); #19 mock | `cancelFailedOnlineOrder` (#19, task 138) |
| fees | UC32 | 2 / 4 | Nối BE thật #1–#2 (T80, 16/11); #3–#4 mock | `listMaterialNorms`, `saveMaterialNorms` |
| payment | UC63 | 3 / 6 | Mock; đối soát chưa có màn hình | `payOnlineOrder`, `getMyPaymentTransaction`, `paymentGatewayCallback` (#1–#3). Đã có #4–#6 (đối soát) |
| returns | UC68, UC69 | 3 / 9 | Mock | `listReturnRequests`, `getReturnRequest`, `cancelReturnRequest`, `approveReturnRequest`, `listRefunds`, `retryRefund`. Đã có #1 `createReturnRequest`, #6 `rejectReturnRequest`, #8 `confirmRefund` |
| materialuse | ST07 | 0 / 3 | Mock | cả 3 (chưa có controller) |
| stockops | UC77–UC79 | 0 / 13 | Mock | cả 13 |
| branchlife | UC13 | 0 / 4 | Mock | cả 4 |
| custmerge | UC27 | 0 / 2 | Mock | cả 2 |
| articlex | UC80, UC82 | 0 / 14 | Mock | cả 14 |
| **Tổng** | | **67 / 115** | | |

> Số thứ tự `#n` trong cột cuối là số thứ tự theo `operationId` trong file OpenAPI. Một số tài liệu khác (`ai-v1.md`) đánh số khác nhau cho cùng thao tác; thân bài gọi bằng tên thao tác.

**Tác vụ hệ thống** (đếm theo lớp job hoặc service có mặt trong mã, chưa kiểm hành vi): giai đoạn 1 có đủ 12 / 12 (ST01–ST06, ST08, ST13, ST15, ST18–ST20). Giai đoạn 2 có **8 / 10**: ST09 (tính phí), ST10, ST12 (đơn online), ST14 (`PaymentReconciliationJob`), ST21 (`GatewayRefundJob`), ST22, ST23 (AI), ST24 (feedback). Chưa có ST07 (trừ vật tư) và ST11 (hết hạn giao dịch thanh toán). Lần đo 18/11 ghi 6 / 10 vì `payment` và `returns` chưa có job.

> Nhãn bắt buộc khi trích bảng này vào chương: "tình trạng ngày 25/11/2026, sẽ cập nhật ở T84".

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
| Phân hệ AI (bộ câu hỏi) | Xem Bảng S.5 | `docs/07` mục 13, ADR-0136 mục "Điều tra T92b" | — |

> **Hai cách đếm số tệp test lệch nhau (25/11/2026, chưa giải).** `git ls-files` ngày 25/11 cho 430 tệp `*Test.java`, 317 `*IT.java`, 236 tệp test frontend; `CLAUDE.md` và `system-overview.md` (23/11) ghi 403 và 284; lần đo 18/11 ghi 378 và 233. Không dùng số nào trong ba số cho đến khi T84 đo bằng một lệnh duy nhất; không chương nào được dựa vào các số này.

## Bảng S.4. Số đo cơ chế cài đặt (chương 3 và 4)

Đo ngày 25/11/2026 trên `037141c2`, chỉ đọc.

| Hạng mục | Số | Lệnh đếm |
|---|---|---|
| Lớp `*TransitionHandler` (cài đặt FSM) | 16 | `git ls-files 'BE/src/main/java/**/*TransitionHandler.java' \| wc -l` |
| Lớp `*Job` | 26 | `git ls-files 'BE/src/main/java/**/*Job.java' \| wc -l` |
| Annotation `@Scheduled` | 28 | `git grep -h '@Scheduled' -- BE/src/main/java \| wc -l` |
| Sự kiện đồng bộ (record `*Event` trong gói `api`) | 7: `PetDeceasedEvent`, `PetOwnerTransferredEvent`, `BranchClinicCancellationEvent`, `AccountLockedEvent`, `AccountUnlockedEvent`, `StaffLeftBranchEvent`, `CustomerMergedEvent` (khai báo, chưa có bên phát) | `git ls-files 'BE/src/main/java/**/api/*Event.java'` |
| Khóa cấu hình [CFG] (`ConfigKey`) | 87 | đếm hằng số trong `ConfigKey.java` (27/11/2026: +1 `ai.chat.intent_llm_enabled`, V66, ADR-0157; trước đó 86) |
| Thư mục module có mã | 19: ai, appointment, boarding, branch, care, catalog, content, customer, fees, identity, inpatient, inventory, payment, report, returns, sales, shop, visit, workforce | `ls BE/src/main/java/com/petcare/module` |
| Module trong đặc tả `docs/06` mục 2 | 24 (11 nhóm miền + `report` + 12 tầng 3 và AI) | đếm hàng bảng mục 2 |
| Vai trò tài khoản | 7: CUSTOMER, ADMIN, SUPER_MANAGER, BRANCH_MANAGER, RECEPTIONIST, VET, CARETAKER | `docs/05` (`accounts.role`); ADR-0003 |
| Vùng khóa tư vấn (advisory) đã dùng | 4: 1001 (thú, đặt lịch), 1002 (khách, đánh giá vi phạm), 1003 (feedback), 1004 (liều vaccine); số tự do kế tiếp 1005 | `docs/architecture/concurrency-locking.md` |

**Hằng số thiết kế** (giá trị đặc tả lấy từ ADR, không phải số đo; khi dùng trong thân bài phải nêu ADR):

| Hằng số | Giá trị | Nguồn |
|---|---|---|
| Access token | 10 phút (khóa [CFG] `session.access_ttl_minutes`, 5–720); refresh token xoay vòng, dùng lại thì hủy phiên | ADR-0091 |
| Phiên tuyệt đối | 12 giờ (`session.ttl_hours`, 1–72) | ADR-0091, ADR-0003 |
| BCrypt | cost 10; mật khẩu tối đa 72 byte | ADR-0009 |
| Giới hạn tần suất theo IP | cửa sổ cố định 60 giây; đăng nhập 30, đăng ký 10, mật khẩu 10; chat AI 10 mỗi phút và 60 mỗi giờ | ADR-0100; ADR-0110 |
| Luồng của bộ lập lịch | 4 | ADR-0114; `docs/convention/backend/07` |
| Phân trang | `page` từ 0, `size` mặc định 20, tối đa 100 | `docs/api/00-method.md` |
| Job dọn dữ liệu kỹ thuật | phiên 03:00; refresh token 03:10:11; OTP 03:20:17; thông báo 03:50:23 (đã đọc 30 ngày, chưa đọc 90 ngày) | ADR-0008, 0114, 0111, 0112 |
| Hàng đợi thông báo (ST20) | thử lại 1, 2, 4, 8 phút; lần thứ 5 thất bại thì `FAILED` | ADR-0012 |

**Phiên bản công nghệ** (đọc ngày 25/11/2026 từ `BE/pom.xml`, `FE/package.json` và phiên bản đã khóa trong `FE/package-lock.json`):

| Lớp | Thành phần và phiên bản |
|---|---|
| Backend | Java 21; Spring Boot 3.5.15; Flyway 11.7.2; jjwt 0.12.3; MapStruct 1.6.3; springdoc-openapi 2.8.11; jsoup 1.18.3; Apache POI 5.2.5 và PDFBox 2.0.27 (nạp tệp tri thức AI); Testcontainers 1.21.4 |
| Frontend | React 18.3.1; React Router 6.30.6; Vite 5.4.21; TypeScript 5.9.3; Tailwind CSS 4.3.3; TanStack Query 5.101.4; Zustand 4.5.7; Axios 1.20.0; Zod 3.25.76; React Hook Form 7.88.0; Vitest 3.2.7; Playwright 1.63.0 |
| Hạ tầng | PostgreSQL 17 qua ảnh `pgvector/pgvector:pg17` (phiên bản nhỏ không ghim); Redis 7 (`redis:7-alpine`); Mailpit (`axllent/mailpit:latest`, không ghim); Node 20 cho ảnh Docker frontend |
| AI | Gemini qua `RestClient` của Spring (không thêm thư viện AI): mô hình ngôn ngữ mặc định `gemini-3.1-flash-lite`, embedding `gemini-embedding-001` 768 chiều (`application.yml`) |

**Cổng và cấu hình triển khai** (đọc ngày 25/11/2026 từ `docker-compose.yml`, `application.yml`, `.env.example`; giá trị mặc định, không phải số đo):

| Hạng mục | Giá trị |
|---|---|
| Dịch vụ trong Docker Compose | 5: PostgreSQL (ảnh pgvector), Redis, Mailpit, backend, frontend |
| Cổng backend | 8081 khi chạy trực tiếp; 8080 trong Docker |
| Cổng frontend | 5173 (Vite, phát triển); 3000 (Nginx trong Docker) |
| Cổng hạ tầng | PostgreSQL 5432 (biến `DB_PORT`; nhóm đặt 5434 khi máy đã có PostgreSQL riêng); Redis 6379; Mailpit SMTP 1025, giao diện 8025 |
| Origin CORS mặc định của backend | 2: cổng 5173 và 3000 |
| Bắt buộc có | biến `JWT_SECRET`; thiếu `GEMINI_API_KEY` thì chatbot không khả dụng; thiếu `GOOGLE_CLIENT_ID` thì đăng nhập Google trả 503 |

## Bảng S.5. Số đo phân hệ AI (ghi nhận, không đo lại ngày 25/11; riêng dòng 9 đo ngày 27/11)

**Dòng 1 đến 8 dưới đây là số ghi nhận từ tài liệu và tệp kết quả của nhóm; không đo lại. Dòng 9 là lần đo mới ngày 27/11/2026.** Docker tắt ngày 25/11 nên không đọc được DB demo. Mỗi lần đo dùng kho tri thức, cây mã và định nghĩa p95 khác nhau; **không so sánh các lần đo khác kho**. Ba định nghĩa p95: spike (đo ở máy khách, chỉ lệnh thành công, bỏ giãn nhịp và chờ thử lại); T77 (cột `latency_ms` do backend ghi, chỉ đường gọi mô hình không lỗi); bộ v2 (đường gọi mô hình theo từng cấu hình).

| # | Lần đo, ngày | Nguồn | Cây mã; kho tri thức | Kết quả |
|---|---|---|---|---|
| 1 | Thăm dò mẫu nhỏ, 5/11 | ADR-0109 "Đo thực tế"; `docs/07` §13 | không tệp; 7 đoạn mẫu | cosine câu đúng phạm vi 0,703–0,790; lạc đề 0,502–0,551; gần chủ đề 0,581–0,665 |
| 2 | Spike T56, 5/11 | `BE/scripts/spike/results/t56-flashlite.*`; `docs/07` §13; ADR-0122 (`0122-ai-spike-t56-decisions`) | 32 tài liệu tự soạn (29 `MANUAL` + 3 phác đồ), 52 đoạn | 43 câu × 3 lượt = 129 lượt, **123 / 129** đạt (126 / 129 đúng kết quả; Q14 chỉ trượt kiểm "không gọi LLM"); nhóm an toàn 42 / 42; p95 **6 435 ms**, p50 1 656 ms (90 lượt có gọi mô hình); lỗi lệnh sinh lần đầu 6 / 90 (6,7 %). Mốc 5 giây không đạt |
| 3 | T77 đợt 1, 10/11 | `BE/scripts/ai-eval/results/t77-t77*`; ADR-0141 | sau `c27bae3`; 32 tài liệu | 32 câu công khai: lượt 1 30 / 32, lượt 2 24 / 25; p95 **4 644 ms** (50 lượt có gọi mô hình); `UNAVAILABLE` 0 / 57 |
| 4 | T77 đợt 2, 11/11 | `t77-d2*`; `docs/07`; ADR-0141, ADR-0142 | cây `0279133`; 32 tài liệu | 43 câu: lượt 1 **42 / 43**, lượt 2 28 / 29; p95 **2 791 ms** (58 mẫu), gộp hai đợt 3 793 ms (108 mẫu); `UNAVAILABLE` 0 / 72; an toàn 14 / 14 và 9 / 9; che tên thú: 0 / 42 dòng nhật ký chứa tên |
| 5 | Bộ v2 pha 1, 16/11 | `BE/scripts/ai-eval/results/v2-p1.*`; `questions-v2.json` (85 câu); ADR-0136 | BE riêng cổng 8089 trên DB tạm; kho **35** | 298 lượt chat, 445 lệnh gọi Google; nhóm rag 20 / 20, general 15 / 15, business 10 / 10, an toàn **14 / 15** (ba cấu hình), p95 theo cấu hình A 3 314, B 5 170, C 3 346 ms. Chưa đủ điều kiện bật chế độ mở (cần an toàn 15 / 15 và p95 ≤ 5 giây) |
| 6 | Baseline 43 câu trên kho mới, 16/11 | `t77-t92-base.*` | BE 8089; kho 35 | **34 / 43**; p95 2 786 ms (29 lượt); `UNAVAILABLE` 0 |
| 7 | T92b điều tra, 18/11 | `t77-t92b-*`; `ket-qua-t92b-dieu-tra.md`; ADR-0136 "Điều tra T92b" | mã `ce9ec0b`; kho 35 rồi 39; 189 lệnh gọi Google | cấu hình demo **35 / 43**; nạp thử 4 tài liệu **38 / 43**; nạp thật vào DB demo (id 67–70): 4 câu liên quan đạt, chỉ đo 4 câu. Q09 là ca đầu tiên truy hồi vector chưa đủ (đoạn đúng ở hạng 6, cắt bởi K = 5) |
| 8 | Kiểm giới hạn nạp tệp, 19/11 | ADR-0147 "Đã đo" | `c0eac03`; bản sao DB | không phải chất lượng chatbot: biên 10 240 kB, 409, 400 |
| 9 | Bộ phân loại câu hỏi cá nhân mơ hồ, 27/11 | ADR-0157 "Kết quả" | cây mã ngày 27/11/2026, chưa dựng lại backend; gọi thẳng Gemini bằng đúng lời nhắc và lược đồ trong mã, chưa đi qua backend thật | 16 câu (4 lịch hẹn, 3 lịch tiêm, 9 câu chung gồm 3 ví dụ "không phải cá nhân" của BR-AI-26 và 1 câu chèn chỉ thị): **15 / 15 câu có phản hồi đúng nhãn**, 1 lượt bị Google trả HTTP 503; độ trễ trung vị 1,1 s, có lượt 19–27 s khi Google quá tải. Bản đầu giới hạn 20 token ra làm 16 / 16 câu bị cắt JSON; đã nâng lên 64 |

**Số tài liệu `PUBLISHED` theo thời điểm** (mỗi số thuộc một kho; không chép số cũ như số mới): 32 (10–11/11: 29 tự soạn + 3 phác đồ; 52 đoạn); 28 (13/11 13:41); 35 (từ 13/11 14:36, kho của mọi lượt đo 16/11); 39 (từ 18/11, thêm id 67–70). Số hiện tại phải đọc lại từ DB demo ở ngày chốt: `TODO(T84)`.

**Trạng thái năm công tắc `ai.chat.*` trên DB demo** (bản ghi cuối 18–19/11, **không đọc lại ngày 25/11**; công tắc thứ năm viết ngày 27/11): `general_knowledge_enabled` bật từ 13/11 (V52); `business_intent_enabled` tắt (V53); `pet_context_enabled` tắt (V56); `open_domain_enabled` tắt (V57); `intent_llm_enabled` (V66, ADR-0157) mới có trong mã, **chưa áp lên DB demo** nên chưa có giá trị ở đó. Mặc định trong migration của bốn công tắc đầu là `false`, của công tắc thứ năm là `true`. `ai.ingest.max_file_kb` mặc định 2048 (V59). `TODO(T84)`: đọc lại bằng `GET /api/system-configs` ở ngày chốt.

**Tham số truy hồi và an toàn:** ngưỡng cosine τ = 0,60; K = 5 đoạn; hết giờ gọi mô hình 15 giây; 17 cụm từ khóa cấp cứu; 16 cụm rủi ro y khoa; đoạn dài 300–1200 ký tự, chồng lấn 100; embedding theo lô 16 (nghỉ 800 ms); mô hình ngôn ngữ nhiệt độ 0,2, tối đa 600 token ra; bộ phân loại câu mơ hồ (ADR-0157) nhiệt độ 0, tối đa 64 token ra. Điểm gợi ý: `100 × (0,5·A + 0,3·H + 0,2·P)`, làm tròn HALF_UP một chữ số (ADR-0133). Nguồn: ADR-0122, 0130, 0133, 0141, 0142; `docs/07`.

**Bộ 43 câu theo nhóm** (số câu đạt, một lượt, cấu hình A; ghi nhận từ tệp tổng hợp của ba lần chạy bằng backend thật: T77 đợt 2 lượt 1 (kho 32), baseline 16/11 (kho 35), nạp thử 18/11 (kho 39); ADR-0136 "Điều tra T92b"; chưa kiểm lại từng tệp). Ba cột thuộc ba kho khác nhau, không so sánh trực tiếp:

| Nhóm | Số câu | Kho 32 | Kho 35 | Kho 39 |
|---|---|---|---|---|
| Có trong tri thức | 12 | 12 | 7 | 10 |
| Lạc đề | 3 | 2 | 2 | 2 |
| Gần chủ đề, ngoài phạm vi | 4 | 4 | 3 | 4 |
| Gần chủ đề, kho chưa có | 2 | 2 | 1 | 1 |
| Y khoa bị từ chối | 3 | 3 | 2 | 2 |
| Các nhóm còn lại (cấp cứu, chèn chỉ thị, cá nhân, tên thú, vãng lai, bệnh án) | 19 | 19 | 19 | 19 |
| **Tổng** | **43** | **42** | **34** | **38** |

**Số bổ sung dùng ở chương 6** (ghi nhận từ tài liệu và tệp kết quả, không đo lại): bộ 43 câu có 13 nhóm (nhóm "có trong tri thức" 12 câu); bộ v2 có 85 câu, 6 nhóm, ba cấu hình A, B, C; lần nạp thử 18/11: p95 6 128 ms trên 29 mẫu, 3 trên 43 lượt lỗi nhà cung cấp; T77 đợt 1: lượt chậm nhất 10 436 ms; Q24 trên kho 35, cấu hình A: `ANSWERED` 4 / 4 lượt hợp lệ; Q09: cosine đoạn đúng 0,6653 (hạng 6) sau hạng 5 là 0,6742, K = 8 trả lời được; Q01: cosine 0,72 nhưng kho không có đoạn nêu mốc tuổi; hậu kiểm liều trên 26 ca tổng hợp của spike: chặn nhầm 2, bỏ sót 4; gói miễn phí của `gemini-2.5-flash`: 20 lệnh sinh mỗi ngày (ADR-0122); ngưỡng lỗi nhà cung cấp chấp nhận khoảng 7 %, xem lại nếu nhật ký thật vượt 10 % (ADR-0122); câu hỏi tối đa 500 ký tự (BR-AI-08); nhật ký giữ 30 ngày (BR-AI-24); độ phổ biến tính trong 90 ngày (BR-AI-20); chỉ tiêu bật chế độ mở: an toàn 15 / 15 và p95 không quá 5 giây, ba lượt liên tiếp (thực tế mới một lượt: 14 / 15, p95 cấu hình B 5 170 ms); chỉ tiêu bộ 43 câu: nhóm "có trong tri thức" đúng ít nhất 10 / 12 (`docs/07` mục 14.1). Ví dụ tính điểm gợi ý của đặc tả (không phải số đo): chó 20 tuần nặng 3,2 kg; dịch vụ có A = 1,0, H = 1,0, P = 0,6 → 92,0; dịch vụ chưa có hồ sơ A = 0,3, H = 0,4, P = 0,5 → 37,0 (`docs/07` mục 7.3).

**Quy mô phân hệ:** 14 endpoint (`ai-v1`), 35 BR-AI (BR-AI-01…35), 5 use case (UC90–UC94), 2 tác vụ (ST22, ST23), 1 máy trạng thái (#13), 4 bảng (`ai_documents`, `ai_chunks`, `ai_chat_logs`, `ai_item_profiles`).
