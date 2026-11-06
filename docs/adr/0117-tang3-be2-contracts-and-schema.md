# ADR-0117: Tầng 3 phần BE-2 — interface chéo, placeholder và migration V43–V48 (T55c)

- **Trạng thái:** Proposed (Hiến viết thay Dũng, 6/11/2026; chờ Dũng xác nhận các giả định A-1…A-3 và câu mở TBD-1…TBD-6 ở mục 5)
- **Ngày:** 2026-11-06
- **Liên quan:** `docs/08-tang3-be2-spec.md` §A–§F (nguồn sự thật, đã duyệt ở T54), `docs/06-module-contracts.md` §1 và §6b, `docs/05-erd.md` §13 mục 26, `docs/07-ai-subsystem.md` §11 (I-1, I-3…I-5, I-7, I-8), ADR-0043 (mã chứng từ), ADR-0018 (index cho FK tới `accounts`), ADR-0081 (CHECK thời điểm ↔ trạng thái), ADR-0110 (T55a), ADR-0113 (T55b: idiom placeholder `default`, `VisitQueryApi.AgeGroup`), **ADR-0116 (T55b làm thay BE-2: Order nguồn `ONLINE`, V42)**.
- **Triển khai:** `V43`…`V48` ở `BE/src/main/resources/db/migration/`; interface và placeholder ở `module/{inventory,sales,customer,catalog,branch,boarding}/api`, mới `module/{shop,payment}/api`, `module/sales/api/{CashierShiftApi,CollectMethod}`, `module/customer/api/CustomerMergedEvent`; `ConfigKey` (+7); test `SchemaMigrationIT` (sửa), `Tier3Be2SchemaIT`, `Tier3Be2ContractWiringIT`, `*Tier3PlaceholderTest`, `*PlaceholderTest`, `ConfigKeyTest`, `SystemConfigIT`, `StockIssueServiceTest` (sửa).

## 1. Dải số Flyway đã dùng (V42–V49 của BE-2)

| V | Nội dung | Bảng / thay đổi | Khóa [CFG] |
|---|---|---|---|
| V42 | **T55b làm thay BE-2** (ADR-0116): Order `ONLINE` | 4 CHECK của `orders` | — |
| V43 | `fees_and_material_norms` (§A) | `shipping_fee_configs` (+1 dòng seed), `branch_home_visit_fees`, `service_material_norms` | — |
| V44 | `online_orders_and_carts` (§B) | `carts`, `cart_items`, `online_orders`, `stock_reservations` | `shop.payment_window_hours`, `shop.auto_confirm_days`, `shop.max_redelivery` |
| V45 | `payment_transactions` (§C) | `payment_transactions`, `payment_reconciliations`, `reconciliation_items` | `payment.txn_ttl_minutes`, `payment.refund_max_attempts` |
| V46 | `returns_and_refunds` (§D) | `return_requests`, `return_request_lines`, `refunds` | `returns.window_days` |
| V47 | `stock_transfers_and_counts` (§E) | `stock_transfers`, `stock_transfer_lines`, `stock_counts`, `stock_count_lines`; sửa 3 CHECK của kho | — |
| V48 | `branch_lifecycle_customer_merge_article_comments` (§F) | `branches` +5 cột và 2 trạng thái, `customers.merged_into_id`, `customer_merges`, `articles` +5 cột và 2 trạng thái, `article_comments` | `content.comment_per_hour` |
| **V49** | **chưa dùng — để dành** cho TBD-1 và TBD-2 (mục 5) | | |

