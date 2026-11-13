# ADR-0133: Gợi ý cá nhân hóa UC92 và hồ sơ gợi ý UC94 (T78 = AI-4, ai-v1 #4, #12, #13)

- **Trạng thái:** Accepted (Hiến và nhóm duyệt 12/11/2026, các điểm ở mục "Đã chốt 12/11/2026"). Từng Proposed khi làm việc AI-4 = T78, 11/11/2026.
- **Liên quan:** ADR-0006 (thú thuộc chủ hiện tại, người khác là 404), 0028 (Bean Validation chạy trước `@PreAuthorize`), 0113 (`VisitQueryApi.AgeGroup`), 0117, 0129 (I-1…I-8 đã cài thật), 0130 (bảng `ai_item_profiles`, UC93), 0132 (mẫu `PetAssistantController`); đặc tả `07` §4.4 (BR-AI-18…23), §4.6, §7, §8, §10, §11, §14.3; `docs/06` §6b hàng 16; hợp đồng `ai-v1` #4, #12, #13.
- **Đánh số:** không có migration (V34 đã có `ai_item_profiles`, V35 đã có đủ khóa `ai.reco.*`). ADR kế tiếp: **0134**.

## Bối cảnh

T78 cài UC92 (`GET /api/me/ai/pets/{petId}/recommendations`, #4) và UC94 (`GET /api/ai/item-profiles` #12, `PUT /api/ai/item-profiles/{itemType}/{itemId}` #13). UC92 không gọi LLM hay nhúng: điểm là công thức, lý do sinh từ mẫu (BR-AI-22). Mọi dữ liệu đọc qua `*QueryApi` đã cài thật ở AI-0 (ADR-0129); `ai` không chạm entity hay repository của module khác. Đặc tả chốt công thức nhưng im lặng ở nhiều chỗ làm thay đổi con số; các chỗ đó ghi ở mục "Giả định" để Hiến xác nhận, không quyết ngầm.

## Quyết định

### D1. Thứ tự một lượt gọi #4 (`RecommendationService`)
1. **Quyền sở hữu và còn sống, mỗi lần, TRƯỚC cache.** Khách lấy từ token (`CustomerQueryApi.findCustomerIdByAccountId`); thú không phải của khách, không tồn tại, hoặc tài khoản chưa có hồ sơ khách là 404 cùng một message; kiểm chủ chạy trước kiểm "đã mất" nên thú mất của người khác vẫn là 404; thú mất của chủ là 400 `BR-KH-05`. Lý do: module `ai` không nghe `PetOwnerTransferredEvent`; nếu tra cache trước thì chủ cũ vẫn đọc được gợi ý tới 10 phút sau khi chuyển chủ.
2. Đọc **lịch sử của thú và khách** (không cache): `findLastServiceDates`, `findStaysOfPet`, `findLastPurchaseDates`.
3. Tra cache theo khóa (D7); trượt thì tính (D2…D6) rồi lưu.
4. Áp `group` và `limit` lên danh sách đã xếp hạng đủ.

### D2. Ứng viên (BR-AI-19) và bộ lọc phòng thủ (BR-AI-18)
- Dịch vụ thẩm mỹ: có trong `listRecommendable` **và** nằm trong `findServiceIdsEnabledAnywhere` (I-4). Sản phẩm: `GOODS`, không kê đơn, còn trong `findProductIdsInStockAnywhere` (I-5). Loại chuồng: bật ở ≥ 1 chi nhánh `ACTIVE` (I-4 gồm cả loại chuồng), **đúng loài** và `max_weight_kg ≥ cân nặng` nếu đã biết cân (bằng nhau vẫn đủ).
- **Lọc lại ở `ai` dù catalog đã lọc** (`type = GOODS`, không kê đơn, nhóm `GROOMING`, đang kinh doanh): BR-AI-18 là "không bao giờ", nên một lỗi của catalog không được thành thuốc trong gợi ý. Chi phí là một dòng `filter`; có đột biến M1, M2.

### D3. Điểm thuộc tính A và loại mục (`RecoAttributes`)
Đúng `07` §7.3: tiêu chí *đánh giá được* = có định nghĩa trong hồ sơ **và** thú có dữ liệu; loài, tuổi, cân nặng đánh giá được mà không khớp thì loại mục; giống không khớp chỉ không tính điểm; không tiêu chí nào đánh giá được (kể cả chưa có hồ sơ) thì `A = 0,3` và lý do "Phù hợp chung". Biên tuổi và cân nặng đóng hai đầu. Giống: `chuẩn hóa(giống của thú)` chứa `chuẩn hóa(từ khóa)`; chuẩn hóa = chữ thường + bỏ dấu + `đ` → `d` (NFD không tách `đ`, nên đổi riêng, để khớp cách `f_unaccent` được dùng ở nơi khác). **Loại chuồng luôn có `A = 1,0`**: loài và cân nặng đã được BR-AI-19 kiểm khi chọn ứng viên, nên mọi tiêu chí đánh giá được đều khớp. Tuổi tính bằng tuần tròn (`ChronoUnit.WEEKS`).

### D4. Điểm lịch sử H (`07` §7.3)
- **Dịch vụ thẩm mỹ:** đã dùng đúng dịch vụ này và `ngày kể từ lần cuối ≥ chu kỳ` → 1,0 ("Đã N ngày kể từ lần dùng gần nhất"); đã dùng chưa tới chu kỳ → `0,2 × ngày / chu kỳ`; chưa dùng nhưng từng dùng dịch vụ thẩm mỹ khác → 0,4; còn lại 0. Chu kỳ = hồ sơ, hoặc [CFG] `ai.reco.default_groom_interval_days`.
- **"Từng dùng dịch vụ thẩm mỹ khác" phân loại theo nhóm của dịch vụ** (`CatalogQueryApi.findServices`), vì `findLastServiceDates` gồm cả khám và tiêm: thú chỉ từng tiêm phòng có H = 0, không phải 0,4. Dịch vụ thẩm mỹ đã ngừng kinh doanh vẫn tính (`findServices` không lọc `active`).
- **Sản phẩm:** đã mua đúng sản phẩm và `≥ chu kỳ` (mặc định 45 ngày) → 1,0; đã mua chưa tới chu kỳ → 0; chưa mua nhưng đã mua sản phẩm **cùng danh mục** → 0,4; còn lại 0. "Cùng danh mục" tính trên **mọi** hàng `listRecommendable` kể cả hàng đang hết (khách từng mua nó vẫn là tín hiệu).
- **Lưu trú:** từng ở đúng loại chuồng → 1,0; ở loại khác → 0,6; chưa ở → 0. **Chỉ tính lần lưu trú thật sự diễn ra:** `CHECKED_IN`, `OVERDUE`, `CHECKED_OUT`; `BOOKED`, `CANCELLED`, `NO_SHOW` không tính.

### D5. Độ phổ biến P
`P = c / c_max` trong cùng nhóm, `c` = số lượt 90 ngày gần nhất: cửa sổ `since = hôm nay − 90 ngày` (tính cả ngày đó, theo quy ước của I-6, I-8). Dịch vụ thẩm mỹ và lưu trú đếm theo **cohort (loài, nhóm tuổi) của thú đang xét** (`AgeGroup.of` với ngưỡng SENIOR theo loài từ [CFG] `ai.reco.senior_age_years_*`, cùng định nghĩa với `visit` và `boarding`); sản phẩm toàn chuỗi (A4). **`c_max` lấy trên các ứng viên của nhóm sau BR-AI-19 và TRƯỚC khi loại theo hồ sơ**, nên P của một mục không đổi theo việc mục khác bị loại. Count của dịch vụ không phải ứng viên (ví dụ tiêm phòng) và của sản phẩm thuốc không được kéo `c_max` lên. Thú thiếu ngày sinh có cohort `UNKNOWN`: chỉ đếm các thú cũng thiếu ngày sinh (hệ quả của `AgeGroup.UNKNOWN`).

### D6. Điểm, làm tròn, trọng số
`score = 100 × (wA·A + wH·H + wP·P)` bằng **BigDecimal**, làm tròn HALF_UP một chữ số thập phân **trước khi sắp xếp** (số thực nhị phân cho 92,00000000000001 ở ví dụ §7.3: sai phép bằng và sai thứ tự "trùng điểm thì id tăng"). Sắp điểm giảm, trùng điểm `id` tăng. `ConfigKey` chỉ kiểm khoảng từng khóa chứ **không** kiểm tổng ba trọng số bằng 1 (`07` §4.6 ghi "tổng bằng 1"): tổng lệch thì chia mỗi trọng số cho tổng (điểm không vượt 100); tổng bằng 0 (cả ba khóa đều 0 là hợp lệ) thì dùng 0,5 / 0,3 / 0,2. Mỗi nhóm giữ `limit` mục (mặc định [CFG] `ai.reco.max_per_group`); thứ tự nhóm `GROOMING`, `BOARDING`, `PRODUCT`.

### D7. Bộ nhớ đệm (BR-AI-23)
- **Khóa** = `petId` + SHA-256 của (loài, giống, ngày sinh, cân nặng, lịch sử dịch vụ, lưu trú, mua hàng). Lịch sử nằm trong khóa nên lượt khám, lưu trú, đơn mới làm khóa đổi ngay; sản phẩm phụ thuộc lịch sử mua của KHÁCH nên dấu vết mua hàng bắt buộc có trong khóa.
- **Giá trị** = danh sách đã xếp hạng ĐỦ của cả ba nhóm; `group` và `limit` áp sau khi lấy, nên không nằm trong khóa.
- TTL = [CFG] `ai.reco.cache_minutes` đọc mỗi lần gọi (0 = tắt). Bộ nhớ trong **tiến trình** (`ConcurrentHashMap`, dọn mục hết hạn khi vượt 1000 mục): mỗi instance có bản riêng, đặc tả không đòi dùng chung.
- **Dữ liệu ngoài khóa có thể cũ tới hết TTL:** độ phổ biến, tồn kho, giá, tên mục, trọng số và các [CFG] `ai.reco.*` khác. Riêng **hồ sơ UC94: lưu hoặc xóa hồ sơ xóa cả bộ đệm SAU commit** (xóa trước commit thì request khác kịp nạp lại dữ liệu cũ) để người quản lý thấy kết quả ngay.
- `generatedAt` của một lần trả từ cache là lúc tính ban đầu, không phải lúc trả.

### D8. UC94 (`ItemProfileService`)
- **Danh sách (#12):** dịch vụ thẩm mỹ rồi sản phẩm `GOODS` không kê đơn đang kinh doanh (từ `listRecommendable`; loại chuồng dùng `kennel_types` nên không có), mỗi nhóm theo thứ tự catalog (tên rồi id). Lọc `itemType`, `missingOnly`, `q` (chữ thường không dấu), phân trang trong bộ nhớ (`size` kẹp 1–100): quy mô danh mục demo, không cần truy vấn riêng.
- **Lưu (#13):** thứ tự kiểm 404 → BR-AI-18 (400) → luật chéo trường (400) → ghi. BR-AI-18: dịch vụ nhóm `MEDICAL` và loại chuồng (nhóm `BOARDING`), sản phẩm `DRUG` / `VACCINE` / kê đơn bị từ chối. Mục **đã ngừng kinh doanh vẫn nhận hồ sơ** (chuẩn bị trước khi bán lại; danh sách chỉ hiện mục đang bán).
- **PUT ghi đè toàn bộ.** Mọi trường rỗng nghĩa là xóa: **xóa dòng** (không giữ dòng rỗng), nên `hasProfile` và `missingOnly` luôn nhất quán và dòng cũ không còn ảnh hưởng. Từ khóa giống chuẩn hóa như lúc so khớp, bỏ trống, bỏ trùng.
- **PUT giống hệt hiện có không ghi, không audit** (không phải một thay đổi); so cân nặng theo giá trị chứ không theo scale (cột `NUMERIC(6,2)` trả `1.00` cho `1`).
- **Audit (BR-AI-17):** `entity_type = ai_item_profiles` (bảng khác `ai_documents` của UC93), mã `AI_ITEM_PROFILE_SAVED` / `AI_ITEM_PROFILE_CLEARED`, `before` / `after` là Map, cùng transaction với ghi (audit lỗi thì ghi rollback).
- **Tranh chấp:** hai PUT đầu tiên cùng lúc cho một mục: bên đến sau vi phạm `UNIQUE (service_id)` / `(product_id)` và nhận 409 `CONCURRENCY_CONFLICT` từ `GlobalExceptionHandler`; PUT idempotent nên thử lại là đủ. Không khóa thêm vì chỉ SUPER_MANAGER ghi và hợp đồng #13 không liệt kê 409.
- Khoảng giá trị khớp cột (Bean Validation): tuổi 0–5200 tuần, cân nặng 0–9999,99 kg tối đa hai chữ số thập phân, chu kỳ 1–3650 ngày, tối đa 50 từ khóa mỗi từ ≤ 100 ký tự; `min > max` là `VALIDATION_FAILED` ở service.

### D9. Thêm một method vào module `catalog` của BE-2 (Hiến duyệt 11/11/2026)
`listRecommendable` (I-3) **không mang** `categoryId` và `imageUrl` của sản phẩm (cần cho hàng H "cùng danh mục" và `imageUrl` của hợp đồng) và cột `price_is_from` của dịch vụ (trang công khai đọc cột này, BR-CK-02). Thêm `CatalogQueryApi.findRecommendDisplay(serviceIds, productIds)` trả `RecommendDisplay(priceFromServiceIds, products: id → ProductDisplay(categoryId, imageUrl))`. **Chỉ thêm method và hai record mới; không đổi record hay chữ ký nào đang có** (cùng lý do `07` §11 không thêm trường vào `BookedSlot`: đổi constructor làm gãy mọi nơi đang dựng record). Đọc theo lô, id không tồn tại thì vắng mặt, tập rỗng thì không chạy SQL. Giá hiển thị: dịch vụ thẩm mỹ và loại chuồng đọc `price_is_from`; loại chuồng thêm `priceUnit = "đêm"`; sản phẩm `priceIsFrom = false`. Cần báo Dũng (BE-2): ghi ở `docs/06` §6b hàng 16.

### D10. Hợp đồng và parity
Hợp đồng HTTP không đổi (`m_ai.py`, `ai-v1.yaml` đã có sẵn #4, #12, #13; không chạy lại bộ sinh). `OpenApiContractParityIT`: ghim `hasSize` nâng 197 → 200 (đúng 3 endpoint mới). `limit` ngoài 1–12 do Spring MVC kiểm trên `@RequestParam` (không `@Validated` ở lớp) → 400 `VALIDATION_FAILED`.

## Giả định ở chỗ đặc tả im lặng (đã chốt 12/11/2026, xem mục "Đã chốt 12/11/2026")

1. **`c_max`** lấy trên ứng viên của nhóm, trước khi loại theo hồ sơ (D5). Cách khác (sau khi loại) cũng khớp ví dụ §7.3 nếu có mục thứ tư làm `c_max`.
2. **"Từng dùng dịch vụ thẩm mỹ khác"** chỉ tính dịch vụ nhóm `GROOMING` (D4).
3. **Lưu trú "từng ở"** chỉ tính `CHECKED_IN`, `OVERDUE`, `CHECKED_OUT` (D4).
4. **"Cùng danh mục"** của sản phẩm tính trên mọi hàng gợi được kể cả đang hết (D4).
5. **Loại chuồng `A = 1,0`** và vẫn nhận `H` và `P` (D3).
6. **Cửa sổ 90 ngày** là `hôm nay − 90` tính cả ngày đó (D5).
7. **Tổng trọng số lệch** thì chia cho tổng; tổng 0 thì mặc định (D6).
8. **Hồ sơ rỗng thì xóa dòng**; mục ngừng kinh doanh vẫn nhận hồ sơ; loại chuồng bị từ chối bằng `BR-AI-18` (D8).
9. **Cache theo tiến trình**, dữ liệu ngoài khóa cũ tới 10 phút, hồ sơ UC94 xóa cache ngay sau commit (D7).
10. **Thú thiếu ngày sinh** tính P theo cohort `UNKNOWN` (D5).
11. **Câu lý do** (BR-AI-22) là mẫu của người cài, không phải nguyên văn ví dụ §7.3 ("Phù hợp chó nhỏ 1–8 kg" → "Phù hợp cân nặng 1–8 kg", "Nhiều chủ chó con chọn" giữ nguyên).

## Đột biến (test phải đỏ)

**74 đột biến, mỗi lần chỉ sửa một chỗ trong mã, rồi khôi phục (mã gốc không bị đụng: chạy trong bản sao cô lập; mức unit biên dịch riêng lớp bị đột biến bằng `javac` và chạy JUnit trực tiếp, mức IT dùng `mvn verify` trong bản sao). Cả 74 đều làm ít nhất một test đỏ**, không đột biến nào gây lỗi biên dịch, không mẫu nào lệch (BAD-PATTERN 0).

**Mức unit (69):** `R*` chạy `RecommendationServiceTest` (37 ca), `RecoAttributesTest` (11), `RecoWeightsTest` (7); `P*` chạy `ItemProfileServiceTest` (19). Lần chạy đầu 68/69 bị bắt; **R6d (khóa cache thiếu loài) sống sót** vì ca "đổi loài" lúc đó đổi cả giống nên khóa vẫn khác. Đã sửa test (chỉ đổi loài, giữ nguyên giống, ngày sinh, cân nặng) và chạy lại: bị bắt.

| # | Đột biến | Test đỏ đầu tiên |
|---|---|---|
| R1 | bỏ lọc DRUG/VACCINE/kê đơn | `neverRecommendsDrugsVaccinesPrescriptionGoodsOrMedicalServicesEvenIfTheCatalogLetsThemThrough` |
| R2 | bỏ lọc dịch vụ MEDICAL | `neverRecommendsDrugsVaccinesPrescriptionGoodsOrMedicalServicesEvenIfTheCatalogLetsThemThrough` |
| R7 | bỏ lọc tồn khả dụng | `servicesNotEnabledAnywhereAndGoodsOutOfStockAreNotCandidates` |
| R8 | bỏ lọc dịch vụ bật ở chi nhánh | `servicesNotEnabledAnywhereAndGoodsOutOfStockAreNotCandidates` |
| R9 | bỏ lọc loại chuồng bật ở chi nhánh | `aKennelNotEnabledAnywhereIsNotACandidate` |
| R10 | bỏ lọc loài của chuồng | `kennelMustMatchTheSpeciesAndHoldTheWeightWhenItIsKnown` |
| R11 | chuồng: >= thành > | `kennelMustMatchTheSpeciesAndHoldTheWeightWhenItIsKnown` |
| R12 | bỏ kiểm chủ hiện tại | `ownershipIsCheckedBeforeTheCacheIsRead` |
| R13 | bỏ kiểm thú đã mất | `aDeceasedPetIsRejectedAfterTheOwnershipCheck` |
| R3 | khóa cache thiếu cân nặng | `changingTheWeightChangesTheListAndTheCacheKey` |
| R4 | khóa cache thiếu lịch sử mua hàng | `aNewCompletedVisitOrPurchaseChangesTheCacheKey` |
| R5 | khóa cache thiếu lịch sử dịch vụ | `aNewCompletedVisitOrPurchaseChangesTheCacheKey` |
| R6 | khóa cache thiếu lưu trú | `aNewStayAndAChangeOfBreedOrBirthDateEachChangeTheCacheKey` |
| R6b | khóa cache thiếu giống | `aNewStayAndAChangeOfBreedOrBirthDateEachChangeTheCacheKey` |
| R6c | khóa cache thiếu ngày sinh | `aNewStayAndAChangeOfBreedOrBirthDateEachChangeTheCacheKey` |
| R6d | khóa cache thiếu loài | `changingTheSpeciesChangesTheList` (xem ghi chú dưới bảng) |
| R28a | TTL gấp đôi | `theCacheExpiresAfterTheConfiguredMinutes` |
| R28b | TTL một nửa | `secondCallWithinTheTtlIsServedFromTheCache` |
| R14 | cửa sổ phổ biến 89 ngày | `ageGroupBoundariesFollowSection73` |
| R20 | mèo đọc khóa tuổi của chó | `catOfEightYearsIsAdultNotSeniorSoTheCatKeyIsUsedNotTheDogKey` |
| R20b | loài khác đọc khóa tuổi của chó | `otherSpeciesReadsItsOwnSeniorKeyNotTheDogOne` |
| R15a | c_max dịch vụ lấy cả dịch vụ không phải ứng viên | `cMaxIsTakenOverTheCandidatesOfTheGroupOnly` |
| R15b | c_max sản phẩm lấy cả hàng không phải ứng viên | `productPopularityIsChainWideAndBoundedByTheCandidates` |
| R16 | lưu trú hủy / vắng mặt cũng tính | `boardingHistoryOnlyCountsStaysThatActuallyHappened` |
| R17 | mọi dịch vụ từng dùng đều tính là thẩm mỹ | `aPetThatOnlyHadVaccinationVisitsHasNoGroomingHistory` |
| R19 | danh mục đã mua chỉ tính hàng còn tồn | `aPurchaseOfAnOutOfStockProductStillCountsForTheSameCategoryRule` |
| R21 | hệ số chưa đến hạn 0,2 thành 0,3 | `groomingHistoryTable` |
| R30 | chuồng A = 0 | `aNewStayAndAChangeOfBreedOrBirthDateEachChangeTheCacheKey` |
| R32 | chuồng: mọi lần ở đều là đúng loại chuồng | `aNewStayAndAChangeOfBreedOrBirthDateEachChangeTheCacheKey` |
| R33 | cùng danh mục tính với mọi sản phẩm có hồ sơ hiển thị | `productHistoryTable` |
| R34 | dịch vụ: đến hạn >= thành > | `groomingHistoryTable` |
| R35 | sản phẩm: đến hạn >= thành > | `productHistoryTable` |
| R36 | bỏ chu kỳ riêng của hồ sơ (dịch vụ) | `groomingHistoryTable` |
| R18 | trùng điểm thì id giảm dần | `equalScoresAreOrderedByIdAscending` |
| R22 | bỏ giới hạn mỗi nhóm | `groupAndLimitAreAppliedAfterTheCacheSoTheyAreNotPartOfTheKey` |
| R24 | bỏ lọc theo nhóm | `groupAndLimitAreAppliedAfterTheCacheSoTheyAreNotPartOfTheKey` |
| A1a | bỏ loại khi cân nặng dưới min | `groupAndLimitAreAppliedAfterTheCacheSoTheyAreNotPartOfTheKey` |
| A1b | bỏ loại khi cân nặng trên max | `changingTheWeightChangesTheListAndTheCacheKey` |
| A2a | tuổi: biên min mở | `ageOutsideTheRangeExcludesTheItemAndBoundsAreInclusive` |
| A2b | tuổi: biên max mở | `ageOutsideTheRangeExcludesTheItemAndBoundsAreInclusive` |
| A3 | bỏ loại khi sai loài | `changingTheSpeciesChangesTheList` |
| A4 | giống không khớp thì loại mục | `breedMismatchDoesNotExcludeButCostsOneOfTheEvaluableCriteria` |
| A5 | điểm trung tính 0,3 thành 0,5 | `cMaxIsTakenOverTheCandidatesOfTheGroupOnly` |
| A7 | giống của thú không chuẩn hóa | `workedExampleGivesExactly92ExcludesTheBigDogCutAndGives37ToTheSpa` |
| A9 | tuổi đánh giá cả khi thiếu ngày sinh | `petWithoutBirthDateAndWeightIsNeitherExcludedNorPenalised` |
| T1 | chữ đ không đổi thành d | `breedMatchingIgnoresCaseAndAccentsIncludingTheLetterD` |
| W1 | làm tròn HALF_EVEN | `roundsHalfUpToOneDecimalPlace` |
| W2 | không chuẩn hóa trọng số | `weightsThatDoNotSumToOneKeepTheScoreAtOrBelow100` |
| W3 | tổng trọng số 0 không có dự phòng | `weightsSummingToZeroFallBackToTheDefaults` |
| P1 | bỏ BR-AI-18 cho thuốc, vaccine, kê đơn | `drugsVaccinesAndPrescriptionGoodsCannotHaveAProfile` |
| P2 | bỏ BR-AI-18 cho loại chuồng | `medicalAndBoardingServicesCannotHaveAProfile` |
| P3 | bỏ BR-AI-18 cho dịch vụ khám, tiêm | `medicalAndBoardingServicesCannotHaveAProfile` |
| P4a | bỏ kiểm min > max của tuổi | `minGreaterThanMaxIsRejectedForAgeAndWeightAndNothingIsWritten` |
| P4b | bỏ kiểm min > max của cân nặng | `minGreaterThanMaxIsRejectedForAgeAndWeightAndNothingIsWritten` |
| P5 | từ khóa giống không chuẩn hóa | `breedKeywordsAreStoredLowercaseWithoutAccentsTrimmedAndDeduplicated` |
| P6 | hồ sơ rỗng không xóa dòng | `anAllEmptyBodyWhenThereIsNoRowIsANoOp` |
| P7 | PUT không đổi vẫn ghi | `anUnchangedPutComparesWeightsByValueNotByScale` |
| P8 | bỏ audit khi lưu | `minGreaterThanMaxIsRejectedForAgeAndWeightAndNothingIsWritten` |
| P9 | bỏ audit khi xóa | `anAllEmptyBodyDeletesTheExistingRowAndTheItemGoesBackToMissing` |
| P10a | bỏ xóa cache sau khi lưu | `savingWritesAnAuditEntryWithBeforeAndAfterAndClearsTheRecommendationCache` |
| P10b | bỏ xóa cache sau khi xóa hồ sơ | `anAllEmptyBodyDeletesTheExistingRowAndTheItemGoesBackToMissing` |
| P11 | danh sách gồm cả dịch vụ không phải thẩm mỹ | `listsOnlyGroomingServicesAndOverTheCounterGoodsServicesFirst` |
| P12 | danh sách gồm cả thuốc, hàng kê đơn | `listsOnlyGroomingServicesAndOverTheCounterGoodsServicesFirst` |
| P13 | tìm theo tên có phân biệt dấu | `searchByNameIgnoresCaseAndAccents` |
| P14 | hồ sơ rỗng tính là có hồ sơ | `anEmptyRowCountsAsMissing` |
| P15 | missingOnly đảo ngược | `anEmptyRowCountsAsMissing` |
| P16 | không kẹp size tối đa | `pagesTheResultAndClampsThePageSize` |
| P18 | quên ghi updated_by | `savesANewServiceProfileAndReturnsTheRow` |
| P19 | so cân nặng bằng equals (scale) | `anUnchangedPutComparesWeightsByValueNotByScale` |

**Mức IT (5, Postgres thật, `mvn verify` chỉ IT liên quan):**

| # | Đột biến | Test đỏ / số ca |
|---|---|---|
| R12 | bỏ kiểm chủ hiện tại | `RecommendationIT.aPetThatChangedOwnerIsNotFoundForTheFormerOwnerEvenWithAWarmCache`, `…aPetOfAnotherCustomerAndAnUnknownPet…` (2/7) |
| R3 | khóa cache thiếu cân nặng | `RecommendationIT.changingTheWeightChangesTheListEvenWhileTheCacheIsWarm` (1/7) |
| P10a | UC94 không xóa cache sau khi lưu | `ItemProfileIT.recommendationsSeeANewProfileImmediatelyBecauseTheCacheIsClearedAfterCommit` (1/9) |
| P8 | UC94 bỏ audit khi lưu | `ItemProfileIT.everyRealWriteIsAuditedWithBeforeAndAfter…`, `…aFailedAuditRollsTheProfileWriteBack` (2/9) |
| X1 | `findRecommendDisplay` bỏ lọc `price_is_from` | `CatalogAiReadIT.recommendDisplayCarriesCategoryImageAndThePriceFromColumnOfTheGivenIdsOnly` (1/3) |

Ghi chú: ví dụ tính tay §7.3 (92,0 / loại / 37,0) là ca bảng ở cả mức unit (`workedExampleGives…`) và mức IT (`workedExampleThroughTheRealQueryApis…`; thu nhỏ 18/30, 15/30 còn 6/10, 5/10 để cùng P = 0,6 và 0,5 mà khỏi dựng 63 lượt khám). Ca "mèo 10 năm là SENIOR" không phân biệt được "mèo đọc khóa chó" (10 ≥ 7 cả hai), nên thêm ca mèo 8 năm phải là ADULT (R20 bắt) và ca loài `OTHER` với [CFG] riêng (R20b bắt).

## Kết quả kiểm thử

- **Unit toàn dự án: 6062 xanh, 0 đỏ** (nền 5986: ADR-0132 ghi 5985 trước ca cuối của T77c; T78 thêm đúng 76 ca = 11 + 7 + 37 + 19 + 2). Ca mới: `RecoAttributesTest` 11, `RecoWeightsTest` 7, `RecommendationServiceTest` 37, `ItemProfileServiceTest` 19, `CatalogQueryServiceTest` +2.
- **IT mới (Postgres + pgvector thật, Testcontainers, embedding / LLM giả):** `ItemProfileIT` 9 (era 2045), `RecommendationIT` 7 (era 2044; kiểm `embeddings.calls() == 0` và `llm.calls()` rỗng ở mọi ca), `CatalogAiReadIT` +1 (3). Cả ba xanh.
- **Hồi quy IT nhóm ai + catalog: 154 xanh, 0 đỏ** (ai 130, catalog 24; `AiDocumentFsmIT` 33, `ChatIT` 22, `MeChatIT` 18, `KnowledgeIndexerIT` 20, `CatalogProtocolSyncIT` 7, `KnowledgeIndexTriggerIT` 6, `ChatLogCleanupIT` 5, `AiJobScheduleIT` 3, `CatalogAdminIT` 11, `PublicCatalogIT` 10, kể cả các IT mới), `Tier3Be2ContractWiringIT` 2 xanh.
- **`OpenApiContractParityIT`: 11 ca, 5 đỏ, đúng 5 ca đỏ của mốc trước, không ca nào của `ai`.** Bốn ca do lệch `feedbacks` sẵn có (`GET /api/feedbacks`, `/{feedbackId}`, `/api/me/feedbacks`: mô tả, tham số, cây thuộc tính) và `POST /api/feedbacks/{feedbackId}/resolve` có trong code mà `content-v1.yaml` không có; ca đếm endpoint: **199 so với ghim 200** (mốc trước 196 so với 197; chênh 1 cũng do `resolveFeedback`). 199 = 196 + 3 chứng tỏ #4, #12, #13 được tính đúng, và cả ba có mặt trong danh sách `documentedOps`. Các ca `responseCodesAndErrorDescriptionsMatchContract`, `everyLeafPropertyHasARealisticExample`, `responsesExposeNoSecretField`, `structureIsConsistent` xanh, nên mô tả lỗi, ví dụ và cây thuộc tính của #4, #12, #13 khớp hợp đồng.
- Hợp đồng HTTP không đổi (`m_ai.py`, `ai-v1.yaml` đã có sẵn #4, #12, #13); không chạy lại bộ sinh.

## Đã chốt 12/11/2026

Hiến và nhóm duyệt ngày 12/11/2026 (nội dung gốc giữ nguyên làm lịch sử, thêm "Quyết định" cho từng điểm).

- Các giả định 1–11 ở trên, nhất là 1 (`c_max`), 3 (trạng thái lưu trú), 8 (hồ sơ rỗng xóa dòng) và 9 (cache theo tiến trình). **Quyết định: duyệt nguyên giả định 1–11.**
- Method mới `CatalogQueryApi.findRecommendDisplay` trong module của BE-2 (D9): báo Dũng, ghi ở `docs/06` §6b. **Quyết định: giữ (Hiến duyệt từ 11/11/2026, D9);** báo Dũng trong tin nhắn gộp 12/11/2026 (`.c2-tmp/final2/msg-be-adr-ai-12-11.md`).
- Ngưỡng SENIOR theo loài vẫn là giá trị tạm (Q3, `07` §7.3): đổi bằng [CFG], không cần sửa mã. **Quyết định: vẫn là [CFG] tạm** (Q3 chưa giải).

## Chưa làm / chưa chạy

- **Chưa chạy qua BE thật** (container `petcare-api` ở 8081 dựng từ ảnh cũ, chưa có mã T78) và chưa có giao diện gọi #4, #12, #13 (T82 là FE). Mọi kiểm chứng ở trên là unit và IT trên Postgres thật, không phải BE đang chạy.
- **Chưa nhập hồ sơ thật** cho dịch vụ thẩm mỹ và 30–50 sản phẩm bán chạy (việc của SUPER_MANAGER qua UC94, `07` §7.2); đến lúc đó mọi mục nhận điểm trung tính (BR-AI-20), nên danh sách gợi ý chỉ phân biệt theo lịch sử và độ phổ biến.
- **Chưa đo hiệu năng** của lượt tính không trúng cache: `VisitUsageRepository.completedSince` quét `visits` không chỉ mục (ADR-0129 đã ghi), cộng ba truy vấn đọc lịch sử mỗi lần gọi. UC92 không gọi LLM nên không có p95 kiểu T77; cần đo trước khi chạy thật nếu `visits` lớn. Cần thì thêm chỉ mục bằng ADR và migration mới (V51 trở lên, không dùng V49).
- **Hai điểm đột biến chưa bắt hết ở mức IT:** (a) xóa cache sau commit đã có IT, nhưng không có ca đua hai request quanh thời điểm commit; (b) cache theo tiến trình chưa có test nhiều instance (đặc tả không đòi).
- `ai.reco.weight_*` đổi thì cache cũ giữ trọng số cũ tới hết TTL (không nằm trong khóa); `ai.reco.cache_minutes` về 0 là cách đổi tức thì.
- Không sửa `OpenApiContractParityIT` ngoài việc nâng ghim 197 → 200; năm ca đỏ còn lại là việc của Mạnh / Dũng (`feedbacks`).
