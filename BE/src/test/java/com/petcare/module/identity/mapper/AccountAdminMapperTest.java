package com.petcare.module.identity.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.appointment.api.AppointmentQueryApi.BookedSlot;
import com.petcare.module.boarding.api.BoardingQueryApi.StaySummary;
import com.petcare.module.boarding.api.BoardingQueryApi.StayStatus;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.AccountSummary;
import com.petcare.module.identity.dto.LockResult;
import com.petcare.module.identity.dto.PendingWorkItem;
import com.petcare.module.identity.dto.PendingWorkItem.PendingWorkType;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.repository.AccountSummaryRow;
import com.petcare.module.sales.api.CashierShiftQueryApi.OpenShift;
import com.petcare.module.sales.api.OrderQueryApi.UnpaidOrderRef;
import com.petcare.module.visit.api.VisitQueryApi.AssignedVisit;
import com.petcare.module.visit.api.VisitQueryApi.VisitStatus;

/**
 * {@code AccountSummary}, {@code LockResult}, {@code PendingWorkItem} (identity-v1 #28, #29; docs/adr/0035): mỗi trường
 * lấy đúng một nguồn — dữ liệu có giá trị riêng cho từng trường, so cả record, nên lấy nhầm nguồn là đỏ.
 */
class AccountAdminMapperTest {

    private final LoginMapper loginMapper = Mappers.getMapper(LoginMapper.class);
    private final AccountAdminMapper mapper = adminMapper();

    private AccountAdminMapper adminMapper() {
        AccountAdminMapper result = Mappers.getMapper(AccountAdminMapper.class);
        ReflectionTestUtils.setField(result, "loginMapper", loginMapper);
        return result;
    }

    @Test
    void summaryRowEveryFieldFromItsOwnSource() {
        assertThat(mapper.toAccountSummary(new AccountSummaryRow(11L, "vet@petcare.test", Role.VET,
                AccountStatus.DISABLED, true, false)))
                .isEqualTo(new AccountSummary(11L, "vet@petcare.test", Role.VET, AccountStatus.DISABLED, true, false));
        assertThat(mapper.toAccountSummary(new AccountSummaryRow(12L, "khach@petcare.test", Role.CUSTOMER,
                AccountStatus.PENDING, false, true)))
                .isEqualTo(new AccountSummary(12L, "khach@petcare.test", Role.CUSTOMER, AccountStatus.PENDING, false,
                        true));
    }

    @Test
    void lockResultAccountGoesThroughLoginMapperAndListIsCopied() {
        Account account = Account.createStaff("rc@petcare.test", "0901234567", "hash", Role.RECEPTIONIST);
        ReflectionTestUtils.setField(account, "id", 13L);
        account.lock("Vi phạm");
        List<PendingWorkItem> work = new ArrayList<>(List.of(
                new PendingWorkItem(PendingWorkType.CASHIER_SHIFT_OPEN, 5L, 3L, false)));

        LockResult result = mapper.toLockResult(account, work);
        work.clear();

        assertThat(result.account()).isEqualTo(loginMapper.toAccountSummary(account))
                .isEqualTo(new AccountSummary(13L, "rc@petcare.test", Role.RECEPTIONIST, AccountStatus.ACTIVE, true,
                        true));
        assertThat(result.pendingWork()).containsExactly(
                new PendingWorkItem(PendingWorkType.CASHIER_SHIFT_OPEN, 5L, 3L, false));
        assertThat(mapper.toLockResult(account, List.of()).pendingWork()).isNotNull().isEmpty();
    }

    /** BR-TN-08: chỉ lượt đã gọi (IN_PROGRESS) cần gán lại. */
    @Test
    void visitTypeAndNeedsReassignFollowStatus() {
        assertThat(mapper.fromVisit(new AssignedVisit(21L, 31L, VisitStatus.WAITING)))
                .isEqualTo(new PendingWorkItem(PendingWorkType.VISIT_WAITING, 21L, 31L, false));
        assertThat(mapper.fromVisit(new AssignedVisit(22L, 32L, VisitStatus.IN_PROGRESS)))
                .isEqualTo(new PendingWorkItem(PendingWorkType.VISIT_IN_PROGRESS, 22L, 32L, true));
    }

    @ParameterizedTest
    @EnumSource(value = VisitStatus.class, names = {"COMPLETED", "CANCELLED"})
    void finishedVisitStatusIsRejected(VisitStatus status) {
        assertThatThrownBy(() -> mapper.fromVisit(new AssignedVisit(23L, 33L, status)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void otherSourcesUseTheirOwnIdAndBranch() {
        assertThat(mapper.fromShift(new OpenShift(41L, 51L)))
                .isEqualTo(new PendingWorkItem(PendingWorkType.CASHIER_SHIFT_OPEN, 41L, 51L, false));
        assertThat(mapper.fromAppointment(new BookedSlot(42L, "LH-260115-0007", 52L, 62L, 72L,
                LocalDate.of(2026, 10, 20), LocalTime.of(9, 30))))
                .isEqualTo(new PendingWorkItem(PendingWorkType.APPOINTMENT_BOOKED, 42L, 52L, false));
        assertThat(mapper.fromStay(new StaySummary(43L, 53L, 73L, 63L, StayStatus.OVERDUE, "0909999999")))
                .isEqualTo(new PendingWorkItem(PendingWorkType.BOARDING_ACTIVE, 43L, 53L, false));
        assertThat(mapper.fromOrder(new UnpaidOrderRef(44L, 54L)))
                .isEqualTo(new PendingWorkItem(PendingWorkType.ORDER_UNPAID, 44L, 54L, false));
    }
}
