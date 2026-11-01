# ADR-0088: Kho — cờ `expiringSoon` của dòng tồn và của lô (inventory-v1 #11–14)

- **Trạng thái:** **Proposed (chờ BE-2 xác nhận)**
- **Ngày:** 2026-10-31
- **Liên quan:**
  - inventory-v1 #11 `GET /inventory`, #12 `GET /inventory/{productId}`, #13 `GET /inventory/{productId}/lots`, #14 `PUT /inventory/{productId}/min-quantity`; 02 BR-KO-01, BR-KO-07; UC75; ST08.
  - ADR-0042 §2 (hết hạn / FEFO) và §6 (xem tồn, bộ lọc `expiringWithinDays`), ADR-0087 G13 (lô hết hạn: `quantity > 0 AND expiry_date ≤ hôm nay + N`), ADR-0004 / 0057 ([CFG] đọc lúc chạy), ADR-0015 (`Clock`), ADR-0038 (SQL chỉ ghép điều kiện đã gửi), ADR-0040 (Swagger = hợp đồng).
  - CLAUDE.md quy tắc 10 / BR-QT-13 (giá trị [CFG] chỉ áp cho bản ghi tạo mới sau thay đổi) — **không áp** ở đây, xem §3.
- **Triển khai:**
  - Người viết: FE-1 (Hiến), **được người dùng cho phép sửa module `inventory` của BE-2** cho việc này; BE-2 chưa xem. Mọi file dưới đây là file của BE-2.
  - `inventory/dto/{InventoryItemResponse, StockLotResponse}` (+ trường `expiringSoon` ở cuối record); `inventory/repository/InventoryQueryRepository` (`findPage` / `findItem` / `findLots` nhận thêm `warnDate`, hàm thuần `itemExpiringSoon` / `lotExpiringSoon`, `mapItem` / `mapLot`); `inventory/service/InventoryService` (thêm `SystemConfigApi`, `warnDate(today)`). `InventoryController` **không đổi chữ ký**.
  - Hợp đồng: `docs/api/generator/m_inventory.py` (schema `InventoryItem`, `StockLot`) → `inventory-v1.md`, `openapi/inventory-v1.yaml` sinh lại bằng `generate.py inventory`.
  - Test: unit `InventoryServiceTest` (5, mới), `InventoryQueryRepositoryTest` (9, mới); IT `InventoryViewIT` (sửa 4 chỗ, thêm 3 ca).

## Bối cảnh

Màn hình kho của FE tô vàng dòng "sắp hết hạn" bằng một hằng 30 ngày chép trong code FE. Con số đúng là [CFG] `stock.expiry_warning_days` (BR-KO-07, mặc định 30, biên 7–180, ADMIN đổi được) — ST08 (`StockAlertDigestService`) và báo cáo (5) (`ReportService`) đã đọc nó. FE không đọc được [CFG] (UC10 chỉ ADMIN) nên khi ADMIN đổi N, badge trên màn hình kho lệch với thông báo ST08 và báo cáo. Cần BE trả sẵn kết quả.

## Quyết định

### 1. Ngữ nghĩa (một vị từ, cùng vị từ với bộ lọc `expiringWithinDays`)

`warnDate = hôm nay + N`, `hôm nay = LocalDate.now(clock)` (Asia/Ho_Chi_Minh), `N` = [CFG] `stock.expiry_warning_days`.

- **Dòng tồn** (`InventoryItemResponse.expiringSoon`): có **ít nhất một lô còn tồn** (`quantity > 0`) với `expiry_date IS NOT NULL AND expiry_date <= warnDate`, **gồm cả lô đã hết hạn còn tồn**. Lô số dư 0, lô mặc định (không hạn), sản phẩm chưa có dòng tồn → `false`.
- **Lô** (`StockLotResponse.expiringSoon`): `expiryDate != null && !expiryDate.isAfter(warnDate)`; lô đã hết hạn cũng `true`; lô mặc định luôn `false`. Cùng tên, cùng nghĩa với cờ của dòng tồn (một lô `true` ⇒ dòng tồn của nó `true`).
- FE đã kiểm `expiredQuantity > 0` / `expired` trước nên thứ tự hiển thị đỏ (đã hết hạn) > vàng (`expiringSoon`) > xanh giữ nguyên; cờ mới không đổi nghĩa của `expired` / `expiredQuantity` / `nearestExpiryDate`.
- Ba đường đọc dùng chung: `GET /inventory`, `GET /inventory/{id}` và phản hồi của `PUT …/min-quantity` (đi qua `getItem`) — một `InventoryItemResponse`, một `mapItem`.

