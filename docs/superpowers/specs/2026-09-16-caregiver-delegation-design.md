# Caregiver Delegation (M04) — Design Spec

> **Ngày:** 2026-09-16 · **Module:** 04 Customer & Pet · **FSM:** FSM-3 (`CaregiverStatus`)
> **Rule:** RULE-04-04 → RULE-04-09 · **Contract:** `docs/api/customer-pet-v1.md` §C3
> **Trạng thái:** Đã duyệt thiết kế, chờ lập implementation plan.

---

## 1. Mục tiêu

Cho phép Primary Owner của một Pet chia sẻ quyền chăm sóc con vật đó cho một người
khác (Caregiver) trong một khoảng thời gian, và thu hồi lại được **tức thì**.

Quyền này có vòng đời riêng — được mời, chờ đồng ý, có thể bị từ chối, tự hết hạn
nếu không ai phản hồi, bị thu hồi bất cứ lúc nào — nên được hiện thực bằng FSM chứ
không phải một cờ boolean trên `pets`.

### 1.1. Trong phạm vi

1. Vòng đời ủy quyền: mời, chấp nhận, từ chối, thu hồi, hết hạn tự động.
2. Tác vụ nền quét hết hạn (lời mời quá TTL, ủy quyền quá `validUntil`).
3. **Quyền đọc Pet cho Caregiver `ACTIVE`** — phần khiến trạng thái `ACTIVE` có
   tác dụng quan sát được từ bên ngoài. Không có phần này thì `ACTIVE` và `REVOKED`
   không khác gì nhau và tính năng không kiểm chứng được.
4. Sửa tài liệu đặc tả kéo theo (mục 11).

### 1.2. Ngoài phạm vi

| Hạng mục | Lý do |
|---|---|
| Caregiver đặt lịch hẹn, check-in, xem hồ sơ y tế (RULE-04-09) | Module Appointment (M06) và Clinical (M09) chưa tồn tại |
| `POST /pets/{id}/transfer` và RULE-04-10 (đổi chủ thì tự thu hồi caregiver cũ) | Transfer chưa triển khai, luật chưa có chỗ bám |
| Caregiver sửa field phi-core của Pet | Contract mới PROPOSED; `PATCH /pets/{id}` giữ owner-only (xem D-06) |
| Tìm kiếm Pet cho Receptionist | Thuộc §C2 của contract, không liên quan |
| Sửa `UserPrincipal` / JWT / `accounts` để mang email | Thuộc auth domain, chủ dự án quyết định chưa đụng tới lúc này |

---

## 2. Nguồn chân lý

| Nội dung | Nguồn |
|---|---|
| Business rules | `docs/02-business-rules.md` RULE-04-04 → RULE-04-09 |
| FSM (bảng transition + mermaid) | `docs/03-state-machines.md` §3 |
| Aggregate boundary | `docs/05-domain-model.md` — `PetCaregiverDelegation` là **child entity của aggregate Pet** |
| Endpoint + guard | `docs/api/customer-pet-v1.md` §C3 + `docs/api/openapi/customer-pet-v1.yaml` |
| Schema hiện có | `BE/src/main/resources/db/migration/V1__init_schema.sql` (`pet_caregiver_delegations`) |
| Convention code | `docs/convention/backend/` (01→09) |

---

## 3. Quyết định thiết kế

### D-01 — Định danh lời mời bằng **email**, không phải phone

ERD quy định `caregiver_phone NOT NULL` và contract A3 yêu cầu "đăng nhập đúng SĐT
được mời". Cả hai viết **trước** 2026-09-13, thời điểm RULE-01-10 đổi danh tính đăng
nhập cấp Platform từ `phone` sang `email`. Hiện `accounts.phone` là nullable và chỉ
còn là liên hệ tùy chọn, `users` không có cột phone — nên guard "đúng người được mời"
theo SĐT không enforce được.

**Quyết định:** thêm `caregiver_email NOT NULL`, hạ `caregiver_phone` xuống nullable.

**Hệ quả:** lệch `docs/06-erd.md` §3.2. Đây là lệch **có chủ ý và đã ghi nhận**; sửa
ERD thuộc đợt rà soát ERD sau, không nằm trong task này. Không sửa gì trong auth domain.

