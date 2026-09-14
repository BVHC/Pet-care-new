# Task 5 Report — PetController (web slice)

## Status: DONE

## Commits
- `feat(pet): add PetController CRUD` (code + docs, không push)

## Files
- `BE/src/main/java/com/petcare/module/pet/controller/PetController.java` — CRUD theo đúng khung plan:
  `POST /api/pets → 201`, `GET /api/pets → 200 Page`, `GET /api/pets/{id} → 200`,
  `PATCH /api/pets/{id} → 200`; `@PreAuthorize("isAuthenticated()")`;
  principal `UserPrincipal → getUserId()`; envelope `ApiResponse.created/ok` + `PageResponse.of`;
  không logic RULE-ID.
- `BE/src/test/java/com/petcare/module/pet/controller/PetControllerTest.java` — `@WebMvcTest` + `@MockitoBean PetService`, 4 cases.
- `docs/api/customer-pet-v1.md` (§C): blockquote phạm vi v1 + `transfer`/C2/C3 đánh dấu **OUT v1**;
  C1 response `isActive: true` → `status: "ACTIVE"` (PetStatus enum, v1 khóa ACTIVE per RULE-04-11).

## Test summary
- RED: `mvn test -Dtest=PetControllerTest` FAIL compilation (`cannot find symbol: PetController`) — đúng kỳ vọng.
- GREEN: `mvn test -Dtest=PetControllerTest` → **Tests run: 4, Failures: 0, Errors: 0** — BUILD SUCCESS.
- Cases: `create_invalidName_400` (case plan bắt buộc) + `detail_missingPet_404` +
  `detail_otherOwnersPet_403` + `list_withoutAuth_401`.

## Deviations khỏi plan (có lý do)
1. `list(...)`: plan ghi `svc.list(me, pageable)` — sửa thành `svc.list(me.getUserId(), pageable)`
   cho khớp signature `PetService.list(UUID, Pageable)` và 3 endpoint còn lại.
2. Test dùng `SecurityMockMvcRequestPostProcessors.authentication(UserPrincipal)` thay vì
   `@WithMockUser`: `@WithMockUser` cho principal kiểu `String` nên `@AuthenticationPrincipal
   UserPrincipal` resolve null; auth trực tiếp bằng `UserPrincipal` thật test đúng behavior hơn.
3. `@Import` thêm `CorsConfig`, `TraceIdFilter`, `JwtAuthenticationFilter`,
   `RestAuthenticationEntryPoint`, `RestAccessDeniedHandler` (real) + `@MockitoBean
   JwtTokenProvider/TokenBlacklistService`: `SecurityConfig` là `@RequiredArgsConstructor`,
   slice không load các bean này nên phải cung cấp tường minh; filter/entry-point real giữ
   behavior 401/403 thật (case `list_withoutAuth_401` PASS qua filter chain thật).
   `GlobalExceptionHandler` là `@RestControllerAdvice` nên slice tự load (giữ trong `@Import`
   theo plan, harmless).
4. Thêm `@SecurityRequirement(BEARER)` class-level (copy `AuthController.logout`), plan không
   nêu nhưng nhất quán OpenAPI.

## Concerns
- `docs/api/openapi/customer-pet-v1.yaml` (dòng ~420: `PetResponse` vẫn `isActive`) chưa sửa —
  ngoài scope plan (chỉ §C1-C4 file md); Task 6 hoặc follow-up nên đồng bộ yaml.
- Các mention `is_active` còn lại trong md (dòng 24/60/136) là ngữ cảnh lịch sử ERD + bảng Q —
  cố ý giữ, không phải contract.
- Method security (`@PreAuthorize`) trong slice chỉ active nhờ `@Import(SecurityConfig.class)`
  (có `@EnableMethodSecurity`); nếu ai đó bỏ import này, `@PreAuthorize` lặng lẽ mất hiệu lực
  mà test vẫn xanh (trừ case 401 đi qua filter). E2E Task 6 (`PetFlowIT`) sẽ cover guard thật.
