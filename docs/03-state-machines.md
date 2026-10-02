# Pet Care Ecosystem — State Machine

> Mô tả trạng thái và vòng đời của các đối tượng nghiệp vụ. Đi kèm `business-rules.md`, `use-case.md`, `domain-model.md`, `erd.md`. **Thứ tự nguồn gốc khi mâu thuẫn:** `business-rules.md` → `state-machine.md` → `domain-model.md` → `erd.md`; file này phải được sửa theo business-rules.

## Quy ước

- **Phạm vi file:** chỉ gồm đối tượng có chuyển trạng thái kèm điều kiện phức tạp hoặc kéo theo đối tượng khác. Đối tượng có vòng đời tuyến tính đơn giản (Bài viết — BR-BV-02, Feedback — BR-DG-04) xem trực tiếp trong business-rules. Liên hệ đã bỏ ở v13. Bệnh án không có trạng thái riêng (khóa khi Visit `COMPLETED`).
- **Whitelist:** mọi chuyển trạng thái không có trong bảng đều bị từ chối.
- **Loại trạng thái:** `INIT` khởi tạo · `MID` trung gian · `FINAL` trạng thái cuối, không chuyển tiếp.
- **Người kích hoạt:** mã actor (`A06`), tác vụ hệ thống (`ST05`), hoặc `SYS ← <Đối tượng>#<số>` khi là hệ quả của một chuyển trạng thái khác.
- **Hệ quả liên đối tượng chỉ ghi ở đối tượng phát ra sự kiện.** Đối tượng bị kéo theo ghi `SYS ← …` để trỏ về nguồn. Các thay đổi trong cùng một sự kiện thực hiện trong **một transaction**.
- **Chuyển `X → X`** là thao tác không đổi trạng thái nhưng có điều kiện riêng (đổi giờ, gán lại, gia hạn).
- Tên bảng/cột (`appointments.status`…) là gợi ý đặt tên; giá trị trạng thái là enum dùng trong code.
- **[CFG]** là tham số cấu hình (BR-QT-13); 🆕 là đề xuất chưa được duyệt riêng.

| # | Đối tượng | Bảng.cột | Module | Tầng |
|---|---|---|---|---|
| 1 | Tài khoản | `accounts.status`, `accounts.is_locked` | TK, QT | 1 |
| 2 | Chi nhánh | `branches.status` | CN | 1 |
| 3 | Lịch hẹn | `appointments.status` | LH | 1 |
| 4 | Visit | `visits.status` | TN, KB | 1 |
| 5 | Order | `orders.status` (+ `orders.source`) | BH, TG | 1 |
| 6 | Đặt chỗ lưu trú | `boarding_bookings.status` | LT | 2 |
| 7 | Chuồng | `kennels.status` | LT | 2 |
| 8 | Ca thu ngân | `cashier_shifts.status` | TG | 1 |
| 9 | Phiếu nhập kho | `stock_receipts.status` | KO | 2 |
| 10 | Care Task | `care_tasks.status` | TB | 1 |

---

## 1. Tài khoản
`accounts.status` + `accounts.is_locked` · TK, QT · Tầng 1

Trạng thái `LOCKED` trong business-rules được cài bằng cờ `is_locked = true`, độc lập với `status`. Vì vậy mở khóa tự trở về trạng thái trước đó (BR-QT-12). Các trường phụ không phải trạng thái: `locked_until` (khóa tạm do đăng nhập sai, ST01), `must_change_password` (BR-TK-17). *(v16)* Bỏ `pending_customer_id`: tài khoản khách luôn có hồ sơ online; trạng thái chờ liên kết là cờ `link_decision_pending` trên hồ sơ khách (BR-TK-19).

| Trạng thái | Loại | Ý nghĩa | Ràng buộc khi ở trạng thái này |
|---|---|---|---|
| `PENDING` | INIT | Đã đăng ký, chưa xác thực OTP | Không đăng nhập được; đăng nhập thì chuyển sang màn hình OTP. Không dùng Quên mật khẩu |
| `ACTIVE` | MID | Đang hoạt động | Nếu `is_locked` hoặc còn `locked_until` thì không đăng nhập được. Hồ sơ khách còn cờ `link_decision_pending` thì ẩn chức năng cần hồ sơ khách |
| `DISABLED` | MID | Nhân viên bị vô hiệu hóa | Không đăng nhập được; dữ liệu lịch sử giữ nguyên. Không phải trạng thái cuối |