### D-02 — Gửi lời mời: email nếu đã có tài khoản, trả token nếu chưa

`notification_tasks.recipient_user_id` là `NOT NULL REFERENCES users(id)`, không ghi
được task cho người chưa tồn tại trong hệ thống, trong khi contract cho phép mời
người chưa có tài khoản (`caregiver_user_id` NULL).

**Quyết định:**

- Email đã có account → `NotificationService.enqueue()` + `dispatch()`, đúng luồng
  OTP đăng ký đang chạy. Response **không** chứa token.
- Chưa có account → response `201` trả raw token **đúng một lần**, Primary Owner tự
  chuyển cho người được mời.

Không sửa schema của module Notification (M23) — ngoài phạm vi task.

### D-03 — `validUntil` tùy chọn, không có giá trị mặc định

RULE-04-07 yêu cầu ủy quyền có thời hạn, nhưng `DelegationValidityPeriod` chưa được
PO chốt độ dài (Q5 của contract). Bịa một con số — kể cả giấu trong `application.yml`
— vi phạm nguyên tắc "không phát minh" của `docs/api/00-method.md` §1.3.

**Quyết định:** `validUntil` là tham số tùy chọn khi mời. `NULL` = ủy quyền chạy tới
khi bị thu hồi. Job chỉ xử lý những bản ghi có `validUntil` khác NULL.

### D-04 — Thêm cạnh `INVITED → REVOKED` vào FSM-3

FSM-3 nguyên bản không có cách nào để Primary Owner hủy một lời mời đang treo — phải
đợi hết 7 ngày. Đây là lỗ hổng nghiệp vụ thật.

**Quyết định (chủ dự án duyệt 2026-09-16):** thêm cạnh `INVITED → REVOKED`, **dùng lại
Command `RevokeCaregiver`**. Không thêm giá trị enum thứ 6, không thêm Command mới,
không thêm endpoint.

**Đây là thay đổi đặc tả**, không chỉ là code: `docs/03-state-machines.md` và
`docs/02-business-rules.md` phải được sửa cùng lúc (mục 11). `05-fsm-pattern.md` cấm
tự suy diễn thêm cạnh, nên cạnh này chỉ hợp lệ sau khi đặc tả được cập nhật.

**Đánh đổi đã chấp nhận:** báo cáo sau này không phân biệt được "bị hủy khi chưa kịp
trả lời" với "bị thu hồi sau khi đã nhận" — cả hai đều là `REVOKED`.

### D-05 — Thu hồi định danh bằng `caregiverEmail`, không phải `caregiverUserId`

Hệ quả trực tiếp của D-04: lời mời đang treo có thể có `caregiver_user_id = NULL`,
nên `RevokeCaregiverRequest{caregiverUserId}` như contract viết không địa chỉ hóa được
nó. `caregiver_email` thì `NOT NULL`, và unique index từng phần (mục 5) bảo đảm mỗi
cặp `(pet_id, caregiver_email)` chỉ có đúng một bản ghi `INVITED`/`ACTIVE`.

**Quyết định:** `RevokeCaregiverRequest{caregiverEmail}`, phủ cả `INVITED` và `ACTIVE`.

### D-06 — `PATCH /pets/{id}` giữ nguyên owner-only

RULE-04-04 cấm Caregiver sửa thông tin định danh cốt lõi. Contract có gợi ý cho
Caregiver sửa field phi-core, nhưng đó mới là PROPOSED.

**Quyết định:** không mở `PATCH` cho Caregiver. Là lựa chọn an toàn và ít phát minh
nhất; mở rộng sau nếu PO xác nhận.

### D-07 — Lưu SHA-256 của token, không lưu raw

Theo precedent `platform/security/token/RefreshTokenService` (SHA-256 hex, có ghi rõ
lý do: DB lộ thì không tự tạo lại được token hợp lệ). Đổi tên cột
`invitation_token` → `invitation_token_hash` để tên cột không nói dối về nội dung.

### D-08 — Guard tự kiểm thời hạn, không tin vào cron

Job chạy theo lịch; giữa hai lần chạy tồn tại khoảng mà `status` vẫn `ACTIVE` nhưng
`valid_until` đã trôi qua. Nếu guard chỉ đọc `status`, Caregiver vẫn vào được hồ sơ
sau khi hết hạn cho đến tick kế tiếp — một lỗ hổng bảo mật.

