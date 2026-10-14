# ADR-0040: Tài liệu Swagger sinh từ code, khớp hợp đồng `docs/api`

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-14
- **Liên quan:**
  - Convention 10 (`docs/convention/backend/10-swagger-api-docs.md`) §1 (project đã dùng thư viện sinh từ code → annotation trong code), §2–§5; convention 01 L10 (`platform/config` chứa OpenAPI), 02 L16, L21, L70, 04, 09 L8.
  - Hợp đồng FE ↔ BE `docs/api/openapi/*.yaml` (sinh từ `docs/api/generator/`), ADR-0005 (path public luôn ẩn danh).
  - Nợ mới D026 (branch, catalog chưa có annotation).
- **Triển khai:**
  - `platform/config/{OpenApiConfig, ApiErrorDoc, ApiErrorDocs, ApiErrorDocCustomizer, PublicPathSecurityCustomizer}`.
  - `platform/security/SecurityConfig.publicPaths()`.
  - `@Schema` trên `platform/model/{ApiResponse, PageResponse, ErrorResponse}`.
  - `application.yml` khóa `springdoc.*`.
  - Annotation trên 10 controller và 43 DTO của identity, care, customer (45 endpoint).
  - Test:
    - Unit: `platform/config/OpenApiCustomizersTest`.
    - IT: `OpenApiContractParityIT`.
  - Không migration, không đổi hành vi runtime.

## Bối cảnh

springdoc 2.8.11 đã có từ đầu (`/v3/api-docs`, `/swagger-ui.html`, public). Nhưng không controller, DTO nào có annotation, nên tài liệu sinh ra không dùng được:

- không có summary, lỗi, ví dụ;
- mọi endpoint bị coi là cần token, kể cả path public;
- 49/82 `operationId` lệch hợp đồng (`list`, `create`… bị đánh số `list_1`, `create_2`).

Hợp đồng `docs/api/openapi/*.yaml` (OpenAPI 3.1) đã mô tả đủ từng endpoint: summary, mô tả kèm BR, mã lỗi và mô tả từng lỗi. 82/82 endpoint đã cài đều có trong hợp đồng (method + `/api` + path).

Convention 10 yêu cầu ba điều:
- spec đúng với code thật;
- đủ case lỗi;
- có ví dụ thực tế.

Convention 10 nhắc `scripts/validate_openapi.py` và `references/framework-annotations.md`, nhưng repo không có hai file này.

## Quyết định

### 1. Code-first, phải bằng hợp đồng

Tài liệu runtime là spec springdoc sinh từ annotation. `docs/api` vẫn là hợp đồng thiết kế.

Với mọi endpoint đã cài của module có tài liệu, spec từ code phải **bằng** hợp đồng ở các điểm sau:
- method + path;
- `operationId`, `tags`, `summary`, `description`;
- `security` (`[]` hay bearer);
- tập mã thành công;
- tập mã lỗi (trừ 500), kèm `$ref` hoặc mô tả của từng lỗi;
- tên và vị trí tham số;
- cây tên property của request body và của `data` trong response.

Cách làm:
- Text chép **nguyên văn** từ YAML hợp đồng.
- `OpenApiContractParityIT` đọc `../docs/api/openapi/*.yaml` (tiền lệ `PendingWorkTypeContractTest`) và so với `/v3/api-docs`.

Hệ quả: sửa generator mà quên annotation, hoặc ngược lại, thì IT đỏ.

### 2. Lỗi khai báo bằng `@ApiErrorDoc(status, description)`

Annotation riêng, lặp được, đặt trên method controller. Không dùng `@io.swagger.v3.oas.annotations.responses.ApiResponse`, vì hai lý do:
- tên đó trùng `platform.model.ApiResponse` mà mọi controller đã import;
- viết đúng chuẩn thì mỗi lỗi tốn khoảng 4 dòng.

Cách sinh response:
- **`description` rỗng:** `$ref` tới response dùng chung cùng tên với hợp đồng: `BadRequest` (400), `Unauthorized` (401), `Forbidden` (403), `NotFound` (404). Text giống hệt hợp đồng.
- **Có `description`:** response riêng với schema `ErrorResponse`.
- **Mã không có response dùng chung (409…) mà thiếu `description`:** ném lỗi khi dựng tài liệu, để IT đỏ thay vì sinh response rỗng.