| # | Từ → Sang | Sự kiện | Người kích hoạt | Điều kiện | Hệ quả | Nguồn |
|---|---|---|---|---|---|---|
| 1 | — → `PENDING` | Đăng ký | A01 | Email không trùng tài khoản khác; tích ≥ 18 tuổi và đồng ý điều khoản; mật khẩu hợp lệ. SĐT không bắt buộc, không kiểm tra trùng | Gửi OTP. Tạo hồ sơ khách online gắn tài khoản (họ tên, SĐT nếu có) | BR-TK-01, 02, 03, BR-KH-01 · UC01 |
| 2 | `PENDING` → `ACTIVE` | Xác thực OTP | A01 | OTP đúng, còn hạn, chưa quá 5 lần sai | SĐT của hồ sơ online trùng ít nhất 1 hồ sơ tại quầy chưa liên kết: đặt `link_decision_pending = true` và đề nghị liên kết | BR-TK-05, 06, 19 · UC02 |
| 3 | `PENDING` → *(xóa)* | Quá 24h **[CFG]** chưa xác thực | ST02 | — | Xóa tài khoản và hồ sơ online tạo kèm để email đăng ký lại được | BR-TK-08 |
| 4 | — → `ACTIVE` | Tạo tài khoản nhân viên | A03, A04, A05 | Đúng phân cấp tạo; A05–A08 phải có đúng 1 chi nhánh `DRAFT`/`ACTIVE` | `must_change_password = true`; sinh mật khẩu ngẫu nhiên gửi email | BR-QT-01, 02, 03 · UC08 |
| 5 | `ACTIVE` → `DISABLED` | Vô hiệu hóa | A03, A04, A05 | Là nhân viên trong phạm vi; không phải chính mình; `is_locked = false`; không có Visit được gán chưa kết thúc, không có ca thu ngân `OPEN`; không phải BRANCH_MANAGER `ACTIVE` cuối cùng của chi nhánh `ACTIVE` | Hủy mọi phiên | BR-QT-04, 07, 08, 09, 12 · UC08 |
| 6 | `DISABLED` → `ACTIVE` | Kích hoạt lại | A03, A04, A05 | Cùng phạm vi với vô hiệu hóa; `is_locked = false`; chi nhánh hợp lệ | `must_change_password = true`; cấp mật khẩu tạm mới | BR-QT-10 · UC08 |
| 7 | `is_locked`: false → true | Khóa | A03 | Không phải chính mình; có lý do | Hủy mọi phiên. Liệt kê quy trình dở dang; Visit `IN_PROGRESS` đánh dấu cần gán lại. Thông báo BRANCH_MANAGER (nhân viên), SUPER_MANAGER (người bị khóa là BRANCH_MANAGER) hoặc lễ tân (khách). Ghi audit | BR-QT-11, BR-TN-08 · UC09 |
| 8 | `is_locked`: true → false | Mở khóa | A03 | Có lý do | `status` giữ nguyên giá trị trước khi khóa. Ghi audit | BR-QT-11, 12 · UC09 |

## 2. Chi nhánh
`branches.status` · CN · Tầng 1

| Trạng thái | Loại | Ý nghĩa | Ràng buộc khi ở trạng thái này |
|---|---|---|---|
| `DRAFT` | INIT | Đang chuẩn bị | Không hiển thị công khai; được gán nhân viên và cấu hình giờ mở cửa |
| `ACTIVE` | MID | Đang hoạt động | Hiển thị công khai; nhận đặt lịch, lưu trú, tiếp nhận. Luôn có ≥ 1 BRANCH_MANAGER `ACTIVE` (trừ trường hợp bị khóa, BR-QT-04) |

Tạm ngừng / đóng cửa (UC13) thuộc tầng 3. Cờ nhận cấp cứu ngoài giờ là thuộc tính, không phải trạng thái.

| # | Từ → Sang | Sự kiện | Người kích hoạt | Điều kiện | Hệ quả | Nguồn |
|---|---|---|---|---|---|---|
| 1 | — → `DRAFT` | Tạo chi nhánh | A04 | Có tên, địa chỉ, SĐT, tọa độ | — | BR-CN-01 · UC12 |
| 2 | `DRAFT` → `ACTIVE` | Kích hoạt | A04 | Có ≥ 1 BRANCH_MANAGER; đã cấu hình giờ mở cửa | Hiển thị công khai; bắt đầu sinh khung giờ đặt lịch | BR-CN-01, BR-QT-04 · UC12 |

## 3. Lịch hẹn
`appointments.status` · LH · Tầng 1

| Trạng thái | Loại | Ý nghĩa | Ràng buộc khi ở trạng thái này |
|---|---|---|---|
| `BOOKED` | INIT | Đã đặt, chờ khách đến | Chiếm quota. Đổi khung giờ tối đa 3 lần **[CFG]** |
| `CHECKED_IN` | MID | Đã tiếp nhận, có Visit | Không đổi giờ; khách không tự hủy |
| `COMPLETED` | FINAL | Lượt đã hoàn tất | Không phụ thuộc thanh toán |
| `CANCELLED` | FINAL | Đã hủy | Có cờ `late_cancel` |
| `NO_SHOW` | FINAL | Không đến | Tính vào hạn chế đặt online (BR-LH-09) |