**Quyết định:** điều kiện "ủy quyền còn hiệu lực" luôn là
`status = ACTIVE AND (valid_until IS NULL OR valid_until > now)`, đánh giá tại thời
điểm request. Job chỉ là dọn dẹp dữ liệu, không phải cơ chế bảo vệ. Tương tự,
`accept` tự kiểm `expires_at > now` thay vì tin rằng job đã quét.

### D-09 — Tổ chức code: tất cả trong `module/pet/`, tách riêng `PetAccessGuard`

`docs/05-domain-model.md` chốt `PetCaregiverDelegation` là child entity của aggregate
Pet, cùng bounded context M04 — nên **không** tạo `module/caregiver/` riêng (sẽ sinh
phụ thuộc vòng: caregiver cần Pet để xác minh Primary Owner, pet cần caregiver để
guard quyền đọc).

Luật "ai đọc được pet nào" tách thành một component riêng vì nó tồn tại ở **hai** dạng:
một guard trong `detail()`, một điều kiện JPQL trong query của `list()`. Để cạnh nhau
trong một unit thì sửa một chỗ khó quên chỗ kia.

### D-10 — Idempotency: lặp lại thao tác đã thành công thì trả 200, không ném 409

Contract ghi accept/reject/revoke là idempotent ("đã ACTIVE vẫn 200"), nhưng FSM-3 không
có cạnh `ACTIVE → ACTIVE`, nên gọi thẳng `validateTransition()` sẽ ném
`InvalidStateTransitionException` 409 và mâu thuẫn với contract.

**Quyết định:** kiểm tra idempotency **trước** khi gọi `validateTransition()`. Nếu bản
ghi đã ở đúng trạng thái đích **và do đúng chủ thể đó gây ra**, trả `200` với trạng thái
hiện tại, **không** transition và **không** ghi event lần hai:

| Thao tác | Idempotent khi | Kết quả |
|---|---|---|
| accept | `status = ACTIVE` và `caregiverUserId = principal.userId` | `200`, không event |
| reject | `status = REJECTED` và `caregiverUserId = principal.userId` | `200`, không event |
| revoke | `status = REVOKED` và người gọi là Primary Owner | `200`, không event |

Mọi tổ hợp còn lại (trạng thái cuối khác với đích, hoặc đúng trạng thái nhưng sai chủ
thể) vẫn đi qua `validateTransition()` và ném `409`/`403` như mục 9. Cách này giữ được
tính idempotent mà **không** phải thêm cạnh tự-lặp vào FSM.

---

## 4. Assumptions & câu hỏi

### Assumptions

| # | Nội dung |
|---|---|
| A-01 | Mời bằng email (D-01). Lệch ERD §3.2 có chủ ý; đề xuất sửa ERD ở đợt rà soát sau. |
| A-02 | Khi người được mời **chưa có tài khoản**, raw token được trả cho Primary Owner và họ tự chuyển đi. Nhánh này là token-bearer: ai cầm token và đang đăng nhập thì nhận được ủy quyền. Chấp nhận được vì token đi qua tay chính chủ nuôi, người vốn đã có toàn quyền trên Pet đó. |
| A-03 | Khi người được mời **đã có tài khoản**, `caregiver_user_id` được gán ngay lúc mời, và `accept` bắt buộc `principal.userId` khớp. Không cần đọc email từ `UserPrincipal` (vốn không có trường email). |
| A-04 | TTL lời mời = 7 ngày, lấy từ RULE-04-05 (CONFIRMED). Cấu hình qua `application.yml` để test rút ngắn được. |

### Câu hỏi còn mở (không chặn task này)

| # | Câu hỏi | Ai trả lời |
|---|---|---|
| Q-01 | `DelegationValidityPeriod` mặc định bao lâu? (Q5 của contract — hiện né bằng D-03) | PO |
| Q-02 | Có cần phân biệt "hủy lời mời" với "thu hồi ủy quyền" trong báo cáo không? Nếu có thì D-04 phải nâng lên trạng thái `CANCELLED` riêng. | PO |
| Q-03 | Sau khi `REJECTED`/`REVOKED`, Primary Owner mời lại cùng email có bị chặn không? (Hiện **không** — unique index chỉ phủ `INVITED`/`ACTIVE`.) | PO |

