# Catalog API v1 — Pet Care v16

> **Module:** SP (và phần sản phẩm / dịch vụ của CK) · **Owner:** BE-2. Danh mục sản phẩm, sản phẩm, dịch vụ (gồm loại chuồng), loại vaccine, phác đồ tiêm; trang công khai dịch vụ và sản phẩm.
> **Nguồn chân lý:** 01 UC15, UC16, UC28–UC31; 02 BR-SP-01…07, BR-CK-01…03; 04 §4; 05 §4 (product_categories, products, services, kennel_types, vaccine_types, vaccination_protocols).
> **Contract máy đọc:** [`./openapi/catalog-v1.yaml`](./openapi/catalog-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- UC32 phí vận chuyển & phí khám tại nhà là tầng 3, không có endpoint.
- Định mức vật tư tiêu hao của dịch vụ (UC30, ST07) là tầng 3.
- Bật / tắt dịch vụ tại chi nhánh (UC33) thuộc module branch.
- Tồn kho thuộc module inventory; trang công khai chỉ nhận danh sách chi nhánh còn hàng qua `StockQueryApi`.

---

## A. Danh sách endpoint (22)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `GET /product-categories` | Danh mục sản phẩm | UC28 | — |
| 2 | `POST /product-categories` | Tạo danh mục | UC28 | — |
| 3 | `PATCH /product-categories/{categoryId}` | Sửa / ẩn danh mục | UC28 | — |
| 4 | `GET /products` | Danh sách sản phẩm | UC29, UC66 | BR-SP-01 |
| 5 | `POST /products` | Tạo sản phẩm | UC29 | BR-SP-01, 05, 07 |
| 6 | `GET /products/{productId}` | Chi tiết sản phẩm | UC29 | — |
| 7 | `PATCH /products/{productId}` | Sửa sản phẩm / đổi giá / ngừng kinh doanh | UC29 | BR-SP-01, 05, 07, BR-BH-03, BR-QT-15 |
| 8 | `GET /services` | Danh sách dịch vụ | UC30 | BR-SP-04, 06 |
| 9 | `POST /services` | Tạo dịch vụ / loại chuồng | UC30 | BR-SP-04, 06 |
| 10 | `GET /services/{serviceId}` | Chi tiết dịch vụ | UC30 | — |
| 11 | `PATCH /services/{serviceId}` | Sửa dịch vụ / đổi giá / ngừng | UC30 | BR-SP-04, 06, BR-BH-03, BR-QT-15 |
| 12 | `GET /vaccine-types` | Danh sách loại vaccine | UC31, UC49 | BR-SP-07 |
| 13 | `POST /vaccine-types` | Tạo loại vaccine | UC31 | BR-SP-07 |
| 14 | `PATCH /vaccine-types/{vaccineTypeId}` | Sửa / ngừng loại vaccine | UC31 | BR-SP-07 |
| 15 | `DELETE /vaccine-types/{vaccineTypeId}` | Xóa loại vaccine chưa dùng | UC31 | BR-SP-07 |
| 16 | `GET /vaccination-protocols` | Phác đồ tiêm chủng | UC31, UC49 | BR-SP-02 |
| 17 | `POST /vaccination-protocols` | Thêm dòng phác đồ | UC31 | BR-SP-02, 07 |
| 18 | `PATCH /vaccination-protocols/{protocolId}` | Sửa dòng phác đồ | UC31 | BR-SP-02, 03 |
| 19 | `GET /public/services` | Dịch vụ công khai | UC15 | BR-CK-01, 02, BR-SP-04 |
| 20 | `GET /public/product-categories` | Danh mục sản phẩm công khai | UC16 | BR-CK-01 |
| 21 | `GET /public/products` | Xem & tìm sản phẩm | UC16 | BR-CK-01, 02, 03, BR-SP-01 |
| 22 | `GET /public/products/{productId}` | Chi tiết sản phẩm công khai | UC16 | BR-CK-01, 03, BR-SP-01 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Danh mục sản phẩm | A04–A08 | Mọi nhân viên | — | — | — |
| Tạo danh mục | A04 | Chỉ SUPER_MANAGER | — | — | — |
| Sửa / ẩn danh mục | A04 | Chỉ SUPER_MANAGER | — | — | — |
| Danh sách sản phẩm | A04–A08 | Mọi nhân viên | — | — | — |
| Tạo sản phẩm | A04 | Chỉ SUPER_MANAGER | — | — | — |
| Chi tiết sản phẩm | A04–A08 | Mọi nhân viên | — | — | — |
| Sửa sản phẩm / đổi giá / ngừng kinh doanh | A04 | Chỉ SUPER_MANAGER | — | — | Đổi giá ghi audit, không ảnh hưởng dòng Order đã có. |
| Danh sách dịch vụ | A04–A08 | Mọi nhân viên | — | — | — |
| Tạo dịch vụ / loại chuồng | A04 | Chỉ SUPER_MANAGER | — | — | — |
| Chi tiết dịch vụ | A04–A08 | Mọi nhân viên | — | — | — |
| Sửa dịch vụ / đổi giá / ngừng | A04 | Chỉ SUPER_MANAGER | — | — | Đổi giá ghi audit; giá đêm đã snapshot ở đặt chỗ lưu trú không đổi (BR-LT-02). |
| Danh sách loại vaccine | A04–A08 | Mọi nhân viên | — | — | — |
| Tạo loại vaccine | A04 | Chỉ SUPER_MANAGER | — | — | — |
| Sửa / ngừng loại vaccine | A04 | Chỉ SUPER_MANAGER | — | — | — |
| Xóa loại vaccine chưa dùng | A04 | Chỉ SUPER_MANAGER | — | — | — |
| Phác đồ tiêm chủng | A04–A08 | Mọi nhân viên | — | — | — |
| Thêm dòng phác đồ | A04 | Chỉ SUPER_MANAGER | — | — | — |
| Sửa dòng phác đồ | A04 | Chỉ SUPER_MANAGER | — | — | Ngày tái chủng đã tính giữ nguyên (BR-SP-03). |
| Dịch vụ công khai | A01, A02 | Public | — | — | Chỉ dịch vụ đang kinh doanh và bật ở ≥ 1 chi nhánh ACTIVE. |
| Danh mục sản phẩm công khai | A01, A02 | Public | — | — | — |
| Xem & tìm sản phẩm | A01, A02 | Public | — | — | — |
| Chi tiết sản phẩm công khai | A01, A02 | Public | — | — | Thuốc kê đơn hoặc ngừng kinh doanh trả 404. |

---

## C. Chi tiết endpoint

### Product categories

- **`GET /product-categories`** — Danh mục sản phẩm. Query: `isActive?`. Response `200` mảng `ProductCategory`. Lỗi: `401` · `403`.
- **`POST /product-categories`** — Tạo danh mục. Request `ProductCategoryRequest`. Response `201` `ProductCategory`. Lỗi: `400` Tên trùng · `401` · `403`.
- **`PATCH /product-categories/{categoryId}`** — Sửa / ẩn danh mục. Request `UpdateProductCategoryRequest`. Response `200` `ProductCategory`. Lỗi: `400` · `401` · `403` · `404`.
### Products

- **`GET /products`** — Danh sách sản phẩm. Query: `q?`, `categoryId?`, `productType?`, `vaccineTypeId?`, `isActive?`, `retailOnly?`, `page?`, `size?`. Response `200` trang `Product`. Lỗi: `401` · `403`.
- **`POST /products`** — Tạo sản phẩm. Request `CreateProductRequest`. Response `201` `Product`. Lỗi: `400` SKU trùng · `BR-SP-01` cờ kê đơn trên loại khác DRUG · `BR-SP-05` thuốc kê đơn / vaccine tắt quản lý hạn dùng · `BR-SP-07` vaccine chưa gắn loại vaccine · `401` · `403`.
- **`GET /products/{productId}`** — Chi tiết sản phẩm. Response `200` `Product`. Lỗi: `401` · `403` · `404`.
- **`PATCH /products/{productId}`** — Sửa sản phẩm / đổi giá / ngừng kinh doanh. Request `UpdateProductRequest`. Response `200` `Product`. Lỗi: `400` `BR-SP-05` tắt quản lý hạn dùng khi còn tồn ở bất kỳ chi nhánh nào · `BR-SP-01`, `BR-SP-07` như khi tạo · `401` · `403` · `404`.
### Services

- **`GET /services`** — Danh sách dịch vụ. Query: `group?`, `isActive?`. Response `200` mảng `Service`. Lỗi: `401` · `403`.
- **`POST /services`** — Tạo dịch vụ / loại chuồng. Request `CreateServiceRequest`. Response `201` `Service`. Lỗi: `400` `BR-SP-06` nhóm MEDICAL chưa chọn loại · `BR-SP-04` loại chuồng thiếu loài hoặc giá ≤ 0 · tên trùng · `401` · `403`.
- **`GET /services/{serviceId}`** — Chi tiết dịch vụ. Response `200` `Service`. Lỗi: `401` · `403` · `404`.
- **`PATCH /services/{serviceId}`** — Sửa dịch vụ / đổi giá / ngừng. Request `UpdateServiceRequest`. Response `200` `Service`. Lỗi: `400` `BR-SP-06`, `BR-SP-04` như khi tạo · đổi nhóm (A1) · `401` · `403` · `404`.
### Vaccines

- **`GET /vaccine-types`** — Danh sách loại vaccine. Query: `species?`, `isActive?`. Response `200` mảng `VaccineType`. Lỗi: `401` · `403`.
- **`POST /vaccine-types`** — Tạo loại vaccine. Request `VaccineTypeRequest`. Response `201` `VaccineType`. Lỗi: `400` Trùng tên trong cùng loài · `401` · `403`.
- **`PATCH /vaccine-types/{vaccineTypeId}`** — Sửa / ngừng loại vaccine. Request `UpdateVaccineTypeRequest`. Response `200` `VaccineType`. Lỗi: `400` · `401` · `403` · `404`.
- **`DELETE /vaccine-types/{vaccineTypeId}`** — Xóa loại vaccine chưa dùng. Response `204` rỗng. Lỗi: `400` `BR-SP-07` đã có phác đồ, sản phẩm hoặc mũi tiêm — chỉ ngừng sử dụng · `401` · `403` · `404`.
- **`GET /vaccination-protocols`** — Phác đồ tiêm chủng. Query: `species?`, `vaccineTypeId?`, `isActive?`. Response `200` mảng `VaccinationProtocol`. Lỗi: `401` · `403`.
- **`POST /vaccination-protocols`** — Thêm dòng phác đồ. Request `CreateProtocolRequest`. Response `201` `VaccinationProtocol`. Lỗi: `400` `BR-SP-02` thiếu trường hoặc khoảng cách ≤ 0 · trùng (loài, loại vaccine, mũi) · loài khác loài của loại vaccine · `401` · `403`.
- **`PATCH /vaccination-protocols/{protocolId}`** — Sửa dòng phác đồ. Request `UpdateProtocolRequest`. Response `200` `VaccinationProtocol`. Lỗi: `400` · `401` · `403` · `404`.
### Public

- **`GET /public/services`** — Dịch vụ công khai. Public. Query: `group?`, `branchId?`. Response `200` mảng `PublicService`. Lỗi: `400`.
- **`GET /public/product-categories`** — Danh mục sản phẩm công khai. Public. Response `200` mảng `ProductCategory`. Lỗi: `400`.
- **`GET /public/products`** — Xem & tìm sản phẩm. Public. Query: `q?`, `categoryId?`, `branchId?`, `page?`, `size?`. Response `200` trang `PublicProduct`. Lỗi: `400`.
- **`GET /public/products/{productId}`** — Chi tiết sản phẩm công khai. Public. Response `200` `PublicProduct`. Lỗi: `404`.

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`Species`** — enum: `DOG`, `CAT`, `OTHER`.
- **`ServiceGroup`** — enum: `MEDICAL`, `GROOMING`, `BOARDING`. Khám/Tiêm, Thẩm mỹ, Lưu trú (05 §0)
- **`MedicalType`** — enum: `EXAM`, `VACCINE`. Chỉ nhóm MEDICAL (BR-SP-06)
- **`ProductType`** — enum: `GOODS`, `DRUG`, `VACCINE`.
- **`ProductCategory`** — {`categoryId`: int64, `name`: string, `isActive`: boolean}
- **`ProductCategoryRequest`** — {`name`: string}
- **`UpdateProductCategoryRequest`** — {`name?`: string, `isActive?`: boolean}
- **`Product`** — {`productId`: int64, `categoryId`: int64, `sku`: string, `name`: string, `productType`: ProductType, `isPrescription`: boolean, `tracksExpiry`: boolean, `vaccineTypeId?`: int64, `unit`: string, `price`: int64, `description?`: string, `imageUrl?`: string, `isActive`: boolean}
- **`CreateProductRequest`** — {`categoryId`: int64, `sku`: string, `name`: string, `productType`: ProductType, `isPrescription`: boolean, `tracksExpiry`: boolean, `vaccineTypeId?`: int64, `unit`: string, `price`: int64, `description?`: string, `imageUrl?`: string}
- **`UpdateProductRequest`** — {`categoryId?`: int64, `name?`: string, `isPrescription?`: boolean, `tracksExpiry?`: boolean, `vaccineTypeId?`: int64, `unit?`: string, `price?`: int64, `description?`: string, `imageUrl?`: string, `isActive?`: boolean}
- **`KennelTypeSpec`** — {`species`: Species, `maxWeightKg`: number} — Chỉ với dịch vụ nhóm BOARDING (BR-SP-04)
- **`Service`** — {`serviceId`: int64, `name`: string, `group`: ServiceGroup, `medicalType?`: MedicalType, `price`: int64, `priceIsFrom`: boolean, `description?`: string, `isActive`: boolean, `kennelType?`: KennelTypeSpec}
- **`CreateServiceRequest`** — {`name`: string, `group`: ServiceGroup, `medicalType?`: MedicalType, `price`: int64, `priceIsFrom?`: boolean, `description?`: string, `kennelType?`: KennelTypeSpec}
- **`UpdateServiceRequest`** — {`name?`: string, `medicalType?`: MedicalType, `price?`: int64, `priceIsFrom?`: boolean, `description?`: string, `isActive?`: boolean, `kennelType?`: KennelTypeSpec}
- **`VaccineType`** — {`vaccineTypeId`: int64, `name`: string, `species`: Species, `isActive`: boolean}
- **`VaccineTypeRequest`** — {`name`: string, `species`: Species}
- **`UpdateVaccineTypeRequest`** — {`name?`: string, `isActive?`: boolean}
- **`VaccinationProtocol`** — {`protocolId`: int64, `species`: Species, `vaccineTypeId`: int64, `doseNumber`: int32, `intervalDays`: int32, `minAgeWeeks`: int32, `requiredForBoarding`: boolean, `isActive`: boolean}
- **`CreateProtocolRequest`** — {`species`: Species, `vaccineTypeId`: int64, `doseNumber`: int32, `intervalDays`: int32, `minAgeWeeks`: int32, `requiredForBoarding`: boolean}
- **`UpdateProtocolRequest`** — {`intervalDays?`: int32, `minAgeWeeks?`: int32, `requiredForBoarding?`: boolean, `isActive?`: boolean} — Chỉ áp dụng cho mũi tiêm sau thời điểm sửa (BR-SP-03)
- **`PublicService`** — {`serviceId`: int64, `name`: string, `group`: ServiceGroup, `price`: int64, `priceIsFrom`: boolean, `description?`: string, `kennelType?`: KennelTypeSpec, `branchIds`: [integer]}
- **`PublicProduct`** — {`productId`: int64, `name`: string, `categoryId`: int64, `categoryName`: string, `price`: int64, `unit`: string, `description?`: string, `imageUrl?`: string, `inStockBranchIds`: [integer]}

---

## D. Bảo mật & độ tin cậy

1. Ghi danh mục chỉ SUPER_MANAGER. Đọc (không public) mở cho mọi nhân viên để dùng ở POS, kê đơn, tiêm.
2. Đổi giá sản phẩm / dịch vụ ghi audit (BR-QT-15); dòng Order đã có giữ giá snapshot (BR-BH-03).
3. API public không bao giờ trả thuốc kê đơn, sản phẩm / dịch vụ ngừng kinh doanh, hay số lượng tồn (BR-CK-01, 03, BR-SP-01).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Nhóm dịch vụ (`group`) và loại sản phẩm (`productType`) không đổi sau khi tạo | Quota, gán nhân viên, dòng Order, phác đồ đều phụ thuộc; docs không có thao tác đổi |
| A2 | Ngừng kinh doanh / ngừng sử dụng = PATCH `isActive = false`; không xóa sản phẩm, dịch vụ, danh mục | 04 nguyên tắc 2 (không xóa cứng); chỉ loại vaccine có rule xóa (BR-SP-07) |
| A3 | Phác đồ: sửa `intervalDays`, `minAgeWeeks`, `requiredForBoarding`, `isActive`; loài, loại vaccine, mũi thứ không đổi | Bộ ba này là khóa duy nhất (05 `vaccination_protocols`); BR-SP-03 |
| A4 | Danh sách sản phẩm cho nhân viên có bộ lọc `retailOnly` (bỏ thuốc kê đơn) để dùng ở POS | BR-SP-01: thuốc kê đơn không bán lẻ |

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Ảnh sản phẩm tải lên bằng cơ chế nào? Contract chỉ nhận URL. | TBD (BE + FE) | 05 `products.image_url` |

---

## F. OpenAPI 3.1

[`./openapi/catalog-v1.yaml`](./openapi/catalog-v1.yaml)
