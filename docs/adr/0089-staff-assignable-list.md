# ADR-0089: Nhân viên có thể gán lượt — `GET /api/staff/assignable` (identity-v1 #38)

- **Trạng thái:** **Proposed (chờ BE-1 xác nhận)**
- **Ngày:** 2026-10-31
- **Liên quan:**
  - identity-v1 #38 `GET /api/staff/assignable`; visit-v1 #5 `POST /visits/{visitId}/assign` (lễ tân gán), #9 `POST /visits/{visitId}/reassign` (gán lại lượt đã gọi); 02 BR-TN-05, BR-TN-06, BR-TN-08; UC45.
  - ADR-0024 (`StaffDirectoryApi`, `findAssignableStaff`, "active" = ACTIVE và không khóa, online theo phiên chưa hủy với access token còn hạn: ADR-0091), ADR-0061 (gán lượt: kiểm lại người được gán dưới khóa), ADR-0063 (gán lại lượt đã gọi), ADR-0030 (`GET /api/staff` chỉ A03–A05), ADR-0029 (thứ tự lỗi quản trị nhân sự), ADR-0040 (Swagger = hợp đồng), ADR-0006 (CUSTOMER không có phạm vi chi nhánh).
  - docs/06-module-contracts.md §4.2 (dòng "Gán / gán lại": "Danh sách chọn người cho FE chưa có endpoint") và §6 (ghi chú cho BE-1).
