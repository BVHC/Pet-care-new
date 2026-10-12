package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.api.AppointmentQueryApi;
import com.petcare.module.appointment.api.AppointmentQueryApi.BookedSlot;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.boarding.api.BoardingQueryApi.StaySummary;
import com.petcare.module.boarding.api.BoardingQueryApi.StayStatus;
import com.petcare.module.branch.api.BranchApi;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.identity.api.AccountLockedEvent;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.AccountSummary;
import com.petcare.module.identity.dto.LockResult;
import com.petcare.module.identity.dto.PendingWorkItem;
import com.petcare.module.identity.dto.PendingWorkItem.PendingWorkType;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.entity.StaffProfile;
import com.petcare.module.identity.mapper.AccountAdminMapper;
import com.petcare.module.identity.mapper.LoginMapper;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.repository.StaffProfileRepository;
import com.petcare.module.identity.repository.StaffRow;
import com.petcare.module.sales.api.CashierShiftQueryApi;
import com.petcare.module.sales.api.CashierShiftQueryApi.OpenShift;
import com.petcare.module.sales.api.OrderQueryApi;
import com.petcare.module.sales.api.OrderQueryApi.UnpaidOrderRef;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.module.visit.api.VisitQueryApi.AssignedVisit;
import com.petcare.module.visit.api.VisitQueryApi.VisitStatus;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

/**
 * UC09 — khóa (#29, Tài khoản#7) và mở khóa (#30, Tài khoản#8) tài khoản (docs/adr/0035).
 * <ul>
 *   <li>Khóa: tự khóa (400, không đọc gì) → đọc role (404) → [BRANCH_MANAGER: khóa {@code branches}] → khóa
 *       {@code accounts} (404) → hồ sơ → [BRANCH_MANAGER: chi nhánh chưa khóa → 409] → đã khóa (409) → <b>đọc</b> việc
 *       dở + người nhận → <b>ghi</b>: cờ khóa → hủy phiên → sự kiện → thông báo → audit → flush.</li>
 *   <li>Mở khóa: khóa {@code accounts} (404) → chưa khóa (409) → gỡ cờ → audit → flush.</li>
 * </ul>
 * Mọi nhánh lỗi không ghi gì. Collector, mapper thật; query API của module khác là mock.
 */
class AccountLockServiceTest {

