package com.petcare.module.visit.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.api.AppointmentApi;
import com.petcare.module.appointment.api.AppointmentApi.CheckInInfo;
import com.petcare.module.appointment.api.AppointmentQueryApi;
import com.petcare.module.appointment.api.AppointmentQueryApi.CheckInRef;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.catalog.api.MedicalType;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.sales.api.VisitOrderApi;
import com.petcare.module.sales.api.VisitOrderApi.LineRef;
import com.petcare.module.visit.api.FollowUpApi;
import com.petcare.module.visit.dto.CreateVisitRequest;
import com.petcare.module.visit.dto.VisitResponse;
import com.petcare.module.visit.entity.Visit;
import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.module.visit.fsm.VisitTransitionHandler;
import com.petcare.module.visit.mapper.VisitMapper;
import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Visit#1 — tiếp nhận (command {@code checkInVisit}, UC44, UC47; visit-v1 #1; docs/adr/0059). Một transaction: Visit
 * {@code WAITING} + Lịch hẹn#3 ({@code AppointmentApi.checkIn}) + Order#1 ({@code VisitOrderApi.openForVisit}).
 * <p>
 * Thứ tự (docs/api/00-method.md L70, docs/adr/0059): khóa thú (404) → lịch hẹn không khóa (404, chi nhánh khác 400
 * BR-TN-02) → dịch vụ walk-in (404) → các 400 của visit (BR-TN-01, BR-CN-01, BR-CN-05, BR-LH-01) → {@code checkIn}
 * (409 → 400 BR-TN-02, lệch sẵn có của docs/adr/0050) → mã lượt → INSERT {@code visits} → Order. Mọi 400 của visit
 * đứng trước câu ghi đầu tiên. Thứ tự khóa: {@code pets FOR SHARE → appointments → document_counters (visits → ORDER)
 * → orders} (docs/adr/0058 mục 5). Không bắt exception nào: lỗi từ callee {@code MANDATORY} đã đánh dấu rollback-only.
 * Dịch vụ tự sinh loại {@code EXAM}: sau {@code openForVisit} gọi {@code FollowUpApi.cancelPendingFollowUps} (Care Task#6,
 * BR-TB-06; docs/adr/0084, trả D029), khóa thêm {@code medical_records → care_tasks} sau {@code orders}. Không audit,
 * không thông báo (08 §8.3).
 */
@Service
public class VisitCheckInService {

    /** {@code visits.service_group} chỉ nhận hai nhóm này (erd §6); Lưu trú không tiếp nhận qua Visit. */
    private static final Set<ServiceGroup> VISIT_GROUPS = Set.of(ServiceGroup.MEDICAL, ServiceGroup.GROOMING);

    private final BranchScope branchScope;
    private final PetQueryApi pets;
    private final AppointmentQueryApi appointmentQueries;
    private final AppointmentApi appointments;
    private final CatalogQueryApi catalog;
    private final BranchQueryApi branches;
    private final CustomerQueryApi customers;
    private final VisitOrderApi visitOrders;
    private final FollowUpApi followUps;
    private final SystemConfigApi configs;
    private final VisitCodeGenerator codes;
    private final VisitRepository visits;
    private final VisitTransitionHandler transitions;
    private final VisitMapper mapper;
    private final Clock clock;