### 2. Tính trong Java từ cột đã có, không thêm SQL

SQL của dòng tồn đã có hai cột: `expired` = `SUM(quantity) FILTER (expiry_date < today)` và `nearest` = `MIN(expiry_date) FILTER (quantity > 0 AND expiry_date >= today)`. Vì `stock_lots.quantity >= 0` (CHECK), `expired > 0` ⇔ có lô hết hạn còn tồn. Công thức:

```
itemExpiringSoon(expired, nearest, warnDate) = expired > 0 || lotExpiringSoon(nearest, warnDate)
lotExpiringSoon(expiry, warnDate)            = expiry != null && !expiry.isAfter(warnDate)
```

Tương đương `EXISTS (… quantity > 0 AND expiry_date IS NOT NULL AND expiry_date <= warnDate)` của bộ lọc ở mọi N ≥ 0 (lô hết hạn có `expiry < today ≤ warnDate`; lô chưa hết hạn nằm trong `nearest`); [CFG] có biên dưới 7 nên N luôn ≥ 7. Cờ của lô tính trong Java từ `expiryDate` đã có, không thêm bind SQL. Hai hàm thuần `static` (package-private) để test không cần DB; `itemExpiringSoon` dùng lại `lotExpiringSoon` nên **một dòng duy nhất giữ biên N cho cả hai cờ** (xem Mutation 1 / 4).

### 3. [CFG] đọc một lần mỗi request, không snapshot

`InventoryService.warnDate(today)` gọi `configs.getInt(ConfigKey.STOCK_EXPIRY_WARNING_DAYS)` **đúng một lần** mỗi `listInventory` / `getItem` / `listLots` (`setMinQuantity` đi qua `getItem`), lấy `today` **một lần** rồi truyền cùng `today` cho bộ lọc và cùng `warnDate` cho mọi dòng của trang. Không cache trong service. Đây là **view đọc, không phải bản ghi tạo mới**, nên quy tắc "snapshot [CFG] vào dòng" (CLAUDE.md quy tắc 10, BR-QT-13) không áp; cách đọc theo ADR-0057 ("áp dụng từ lần kiểm kế"): ADMIN đổi N thì request kế tiếp đã dùng giá trị mới (`SystemConfigService.reload()` sau commit).

### 4. Trả cờ, không trả N

Không thêm trường số ngày vào phản hồi: [CFG] chỉ ADMIN được đọc (UC10); A05–A08 chỉ cần biết "có sắp hết hạn không", không cần biết ngưỡng.

### 5. Chữ ký repository

`findPage(Filter, LocalDate warnDate, offset, limit)`, `findItem(branchId, productId, today, warnDate)`, `findLots(branchId, productId, today, warnDate)`; `Filter` và `count` giữ nguyên (đếm không cần cờ). `ITEM_MAPPER` / `LOT_MAPPER` hằng số thành mapper nhận `warnDate`. Nơi gọi duy nhất là `InventoryService` (đã grep `BE/src` main + test).

## Lý do & phương án loại

- **Không đọc [CFG] ở controller / FE đọc [CFG]:** controller không có logic; FE không có quyền đọc [CFG].
- **Không `BOOL_OR(...)` / `EXISTS` trong SQL:** phải bind `:warnDate` ở 3 chỗ (`findPage`, `findItem`, `findLots`) và thêm một cột tính vào lateral mà không có lợi chỉ mục (lô của một dòng tồn ít, lateral hiện tại đã quét hết). Hai cột sẵn có là đủ.
- **Tên khác cho cờ lô (`nearExpiry`, `warning`):** loại. Hai cờ cùng nghĩa nên cùng tên để FE dùng chung một hàm tô màu.
- **Cờ lô loại trừ lô đã hết hạn (`expiringSoon = hạn trong (hôm nay, warnDate]`):** loại. Khác với bộ lọc (G13 gồm lô đã hết hạn) và với ST08; FE đã kiểm `expired` trước nên không cần loại trừ ở BE.
- **Snapshot N vào `inventory_items` / `stock_lots`:** loại, xem §3 (view đọc; sẽ phải cập nhật cả bảng mỗi lần ADMIN đổi N).
- **Trả thêm `warningDays` cho FE hiển thị "trong N ngày":** loại (§4); FE hiển thị ngày hết hạn thật (`nearestExpiryDate` / `expiryDate`).

