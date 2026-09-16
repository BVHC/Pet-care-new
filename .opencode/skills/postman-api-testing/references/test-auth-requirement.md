# Test-Only OTP Requirement (ADR Draft)

Status: **proposed, not implemented.** This file documents the requirement, the investigation behind it, and the recommended design — written to the same shape as `docs/adr/000N-*.md` so it can be dropped in as `0004-test-only-otp-mechanism.md` once the team accepts it. No backend code has been changed to support this yet; Postman collections must work around the current limitation until it lands (see "Interim state" below).

## Requirement

> The backend must provide a test-only authentication mechanism for local/dev environments, so automated API tests can complete the OTP flow without depending on a real email inbox. This mechanism must never be reachable in production, under any configuration mistake.

## Why this exists

`register → verify-otp → login` is the entry point to every other authenticated flow in the API. If it can't run unattended, nothing downstream can either — no CI run, no scheduled regression, no "run the whole collection" button that actually completes.

## Investigation

Traced the full path, `AuthServiceImpl` → `NotificationService` → `EmailGateway`:

- `AuthServiceImpl.generateOtpCode()` (`module/auth/service/AuthServiceImpl.java:361`) is a **private method**, the single point where OTP values originate for both `registerAccount` and `resendOtp`. It uses `SecureRandom`, unconditionally.
- OTP rows live in the `otps` table, keyed by `(email, purpose)`; 300-second TTL, 5 wrong attempts locks the session for 15 minutes, resend has a 60-second cooldown and 5/hour cap. This logic is exactly what a real test should exercise — a workaround that skips it also skips its own test coverage.
- `EmailGateway` has exactly one implementation, `GmailSmtpEmailGateway` (`module/notification/gateway/GmailSmtpEmailGateway.java`), a bare `@Component` with **no `@Profile` restriction** — it's active in every profile, including `test`. It sends real SMTP mail.
- `NotificationServiceImpl.dispatch` (`module/notification/service/NotificationServiceImpl.java:47`) catches any SMTP failure, logs a `FAILED` delivery row, and returns normally — `register` still responds `201 Created` even when the mail never sent. So a Postman collection can't even detect a broken mail gateway from the API response; it just never receives an OTP.
- Spring profiles that exist today: default, `test` (`application-test.yml`, port 8081, disables cron jobs), `docker`. **There is no `local` or `dev` profile** — one of these needs to exist before a profile-gated OTP source has an obvious home.
- The codebase already uses `@ConditionalOnProperty(..., matchIfMissing = true)` as its idiom for toggleable behavior (`OtpExpiryJob`, `RefreshTokenCleanupJob`) — for this feature that default direction is deliberately **inverted** (see below).

## Recommended design: deterministic OTP via a bean seam

Extract OTP generation behind an interface instead of branching inside business logic:

```java
public interface OtpCodeGenerator {
    String generate();
}

@Component
@ConditionalOnMissingBean(name = "fixedOtpCodeGenerator")
public class SecureRandomOtpCodeGenerator implements OtpCodeGenerator {
    private final SecureRandom secureRandom = new SecureRandom();
    public String generate() {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }
}

@Component("fixedOtpCodeGenerator")
@Profile({"local", "dev"})
@ConditionalOnProperty(name = "app.auth.otp.fixed-code") // no matchIfMissing — see below
public class FixedOtpCodeGenerator implements OtpCodeGenerator {
    private final String fixedCode;
    public FixedOtpCodeGenerator(@Value("${app.auth.otp.fixed-code}") String fixedCode) {
        this.fixedCode = fixedCode;
    }
    public String generate() { return fixedCode; }
}
```

`AuthServiceImpl` calls `otpCodeGenerator.generate()` instead of its current private method — every other line of `registerAccount`, `verifyOtp`, `resendOtp` stays untouched. The full real code path still runs: OTP is still generated, stored, checked for expiry, counted for wrong attempts, locked after 5 tries. Only the *source* of the value is swapped, so a "wrong OTP" negative test still works — send anything other than the fixed code.

### Three layers of protection

1. **Fail-closed by omission, not by matching a default.** `app.auth.otp.fixed-code` has no `matchIfMissing = true` — the property must be explicitly set for `FixedOtpCodeGenerator` to activate at all. This is a deliberate departure from the `matchIfMissing = true` pattern already used elsewhere in this codebase (`OtpExpiryJob`, `RefreshTokenCleanupJob`) — those toggle *scheduled jobs* where "on by default" is safe; this toggles *authentication material* where "on by default" is not. Document that divergence in the ADR so a future reviewer doesn't flip it to match the other two out of consistency instinct.
2. **Boot-time assertion, not just profile annotation.** A `@PostConstruct` or `ApplicationListener<ApplicationReadyEvent>` that checks: if `app.auth.otp.fixed-code` is set AND the active profile is not `local`/`dev`, throw and refuse to start. A `@Profile` annotation alone only stops the bean from being *created* under the wrong profile — it does nothing to catch a misconfigured profile list on a production deploy where someone copy-pasted a `dev` properties file. The assertion makes that scenario a boot failure, not a silent security hole.
3. **WARN-level log line on startup** whenever `FixedOtpCodeGenerator` is active, naming the fixed code's presence (not its value) — so it's visible in any log aggregation a team member might be watching, not just discoverable by reading config.

## Why not the alternatives

| Option | Why it's weaker |
|---|---|
| Dev-only endpoint `GET /api/dev/otp?email=` | Adds a new API surface whose entire purpose is reading OTP codes back out. If the profile guard on *that* fails, it's a direct account-takeover primitive — an attacker reads anyone's OTP by email. Worse failure mode than a fixed value that's already known and public in this project's own docs. |
| `LogEmailGateway` (log OTP instead of emailing it) | Doesn't satisfy the requirement on its own — Newman/CI can't read application logs. Worth adding *separately* as a dev convenience (so a human running the app locally isn't spamming a real Gmail account), but it's a different problem from automated test flow. |
| Seed an already-`ACTIVE` account via Flyway | Skips the OTP flow rather than testing it — `verify-otp`'s guards (wrong code, lockout, expiry, resend cooldown) get zero coverage. Still worth having as a fast-path fixture for the 24 other modules that just need *a* logged-in user and don't care about auth mechanics, but it doesn't replace this requirement. |

Deterministic OTP is the same approach Firebase Auth (test phone numbers) and Twilio (test credentials) use for exactly this problem: keep the real code path, replace only the external, non-deterministic input.

## Interim state — until this lands

Until `OtpCodeGenerator` exists, the Postman collection **cannot** run `Register → Verify OTP → Login` unattended. Options while waiting:

- Keep one manually-verified test account per environment (created once by a human who actually received the real OTP), and point the auth bootstrap script (`references/pm-scripts.md`) at its `email`/`password` via collection variables. This covers every *other* module's need for "a logged-in user" — it does not exercise the OTP flow itself.
- Test `Register`, `Resend OTP`, and OTP *failure* cases (wrong code, lockout) directly — none of those require reading a real inbox, only sending requests and checking the resulting `errorCode`/state. Only the successful `Verify OTP` step is blocked.
- Do not hardcode a real, monitored inbox's credentials into any shared collection or environment as a workaround — that reintroduces the exact secret-handling problem this skill's Security section exists to prevent.