- **Số đã đổi một lần.** Bản nháp đầu của T55c dùng V42–V48 và ADR-0116; T55b commit trước và lấy V42 + ADR-0116 (Order `ONLINE`), nên T55c dời sang V43–V48 / ADR-0117 và **bỏ migration "giá trị mới của cột có sẵn"** vì T55b đã làm đủ: `services.medical_type` + `order_lines.line_type` ở V41, `orders.source`/`single_source`/`paid_payment`/`cancel_type` ở V42.
- Mỗi `[CFG]` seed **cùng file** với bảng của nó thay vì một file `ConfigKey` riêng, để chừa V49. Đủ 7 khóa của `08` §G; 8 khóa còn lại của `10` §1.7 (`complaint.*`, `workforce.*`) thuộc T55b (V40).
- **Không đụng migration đã áp dụng** (V1…V42): 5 CHECK của V1 được `DROP` + `ADD` cùng tên — V47: `ck_stock_movements_movement_type`, `ck_stock_movements_source_type`, `ck_stock_adjustments_reason`; V48: `ck_branches_status`, `ck_articles_status`.
- **Mẫu thông báo của BE-2 không seed ở đây:** T55b seed ở V40 cả họ `ONLINE_ORDER_*`, `PAYMENT_*`, `RETURN_*`, `REFUND_*`, `ARTICLE_REVIEW_*` (Hiến chọn "seed đủ cả BE-1 và BE-2", 6/11); tên mã, kênh, biến là giả định của T55b (ADR-0113), BE-2 xem lại khi cài T63–T65, T73, T75.
- **Mã chứng từ không cần migration** (ADR-0043 mục "Hệ quả"): tiền tố mới `DH`, `TH`, `HT`, `CK`, `KK` chỉ là tham số của `DocumentCodeGenerator.next`. `08` §E và `05` §13 mục 26 gọi bảng là `document_code_sequences`; bảng thật là `document_counters` (V14). Định dạng ở `08` (`DH-yyMMdd-xxxx`) khác định dạng ADR-0043 (`DH-yyMM-00001`); cả hai vừa `VARCHAR(20)`, task cài (T63, T73, T74) chọn một và nếu đổi ADR-0043 thì qua ADR.

## 2. Interface chéo và placeholder

**Idiom.** Theo `06` §1 ("không trả giá trị giả"), cùng cách T55b chọn (ADR-0113):

- Method thêm vào interface **đã có bản thật** (`StockApi`, `StockQueryApi`, `OrderQueryApi`, `CashierShiftQueryApi`, `CustomerQueryApi`, `PetQueryApi`, `CatalogQueryApi`, `BranchQueryApi`, `BoardingQueryApi`): `default` ném `UnsupportedOperationException("<Api>.<method> chưa cài (placeholder T55c, docs/06 §6b); bản thật ở T<n>")`. Khi cài thật, lớp thật ghi đè và **xóa `default`** (method thành trừu tượng, trình biên dịch buộc mọi bên cài đủ).
- Interface **mới** (`OnlineOrderApi` ở `shop.api`, `CashierShiftApi` ở `sales.api`, `PaymentGatewayClient` ở `payment.api`): thêm một bean `*Placeholder` (`@Component`) ném cùng thông điệp. **Xóa lớp `*Placeholder` khi cài thật** (hai bean cùng interface làm hỏng việc tiêm).

**Kiểm tra "lớp bị gãy" (`CLAUDE.md` Gotchas).** Trước khi thêm: `grep` `implements`/`extends` (mọi vị trí trong danh sách) và `new <Api>() {` trong `BE/src` cho 9 interface trên. Bản thật: `StockIssueService`, `StockQueryService`, `OrderQueryService`, `CashierShiftLookupService`, `CustomerQueryService`, `PetQueryService`, `CatalogQueryService`, `BranchQueryService`, `BoardingQueryService`; lớp thay thế trong test: `appointment/JdbcPetCustomerModule implements PetQueryApi, CustomerQueryApi` (BE-1). Vì mọi method mới là `default` nên **không lớp nào gãy, không phải sửa test của BE-1**. Có một test quét reflection phải sửa: `StockIssueServiceTest.everyMethodJoinsTheCallersTransaction` (xem mục 6).

**Chữ ký chốt ở T55c** (đặc tả `08`/`06` chỉ ghi tên và tham số rút gọn; chỗ khác đặc tả được ghi rõ):

