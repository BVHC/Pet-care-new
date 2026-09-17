package com.petcare.platform.audit;

import com.petcare.platform.enums.SecurityScope;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kiểm chứng {@link AuditAspect} thật sự chạy qua AOP (không gọi trực tiếp method Java) —
 * dùng {@link AspectJProxyFactory} với {@code setProxyTargetClass(true)} để khớp đúng
 * default {@code spring.aop.proxy-target-class=true} của Spring Boot (CGLIB, giữ nguyên
 * annotation tham số trên method của impl — {@link AuditResourceId}/{@link AuditDetail}
 * đặt trên tham số IMPL, không phải interface).
 */
@ExtendWith(MockitoExtension.class)
class AuditAspectTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    interface Dummy {
        String doAction(UUID resourceId, String reason);

        CreatedThing create(String name);

        CreatedThingWithParentScope createWithParentScope(String name);

        TransitionResult transition(UUID resourceId, UUID organizationId, UUID storeId);

        ScopedResult assignLikeAction(UUID resourceId, UUID resultOrganizationId, UUID resultStoreId);
    }

    record CreatedThing(UUID id) {
    }

    // Mô phỏng StoreResourceResponse: response trả về có CẢ resourceId (chính resource được tạo)
    // LẪN storeId (Store cha) — accessor "resourceId" phải được ưu tiên hơn "storeId", nếu không
    // audit_logs.resource_id sẽ ghi nhầm ID của Store cha thay vì ID resource thật sự bị tác động.
    record CreatedThingWithParentScope(UUID resourceId, UUID storeId) {
    }

    // organizationId/storeId KHÔNG đánh dấu @AuditOrganizationId/@AuditStoreId ở TransitionResult
    // trong DummyImpl.transition — mô phỏng AccountSummary vẫn dùng tham số đánh dấu (ưu tiên 1),
    // dù result CŨNG có 2 field cùng tên (khác giá trị) để test độ ưu tiên tham số > result.
    record TransitionResult(String previousStatus, UUID organizationId, UUID storeId) {
    }

    // Mô phỏng RoleAssignmentResponse/OrganizationResponse: method @Auditable KHÔNG đánh dấu
    // @AuditOrganizationId/@AuditStoreId trên tham số nào, nhưng kiểu trả về có sẵn 2 accessor
    // này — AuditAspect phải tự dò ra (ưu tiên 2), không cần sửa method nghiệp vụ.
    record ScopedResult(UUID organizationId, UUID storeId) {
    }

    static class DummyImpl implements Dummy {
        @Override
        @Auditable(action = "DoAction", resourceType = "Thing")
        public String doAction(@AuditResourceId UUID resourceId, @AuditDetail("reason") String reason) {
            return "ok";
        }

        @Override
        @Auditable(action = "CreateThing", resourceType = "Thing")
        public CreatedThing create(String name) {
            return new CreatedThing(UUID.randomUUID());
        }

        @Override
        @Auditable(action = "CreateThingWithParentScope", resourceType = "Thing")
        public CreatedThingWithParentScope createWithParentScope(String name) {
            return new CreatedThingWithParentScope(UUID.randomUUID(), UUID.randomUUID());
        }

        @Override
        @Auditable(action = "TransitionThing", resourceType = "Thing")
        public TransitionResult transition(@AuditResourceId UUID resourceId,
                                            @AuditOrganizationId UUID organizationId, @AuditStoreId UUID storeId) {
            // organizationId/storeId của RESULT cố tình khác tham số đánh dấu — xem test
            // organizationIdMarkedParameter_takesPriorityOverResultAccessor.
            return new TransitionResult("OLD_STATE", UUID.randomUUID(), UUID.randomUUID());
        }

        @Override
        @Auditable(action = "AssignLikeAction", resourceType = "Thing")
        public ScopedResult assignLikeAction(@AuditResourceId UUID resourceId,
                                              UUID resultOrganizationId, UUID resultStoreId) {
            return new ScopedResult(resultOrganizationId, resultStoreId);
        }
    }

    private Dummy proxy(AuditAspect aspect) {
        AspectJProxyFactory factory = new AspectJProxyFactory(new DummyImpl());
        factory.setProxyTargetClass(true);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    private void authenticateAs(UUID userId, UUID accountId, UUID organizationId) {
        UserPrincipal principal = UserPrincipal.builder()
                .userId(userId).accountId(accountId).organizationId(organizationId)
                .role(UserRole.SUPER_ADMIN).scope(SecurityScope.PLATFORM).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void resourceIdFromMarkedParameter_andDetailFromAuditDetail_arePersisted() {
        UUID actorId = UUID.randomUUID();
        UUID actorAccountId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        authenticateAs(actorId, actorAccountId, orgId);
        UUID targetId = UUID.randomUUID();

        Dummy dummy = proxy(new AuditAspect(auditLogRepository));
        String result = dummy.doAction(targetId, "vi pham chinh sach");

        assertThat(result).isEqualTo("ok");
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getAction()).isEqualTo("DoAction");
        assertThat(saved.getResourceType()).isEqualTo("Thing");
        assertThat(saved.getResourceId()).isEqualTo(targetId.toString());
        assertThat(saved.getAccountId()).isEqualTo(actorAccountId);
        assertThat(saved.getOrganizationId()).isEqualTo(orgId);
        assertThat(saved.getSnapshotAfter()).contains("\"reason\":\"vi pham chinh sach\"");
    }

    @Test
    void resourceIdFallsBackToReturnValueAccessor_whenNoParameterMarked() {
        authenticateAs(UUID.randomUUID(), UUID.randomUUID(), null);
        Dummy dummy = proxy(new AuditAspect(auditLogRepository));

        CreatedThing created = dummy.create("Ten moi");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getResourceId()).isEqualTo(created.id().toString());
        // Không có tham số nào đánh dấu @AuditDetail -> snapshot rỗng, không dump tham số thô.
        assertThat(captor.getValue().getSnapshotAfter()).isNull();
    }

    @Test
    void resourceIdAccessorOnResult_isPreferredOverStoreIdAccessor_whenNoParameterMarked() {
        authenticateAs(UUID.randomUUID(), UUID.randomUUID(), null);
        Dummy dummy = proxy(new AuditAspect(auditLogRepository));

        CreatedThingWithParentScope created = dummy.createWithParentScope("Ten moi");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getResourceId()).isEqualTo(created.resourceId().toString());
        assertThat(captor.getValue().getResourceId()).isNotEqualTo(created.storeId().toString());
    }

    @Test
    void organizationIdMarkedParameter_takesPriorityOverResultAccessor() {
        // Bug đã sửa (RULE-25-01/07): actor mô phỏng SUPER_ADMIN (organizationId=null) thao tác
        // trên resource thuộc 1 Organization cụ thể — audit_logs phải ghi org của RESOURCE, không
        // phải NULL của actor, nếu không Organization Admin tra Audit Log theo scope sẽ không thấy.
        // TransitionResult (xem DummyImpl.transition) trả về organizationId/storeId RIÊNG, khác
        // targetOrgId/targetStoreId — khẳng định tham số đánh dấu (ưu tiên 1) thắng accessor trên
        // result (ưu tiên 2), không bị lẫn.
        authenticateAs(UUID.randomUUID(), UUID.randomUUID(), null);
        UUID targetOrgId = UUID.randomUUID();
        UUID targetStoreId = UUID.randomUUID();

        Dummy dummy = proxy(new AuditAspect(auditLogRepository));
        dummy.transition(UUID.randomUUID(), targetOrgId, targetStoreId);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getOrganizationId()).isEqualTo(targetOrgId);
        assertThat(captor.getValue().getStoreId()).isEqualTo(targetStoreId);
    }

    @Test
    void organizationIdAccessorOnResult_isUsedWhenNoMarkedParameter() {
        // Mô phỏng UserManagementServiceImpl.assignRole/OrganizationServiceImpl.updateOrganization:
        // KHÔNG đánh dấu @AuditOrganizationId/@AuditStoreId trên tham số nào, nhưng response trả
        // về (RoleAssignmentResponse/OrganizationResponse) đã có sẵn accessor organizationId()/
        // storeId() — AuditAspect phải tự dò ra qua ScopedResult, ghi đè scope actor (SUPER_ADMIN
        // organizationId=null).
        authenticateAs(UUID.randomUUID(), UUID.randomUUID(), null);
        UUID resultOrgId = UUID.randomUUID();
        UUID resultStoreId = UUID.randomUUID();

        Dummy dummy = proxy(new AuditAspect(auditLogRepository));
        dummy.assignLikeAction(UUID.randomUUID(), resultOrgId, resultStoreId);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getOrganizationId()).isEqualTo(resultOrgId);
        assertThat(captor.getValue().getStoreId()).isEqualTo(resultStoreId);
    }

    @Test
    void noOrganizationIdSourceAtAll_fallsBackToActorOrganizationId() {
        // Method không đánh dấu @AuditOrganizationId/@AuditStoreId VÀ kiểu trả về cũng không có
        // accessor phù hợp (vd doAction trả về String) — hành vi cũ giữ nguyên, dùng scope actor.
        UUID actorOrgId = UUID.randomUUID();
        authenticateAs(UUID.randomUUID(), UUID.randomUUID(), actorOrgId);

        Dummy dummy = proxy(new AuditAspect(auditLogRepository));
        dummy.doAction(UUID.randomUUID(), "ly do");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getOrganizationId()).isEqualTo(actorOrgId);
    }

    @Test
    void previousStatusAccessorOnResult_populatesSnapshotBefore() {
        authenticateAs(UUID.randomUUID(), UUID.randomUUID(), null);

        Dummy dummy = proxy(new AuditAspect(auditLogRepository));
        dummy.transition(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getSnapshotBefore()).contains("\"status\":\"OLD_STATE\"");
    }

    @Test
    void resultWithoutPreviousStatusAccessor_leavesSnapshotBeforeNull() {
        authenticateAs(UUID.randomUUID(), UUID.randomUUID(), null);

        Dummy dummy = proxy(new AuditAspect(auditLogRepository));
        dummy.doAction(UUID.randomUUID(), "ly do");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getSnapshotBefore()).isNull();
    }

    @Test
    void requestContext_populatesClientIpAndUserAgent() {
        authenticateAs(UUID.randomUUID(), UUID.randomUUID(), null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("User-Agent", "JUnitAgent/1.0");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Dummy dummy = proxy(new AuditAspect(auditLogRepository));
        dummy.doAction(UUID.randomUUID(), "ly do");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getClientIp()).isEqualTo("203.0.113.7");
        assertThat(captor.getValue().getUserAgent()).isEqualTo("JUnitAgent/1.0");
    }

    @Test
    void noRequestContext_leavesClientIpAndUserAgentNull() {
        // Action chạy ngoài 1 HTTP request (vd test, tác vụ nền) — không có RequestContextHolder,
        // không được ném lỗi.
        authenticateAs(UUID.randomUUID(), UUID.randomUUID(), null);

        Dummy dummy = proxy(new AuditAspect(auditLogRepository));
        dummy.doAction(UUID.randomUUID(), "ly do");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getClientIp()).isNull();
        assertThat(captor.getValue().getUserAgent()).isNull();
    }

    @Test
    void persistenceFailure_doesNotFailBusinessCall() {
        authenticateAs(UUID.randomUUID(), UUID.randomUUID(), null);
        doThrow(new RuntimeException("DB hiccup")).when(auditLogRepository).save(any());
        Dummy dummy = proxy(new AuditAspect(auditLogRepository));

        // Không ném lỗi ra ngoài dù ghi audit_logs thất bại — cùng triết lý fail-open đã
        // áp dụng cho TokenBlacklistService.
        String result = dummy.doAction(UUID.randomUUID(), "ly do");

        assertThat(result).isEqualTo("ok");
    }
}
