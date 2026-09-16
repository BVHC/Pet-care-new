# Collection Structure

Folder numbering matches the 25-module list in `docs/INDEX.md`, so anyone who knows the docs can find the matching folder without a lookup table. Keep the numbers even for modules that currently have no controller — an empty numbered folder is an honest signal of backlog; renumbering later to close gaps just churns everyone's local collection state.

## Folder skeleton

```
Pet Care API
├── 01 - Auth                    ← 6 requests exist
├── 02 - IAM                     ← placeholder, no controller yet
├── 03 - Org & Store             ← placeholder
├── 04 - Customer & Pet          ← 4 requests exist (Pet only; Customer/CaregiverInvitation not built)
├── 05 - Catalog                 ← placeholder
├── 06 - Appointment              ← placeholder
├── 07 - Queue                    ← placeholder
├── 08 - Workforce                ← placeholder
├── 09 - Clinical                 ← placeholder
├── 10 - Vaccination              ← placeholder
├── 11 - Grooming                 ← placeholder
├── 12 - Inventory                ← placeholder
├── 13 - Procurement              ← placeholder
├── 14 - Order                    ← placeholder
├── 15 - Invoice                  ← placeholder
├── 16 - Payment                  ← placeholder
├── 17 - Refund                   ← placeholder
├── 18 - Promotion                ← placeholder
├── 19 - Membership                ← placeholder
├── 20 - Package                  ← placeholder
├── 21 - Incident                 ← placeholder
├── 22 - Consent                  ← placeholder
├── 23 - Notification             ← placeholder
├── 24 - Report                   ← placeholder
├── 25 - Audit                    ← placeholder
└── _Cleanup                      ← not a numbered module; run LAST, always

A "placeholder" folder holds either nothing yet, or a single disabled/skipped request documenting the target endpoint from the design-time yaml — never a request expected to pass today.

**`Logout` and `Logout — 401 No Token` live in `_Cleanup`, not `01 - Auth`, and `_Cleanup` is positioned last in the collection — deliberately.** Logout blacklists the access token and revokes the refresh token server-side (RULE-01-06). If it sat inside `01 - Auth` (which runs before `04 - Customer & Pet`), every authenticated request after it in a full top-to-bottom run would receive a dead token: `Logout`'s own Tests script clears `{{access_token}}`/`{{refresh_token}}` after confirming `revoked: true`, but the collection-level pre-request bootstrap's "is the token still good" check only decodes the JWT's `exp` claim — it has no way to detect server-side revocation, and its own attempt to silently re-authenticate via nested `pm.sendRequest` calls in a pre-request script is not reliable across Postman clients (confirmed in practice — don't re-add reliance on it). Keeping the destructive action last avoids needing that self-healing path at all. If you add a new folder, insert it **before** `_Cleanup`, never after.

## What's real today (10 requests)

### `01 - Auth` — no auth required, none of this folder's requests need a token

| Request | Method | Path | Auth |
|---|---|---|---|
| Register | POST | `/api/auth/register` | None |
| Verify OTP | POST | `/api/auth/verify-otp` | None |
| Resend OTP | POST | `/api/auth/otp/resend` | None |
| Login | POST | `/api/auth/login` | None |
| Refresh Token | POST | `/api/auth/refresh` | None |

### `_Cleanup` — requires Bearer, run last

| Request | Method | Path | Auth |
|---|---|---|---|
| Logout | POST | `/api/auth/logout` | Bearer (required) |

### `04 - Customer & Pet` — all require Bearer

| Request | Method | Path | Notes |
|---|---|---|---|
| Create Pet | POST | `/api/pets` | Owner = caller (`me.getUserId()`) |
| List Pets | GET | `/api/pets` | Paginated (`page`, `size`, `sort`) |
| Get Pet | GET | `/api/pets/{id}` | 404 if not found or not owned |
| Update Pet | PATCH | `/api/pets/{id}` | Partial update |

No `DELETE /api/pets/{id}` exists. Don't add a request for it and mark it "pending" — there's no endpoint to call yet; note it in the module backlog instead.

## Case naming convention

```
<Action>                                  — happy path, no suffix
<Action> — <status> <ERROR_CODE>          — negative case, name matches references/error-catalog.md exactly
```

Examples actually applicable to the 10 real endpoints:

```
Register
Register — 400 VALIDATION_FAILED           (missing email)
Register — 400 BUSINESS_RULE_VIOLATION     (duplicate email, RULE-01-10)

Login
Login — 401 INVALID_CREDENTIALS
Login — 423 ACCOUNT_LOCKED
Login — 403 ACCOUNT_NOT_ACTIVE             (before verify-otp)

Verify OTP
Verify OTP — 400 BUSINESS_RULE_VIOLATION   (wrong code, RULE-01-02)
Verify OTP — 400 BUSINESS_RULE_VIOLATION   (locked after 5 attempts, RULE-01-05)

Create Pet
Create Pet — 400 VALIDATION_FAILED         (missing name/species)
Create Pet — 401 TOKEN_EXPIRED
Create Pet — 401 (no token at all)

Get Pet
Get Pet — 404 RESOURCE_NOT_FOUND

Update Pet
Update Pet — 400 VALIDATION_FAILED
Update Pet — 404 RESOURCE_NOT_FOUND
```

Two `BUSINESS_RULE_VIOLATION` cases with the same status+code but different scenarios (as with Register and Verify OTP above) still need distinct request names — append the distinguishing detail in parentheses, or split into `— duplicate email` / `— wrong OTP` suffixes if the codebase has more than one such collision per endpoint.

## Variable inventory

| Variable | Scope | Set by | Read by |
|---|---|---|---|
| `base_url` | Environment | manual, per environment | every request URL |
| `env_type` | Environment | manual, per environment | production safety guard |
| `email`, `password` | Collection variables | manual (seed test account) | auth bootstrap pre-request script |
| `access_token`, `refresh_token` | Collection variables | auth bootstrap script, Login/Refresh Tests tab | collection-level Bearer auth |
| `gen_email` | Collection variables | pre-request script on Register-style requests | request body, for uniqueness |
| `pet_id` | Collection variables | Create Pet Tests tab | Get/Update Pet requests within the same run |

Anything holding a secret (real Gmail app password, admin password for a shared account) belongs in Postman Vault, never in the table above.

## Pagination

`GET /api/pets` accepts Spring's standard `Pageable` binding:

```
GET {{base_url}}/api/pets?page=0&size=20&sort=createdAt,desc
```

Response nests under `data` as `PageResponse<PetResponse>`: `content`, `page`, `size`, `totalElements`, `totalPages`, `first`, `last`.