| Interface · method | Bản thật | Khác đặc tả |
|---|---|---|
| `StockApi.reserve(branchId, OrderRef, lines)`, `release(OrderRef)`, `consumeReservation(OrderRef, actorId)`, `restock(OrderRef, actorId)` | T63 (17/11) | `orderRef` cụ thể hóa thành `record OrderRef(onlineOrderId, orderId)` (khóa của `stock_reservations` + nguồn của movement `orders/<orderId>`); `reserve` trả `List<Shortage>` như `issueForPayment`; thêm `actorId` vì `stock_movements.created_by NOT NULL` |
| `StockApi.restockToOriginalLot(orderLineId, quantity, actorId)`, `discardReturned(...)` | T73 (30/11) | thêm `actorId` (cùng lý do) |
| `StockApi.transferOut(sourceBranchId, transferId, lines, actorId)` → `List<TransferredLot>`; `transferIn(destBranchId, transferId, lots, actorId)` → id lô đích; `transferCancel(sourceBranchId, transferId, lots, actorId)` | T74 (3/12) | `08` §E ghi "nội bộ BE-2", chưa có chữ ký; `TransferredLot` mang lô nguồn, số lô, hạn dùng để ghi `stock_transfer_lines` |
| `StockQueryApi.findAvailableQuantity(branchId, productIds)` | T63 | đã trừ phần giữ; T54 chốt "mọi nơi tính tồn dùng nó" nên T63 phải chuyển các chỗ gọi `availableQuantity(ies)` |
| `StockQueryApi.findProductIdsInStockAnywhere(ids)` (I-5), `findMovementsOfLot(lotId)`, `sumQuantityByBranch(branchId)` → `StockTotals(productCount, totalQuantity)` | T78, T74, T75 | — |
| `OrderQueryApi.findLinesForReturn(orderId)` → `Optional<ReturnableOrder>`; `findLastPurchaseDates`, `sumSoldUnitsByProduct` (I-8); `countOpenByBranch` | T73, T78, T75 | `ReturnableOrder` chỉ chứa dòng `GOODS`; hạn trả của đơn online tính từ `delivered_at` (shop) nên **không** có ở đây (TBD-6) |
| `CashierShiftQueryApi.hasOpenShift(branchId)` | T75 | trùng tên với `hasOpenShift(cashierAccountId)` đã bỏ ngày 12/10, tham số khác nghĩa; javadoc ghi rõ |
| `CashierShiftApi.recordRefund(shiftId, CollectMethod, amount)` | T73 | interface mới; `CollectMethod { CASH, TRANSFER, GATEWAY }` dùng chung với `OrderQueryApi` (`GATEWAY` không vào ca) |
| `CustomerQueryApi.findAddress(customerId, addressId)` → `Optional<AddressInfo>` | T63 | địa chỉ của khách khác trả rỗng (bên gọi trả 400 `BR-BH-08`) |
| `PetQueryApi.findPetProfile`, `findLivingPetsOfCustomer` (I-1) | T77 (30/11) | `PetProfile` thêm `breed` mà `PetSummary` không có; tên thú tra bằng `findPets` |
| `CatalogQueryApi.listActiveProtocols(species)` (I-3, ST22), `listRecommendable()` | T77, T78 | `Recommendable(groomingServices, kennelTypes, products)` |
| `BranchQueryApi.findEmergencyBranches()`, `findServiceIdsEnabledAnywhere()` (I-4) | T77 | "bật ở ≥ 1 chi nhánh" hiểu là chi nhánh `ACTIVE` |
| `BoardingQueryApi.findStaysOfPet`, `countStaysByKennelType(species, VisitQueryApi.AgeGroup, since)` (I-7); `countActiveStaysByBranch` | T78, T75 | dùng lại `VisitQueryApi.AgeGroup` của T55b (ADR-0113) như T55b đề xuất; `visit.api` không import `boarding.api` nên không có vòng phụ thuộc giữa hai `api` |
| `OnlineOrderApi.markPaid(onlineOrderId, transactionCode)`, `markPaymentCancelled(onlineOrderId)` | T65 (24/11) | `orderId` của `08` hiểu là `online_orders.id` |
| `PaymentGatewayClient`: `createSession`, `verifyCallback`, `queryTransaction`, `refund`, `fetchSettlementReport` | T65; `refund` T73 | prompt T55c chỉ nêu `refund`, nhưng `08` §C BR-TG-11 và prompt T65 ("interface đã có ở T55c") cần đủ năm thao tác; chữ ký là đề xuất, đổi qua ADR của T65 |
| `CustomerMergedEvent(sourceId, targetId)` | T75 (7/12) | đặt ở `customer.api` cạnh `PetOwnerTransferredEvent` (06 §3 ghi publisher là customer); chỉ khai báo, chưa có bên phát |

