# Swagger / OpenAPI API Docs

Mục tiêu: tạo tài liệu API mà người khác (frontend, đối tác, QA) đọc là gọi được ngay, không cần hỏi lại backend. Một spec tốt phải **đúng với code thật**, **đầy đủ các case lỗi**, và **có ví dụ thực tế**.

## 1. Xác định đầu vào và đầu ra

Trước khi viết, xác định:

- **Nguồn thông tin**: code (controller/route/DTO/model), mô tả bằng lời, Postman collection, cURL, hay file swagger cũ cần sửa. Nếu có code, đọc code là nguồn sự thật — đừng đoán field.
- **Dạng output**:
  - *Spec độc lập* (`openapi.yaml`) → mặc định, dùng YAML, OpenAPI `3.0.3` (tương thích tool rộng nhất). Dùng `3.1.0` chỉ khi người dùng yêu cầu hoặc project đã dùng 3.1.
  - *Annotation trong code* → khi project đã dùng thư viện sinh swagger từ code. Đọc `references/framework-annotations.md` để viết đúng cú pháp của framework đó.
- **Ngôn ngữ của phần mô tả** (`summary`, `description`): theo ngôn ngữ người dùng hoặc theo docs sẵn có của project. Tên field, operationId, tag giữ tiếng Anh.

Nếu thiếu thông tin quan trọng (auth kiểu gì, base URL, format lỗi chung), hỏi một câu gọn hoặc đặt giả định hợp lý và ghi rõ giả định đó ở cuối câu trả lời.

## 2. Quy trình viết spec

1. **Khung**: bắt đầu từ `assets/openapi-template.yaml` (info, servers, securitySchemes, schema lỗi chung, responses dùng lại).
2. **Liệt kê endpoint**: method + path cho từng route. Path param viết `{id}`, không phải `:id`.
3. **Tách schema dùng chung** vào `components/schemas`, tham chiếu bằng `$ref`. Không lặp lại cùng một object ở nhiều endpoint. Tách riêng schema input và output khi chúng khác nhau (ví dụ `UserCreateRequest`, `UserUpdateRequest`, `User`) — vì `id`, `createdAt` chỉ có ở response, còn `password` chỉ có ở request.
4. **Mỗi operation** có đủ:
   - `tags` (nhóm theo resource), `summary` (≤ 10 từ, dạng động từ: "Lấy danh sách user"), `description` khi có logic cần giải thích (quyền, side-effect, giới hạn).
   - `operationId` duy nhất, camelCase (`listUsers`, `getUserById`, `createOrder`) — tool sinh client dùng nó làm tên hàm.
   - `parameters`: path/query/header, mỗi cái có `description`, `schema`, `required`, `example`. Query phân trang/lọc/sắp xếp dùng lại từ `components/parameters`.
   - `requestBody` với `content` type đúng (`application/json`, `multipart/form-data` cho upload).
   - `responses`: response thành công **và** các lỗi thực sự có thể xảy ra (xem mục 3).
   - `security` nếu endpoint khác mặc định (ví dụ endpoint public thì `security: []`).
5. **Schema chi tiết**: mỗi property có `type`, `description`, `example`; thêm `format` (`uuid`, `email`, `date-time`, `int64`, `uri`, `binary`), ràng buộc (`minLength`, `maxLength`, `minimum`, `pattern`, `enum`), `required` ở cấp object, `nullable: true` (3.0) hoặc `type: [string, "null"]` (3.1), `readOnly`/`writeOnly` khi phù hợp.
6. **Ví dụ**: mỗi request body và response thành công nên có `example` thực tế (dữ liệu giống thật, không phải `"string"`). Dùng `examples` (số nhiều) khi có nhiều kịch bản.
7. **Validate** (mục 5) rồi mới giao.

## 3. Response lỗi

Chỉ liệt kê lỗi mà endpoint thật sự trả. Gợi ý theo loại endpoint:

