package com.petcare.module.appointment.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.api.BookingRestrictionApi;
import com.petcare.module.appointment.dto.AppointmentResponse;
import com.petcare.module.appointment.dto.BookAppointmentRequest;
import com.petcare.module.appointment.entity.Appointment;
import com.petcare.module.appointment.entity.AppointmentChannel;
import com.petcare.module.appointment.entity.AppointmentStatus;
import com.petcare.module.appointment.fsm.AppointmentTransitionHandler;
import com.petcare.module.appointment.mapper.AppointmentMapper;
import com.petcare.module.appointment.repository.AppointmentRepository;
import com.petcare.module.branch.api.BranchApi;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.visit.api.FollowUpApi;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Đặt lịch hẹn — appointment-v1 #2, Lịch hẹn#1 {@code — → BOOKED} (UC39; BR-LH-01…05, 09, BR-KH-05, BR-TK-19;
 * docs/adr/0045 §4, 0047). Một transaction ghi, không {@code noRollbackFor}: bị từ chối thì không giữ lại gì.
 * <p>
 * Thứ tự (docs/adr/0047): phạm vi (lễ tân: chi nhánh của mình, trước mọi lần đọc DB; khách: hồ sơ của tài khoản) →
 * {@code lockPet} → đọc thú <b>một lần</b> (404, chủ — thú của khách khác cùng 404 —, đã mất cùng một snapshot) → dịch vụ
 * (404) → {@code lockForBooking} (404; khách + chi nhánh DRAFT cũng 404) → {@code now} → BR-LH-01 → BR-TK-19, BR-LH-09
 * (chỉ khách) → BR-KH-05 → BR-LH-04 → BR-LH-02 → BR-LH-05 → BR-LH-03 → mã → INSERT. Mọi lần đọc lịch chi nhánh
 * ({@code BranchQueryApi.*}), mọi phép đếm và {@code now} đều sau hai khóa. Nhóm {@code MEDICAL}: sau INSERT gọi
 * {@code FollowUpApi.cancelPendingFollowUps} (Care Task#6, BR-TB-06; docs/adr/0084, trả D029) — khóa thêm
 * {@code medical_records → care_tasks}, luôn sau {@code advisory → branches → appointments}. Không audit, không thông báo.
 */
@Service
public class AppointmentBookingService {

    static final String CUSTOMER = "CUSTOMER";
    static final String RECEPTIONIST = "RECEPTIONIST";

    /** Nhóm đặt được qua UC39 (BR-LH-01); Lưu trú đặt qua UC58. */
    private static final Set<ServiceGroup> BOOKABLE = Set.of(ServiceGroup.MEDICAL, ServiceGroup.GROOMING);

    /**
     * Thời điểm hết hạn chế trong message BR-LH-09 (02 L130 "kèm ngày hết hạn chế"; docs/adr/0055): giờ Việt Nam, có cả
     * giờ vì {@code ends_at} là một thời điểm trong ngày — chỉ ghi ngày thì khách đặt lại sớm hơn lúc được phép.
     */
    static final DateTimeFormatter RESTRICTION_END = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")
            .withZone(TimeConfig.BUSINESS_ZONE);

    private final BranchScope branchScope;
    private final CustomerQueryApi customers;
    private final PetQueryApi pets;
    private final CatalogQueryApi catalog;
    private final BranchApi branchLocks;
    private final BranchQueryApi branches;
    private final BookingRestrictionApi restrictions;
    private final FollowUpApi followUps;
    private final SystemConfigApi configs;
    private final AppointmentLocks locks;
    private final AppointmentCodeGenerator codes;
    private final AppointmentRepository appointments;
    private final AppointmentTransitionHandler transitions;
    private final AppointmentMapper mapper;
    private final Clock clock;

    public AppointmentBookingService(BranchScope branchScope, CustomerQueryApi customers, PetQueryApi pets,
            CatalogQueryApi catalog, BranchApi branchLocks, BranchQueryApi branches,
            BookingRestrictionApi restrictions, FollowUpApi followUps, SystemConfigApi configs, AppointmentLocks locks,
            AppointmentCodeGenerator codes, AppointmentRepository appointments,
            AppointmentTransitionHandler transitions, AppointmentMapper mapper, Clock clock) {
        this.branchScope = branchScope;
        this.customers = customers;
        this.pets = pets;
        this.catalog = catalog;
        this.branchLocks = branchLocks;
        this.branches = branches;
        this.restrictions = restrictions;
        this.followUps = followUps;
        this.configs = configs;
        this.locks = locks;
        this.codes = codes;
        this.appointments = appointments;
        this.transitions = transitions;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public AppointmentResponse bookAppointment(BookAppointmentRequest request) {
        SecurityPrincipal principal = branchScope.current();
        boolean customer = switch (principal.role()) {
            case CUSTOMER -> true;
            case RECEPTIONIST -> {
                branchScope.check(request.branchId());
                yield false;
            }
            default -> throw new IllegalStateException("Role không được đặt lịch hẹn: " + principal.role());
        };
        Long ownCustomerId = customer ? ownCustomerId(principal.accountId()) : null;

        locks.lockPet(request.petId());
        PetSummary pet = pets.findPet(request.petId())
                .filter(found -> !customer || found.customerId().equals(ownCustomerId))
                .orElseThrow(() -> new ResourceNotFoundException("thú cưng", request.petId()));
        ServiceInfo service = catalog.findService(request.serviceId())
                .orElseThrow(() -> new ResourceNotFoundException("dịch vụ", request.serviceId()));
        BranchStatus branchStatus = branchLocks.lockForBooking(request.branchId())
                .filter(found -> !customer || found == BranchStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("chi nhánh", request.branchId()));
        Instant now = clock.instant();

        Long branchId = request.branchId();
        LocalDate date = request.slotDate();
        LocalTime start = request.slotStart();
        if (branchStatus != BranchStatus.ACTIVE) {
            throw new BusinessRuleViolationException("BR-LH-01", "Chi nhánh chưa nhận đặt lịch");
        }
        if (!service.active() || !BOOKABLE.contains(service.group())
                || !branches.isServiceEnabled(branchId, service.serviceId())) {
            throw new BusinessRuleViolationException("BR-LH-01", "Dịch vụ không đặt được tại chi nhánh này");
        }
        CustomerContact contact = customers.findContact(pet.customerId())
                .orElseThrow(() -> new IllegalStateException("Thú #" + pet.petId() + " trỏ tới hồ sơ khách không có: "
                        + pet.customerId()));
        if (customer && contact.linkDecisionPending()) {
            throw new BusinessRuleViolationException("BR-TK-19",
                    "Hồ sơ của bạn đang chờ quyết định liên kết với hồ sơ tại phòng khám");
        }
        if (customer) {
            Optional<Instant> restrictedUntil = restrictions.findActiveUntil(ownCustomerId, now);
            if (restrictedUntil.isPresent()) {
                throw new BusinessRuleViolationException("BR-LH-09", "Bạn đang bị hạn chế đặt lịch online đến "
                        + RESTRICTION_END.format(restrictedUntil.get()) + ", vui lòng liên hệ chi nhánh để đặt lịch");
            }
        }
        if (pet.deceased()) {
            throw new BusinessRuleViolationException("BR-KH-05", "Thú cưng đã mất, không đặt được lịch");
        }
        checkWindow(customer, now, date, start);
        if (!SlotGrid.isSlotStart(branches.openingRangesOn(branchId, date), start)) {
            throw new BusinessRuleViolationException("BR-LH-02",
                    "Khung giờ không hợp lệ: ngoài giờ mở cửa, ngày nghỉ hoặc không đúng mốc 30 phút");
        }
        checkPetLimits(pet.petId(), service.group(), date, start);
        if (appointments.countActiveInSlot(branchId, service.group().name(), date, start)
                >= branches.quotaFor(branchId, service.group(), date, start)) {
            throw new BusinessRuleViolationException("BR-LH-03", "Khung giờ đã hết chỗ");
        }

        transitions.validateInitial(AppointmentStatus.BOOKED);
        Appointment saved = appointments.saveAndFlush(Appointment.book(codes.nextCode(), pet.customerId(), pet.petId(),
                branchId, service.serviceId(), service.group(), date, start,
                customer ? AppointmentChannel.ONLINE : AppointmentChannel.COUNTER, principal.accountId(),
                normalizeNote(request.note())));
        if (service.group() == ServiceGroup.MEDICAL) {
            followUps.cancelPendingFollowUps(pet.petId(), now);
        }

        String branchName = branches.findBranch(branchId)
                .orElseThrow(() -> new IllegalStateException("Chi nhánh đã khóa nhưng không đọc được: " + branchId))
                .name();
        AppointmentActions.Flags flags = AppointmentActions.forViewer(saved, customer, now,
                configs.getInt(ConfigKey.APPOINTMENT_MAX_RESCHEDULES),
                configs.getInt(ConfigKey.APPOINTMENT_RESCHEDULE_MIN_LEAD_HOURS));
        // Lịch vừa đặt là BOOKED, chưa thể có Visit: visitId null (docs/adr/0058).
        return mapper.toResponse(saved,
                new AppointmentMapper.Names(contact.fullName(), pet.name(), branchName, service.name()), flags, null);
    }

    /** BR-KH-01: tài khoản khách luôn có hồ sơ; không có là dữ liệu hỏng (500), không phải lỗi của người dùng. */
    private Long ownCustomerId(Long accountId) {
        return customers.findCustomerIdByAccountId(accountId)
                .orElseThrow(() -> new IllegalStateException("Tài khoản khách #" + accountId + " không có hồ sơ khách"));
    }

    /** BR-LH-04 qua {@link BookingWindow} (docs/adr/0046), với {@code now} lấy sau khi đã khóa. */
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

    /** BR-LH-05 trên mọi chi nhánh, dưới khóa thú: (a) số lịch BOOKED, (b) trùng khung, (c) trùng nhóm trong ngày. */
    private void checkPetLimits(Long petId, ServiceGroup group, LocalDate date, LocalTime start) {
        int maxBooked = configs.getInt(ConfigKey.APPOINTMENT_MAX_BOOKED_PER_PET);
        if (appointments.countBookedByPet(petId) >= maxBooked) {
            throw new BusinessRuleViolationException("BR-LH-05",
                    "Thú cưng đã có " + maxBooked + " lịch hẹn đang chờ");
        }
        if (appointments.existsActiveSameSlot(petId, date, start)) {
            throw new BusinessRuleViolationException("BR-LH-05", "Thú cưng đã có lịch hẹn khác trùng khung giờ này");
        }
        if (appointments.existsActiveSameGroupDay(petId, group.name(), date)) {
            throw new BusinessRuleViolationException("BR-LH-05",
                    "Thú cưng đã có lịch " + groupLabel(group) + " trong ngày này");
        }
    }

    private static String groupLabel(ServiceGroup group) {
        return group == ServiceGroup.MEDICAL ? "Khám/Tiêm" : "Thẩm mỹ";
    }

    /** Ghi chú bỏ khoảng trắng hai đầu; rỗng thì không lưu (docs/adr/0047). */
    private static String normalizeNote(String note) {
        if (note == null) {
            return null;
        }
        String stripped = note.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