### 3. Security suy ra từ `SecurityConfig.PUBLIC_PATHS`

Dùng cùng danh sách với runtime, như tinh thần ADR-0005.
- **Path khớp:** `security: []`.
- **Path còn lại:** dùng bearer toàn cục; tự thêm `401` → `$ref Unauthorized` nếu chưa khai (hợp đồng có 401 ở mọi endpoint cần token).

Không controller nào tự viết `security`. Scheme đổi tên `bearer-jwt` → **`bearerAuth`** cho trùng hợp đồng.

### 4. 500 tự thêm cho mọi operation

`$ref InternalError` được thêm cho mọi operation. Hợp đồng không ghi 500, nên parity bỏ qua mã này.

### 5. `springdoc.override-with-generic-response: false`

Mặc định (`true`), springdoc gắn vào **mọi** operation các response suy ra từ `@ControllerAdvice`, làm sai tập mã lỗi. Hôm nay các handler của `GlobalExceptionHandler` trả `ResponseEntity<ErrorResponse>` và không có `@ResponseStatus`, nên springdoc chưa suy ra được gì: mutation bật lại khóa này, IT vẫn xanh (2026-10-14). Khóa được giữ để phòng hờ: nếu sau này handler thêm `@ResponseStatus`, mã lỗi của mọi endpoint vẫn chỉ đến từ `@ApiErrorDoc`.

Thêm `default-produces-media-type: application/json` để content type là JSON thay vì `*/*`.

### 6. Ví dụ trên DTO

- Mỗi record component có `@Schema(description, example)`, dữ liệu giống thật.
- Ràng buộc Bean Validation (`@Size`, `@Pattern`, `@NotBlank`…) do springdoc tự sinh, không chép lại.
- `Instant` ví dụ theo UTC `…Z`, đúng cách Jackson trả.
- Field mật khẩu / mã OTP của request đặt `accessMode = WRITE_ONLY`.
- Field chỉ để từ chối (`email` trong body tự sửa hồ sơ, BR-TK-15) đặt `hidden = true`, vì hợp đồng không có field đó.
- IT kiểm hai điều:
  - mọi property lá có `example` (hoặc `enum`), không phải `"string"` / `"TODO"`;
  - response 2xx không có property tên kiểu mật khẩu / OTP / secret / hash. Cờ `mustChangePassword` được miễn.

### 7. Spec và cách expose

- Spec là OpenAPI 3.1 (mặc định springdoc 2.8, cùng phiên bản với hợp đồng). Convention 10 §1 cho dùng 3.1 khi project đã dùng 3.1.
- Swagger UI và `/v3/api-docs` vẫn public ở mọi profile, như trước. Không đổi trong ADR này.

### 8. Validate bằng IT Java, không thêm dependency

- Đọc YAML / JSON bằng `io.swagger.v3.core.util.Yaml` và Jackson, đều đã có qua springdoc.
- IT ghi spec ra `target/openapi.json`, để khi smoke có thể chạy `openapi-spec-validator` bằng tay. Lần chạy 2026-10-14 cho kết quả OK.
- Không thêm `scripts/validate_openapi.py`.

## Hệ quả

- Sửa hợp đồng của endpoint đã cài (generator `docs/api`) thì phải sửa annotation cùng PR. `OpenApiContractParityIT` chỉ đúng chỗ lệch.
- Endpoint mới của module có tài liệu phải có `@Operation` + `@ApiErrorDoc` + `@Schema` ngay khi cài, nếu không IT đỏ.
- Module của BE-2 (branch, catalog) chưa có annotation: IT chỉ kiểm "endpoint có trong hợp đồng". Khi BE-2 làm theo convention 10 §7 thì thêm file hợp đồng vào `OpenApiContractParityIT.DOCUMENTED` (nợ D026).
- Annotation mang text có mã BR. Đây là chuỗi tài liệu chép từ hợp đồng, không phải logic, nên không vi phạm convention 02: controller không biết mã rule.
- `OpenApiConfig.BEARER_SCHEME` đổi giá trị. Hằng số này không được tham chiếu ở chỗ nào khác.
