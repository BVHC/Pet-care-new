package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.api.CustomerApi;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.CustomerQueryApi.OnlineProfileLinkability;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.LinkResult;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.exception.LinkAccountNotFoundException;
import com.petcare.module.identity.mapper.LinkProfileMapper;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.service.LinkProfileAttemptService.LinkSnapshot;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ErrorCode;
import com.petcare.platform.exception.ResourceNotFoundException;

/**
 * UC22 — lễ tân liên kết hộ (identity-v1 #27; BR-TK-11, 16, 19), phần có transaction. Thứ tự:
 * <ul>
 *   <li>Đọc: hồ sơ (404) → đã gắn tài khoản (BR-TK-19) → chưa có email (BR-TK-19 kèm hướng dẫn).</li>
 *   <li>Ghi: khóa {@code accounts} theo email → tài khoản khách {@code ACTIVE} có hồ sơ (404 chung) → bị khóa (BR-TK-11) →
 *       hồ sơ của tài khoản chính là hồ sơ đang liên kết (BR-TK-19) → liên kết được không (BR-TK-19) →
 *       {@code linkAccountToCounterProfile} → audit.</li>
 * </ul>
 * Mỗi nhánh lỗi không gọi lệnh ghi nào đứng sau nó. Mapper thật; {@code CustomerApi}, {@code CustomerQueryApi} mock.
 */
class CounterLinkStepServiceTest {