| # | Từ → Sang | Sự kiện | Người kích hoạt | Điều kiện | Hệ quả | Nguồn |
|---|---|---|---|---|---|---|
| 1 | — → `BOOKED` | Đặt lịch | A02, A06 | Dịch vụ nhóm Khám/Tiêm hoặc Thẩm mỹ, đang bật tại chi nhánh `ACTIVE`; khung còn quota; khách: trước ≥ 24h, xa nhất 30 ngày, không bị hạn chế online, tài khoản đã liên kết hồ sơ; thú ≤ 2 lịch `BOOKED`, không trùng nhóm trong ngày, không trùng khung; thú chưa mất | — | BR-LH-01…05, 09 · UC39 |
| 2 | `BOOKED` → `BOOKED` | Đổi khung giờ | A02, A06 | Cùng chi nhánh; khách: trước ≥ 12h và khung mới thỏa BR-LH-04; còn lượt đổi; khung mới còn quota | Tăng số lần đổi; giữ mã lịch hẹn | BR-LH-06 · UC40 |
| 3 | `BOOKED` → `CHECKED_IN` | Tiếp nhận | SYS ← Visit#1 | — | — | BR-TN-01, 02 |
| 4 | `BOOKED` → `CANCELLED` | Khách / lễ tân hủy | A02, A06 | Trước giờ hẹn | `late_cancel = true` nếu còn < 12h **[CFG]** | BR-LH-07 · UC40 |
| 5 | `BOOKED` → `CANCELLED` | Phòng khám hủy | SYS ← ngày nghỉ / thu hẹp giờ hủy hàng loạt (UC14), thú đã mất, chuyển chủ | — | `late_cancel = false`; không tính lần đổi; thông báo khách | BR-LH-10, BR-CN-04, BR-KH-05, 08 |
| 6 | `BOOKED` → `NO_SHOW` | Quá giờ hẹn 30 phút **[CFG]** | ST05 | Chưa check-in | — | BR-LH-08 |
| 7 | `CHECKED_IN` → `COMPLETED` | Hoàn tất lượt | SYS ← Visit#5 | — | — | BR-LH-11 |
| 8 | `CHECKED_IN` → `CANCELLED` | Hủy lượt khách bỏ về | SYS ← Visit#6 | — | `late_cancel = false` | BR-TN-07 |

## 4. Visit
`visits.status` · TN, KB · Tầng 1

| Trạng thái | Loại | Ý nghĩa | Ràng buộc khi ở trạng thái này |
|---|---|---|---|
| `WAITING` | INIT | Trong hàng đợi, chưa gọi | `assignee_id` có thể trống; lễ tân gán / gán lại tự do; hủy được |
| `IN_PROGRESS` | MID | Nhân viên phụ trách đã gọi lượt | Không hủy được. Chỉ gán lại khi người phụ trách không còn `ACTIVE`. VET ghi bệnh án, kê đơn, tiêm |
| `COMPLETED` | FINAL | Hoàn tất | Bệnh án khóa, chỉ thêm bản bổ sung (ghi audit) |
| `CANCELLED` | FINAL | Hủy khi chưa gọi | — |

| # | Từ → Sang | Sự kiện | Người kích hoạt | Điều kiện | Hệ quả | Nguồn |
|---|---|---|---|---|---|---|
| 1 | — → `WAITING` | Tiếp nhận (check-in lịch hẹn hoặc walk-in) | A06 | Đã chọn 1 thú, thú chưa mất; chi nhánh `ACTIVE` và trong giờ mở cửa, hoặc ngoài giờ có cờ cấp cứu và đánh dấu cấp cứu. Có lịch hẹn: lịch `BOOKED`, đúng chi nhánh, đúng ngày, sớm nhất 30 phút **[CFG]** trước giờ hẹn | Lịch hẹn → `CHECKED_IN` (nếu có). Order (nguồn Visit) → `OPEN` kèm 1 dòng dịch vụ tự sinh, snapshot giá. Xếp hàng đợi: cấp cứu → lịch hẹn → walk-in; trễ 15–30 phút xếp như walk-in | BR-TN-01…04, BR-CN-05 · UC44 |
| 2 | `WAITING` → `WAITING` | Gán / gán lại nhân viên | A06 | Nhân viên `ACTIVE` của chi nhánh, đúng chức vụ (VET: Khám/Tiêm; CARETAKER: Thẩm mỹ) | Cảnh báo nếu offline, không chặn | BR-TN-05, 06, 08 · UC45 |
| 3 | `WAITING` → `IN_PROGRESS` | Gọi lượt | A07, A08 | Lượt được gán cho chính người gọi | — | BR-TN-05 · UC46 |
| 4 | `IN_PROGRESS` → `IN_PROGRESS` | Gán lại lượt đã gọi | A05 (A04 nếu chi nhánh không còn BRANCH_MANAGER `ACTIVE`) | Người phụ trách hiện tại không còn `ACTIVE`; người mới đúng chức vụ; có lý do | Bệnh án cũ giữ nguyên kèm tên người ghi; quyền xóa dòng Order của người cũ chuyển cho người mới. Ghi audit | BR-TN-08 · UC45 |
| 5 | `IN_PROGRESS` → `COMPLETED` | Hoàn tất lượt | Nhân viên phụ trách | Có dịch vụ loại Khám: bắt buộc có chẩn đoán. Chỉ có dịch vụ loại Tiêm: có ≥ 1 mũi tiêm. Thẩm mỹ: không điều kiện thêm | Lịch hẹn → `COMPLETED` (nếu có). Order → `PENDING`. Bệnh án khóa | BR-KB-01, 02, BR-SP-06 · UC48, UC49, UC52 |
| 6 | `WAITING` → `CANCELLED` | Hủy lượt khách bỏ về | A06 | Lượt chưa được gọi | Order → `CANCELLED` (kèm dòng tự sinh). Lịch hẹn → `CANCELLED`, `late_cancel = false` | BR-TN-07 · UC45 |

