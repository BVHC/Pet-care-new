# Postman Scripts

Copy-paste snippets bound to this project's actual envelope and auth shape. Section 1 (auth bootstrap) and section 2 (production guard) go on the collection's own Pre-request tab (right-click collection → Edit) — they're self-contained and don't need anything from a request's own script.

**Section 3 (`assertSuccess`/`assertError`) is different: paste it into the Tests tab of *every request that uses it*, not just the collection.** A collection-level Tests script defining these functions does **not** reliably share that scope with a request's own Tests script — confirmed in practice (`ReferenceError: assertSuccess is not defined` when a request's Tests script called a function only defined at the collection level, in an actual Postman client). Don't assume your client is the exception; duplicate the helper block. Only the collection-wide "never a 5xx" check (end of section 3) is safe to keep collection-level, since it doesn't call either helper.

## 1. Collection Pre-request: Auth Bootstrap

Handles token refresh and login fallback so any folder can run standalone, even after the 15-minute access token TTL has passed. Requires `email` / `password` collection variables pointing at a known-good test account (or generated during a Register+VerifyOtp setup folder — see `test-auth-requirement.md` for why that's not automatable yet without a backend change).

```javascript
// Collection > Pre-request Script

function base64UrlDecode(str) {
    str = str.replace(/-/g, "+").replace(/_/g, "/");
    while (str.length % 4) str += "=";
    return JSON.parse(atob(str));
}

function decodeJwt(token) {
    try {
        const payload = token.split(".")[1];
        return base64UrlDecode(payload);
    } catch (e) {
        return null;
    }
}

function isExpiringSoon(token, skewSeconds) {
    const claims = decodeJwt(token);
    if (!claims || !claims.exp) return true;
    const nowSeconds = Math.floor(Date.now() / 1000);
    return claims.exp - nowSeconds < skewSeconds;
}

const accessToken = pm.collectionVariables.get("access_token");
const refreshToken = pm.collectionVariables.get("refresh_token");

if (!accessToken || isExpiringSoon(accessToken, 120)) {
    if (refreshToken) {
        pm.sendRequest({
            url: pm.environment.get("base_url") + "/api/auth/refresh",
            method: "POST",
            header: { "Content-Type": "application/json" },
            body: { mode: "raw", raw: JSON.stringify({ refreshToken }) }
        }, (err, res) => {
            if (!err && res.code === 200) {
                const data = res.json().data;
                pm.collectionVariables.set("access_token", data.accessToken);
                pm.collectionVariables.set("refresh_token", data.refreshToken);
                return;
            }
            loginFallback();
        });
    } else {
        loginFallback();
    }
}

function loginFallback() {
    pm.sendRequest({
        url: pm.environment.get("base_url") + "/api/auth/login",
        method: "POST",
        header: { "Content-Type": "application/json" },
        body: { mode: "raw", raw: JSON.stringify({
            email: pm.collectionVariables.get("email"),
            password: pm.collectionVariables.get("password")
        }) }
    }, (err, res) => {
        if (!err && res.code === 200) {
            const data = res.json().data;
            pm.collectionVariables.set("access_token", data.accessToken);
            pm.collectionVariables.set("refresh_token", data.refreshToken);
        } else {
            console.error("Auth bootstrap failed:", err || res.json());
        }
    });
}
```

## 2. Production Safety Guard

Also on the collection Pre-request tab, after the auth bootstrap above.

```javascript
const destructivePattern = /delete|reset|bulk|force|purge/i;
const envType = pm.environment.get("env_type");

if (envType === "production" && destructivePattern.test(pm.info.requestName)) {
    throw new Error(
        `Blocked: request "${pm.info.requestName}" looks destructive and env_type is "production". ` +
        `If this is intentional, rename the request to avoid the pattern only after team sign-off.`
    );
}
```

## 3. Assertion Helpers (Tests tab)

```javascript
// Success envelope: { data, message, code }
function assertSuccess(expectedStatus) {
    pm.test(`Status is ${expectedStatus}`, () => {
        pm.response.to.have.status(expectedStatus);
    });
    let body;
    pm.test("Response is valid JSON", () => {
        body = pm.response.json();
    });
    pm.test("Envelope has data field", () => {
        pm.expect(body).to.have.property("data");
    });
    return body ? body.data : undefined;
}

// Error envelope: { success:false, errorCode, message, statusCode, timestamp, traceId }
function assertError(expectedStatus, expectedErrorCode) {
    pm.test(`Status is ${expectedStatus}`, () => {
        pm.response.to.have.status(expectedStatus);
    });
    const body = pm.response.json();
    pm.test("Error envelope has all 6 required fields", () => {
        pm.expect(body).to.include.keys(
            "success", "errorCode", "message", "statusCode", "timestamp", "traceId"
        );
        pm.expect(body.success).to.equal(false);
    });
    pm.test(`errorCode is ${expectedErrorCode}`, () => {
        pm.expect(body.errorCode).to.equal(expectedErrorCode);
    });
}

// Collection-wide guard — paste once at the collection level Tests tab
// so it runs after every single request, regardless of expected outcome.
pm.test("Never a 5xx", () => {
    pm.expect(pm.response.code).to.be.below(500);
});
```

Usage in a request's Tests tab — **guard the variable-set lines**, don't call them unconditionally:

```javascript
// Login — happy path
const data = assertSuccess(200);
pm.test("Has access and refresh tokens", () => {
    if (!data) {
        // assertSuccess already recorded why (wrong status / no data field) — this
        // throw just stops us reading .accessToken off undefined below.
        throw new Error("No 'data' in response — check email/password are set to an ACTIVE account.");
    }
    pm.expect(data.accessToken).to.be.a("string").and.not.empty;
    pm.expect(data.refreshToken).to.be.a("string").and.not.empty;
});
if (data && data.accessToken) {
    pm.collectionVariables.set("access_token", data.accessToken);
    pm.collectionVariables.set("refresh_token", data.refreshToken);
}
```

**Why the guard matters**: `assertSuccess` returns `undefined` when the request didn't actually succeed (wrong status, or a well-formed error envelope with no `data` field) — it doesn't throw, by design, so the rest of the test script can still run and report clean `pm.test` failures instead of an aborted script. But `pm.collectionVariables.set("access_token", data.accessToken)` sits *outside* any `pm.test(...)` wrapper. If `data` is `undefined` (e.g. Login was sent with empty `{{email}}`/`{{password}}` and the API correctly returned `400 VALIDATION_FAILED`), that line throws an uncaught `TypeError` mid-script — no token gets saved, and depending on the client this can look like nothing happened at all rather than a clear "Login failed" signal. Wrap the read in a `pm.test` (so it reports as a real failure) and guard the `.set(...)` calls (so a failed login can never throw past them) on every request that extracts tokens from a response — this applies equally to `Refresh Token`.

```javascript
// Login — 423 ACCOUNT_LOCKED
assertError(423, "ACCOUNT_LOCKED");
```

## 4. Unique Test Data

```javascript
// Pre-request script on a Register-style request, to guarantee a fresh email every run
pm.collectionVariables.set("gen_email", `pet-care-test+${Date.now()}@example.com`);
```

```
// Request body
{
  "email": "{{gen_email}}",
  "phone": "0900000000",
  "password": "TestPass123!",
  "name": "QA Bot"
}
```

Prefer Postman's built-in dynamic variables where they fit: `{{$randomEmail}}`, `{{$guid}}`, `{{$timestamp}}`. Use an explicit `Date.now()`-suffixed value (as above) when the format must stay human-recognizable, e.g. to find test accounts later for cleanup.

## 5. Pagination Query

```
GET {{base_url}}/api/pets?page=0&size=20&sort=createdAt,desc
```

```javascript
const data = assertSuccess(200);
pm.test("Page envelope shape", () => {
    pm.expect(data).to.include.keys(
        "content", "page", "size", "totalElements", "totalPages", "first", "last"
    );
});
```