**Không đặt ở T55** (theo `time.md` T55 và prompt): `CatalogQueryApi.findHomeVisitFee`, `findMaterialNorms`; `VisitOrderApi.addHomeFeeLine`, `addServiceLine`; `StockApi.issueMaterials`; `BoardingQueryApi.findCheckedInStay`, `KennelApi.occupyForTreatment/release` — task tiêu thụ (T60, T61, T69, T71) tự thêm. Cổng thanh toán **không** cài gì ngoài interface và placeholder (cài ở T65).

**Enum Java chưa thêm hằng số** (`MedicalType.SURGERY/INPATIENT`, `OrderSource.ONLINE`, `OrderCancelType.ONLINE_*`, loại dòng `HOME_FEE`): chúng nằm trong DTO và hợp đồng HTTP của catalog-v1 / sales-v1, thêm là đổi hợp đồng nên làm cùng task dùng chúng (T60, T61, T63, T69; danh sách việc kèm theo ở ADR-0116 mục 3). V41, V42 chỉ cho DB nhận các giá trị đó.

## 3. Chỗ schema khác đặc tả (giữ `08` làm nguồn, ghi lý do)

| Chỗ | `08` | Migration | Lý do |
|---|---|---|---|
| `refunds.method`, `stock_counts.scope`, `article_comments.status` | `VARCHAR(20)` | `VARCHAR(30)` | erd §0: mọi cột enum `VARCHAR(30)` + `CHECK`; `SchemaMigrationIT.enumCheckColumnsAreVarchar30` bắt cột lệch |
| `stock_movements.type` | tên cột `type` | cột `movement_type` | tên thật trong V1 |
| `stock_adjustments.reason` thêm `COUNT_DIFF`, `RETURN_DISCARD` | — | **giữ** `COUNT_MISMATCH` của V1 | dòng cũ vẫn hợp lệ; `COUNT_DIFF` (kiểm kê, BR-KO-12) và `COUNT_MISMATCH` gần nghĩa nhau — T74 chọn dùng cái nào, không xóa cái cũ |
| `ck_stock_movements_source_type` | không nhắc | thêm `stock_transfers` | movement `TRANSFER_*` cần nguồn (BR-KO-09 "ghi hai phía"); xem A-3 |
| `shipping_fee_configs` | một dòng, `id = 1` | seed `(1, 0, 0)` bằng `OVERRIDING SYSTEM VALUE` | xem A-1 |
| CHECK cột ↔ trạng thái | "CHECK quyết định ↔ trạng thái" | xem A-2 | đặc tả nêu nguyên tắc, không nêu biểu thức |

## 4. Khoảng min–max của 7 khóa [CFG]

Đặc tả chỉ cho giá trị mặc định. Khoảng và đơn vị do T55c chọn (ADMIN sửa được trong khoảng, BR-QT-13); Dũng chỉnh được bằng migration mới.

| Khóa | Mặc định | Min | Max | Đơn vị |
|---|---|---|---|---|
| `shop.payment_window_hours` | 24 | 1 | 168 | giờ |
| `shop.auto_confirm_days` | 7 | 1 | 30 | ngày |
| `shop.max_redelivery` | 2 | 0 | 5 | lần |
| `payment.txn_ttl_minutes` | 15 | 5 | 60 | phút |
| `payment.refund_max_attempts` | 3 | 1 | 10 | lần |
| `returns.window_days` | 7 | 1 | 30 | ngày |
| `content.comment_per_hour` | 10 | 1 | 100 | bình luận |