    private static final long COUNTER = 7L;
    private static final long ACCOUNT = 70L;
    private static final long ONLINE = 71L;
    private static final String COUNTER_EMAIL = "Quay7@PetCare.test";
    private static final String ACCOUNT_EMAIL = "khach70@petcare.test";

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final CustomerQueryApi customerQueries = mock(CustomerQueryApi.class);
    private final CustomerApi customers = mock(CustomerApi.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final LinkProfileMapper mapper = Mappers.getMapper(LinkProfileMapper.class);
    private final CounterLinkStepService service = new CounterLinkStepService(accounts, customerQueries, customers,
            audit, mapper);

    // ================================================================ findCounterEmail — bước đọc

    /** Email hồ sơ đi nguyên văn (kể cả hoa thường): customer so {@code equals} với email đang lưu. */
    @Test
    void findReturnsTheProfileEmailVerbatim() {
        when(customerQueries.findContact(COUNTER)).thenReturn(Optional.of(contact(null, COUNTER_EMAIL)));

        assertThat(service.findCounterEmail(COUNTER)).isEqualTo(COUNTER_EMAIL);

        verifyNoInteractions(accounts, customers, audit);
    }

    @Test
    void findUnknownProfileIs404() {
        when(customerQueries.findContact(COUNTER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findCounterEmail(COUNTER)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy hồ sơ khách #7");
    }

    /** Hồ sơ online hoặc hồ sơ quầy đã liên kết: {@code CustomerContact} không có kênh nên một câu chung. */
    @Test
    void findProfileHoldingAnAccountIsBrTk19EvenWhenItHasNoEmail() {
        when(customerQueries.findContact(COUNTER)).thenReturn(Optional.of(contact(ONLINE, null)));

        assertRule(() -> service.findCounterEmail(COUNTER), "BR-TK-19",
                "Hồ sơ này đã gắn tài khoản (hồ sơ online hoặc đã liên kết)");
    }

    @ParameterizedTest(name = "email hồ sơ = [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void findProfileWithoutEmailIsBrTk19WithGuidance(String email) {
        when(customerQueries.findContact(COUNTER)).thenReturn(Optional.of(contact(null, email)));

        assertRule(() -> service.findCounterEmail(COUNTER), "BR-TK-19", "Hồ sơ tại quầy chưa có email. Hãy bổ sung email"
                + " vào hồ sơ (sửa hồ sơ khách) rồi liên kết lại");
    }

    // ================================================================ applyLink — bước ghi

    @Test
    void applyLocksTheAccountFirstThenGuardsThenLinksWithTheLockedRowEmailThenAudits() {
        Account account = account(Role.CUSTOMER, AccountStatus.ACTIVE, false);
        stubLockedAccount(account);

        LinkResult result = service.applyLink(COUNTER, ACCOUNT_EMAIL, COUNTER_EMAIL);

        assertThat(result).isEqualTo(new LinkResult(COUNTER));
        InOrder order = inOrder(accounts, customerQueries, customers, audit);
        order.verify(accounts).findByEmailForUpdate(ACCOUNT_EMAIL);
        order.verify(customerQueries).findCustomerIdByAccountId(ACCOUNT);
        order.verify(customerQueries).checkOnlineProfileLinkable(ACCOUNT);
        order.verify(customers).linkAccountToCounterProfile(ACCOUNT, COUNTER, COUNTER_EMAIL, ACCOUNT_EMAIL);
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        order.verify(audit).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo("CUSTOMER_PROFILE_LINKED");
        assertThat(entry.getValue().entityType()).isEqualTo("customers");
        assertThat(entry.getValue().entityId()).isEqualTo(COUNTER);
        assertThat(entry.getValue().before()).isEqualTo(new LinkSnapshot(COUNTER, ONLINE, null, null));
        assertThat(entry.getValue().after())
                .isEqualTo(new LinkSnapshot(COUNTER, null, ACCOUNT, "ID_CARD_IN_PERSON"));
        assertThat(entry.getValue().actorOverridden()).as("actor = lễ tân đang đăng nhập").isFalse();
        assertThat(entry.getValue().reason()).isNull();
    }

    /** Email khách đã chuẩn hóa mà không có tài khoản nào: 404 chung, không đụng customer. */
    @Test
    void applyUnknownEmailIs404AndNeverTouchesCustomer() {
        when(accounts.findByEmailForUpdate(ACCOUNT_EMAIL)).thenReturn(Optional.empty());

        assertNotFound(() -> service.applyLink(COUNTER, ACCOUNT_EMAIL, COUNTER_EMAIL));

        verifyNoInteractions(customerQueries, customers, audit);
    }

    /** Tài khoản nhân viên: 404, không được lọt xuống bước đọc hồ sơ / kiểm liên kết (nơi nó là 500). */
    @ParameterizedTest(name = "role {0} → 404")
    @EnumSource(value = Role.class, names = "CUSTOMER", mode = EnumSource.Mode.EXCLUDE)
    void applyStaffAccountIs404(Role role) {
        stubLockedAccount(account(role, AccountStatus.ACTIVE, false));

        assertNotFound(() -> service.applyLink(COUNTER, ACCOUNT_EMAIL, COUNTER_EMAIL));

        verifyNoInteractions(customerQueries, customers, audit);
    }

    /**
     * ST02 xóa hồ sơ gắn tài khoản PENDING quá hạn (không lọc kênh): tài khoản chưa xác thực email không được nhận hồ sơ
     * quầy — 404 chung, trước mọi lệnh ghi.
     */
    @Test
    void applyPendingAccountIs404BeforeAnyCustomerCall() {
        stubLockedAccount(account(Role.CUSTOMER, AccountStatus.PENDING, false));

        assertNotFound(() -> service.applyLink(COUNTER, ACCOUNT_EMAIL, COUNTER_EMAIL));

        verifyNoInteractions(customerQueries, customers, audit);
    }

    /** BR-KH-01 bị vi phạm (khách ACTIVE không có hồ sơ): 404, không để {@code checkOnlineProfileLinkable} ném 500. */
    @Test
    void applyCustomerAccountWithoutAProfileIs404AndNeverChecksLinkability() {
        when(accounts.findByEmailForUpdate(ACCOUNT_EMAIL))
                .thenReturn(Optional.of(account(Role.CUSTOMER, AccountStatus.ACTIVE, false)));
        when(customerQueries.findCustomerIdByAccountId(ACCOUNT)).thenReturn(Optional.empty());

        assertNotFound(() -> service.applyLink(COUNTER, ACCOUNT_EMAIL, COUNTER_EMAIL));

        verify(customerQueries, never()).checkOnlineProfileLinkable(anyLong());
        verifyNoInteractions(customers, audit);
    }

    @Test
    void applyLockedAccountIsBrTk11BeforeTheLinkabilityCheck() {
        stubLockedAccount(account(Role.CUSTOMER, AccountStatus.ACTIVE, true));

        assertRule(() -> service.applyLink(COUNTER, ACCOUNT_EMAIL, COUNTER_EMAIL), "BR-TK-11",
                "Tài khoản đã bị khóa. Vui lòng liên hệ Pet Care để được hỗ trợ");

        verify(customerQueries, never()).checkOnlineProfileLinkable(anyLong());
        verifyNoInteractions(customers, audit);
    }

    /** Bấm đúp: lần trước đã gắn xong — hồ sơ của tài khoản chính là hồ sơ này, nên không nói "liên kết hồ sơ quầy khác". */
    @Test
    void applyWhenTheAccountAlreadyOwnsThisProfileIsTheProfileHasAccountMessage() {
        stubLockedAccount(account(Role.CUSTOMER, AccountStatus.ACTIVE, false));
        when(customerQueries.findCustomerIdByAccountId(ACCOUNT)).thenReturn(Optional.of(COUNTER));

        assertRule(() -> service.applyLink(COUNTER, ACCOUNT_EMAIL, COUNTER_EMAIL), "BR-TK-19",
                "Hồ sơ này đã gắn tài khoản (hồ sơ online hoặc đã liên kết)");

        verify(customerQueries, never()).checkOnlineProfileLinkable(anyLong());
        verifyNoInteractions(customers, audit);
    }

    @Test
    void applyAccountLinkedToAnotherCounterProfileIsBrTk19() {
        stubLockedAccount(account(Role.CUSTOMER, AccountStatus.ACTIVE, false));
        when(customerQueries.checkOnlineProfileLinkable(ACCOUNT)).thenReturn(OnlineProfileLinkability.ALREADY_LINKED);

        assertRule(() -> service.applyLink(COUNTER, ACCOUNT_EMAIL, COUNTER_EMAIL), "BR-TK-19",
                "Tài khoản đã được liên kết với một hồ sơ tại quầy khác");

        verifyNoInteractions(customers, audit);
    }

    /** Không dùng câu "của bạn" của UC07: người đọc là lễ tân. */
    @Test
    void applyOnlineProfileWithDataIsBrTk19() {
        stubLockedAccount(account(Role.CUSTOMER, AccountStatus.ACTIVE, false));
        when(customerQueries.checkOnlineProfileLinkable(ACCOUNT)).thenReturn(OnlineProfileLinkability.HAS_DATA);

        assertRule(() -> service.applyLink(COUNTER, ACCOUNT_EMAIL, COUNTER_EMAIL), "BR-TK-19",
                "Hồ sơ online của tài khoản đã có thú cưng, lịch hẹn, đặt chỗ, đơn hàng hoặc góp ý nên không liên kết được");

        verifyNoInteractions(customers, audit);
    }

    /** Customer từ chối dưới khóa (hồ sơ vừa bị liên kết / đổi email): lỗi đi nguyên, không audit. */
    @Test
    void applyRejectionUnderLockPassesThroughWithoutAudit() {
        stubLockedAccount(account(Role.CUSTOMER, AccountStatus.ACTIVE, false));
        BusinessRuleViolationException rejected = new BusinessRuleViolationException("BR-TK-19",
                "Hồ sơ tại quầy không còn liên kết được");
        doThrow(rejected).when(customers).linkAccountToCounterProfile(anyLong(), anyLong(), anyString(), anyString());

        assertThatThrownBy(() -> service.applyLink(COUNTER, ACCOUNT_EMAIL, COUNTER_EMAIL)).isSameAs(rejected);

        verifyNoInteractions(audit);
    }

    /** Ranh giới transaction: bước đọc readOnly, bước ghi REQUIRED không {@code noRollbackFor} (mọi lỗi rollback hết). */
    @Test
    void transactionBoundaries() throws NoSuchMethodException {
        Transactional find = method("findCounterEmail", Long.class).getAnnotation(Transactional.class);
        Transactional apply = method("applyLink", Long.class, String.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(find).isNotNull();
        assertThat(find.readOnly()).isTrue();
        assertThat(apply).isNotNull();
        assertThat(apply.readOnly()).isFalse();
        assertThat(apply.noRollbackFor()).isEmpty();
        assertThat(apply.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(find.propagation()).isEqualTo(Propagation.REQUIRED);
    }

    // ================================================================ helpers

    private static Method method(String name, Class<?>... types) throws NoSuchMethodException {
        return CounterLinkStepService.class.getMethod(name, types);
    }

    private static CustomerContact contact(Long accountId, String email) {
        return new CustomerContact(COUNTER, "Khách quầy", "0912345678", email, accountId, false);
    }

    private static Account account(Role role, AccountStatus status, boolean locked) {
        Account account = role == Role.CUSTOMER
                ? Account.registerCustomer(ACCOUNT_EMAIL, "hash", Instant.parse("2026-11-03T03:00:00Z"))
                : Account.createStaff(ACCOUNT_EMAIL, "0901234567", "hash", role);
        ReflectionTestUtils.setField(account, "id", ACCOUNT);
        ReflectionTestUtils.setField(account, "status", status);
        ReflectionTestUtils.setField(account, "locked", locked);
        return account;
    }

    /** Khóa được dòng tài khoản; hồ sơ của nó là hồ sơ online {@code ONLINE}, liên kết được. */
    private void stubLockedAccount(Account account) {
        when(accounts.findByEmailForUpdate(ACCOUNT_EMAIL)).thenReturn(Optional.of(account));
        when(customerQueries.findCustomerIdByAccountId(ACCOUNT)).thenReturn(Optional.of(ONLINE));
        when(customerQueries.checkOnlineProfileLinkable(ACCOUNT)).thenReturn(OnlineProfileLinkability.LINKABLE);
    }

    private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(LinkAccountNotFoundException.class)
                .hasMessage("Không tìm thấy tài khoản khách hàng đã kích hoạt với email này")
                .satisfies(ex -> assertThat(((LinkAccountNotFoundException) ex).errorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private static void assertRule(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String ruleId,
            String message) {
        assertThatThrownBy(call).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage(message + " (" + ruleId + ")");
    }
}