Sự kiện trong `IN_PROGRESS` không đổi trạng thái nhưng có hệ quả: ghi nhận mũi tiêm thì trừ kho vaccine ngay theo FEFO, sinh dòng Order vaccine, ghi ngày tái chủng và hủy nhắc / Care Task tái chủng của mũi cũ cùng loại vaccine (BR-KB-04, BR-TB-03); **xóa mũi tiêm ghi nhầm** thì hoàn kho vào đúng lô và xóa dòng Order vaccine trong cùng transaction (BR-KB-04); ghi ngày tái khám (BR-KB-06); kê đơn thiếu tồn thì VET chọn giảm số lượng hoặc **mua ngoài** (BR-KB-03).

## 5. Order
`orders.status` + `orders.source` (`VISIT` / `RETAIL` / `BOARDING`) · BH, TG · Tầng 1

| Trạng thái | Loại | Ý nghĩa | Ràng buộc khi ở trạng thái này |
|---|---|---|---|
| `OPEN` | INIT (`VISIT`, `RETAIL`) | Đang thêm dòng | Dòng chỉ xóa bởi người đã thêm (lễ tân xóa được dòng bán lẻ); dòng vaccine không xóa trực tiếp, chỉ xóa kèm khi xóa mũi tiêm (BR-BH-02, BR-KB-04). Giá snapshot khi thêm dòng |
| `PENDING` | MID; INIT với `BOARDING` | Đã chốt, chờ thu tiền | Dòng dịch vụ, thuốc bị khóa; lễ tân chỉ thêm / xóa dòng bán lẻ. **Chưa giao hàng.** Không tự hủy |
| `PAID` | FINAL | Đã thu tiền | Kho đã trừ; được giao hàng / thú. Không hủy, không hoàn tiền (tầng 1) |
| `CANCELLED` | FINAL | Đã hủy | Hủy từ `PENDING` bắt buộc có `cancel_reason` |

| # | Từ → Sang | Sự kiện | Người kích hoạt | Điều kiện | Hệ quả | Nguồn |
|---|---|---|---|---|---|---|
| 1 | — → `OPEN` (`VISIT`) | Tiếp nhận | SYS ← Visit#1 | — | — | BR-BH-01, BR-TN-01 |
| 2 | — → `OPEN` (`RETAIL`) | Tạo đơn bán lẻ | A06 | — | Thêm dòng: cảnh báo nếu thiếu tồn khả dụng; không cho thêm thuốc kê đơn | BR-BH-01, 04, BR-SP-01 · UC66 |
| 3 | — → `PENDING` (`BOARDING`) | Bắt đầu trả thú / kết thúc do thú mất | SYS ← Đặt chỗ#9, #11 | Đặt chỗ chưa có Order `BOARDING` ở `PENDING`; nếu có thì dùng lại và tính lại số đêm | — | BR-BH-01, BR-LT-09, 12 |
| 4 | `OPEN` → `PENDING` (`VISIT`) | Hoàn tất lượt | SYS ← Visit#5 | — | — | BR-KB-02 |
| 5 | `OPEN` → `PENDING` (`RETAIL`) | Chốt đơn | A06 | Có ≥ 1 dòng | — | BR-BH-01 · UC66 |
| 6 | `OPEN` → `CANCELLED` (`VISIT`) | Hủy lượt | SYS ← Visit#6 | — | — | BR-TN-07 |
| 7 | `OPEN` → `CANCELLED` (`RETAIL`) | Hủy đơn chưa chốt | A06 | — | — | BR-BH-01 · UC66 |
| 8 | `PENDING` → `PAID` | Thu tiền | A06 | Lễ tân có ca `OPEN` của chính mình; số tiền = tổng Order; tồn khả dụng đủ cho mọi dòng hàng, thuốc (bỏ qua vaccine). Thu gộp được nhiều Order của cùng khách, cùng chi nhánh | Trừ kho FEFO (ST06) cùng transaction với kiểm tra tồn; ghi giao dịch vào ca; ghi audit; được giao hàng | BR-TG-01…04, BR-BH-04, 06 · UC70 |
| 9 | `PENDING` → `CANCELLED` (`BOARDING`) | Hủy phiên trả thú | A06 | Thú còn trong chuồng (đặt chỗ `CHECKED_IN`/`OVERDUE`); có lý do | Đặt chỗ giữ nguyên trạng thái, tiếp tục tính đêm. Ghi audit | BR-LT-09 · UC59 |
| 10 | `PENDING` → `CANCELLED` | Hủy Order khách không thanh toán | A05 | Nguồn `VISIT` / `RETAIL`, hoặc `BOARDING` khi thú không còn trong chuồng; có lý do | Không đổi kho, bệnh án, lịch hẹn, lưu trú. Tính là thất thu (BR-BC-02). Ghi audit | BR-BH-05 · UC66 |