---

## 5. Data model & migration V5

Bảng `pet_caregiver_delegations` đã tồn tại từ `V1__init_schema.sql` nhưng **chưa từng
có code nào ghi vào** (rỗng trên mọi môi trường, nên đổi cột an toàn), và thiếu toàn
bộ cột audit của `platform.model.BaseEntity` — kể cả `created_at`.

`V5__caregiver_delegations.sql`:

```sql
ALTER TABLE pet_caregiver_delegations
    ADD COLUMN caregiver_email VARCHAR(100) NOT NULL,
    ALTER COLUMN caregiver_phone DROP NOT NULL,
    ADD COLUMN valid_until TIMESTAMPTZ,
    ADD COLUMN created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN created_by  UUID REFERENCES accounts(id),
    ADD COLUMN updated_by  UUID REFERENCES accounts(id),
    ADD COLUMN deleted_at  TIMESTAMPTZ,
    ADD COLUMN version     BIGINT NOT NULL DEFAULT 0;

ALTER TABLE pet_caregiver_delegations
    RENAME COLUMN invitation_token TO invitation_token_hash;

-- Chặn mời trùng ở tầng DB (không check-then-insert ở service, tránh hở race).
CREATE UNIQUE INDEX uq_pcd_outstanding
    ON pet_caregiver_delegations (pet_id, caregiver_email)
    WHERE status IN ('INVITED', 'ACTIVE');

-- Phục vụ job quét hết hạn (lối V3__refresh_token_cleanup_index.sql).
CREATE INDEX idx_pcd_invitation_expiry
    ON pet_caregiver_delegations (expires_at) WHERE status = 'INVITED';
CREATE INDEX idx_pcd_delegation_expiry
    ON pet_caregiver_delegations (valid_until) WHERE status = 'ACTIVE';

-- Phục vụ guard đọc Pet + query GET /pets.
CREATE INDEX idx_pcd_caregiver
    ON pet_caregiver_delegations (caregiver_user_id, status);
```

**Không thêm** `accepted_at`/`revoked_at`: `updated_at` đã ghi nhận thời điểm đổi
trạng thái cuối, và mỗi delegation chỉ rời `INVITED`/`ACTIVE` đúng một lần.

### Entity

`PetCaregiverDelegation extends BaseEntity` (id UUID, created/updated, deleted_at,
`@Version`) với các trường: `petId`, `primaryOwnerId`, `caregiverUserId` (nullable),
`caregiverEmail`, `caregiverPhone` (nullable), `invitationTokenHash`, `status`
(`@Enumerated(STRING)`), `expiresAt`, `validUntil` (nullable).

---

## 6. FSM-3

```
                 ┌──→ REJECTED     (RejectCaregiverInvitation, Caregiver)
                 │
[*] ──→ INVITED ─┼──→ EXPIRED      (ProcessInvitationExpiry, System — quá 7 ngày)
                 │
                 ├──→ REVOKED      (RevokeCaregiver, Primary Owner — D-04, cạnh MỚI)
                 │
                 └──→ ACTIVE ─┬──→ REVOKED   (RevokeCaregiver, hiệu lực tức thì)
                              │
                              └──→ EXPIRED   (ProcessDelegationExpiry, System)
```

Transition map (`CaregiverDelegationTransitionHandler extends StateMachineBase<CaregiverStatus>`):

```
INVITED → { ACTIVE, REJECTED, EXPIRED, REVOKED }
ACTIVE  → { REVOKED, EXPIRED }
```

`REJECTED`, `EXPIRED`, `REVOKED` là trạng thái cuối, không có cạnh ra.

Handler là **transition map thuần**, không chứa method transition `@Transactional` —
theo precedent `module/auth/fsm/AccountTransitionHandler` trong code, không theo ví dụ
minh họa ở `05-fsm-pattern.md`. Service gọi `validateTransition(from, to)` rồi mới
`setStatus()`.

Chấp nhận lời mời là `ACTIVE` **ngay**, không có bước duyệt trung gian (ghi chú dưới
sơ đồ FSM-3). Không thêm trạng thái chờ duyệt nào.

