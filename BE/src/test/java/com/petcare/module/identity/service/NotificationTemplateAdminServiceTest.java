package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.NotificationTemplateResponse;
import com.petcare.module.identity.dto.UpdateTemplateRequest;
import com.petcare.module.identity.entity.NotificationTemplate;
import com.petcare.module.identity.mapper.NotificationTemplateAdminMapper;
import com.petcare.module.identity.repository.NotificationTemplateRepository;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

/**
 * UC10 — sửa / khôi phục mẫu thông báo (identity-v1 #33–35; BR-QT-14, 15; docs/adr/0037). Thứ tự: khóa (404) → quét
 * biến (400 BR-QT-14) → không đổi thì chỉ audit → ghi → flush → audit. Mọi nhánh lỗi không ghi, không audit.
 */
class NotificationTemplateAdminServiceTest {

    private static final long ADMIN = 7L;
    private static final String CODE = "OTP_REGISTER";
    private static final String DEFAULT_SUBJECT = "Mã xác thực";
    private static final String DEFAULT_BODY = "Chào {ten_khach}, mã {ma_otp} còn {thoi_han_phut} phút";

    private final NotificationTemplateRepository templates = mock(NotificationTemplateRepository.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final BranchScope branchScope = mock(BranchScope.class);
    private final NotificationTemplateAdminMapper mapper = Mappers.getMapper(NotificationTemplateAdminMapper.class);
    private final NotificationTemplateAdminService service = new NotificationTemplateAdminService(templates, mapper,
            audit, branchScope);
    private NotificationTemplate row;

    @BeforeEach
    void setUp() {
        when(branchScope.current()).thenReturn(new AccountPrincipal(ADMIN, "admin@petcare.test", 42L, Role.ADMIN,
                null, false));
        row = template(CODE, "EMAIL", DEFAULT_SUBJECT, DEFAULT_BODY, List.of("ten_khach", "ma_otp", "thoi_han_phut"),
                List.of("ma_otp"));
        when(templates.findByCodeForUpdate(CODE)).thenReturn(Optional.of(row));
        when(templates.findByCodeForUpdate("MISSING")).thenReturn(Optional.empty());
    }

    // ================================================================ #33

    @Test
    void listReadsEveryRowSortedByCode() {
        NotificationTemplate other = template("A_FIRST_APP", "IN_APP", "Tiêu đề", "Nội dung", List.of(), List.of());
        when(templates.findAll(Sort.by("code"))).thenReturn(List.of(other, row));

        assertThat(service.listTemplates()).extracting(NotificationTemplateResponse::code)
                .containsExactly("A_FIRST_APP", CODE);
        verify(templates).findAll(Sort.by("code"));
    }

    // ================================================================ #34 thành công

    @Test
    void updateLocksChecksWritesFlushesThenAudits() {
        NotificationTemplateResponse response = service.updateTemplate(CODE,
                new UpdateTemplateRequest("Mã mới", "Mã của bạn: {ma_otp}"));

        assertThat(response).isEqualTo(new NotificationTemplateResponse(CODE, Channel.EMAIL, "Mã mới",
                "Mã của bạn: {ma_otp}", List.of("ten_khach", "ma_otp", "thoi_han_phut"), List.of("ma_otp"), false));
        assertThat(row.getSubject()).isEqualTo("Mã mới");
        assertThat(row.getBody()).isEqualTo("Mã của bạn: {ma_otp}");
        assertThat(row.getUpdatedBy()).isEqualTo(ADMIN);
        InOrder order = inOrder(templates, audit);
        order.verify(templates).findByCodeForUpdate(CODE);
        order.verify(templates).flush();
        AuditEntry entry = captureAudit(order);
        order.verifyNoMoreInteractions();
        assertThat(entry.action()).isEqualTo("NOTIFICATION_TEMPLATE_UPDATED");
        assertThat(entry.entityType()).isEqualTo("notification_templates");
        assertThat(entry.entityId()).isNull();
        assertThat(entry.before()).isEqualTo(snapshot(CODE, DEFAULT_SUBJECT, DEFAULT_BODY));
        assertThat(entry.after()).isEqualTo(snapshot(CODE, "Mã mới", "Mã của bạn: {ma_otp}"));
    }

    @Test
    void contentIsStoredVerbatim() {
        service.updateTemplate(CODE, new UpdateTemplateRequest("  Tiêu đề  ", "\r\n Mã {ma_otp}\r\n"));

        assertThat(row.getSubject()).isEqualTo("  Tiêu đề  ");
        assertThat(row.getBody()).isEqualTo("\r\n Mã {ma_otp}\r\n");
    }

    @Test
    void requiredVariableMayAppearOnlyInTheSubject() {
        service.updateTemplate(CODE, new UpdateTemplateRequest("Mã {ma_otp}", "Không có biến"));

        assertThat(row.getBody()).isEqualTo("Không có biến");
    }

    @Test
    void sameContentIsNotWrittenButStillAuditedWithBeforeEqualAfter() {
        NotificationTemplateResponse response = service.updateTemplate(CODE,
                new UpdateTemplateRequest(DEFAULT_SUBJECT, DEFAULT_BODY));

        assertThat(response.isDefault()).isTrue();
        assertThat(row.getUpdatedBy()).isNull();
        verify(templates, never()).flush();
        AuditEntry entry = captureAudit(inOrder(audit));
        assertThat(entry.action()).isEqualTo("NOTIFICATION_TEMPLATE_UPDATED");
        assertThat(entry.before()).isEqualTo(entry.after())
                .isEqualTo(snapshot(CODE, DEFAULT_SUBJECT, DEFAULT_BODY));
    }

    @Test
    void contentDifferingOnlyByWhitespaceIsAChange() {
        service.updateTemplate(CODE, new UpdateTemplateRequest(DEFAULT_SUBJECT + " ", DEFAULT_BODY));

        verify(templates).flush();
        assertThat(row.isDefault()).isFalse();
    }

    // ================================================================ #34 lỗi

    @Test
    void unknownCodeIs404AndWritesNothing() {
        assertThatThrownBy(() -> service.updateTemplate("MISSING", new UpdateTemplateRequest("A", "B {ma_otp}")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy mẫu thông báo #MISSING");

        verifyNothingWritten();
    }

    @Test
    void unknownVariablesAreAllNamedPerPart() {
        assertRule(new UpdateTemplateRequest("Tiêu đề {abc}", "Mã {ma_otp} {Ma_OTP} {xyz} {abc} {Ma_OTP}"),
                "Tiêu đề dùng biến không có: {abc}; Nội dung dùng biến không có: {Ma_OTP}, {xyz}, {abc}; Biến dùng "
                        + "được: {ten_khach}, {ma_otp}, {thoi_han_phut} (BR-QT-14)");
    }

    @Test
    void moreThanTenUnknownVariablesAreAllNamed() {
        StringBuilder body = new StringBuilder("{ma_otp}");
        for (int i = 1; i <= 12; i++) {
            body.append(" {v").append(i).append('}');
        }

        BusinessRuleViolationException error = assertRuleThrown(new UpdateTemplateRequest("T", body.toString()));
        for (int i = 1; i <= 12; i++) {
            assertThat(error.getMessage()).contains("{v" + i + "}");
        }
    }

    @Test
    void strayBraceIsReported() {
        assertRule(new UpdateTemplateRequest("Tiêu đề }", "Mã {{ma_otp}}"),
                "Tiêu đề có dấu { hoặc } lẻ — dấu ngoặc nhọn chỉ dùng để viết biến dạng {ten_bien}; Nội dung có dấu "
                        + "{ hoặc } lẻ — dấu ngoặc nhọn chỉ dùng để viết biến dạng {ten_bien} (BR-QT-14)");
    }

    @Test
    void missingRequiredVariableIsNamed() {
        assertRule(new UpdateTemplateRequest("Mã xác thực", "Chào {ten_khach}"),
                "Mẫu thiếu biến bắt buộc: {ma_otp} (BR-QT-14)");
    }

    @Test
    void everyProblemIsReportedInOneMessage() {
        assertRule(new UpdateTemplateRequest("{x", "Chào {ten_khach} {y}"),
                "Tiêu đề có dấu { hoặc } lẻ — dấu ngoặc nhọn chỉ dùng để viết biến dạng {ten_bien}; Nội dung dùng "
                        + "biến không có: {y}; Biến dùng được: {ten_khach}, {ma_otp}, {thoi_han_phut}; Mẫu thiếu biến "
                        + "bắt buộc: {ma_otp} (BR-QT-14)");
    }

    @Test
    void variablesAreCheckedAgainstTheLockedRow() {
        ReflectionTestUtils.setField(row, "allowedVars", List.of("ma_otp", "moi"));

        service.updateTemplate(CODE, new UpdateTemplateRequest("T", "{ma_otp} {moi}"));

        assertThat(row.getBody()).isEqualTo("{ma_otp} {moi}");
    }

    // ================================================================ #35

    @Test
    void restoreCopiesDefaultsFlushesThenAudits() {
        row.changeContent("Đã sửa", "Đã sửa {ma_otp}", 99L);

        NotificationTemplateResponse response = service.restoreTemplate(CODE);

        assertThat(response.subject()).isEqualTo(DEFAULT_SUBJECT);
        assertThat(response.body()).isEqualTo(DEFAULT_BODY);
        assertThat(response.isDefault()).isTrue();
        assertThat(row.getUpdatedBy()).isEqualTo(ADMIN);
        InOrder order = inOrder(templates, audit);
        order.verify(templates).findByCodeForUpdate(CODE);
        order.verify(templates).flush();
        AuditEntry entry = captureAudit(order);
        order.verifyNoMoreInteractions();
        assertThat(entry.action()).isEqualTo("NOTIFICATION_TEMPLATE_RESTORED");
        assertThat(entry.before()).isEqualTo(snapshot(CODE, "Đã sửa", "Đã sửa {ma_otp}"));
        assertThat(entry.after()).isEqualTo(snapshot(CODE, DEFAULT_SUBJECT, DEFAULT_BODY));
    }

    @Test
    void restoreOfADefaultTemplateIsNotWrittenButStillAudited() {
        service.restoreTemplate(CODE);

        assertThat(row.getUpdatedBy()).isNull();
        verify(templates, never()).flush();
        AuditEntry entry = captureAudit(inOrder(audit));
        assertThat(entry.action()).isEqualTo("NOTIFICATION_TEMPLATE_RESTORED");
        assertThat(entry.before()).isEqualTo(entry.after());
    }

    @Test
    void restoreOfUnknownCodeIs404() {
        assertThatThrownBy(() -> service.restoreTemplate("MISSING"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy mẫu thông báo #MISSING");

        verifyNothingWritten();
    }

    // ================================================================ ranh giới transaction

    /** Convention 07 §7.1: một transaction mỗi thao tác ghi, mọi lỗi rollback (không noRollbackFor); list chỉ đọc. */
    @Test
    void transactionBoundaries() throws NoSuchMethodException {
        for (Method method : List.of(
                NotificationTemplateAdminService.class.getMethod("updateTemplate", String.class,
                        UpdateTemplateRequest.class),
                NotificationTemplateAdminService.class.getMethod("restoreTemplate", String.class))) {
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).as(method.getName()).isNotNull();
            assertThat(tx.readOnly()).isFalse();
            assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
            assertThat(tx.noRollbackFor()).isEmpty();
            assertThat(tx.noRollbackForClassName()).isEmpty();
        }
        Transactional list = NotificationTemplateAdminService.class.getMethod("listTemplates")
                .getAnnotation(Transactional.class);
        assertThat(list.readOnly()).isTrue();
        assertThat(NotificationTemplateAdminService.class.getAnnotation(Transactional.class)).isNull();
    }

    // ================================================================ helpers

    private void assertRule(UpdateTemplateRequest request, String expectedMessageEnd) {
        assertThat(assertRuleThrown(request).getMessage()).endsWith(expectedMessageEnd);
    }

    private BusinessRuleViolationException assertRuleThrown(UpdateTemplateRequest request) {
        BusinessRuleViolationException error = (BusinessRuleViolationException) org.assertj.core.api.Assertions
                .catchThrowable(() -> service.updateTemplate(CODE, request));
        assertThat(error).isNotNull();
        assertThat(error.getRuleId()).isEqualTo("BR-QT-14");
        assertThat(row.getSubject()).isEqualTo(DEFAULT_SUBJECT);
        assertThat(row.getBody()).isEqualTo(DEFAULT_BODY);
        verifyNothingWritten();
        return error;
    }

    private void verifyNothingWritten() {
        verify(templates, never()).flush();
        verify(audit, never()).record(any());
        verify(audit, never()).recordIndependently(any());
        assertThat(row.getUpdatedBy()).isNull();
    }

    private AuditEntry captureAudit(InOrder order) {
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        order.verify(audit).record(entry.capture());
        return entry.getValue();
    }

    private static Map<String, Object> snapshot(String code, String subject, String body) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("code", code);
        snapshot.put("subject", subject);
        snapshot.put("body", body);
        return snapshot;
    }

    static NotificationTemplate template(String code, String channel, String subject, String body,
            List<String> allowed, List<String> required) {
        NotificationTemplate template = BeanUtils.instantiateClass(NotificationTemplate.class);
        ReflectionTestUtils.setField(template, "code", code);
        ReflectionTestUtils.setField(template, "channel", channel);
        ReflectionTestUtils.setField(template, "subject", subject);
        ReflectionTestUtils.setField(template, "body", body);
        ReflectionTestUtils.setField(template, "defaultSubject", subject);
        ReflectionTestUtils.setField(template, "defaultBody", body);
        ReflectionTestUtils.setField(template, "allowedVars", allowed);
        ReflectionTestUtils.setField(template, "requiredVars", required);
        return template;
    }
}