Trong `PENDING`, thiếu tồn lúc thu: từ chối thu; dòng bán lẻ thì lễ tân xóa / giảm, dòng thuốc kê đơn thì chuyển sang mua ngoài (ghi audit). Không đổi trạng thái (BR-BH-04).

## 6. Đặt chỗ lưu trú
`boarding_bookings.status` · LT · Tầng 2

| Trạng thái | Loại | Ý nghĩa | Ràng buộc khi ở trạng thái này |
|---|---|---|---|
| `BOOKED` | INIT | Đã đặt theo loại chuồng | Chiếm sức chứa các đêm từ ngày nhận đến ngày trả dự kiến. Đơn giá theo đêm đã snapshot |
| `CHECKED_IN` | MID | Thú đang ở một chuồng cụ thể | Nhật ký chăm sóc ≥ 1 mục/ngày **[CFG]**. Order lưu trú không bị BRANCH_MANAGER hủy |
| `OVERDUE` | MID | Quá hạn đón | Vẫn tính đêm; chiếm chỗ đêm hiện tại + 2 đêm **[CFG]**; thông báo khách mỗi ngày. Không gia hạn |
| `CHECKED_OUT` | FINAL | Kết thúc lưu trú | Có `end_reason`: `TRẢ_THÚ` hoặc `THÚ_MẤT` |
| `CANCELLED` | FINAL | Đã hủy | Có cờ `late_cancel` |
| `NO_SHOW` | FINAL | Không đến nhận | Tính vào hạn chế đặt online (BR-LH-09) |

