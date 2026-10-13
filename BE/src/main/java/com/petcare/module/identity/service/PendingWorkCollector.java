package com.petcare.module.identity.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.petcare.module.appointment.api.AppointmentQueryApi;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.identity.dto.PendingWorkItem;
import com.petcare.module.identity.mapper.AccountAdminMapper;
import com.petcare.module.sales.api.CashierShiftQueryApi;
import com.petcare.module.sales.api.OrderQueryApi;
import com.petcare.module.visit.api.VisitQueryApi;

/**
 * Quy trình dở dang của tài khoản sắp bị khóa (BR-QT-11, BR-TN-08, 06-module-contracts §4, §8 Q2 — docs/adr/0035). Chỉ
 * đọc qua các query API của module khác, trong transaction của {@link AccountLockService} và <b>trước mọi lệnh ghi</b>
 * của nó: interface nào còn là placeholder thì ném lỗi → 500, không có gì đổi (nợ D001, D010, D016). Không bắt lỗi nào.
 * Kết quả sắp theo {@link PendingWorkItem.PendingWorkType} (thứ tự contract: lượt cần gán lại lên đầu) rồi
 * {@code entityId}, nên danh sách là xác định.
 */
@Component
public class PendingWorkCollector {

    static final Comparator<PendingWorkItem> ORDER =
            Comparator.comparing(PendingWorkItem::type).thenComparing(PendingWorkItem::entityId);

    private final CustomerQueryApi customers;
    private final AppointmentQueryApi appointments;
    private final BoardingQueryApi boarding;
    private final OrderQueryApi orders;
    private final VisitQueryApi visits;
    private final CashierShiftQueryApi cashierShifts;
    private final AccountAdminMapper mapper;

    public PendingWorkCollector(CustomerQueryApi customers, AppointmentQueryApi appointments,
            BoardingQueryApi boarding, OrderQueryApi orders, VisitQueryApi visits, CashierShiftQueryApi cashierShifts,
            AccountAdminMapper mapper) {
        this.customers = customers;
        this.appointments = appointments;
        this.boarding = boarding;
        this.orders = orders;
        this.visits = visits;
        this.cashierShifts = cashierShifts;
        this.mapper = mapper;
    }

    /** Hồ sơ khách của tài khoản và việc dở của hồ sơ đó. */
    public record CustomerWork(Long customerId, String fullName, List<PendingWorkItem> items) {}

    /**
     * Nhân viên A05–A08 (mọi {@code status}): lượt tiếp nhận được gán chưa kết thúc và ca thu ngân đang mở — hỏi cả
     * hai với mọi chức vụ, như BR-QT-08 (docs/adr/0031).
     */
    public List<PendingWorkItem> forBranchStaff(Long accountId) {
        List<PendingWorkItem> items = new ArrayList<>();
        visits.findUnfinishedVisitsAssignedTo(accountId).forEach(visit -> items.add(mapper.fromVisit(visit)));
        cashierShifts.findOpenShift(accountId).ifPresent(shift -> items.add(mapper.fromShift(shift)));
        return sorted(items);
    }

    /**
     * Khách (06 §8 Q2): lịch hẹn {@code BOOKED}, đặt chỗ {@code BOOKED}/{@code CHECKED_IN}/{@code OVERDUE}, Order
     * {@code OPEN}/{@code PENDING}. Visit đang mở luôn có Order {@code OPEN}/{@code PENDING} nên đã nằm trong nhóm cuối.
     * Tài khoản khách luôn có hồ sơ (BR-KH-01); thiếu là dữ liệu hỏng → 500, như {@code MeService}.
     */
    public CustomerWork forCustomer(Long accountId) {
        Long customerId = customers.findCustomerIdByAccountId(accountId)
                .orElseThrow(() -> new IllegalStateException(
                        "Customer account " + accountId + " has no customer profile (BR-KH-01)"));
        CustomerContact contact = customers.findContact(customerId)
                .orElseThrow(() -> new IllegalStateException("Customer " + customerId + " not readable"));
        List<PendingWorkItem> items = new ArrayList<>();
        appointments.findBookedByCustomer(customerId).forEach(slot -> items.add(mapper.fromAppointment(slot)));
        boarding.findActiveStaysByCustomer(customerId).forEach(stay -> items.add(mapper.fromStay(stay)));
        orders.findUnpaidOrdersByCustomer(customerId).forEach(order -> items.add(mapper.fromOrder(order)));
        return new CustomerWork(customerId, contact.fullName(), sorted(items));
    }

    private static List<PendingWorkItem> sorted(List<PendingWorkItem> items) {
        items.sort(ORDER);
        return List.copyOf(items);
    }
}
