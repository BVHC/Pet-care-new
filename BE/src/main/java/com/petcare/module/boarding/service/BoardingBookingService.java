package com.petcare.module.boarding.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.api.BookingRestrictionApi;
import com.petcare.module.boarding.dto.BookingResponse;
import com.petcare.module.boarding.dto.BookingResultResponse;
import com.petcare.module.boarding.dto.CreateBookingRequest;
import com.petcare.module.boarding.dto.VaccineGapResponse;
import com.petcare.module.boarding.entity.BoardingBooking;
import com.petcare.module.boarding.entity.BoardingBookingStatus;
import com.petcare.module.boarding.entity.BookingChannel;
import com.petcare.module.boarding.fsm.BoardingBookingTransitionHandler;
import com.petcare.module.boarding.repository.BoardingBookingRepository;
import com.petcare.module.branch.api.BranchApi;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.KennelTypeInfo;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.visit.api.VaccinationQueryApi;
import com.petcare.module.visit.api.VaccinationQueryApi.BoardingVaccineGap;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;
import com.petcare.platform.sequence.DocumentCodeGenerator;

/**
 * Đặt chỗ lưu trú (UC58, boarding-v1 #8, Đặt chỗ#1; docs/adr/0065). Thứ tự khóa: {@code pets FOR SHARE} →
 * {@code branches FOR NO KEY UPDATE} (tuần tự hóa sức chứa, cùng khóa với đặt lịch hẹn và đổi giờ / ngày nghỉ) →
 * INSERT → {@code document_counters}. READ COMMITTED: kiểm sức chứa sau khi có khóa phải thấy đặt chỗ đã commit.
 */
@Service
public class BoardingBookingService {

    static final String CUSTOMER = "CUSTOMER";
    static final String RECEPTIONIST = "RECEPTIONIST";

    /** Giờ hết hạn chế trong message BR-LH-09, cùng khuôn với đặt lịch hẹn (docs/adr/0055). */
    static final DateTimeFormatter RESTRICTION_END = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")
            .withZone(TimeConfig.BUSINESS_ZONE);

    private final BranchScope branchScope;
    private final CustomerQueryApi customers;
    private final PetQueryApi pets;
    private final CatalogQueryApi catalog;
    private final BranchApi branchLocks;
    private final BranchQueryApi branches;
    private final BookingRestrictionApi restrictions;
    private final VaccinationQueryApi vaccinations;
    private final SystemConfigApi configs;
    private final BoardingCapacity capacity;
    private final BoardingAvailabilityService availability;
    private final DocumentCodeGenerator codes;
    private final BoardingBookingRepository bookings;
    private final BoardingBookingTransitionHandler transitions;
    private final BoardingAssembler assembler;
    private final Clock clock;

    public BoardingBookingService(BranchScope branchScope, CustomerQueryApi customers, PetQueryApi pets,
            CatalogQueryApi catalog, BranchApi branchLocks, BranchQueryApi branches,
            BookingRestrictionApi restrictions, VaccinationQueryApi vaccinations, SystemConfigApi configs,
            BoardingCapacity capacity, BoardingAvailabilityService availability, DocumentCodeGenerator codes,
            BoardingBookingRepository bookings, BoardingBookingTransitionHandler transitions,
            BoardingAssembler assembler, Clock clock) {
        this.branchScope = branchScope;
        this.customers = customers;
        this.pets = pets;
        this.catalog = catalog;
        this.branchLocks = branchLocks;
        this.branches = branches;
        this.restrictions = restrictions;
        this.vaccinations = vaccinations;
        this.configs = configs;
        this.capacity = capacity;
        this.availability = availability;
        this.codes = codes;
        this.bookings = bookings;
        this.transitions = transitions;
        this.assembler = assembler;
        this.clock = clock;
    }

    @Transactional
    public BookingResultResponse createBooking(CreateBookingRequest request) {
        SecurityPrincipal principal = branchScope.current();
        boolean customer = switch (principal.role()) {
            case CUSTOMER -> true;
            case RECEPTIONIST -> {
                branchScope.check(request.branchId());
                yield false;
            }
            default -> throw new IllegalStateException("Role không được đặt chỗ lưu trú: " + principal.role());
        };
        Long ownCustomerId = customer ? ownCustomerId(principal.accountId()) : null;

        PetSummary pet = pets.findPetForShare(request.petId())
                .filter(found -> !customer || found.customerId().equals(ownCustomerId))
                .orElseThrow(() -> new ResourceNotFoundException("thú cưng", request.petId()));
        KennelTypeInfo type = catalog.findKennelType(request.kennelTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("loại chuồng", request.kennelTypeId()));
        BranchStatus branchStatus = branchLocks.lockForBooking(request.branchId())
                .filter(found -> !customer || found == BranchStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("chi nhánh", request.branchId()));
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);
        Long branchId = request.branchId();
        LocalDate in = request.checkInDate();
        LocalDate out = request.checkOutDate();