    private static final long ACTOR = 1L;
    private static final long TARGET = 7L;
    private static final long ACTIVE_BRANCH = 3L;
    private static final long DRAFT_BRANCH = 4L;
    private static final long OTHER_BRANCH = 5L;
    private static final long CUSTOMER_ID = 500L;
    private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");
    private static final String NOW_VN = "10:00 12/10/2026";
    private static final Map<Long, BranchStatus> BRANCHES = Map.of(ACTIVE_BRANCH, BranchStatus.ACTIVE,
            DRAFT_BRANCH, BranchStatus.DRAFT, OTHER_BRANCH, BranchStatus.ACTIVE);

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final StaffProfileRepository staffProfiles = mock(StaffProfileRepository.class);
    private final BranchApi branchLocks = mock(BranchApi.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final AppointmentQueryApi appointments = mock(AppointmentQueryApi.class);
    private final BoardingQueryApi boarding = mock(BoardingQueryApi.class);
    private final OrderQueryApi orders = mock(OrderQueryApi.class);
    private final VisitQueryApi visits = mock(VisitQueryApi.class);
    private final CashierShiftQueryApi cashierShifts = mock(CashierShiftQueryApi.class);
    private final SessionService sessions = mock(SessionService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final NotificationApi notifications = mock(NotificationApi.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final BranchScope branchScope = mock(BranchScope.class);
    private final LoginMapper loginMapper = Mappers.getMapper(LoginMapper.class);
    private final AccountAdminMapper mapper = adminMapper(loginMapper);
    private final AccountLockService service = new AccountLockService(accounts, staffProfiles, branchLocks, branches,
            new PendingWorkCollector(customers, appointments, boarding, orders, visits, cashierShifts, mapper),
            sessions, events, notifications, audit, mapper, loginMapper, branchScope,
            Clock.fixed(NOW, ZoneOffset.UTC));

    static AccountAdminMapper adminMapper(LoginMapper loginMapper) {
        AccountAdminMapper mapper = Mappers.getMapper(AccountAdminMapper.class);
        ReflectionTestUtils.setField(mapper, "loginMapper", loginMapper);
        return mapper;
    }

    @BeforeEach
    void setUp() {
        when(branchScope.current()).thenReturn(new AccountPrincipal(ACTOR, "admin@petcare.test", 42L, Role.ADMIN,
                null, false));
        when(branchLocks.lockForStaffChange(any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            SortedMap<Long, BranchStatus> locked = new TreeMap<>();
            ids.stream().filter(BRANCHES::containsKey).forEach(id -> locked.put(id, BRANCHES.get(id)));
            return locked;
        });
        BRANCHES.forEach((id, status) -> when(branches.findBranch(id)).thenReturn(Optional.of(
                new BranchSummary(id, "CN " + id, "Địa chỉ", "0280000000", status, false))));
        when(visits.findUnfinishedVisitsAssignedTo(anyLong())).thenReturn(List.of());
        when(cashierShifts.findOpenShift(anyLong())).thenReturn(Optional.empty());
        when(accounts.findActiveIdsByRole(Role.SUPER_MANAGER)).thenReturn(List.of(90L, 91L));
    }

    // ================================================================ #29 khóa — nhân viên A06–A08

    @Test
    void lockVetReadsThenLocksRevokesPublishesNotifiesAuditsFlushes() {
        Account account = staff(TARGET, Role.VET, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        ReflectionTestUtils.setField(account, "lastSeenAt", NOW);
        when(visits.findUnfinishedVisitsAssignedTo(TARGET)).thenReturn(List.of(
                new AssignedVisit(15L, ACTIVE_BRANCH, VisitStatus.WAITING),
                new AssignedVisit(12L, ACTIVE_BRANCH, VisitStatus.IN_PROGRESS)));
        managers(ACTIVE_BRANCH, 20L, 21L);

        LockResult result = service.lockAccount(TARGET, "  Nghi bị chiếm tài khoản  ");

        InOrder order = inOrder(accounts, staffProfiles, visits, cashierShifts, sessions, events, notifications, audit);
        order.verify(accounts).findRoleById(TARGET);
        order.verify(accounts).findByIdForUpdate(TARGET);
        order.verify(staffProfiles).findById(TARGET);
        order.verify(visits).findUnfinishedVisitsAssignedTo(TARGET);
        order.verify(cashierShifts).findOpenShift(TARGET);
        order.verify(staffProfiles).findActiveStaffRows(ACTIVE_BRANCH, Role.BRANCH_MANAGER);
        order.verify(sessions).revokeAll(TARGET);
        order.verify(events).publishEvent(any(AccountLockedEvent.class));
        order.verify(notifications, org.mockito.Mockito.times(2)).enqueue(any());
        order.verify(audit).record(any());
        order.verify(accounts).flush();
        verifyNoInteractions(branchLocks, customers);

        assertThat(account.isLocked()).isTrue();
        assertThat(account.getLockedReason()).isEqualTo("Nghi bị chiếm tài khoản");
        assertThat(account.getLastSeenAt()).isNull();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);

        assertThat(result.account()).isEqualTo(new AccountSummary(TARGET, "staff7@petcare.test", Role.VET,
                AccountStatus.ACTIVE, true, true));
        assertThat(result.pendingWork()).containsExactly(
                new PendingWorkItem(PendingWorkType.VISIT_IN_PROGRESS, 12L, ACTIVE_BRANCH, true),
                new PendingWorkItem(PendingWorkType.VISIT_WAITING, 15L, ACTIVE_BRANCH, false));

        assertThat(capturedEvent()).isEqualTo(new AccountLockedEvent(TARGET, Role.VET, ACTIVE_BRANCH,
                "Nghi bị chiếm tài khoản", ACTOR));
        List<NotificationRequest> sent = capturedNotices(2);
        assertThat(sent).extracting(NotificationRequest::recipientAccountId).containsExactly(20L, 21L);
        assertThat(sent).allSatisfy(notice -> {
            assertThat(notice.templateCode()).isEqualTo(NotificationTemplateCode.ACCOUNT_LOCKED_FOLLOW_UP_APP);
            assertThat(notice.channel()).isEqualTo(Channel.IN_APP);
            assertThat(notice.recipientEmail()).isNull();
            assertThat(notice.payload()).containsExactly(
                    Map.entry("ho_ten", "Nhân viên 7"),
                    Map.entry("email_tai_khoan", "staff7@petcare.test"),
                    Map.entry("vai_tro", "Bác sĩ thú y"),
                    Map.entry("ly_do", "Nghi bị chiếm tài khoản"),
                    Map.entry("thoi_diem", NOW_VN),
                    Map.entry("viec_do_dang", "- Lượt tiếp nhận #12: đang thực hiện, cần gán lại\n"
                            + "- Lượt tiếp nhận #15: đang chờ trong hàng đợi"));
        });

        AuditEntry entry = capturedAudit();
        assertThat(entry.action()).isEqualTo(QtAuditActions.ACCOUNT_LOCKED);
        assertThat(entry.entityType()).isEqualTo("accounts");
        assertThat(entry.entityId()).isEqualTo(TARGET);
        assertThat(entry.reason()).isEqualTo("Nghi bị chiếm tài khoản");
        assertThat(snapshot(entry.before())).containsExactly(Map.entry("isLocked", false),
                Map.entry("status", "ACTIVE"));
        assertThat(snapshot(entry.after())).containsExactly(Map.entry("isLocked", true),
                Map.entry("status", "ACTIVE"));
    }

    /** U2: nhân viên luôn được báo, kể cả không có việc dở; chi nhánh không còn quản lý → SUPER_MANAGER (BR-TN-08). */
    @Test
    void staffWithoutPendingWorkStillNotifiesAndFallsBackToSuperManagers() {
        staff(TARGET, Role.RECEPTIONIST, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        when(cashierShifts.findOpenShift(TARGET)).thenReturn(Optional.empty());
        managers(ACTIVE_BRANCH);

        LockResult result = service.lockAccount(TARGET, "Vi phạm");

        assertThat(result.pendingWork()).isEmpty();
        List<NotificationRequest> sent = capturedNotices(2);
        assertThat(sent).extracting(NotificationRequest::recipientAccountId).containsExactly(90L, 91L);
        assertThat(sent).allSatisfy(notice -> assertThat(notice.payload())
                .containsEntry("viec_do_dang", AccountLockNotices.NO_PENDING_WORK)
                .containsEntry("vai_tro", "Lễ tân"));
    }

    @Test
    void openCashierShiftIsListed() {
        staff(TARGET, Role.RECEPTIONIST, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        when(cashierShifts.findOpenShift(TARGET)).thenReturn(Optional.of(new OpenShift(33L, ACTIVE_BRANCH)));
        managers(ACTIVE_BRANCH, 20L);

        LockResult result = service.lockAccount(TARGET, "Vi phạm");

        assertThat(result.pendingWork()).containsExactly(
                new PendingWorkItem(PendingWorkType.CASHIER_SHIFT_OPEN, 33L, ACTIVE_BRANCH, false));
        assertThat(capturedNotices(1).get(0).payload()).containsEntry("viec_do_dang", "- Ca thu ngân #33: đang mở");
    }

    /** BR-QT-12: tài khoản DISABLED vẫn khóa được; {@code status} giữ nguyên. */
    @Test
    void disabledStaffCanBeLockedAndKeepsStatus() {
        Account account = staff(TARGET, Role.CARETAKER, AccountStatus.DISABLED, ACTIVE_BRANCH);
        managers(ACTIVE_BRANCH, 20L);

        LockResult result = service.lockAccount(TARGET, "Vi phạm");

        assertThat(account.isLocked()).isTrue();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.DISABLED);
        assertThat(result.account().status()).isEqualTo(AccountStatus.DISABLED);
        assertThat(snapshot(capturedAudit().after())).containsEntry("status", "DISABLED");
    }

    // ================================================================ #29 khóa — BRANCH_MANAGER

    /** U3, U4: quản lý cuối cùng (ACTIVE, chi nhánh ACTIVE dưới khóa, không còn quản lý hoạt động khác) → chỉ LAST. */
    @ParameterizedTest(name = "status={0} branch={1} otherManagers={2} → last={3}")
    @CsvSource({
            "ACTIVE,   ACTIVE, 0, true",
            "ACTIVE,   ACTIVE, 1, false",
            "ACTIVE,   DRAFT,  0, false",
            "DISABLED, ACTIVE, 0, false"})
    void lastBranchManagerTruthTable(AccountStatus status, String branch, int otherManagers, boolean last) {
        long branchId = "ACTIVE".equals(branch) ? ACTIVE_BRANCH : DRAFT_BRANCH;
        staff(TARGET, Role.BRANCH_MANAGER, status, branchId);
        Long[] others = otherManagers == 0 ? new Long[] {TARGET} : new Long[] {TARGET, 22L};
        managers(branchId, others);

        service.lockAccount(TARGET, "Vi phạm");

        verify(branchLocks).lockForStaffChange(Set.of(branchId));
        InOrder order = inOrder(branchLocks, accounts);
        order.verify(branchLocks).lockForStaffChange(Set.of(branchId));
        order.verify(accounts).findByIdForUpdate(TARGET);
        List<NotificationRequest> sent = capturedNotices(2);
        assertThat(sent).extracting(NotificationRequest::recipientAccountId).containsExactly(90L, 91L);
        String expectedCode = last ? NotificationTemplateCode.LAST_BRANCH_MANAGER_LOCKED_APP
                : NotificationTemplateCode.ACCOUNT_LOCKED_FOLLOW_UP_APP;
        assertThat(sent).allSatisfy(notice -> assertThat(notice.templateCode()).isEqualTo(expectedCode));
        if (last) {
            assertThat(sent.get(0).payload()).containsEntry("ten_chi_nhanh", "CN " + branchId);
        } else {
            assertThat(sent.get(0).payload()).doesNotContainKey("ten_chi_nhanh");
        }
        assertThat(capturedEvent().branchId()).isEqualTo(branchId);
    }

    /** U4: chi nhánh đổi trong lúc chờ khóa (điều chuyển) → 409, không ghi. */
    @Test
    void branchManagerMovedWhileWaitingIs409WithoutWrite() {
        Account account = staff(TARGET, Role.BRANCH_MANAGER, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        StaffProfile moved = profile(TARGET, OTHER_BRANCH);
        when(staffProfiles.findById(TARGET)).thenReturn(Optional.of(moved));

        assertThatThrownBy(() -> service.lockAccount(TARGET, "Vi phạm"))
                .isInstanceOf(ConcurrencyConflictException.class);
        verify(branchLocks).lockForStaffChange(Set.of(ACTIVE_BRANCH));
        assertThat(account.isLocked()).isFalse();
        verifyNoInteractions(visits, cashierShifts);
        assertNoWrite();
    }

    /** U4: đọc không khóa thấy VET (không khóa chi nhánh), dưới khóa đã là BRANCH_MANAGER → 409. */
    @Test
    void becameBranchManagerWhileWaitingIs409WithoutWrite() {
        Account account = staff(TARGET, Role.BRANCH_MANAGER, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        when(accounts.findRoleById(TARGET)).thenReturn(Optional.of(Role.VET));

        assertThatThrownBy(() -> service.lockAccount(TARGET, "Vi phạm"))
                .isInstanceOf(ConcurrencyConflictException.class);
        verifyNoInteractions(branchLocks);
        assertThat(account.isLocked()).isFalse();
        assertNoWrite();
    }

    // ================================================================ #29 khóa — khách (06 §8 Q2)

    @Test
    void customerWorkIsGroupedByBranchToThatBranchsReceptionists() {
        Account account = customer(TARGET, AccountStatus.ACTIVE);
        when(appointments.findBookedByCustomer(CUSTOMER_ID)).thenReturn(List.of(
                new BookedSlot(40L, "LH-1", ACTIVE_BRANCH, 1L, CUSTOMER_ID, LocalDate.of(2026, 10, 20),
                        LocalTime.of(9, 0))));
        when(boarding.findActiveStaysByCustomer(CUSTOMER_ID)).thenReturn(List.of(
                new StaySummary(50L, OTHER_BRANCH, CUSTOMER_ID, 1L, StayStatus.CHECKED_IN, "0901111111")));
        when(orders.findUnpaidOrdersByCustomer(CUSTOMER_ID)).thenReturn(List.of(
                new UnpaidOrderRef(60L, ACTIVE_BRANCH), new UnpaidOrderRef(61L, DRAFT_BRANCH)));
        receptionists(ACTIVE_BRANCH, 30L, 31L);
        receptionists(OTHER_BRANCH, 32L);
        receptionists(DRAFT_BRANCH);

        LockResult result = service.lockAccount(TARGET, "Đặt lịch ảo");

        assertThat(account.isLocked()).isTrue();
        assertThat(result.pendingWork()).containsExactly(
                new PendingWorkItem(PendingWorkType.APPOINTMENT_BOOKED, 40L, ACTIVE_BRANCH, false),
                new PendingWorkItem(PendingWorkType.BOARDING_ACTIVE, 50L, OTHER_BRANCH, false),
                new PendingWorkItem(PendingWorkType.ORDER_UNPAID, 60L, ACTIVE_BRANCH, false),
                new PendingWorkItem(PendingWorkType.ORDER_UNPAID, 61L, DRAFT_BRANCH, false));
        List<NotificationRequest> sent = capturedNotices(3);
        assertThat(sent).extracting(NotificationRequest::recipientAccountId).containsExactly(30L, 31L, 32L);
        assertThat(sent.get(0).payload()).containsEntry("ho_ten", "Nguyễn Văn Khách")
                .containsEntry("vai_tro", "Khách hàng")
                .containsEntry("viec_do_dang", "- Lịch hẹn #40: đã đặt\n- Order #60: chưa thanh toán");
        assertThat(sent.get(2).payload()).containsEntry("viec_do_dang", "- Đặt chỗ lưu trú #50: đang hiệu lực");
        assertThat(capturedEvent()).isEqualTo(new AccountLockedEvent(TARGET, Role.CUSTOMER, null, "Đặt lịch ảo",
                ACTOR));
        verifyNoInteractions(visits, cashierShifts, branchLocks);
    }

    /** Q2: khách không có việc dở → không gửi cho ai; vẫn khóa, hủy phiên, audit. Tài khoản PENDING cũng khóa được. */
    @Test
    void customerWithoutPendingWorkNotifiesNobody() {
        Account account = customer(TARGET, AccountStatus.PENDING);

        LockResult result = service.lockAccount(TARGET, "Spam");

        assertThat(account.isLocked()).isTrue();
        assertThat(result.pendingWork()).isEmpty();
        verifyNoInteractions(notifications);
        verify(sessions).revokeAll(TARGET);
        verify(audit).record(any());
    }

    @Test
    void customerWithoutProfileIsServerErrorWithoutWrite() {
        customer(TARGET, AccountStatus.ACTIVE);
        when(customers.findCustomerIdByAccountId(TARGET)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.lockAccount(TARGET, "Spam")).isInstanceOf(IllegalStateException.class);
        assertNoWrite();
    }

    // ================================================================ #29 khóa — ADMIN, SUPER_MANAGER

    @ParameterizedTest(name = "{0}")
    @CsvSource({"ADMIN", "SUPER_MANAGER"})
    void chainLevelAccountsHaveNoPendingWorkAndNoNotice(Role role) {
        Account account = staff(TARGET, role, AccountStatus.ACTIVE, null);

        LockResult result = service.lockAccount(TARGET, "Sự cố bảo mật");

        assertThat(account.isLocked()).isTrue();
        assertThat(result.pendingWork()).isEmpty();
        assertThat(capturedEvent().branchId()).isNull();
        verifyNoInteractions(visits, cashierShifts, customers, notifications, branchLocks);
    }

    // ================================================================ #29 khóa — lỗi

    @Test
    void selfLockIsBrQt11BeforeAnyRead() {
        assertRule(() -> service.lockAccount(ACTOR, "Thử"), "BR-QT-11",
                "Không thể tự khóa tài khoản của chính mình (BR-QT-11)");
        verifyNoInteractions(accounts, staffProfiles, branchLocks, visits, cashierShifts, customers);
        assertNoWrite();
    }

    @Test
    void missingAccountIs404() {
        when(accounts.findRoleById(8L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.lockAccount(8L, "Spam")).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy tài khoản #8");
        assertNoWrite();
    }

    /** ST02 xóa tài khoản PENDING giữa bước đọc và bước khóa → 404, không phải 500. */
    @Test
    void accountDeletedBeforeLockIs404() {
        when(accounts.findRoleById(8L)).thenReturn(Optional.of(Role.CUSTOMER));
        when(accounts.findByIdForUpdate(8L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.lockAccount(8L, "Spam")).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy tài khoản #8");
        assertNoWrite();
    }

    @Test
    void alreadyLockedIs409BeforeReadingPendingWork() {
        Account account = staff(TARGET, Role.VET, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        ReflectionTestUtils.setField(account, "locked", true);
        ReflectionTestUtils.setField(account, "lockedReason", "Lần trước");

        assertThatThrownBy(() -> service.lockAccount(TARGET, "Lần này"))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Không thể chuyển trạng thái tài khoản từ đã khóa sang khóa");
        assertThat(account.getLockedReason()).isEqualTo("Lần trước");
        verifyNoInteractions(visits, cashierShifts);
        assertNoWrite();
    }

    /** Nợ D016: placeholder ném trước lệnh ghi đầu tiên → không đổi gì. */
    @Test
    void placeholderFailurePropagatesWithoutWrite() {
        Account account = staff(TARGET, Role.VET, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        when(visits.findUnfinishedVisitsAssignedTo(TARGET)).thenThrow(new UnsupportedOperationException("D016"));

        assertThatThrownBy(() -> service.lockAccount(TARGET, "Vi phạm"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(account.isLocked()).isFalse();
        assertNoWrite();
    }

    /** Mapper ném với trạng thái ngoài hợp đồng của {@code findUnfinishedVisitsAssignedTo}, không gán sai loại. */
    @Test
    void finishedVisitFromApiIsRejectedWithoutWrite() {
        staff(TARGET, Role.VET, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        when(visits.findUnfinishedVisitsAssignedTo(TARGET)).thenReturn(List.of(
                new AssignedVisit(12L, ACTIVE_BRANCH, VisitStatus.COMPLETED)));

        assertThatThrownBy(() -> service.lockAccount(TARGET, "Vi phạm")).isInstanceOf(IllegalArgumentException.class);
        assertNoWrite();
    }

    // ================================================================ #30 mở khóa

    @ParameterizedTest(name = "status={0}")
    @CsvSource({"ACTIVE", "DISABLED"})
    void unlockKeepsStatusAndReasonAndAudits(AccountStatus status) {
        Account account = staff(TARGET, Role.VET, status, ACTIVE_BRANCH);
        ReflectionTestUtils.setField(account, "locked", true);
        ReflectionTestUtils.setField(account, "lockedReason", "Vi phạm");

        AccountSummary summary = service.unlockAccount(TARGET, " Đã xác minh ");

        assertThat(summary).isEqualTo(new AccountSummary(TARGET, "staff7@petcare.test", Role.VET, status, false, true));
        assertThat(account.isLocked()).isFalse();
        assertThat(account.getStatus()).isEqualTo(status);
        assertThat(account.getLockedReason()).isEqualTo("Vi phạm");
        InOrder order = inOrder(accounts, audit);
        order.verify(accounts).findByIdForUpdate(TARGET);
        order.verify(audit).record(any());
        order.verify(accounts).flush();
        AuditEntry entry = capturedAudit();
        assertThat(entry.action()).isEqualTo(QtAuditActions.ACCOUNT_UNLOCKED);
        assertThat(entry.reason()).isEqualTo("Đã xác minh");
        assertThat(snapshot(entry.before())).containsExactly(Map.entry("isLocked", true),
                Map.entry("status", status.name()));
        assertThat(snapshot(entry.after())).containsExactly(Map.entry("isLocked", false),
                Map.entry("status", status.name()));
        verifyNoInteractions(sessions, events, notifications, branchLocks);
    }

    @Test
    void unlockNotLockedIs409WithoutWrite() {
        staff(TARGET, Role.VET, AccountStatus.ACTIVE, ACTIVE_BRANCH);

        assertThatThrownBy(() -> service.unlockAccount(TARGET, "Thử"))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Không thể chuyển trạng thái tài khoản từ chưa khóa sang mở khóa");
        assertNoWrite();
    }

    @Test
    void unlockMissingIs404() {
        when(accounts.findByIdForUpdate(8L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unlockAccount(8L, "Thử")).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy tài khoản #8");
        assertNoWrite();
    }

    // ================================================================ ranh giới transaction

    /** Khóa không có gì phải giữ lại khi rollback: không {@code noRollbackFor}, không {@code readOnly}. */
    @Test
    void writeMethodsAreFullTransactionsWithoutNoRollbackFor() throws NoSuchMethodException {
        for (Method method : List.of(
                AccountLockService.class.getMethod("lockAccount", Long.class, String.class),
                AccountLockService.class.getMethod("unlockAccount", Long.class, String.class))) {
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).as(method.getName()).isNotNull();
            assertThat(tx.readOnly()).isFalse();
            assertThat(tx.noRollbackFor()).isEmpty();
            assertThat(tx.noRollbackForClassName()).isEmpty();
        }
        assertThat(AccountAdminQueryService.class.getAnnotation(Transactional.class).readOnly()).isTrue();
    }

    // ================================================================ helpers

    private Account staff(long id, Role role, AccountStatus status, Long branchId) {
        Account account = Account.createStaff("staff" + id + "@petcare.test", "0901234567", "hash", role);
        ReflectionTestUtils.setField(account, "id", id);
        ReflectionTestUtils.setField(account, "status", status);
        when(accounts.findRoleById(id)).thenReturn(Optional.of(role));
        when(staffProfiles.findStaffRow(id)).thenReturn(Optional.of(new StaffRow(id, "Nhân viên " + id, role,
                branchId, status, false, null)));
        when(accounts.findByIdForUpdate(id)).thenReturn(Optional.of(account));
        when(staffProfiles.findById(id)).thenReturn(Optional.of(profile(id, branchId)));
        return account;
    }

    private static StaffProfile profile(long id, Long branchId) {
        StaffProfile profile = BeanUtils.instantiateClass(StaffProfile.class);
        ReflectionTestUtils.setField(profile, "accountId", id);
        ReflectionTestUtils.setField(profile, "fullName", "Nhân viên " + id);
        ReflectionTestUtils.setField(profile, "branchId", branchId);
        return profile;
    }

    private Account customer(long id, AccountStatus status) {
        Account account = Account.registerCustomer("khach" + id + "@petcare.test", "hash",
                status == AccountStatus.PENDING ? NOW.plusSeconds(3600) : null);
        ReflectionTestUtils.setField(account, "id", id);
        ReflectionTestUtils.setField(account, "status", status);
        when(accounts.findRoleById(id)).thenReturn(Optional.of(Role.CUSTOMER));
        when(accounts.findByIdForUpdate(id)).thenReturn(Optional.of(account));
        when(customers.findCustomerIdByAccountId(id)).thenReturn(Optional.of(CUSTOMER_ID));
        when(customers.findContact(CUSTOMER_ID)).thenReturn(Optional.of(new CustomerContact(CUSTOMER_ID,
                "Nguyễn Văn Khách", "0901111111", "khach" + id + "@petcare.test", id, false)));
        when(appointments.findBookedByCustomer(CUSTOMER_ID)).thenReturn(List.of());
        when(boarding.findActiveStaysByCustomer(CUSTOMER_ID)).thenReturn(List.of());
        when(orders.findUnpaidOrdersByCustomer(CUSTOMER_ID)).thenReturn(List.of());
        return account;
    }

    private void managers(long branchId, Long... ids) {
        activeStaff(branchId, Role.BRANCH_MANAGER, ids);
    }

    private void receptionists(long branchId, Long... ids) {
        activeStaff(branchId, Role.RECEPTIONIST, ids);
    }

    private void activeStaff(long branchId, Role role, Long... ids) {
        when(staffProfiles.findActiveStaffRows(branchId, role)).thenReturn(java.util.Arrays.stream(ids)
                .map(id -> new StaffRow(id, "NV " + id, role, branchId, AccountStatus.ACTIVE, false, null)).toList());
    }

    private AccountLockedEvent capturedEvent() {
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        return (AccountLockedEvent) event.getValue();
    }

    private List<NotificationRequest> capturedNotices(int count) {
        ArgumentCaptor<NotificationRequest> notice = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications, org.mockito.Mockito.times(count)).enqueue(notice.capture());
        return notice.getAllValues();
    }

    private AuditEntry capturedAudit() {
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(entry.capture());
        return entry.getValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> snapshot(Object value) {
        return (Map<String, Object>) value;
    }

    private void assertNoWrite() {
        verify(accounts, never()).flush();
        verify(accounts, never()).save(any());
        verifyNoInteractions(sessions, events, notifications, audit);
    }

    private static void assertRule(ThrowingCallable call, String ruleId, String message) {
        assertThatThrownBy(call).isInstanceOf(BusinessRuleViolationException.class).hasMessage(message)
                .hasMessageEndingWith("(" + ruleId + ")");
    }
}
