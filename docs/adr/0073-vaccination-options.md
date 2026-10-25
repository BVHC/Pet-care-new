# ADR-0073: Gợi ý mũi tiêm (visit-v1 #18) — mũi gợi ý, phạm vi xem, sản phẩm còn tồn

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-25
- **Liên quan:** BR-KB-04, BR-SP-02, 03, 07, BR-KO-01; UC49; visit-v1 #18, A18–A20; ADR-0067 (khuôn #12/#13), 0072 (mũi gần nhất).
- **Triển khai (T28 phần 1):** `visit/controller/VaccinationController`, `service/VaccinationOptionService`, `VaccinationRecordReader.latestOfType`, `VaccinationRepository.findFirstByPetIdAndVaccineTypeIdOrderByAdministeredOnDescIdDesc`, `mapper/VaccinationOptionsMapper`, 3 DTO. Catalog (BE-2, BE-1 thêm ở cuối file, không sửa method cũ): `CatalogQueryApi.findVaccineType`, `findProductsByVaccineType` + `ProductRepository.findByVaccineTypeIdOrderByNameAscIdAsc`. Không migration.

## Quyết định

1. **Thứ tự lỗi:** 400 tham số → 403 vai trò → 404 lượt → 403 chi nhánh → 404 GROOMING → 403 không phải người phụ trách hiện tại → 404 loại vaccine → 400 BR-KB-04 (khác loài) → 400 BR-SP-07 (loại đã ngừng). Tắt loại vaccine không tắt phác đồ (`VaccineTypeService`), nên phải kiểm `active` của loại riêng.
2. **Mũi gợi ý (A18):** chưa tiêm → mũi nhỏ nhất đang dùng; đã tiêm → mũi đang dùng nhỏ nhất lớn hơn **số mũi đã chụp** của mũi gần nhất; hết mũi lớn hơn → mũi lớn nhất (nhắc lại định kỳ, vì mọi mũi có `interval_days > 0` và `next_due_date` NOT NULL); phác đồ rỗng → `null`, vẫn 200.
3. **Mọi trạng thái lượt (A19):** chỉ đọc; #19 mới chặn khi không `IN_PROGRESS`.
4. **Dữ liệu (A20):** `lastDose` = mũi gần nhất cùng loại ở mọi chi nhánh (cách chọn như ADR-0072 G2), kể cả mũi của chính lượt; lô `null` (D046). `products` = sản phẩm **đang kinh doanh** (vì `VisitOrderService.requireProduct` từ chối sản phẩm ngừng → #19 sẽ lỗi), tồn khả dụng > 0 tại chi nhánh của lượt, theo tên rồi id; rỗng vẫn 200. `underMinAge` theo mũi gợi ý và `LocalDate.now(clock)`; không có ngày sinh → `false`; đúng bằng tuổi tối thiểu là đủ.
5. **Transaction:** `readOnly + REPEATABLE_READ` như #12; không khóa, không audit. Tối đa 9 câu cố định, không N+1.
6. `products.vaccine_type_id` không có index: bảng danh mục nhỏ, chấp nhận quét (không ghi nợ).

## Bảng đảm bảo

| # | Ràng buộc | Test |
|---|---|---|
| G1 | Thứ tự guard, guard sai không đọc tiếp | `VaccinationOptionServiceTest` (`InOrder`, `verifyNoInteractions`); `VaccinationOptionsIT.visitGuardsComeBeforeTheVaccineType`, `vaccineTypeGuards` |
| G2 | Quy tắc gợi ý A18, kể cả mũi tắt / mũi cuối | unit `suggestionRules`; IT `afterTheLastDose…`, `returnsActiveProtocol…` |
| G3 | Chỉ sản phẩm đang kinh doanh, lô chưa hết hạn, đúng chi nhánh | unit `productsAreActive…`; IT `returnsActiveProtocol…` |
| G4 | Chưa đủ tuổi ở biên | unit `underMinAgeRules`; IT `underMinAgeFollows…` |
| G5 | Không ghi gì | IT `nothingIsWritten` (`xmin`, audit) |
| G6 | Contract | `OpenApiContractParityIT` (154), `VaccinationControllerSecurityTest` |

## Hệ quả

- #19 dùng lại G7–G9 và lọc sản phẩm, rồi kiểm lại dưới khóa.
- Ngày hiện tại đọc ở service và ở `StockQueryService` có thể lệch 1 ngày sát nửa đêm — chấp nhận.