        if (branchStatus != BranchStatus.ACTIVE) {
            throw new BusinessRuleViolationException("BR-LT-02", "Chi nhánh chưa nhận đặt chỗ lưu trú");
        }
        if (!type.active() || !branches.isServiceEnabled(branchId, type.serviceId())) {
            throw new BusinessRuleViolationException("BR-LT-02", "Loại chuồng không đặt được tại chi nhánh này");
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
                throw new BusinessRuleViolationException("BR-LH-09", "Bạn đang bị hạn chế đặt online đến "
                        + RESTRICTION_END.format(restrictedUntil.get())
                        + ", vui lòng liên hệ chi nhánh để đặt chỗ");
            }
        }
        if (pet.deceased()) {
            throw new BusinessRuleViolationException("BR-KH-05", "Thú cưng đã mất, không đặt được chỗ lưu trú");
        }
        checkPetFitsType(pet, type);
        availability.checkNights(in, out);
        if (bookings.existsActiveOverlap(pet.petId(), in, out, 0L)) {
            throw new BusinessRuleViolationException("BR-LT-02", "Thú cưng đã có đặt chỗ lưu trú trùng ngày");
        }
        checkDates(customer, now, today, branchId, in, out);
        List<LocalDate> shortNights = capacity.shortNights(branchId, type.serviceId(), in, out, today);
        if (!shortNights.isEmpty()) {
            throw new BusinessRuleViolationException("BR-LT-03",
                    "Hết chỗ các đêm: " + BoardingRules.nightList(shortNights));
        }

        transitions.validateInitial(BoardingBookingStatus.BOOKED);
        BoardingBooking saved = bookings.saveAndFlush(BoardingBooking.book(
                codes.next("BOARDING_BOOKING", "LT", today), pet.customerId(), pet.petId(), branchId,
                type.serviceId(), in, out, type.nightlyPrice(),
                customer ? BookingChannel.ONLINE : BookingChannel.COUNTER, principal.accountId()));

        BookingResponse response = assembler.toResponse(saved, today);
        return new BookingResultResponse(response, vaccineWarnings(pet.petId(), in));
    }

    /** BR-KH-01: tài khoản khách luôn có hồ sơ; không có là dữ liệu hỏng (500), không phải lỗi của người dùng. */
    private Long ownCustomerId(Long accountId) {
        return customers.findCustomerIdByAccountId(accountId)
                .orElseThrow(() -> new IllegalStateException("Tài khoản khách #" + accountId + " không có hồ sơ khách"));
    }

    /** BR-LT-02: đúng loài, cân nặng hiện tại không vượt tối đa (thú chưa cân thì cho qua, cân bắt buộc lúc nhận). */
    private static void checkPetFitsType(PetSummary pet, KennelTypeInfo type) {
        if (pet.species() != type.species()) {
            throw new BusinessRuleViolationException("BR-LT-02", "Loại chuồng không phù hợp loài của thú cưng");
        }
        if (pet.currentWeightKg() != null && pet.currentWeightKg().compareTo(type.maxWeightKg()) > 0) {
            throw new BusinessRuleViolationException("BR-LT-02",
                    "Cân nặng hiện tại vượt cân nặng tối đa của loại chuồng");
        }
    }

    /** BR-LT-04: khoảng đặt trước của khách, lễ tân không đặt ngày đã qua / sau giờ đóng cửa, ngày làm việc. */
    private void checkDates(boolean customer, Instant now, LocalDate today, Long branchId, LocalDate in,
            LocalDate out) {
        if (customer) {
            int minLead = configs.getInt(ConfigKey.BOARDING_MIN_LEAD_DAYS);
            int maxAdvance = configs.getInt(ConfigKey.BOARDING_MAX_ADVANCE_DAYS);
            if (!BoardingRules.customerWindowAllows(today, in, minLead, maxAdvance)) {
                throw new BusinessRuleViolationException("BR-LT-04", "Chỉ đặt online trước ngày nhận ít nhất "
                        + minLead + " ngày và trong vòng " + maxAdvance + " ngày tới");
            }
        } else if (in.isBefore(today)) {
            throw new BusinessRuleViolationException("BR-LT-04", "Ngày nhận đã qua");
        } else if (in.equals(today)
                && !now.isBefore(BoardingRules.noShowCutoff(today, branches.openingRangesOn(branchId, today)))) {
            throw new BusinessRuleViolationException("BR-LT-04",
                    "Đã hết giờ làm việc hôm nay, không nhận đặt chỗ nhận trong ngày");
        }
        if (branches.openingRangesOn(branchId, in).isEmpty() || branches.openingRangesOn(branchId, out).isEmpty()) {
            throw new BusinessRuleViolationException("BR-LT-04", "Ngày nhận / trả trùng ngày chi nhánh không làm việc");
        }
    }

    /** BR-LT-05 lúc đặt chỉ cảnh báo (không chặn); lỗi của {@code VaccinationQueryApi} làm rollback cả lần đặt. */
    private List<VaccineGapResponse> vaccineWarnings(Long petId, LocalDate checkInDate) {
        List<BoardingVaccineGap> gaps = vaccinations.findBoardingVaccineGaps(petId, checkInDate);
        if (gaps.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = catalog.findVaccineTypeNames(gaps.stream().map(BoardingVaccineGap::vaccineTypeId)
                .toList());
        return gaps.stream().map(gap -> new VaccineGapResponse(gap.vaccineTypeId(),
                names.getOrDefault(gap.vaccineTypeId(), "Loại vaccine #" + gap.vaccineTypeId()),
                gap.lastAdministeredOn(), gap.nextDueDate())).toList();
    }
}
