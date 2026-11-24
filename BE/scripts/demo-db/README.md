# DB demo dùng chung (`petcare_demo.dump`)

Ảnh chụp DB demo của nhóm: schema đã áp **Flyway V1…V62** và dữ liệu đã làm giàu (24/11/2026): **134 sản phẩm** (31 có ảnh bìa thật, 14 có ảnh phụ `galleryUrls` từ V62; 119 hiện ở cửa hàng chi nhánh 1) trong 9 danh mục với 9 nhà cung cấp, 12 phiếu nhập, 143 lô (có lô sắp hết hạn, đã hết hạn, tồn thấp), **42 khách, 72 thú cưng**, 17 dịch vụ, **20 bài viết**, 13 chuồng, **80 lịch hẹn**, 47 đặt chỗ lưu trú, và dữ liệu **nhân sự** (ca, đăng ký tuần, 195 phân ca, 6 yêu cầu nghỉ / đổi ca đủ 4 trạng thái), cùng 70 tài liệu tri thức AI. Mỗi người chạy DB trên máy mình nên cách chung là cùng restore file này, không tự seed lại từ đầu.

Tài khoản và mật khẩu dev: xem `docs/be-fe-integration.md` mục "Dữ liệu nền" (không lặp lại ở đây).

## Điều kiện

- **Code BE từ commit `e6436a0` (gallery ảnh sản phẩm, V62) trở lên**: `git pull origin dev` trước. Bản `d466c4d` cũ có hai file V55 và hai file V56 trùng số nên BE không khởi động được, và checksum Flyway của dump (V55 `drop_feedback`, V60, V61, V62) chỉ khớp với code đã sửa.
- Postgres 17 có extension `vector` (ảnh `pgvector/pgvector:pg17`, như `docker-compose.yml`).

## Restore

Dùng được ở mọi shell (Git Bash, PowerShell, cmd). **Chạy từ thư mục gốc repo** (đường dẫn `BE/scripts/...` tương đối). Thay `<container>` bằng tên container Postgres của bạn. Trong Git Bash đặt trước `export MSYS_NO_PATHCONV=1` (nếu không đường dẫn `/tmp/...` bị đổi), và đừng dùng đường dẫn tuyệt đối kiểu `/d/...` với `docker cp`: nó thành `D:\d\...` và hỏng.

```bash
docker exec <container> psql -U postgres -c "create database petcare_demo"
docker cp BE/scripts/demo-db/petcare_demo.dump <container>:/tmp/petcare_demo.dump
docker exec <container> pg_restore -U postgres -d petcare_demo --no-owner --exit-on-error /tmp/petcare_demo.dump
docker exec <container> rm /tmp/petcare_demo.dump
```

Chạy BE trỏ vào DB đó: `DB_NAME=petcare_demo` (và `DB_PORT` của container bạn). Muốn làm lại từ đầu: `drop database petcare_demo` rồi restore lại. Không dùng `<` hay pipe của PowerShell 5: nó làm hỏng file nhị phân.

Đã kiểm 24/11/2026 bằng đúng các lệnh trên: restore vào DB trống, `exit 0`, Flyway 62, 134 sản phẩm (14 có ảnh phụ), 42 khách, 72 thú, 80 lịch hẹn, 195 phân ca, 86 khóa [CFG], extension `vector` có.

## Cần biết

- **Ngày trong dữ liệu là ngày thật của 24/11/2026.** Lịch nhân sự phủ tuần 30/11, 07/12, 14/12 và tuần hiện tại từ 24/11; lịch hẹn và đặt chỗ lưu trú rải trong 2–3 tuần sau 24/11; hạn dùng của lô tính từ ngày đó. API không cho đặt lịch quá khứ nên không có lịch sử cũ. Sau các ngày đó các màn lịch trống dần: cần ảnh mới.
- **Ảnh sản phẩm:** 30 sản phẩm (đồ ăn có thương hiệu, vài đồ dùng) có ảnh thật ở `FE/public/products/` (đường dẫn `/products/...`; nguồn và giấy phép ở `FE/public/products/SOURCES.md`; ảnh khớp loại hàng, không phải đúng bao bì Việt). Các sản phẩm còn lại dùng ảnh minh họa chung theo danh mục (`/imgs/...`). **Cần có thư mục `FE/public/products/` trong code (pull `dev`) thì mới thấy ảnh.** Thuốc kê đơn và vaccine không hiện ở cửa hàng; thuốc không kê đơn và vaccine có hiện nhưng giỏ online chỉ nhận hàng hóa (BR-BH-07).
- **Chưa có** lượt khám, hóa đơn bán lẻ, đơn online, ca thu ngân mới: chúng cần chạy đúng giờ mở cửa (xem `FE/scripts/seed-dev/cli-phase2.ts`, nằm ngoài git).
- Dump chứa dữ liệu giả của dev, không có khóa API hay mật khẩu thật. Khóa Gemini, VNPay… vẫn lấy từ biến môi trường của từng người.
- **Hàng đợi thông báo chưa gửi:** ảnh được chụp khi mọi cron tắt nên `notification_outbox` còn 18 dòng `PENDING`. Lần đầu bật BE sau khi restore, các dòng này được gửi dồn (tin trong ứng dụng và email tới SMTP bạn cấu hình; Mailpit thì vô hại).
- Có một phân ca `CANCELLED` thừa (Ngô Thị Lan, 01/12, Ca sáng, lý do "Ca thừa do seed chạy lặp, hủy.") do một lần chạy seed lỗi; vô hại, chỉ là dòng lịch sử.
- **Lần đầu bật BE bình thường sau khi restore**, chỉ mục tri thức AI (`AI_INDEXING_ENABLED` mặc định `true`) sẽ nạp lại các bài viết mới xuất bản và có thể cả dịch vụ mới: tốn lượt gọi Gemini embedding. Demo không cần AI thì chạy với `AI_INDEXING_ENABLED=false`.
- Không sửa tay file `.dump`. Muốn thêm dữ liệu: chạy BE, thêm qua API hoặc giao diện, rồi xuất lại ảnh mới (mục dưới) và nói cả nhóm restore lại.
- Mỗi lần xuất lại là thêm một file nhị phân ~1 MB vào lịch sử git: chỉ xuất lại khi dữ liệu đổi thật.

## Xuất lại ảnh (người giữ DB chuẩn)

```bash
docker exec <container> pg_dump -U postgres -Fc --no-owner --no-privileges petcare_seed > BE/scripts/demo-db/petcare_demo.dump
```

Trước khi xuất: tắt BE đang nối DB đó, và thử restore vào một DB tạm (lệnh ở trên) rồi `drop` nó.
