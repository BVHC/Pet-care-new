package com.petcare.module.appointment.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.dto.AppointmentListFilter;
import com.petcare.module.appointment.dto.AppointmentResponse;
import com.petcare.module.appointment.entity.Appointment;
import com.petcare.module.appointment.entity.AppointmentStatus;
import com.petcare.module.appointment.mapper.AppointmentMapper;
import com.petcare.module.appointment.repository.AppointmentRepository;
import com.petcare.module.appointment.service.AppointmentResponder.Viewer;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

import jakarta.persistence.criteria.Predicate;

/**
 * Xem lịch hẹn — appointment-v1 #3 (danh sách), #4 (chi tiết); UC40, UC44; BR-LH-11, BR-TN-02 (docs/adr/0048).
 * Chỉ đọc, không khóa: một transaction {@code readOnly} + {@code REPEATABLE_READ} để count, trang, tên và cờ cùng một
 * snapshot (khuôn docs/adr/0038, 0046).
 * <ul>
 * <li>Phạm vi: khách luôn bị ép {@code customer_id} = hồ sơ của tài khoản (chủ lúc đặt, không theo chủ hiện tại của
 * thú); nhân viên luôn bị ép chi nhánh qua {@link BranchScope} — {@code branchId} khác là 403 trước mọi lần đọc DB. Bộ
 * lọc người dùng gửi chỉ AND thêm vào phạm vi.</li>
 * <li>Chi tiết: không có, hoặc khách xem lịch của khách khác → cùng 404; nhân viên xem chi nhánh khác → 403.</li>
 * <li>Tên hiển thị lấy theo lô cho cả trang qua {@link AppointmentResponder} (mỗi module gọi một lần); trang rỗng không
 * gọi module nào.</li>
 * <li>{@code canReschedule} / {@code canCancel} theo người xem; BRANCH_MANAGER, VET, CARETAKER không có quyền đổi / hủy
 * (hợp đồng #5, #6) nên luôn {@link AppointmentActions.Flags#NONE}.</li>
 * </ul>
 */
@Service
public class AppointmentQueryService {

    static final int MAX_PAGE_SIZE = 100;

    /** Tăng dần theo giờ hẹn, hòa thì theo id (docs/adr/0048 §7). */
    static final Sort ORDER = Sort.by(Sort.Order.asc("slotDate"), Sort.Order.asc("slotStart"), Sort.Order.asc("id"));

    private final BranchScope branchScope;
    private final CustomerQueryApi customers;
    private final SystemConfigApi configs;
    private final AppointmentRepository appointments;
    private final AppointmentResponder responder;
    private final Clock clock;

    public AppointmentQueryService(BranchScope branchScope, CustomerQueryApi customers, PetQueryApi pets,
            BranchQueryApi branches, CatalogQueryApi catalog, VisitQueryApi visits, SystemConfigApi configs,
            AppointmentRepository appointments, AppointmentMapper mapper, Clock clock) {
        this.branchScope = branchScope;
        this.customers = customers;
        this.configs = configs;
        this.appointments = appointments;
        this.responder = new AppointmentResponder(customers, pets, branches, catalog, visits, configs, mapper);
        this.clock = clock;
    }

    /** appointment-v1 #3. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<AppointmentResponse> listAppointments(AppointmentListFilter filter, int page, int size) {
        SecurityPrincipal principal = branchScope.current();
        Viewer viewer = viewerOf(principal.role(), false);
        Specification<Appointment> scope;
        if (viewer == Viewer.CUSTOMER) {
            Long own = ownCustomerId(principal.accountId());
            scope = (root, query, cb) -> cb.equal(root.get("customerId"), own);
        } else {
            Long branchId = branchScope.resolve(filter.branchId());
            scope = (root, query, cb) -> cb.equal(root.get("branchId"), branchId);
        }

        Instant now = clock.instant();
        Specification<Appointment> spec = scope.and(filters(filter, viewer == Viewer.CUSTOMER, now));
        Page<Appointment> found = appointments.findAll(spec,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), ORDER));
        return PageResponse.of(found.map(responder.forRows(found.getContent(), viewer, now)));
    }

    /** appointment-v1 #4. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AppointmentResponse getAppointment(Long appointmentId) {
        SecurityPrincipal principal = branchScope.current();
        Viewer viewer = viewerOf(principal.role(), true);
        Long own = viewer == Viewer.CUSTOMER ? ownCustomerId(principal.accountId()) : null;
        Appointment appointment = appointments.findById(appointmentId)
                .filter(found -> own == null || own.equals(found.getCustomerId()))
                .orElseThrow(() -> new ResourceNotFoundException("lịch hẹn", appointmentId));
        if (viewer != Viewer.CUSTOMER) {
            branchScope.check(appointment.getBranchId());
        }
        return responder.one(appointment, viewer, clock.instant());
    }

    /** Hợp đồng #3: A02, A05, A06; #4 thêm A07, A08. Role khác đã bị {@code @PreAuthorize} chặn — tới đây là lỗi lập trình. */
    static Viewer viewerOf(String role, boolean detail) {
        return switch (role) {
            case "CUSTOMER" -> Viewer.CUSTOMER;
            case "RECEPTIONIST" -> Viewer.RECEPTIONIST;
            case "BRANCH_MANAGER" -> Viewer.READ_ONLY_STAFF;
            case "VET", "CARETAKER" -> {
                if (!detail) {
                    throw new IllegalStateException("Role không được xem danh sách lịch hẹn: " + role);
                }
                yield Viewer.READ_ONLY_STAFF;
            }
            default -> throw new IllegalStateException("Role không được xem lịch hẹn: " + role);
        };
    }

    /** BR-KH-01: tài khoản khách luôn có hồ sơ; không có là dữ liệu hỏng (500). */
    private Long ownCustomerId(Long accountId) {
        return customers.findCustomerIdByAccountId(accountId)
                .orElseThrow(() -> new IllegalStateException("Tài khoản khách #" + accountId + " không có hồ sơ khách"));
    }

    /** Chỉ ghép điều kiện được gửi; mọi điều kiện AND (docs/adr/0048 §5, 6). */
    private Specification<Appointment> filters(AppointmentListFilter filter, boolean customer, Instant now) {
        CheckInWindow window = filter.checkInReadyRequested()
                ? CheckInWindow.at(now, configs.getInt(ConfigKey.VISIT_CHECK_IN_EARLY_MINUTES),
                        configs.getInt(ConfigKey.APPOINTMENT_NO_SHOW_AFTER_MINUTES))
                : null;
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (customer && filter.branchId() != null) {
                predicates.add(cb.equal(root.get("branchId"), filter.branchId()));
            }
            if (filter.date() != null) {
                predicates.add(cb.equal(root.get("slotDate"), filter.date()));
            }
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("slotDate"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.<LocalDate>get("slotDate"), filter.to()));
            }
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.petId() != null) {
                predicates.add(cb.equal(root.get("petId"), filter.petId()));
            }
            if (filter.customerId() != null) {
                predicates.add(cb.equal(root.get("customerId"), filter.customerId()));
            }
            if (window != null) {
                predicates.add(cb.equal(root.get("status"), AppointmentStatus.BOOKED));
                predicates.add(cb.equal(root.get("slotDate"), window.date()));
                predicates.add(cb.greaterThanOrEqualTo(root.<LocalTime>get("slotStart"), window.earliestStart()));
                if (window.latestStart() != null) {
                    predicates.add(cb.lessThanOrEqualTo(root.<LocalTime>get("slotStart"), window.latestStart()));
                }
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