| Tình huống | Mã |
|---|---|
| Body/query sai định dạng, validation fail | 400 (hoặc 422 nếu project dùng) |
| Thiếu/sai token | 401 |
| Không đủ quyền | 403 |
| Resource theo `{id}` không tồn tại | 404 |
| Trùng unique (email đã tồn tại…) | 409 |
| Rate limit | 429 |
| Lỗi server | 500 (thường khai báo một lần qua `$ref`) |

Dùng `components/responses` (`BadRequest`, `Unauthorized`, `NotFound`…) để không lặp lại. Nếu project có format lỗi riêng, mô tả đúng format đó thay vì format trong template.

Thành công: `200` cho GET/PUT/PATCH trả dữ liệu, `201` cho POST tạo mới (kèm header `Location` nếu có), `204` cho DELETE/không có body.

## 4. Quy ước đặt tên và nhất quán

- Theo convention của project (camelCase hay snake_case cho field). Không trộn lẫn.
- Tên schema PascalCase, số ít (`User`, không phải `users`).
- Danh sách có phân trang: bọc trong object có `data` + `meta`/`pagination` đúng như API thật trả, không phải trả mảng trần nếu API không làm thế.
- Enum: liệt kê giá trị và giải thích ý nghĩa từng giá trị trong `description` nếu không hiển nhiên.
- Tiền tệ, thời gian: ghi rõ đơn vị (VND, cents, ISO 8601, UTC/múi giờ).

## 5. Kiểm tra trước khi giao

Chạy validator:

```bash
python scripts/validate_openapi.py path/to/openapi.yaml
```

Script dùng `openapi-spec-validator` nếu cài được (`pip install openapi-spec-validator pyyaml --break-system-packages`), và luôn chạy thêm các check chất lượng (thiếu operationId, thiếu example, `$ref` hỏng, path param không khai báo…). Sửa hết lỗi; cảnh báo thì sửa nếu hợp lý.

Tự rà thêm:
- [ ] Mọi `{param}` trong path đều có trong `parameters` với `in: path, required: true`.
- [ ] Không còn placeholder như `"string"`, `0`, `TODO` trong example.
- [ ] Field nhạy cảm (`password`, token) là `writeOnly` hoặc không xuất hiện trong response.
- [ ] Mỗi endpoint cần auth đều phản ánh đúng `security`.

## 6. Cách trình bày kết quả

- Spec ngắn (vài endpoint): đưa YAML trong câu trả lời.
- Spec dài hoặc cả API: tạo file `openapi.yaml`, kèm 2–3 câu tóm tắt: số endpoint, giả định đã đặt, chỗ cần người dùng xác nhận.
- Annotation trong code: sửa trực tiếp file controller/DTO người dùng đưa, giữ nguyên logic code.
- Gợi ý cách xem: dán vào https://editor.swagger.io hoặc chạy Swagger UI / Redoc.

## Tài nguyên

- `assets/openapi-template.yaml` — khung spec với auth, lỗi chung, phân trang.
- `references/framework-annotations.md` — cú pháp annotation cho NestJS, Express (swagger-jsdoc), FastAPI, Spring (springdoc), Go (swaggo), Laravel (l5-swagger), ASP.NET (Swashbuckle). Đọc phần tương ứng khi cần viết docs trong code.
- `scripts/validate_openapi.py` — validate cấu trúc và chất lượng spec.

## 7. Áp dụng ở Pet Care (springdoc, ADR-0040)

Pet Care đã dùng springdoc. Theo §1, tài liệu viết bằng **annotation trong code**, không viết `openapi.yaml` tay. Hợp đồng thiết kế vẫn là `docs/api/openapi/*.yaml`, sinh từ `docs/api/generator/`. Tài liệu runtime (`/v3/api-docs`, `/swagger-ui.html`) phải **bằng** hợp đồng đó, và `OpenApiContractParityIT` kiểm điều này.

### Phần chung đã có (không viết lại)