---

## 7. Components

Tất cả trong `BE/src/main/java/com/petcare/module/pet/`:

| File | Trạng thái | Vai trò |
|---|---|---|
| `entity/PetCaregiverDelegation.java` | mới | Child entity của aggregate Pet |
| `repository/PetCaregiverDelegationRepository.java` | mới | Truy vấn theo token hash, theo (pet, email), quét hết hạn |
| `fsm/CaregiverDelegationTransitionHandler.java` | mới | Transition map FSM-3 |
| `service/PetAccessGuard.java` | mới | `requireCanViewPet()`, `requirePrimaryOwner()` — nơi duy nhất biết luật phân quyền Pet |
| `service/CaregiverDelegationService.java` + `Impl` | mới | 4 command nghiệp vụ |
| `service/CaregiverInvitationOutcome.java` | mới | Record nội bộ (không phải DTO API), khuôn `RegistrationOutcome` |
| `controller/CaregiverDelegationController.java` | mới | 4 endpoint |
| `mapper/CaregiverDelegationMapper.java` | mới | MapStruct, `componentModel = "spring"` |
| `dto/InviteCaregiverRequest.java` | mới | `caregiverEmail` (@NotBlank @Email), `caregiverPhone?`, `validUntil?` (@Future) |
| `dto/CaregiverInvitationResponse.java` | mới | `id`, `petId`, `status`, `expiresAt`, `invitationToken?` (chỉ khi D-02 nhánh 2) |
| `dto/RevokeCaregiverRequest.java` | mới | `caregiverEmail` (@NotBlank @Email) — D-05 |
| `dto/CaregiverDelegationResponse.java` | mới | `id`, `petId`, `caregiverUserId`, `status`, `expiresAt`, `validUntil` |
| `job/CaregiverExpiryJob.java` + `CaregiverExpiryService.java` | mới | Khuôn `auth/job/OtpExpiryJob` |
| `exception/UnauthorizedDelegatedActionException.java` | mới | extends `AccessDeniedScopeException` |
| `exception/CaregiverInvitationConflictException.java` | mới | extends `BusinessRuleViolationException` |
| `service/PetServiceImpl.java` | **sửa** | `detail()` và `list()` dùng `PetAccessGuard` |
| `repository/PetRepository.java` | **sửa** | thêm `findAccessibleBy(userId, now, pageable)` |

Ngoài module:

| File | Trạng thái | Vai trò |
|---|---|---|
| `module/auth/service/AuthService.java` + `Impl` | **sửa** | thêm `Optional<UUID> findUserIdByActiveAccountEmail(String email)` — thuần đọc |
| `platform/exception/GlobalExceptionHandler.java` | **sửa** | 2 handler mới, đặt **trước** handler của class cha |
| `BE/src/main/resources/application.yml` + `application-test.yml` | **sửa** | `app.caregiver-expiry.{enabled,cron}`, `app.caregiver.invitation-ttl-days: 7` |

### Phụ thuộc cross-module

Chỉ **một**: `AuthService.findUserIdByActiveAccountEmail()`. Cần vì D-02 phải trả lời
được "email này đã có account chưa", mà email nằm ở `accounts` (auth module) và
`01-package-structure.md` cấm pet import repository của module khác. Method thuần đọc,
không đổi hành vi nào đang chạy, không đụng `UserPrincipal` hay JWT.

---

## 8. Luồng xử lý

### 8.1. `POST /api/pets/{id}/caregiver-invitations` — InviteCaregiver

1. `PetAccessGuard.requirePrimaryOwner(principal.userId, pet)` — RULE-04-04.
2. Sinh raw token bằng `SecureRandom` (precedent `AuthServiceImpl`), lưu SHA-256 hex.
3. `expiresAt = now + 7 ngày` (RULE-04-05).
4. `authService.findUserIdByActiveAccountEmail(req.caregiverEmail())`:
   - có → gán `caregiverUserId`, `notificationService.enqueue(...)`, response **không** trả token.
   - không → `caregiverUserId = NULL`, response trả raw token một lần.