| # | Từ → Sang | Sự kiện | Người kích hoạt | Điều kiện | Hệ quả | Nguồn |
|---|---|---|---|---|---|---|
| 1 | — → `BOOKED` | Đặt chỗ | A02, A06 | Loại chuồng đang bật tại chi nhánh `ACTIVE`; thú đúng loài, cân nặng ≤ tối đa; 1–30 đêm **[CFG]**; còn sức chứa mọi đêm; khách: trước ≥ 1 ngày, xa nhất 60 ngày **[CFG]**, không bị hạn chế online; ngày nhận / trả không phải ngày nghỉ; không chồng ngày với đặt chỗ khác của thú; thú chưa mất | Snapshot giá đêm. Dự kiến thiếu mũi tiêm bắt buộc vào ngày nhận: cảnh báo, gợi ý đặt lịch tiêm | BR-LT-02…05, BR-LH-09 · UC58 |
| 2 | `BOOKED`/`CHECKED_IN` → (giữ nguyên) | Gia hạn ngày trả | A02, A06 | Trước ngày trả dự kiến; còn chỗ các đêm thêm; tổng ≤ 30 đêm | — | BR-LT-06 · UC58 |
| 3 | `BOOKED` → `CHECKED_IN` | Nhận thú | A06, A08 | Trong giờ mở cửa; đủ mũi bắt buộc khi lưu trú, chưa quá ngày tái chủng (chỉ tính mũi tiêm tại hệ thống); có chuồng `AVAILABLE` **đúng loại đã đặt** (không gán loại khác); nhập cân nặng. Nhận sớm được nếu còn chỗ | Chuồng → `OCCUPIED`. Ghi tình trạng lúc nhận; cân nặng vào lịch sử. Thiếu tiêm: từ chối, lễ tân tiếp nhận walk-in để tiêm trước. Hết chuồng đúng loại: từ chối nhận, xử lý theo #5 | BR-LT-05, 08, BR-KH-04 · UC59 |
| 4 | `BOOKED` → `CANCELLED` | Khách / lễ tân hủy | A02, A06 | Trước ngày nhận | `late_cancel = true` nếu còn < 24h **[CFG]** | BR-LT-06 · UC58 |
| 5 | `BOOKED` → `CANCELLED` | Phòng khám hủy | SYS ← không còn chuồng `AVAILABLE` đúng loại lúc nhận, ngày nghỉ hủy hàng loạt, thú đã mất, chuyển chủ | — | `late_cancel = false`; thông báo khách; hết chuồng thì thông báo BRANCH_MANAGER | BR-LT-08, BR-LH-10, BR-KH-05, 08 |
| 6 | `BOOKED` → `NO_SHOW` | Hết giờ làm việc ngày nhận | ST05 | Chưa nhận thú | — | BR-LT-06 |
| 7 | `CHECKED_IN` → `OVERDUE` | Quá giờ trả quy định 12:00 **[CFG]** của ngày trả dự kiến | ST15 | Chưa trả, chưa gia hạn | Thông báo khách mỗi ngày. Quá ≥ 1 ngày: Care Task gọi điện (ST18). Quá ≥ 7 ngày: thông báo BRANCH_MANAGER. Thiếu chỗ cho đặt chỗ nhận trong 2 ngày tới: cảnh báo lễ tân | BR-LT-03, 10 |
| 8 | `CHECKED_IN`/`OVERDUE` → (giữ nguyên) | Ghi nhật ký đánh dấu bất thường | A07, A08 | — | Thông báo ngay khách và lễ tân; lễ tân tiếp nhận walk-in hoặc cấp cứu để VET khám | BR-LT-11, 12 · UC57 |
| 9 | `CHECKED_IN`/`OVERDUE` → (giữ nguyên) | Bắt đầu trả thú | A06 | Trong giờ mở cửa | Order (`BOARDING`) → `PENDING`: số đêm = ngày trả − ngày nhận thực tế, tối thiểu 1; trả sau giờ quy định +1 đêm | BR-LT-04, 09 · UC59 |
| 10 | `CHECKED_IN`/`OVERDUE` → `CHECKED_OUT` | Giao thú (`TRẢ_THÚ`) | A06 | Order lưu trú đã `PAID` — không có ngoại lệ | Chuồng → `AVAILABLE` | BR-LT-09, BR-BH-06 · UC59 |
| 11 | `CHECKED_IN`/`OVERDUE` → `CHECKED_OUT` | Thú mất (`THÚ_MẤT`) | A06 | Ghi rõ diễn biến | Chuồng → `AVAILABLE`. Order (`BOARDING`) → `PENDING`, tính đến ngày mất. Sau đó mới cho đánh dấu thú đã mất (BR-KH-05) | BR-LT-12 · UC59 |

## 7. Chuồng
`kennels.status` · LT · Tầng 2

| Trạng thái | Loại | Ý nghĩa | Ràng buộc khi ở trạng thái này |
|---|---|---|---|
| `AVAILABLE` | INIT | Trống, sẵn sàng | Tính vào sức chứa của loại chuồng |
| `OCCUPIED` | MID | Đang có 1 thú | Tính vào sức chứa. Không chuyển bảo trì, không xóa |
| `MAINTENANCE` | MID | Ngừng sử dụng | Không tính vào sức chứa |