| Việc | Ở đâu |
|---|---|
| Thông tin API, scheme `bearerAuth` mặc định cho mọi operation, schema `ErrorResponse`, response chung `BadRequest` / `Unauthorized` / `Forbidden` / `NotFound` / `InternalError`, tag | `platform/config/OpenApiConfig` |
| `security: []` cho path public, theo đúng `SecurityConfig.PUBLIC_PATHS`; tự thêm `401` cho endpoint cần token | `platform/config/PublicPathSecurityCustomizer` |
| `@ApiErrorDoc` → `responses`; tự thêm `500` | `platform/config/ApiErrorDocCustomizer` |
| `@Schema` cho `ApiResponse`, `PageResponse`, `ErrorResponse` | `platform/model` |
| Không gắn mọi `@ExceptionHandler` vào mọi operation | `application.yml`: `springdoc.override-with-generic-response: false` |

### Mỗi endpoint

```java
@Operation(operationId = "lockAccount", tags = "Accounts",
        summary = "Khóa tài khoản",
        description = "UC09 · BR-QT-04, 11, … (chép nguyên văn description của hợp đồng)",
        parameters = {
                @Parameter(name = "id", in = ParameterIn.PATH, example = "42")})
@ApiErrorDoc(status = 400, description = "Thiếu lý do (`VALIDATION_FAILED`) · `BR-QT-11` khóa chính mình")
@ApiErrorDoc(status = 403)          // không mô tả = $ref response chung cùng mã
@ApiErrorDoc(status = 404)
@ApiErrorDoc(status = 409, description = "`INVALID_STATE_TRANSITION` đã bị khóa · …")
@PostMapping("/{id}/lock")
@PreAuthorize(ADMIN_ONLY)
public ApiResponse<LockResult> lock(…)   // tên method Java tùy ý, operationId mới là tên trong spec
```

- **`@Operation`:** `operationId`, `tags`, `summary`, `description` lấy **nguyên văn** từ operation tương ứng trong `docs/api/openapi/<module>-v1.yaml`.
- **`@ApiErrorDoc`:** khai báo mọi mã 4xx của hợp đồng.
  - Hợp đồng dùng `$ref` (BadRequest, Forbidden…) → không ghi `description`.
  - Hợp đồng mô tả riêng → chép mô tả.
  - Không khai `401` của endpoint cần token, cũng không khai `500`: hai mã này tự thêm.
  - Mã không có response chung (409…) bắt buộc có `description`; thiếu là lỗi khi dựng tài liệu.
- **Không** viết `@SecurityRequirement` hay `security`. Path public là các path trong `SecurityConfig.PUBLIC_PATHS`.
- **`@Parameter(name, in, description, example)`:** dùng cho path / query param, tên trùng hợp đồng.
- **Thành công:** 200/201/202/204 lấy từ `@ResponseStatus` và kiểu trả về như convention 02. Không khai lại.

### Mỗi DTO (record)

- Mỗi component có `@Schema(description = "…", example = "…")`, ví dụ giống thật.
  - Enum: `example = "VET"`.
  - Thời điểm `Instant`: UTC `2026-10-14T02:30:00Z`.
  - Object / list lồng nhau: chỉ cần `description`.
- Mật khẩu, mã OTP trong request: thêm `accessMode = Schema.AccessMode.WRITE_ONLY`. Response không có các field này.
- Field có trong record nhưng không có trong hợp đồng (ví dụ `email` chỉ để từ chối, BR-TK-15): `@Schema(hidden = true)`.
- Không chép ràng buộc Bean Validation vào `@Schema`; springdoc tự sinh `minLength`, `pattern`, `required`…

### Kiểm tra

- Thay cho `scripts/validate_openapi.py` (không có trong repo) là `mvn verify -Dit.test=OpenApiContractParityIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false`. IT so mọi điểm ở trên với hợp đồng, kiểm checklist §5 (operationId duy nhất, path param, `$ref`, ví dụ, field nhạy cảm) và ghi spec ra `BE/target/openapi.json`.
- Validator ngoài (tùy chọn): `pip install openapi-spec-validator` rồi `python -m openapi_spec_validator BE/target/openapi.json`.
- Module mới viết xong tài liệu: thêm tên file hợp đồng vào `OpenApiContractParityIT.DOCUMENTED`. Hiện có identity, care, customer; branch, catalog còn nợ D026 (BE-2).
- Sửa hợp đồng (generator `docs/api`) của endpoint đã cài thì sửa annotation trong cùng PR.