5. Ghi `OutboxEvent` `CaregiverInvited`.
6. Trả `CaregiverInvitationOutcome(response, notificationTaskId, rawToken)`.
7. **Controller** gọi `notificationService.dispatch(taskId, email)` **sau khi
   transaction commit** — đúng khuôn `AuthController` + `RegistrationOutcome`, vì lý do
   đã ghi trong javadoc `NotificationService`: lỗi SMTP tạm thời không được rollback
   việc tạo delegation.

Mời trùng bị chặn ở tầng DB bởi `uq_pcd_outstanding`; bắt `DataIntegrityViolationException`
→ `CaregiverInvitationConflictException`, đúng lối `PetServiceImpl.create()`.

**Response `201`:** `CaregiverInvitationResponse`. Đây là thao tác **không** idempotent —
mỗi lần gọi là một lời mời mới, và lời mời thứ hai cho cùng cặp (pet, email) bị chặn
bằng `409` chứ không trả về lời mời cũ.

### 8.2. `POST /api/caregiver-invitations/{token}/accept` — AcceptCaregiverInvitation

1. Tra theo **hash** của token → không có thì `404` (không lộ token có hợp lệ hay không).
2. Kiểm `expires_at > now`, không thì `400` RULE-04-05 (D-08 — không tin cron).
3. Định danh:
   - `caregiverUserId != NULL` → bắt buộc `principal.userId` khớp, lệch thì `403`.
   - `caregiverUserId == NULL` → gán `= principal.userId` (A-02).
4. Kiểm idempotency theo D-10 → nếu trúng thì trả `200` và dừng tại đây.
5. `validateTransition(INVITED, ACTIVE)` → `setStatus(ACTIVE)`.
6. Outbox `CaregiverInvitationAccepted`.

**Response `200`:** `CaregiverDelegationResponse`.

### 8.3. `POST /api/caregiver-invitations/{token}/reject` — RejectCaregiverInvitation

Như 8.2 bước 1–4, rồi `INVITED → REJECTED`, outbox `CaregiverInvitationRejected`.

**Response `200`:** `CaregiverDelegationResponse`.

### 8.4. `POST /api/pets/{id}/caregiver-revoke` — RevokeCaregiver

1. `requirePrimaryOwner` — RULE-04-04.
2. Tìm bản ghi `INVITED`/`ACTIVE` theo `(petId, caregiverEmail)` — D-05. Không có thì
   tra tiếp bản ghi `REVOKED` gần nhất của cặp đó để phục vụ D-10; vẫn không có thì `404`.
3. Kiểm idempotency theo D-10 → nếu trúng thì trả `200` và dừng tại đây.
4. `validateTransition(current, REVOKED)` → `setStatus(REVOKED)`.
5. Outbox `CaregiverRevoked`.

**Response `200`:** `CaregiverDelegationResponse`.

Hiệu lực tức thì (RULE-04-08) là hệ quả tự nhiên của D-08: guard đọc trạng thái trực
tiếp từ DB mỗi request, không có cache nào phải invalidate.

### 8.5. Tác vụ nền

`CaregiverExpiryJob` (`@Scheduled` + `@ConditionalOnProperty`, tắt được trong
`application-test.yml` như `app.otp-expiry.enabled: false`), không distributed lock vì
hệ thống chạy một instance — sao y `OtpExpiryJob`. Hai lệnh quét:

- `INVITED` có `expires_at <= now` → `EXPIRED`, outbox `CaregiverInvitationExpired`.
- `ACTIVE` có `valid_until IS NOT NULL AND valid_until <= now` → `EXPIRED`, outbox
  `CaregiverDelegationExpired`.

### 8.6. Quyền đọc Pet

`PetAccessGuard` là nơi duy nhất định nghĩa "ủy quyền còn hiệu lực":

```
status = ACTIVE AND (valid_until IS NULL OR valid_until > now)
```

- `PetServiceImpl.detail()` — thay so sánh `ownerId` inline hiện tại bằng
  `requireCanViewPet()`.
- `PetServiceImpl.list()` — dùng `PetRepository.findAccessibleBy(userId, now, pageable)`:
  pet mình sở hữu **hoặc** pet có delegation còn hiệu lực trỏ về mình (JPQL `exists`
  subquery, cùng điều kiện trên).
- `PetServiceImpl.create()` / `update()` — **không đổi**, giữ owner-only (D-06).