## Hệ quả

- Hợp đồng đổi **thêm trường bắt buộc** `expiringSoon` vào response `InventoryItem` và `StockLot` (không đổi request, không đổi endpoint; 17 endpoint inventory, 230 endpoint toàn bộ). FE gọi API cũ (mock không có cờ) sẽ nhận `undefined` → xem là `false`; FE-1 sửa mock và bỏ hằng 30 riêng.
- `InventoryService` thêm phụ thuộc `SystemConfigApi` (`identity.api`, đã dùng ở `StockAlertDigestService` — không tạo interface chéo mới). Một lần đọc map trong bộ nhớ mỗi request, không thêm truy vấn DB.
- `OpenApiContractParityIT` so cây tên property của `data` với yaml: Java và hợp đồng đổi cùng một lúc.
- Cache FE (React Query) có thể cũ: qua nửa đêm giờ VN hoặc khi ADMIN đổi N thì badge đổi ở lần tải lại kế tiếp (stale time của truy vấn) — chấp nhận, không đẩy mới.

## Kiểm chứng

- Unit: `InventoryServiceTest` (5): [CFG] đọc đúng 1 lần mỗi `listInventory` / `getItem` / `listLots` / `setMinQuantity`; `warnDate = today + N` truyền xuống repository (`ArgumentCaptor`, N = 45 để hằng 30 không lọt), `Filter.today` cùng `today`; đổi [CFG] giữa hai request thì request sau dùng giá trị mới. `InventoryQueryRepositoryTest` (9): hai hàm thuần (hết hạn còn tồn → true, biên `warnDate` → true, `warnDate + 1` → false, null → false, lô hết hạn → true, lô không hạn không ném) và hai mapper đọc đúng cột `expired` / `nearest` / `expiry_date`.
- IT (Postgres thật): `InventoryViewIT` (19 ca: 16 cũ, 4 chỗ sửa tập khóa JSON và giá trị cờ, 3 ca mới) và `OpenApiContractParityIT` (11) xanh. 3 ca mới (mỗi ca đổi [CFG] bằng `UPDATE system_configs` + `SystemConfigService.reload()`, khôi phục trong `finally`): `expiringSoonOfAnItemFollowsTheWarningWindowOnEveryRead` (N = 30; mỗi sản phẩm một ca: lô +10 → true, đúng biên +30 → true, +31 → false, +200 → false, lô −3 qty 4 → true kèm `expiredQuantity = 4`, lô +10 qty 0 → false, GOODS lô mặc định → false, chưa có dòng tồn → false; cùng cờ qua `GET /{id}`, `GET ?q=` và `PUT …/min-quantity`), `expiringSoonFollowsAChangedConfigWithoutExposingTheDays` (N = 7: lô +10 false, lô +7 true; N = 180: lô +100 true; không có khóa `warningDays`), `expiringSoonOfALotFollowsTheWindowIncludingExpiredLotsButNeverTheDefaultLot` (lô −3 / +10 / +30 true, +31 / +40 false, lô mặc định false). Hạn chế: `InventoryViewIT` tính ngày bằng `LocalDate.now(ZONE)` giờ thật (không dùng `MutableClock`), nên chạy sát 00:00 giờ VN thì biên có thể lệch một ngày.
- Hợp đồng: `py docs/api/generator/generate.py inventory` rồi `node docs/api/check-contracts.mjs` → `ALL CONTRACTS PASS — 12 file, 230 operations`; chỉ `inventory-v1.md` (2/2 dòng) và `openapi/inventory-v1.yaml` (8/2) đổi.

## Mutation

Mỗi cái sửa bằng tay → chạy `InventoryServiceTest` + `InventoryQueryRepositoryTest` → ghi ca đỏ → hoàn tác.