    public VisitCheckInService(BranchScope branchScope, PetQueryApi pets, AppointmentQueryApi appointmentQueries,
            AppointmentApi appointments, CatalogQueryApi catalog, BranchQueryApi branches, CustomerQueryApi customers,
            VisitOrderApi visitOrders, FollowUpApi followUps, SystemConfigApi configs, VisitCodeGenerator codes,
            VisitRepository visits,
            VisitTransitionHandler transitions, VisitMapper mapper, Clock clock) {
        this.branchScope = branchScope;
        this.pets = pets;
        this.appointmentQueries = appointmentQueries;
        this.appointments = appointments;
        this.catalog = catalog;
        this.branches = branches;
        this.customers = customers;
        this.visitOrders = visitOrders;
        this.followUps = followUps;
        this.configs = configs;
        this.codes = codes;
        this.visits = visits;
        this.transitions = transitions;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public VisitResponse checkInVisit(CreateVisitRequest request) {
        SecurityPrincipal principal = branchScope.current();
        Long branchId = branchScope.resolve(null);
        Long appointmentId = request.appointmentId();

        PetSummary pet = pets.findPetForShare(request.petId())
                .orElseThrow(() -> new ResourceNotFoundException("thú cưng", request.petId()));
        CheckInRef ref = null;
        if (appointmentId != null) {
            ref = appointmentQueries.findCheckInRef(appointmentId)
                    .orElseThrow(() -> new ResourceNotFoundException("lịch hẹn", appointmentId));
            if (!ref.branchId().equals(branchId)) {
                // Trước mọi guard khác: message của chúng không được lộ thú / dịch vụ của lịch chi nhánh khác.
                throw new BusinessRuleViolationException("BR-TN-02", "Lịch hẹn thuộc chi nhánh khác");
            }
        }
        Long serviceId = ref != null ? ref.serviceId() : request.serviceId();
        ServiceInfo service = serviceId == null ? null : findService(serviceId, ref != null);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        if (service == null) {
            throw new BusinessRuleViolationException("BR-TN-01", "Khách không hẹn trước phải chọn dịch vụ");
        }
        if (ref != null && !ref.petId().equals(pet.petId())) {
            throw new BusinessRuleViolationException("BR-TN-01", "Thú cưng không khớp lịch hẹn");
        }
        if (pet.deceased()) {
            throw new BusinessRuleViolationException("BR-TN-01", "Thú cưng đã mất, không tiếp nhận được");
        }
        BranchSummary branch = branches.findBranch(branchId)
                .orElseThrow(() -> new IllegalStateException("Chi nhánh của nhân viên không tồn tại: " + branchId));
        if (branch.status() != BranchStatus.ACTIVE) {
            throw new BusinessRuleViolationException("BR-CN-01", "Chi nhánh chưa hoạt động, chưa tiếp nhận được");
        }
        checkOpeningHours(branch, now, request.emergency());
        checkService(service, ref != null, branchId);

        CheckInInfo info = null;
        if (ref != null) {
            info = appointments.checkIn(appointmentId, branchId, now);
            if (!info.customerId().equals(pet.customerId())) {
                // Chuyển chủ hủy lịch BOOKED trong cùng transaction và ta đang giữ khóa thú: lệch là dữ liệu hỏng.
                throw new IllegalStateException("Lịch hẹn #" + appointmentId + " của khách #" + info.customerId()
                        + " nhưng thú #" + pet.petId() + " thuộc khách #" + pet.customerId());
            }
        }
        ServiceGroup group = info != null ? info.serviceGroup() : service.group();
        QueuePlacement placement = QueuePlacement.of(request.emergency(), info != null ? info.scheduledAt() : null,
                now, configs.getInt(ConfigKey.VISIT_LATE_PRIORITY_MINUTES));

        transitions.validateInitial(VisitStatus.WAITING);
        Visit visit = visits.saveAndFlush(Visit.checkIn(codes.nextCode(), branchId, pet.customerId(), pet.petId(),
                appointmentId, group, placement.priorityClass(), placement.queueSortKey(), principal.accountId(), now));
        LineRef order = visitOrders.openForVisit(visit.getId(), branchId, pet.customerId(), service.serviceId(), null,
                principal.accountId());
        if (service.medicalType() == MedicalType.EXAM) {
            followUps.cancelPendingFollowUps(pet.petId(), now);
        }

        CustomerContact contact = customers.findContact(pet.customerId())
                .orElseThrow(() -> new IllegalStateException("Thú #" + pet.petId() + " trỏ tới hồ sơ khách không có: "
                        + pet.customerId()));
        return mapper.toResponse(visit, new VisitMapper.Parties(contact.fullName(), pet.name(), pet.species(), null),
                order.orderId());
    }

    /** Walk-in: 404. Có lịch: {@code appointments.service_id} có FK nên thiếu là dữ liệu hỏng. */
    private ServiceInfo findService(Long serviceId, boolean fromAppointment) {
        return catalog.findService(serviceId).orElseThrow(() -> fromAppointment
                ? new IllegalStateException("Lịch hẹn trỏ tới dịch vụ không có: " + serviceId)
                : new ResourceNotFoundException("dịch vụ", serviceId));
    }

    /** BR-CN-05 (03 #1): ngoài giờ mở cửa / ngày nghỉ chỉ chi nhánh có cờ, và chỉ ca đánh dấu cấp cứu. */
    private void checkOpeningHours(BranchSummary branch, Instant now, boolean emergency) {
        if (branches.isOpenAt(branch.branchId(), LocalDateTime.ofInstant(now, TimeConfig.BUSINESS_ZONE))) {
            return;
        }
        if (!branch.acceptsAfterHoursEmergency()) {
            throw new BusinessRuleViolationException("BR-CN-05",
                    "Ngoài giờ mở cửa, chi nhánh không nhận cấp cứu ngoài giờ");
        }
        if (!emergency) {
            throw new BusinessRuleViolationException("BR-CN-05", "Ngoài giờ mở cửa chỉ tiếp nhận ca cấp cứu");
        }
    }

    /**
     * BR-LH-01. Walk-in: dịch vụ đang kinh doanh, nhóm Khám/Tiêm hoặc Thẩm mỹ, đang bật tại chi nhánh. Có lịch: chỉ
     * chặn khi dịch vụ đã ngừng hẳn ({@code openForVisit} cần dịch vụ đang kinh doanh); tắt tại chi nhánh sau khi đặt vẫn
     * tiếp nhận được (docs/adr/0059 Q3, nợ D040).
     */
    private void checkService(ServiceInfo service, boolean fromAppointment, Long branchId) {
        if (fromAppointment) {
            if (!service.active()) {
                throw new BusinessRuleViolationException("BR-LH-01", "Dịch vụ của lịch hẹn đã ngừng kinh doanh");
            }
            return;
        }
        if (!service.active() || !VISIT_GROUPS.contains(service.group())
                || !branches.isServiceEnabled(branchId, service.serviceId())) {
            throw new BusinessRuleViolationException("BR-LH-01", "Dịch vụ không tiếp nhận được tại chi nhánh này");
        }
    }
}