---

## 9. Catalog lỗi

| Tình huống | Exception | errorCode | HTTP |
|---|---|---|---|
| Không phải Primary Owner gọi invite/revoke | `UnauthorizedDelegatedActionException` | `UNAUTHORIZED_DELEGATED_ACTION` | 403 |
| Không phải owner cũng không phải caregiver `ACTIVE` đọc Pet | `UnauthorizedDelegatedActionException` | `UNAUTHORIZED_DELEGATED_ACTION` | 403 |
| Accept nhưng không phải người được mời | `UnauthorizedDelegatedActionException` | `UNAUTHORIZED_DELEGATED_ACTION` | 403 |
| Pet / token không tồn tại | `ResourceNotFoundException` | `RESOURCE_NOT_FOUND` | 404 |
| Token quá `expires_at` | `BusinessRuleViolationException("RULE-04-05")` | `BUSINESS_RULE_VIOLATION` | 400 |
| Accept/reject/revoke khi đã ở trạng thái cuối | `InvalidStateTransitionException` | `INVALID_STATE_TRANSITION` | 409 |
| Đã có `INVITED`/`ACTIVE` cho cặp (pet, email) | `CaregiverInvitationConflictException` | `CAREGIVER_INVITATION_CONFLICT` | 409 |
| Optimistic lock lúc flush | `ConcurrencyConflictException` | `CONCURRENCY_CONFLICT` | 409 |
| `validUntil` trong quá khứ | Bean Validation `@Future` | `VALIDATION_FAILED` | 400 |

Hai exception riêng của module, lý do theo `04-exception-handling.md` §4.2 (bắt buộc
ghi trong comment class + PR description):

- `UnauthorizedDelegatedActionException extends AccessDeniedScopeException` — **tiêu
  chí 2**: cùng HTTP 403 nhưng khác `errorCode`, và contract chốt CONFIRMED mã này cho
  RULE-04-09. Vì là subclass nên `PetServiceImplTest` và `PetControllerTest` (đang
  khẳng định `isInstanceOf(AccessDeniedScopeException)`) vẫn xanh.
- `CaregiverInvitationConflictException extends BusinessRuleViolationException` —
  **tiêu chí 2**: cần HTTP **409** thay vì 400 mặc định. Contract ghi 409 và
  `00-method.md` §1.4 chốt "conflict → 409".

Cả hai đăng ký handler đặt **trước** handler của class cha trong `GlobalExceptionHandler`,
đúng khuôn 4 exception auth hiện có.

---

## 10. Events (Transactional Outbox)

Sáu event theo `docs/05-domain-model.md`, ghi cùng transaction với thay đổi trạng thái:

`CaregiverInvited` · `CaregiverInvitationAccepted` · `CaregiverInvitationRejected` ·
`CaregiverInvitationExpired` · `CaregiverRevoked` · `CaregiverDelegationExpired`

`aggregateType = "Pet"`, `aggregateId = petId` — delegation là child entity nên
aggregate root vẫn là Pet, khớp cách `PetServiceImpl.create()` đang ghi.

---

## 11. Sửa tài liệu đặc tả

Hệ quả bắt buộc của D-01 và D-04. **Phải làm cùng đợt**, không để nợ — nếu không,
code sẽ mâu thuẫn với nguồn chân lý và lần sau đọc doc sẽ ra kết luận sai.

| File | Sửa gì |
|---|---|
| `docs/03-state-machines.md` §3 | Thêm cạnh `INVITED → REVOKED` vào mermaid + một dòng vào bảng transition (actor: Customer/Primary Owner; guard: RULE-04-04, RULE-04-08; event: `CaregiverRevoked`) |
| `docs/02-business-rules.md` | RULE-04-08 mở rộng: thu hồi áp dụng cho cả lời mời đang treo (`INVITED`), không chỉ ủy quyền `ACTIVE` |
| `docs/api/customer-pet-v1.md` | Bỏ ghi chú "C3 OUT v1"; revoke định danh bằng email và phủ `INVITED`; ghi rõ ngữ nghĩa idempotent theo D-10 (hiện chỉ nói "Idempotent (đã ACTIVE vẫn 200)" mà không nêu điều kiện chủ thể); đóng Q5 (theo D-03) và Q12 (theo D-02); ghi assumption mới về email (D-01) |
| `docs/api/openapi/customer-pet-v1.yaml` | Đồng bộ 4 path + schema, rồi chạy `node docs/api/check-contracts.mjs` và dán output làm bằng chứng (V1 của `00-method.md`) |
| `docs/architecture/system-overview.md` §7 | Làm mới — đang ghi `module/` rỗng và migration dừng ở V3, đã lệch từ trước task này |