Không có ràng buộc chéo giữa các khóa này; `ConfigKey` thêm 7 hằng số (78 → 85, sau 70 → 78 của T55b) và `SystemConfigIT` chép độc lập 7 dòng.

## 5. Giả định và câu hỏi mở (chờ Dũng)

**Giả định đã áp dụng vào schema:**

- **A-1 — Dòng phí vận chuyển được seed 0 đ / ngưỡng 0, `updated_by` cho phép NULL.** Cột `id` là `IDENTITY ALWAYS`: một `INSERT` hỏng (phí âm) vẫn tiêu một giá trị của dãy, lần sau nhận `id = 2` và bị `CHECK (id = 1)` chặn mãi. Có sẵn dòng thì T62 chỉ `UPDATE`. Hệ quả: phí 0 đ (miễn phí vận chuyển) cho tới khi SUPER_MANAGER cấu hình.
- **A-2 — Các CHECK cột ↔ trạng thái tự suy từ bảng FSM và BR** (đặc tả nêu nguyên tắc, không nêu biểu thức): `online_orders` (khuôn `care_tasks`, ADR-0081), `return_requests` (BR-BH-14: từ chối bắt buộc lý do), `refunds` (`completed_at` ⇔ `SUCCEEDED`; hoàn tại quầy xong phải có ca), `payment_transactions` (`paid_at` ⇔ `SUCCEEDED`), `stock_transfers` (nhận / hủy đủ bộ cột, hủy bắt buộc lý do), `stock_counts` (xác nhận đủ bộ cột; hủy ⇔ có lý do; `CATEGORY` ⇔ có danh mục), `branches` (SUSPENDED có thời điểm + lý do, CLOSED có thời điểm + lý do, DRAFT chưa từng tạm ngừng), `articles` (REJECTED có `review_note`, PENDING_REVIEW có `submitted_at`), `article_comments` (HIDDEN có người, lý do, lúc ẩn). `Tier3Be2SchemaIT` ghim từng CHECK.
- **A-3 — `stock_movements.source_type` thêm `stock_transfers`.** Đặc tả chỉ thêm loại `TRANSFER_*`; không có nguồn thì movement của phiếu chuyển không ghi được.
- ~~Order `ONLINE` không có `payment_id`~~ (giả định A-2 của bản nháp đầu) **đã chốt bởi ADR-0116 của T55b** với cùng nội dung; `online_orders` ở V44 dựa vào đó (chứng từ thanh toán của đơn online là `payment_transactions`).

**Câu hỏi mở (không chặn T55, chặn task tiêu thụ):**

