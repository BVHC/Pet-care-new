package com.petcare.module.appointment.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.api.BookingRestrictionApi;
import com.petcare.module.appointment.dto.AppointmentResponse;
import com.petcare.module.appointment.dto.CancelAppointmentRequest;
import com.petcare.module.appointment.dto.RescheduleAppointmentRequest;
import com.petcare.module.appointment.entity.Appointment;
import com.petcare.module.appointment.entity.AppointmentCancelSource;
import com.petcare.module.appointment.entity.AppointmentStatus;
import com.petcare.module.appointment.fsm.AppointmentTransitionHandler;
import com.petcare.module.appointment.mapper.AppointmentMapper;
import com.petcare.module.appointment.repository.AppointmentRepository;
import com.petcare.module.appointment.repository.AppointmentRepository.AccessRow;
import com.petcare.module.appointment.service.AppointmentResponder.Viewer;
import com.petcare.module.branch.api.BranchApi;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Đổi khung giờ (appointment-v1 #5, Lịch hẹn#2 {@code BOOKED → BOOKED}, BR-LH-02…06) và khách / lễ tân hủy (#6,
 * Lịch hẹn#4 {@code BOOKED → CANCELLED}, BR-LH-07, 09) — UC40, docs/adr/0049. Mỗi thao tác một transaction ghi, không
 * {@code noRollbackFor}, không {@code try/catch}: bị từ chối hay lỗi giữa chừng thì không giữ lại gì (docs/adr/0045 §4).
 * <ul>
 * <li>Phân quyền trước mọi khóa, trên ba cột không bao giờ đổi ({@link AccessRow}): khách — lịch có {@code customer_id}
 * là hồ sơ của mình (lịch của khách khác cùng 404); lễ tân — {@link BranchScope#check} chi nhánh của lịch (403).</li>
 * <li>Đổi khung khóa {@code advisory(thú) → branches → appointments} (docs/adr/0045); hủy chỉ khóa dòng
 * {@code appointments} (đoạn cuối của cùng thứ tự). Mọi rule kiểm dưới khóa, {@code now} lấy sau khóa.</li>
 * <li>Lịch không còn {@code BOOKED} → 409 ngay sau khi khóa, trước mọi rule 400 (Q6, lệch có chủ đích so với convention
 * 05).</li>
 * </ul>
 */
@Service
public class AppointmentChangeService {

    static final String CUSTOMER = "CUSTOMER";
    static final String RECEPTIONIST = "RECEPTIONIST";

    /** Tên đối tượng trong message 409, giống {@code AppointmentTransitionHandler.fsmName()}. */
    static final String FSM_NAME = "lịch hẹn";

    private final BranchScope branchScope;
    private final CustomerQueryApi customers;
    private final BranchApi branchLocks;
    private final BranchQueryApi branches;
    private final BookingRestrictionApi restrictions;
    private final SystemConfigApi configs;
    private final AppointmentLocks locks;
    private final AppointmentRepository appointments;
    private final AppointmentTransitionHandler transitions;
    private final AppointmentResponder responder;
    private final Clock clock;

    public AppointmentChangeService(BranchScope branchScope, CustomerQueryApi customers, PetQueryApi pets,
            CatalogQueryApi catalog, BranchApi branchLocks, BranchQueryApi branches,
            BookingRestrictionApi restrictions, SystemConfigApi configs, AppointmentLocks locks,
            AppointmentRepository appointments, AppointmentTransitionHandler transitions, AppointmentMapper mapper,
            Clock clock) {
        this.branchScope = branchScope;
        this.customers = customers;
        this.branchLocks = branchLocks;
        this.branches = branches;
        this.restrictions = restrictions;
        this.configs = configs;
        this.locks = locks;
        this.appointments = appointments;
        this.transitions = transitions;
        this.responder = new AppointmentResponder(customers, pets, branches, catalog, configs, mapper);
        this.clock = clock;
    }

    /** Lịch hẹn#2 — đổi khung giờ (BR-LH-06). */
    @Transactional
    public AppointmentResponse rescheduleAppointment(Long appointmentId, RescheduleAppointmentRequest request) {
        Actor actor = authorize(appointmentId);
        locks.lockPet(actor.access().getPetId());
        Long branchId = actor.access().getBranchId();
        branchLocks.lockForBooking(branchId)
                .orElseThrow(() -> new IllegalStateException("Lịch hẹn #" + appointmentId
                        + " trỏ tới chi nhánh không có: #" + branchId));
        Appointment appointment = lockAppointment(appointmentId);
        Instant now = clock.instant();

        transitions.validateTransition(appointment.getStatus(), AppointmentStatus.BOOKED);
        LocalDate date = request.slotDate();
        LocalTime start = request.slotStart();
        if (date.equals(appointment.getSlotDate()) && start.equals(appointment.getSlotStart())) {
            return responder.one(appointment, actor.viewer(), now);
        }
        checkRescheduleAllowed(appointment, actor.customer(), now);
        checkWindow(actor.customer(), now, date, start);
        if (!SlotGrid.isSlotStart(branches.openingRangesOn(branchId, date), start)) {
            throw new BusinessRuleViolationException("BR-LH-02",
                    "Khung giờ không hợp lệ: ngoài giờ mở cửa, ngày nghỉ hoặc không đúng mốc 30 phút");
        }
        checkPetLimits(appointment, date, start);
        ServiceGroup group = appointment.getServiceGroup();
        if (appointments.countActiveInSlot(branchId, group.name(), date, start)
                >= branches.quotaFor(branchId, group, date, start)) {
            throw new BusinessRuleViolationException("BR-LH-03", "Khung giờ đã hết chỗ");
        }

        appointment.reschedule(date, start);
        Appointment saved = appointments.saveAndFlush(appointment);
        return responder.one(saved, actor.viewer(), now);
    }

    /** Lịch hẹn#4 — khách / lễ tân hủy (BR-LH-07; hủy muộn → BR-LH-09). */
    @Transactional
    public AppointmentResponse cancelAppointment(Long appointmentId, CancelAppointmentRequest request) {
        Actor actor = authorize(appointmentId);
        Appointment appointment = lockAppointment(appointmentId);
        // µs như cột TIMESTAMPTZ: response = GET #4, starts_at của lần hạn chế = cancelled_at (docs/adr/0049).
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        // Tường minh, không qua FSM: cạnh CHECKED_IN → CANCELLED (#8) chỉ dành cho Visit#6.
        if (appointment.getStatus() != AppointmentStatus.BOOKED) {
            throw new InvalidStateTransitionException(FSM_NAME, appointment.getStatus().name(),
                    AppointmentStatus.CANCELLED.name());
        }
        Instant start = startOf(appointment);
        if (!now.isBefore(start)) {
            throw new BusinessRuleViolationException("BR-LH-07", "Đã tới giờ hẹn, không hủy được nữa");
        }
        boolean late = Duration.between(now, start)
                .compareTo(Duration.ofHours(configs.getInt(ConfigKey.APPOINTMENT_LATE_CANCEL_HOURS))) < 0;

        transitions.validateTransition(AppointmentStatus.BOOKED, AppointmentStatus.CANCELLED);
        appointment.cancel(actor.customer() ? AppointmentCancelSource.CUSTOMER : AppointmentCancelSource.STAFF,
                normalizeReason(request == null ? null : request.reason()), now, late);
        Appointment saved = appointments.saveAndFlush(appointment);
        if (late) {
            restrictions.evaluateAfterViolation(saved.getCustomerId(), now);
        }
        return responder.one(saved, actor.viewer(), now);
    }

    /** Người gọi đã qua {@code @PreAuthorize} (A02, A06) và được phép thao tác trên lịch này. */
    private record Actor(boolean customer, AccessRow access) {

        Viewer viewer() {
            return customer ? Viewer.CUSTOMER : Viewer.RECEPTIONIST;
        }
    }

    /** Phạm vi trước mọi khóa (docs/adr/0047): khách theo hồ sơ của mình, lễ tân theo chi nhánh của lịch. */
    private Actor authorize(Long appointmentId) {
        SecurityPrincipal principal = branchScope.current();
        boolean customer = switch (principal.role()) {
            case CUSTOMER -> true;
            case RECEPTIONIST -> false;
            default -> throw new IllegalStateException("Role không được đổi / hủy lịch hẹn: " + principal.role());
        };
        Long own = customer ? ownCustomerId(principal.accountId()) : null;
        AccessRow access = appointments.findAccessRow(appointmentId)
                .filter(found -> !customer || own.equals(found.getCustomerId()))
                .orElseThrow(() -> new ResourceNotFoundException("lịch hẹn", appointmentId));
        if (!customer) {
            branchScope.check(access.getBranchId());
        }
        return new Actor(customer, access);
    }

    /** BR-KH-01: tài khoản khách luôn có hồ sơ; không có là dữ liệu hỏng (500). */
    private Long ownCustomerId(Long accountId) {
        return customers.findCustomerIdByAccountId(accountId)
                .orElseThrow(() -> new IllegalStateException("Tài khoản khách #" + accountId + " không có hồ sơ khách"));
    }

    private Appointment lockAppointment(Long appointmentId) {
        return appointments.findByIdForUpdate(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("lịch hẹn", appointmentId));
    }

    /**
     * BR-LH-06 trên khung hiện tại: còn lượt đổi ({@code appointment.max_reschedules} [CFG], tính chung khách và lễ tân);
     * khách còn ít nhất {@code appointment.reschedule_min_lead_hours} [CFG] (đúng bằng vẫn được, như
     * {@link AppointmentActions}); lễ tân chỉ cần chưa tới giờ hẹn.
     */
    private void checkRescheduleAllowed(Appointment appointment, boolean customer, Instant now) {
        int maxReschedules = configs.getInt(ConfigKey.APPOINTMENT_MAX_RESCHEDULES);
        if (appointment.getRescheduleCount() >= maxReschedules) {
            throw new BusinessRuleViolationException("BR-LH-06", "Lịch hẹn đã đổi đủ " + maxReschedules
                    + " lần, muốn đổi nữa vui lòng hủy rồi đặt lại");
        }
        Instant start = startOf(appointment);
        if (customer) {
            int leadHours = configs.getInt(ConfigKey.APPOINTMENT_RESCHEDULE_MIN_LEAD_HOURS);
            if (now.plus(Duration.ofHours(leadHours)).isAfter(start)) {
                throw new BusinessRuleViolationException("BR-LH-06", "Chỉ đổi được lịch trước giờ hẹn ít nhất "
                        + leadHours + " giờ, vui lòng liên hệ chi nhánh");
            }
        } else if (!now.isBefore(start)) {
            throw new BusinessRuleViolationException("BR-LH-06", "Lịch hẹn đã tới giờ, không đổi được nữa");
        }
    }

    /** BR-LH-04 với khung mới qua {@link BookingWindow} (cùng message với đặt lịch, docs/adr/0047). */
    private void checkWindow(boolean customer, Instant now, LocalDate date, LocalTime start) {
        if (customer) {
            int minLeadHours = configs.getInt(ConfigKey.APPOINTMENT_MIN_LEAD_HOURS);
            int maxAdvanceDays = configs.getInt(ConfigKey.APPOINTMENT_MAX_ADVANCE_DAYS);
            if (!BookingWindow.forCustomer(now, minLeadHours, maxAdvanceDays).allows(date, start)) {
                throw new BusinessRuleViolationException("BR-LH-04", "Chỉ đặt được khung bắt đầu sau ít nhất "
                        + minLeadHours + " giờ và trong vòng " + maxAdvanceDays + " ngày tới");
            }
        } else if (!BookingWindow.forStaff(now).allows(date, start)) {
            throw new BusinessRuleViolationException("BR-LH-04", "Khung giờ đã bắt đầu");
        }
    }

    /**
     * BR-LH-05 với khung mới trên mọi chi nhánh, dưới khóa thú, không tính chính lịch này. Không đếm số lịch BOOKED: đổi
     * khung không làm tăng số lịch của thú.
     */
    private void checkPetLimits(Appointment appointment, LocalDate date, LocalTime start) {
        Long petId = appointment.getPetId();
        if (appointments.existsActiveSameSlotExcept(petId, date, start, appointment.getId())) {
            throw new BusinessRuleViolationException("BR-LH-05", "Thú cưng đã có lịch hẹn khác trùng khung giờ này");
        }
        ServiceGroup group = appointment.getServiceGroup();
        if (appointments.existsActiveSameGroupDayExcept(petId, group.name(), date, appointment.getId())) {
            throw new BusinessRuleViolationException("BR-LH-05",
                    "Thú cưng đã có lịch " + (group == ServiceGroup.MEDICAL ? "Khám/Tiêm" : "Thẩm mỹ")
                            + " trong ngày này");
        }
    }

    private static Instant startOf(Appointment appointment) {
        return appointment.getSlotDate().atTime(appointment.getSlotStart()).atZone(TimeConfig.BUSINESS_ZONE).toInstant();
    }

    /** Lý do bỏ khoảng trắng hai đầu; rỗng thì không lưu. */
    private static String normalizeReason(String reason) {
        if (reason == null) {
            return null;
        }
        String stripped = reason.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