Không sửa `docs/06-erd.md` trong task này: lệch cột `caregiver_email`/`caregiver_phone`
được ghi nhận ở A-01 và để đợt rà soát ERD xử lý cùng các bảng khác.

---

## 12. Kiểm thử

### Unit (`09-testing.md` bắt buộc cho mọi service/handler có guard)

| Test | Nội dung |
|---|---|
| `CaregiverDelegationTransitionHandlerTest` | extends `FsmTransitionTestBase<CaregiverStatus>`, liệt kê **đủ 25 cặp** (5 trạng thái × 5): 6 hợp lệ, 19 không hợp lệ. Convention cấm bỏ sót cặp nào. |
| `PetAccessGuardTest` | owner qua; `ACTIVE` qua; **`ACTIVE` nhưng `validUntil` đã qua → 403** (ca cửa sổ cron, D-08); `INVITED`/`REJECTED`/`EXPIRED`/`REVOKED` → 403; người lạ → 403; `requirePrimaryOwner` với caregiver `ACTIVE` → 403 |
| `CaregiverDelegationServiceImplTest` | mời khi đã có account (gọi `enqueue`, response không có token) / chưa có account (không gọi `enqueue`, response có token); mời trùng → 409; accept sai người → 403; accept quá hạn → 400; accept trạng thái cuối khác `ACTIVE` → 409; reject; revoke khi `ACTIVE`; **revoke khi `INVITED`** (D-04); revoke bởi người không phải owner → 403 |
| `CaregiverDelegationServiceImplTest` — idempotency (D-10) | accept lần hai bởi **đúng** caregiver → `200`, **không** ghi outbox lần hai; accept bởi caregiver **khác** khi đã `ACTIVE` → 403; revoke lần hai bởi owner → `200`, không ghi outbox lần hai |
| `CaregiverExpiryServiceTest` | `INVITED` quá hạn → `EXPIRED`; `ACTIVE` quá `validUntil` → `EXPIRED`; **`ACTIVE` có `validUntil` NULL → không đụng tới** (D-03) |

### Integration (Testcontainers, Postgres thật, `EmailGateway` mock bằng `@MockitoBean`)

| Test | Nội dung |
|---|---|
| `CaregiverFlowIT` | A tạo pet → mời B → B accept → **B `GET /pets/{id}` trả 200** → B thấy pet trong `GET /pets` → A revoke → **B trả 403 `UNAUTHORIZED_DELEGATED_ACTION`**. Nhánh hai: mời → A hủy lúc còn `INVITED` (D-04) → B accept trả **409 `INVALID_STATE_TRANSITION`**. |
| `PetRepositoryTest` (sửa) | Thêm ca cho `findAccessibleBy` — JPQL có subquery `exists` và điều kiện `validUntil`, phải chạy trên Postgres thật mới tin được |
| `PetFlowIT` (xác nhận) | Test cũ vẫn xanh sau khi `detail()`/`list()` đổi guard |

Hai bất biến xuyên suốt bộ test, cũng là hai thứ mà nếu sai thì sai về mặt bảo mật:
**thu hồi có hiệu lực tức thì** (RULE-04-08) và **hết hạn không phụ thuộc cron** (D-08).

---

## 13. Tiêu chí hoàn thành

1. `mvn verify` xanh (Surefire + Failsafe), có dán output.
2. `node docs/api/check-contracts.mjs` pass cho `customer-pet-v1.yaml`, có dán output.
3. Bảng transition trong code khớp **chính xác** mermaid FSM-3 **sau khi đã cập nhật** —
   không thừa, không thiếu cạnh nào.
4. Năm file tài liệu ở mục 11 đã sửa xong trong cùng đợt.
5. Mọi exception riêng của module đều ghi rõ tiêu chí §4.2 trong comment class.