- **TBD-1 — Loại movement khi hoàn hàng về lô gốc** (`StockApi.restock`, `restockToOriginalLot`; BR-BH-09, BR-BH-14): `05` §13 mục 26 chỉ thêm `TRANSFER_*`. Chưa thêm giá trị nào; V49 để dành. Cần quyết cùng nguồn (`orders` hay `return_requests`) trước T63.
- **TBD-2 — Lý do giao thất bại** (FSM #8 "Có lý do"): `online_orders` không có cột cho nó (chỉ `cancel_reason`). Lưu vào audit hay thêm `delivery_failure_reason` (V49)? Quyết trước T64.
- **TBD-3 — `refund_amount = 0`** (mọi dòng trả giá 0 đ) không sinh được `refunds` vì `amount > 0` theo đặc tả. Nới `CHECK` hay bỏ qua bước hoàn tiền? Quyết trước T73.
- **TBD-4 — Mở lại chi nhánh `SUSPENDED → ACTIVE`** có xóa `suspended_at` / `suspend_reason` hay giữ làm lịch sử? CHECK cho phép cả hai. Quyết ở T75.
- **TBD-5 — Ngữ nghĩa `OnlineOrderApi.markPaymentCancelled`**: `08` §C chỉ nêu tên; FSM #15 dòng 5 là shop hủy đơn thì payment hủy giao dịch (chiều ngược lại). Quyết ở T65 cùng Q3 của `08` §C.
- **TBD-6 — Hạn trả hàng của đơn online** tính từ `online_orders.delivered_at` (BR-BH-13) nhưng `OrderQueryApi.findLinesForReturn` chỉ biết Order bán hàng. T73 cần thêm truy vấn ở `shop.api` (hoặc nhận `deliveredAt` làm tham số); chưa đặt placeholder vì đặc tả `08` §D không liệt kê.

## 6. Kiểm

Chạy thật ngày 6/11/2026 trên nhánh `t55c-final` (worktree riêng, ngay trên `dev` = `7da3195`: có T55a bổ sung, T55b V36–V42), Postgres `pgvector/pgvector:pg17` qua Testcontainers. Đây là bản đã gộp với T55b; `origin/dev` của Mạnh chưa gộp (mục 7).

| Phạm vi | Lệnh | Kết quả |
|---|---|---|
| Test đơn vị toàn bộ | `mvn clean test` | **5644 ca, 0 lỗi** (5 601 của T55b + 43 ca mới của T55c) |
| IT gốc (`SchemaMigrationIT` 28, `Tier3Be1SchemaIT`, `Tier3Be2SchemaIT` 23, `AiSchemaIT`, `OpenApiContractParityIT`, ...) | `-Dit.test='com/petcare/*IT'` | 108 ca, 0 lỗi |
| `platform`, `Tier3Be2ContractWiringIT` | | 30, 2 ca, 0 lỗi |
| IT theo module | `-Dit.test='com/petcare/module/<m>/**/*IT'` | catalog 21 · branch 67 · customer 164 · inventory 106 · sales 177 · boarding 163 · content 71 · identity 863 · appointment 389 · visit 516 · care 136 · report 11 — **tất cả 0 lỗi** |
| Tổng IT | | **2824 ca, 0 lỗi** |

- **Test mới của T55c:** `Tier3Be2SchemaIT` 23 ca (từng CHECK / UNIQUE: kiểm cả SQLSTATE lẫn tên ràng buộc), `Tier3Be2ContractWiringIT` 2 ca (đủ một bean mỗi interface, method mới ném `UnsupportedOperationException` qua bean thật trong Spring), 42 ca `*Tier3PlaceholderTest` / `*PlaceholderTest` (mỗi method một ca), 1 ca `ConfigKeyTest`; sửa `SchemaMigrationIT`, `SystemConfigIT`, `ConfigKeyTest`, `StockIssueServiceTest`.
- **`StockIssueServiceTest.everyMethodJoinsTheCallersTransaction`** (có sẵn) quét mọi method của `StockApi` và đòi `@Transactional(MANDATORY)` ở `StockIssueService`; `default` placeholder làm nó văng `NullPointerException`. Đã sửa: bỏ qua method mà lớp cài vẫn kế thừa từ `StockApi`; khi bản thật ghi đè trong `StockIssueService` thì method khai báo ở lớp này và bị kiểm `MANDATORY` ngay.
- **Bản nháp đầu** (V42–V48, ADR-0116, trước khi T55b commit) đã chạy hết 15 nhóm xanh (unit 5 662, IT 2 839) rồi mới dời số; lần `care` đầu của bản nháp đỏ 13 ca vì GreenMail dùng cổng cố định 3025 mà phiên khác đang giữ, chạy lại khi cổng trống thì xanh. Kết quả ở bảng trên là của bản cuối.
- **Không ca đỏ cũ nào xuất hiện** (KHỐI CHUNG nhắc 5 ca đỏ cũ ở 4 lớp, gồm `OpenApiContractParityIT` và `ReminderScanIndexIT`; cả hai lớp đó xanh).

**Đột biến** (sửa tạm file migration, chạy `Tier3Be2SchemaIT`, khôi phục rồi so với bản gốc; phần `orders`, `medical_type`, `line_type` nay thuộc V41, V42 của T55b và `Tier3Be1SchemaIT` nên không đột biến lại ở đây):

| Phá | Test đỏ |
|---|---|
| M2 bỏ UNIQUE một phiên kiểm kê OPEN mỗi chi nhánh | partialIndexesKeepTheStatusLiteralTheJobsFilterOn, stockCountAllowsOneOpenSessionPerBranch |
| M3 ck_online_orders_carrier thành true | onlineOrderTimestampsFollowTheStatus |
| M4 ck_refunds_counter_shift thành true | refundHasExactlyOneSourceAndCounterRefundNeedsAShift |
| M6 ck_branches_suspended thành true | branchSuspendAndCloseColumnsFollowTheStatus |

**Kiểm T55 đã đủ chưa** (6/11, `check_t55.py` đối chiếu mã với tài liệu): 33/33 bảng của `05` §14 có migration V34–V48 (4 AI + 10 BE-1 + 19 BE-2), 43/43 ký hiệu interface của `06` §6b (trừ ba nhóm bị loại: vật tư tiêu hao, phí khám tại nhà, nhập viện từ lưu trú), đủ 15 khóa `ConfigKey` mới của `10` §1.7 (`ai.*` có 18 theo `07` §4.6 và V35, `10` §1.7 còn ghi 14), I-9 bộ khớp rate limit AI có, Flyway liền V1–V48.

**Chưa chạy / chưa kiểm:** gộp `origin/dev` của Mạnh (mục 7); chạy BE thật trên DB dev (cố ý, mục 7); hành vi nghiệp vụ (không có entity / endpoint ở T55).

## 7. Gộp và rủi ro

- **Đã làm trên đầu `dev` sau commit T55b** (`4037f81`) và T55a bổ sung (`7da3195`); không còn bước gộp với T55b. Các file dùng chung đã cộng hai phía: `SchemaMigrationIT` (`ERD_TABLES` 69 + 19 = **88**, `LOG_TABLES` 12 + 1 = **13**, FK tới `accounts` 61 + 16 = **77**, phiên bản liên tục V36…V48), `ConfigKey` 78 + 7 = **85** (`ConfigKeyTest`, `SystemConfigIT`), `docs/06` §6b, `docs/INDEX.md`, `docs/adr/INDEX.md`, `docs/time.md`.
- **Chưa gộp `origin/dev`** (PR #29 của Mạnh: ADR-0111, 0112, job dọn `otp_tokens` / `notifications`, đổi `application.yml`, `docker-compose.yml`, `CLAUDE.md`, `docs/INDEX.md`, `docs/adr/INDEX.md`, `system-overview.md`). Không đụng migration hay interface của T55c; khi gộp sẽ xung đột chữ ở các file `INDEX` và `CLAUDE.md` giữa `dev` (T55a, T55b, T56) và `origin/dev`, không phải do T55c.
- **Flyway lệch thứ tự trên DB dev:** `application.yml` không bật `out-of-order`. DB nào đã áp V43–V48 mà chưa có V36–V42 sẽ **không khởi động**. Theo hàng T55 của `time.md`, DB demo (`petcare` 5434, `petcare_seed` 5433) mới áp V34–V35 (T55a): khởi động lại BE trên DB đó sẽ áp V36→V48 theo thứ tự nên không lệch, nhưng **chưa thử**; sao lưu trước, và đừng áp V43–V48 riêng lẻ ở môi trường khác khi V36–V42 chưa có.
- Tên lớp IT: BE-1 đặt `Tier3Be1SchemaIT`, T55c đặt `Tier3Be2SchemaIT` và `Tier3Be2ContractWiringIT`.

## 8. Rủi ro và việc để lại

- Placeholder `*Placeholder` (3 lớp) và `default` (khoảng 30 method) phải bị xóa khi bản thật vào; `Tier3Be2ContractWiringIT` và các `*PlaceholderTest` sẽ đỏ đúng lúc đó và cần đổi thành test của bản thật.
- Chưa có entity JPA cho 19 bảng mới (ngoài phạm vi T55, entity làm ở task tiêu thụ): `ddl-auto=validate` chỉ kiểm cột đã ánh xạ nên không lệch.
- Chưa viết hợp đồng HTTP mới: 8 module `fees`, `shop`, `payment`, `returns`, `stockops`, `branchlife`, `custmerge`, `articlex` đã có ở `docs/api/` từ T54; T55c không thêm endpoint.