| # | Sửa | Ca đỏ |
|---|---|---|
| 1 | `!expiry.isAfter(warnDate)` → `expiry.isBefore(warnDate)` (mất biên N; dòng này giữ biên cho **cả** cờ lô và cờ dòng tồn, nên (1) và (4: đổi sang `isBefore`) là cùng một sửa) | `itemWhoseNearestLotExpiresOnTheWarnDateIsExpiringSoon`, `lotExpiringOnTheWarnDateOrBeforeIsFlaggedIncludingAnAlreadyExpiredLot`, `itemMapperFlagsFromTheExpiredAndNearestColumns` |
| 2 | bỏ vế `expiredQty > 0` | `itemWithStockInAnExpiredLotIsExpiringSoonEvenWithoutAnyUpcomingLot`, `itemMapperFlagsFromTheExpiredAndNearestColumns` |
| 3 | `configs.getInt(...)` → hằng `30` (N test = 45) | cả 5 ca `InventoryServiceTest`: `listReadsTheConfigOnceAndPassesTodayPlusNAlongsideTheFilterToday`, `getItemReadsTheConfigOnceAndPassesTodayPlusN`, `listLotsReadsTheConfigOnceAndPassesTodayPlusN`, `setMinQuantityAnswersWithTheSameFlagRuleAsGetItem`, `aChangedConfigAppliesToTheNextRequestBecauseTheServiceKeepsNoCopy` |
| 4a | bỏ kiểm `expiry != null` (lô mặc định) | NPE ở `lotWithoutExpiryDateIsNeverFlaggedAndDoesNotThrow`, `itemWithNoExpiredStockAndNoDatedLotIsNot`, `lotMapperFlagsFromTheExpiryDateColumn`, `itemMapperFlagsFromTheExpiredAndNearestColumns` |

Cùng ba sửa (1), (2), (3) chạy thêm ở mức IT (`InventoryViewIT`, Postgres thật), mỗi lần hoàn tác sau khi ghi:

| # | Sửa | Ca đỏ (IT) |
|---|---|---|
| 1 | `isBefore` thay `!isAfter` | `expiringSoonOfAnItemFollowsTheWarningWindowOnEveryRead` (biên +30), `expiringSoonOfALotFollowsTheWindowIncludingExpiredLotsButNeverTheDefaultLot` (lô +30), `expiringSoonFollowsAChangedConfigWithoutExposingTheDays` (lô đúng +7) |
| 2 | bỏ `expiredQty > 0` | `expiringSoonOfAnItemFollowsTheWarningWindowOnEveryRead` (lô −3 qty 4 phải `true`) |
| 3 | hằng `30` thay `configs.getInt` | `expiringSoonFollowsAChangedConfigWithoutExposingTheDays` (N = 7 mà lô +10 vẫn `true`) |

Mutation 4a chỉ chạy ở mức unit.

## Nợ để lại

- **Bộ lọc `expiringWithinDays` giữ nguyên** (quyết định F2: không thêm tham số lọc `expiringSoon`). Checkbox "sắp hết hạn" ở FE vẫn gửi hằng 30 → khi ADMIN đổi [CFG], badge (theo [CFG]) và bộ lọc (theo 30) **lệch nhau**. **F1 (đề xuất cho BE-2 quyết):** thêm query param `expiringSoon=true` ở `GET /inventory` dùng cùng `warnDate` (một câu `EXISTS` bind `warnDate`, không `:x IS NULL OR`), giữ `expiringWithinDays` cho báo cáo / API khác; FE bỏ hẳn hằng 30. Cần BE-2 xác nhận: đổi hợp đồng thêm một query param + ca IT.
- ADR này **Proposed**: BE-2 chưa xem; nếu BE-2 muốn ngữ nghĩa khác (ví dụ lô hết hạn không tính là "sắp") thì sửa `lotExpiringSoon` / `itemExpiringSoon` (hai hàm thuần, 1 dòng mỗi hàm) và hợp đồng.
- Cờ không có ở các nơi khác đọc tồn (POS / kê đơn / tiêm dùng `StockQueryApi.availableQuantity`, không đổi).
- Cache FE cũ sau nửa đêm / sau khi ADMIN đổi N: chấp nhận (xem Hệ quả).
