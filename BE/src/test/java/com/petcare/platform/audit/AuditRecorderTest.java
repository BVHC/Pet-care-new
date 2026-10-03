package com.petcare.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@ExtendWith(OutputCaptureExtension.class)
class AuditRecorderTest {

    record Snapshot(String status, Instant lockedUntil) {
    }

    record TestPrincipal(Long accountId, String email) implements AuditPrincipal {
    }

    private final AuditLogRepository repository = mock(AuditLogRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final AuditRecorder recorder = new AuditRecorder(repository,
            JsonMapper.builder().addModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build(),
            transactionManager);

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    // ---------------------------------------------------------------- V: validate

    @Test
    void savesAllFieldsOfValidEntry() {
        AuditLogEntity saved = recordAndCapture(AuditEntry.of("ACCOUNT_LOCKED")
                .entity("accounts", 7L)
                .before(new Snapshot("ACTIVE", null))
                .after(new Snapshot("ACTIVE", Instant.parse("2026-10-03T08:00:00Z")))
                .reason("spam feedback"));

        assertThat(saved.getAction()).isEqualTo("ACCOUNT_LOCKED");
        assertThat(saved.getEntityType()).isEqualTo("accounts");
        assertThat(saved.getEntityId()).isEqualTo(7L);
        assertThat(saved.getBeforeData().get("status").asText()).isEqualTo("ACTIVE");
        assertThat(saved.getAfterData().get("lockedUntil").asText()).isEqualTo("2026-10-03T08:00:00Z");
        assertThat(saved.getReason()).isEqualTo("spam feedback");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"account_locked", "ACCOUNT-LOCKED", "AB", "1ACTION", "ACTION LOCKED",
            "A123456789012345678901234567890123456789012345678901"})
    void rejectsInvalidAction(String action) {
        assertThatThrownBy(() -> recorder.record(AuditEntry.of(action)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action");
        verify(repository, never()).save(any());
    }

    @Test
    void acceptsActionOfMaxLength() {
        String action = "A" + "B".repeat(49);

        assertThat(recordAndCapture(AuditEntry.of(action)).getAction()).hasSize(50);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Accounts", "audit-logs", "a", "1orders",
            "a12345678901234567890123456789012345678901234567890"})
    void rejectsInvalidEntityType(String entityType) {
        assertThatThrownBy(() -> recorder.record(AuditEntry.of("PRICE_CHANGED").entity(entityType, 1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityType");
    }

    @Test
    void acceptsEntityTypeWithoutIdForStringKeyedTables() {
        AuditLogEntity saved = recordAndCapture(AuditEntry.of("CONFIG_CHANGED").entity("system_configs", null)
                .before(Map.of("key", "otp.ttl_minutes", "value", "5")));

        assertThat(saved.getEntityType()).isEqualTo("system_configs");
        assertThat(saved.getEntityId()).isNull();
    }

    @Test
    void rejectsEntityIdWithoutEntityType() {
        assertThatThrownBy(() -> recorder.record(AuditEntry.of("PRICE_CHANGED").entity(null, 1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityId requires entityType");
    }

    @Test
    void rejectsJpaEntityAsSnapshot() {
        AuditLogEntity entity = new AuditLogEntity(null, null, "ANY_ACTION", null, null, null, null, null, null);

        assertThatThrownBy(() -> recorder.record(AuditEntry.of("PRICE_CHANGED").before(entity)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a JPA entity");
        assertThatThrownBy(() -> recorder.record(AuditEntry.of("PRICE_CHANGED").after(List.of(entity))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a JPA entity");
        assertThatThrownBy(() -> recorder.record(AuditEntry.of("PRICE_CHANGED").after(Map.of("row", entity))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a JPA entity");
    }

    @Test
    void nullSnapshotsStayNull() {
        AuditLogEntity saved = recordAndCapture(AuditEntry.of("LOGIN_SUCCEEDED"));

        assertThat(saved.getBeforeData()).isNull();
        assertThat(saved.getAfterData()).isNull();
        assertThat(saved.getEntityType()).isNull();
        assertThat(saved.getReason()).isNull();
    }

    // ---------------------------------------------------------------- S: khóa nhạy cảm

    @Test
    void rejectsSensitiveKeysAnywhereInSnapshot() {
        List<Object> snapshots = List.of(
                Map.of("passwordHash", "x"),
                Map.of("account", Map.of("profile", Map.of("token_hash", "x"))),
                Map.of("checks", List.of(Map.of("cccdNumber", "0123"))),
                Map.of("OTPCode", "123456"));

        for (Object snapshot : snapshots) {
            assertThatThrownBy(() -> recorder.record(AuditEntry.of("ACCOUNT_UPDATED").after(snapshot)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sensitive key");
        }
        verify(repository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "passwordHash", "password_hash", "newPasswd", "otp", "otpCode", "OTPCode",
            "token", "tokenHash", "refresh_token", "clientSecret", "cccd", "cccdNumber", "pin", "PIN"})
    void detectsSensitiveKeyNames(String key) {
        assertThat(AuditRecorder.isSensitiveKey(key)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"spinach", "topping", "pinned", "hotpot", "status", "email", "phone", "lockedReason",
            "tokenizer", "verificationMethod"})
    void allowsHarmlessKeysContainingSensitiveSubstrings(String key) {
        assertThat(AuditRecorder.isSensitiveKey(key)).isFalse();
    }

    // ---------------------------------------------------------------- A: actor

    @Test
    void takesActorFromAuditPrincipal() {
        authenticate(new TestPrincipal(42L, "admin@petcare.vn"));

        AuditLogEntity saved = recordAndCapture(AuditEntry.of("ACCOUNT_LOCKED"));

        assertThat(saved.getActorAccountId()).isEqualTo(42L);
        assertThat(saved.getActorEmail()).isEqualTo("admin@petcare.vn");
    }

    @Test
    void systemActorWhenNoAuthenticationOrAnonymous() {
        AuditLogEntity withoutAuth = recordAndCapture(AuditEntry.of("SHIFT_AUTO_CLOSED"));
        assertThat(withoutAuth.getActorAccountId()).isNull();
        assertThat(withoutAuth.getActorEmail()).isNull();

        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        AuditLogEntity anonymous = recordAndCapture(AuditEntry.of("SHIFT_AUTO_CLOSED"));
        assertThat(anonymous.getActorAccountId()).isNull();
        assertThat(anonymous.getActorEmail()).isNull();
    }

    @Test
    void overrideActorWinsOverSecurityContext() {
        authenticate(new TestPrincipal(42L, "admin@petcare.vn"));

        AuditLogEntity saved = recordAndCapture(AuditEntry.of("LOGIN_FAILED").actor(null, "typed@example.com"));

        assertThat(saved.getActorAccountId()).isNull();
        assertThat(saved.getActorEmail()).isEqualTo("typed@example.com");
    }

    @Test
    void rejectsActorAccountWithoutEmail() {
        assertThatThrownBy(() -> recorder.record(AuditEntry.of("LOGIN_FAILED").actor(5L, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");

        authenticate(new TestPrincipal(5L, " "));
        assertThatThrownBy(() -> recorder.record(AuditEntry.of("ACCOUNT_LOCKED")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");
    }

    @Test
    void rejectsPrincipalNotImplementingAuditPrincipal() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("plain-user", null, List.of()));

        assertThatThrownBy(() -> recorder.record(AuditEntry.of("ACCOUNT_LOCKED")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AuditPrincipal");
    }

    @Test
    void truncatesOverlongOverrideEmail() {
        String email = "a".repeat(300) + "@example.com";

        AuditLogEntity saved = recordAndCapture(AuditEntry.of("LOGIN_FAILED").actor(null, email));

        assertThat(saved.getActorEmail()).hasSize(AuditRecorder.EMAIL_MAX_LENGTH);
    }

    // ---------------------------------------------------------------- I: IP

    @Test
    void takesIpFromCurrentRequest() {
        bindRequest("203.0.113.9");

        assertThat(recordAndCapture(AuditEntry.of("ORDER_PAID")).getIpAddress()).isEqualTo("203.0.113.9");
    }

    @Test
    void ipIsNullOutsideRequest() {
        assertThat(recordAndCapture(AuditEntry.of("SHIFT_AUTO_CLOSED")).getIpAddress()).isNull();
    }

    @Test
    void truncatesOverlongIp() {
        bindRequest("1".repeat(60));

        assertThat(recordAndCapture(AuditEntry.of("ORDER_PAID")).getIpAddress()).hasSize(AuditRecorder.IP_MAX_LENGTH);
    }

    // ---------------------------------------------------------------- R: ghi độc lập (REQUIRES_NEW)

    @Test
    void recordIndependentlySavesInItsOwnTransaction() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        recorder.recordIndependently(AuditEntry.of("LOGIN_FAILED").actor(null, "typed@example.com"));

        verify(repository).save(any(AuditLogEntity.class));
        verify(transactionManager).commit(any());
    }

    @Test
    void recordIndependentlyLogsAndSwallowsWriteFailure(CapturedOutput output) {
        when(transactionManager.getTransaction(any())).thenThrow(new CannotCreateTransactionException("db down"));

        assertThatCode(() -> recorder.recordIndependently(
                AuditEntry.of("LOGIN_FAILED").actor(null, "typed@example.com")))
                .doesNotThrowAnyException();

        assertThat(output).contains("AUDIT_WRITE_FAILED", "action=LOGIN_FAILED", "actorEmail=typed@example.com");
    }

    @Test
    void recordIndependentlyStillRejectsInvalidEntry() {
        assertThatThrownBy(() -> recorder.recordIndependently(AuditEntry.of("bad action")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(transactionManager, never()).getTransaction(any());
        verify(repository, never()).save(any());
    }

    // ----------------------------------------------------------------

    private AuditLogEntity recordAndCapture(AuditEntry entry) {
        recorder.record(entry);
        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private static void authenticate(AuditPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }

    private static void bindRequest(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
