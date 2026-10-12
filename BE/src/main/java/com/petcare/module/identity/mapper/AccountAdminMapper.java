package com.petcare.module.identity.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.ValueMapping;

import com.petcare.module.appointment.api.AppointmentQueryApi.BookedSlot;
import com.petcare.module.boarding.api.BoardingQueryApi.StaySummary;
import com.petcare.module.identity.dto.AccountSummary;
import com.petcare.module.identity.dto.LockResult;
import com.petcare.module.identity.dto.PendingWorkItem;
import com.petcare.module.identity.dto.PendingWorkItem.PendingWorkType;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.repository.AccountSummaryRow;
import com.petcare.module.sales.api.CashierShiftQueryApi.OpenShift;
import com.petcare.module.sales.api.OrderQueryApi.UnpaidOrderRef;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.module.visit.api.VisitQueryApi.AssignedVisit;

/**
 * Tìm / khóa / mở khóa tài khoản (identity-v1 #28, #29; UC09 — docs/adr/0035). Mọi trường đích khai báo tường minh và
 * {@code unmappedTargetPolicy = ERROR}: thiếu một trường của contract là lỗi biên dịch. {@code account} của
 * {@link LockResult} (và response #30) đi qua {@link LoginMapper#toAccountSummary(Account)} — cùng một đường map với
 * đăng nhập, {@code GET /me}. Việc dở dang: id nội bộ của từng nguồn làm {@code entityId}; chỉ lượt đã gọi cần gán lại
 * (BR-TN-08).
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR, uses = LoginMapper.class)
public interface AccountAdminMapper {

    /** identity-v1 #28 — projection không có {@code password_hash}, {@code locked_reason}, {@code last_seen_at}. */
    @Mapping(target = "id", source = "id")
    @Mapping(target = "email", source = "email")
    @Mapping(target = "role", source = "role")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "isLocked", source = "locked")
    @Mapping(target = "mustChangePassword", source = "mustChangePassword")
    AccountSummary toAccountSummary(AccountSummaryRow row);

    /** identity-v1 #29 — {@code account} là entity đã khóa và flush, dưới khóa dòng. */
    @Mapping(target = "account", source = "account")
    @Mapping(target = "pendingWork", source = "pendingWork")
    LockResult toLockResult(Account account, List<PendingWorkItem> pendingWork);

    @Mapping(target = "type", source = "status")
    @Mapping(target = "entityId", source = "visitId")
    @Mapping(target = "branchId", source = "branchId")
    @Mapping(target = "needsReassign",
            expression = "java(visit.status() == com.petcare.module.visit.api.VisitQueryApi.VisitStatus.IN_PROGRESS)")
    PendingWorkItem fromVisit(AssignedVisit visit);

    /**
     * {@code VisitQueryApi.findUnfinishedVisitsAssignedTo} chỉ hứa WAITING / IN_PROGRESS; giá trị khác là lỗi của bên
     * cài → ném, không âm thầm gán sai loại.
     */
    @ValueMapping(source = "WAITING", target = "VISIT_WAITING")
    @ValueMapping(source = "IN_PROGRESS", target = "VISIT_IN_PROGRESS")
    @ValueMapping(source = MappingConstants.ANY_REMAINING, target = MappingConstants.THROW_EXCEPTION)
    PendingWorkType visitType(VisitQueryApi.VisitStatus status);

    @Mapping(target = "type", constant = "CASHIER_SHIFT_OPEN")
    @Mapping(target = "entityId", source = "shiftId")
    @Mapping(target = "branchId", source = "branchId")
    @Mapping(target = "needsReassign", constant = "false")
    PendingWorkItem fromShift(OpenShift shift);

    @Mapping(target = "type", constant = "APPOINTMENT_BOOKED")
    @Mapping(target = "entityId", source = "appointmentId")
    @Mapping(target = "branchId", source = "branchId")
    @Mapping(target = "needsReassign", constant = "false")
    PendingWorkItem fromAppointment(BookedSlot appointment);

    @Mapping(target = "type", constant = "BOARDING_ACTIVE")
    @Mapping(target = "entityId", source = "bookingId")
    @Mapping(target = "branchId", source = "branchId")
    @Mapping(target = "needsReassign", constant = "false")
    PendingWorkItem fromStay(StaySummary stay);

    @Mapping(target = "type", constant = "ORDER_UNPAID")
    @Mapping(target = "entityId", source = "orderId")
    @Mapping(target = "branchId", source = "branchId")
    @Mapping(target = "needsReassign", constant = "false")
    PendingWorkItem fromOrder(UnpaidOrderRef order);
}