Chuồng **không được gán khi đặt chỗ**; chỉ gán lúc nhận thú (Đặt chỗ#3) và phải **đúng loại chuồng đã đặt**. Xóa chuồng chỉ khi không `OCCUPIED`.

| # | Từ → Sang | Sự kiện | Người kích hoạt | Điều kiện | Hệ quả | Nguồn |
|---|---|---|---|---|---|---|
| 1 | — → `AVAILABLE` | Tạo chuồng | A05 | Mã duy nhất trong chi nhánh; thuộc 1 loại chuồng | Tăng sức chứa | BR-LT-01, BR-SP-04 · UC54 |
| 2 | `AVAILABLE` → `OCCUPIED` | Nhận thú | SYS ← Đặt chỗ#3 | — | — | BR-LT-08 |
| 3 | `OCCUPIED` → `AVAILABLE` | Kết thúc lưu trú | SYS ← Đặt chỗ#10, #11 | — | — | BR-LT-09, 12 |
| 4 | `AVAILABLE` → `MAINTENANCE` | Chuyển bảo trì | A05 | — | Giảm sức chứa. Nếu thiếu chỗ cho đặt chỗ đã có: cảnh báo kèm danh sách, không tự hủy | BR-LT-01 · UC54 |
| 5 | `MAINTENANCE` → `AVAILABLE` | Hết bảo trì | A05 | — | Tăng sức chứa | BR-LT-01 · UC54 |

## 8. Ca thu ngân
`cashier_shifts.status` (+ `cashier_shifts.is_after_hours`) · TG · Tầng 1

Có hai loại ca, phân biệt bằng cờ `is_after_hours` đặt lúc mở ca và không đổi: **ca thường** (trong giờ mở cửa) và **ca ngoài giờ** (thu tiền ca cấp cứu ở chi nhánh có cờ nhận cấp cứu ngoài giờ). Hai loại có cùng trạng thái, chỉ khác điều kiện mở và thời điểm tự chốt.

| Trạng thái | Loại | Ý nghĩa | Ràng buộc khi ở trạng thái này |
|---|---|---|---|
| `OPEN` | INIT | Lễ tân đang thu tiền | Mỗi lễ tân tối đa 1 ca `OPEN` (tính chung hai loại). Chặn vô hiệu hóa tài khoản lễ tân. Ca ngoài giờ được kéo qua nửa đêm, thuộc ngày mở ca |
| `CLOSED` | MID | Đã chốt | Không thu thêm. Có số tiền thực đếm, hoặc `auto_closed = true` |
| `RECONCILED` | FINAL | Đã đối soát | Chênh lệch và ghi chú đã ghi nhận |

| # | Từ → Sang | Sự kiện | Người kích hoạt | Điều kiện | Hệ quả | Nguồn |
|---|---|---|---|---|---|---|
| 1 | — → `OPEN` | Mở ca thường | A06 | Trong khoảng giờ mở cửa hôm nay; không phải ngày nghỉ; chưa có ca `OPEN` của mình | `is_after_hours = false` | BR-TG-01, 05 · UC71 |
| 2 | — → `OPEN` | Mở ca ngoài giờ | A06 | Chi nhánh có cờ nhận cấp cứu ngoài giờ; đang ngoài giờ mở cửa hoặc trong ngày nghỉ; chưa có ca `OPEN` của mình | `is_after_hours = true` | BR-CN-05, BR-TG-01, 05 · UC71 |
| 3 | `OPEN` → `CLOSED` | Chốt ca | A06 | Nhập số tiền thực đếm | Tổng hợp giao dịch theo phương thức | BR-TG-05 · UC71 |
| 4 | `OPEN` → `CLOSED` | Tự chốt ca thường | ST13 | `is_after_hours = false`; quá giờ đóng của khoảng giờ cuối trong ngày + 30 phút **[CFG]** | `auto_closed = true`; thông báo BRANCH_MANAGER | BR-TG-05 |
| 5 | `OPEN` → `CLOSED` | Tự chốt ca ngoài giờ | ST13 | `is_after_hours = true`; đến giờ bắt đầu của khoảng giờ mở cửa kế tiếp của chi nhánh | `auto_closed = true`; thông báo BRANCH_MANAGER | BR-TG-05 |
| 6 | `CLOSED` → `RECONCILED` | Đối soát | A05 | Có số tiền thực đếm (BRANCH_MANAGER nhập nếu ca tự chốt) | Ghi chênh lệch + ghi chú; ghi audit | BR-TG-04, 05 · UC72 |

## 9. Phiếu nhập kho
`stock_receipts.status` · KO · Tầng 2

| Trạng thái | Loại | Ý nghĩa | Ràng buộc khi ở trạng thái này |
|---|---|---|---|
| `DRAFT` | INIT | Đang soạn | Sửa tự do; chưa ảnh hưởng tồn |
| `CONFIRMED` | MID | Đã cộng tồn | Không sửa được |
| `CANCELLED` | FINAL | Đã hủy | Không xóa cứng, giữ dãy số phiếu liên tục |

| # | Từ → Sang | Sự kiện | Người kích hoạt | Điều kiện | Hệ quả | Nguồn |
|---|---|---|---|---|---|---|
| 1 | — → `DRAFT` | Tạo phiếu | A05 | — | — | BR-KO-03 · UC74 |
| 2 | `DRAFT` → `CONFIRMED` | Xác nhận | A05 | Nhà cung cấp đang hợp tác; mỗi dòng số lượng > 0, có giá nhập; sản phẩm quản lý hạn dùng có số lô và hạn dùng sau ngày nhập | Cộng tồn theo lô | BR-KO-02, 03, BR-SP-05 · UC74 |
| 3 | `DRAFT` → `CANCELLED` | Hủy phiếu nháp | A05 | — | Không đổi tồn | BR-KO-03 · UC74 |
| 4 | `CONFIRMED` → `CANCELLED` | Hủy phiếu đã xác nhận | A05 | Tồn hiện tại của từng lô ≥ số lượng trên phiếu | Trừ lại tồn; ghi audit. Không đủ tồn: hướng dẫn dùng điều chỉnh tồn (BR-KO-06) | BR-KO-04 · UC74 |

## 10. Care Task 🆕
`care_tasks.status` (+ `care_tasks.type`: `TÁI_CHỦNG` / `QUÁ_HẠN_TÁI_CHỦNG` / `TÁI_KHÁM` / `QUÁ_HẠN_ĐÓN`) · TB · Tầng 1

| Trạng thái | Loại | Ý nghĩa | Ràng buộc khi ở trạng thái này |
|---|---|---|---|
| `OPEN` | INIT | Chờ lễ tân chi nhánh phụ trách gọi điện (chỉ A06 thực hiện) | Hiển thị trong danh sách việc của chi nhánh |
| `DONE` | FINAL | Đã thực hiện | Có `result`: `LIÊN_HỆ_ĐƯỢC` / `KHÔNG_LIÊN_LẠC_ĐƯỢC`, kèm ghi chú |
| `CANCELLED` | FINAL | Đã hủy | Có lý do |

| # | Từ → Sang | Sự kiện | Người kích hoạt | Điều kiện | Hệ quả | Nguồn |
|---|---|---|---|---|---|---|
| 1 | — → `OPEN` | Sinh task | ST18 | Một trong: đến hạn nhắc tái chủng (`TÁI_CHỦNG`) hoặc tái khám (`TÁI_KHÁM`) và hồ sơ không có email; quá ngày tái chủng 7 ngày **[CFG]** chưa tiêm lại và hồ sơ có SĐT (`QUÁ_HẠN_TÁI_CHỦNG`, tối đa 1 task/mũi); đặt chỗ `OVERDUE` ≥ 1 ngày **[CFG]** (`QUÁ_HẠN_ĐÓN`) | Giao cho lễ tân chi nhánh phụ trách: chi nhánh tiêm gần nhất cùng loại vaccine / chi nhánh của Visit có hẹn tái khám / chi nhánh lưu trú | BR-TB-02, 04, 06, BR-LT-10 |
| 2 | `OPEN` → `DONE` | Thực hiện | A06 | Chọn kết quả, nhập ghi chú | Không liên lạc được: không sinh task gọi lại | BR-TB-05 · UC87 |
| 3 | `OPEN` → `CANCELLED` | Hủy thủ công | A06 | Có lý do | — | BR-TB-05 · UC87 |
| 4 | `OPEN` → `CANCELLED` | Thú đã mất | ST19 | — | — | BR-TB-03, BR-KH-05 |
| 5 | `OPEN` → `CANCELLED` | Mũi đã được tiêm lại | SYS ← Visit (ghi nhận mũi tiêm) | Task loại `TÁI_CHỦNG` / `QUÁ_HẠN_TÁI_CHỦNG` của mũi cũ; mũi mới cùng loại vaccine | — | BR-TB-03, 05 |
| 6 | `OPEN` → `CANCELLED` | Nhắc tái khám bị hủy | SYS ← Lịch hẹn#1, Visit#1 | Task loại `TÁI_KHÁM`; từ ngày lập hẹn, thú có lịch hẹn nhóm Khám/Tiêm `BOOKED` hoặc Visit mới có dịch vụ loại Khám | — | BR-TB-05, 06 |

---

## Phụ lục: chuỗi tác động chính

| Sự kiện nguồn | Kéo theo |
|---|---|
| Visit#1 Tiếp nhận | Lịch hẹn → `CHECKED_IN`; Order (`VISIT`) → `OPEN` |
| Visit#5 Hoàn tất | Lịch hẹn → `COMPLETED`; Order → `PENDING`; bệnh án khóa |
| Visit#6 Hủy lượt | Lịch hẹn → `CANCELLED`; Order → `CANCELLED` |
| Visit — ghi nhận mũi tiêm (`IN_PROGRESS`) | Trừ kho FEFO; dòng Order vaccine; Care Task tái chủng của mũi cũ cùng loại → `CANCELLED` |
| Visit — xóa mũi tiêm ghi nhầm (`IN_PROGRESS`) | Hoàn kho đúng lô; xóa dòng Order vaccine |
| Lịch hẹn#1 / Visit#1 có dịch vụ Khám | Care Task `TÁI_KHÁM` của thú → `CANCELLED`; hủy nhắc tái khám |
| Order#8 Thu tiền | Trừ kho FEFO; giao dịch vào ca thu ngân; cho phép giao hàng / giao thú (Đặt chỗ#10) |
| Đặt chỗ#3 Nhận thú | Chuồng → `OCCUPIED` |
| Đặt chỗ#9 Bắt đầu trả thú | Order (`BOARDING`) → `PENDING` |
| Đặt chỗ#10, #11 Kết thúc lưu trú | Chuồng → `AVAILABLE`; (#11) Order (`BOARDING`) → `PENDING` |
| Thú cưng đánh dấu đã mất (BR-KH-05) | Lịch hẹn, Đặt chỗ `BOOKED` → `CANCELLED` (không tính hủy muộn); Care Task → `CANCELLED`; hủy nhắc tái chủng, tái khám |
| Chuyển chủ thú cưng (BR-KH-08) | Lịch hẹn, Đặt chỗ `BOOKED` → `CANCELLED` (không tính hủy muộn); nhắc tái chủng, tái khám đi theo thú sang chủ mới |