- **Triển khai:**
  - Người viết: FE-1 (Hiến), **được người dùng cho phép sửa module `identity` của BE-1** cho việc này; BE-1 chưa xem. Các file dưới đây nằm trong module của BE-1.
  - Sửa: `identity/controller/StaffController` (thêm handler `listAssignableStaff`, hằng `ASSIGNERS`, `AssignableStaffService` vào constructor, sửa Javadoc lớp).
  - Mới: `identity/service/AssignableStaffService`, `identity/dto/{AssignableStaffResponse, AssignableRole}`, `identity/mapper/AssignableStaffMapper`, `identity/exception/BranchSelectionRequiredException`.
  - **Không đổi** `StaffDirectoryApi`, `StaffDirectoryService`, repository, `StaffQueryService` (nên không phải sửa `StaffQueryServiceTest` và không đổi bảng §6 của 06 về `StaffDirectoryApi`).
  - Hợp đồng: `docs/api/generator/m_identity.py` (schema `AssignableStaff` + op **#38 thêm ở cuối file**) → `identity-v1.md`, `openapi/identity-v1.yaml` sinh lại bằng `generate.py identity`; `docs/api/INDEX.md` (identity 37 → 38, tổng 230 → 231, thêm UC45 ở dòng identity).
  - Test: `StaffControllerSecurityTest` (3, mới), `AssignableStaffServiceTest` (9, mới), `AssignableStaffIT` (11, mới); `OpenApiContractParityIT` đổi số endpoint đã cài 180 → 181.

## Bối cảnh

Màn gán lượt của lễ tân (visit-v1 #5) cần danh sách VET / CARETAKER của chi nhánh để chọn người; `StaffDirectoryApi.findAssignableStaff` đã có (ADR-0024) nhưng **không có endpoint nào** trả nó cho FE. Endpoint duy nhất liệt kê nhân viên là `GET /api/staff` (#15), và nó chỉ mở cho ADMIN, SUPER_MANAGER, BRANCH_MANAGER (`STAFF_ADMINS`, ADR-0030): lễ tân nhận 403 `ACCESS_DENIED`, nên hộp chọn người gán của lễ tân bị khóa. Cùng lúc, BRANCH_MANAGER và SUPER_MANAGER gán lại lượt đã gọi (#9) cũng cần chính danh sách này.

## Quyết định

### 1. Thêm endpoint hẹp, không mở `GET /api/staff`

`GET /api/staff/assignable?role=VET|CARETAKER[&branchId=<id>]`, `operationId = listAssignableStaff`, tag `Staff`, identity-v1 **#38**. `GET /api/staff` giữ nguyên (`STAFF_ADMINS`, lễ tân vẫn 403; IT ghim). Lý do: `StaffController` là quản trị nhân sự, `StaffResponse` có email, SĐT, trạng thái khóa, `mustChangePassword`… — mở nó cho lễ tân là lộ quá mức cần cho việc chọn người gán. Người gán chỉ cần biết ai chọn được, theo thứ tự gợi ý.

### 2. Đặt trong `StaffController`, đường dẫn `/api/staff/assignable`

- **Đặt trong `StaffController`** (cùng tiền tố `/api/staff`, cùng tag `Staff`) thay vì controller riêng: tài nguyên là "nhân viên", `StaffController` đã là nơi có `@RequestMapping("/api/staff")`; một controller mới chỉ để chứa một handler thêm một bean và một lớp quyền nữa. Rủi ro của việc ở chung — một handler mới quên `@PreAuthorize` thì `SecurityConfig` (`anyRequest().authenticated()`) mở cho cả khách hàng — được chặn bằng `StaffControllerSecurityTest` (ghim cả 10 handler cũ và handler mới, thiếu `@PreAuthorize` là đỏ; **ASSIGNERS là hằng riêng**, không dùng lại `STAFF_ADMINS`). Javadoc lớp được sửa: quản trị nhân sự + một endpoint đọc hẹp cho người gán.
- **Đường dẫn `/staff/assignable`** (không phải `/assignable-staff`): giữ tiền tố tài nguyên `staff` (`/staff/{id}`, `/staff/{id}/disable`… đều dưới đó), gom cùng nhóm Swagger `Staff`; `/assignable-staff` là một tài nguyên gốc mới không có tiền lệ trong 12 hợp đồng.
- **Route literal thắng `/{id}`:** `GET /{id}` có `@PathVariable Long id`; Spring ưu tiên đoạn literal nên `/assignable` không bao giờ vào `get`. IT chứng minh cả hai chiều: `/assignable` → 200 (và khi thiếu `role` lỗi là "Thiếu tham số 'role'" của handler mới, không phải "Tham số 'id' sai kiểu"), `GET /api/staff/abc` vẫn 400 `MALFORMED_REQUEST`, `GET /api/staff/{số}` vẫn trả `StaffResponse`.
- Số **#38 = thứ tự khai báo** trong `m.ops`: op thêm ở **cuối** `m_identity.py` để không đánh số lại #25–37 mà ADR và FE đang trích. Hệ quả phụ: `lib.py` in thêm một tiêu đề "### Staff" thứ hai ở mục C của `identity-v1.md` (chỉ hiển thị).

### 3. Vai trò gọi = tập vai trò của lệnh gán và gán lại

`ASSIGNERS = hasAnyRole('RECEPTIONIST','BRANCH_MANAGER','SUPER_MANAGER')`: `assign` (#5) chỉ RECEPTIONIST, `reassign` (#9) BRANCH_MANAGER và SUPER_MANAGER (`VisitController.RECEPTIONIST`, `REASSIGN_ROLES`). Ai không có quyền gán thì không có lý do đọc danh sách chọn. ADMIN, VET, CARETAKER, CUSTOMER → 403 `ACCESS_DENIED` (ADMIN là tài khoản kỹ thuật, không vận hành hàng đợi). Quyền thật của lệnh gán vẫn kiểm ở #5 / #9 (BR-TN-05 dưới khóa, ADR-0061, 0063); endpoint này chỉ là danh sách đọc.

### 4. Tham số

- `role` **bắt buộc**, kiểu enum riêng `AssignableRole { VET, CARETAKER }` (VET cho nhóm Khám / Tiêm, CARETAKER cho nhóm Thẩm mỹ — BR-TN-05). Thiếu → 400 `MALFORMED_REQUEST` "Thiếu tham số 'role'"; giá trị ngoài hai chức vụ (BRANCH_MANAGER, RECEPTIONIST, ADMIN, `xyz`, và cả `vet` viết thường — chuyển kiểu phân biệt hoa thường) → lỗi chuyển kiểu của Spring → 400 `MALFORMED_REQUEST` "Tham số 'role' sai kiểu dữ liệu". **Chọn một hình dạng lỗi:** cả ba ca (thiếu, ngoài tập, sai kiểu) cùng `MALFORMED_REQUEST` qua `GlobalExceptionHandler`, không guard thứ hai trong service, nên không cần exception thêm cho `role`.
- `branchId` tùy chọn, `@Positive` như `StaffController.list`: ≤ 0 → 400 `VALIDATION_FAILED`; không phải số → 400 `MALFORMED_REQUEST`.

### 5. Phạm vi chi nhánh

`AssignableStaffService.listAssignable(role, branchId)` (`@Transactional(readOnly = true)`): `effective = branchScope.resolve(branchId)`.

- A05 / A06 (BRANCH): chi nhánh của mình; không gửi `branchId` = chi nhánh mình; gửi chi nhánh khác → `AccessDeniedScopeException` 403 `ACCESS_DENIED_SCOPE_MISMATCH` (không audit, như `GET /api/staff`).
- A04 (CHAIN): `resolve` trả nguyên giá trị gửi. **Không gửi `branchId` → `effective == null` → 400** bằng exception mới `BranchSelectionRequiredException extends PlatformException` với `ErrorCode.VALIDATION_FAILED` và message "Vui lòng chọn chi nhánh" (**không gắn mã BR**, khuôn `InvalidBoardingRequestException`). Lý do: "thiếu tham số bắt buộc với người này" không phải rule `BR-*`; BR-TN-05 là quy tắc "ai được gán", dùng nó cho ca thiếu chi nhánh sẽ sai nghĩa. CLAUDE.md chỉ cho subclass khi khác status / mã: ở đây khác **mã** (`VALIDATION_FAILED`, `BusinessRuleViolationException` luôn là `BUSINESS_RULE_VIOLATION` kèm `(BR-…)`).
- **Guard `effective == null` đứng trước `StaffDirectoryApi.findAssignableStaff`** vì `StaffDirectoryService.findAssignableStaff` gọi `Objects.requireNonNull(branchId)` — thiếu guard thì SUPER_MANAGER không gửi `branchId` nhận NPE → 500. Unit test `verifyNoInteractions(directory)` ghim.
- Chi nhánh không tồn tại (SUPER_MANAGER gửi) → **200 mảng rỗng, không 404**: không để dò mã chi nhánh bằng endpoint này, và danh sách rỗng đúng nghĩa ("không có ai để chọn"). Chi nhánh `DRAFT` có nhân viên thì vẫn liệt kê (không lọc trạng thái chi nhánh — như `findAssignableStaff`).

### 6. Thứ tự lỗi

401 → 400 `BR-TK-17` (`MustChangePasswordInterceptor`) → 400 tham số (`role` thiếu / ngoài tập / sai kiểu; `branchId` ≤ 0 hoặc không phải số) → 403 vai trò (`@PreAuthorize`, `ACCESS_DENIED`) → 403 phạm vi (`resolve`, `ACCESS_DENIED_SCOPE_MISMATCH`) → 400 SUPER_MANAGER thiếu chi nhánh → 200. Bean Validation và chuyển kiểu chạy **trước** `@PreAuthorize` (CLAUDE.md "Error precedence", khuôn `StaffAdminIT.wrongRoleIs403AccessDeniedWithoutAuditButBadBodyIs400First`): một VET / CUSTOMER gửi `role` sai nhận 400, gửi đúng nhận 403. IT `badParametersAre400BeforeTheRoleCheckAndValidOnesAre403` ghim cả hai chiều.

### 7. Response

`ApiResponse<List<AssignableStaffResponse>>` qua `ok(...)`, mảng trả **thẳng trong `data`, không phân trang** (`docs/api/00-method.md` §3.4; cùng cách `listPublicVets`): số VET / CARETAKER của một chi nhánh nhỏ. `AssignableStaffResponse` đúng **4 trường**: `accountId`, `fullName`, `role` (VET | CARETAKER), `online`. Không trả email, SĐT, trạng thái, khóa, `branchId`, chuyên môn, giới thiệu, `mustChangePassword`.

- **`accountId`, không `id`:** quy tắc `id` ở `docs/api/00-method.md:36` chỉ nói tham số path; mọi response nhân viên khác (`StaffResponse`, `PublicVet`) dùng `accountId`, và giá trị này chính là `assigneeId` của #5, #9.
- **`online`:** có phiên chưa hủy với access token còn hạn (BR-TN-06, ADR-0091) — **chỉ là gợi ý**, vẫn gán được người offline (cảnh báo `assigneeOffline` ở #5). Thứ tự do `findAssignableStaff` quyết: online trước, rồi họ tên, rồi `accountId`; service giữ nguyên thứ tự, không sắp lại.

### 8. Không audit, không khóa; tái dùng `StaffDirectoryApi`

Đọc, không ghi nên không audit. Không khóa: danh sách chỉ là ảnh chụp; giữa lúc xem và lúc gán người đó có thể bị khóa, vô hiệu hóa hay điều chuyển, nên lệnh gán (#5, #9) kiểm lại dưới khóa `accounts FOR SHARE` (ADR-0061, 0063). Nguồn dữ liệu là `StaffDirectoryApi.findAssignableStaff(branchId, role)` — nhân viên `ACTIVE` ∧ không `is_locked` (khóa tạm `locked_until` không loại), đúng chi nhánh và chức vụ — **cùng định nghĩa với kiểm tra của lệnh gán**, nên danh sách không hứa người mà lệnh gán sẽ từ chối. Interface không đổi nên bảng 06 §6 về `StaffDirectoryApi` không đổi. Ánh xạ `StaffSummary → AssignableStaffResponse` bằng MapStruct (`unmappedTargetPolicy = ERROR`); `branchId` và `active` của `StaffSummary` cố ý không ra ngoài.

### 9. Hợp đồng và Swagger

UC45 · BR-TN-05, 06 (nguồn: `docs/01-business-operations.md` dòng UC45 "Điều phối hàng đợi — gán nhân viên, ưu tiên nhân viên đang online … gán lại" và `m_visit.py` của `assignVisit` / `reassignCalledVisit`); Actor A04, A05, A06 (A06 gán; A05, A04 gán lại — BR-TN-08). `@Operation.description`, mô tả 400 / 403 trong `@ApiErrorDoc` chép **nguyên văn** từ `openapi/identity-v1.yaml`; không viết `security` / 401 / 500. `OpenApiContractParityIT` giữ số endpoint đã cài: 181.

## Phương án đã loại

- **Mở `GET /api/staff` cho lễ tân:** lộ email / SĐT / trạng thái của mọi nhân viên chi nhánh, và `GET /api/staff/{id}`, `POST …/disable`… dùng cùng hằng `STAFF_ADMINS` sẽ phải tách. Đổi một hằng chung để mở một nhu cầu hẹp là hướng sai.
- **Trả `StaffResponse` rút gọn qua chính `GET /api/staff` khi người gọi là lễ tân (hai hình dạng một endpoint):** hợp đồng không biểu diễn được "response phụ thuộc người gọi", FE phải rẽ nhánh theo vai trò, Swagger sai với một trong hai.
- **Loại SUPER_MANAGER khỏi `ASSIGNERS`:** SUPER_MANAGER gán lại lượt đã gọi khi chi nhánh không còn BRANCH_MANAGER ACTIVE (BR-TN-08, #9); thiếu quyền đọc danh sách thì ca đó không chọn được người.
- **Dùng `id` thay `accountId`:** lệch mọi response nhân viên khác và lệch tên `assigneeId` mà FE sẽ gửi; quy tắc `id` chỉ áp cho tham số path.
- **Guard `role` trong service bằng exception `VALIDATION_FAILED`:** hai hình dạng 400 cho cùng một tham số (thiếu → `MALFORMED_REQUEST`, ngoài tập → `VALIDATION_FAILED`). Kiểu enum riêng cho ra một hình dạng.
- **404 khi chi nhánh không tồn tại:** cho phép dò mã chi nhánh; "không có ai để chọn" là câu trả lời đúng.
- **Gán 400 thiếu chi nhánh vào BR-TN-05:** sai nghĩa (xem §5).
- **Controller riêng `AssignableStaffController`:** thêm bean và lớp quyền chỉ cho một handler; xem §2.

## Hệ quả

- `GET /api/staff` **vẫn đóng với lễ tân** (`StaffAdminIT` đã ghim 403 cho VET / khách; `AssignableStaffIT.receptionistIsDeniedTheStaffDirectoryButReadsTheAssignableList` ghim riêng cho lễ tân).
- Hợp đồng thêm **một endpoint** (identity 37 → 38, tổng 230 → 231). FE-1 đổi hook / mock của hộp gán: mock phải theo BE thật — lễ tân gọi `/staff/assignable?role=…`, SUPER_MANAGER bắt buộc `branchId`, phản hồi 4 trường.
- **Điểm merge với nhánh `manh` (BE-1):** `StaffController` (handler, hằng, constructor, Javadoc lớp), `OpenApiContractParityIT` (180 → 181 và chú thích), `docs/api/generator/m_identity.py` (op #38 ở cuối, schema `AssignableStaff`), `docs/api/identity-v1.{md,yaml}` + `docs/api/INDEX.md` (file sinh / tổng số), số ADR 0089. Nếu BE-1 thêm op identity khác ở cuối `m_identity.py`, số thứ tự phải sắp lại và ADR này đổi số.
- `OpenApiContractParityIT` so cây tên property, tên tham số và chuỗi mô tả với yaml: Java và hợp đồng phải đổi cùng một lúc (đã chép nguyên văn).

## Kiểm chứng

Mục này chỉ chép những gì còn trong `BE/target/{surefire,failsafe}-reports` (31/10 và 1/11) và trong mã nguồn; chỗ nào không có bản ghi thì ghi rõ là chưa chạy.

- **Unit, xanh** (surefire: 31/10 01:39; bản ghi mới nhất 1/11 10:41 cũng 3/3 và 9/9). `StaffControllerSecurityTest` 3/3: `everyStaffHandlerCarriesExactlyItsContractRoles` (11 handler đúng vai trò, `ASSIGNERS` là hằng riêng), `onlyTheAssignableListIsOpenToTheReceptionist` (chỉ `listAssignableStaff` chứa RECEPTIONIST), `theAssignableListIsReadOnlyAndNeverReachesAdminVetCaretakerOrCustomer` (GET `/assignable`, không ADMIN / VET / CARETAKER / CUSTOMER). `AssignableStaffServiceTest` 9/9: `branchScopedActorsWithoutBranchIdListTheirOwnBranch`, `branchScopedActorsMayRepeatTheirOwnBranchId`, `branchScopedActorsAskingForAnotherBranchAreDeniedWithoutReadingAnything`, `superManagerListsTheBranchItChose`, `superManagerWithoutABranchGets400BeforeTheDirectoryIsCalled` (có `verifyNoInteractions(directory)`), `aCustomerIsDeniedByTheScopeEvenIfTheRoleCheckWereMissing`, `mapsExactlyFourFieldsAndKeepsTheOrderOfTheDirectory`, `anEmptyDirectoryGivesAnEmptyList`, `theRoleParameterHasExactlyTheTwoAssignableRoles`.
- **IT `AssignableStaffIT` (11 ca, Postgres thật): xanh 11/11** theo bản ghi failsafe mới nhất trên đĩa (1/11 10:43:02, 0 đỏ, 0 lỗi, 0 bỏ qua; lead chạy lại, bản ghi cũ bên dưới đã bị ghi đè). Tên 11 ca trong mã nguồn: `receptionistIsDeniedTheStaffDirectoryButReadsTheAssignableList`, `listsOnlyActiveUnlockedStaffOfTheCallersBranchAndRoleOnlineFirstWithFourKeys`, `theCaretakerListHoldsOnlyCaretakers`, `aStaffMemberCreatedJustNowIsListedOnTheNextCall`, `scopeMatrixForEachCaller`, `superManagerMustChooseABranchAndAnUnknownBranchIsAnEmptyList`, `adminVetCaretakerAndCustomerAreDeniedAndAnonymousIs401`, `aCallerWhoMustChangeThePasswordIsBlockedByBrTk17`, `badParametersAre400BeforeTheRoleCheckAndValidOnesAre403`, `anAuthorizedCallerWithBadParametersGets400`, `assignableIsNotSwallowedByTheIdRouteAndTheIdRouteIsUnchanged`. Dấu vết cũ: bản ghi failsafe trước đó (31/10 01:38:34) là 11 chạy, 10 xanh, 1 đỏ là `listsOnlyActiveUnlockedStaffOfTheCallersBranchAndRoleOnlineFirstWithFourKeys` (danh sách có thêm id 33, tức VET `ACTIVE` bị `is_locked`). Mã hiện tại của `StaffProfileRepository.findActiveStaffRows` có `a.locked = false` và trùng HEAD (file được ghi lúc 01:38:47, ngay sau lần chạy đó), nên bản ghi này phù hợp với **một lần chạy đột biến** (bỏ `a.locked = false`) chứ không phải lỗi của mã hiện tại; đây là suy luận từ dấu thời gian và nội dung ca đỏ, **không xác nhận được**.
- **`OpenApiContractParityIT`:** nguồn đã đổi 180 → 181 (`hasSize(181)`, chú thích "+ 1 identity #38 (docs/adr/0089)"). Bản ghi failsafe 31/10 01:28 (11/11 xanh) có trước lần sửa cuối của `StaffController` (01:33) và `AssignableStaffService` (01:36); bản ghi mới nhất 1/11 10:39:50 cũng 11/11 xanh, sau mọi lần sửa mã.
- **Hợp đồng:** `docs/api/identity-v1.md` có hàng #38 và `openapi/identity-v1.yaml` có 38 `operationId` (đếm lúc viết ADR). `node docs/api/check-contracts.mjs` (1/11): `ALL CONTRACTS PASS — 12 file, 231 operations`.
- **Chạy lại 1/11 (lead tự đo, sau mọi lần sửa mã):** toàn bộ test đơn vị BE 5435 ca, 0 đỏ (lượt chạy đầu có 27 ca đỏ ở gói `platform/` do `NoClassDefFound` khi `target/classes` bị ghi đè đồng thời; chạy riêng 6 lớp đó 124 ca xanh, chạy lại toàn bộ sạch); nhóm IT identity cộng `OpenApiContractParityIT` và `SchemaMigrationIT` 783 ca, 0 đỏ; IT nhắm đích `AssignableStaffIT` 11, `StaffAdminIT` 52, `StaffDirectoryIT` 7. Trên BE thật (8082): lễ tân `GET /api/staff` → 403 `ACCESS_DENIED`, `/api/staff/assignable?role=VET` → 200 (4 khóa), VET / khách / ADMIN → 403, `GET /api/staff/abc` → 400 `MALFORMED_REQUEST`.

## Mutation

Quy trình: sửa bằng tay (công cụ Edit, không `git stash`) → chạy `mvn test -Dtest='AssignableStaffServiceTest,StaffControllerSecurityTest'` → ghi ca đỏ → hoàn tác → `grep` kiểm lại mã gốc. Ba đột biến đầu do lead tự chạy ngày 1/11 và đọc trực tiếp từ đầu ra Maven:

| # | Sửa | Ca đỏ | Trạng thái |
|---|---|---|---|
| 1 | bỏ guard `effectiveBranchId == null` ở `AssignableStaffService.listAssignable` (đổi thành `&& false`) | `AssignableStaffServiceTest.superManagerWithoutABranchGets400BeforeTheDirectoryIsCalled` (1 đỏ) | đã chạy, đã hoàn tác |
| 2 | bỏ `branchScope.resolve(branchId)` (dùng thẳng `branchId`) | `AssignableStaffServiceTest.branchScopedActorsAskingForAnotherBranchAreDeniedWithoutReadingAnything`, `aCustomerIsDeniedByTheScopeEvenIfTheRoleCheckWereMissing`, `mapsExactlyFourFieldsAndKeepsTheOrderOfTheDirectory`, `branchScopedActorsWithoutBranchIdListTheirOwnBranch` (4 đỏ) | đã chạy, đã hoàn tác |
| 3 | bỏ `@PreAuthorize(ASSIGNERS)` của `listAssignableStaff` | `StaffControllerSecurityTest.everyStaffHandlerCarriesExactlyItsContractRoles`, `onlyTheAssignableListIsOpenToTheReceptionist` (2 đỏ) | đã chạy, đã hoàn tác |
| 4 | bỏ `AND a.locked = false` ở `StaffProfileRepository.findActiveStaffRows` (câu query này `findActiveStaffIds` cũng dùng chung) | `AssignableStaffIT.listsOnlyActiveUnlockedStaffOfTheCallersBranchAndRoleOnlineFirstWithFourKeys` | **suy luận** từ bản ghi failsafe 31/10 01:38:34 (đã bị lần chạy 1/11 ghi đè); không xác nhận được đây là lần chạy đột biến |

Các đột biến còn lại: **chưa chạy**, chưa có ca đỏ nào để ghi:

- thêm `ADMIN` vào `ASSIGNERS`, hoặc bỏ `RECEPTIONIST` khỏi `ASSIGNERS` (kỳ vọng `AssignableStaffIT.adminVetCaretakerAndCustomerAreDeniedAndAnonymousIs401` và `StaffControllerSecurityTest` bắt);
- bỏ `@Positive` ở `branchId`;
- sắp lại danh sách trong `AssignableStaffService` (thay vì giữ thứ tự của `findAssignableStaff`);
- thêm một trường (ví dụ `branchId`) vào `AssignableStaffResponse`.

## Nợ để lại

- **`GET /api/staff` (ACTIVE) vẫn gồm người bị khóa (`is_locked`)**: bộ lọc `status = ACTIVE` của #15 không loại người bị ADMIN khóa. Chỉ ảnh hưởng endpoint cũ (FE cũ tự lọc theo `isLocked`); endpoint mới đã loại.
- **UC / Actor / BR của #38 do FE-1 chọn** (UC45 · BR-TN-05, 06; A04–A06) từ UC45 và `assignVisit` / `reassignCalledVisit`; cần BE-1 xác nhận, đặc biệt "SUPER_MANAGER phải gửi `branchId`" (400 `VALIDATION_FAILED`, không mã BR).
- ADR này **Proposed**: BE-1 chưa xem. Nếu BE-1 muốn SUPER_MANAGER không gửi `branchId` thì trả toàn chuỗi (cần đổi `StaffDirectoryApi`, vì `findAssignableStaff` hiện bắt buộc chi nhánh) hay lỗi khác, sửa `AssignableStaffService` và mô tả 400 trong hợp đồng.
- Không chặn người gán cho **lượt của chi nhánh khác**: endpoint chỉ trả danh sách theo chi nhánh người gọi / chi nhánh SUPER_MANAGER chọn; việc người được chọn có hợp lệ với lượt cụ thể (chi nhánh, chức vụ, ACTIVE) kiểm ở #5, #9.
