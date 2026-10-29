package com.petcare.module.boarding.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.boarding.dto.BookingResponse;
import com.petcare.module.boarding.dto.CheckInReadinessResponse;
import com.petcare.module.boarding.dto.CheckInRequest;
import com.petcare.module.boarding.dto.KennelResponse;
import com.petcare.module.boarding.dto.VaccineGapResponse;
import com.petcare.module.boarding.entity.BoardingBooking;
import com.petcare.module.boarding.entity.BoardingBookingStatus;
import com.petcare.module.boarding.entity.BoardingCheckIn;
import com.petcare.module.boarding.entity.BookingCancelSource;
import com.petcare.module.boarding.entity.Kennel;
import com.petcare.module.boarding.entity.KennelStatus;
import com.petcare.module.boarding.exception.BoardingConflictException;
import com.petcare.module.boarding.fsm.BoardingBookingTransitionHandler;
import com.petcare.module.boarding.fsm.KennelTransitionHandler;
import com.petcare.module.boarding.repository.BoardingBookingRepository;
import com.petcare.module.boarding.repository.BoardingBookingRepository.BookingKeys;
import com.petcare.module.boarding.repository.BoardingCheckInRepository;
import com.petcare.module.boarding.repository.KennelRepository;
import com.petcare.module.branch.api.BranchApi;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.KennelTypeInfo;
import com.petcare.module.customer.api.PetApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.RecordedWeight;
import com.petcare.module.customer.api.WeightSource;
import com.petcare.module.visit.api.VaccinationQueryApi;
import com.petcare.module.visit.api.VaccinationQueryApi.BoardingVaccineGap;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Nhận thú và hủy vì hết chuồng (UC59, boarding-v1 #13–15; Đặt chỗ#3, #5; Chuồng#2; docs/adr/0085). Nhận thú khóa
 * {@code pets FOR SHARE → branches → boarding_bookings → kennels} rồi INSERT {@code weight_records} và
 * {@code boarding_check_ins}; hủy vì hết chuồng khóa {@code branches → boarding_bookings}. Thứ tự lỗi như ADR-0049:
 * 404 / 403 → 409 trạng thái → 400 rule. Lễ tân và CARETAKER nhận thú; chỉ lễ tân hủy (controller).
 */
@Service
public class BoardingCheckInService {

    private final BranchScope branchScope;
    private final PetQueryApi pets;
    private final PetApi petWeights;
    private final CatalogQueryApi catalog;
    private final BranchApi branchLocks;
    private final BranchQueryApi branches;
    private final VaccinationQueryApi vaccinations;
    private final BoardingCapacity capacity;
    private final BoardingAvailabilityService availability;
    private final BoardingBookingRepository bookings;
    private final KennelRepository kennels;
    private final BoardingCheckInRepository checkIns;
    private final BoardingBookingTransitionHandler transitions;
    private final KennelTransitionHandler kennelTransitions;
    private final BoardingAssembler assembler;
    private final BoardingClinicCancellationNotices customerNotices;
    private final BoardingManagerNotices managerNotices;
    private final Clock clock;

    public BoardingCheckInService(BranchScope branchScope, PetQueryApi pets, PetApi petWeights,
            CatalogQueryApi catalog, BranchApi branchLocks, BranchQueryApi branches, VaccinationQueryApi vaccinations,
            BoardingCapacity capacity, BoardingAvailabilityService availability, BoardingBookingRepository bookings,
            KennelRepository kennels, BoardingCheckInRepository checkIns,
            BoardingBookingTransitionHandler transitions, KennelTransitionHandler kennelTransitions,
            BoardingAssembler assembler, BoardingClinicCancellationNotices customerNotices,
            BoardingManagerNotices managerNotices, Clock clock) {
        this.branchScope = branchScope;
        this.pets = pets;
        this.petWeights = petWeights;
        this.catalog = catalog;
        this.branchLocks = branchLocks;
        this.branches = branches;
        this.vaccinations = vaccinations;
        this.capacity = capacity;
        this.availability = availability;
        this.bookings = bookings;
        this.kennels = kennels;
        this.checkIns = checkIns;
        this.transitions = transitions;
        this.kennelTransitions = kennelTransitions;
        this.assembler = assembler;
        this.customerNotices = customerNotices;
        this.managerNotices = managerNotices;
        this.clock = clock;
    }

    /** #13: không khóa; kết quả chỉ để lễ tân xem, {@link #checkIn} kiểm lại dưới khóa. */
    @Transactional(readOnly = true)
    public CheckInReadinessResponse readiness(Long bookingId) {
        authorize(bookingId);
        BoardingBooking booking = bookings.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("đặt chỗ lưu trú", bookingId));
        requireBooked(booking);
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);

        List<VaccineGapResponse> gaps = vaccineGaps(booking.getPetId(), today);
        KennelTypeInfo type = kennelType(booking);
        List<KennelResponse> available = kennels.findByBranchIdAndKennelTypeIdAndStatusOrderByCodeAsc(
                booking.getBranchId(), booking.getKennelTypeId(), KennelStatus.AVAILABLE).stream()
                .map(k -> new KennelResponse(k.getId(), k.getBranchId(), k.getKennelTypeId(), type.name(),
                        k.getCode(), k.getStatus(), k.getNote(), null)).toList();
        boolean open = BoardingRules.withinOpeningHours(now, branches.openingRangesOn(booking.getBranchId(), today));
        boolean dateOk = !today.isAfter(booking.getCheckInDate()) && earlyNightsFit(booking, today);
        return new CheckInReadinessResponse(gaps, available, open,
                gaps.isEmpty() && !available.isEmpty() && open && dateOk);
    }

    @Transactional
    public BookingResponse checkIn(Long bookingId, CheckInRequest request) {
        BookingKeys keys = authorize(bookingId);
        SecurityPrincipal principal = branchScope.current();
        PetSummary pet = pets.findPetForShare(keys.getPetId())
                .orElseThrow(() -> new IllegalStateException("Đặt chỗ #" + bookingId + " trỏ tới thú không có"));
        branchLocks.lockForBooking(keys.getBranchId())
                .orElseThrow(() -> new IllegalStateException("Đặt chỗ #" + bookingId + " trỏ tới chi nhánh không có"));
        BoardingBooking booking = bookings.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("đặt chỗ lưu trú", bookingId));
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);

        transitions.validateTransition(booking.getStatus(), BoardingBookingStatus.CHECKED_IN);
        if (today.isAfter(booking.getCheckInDate())) {
            throw new BusinessRuleViolationException("BR-LT-06", "Đã quá ngày nhận, đặt chỗ không nhận được nữa");
        }
        if (!BoardingRules.withinOpeningHours(now, branches.openingRangesOn(booking.getBranchId(), today))) {
            throw new BusinessRuleViolationException("BR-LT-04", "Chỉ nhận thú trong giờ mở cửa của chi nhánh");
        }
        checkEarlyNights(booking, today);
        List<VaccineGapResponse> gaps = vaccineGaps(pet.petId(), today);
        if (!gaps.isEmpty()) {
            throw new BusinessRuleViolationException("BR-LT-05", "Thiếu hoặc quá hạn mũi tiêm bắt buộc khi lưu trú: "
                    + String.join(", ", gaps.stream().map(VaccineGapResponse::vaccineTypeName).toList()));
        }
        if (request.weightKg() == null) {
            throw new BusinessRuleViolationException("BR-LT-08", "Vui lòng nhập cân nặng lúc nhận");
        }
        if (request.kennelId() == null) {
            throw new BusinessRuleViolationException("BR-LT-08", "Vui lòng chọn chuồng cho thú");
        }
        Kennel kennel = kennels.findByIdForUpdate(request.kennelId())
                .orElseThrow(() -> new ResourceNotFoundException("chuồng", request.kennelId()));
        if (!kennel.getBranchId().equals(booking.getBranchId())
                || !kennel.getKennelTypeId().equals(booking.getKennelTypeId())) {
            throw new BusinessRuleViolationException("BR-LT-08", "Chuồng phải đúng chi nhánh và đúng loại đã đặt");
        }
        if (kennel.getStatus() != KennelStatus.AVAILABLE) {
            throw new BusinessRuleViolationException("BR-LT-08", "Chuồng không còn trống");
        }
        kennelTransitions.validateTransition(kennel.getStatus(), KennelStatus.OCCUPIED);

        RecordedWeight weight = petWeights.recordWeight(pet.petId(), request.weightKg(), WeightSource.INTAKE, null,
                bookingId, principal.accountId());
        booking.checkIn(kennel.getId(), now, today);
        kennel.occupy();
        BoardingBooking saved = bookings.saveAndFlush(booking);
        kennels.saveAndFlush(kennel);
        checkIns.saveAndFlush(BoardingCheckIn.record(bookingId, weight.weightRecordId(),
                request.healthCondition().strip(), blankToNull(request.belongings()),
                blankToNull(request.dietInstructions()), request.emergencyPhone(), principal.accountId(),
                now.truncatedTo(ChronoUnit.MICROS)));
        return assembler.toResponse(saved, today);
    }

    /**
     * #15 (Đặt chỗ#5): lễ tân bấm sau khi nhận thất bại vì hết chuồng (A3). Không tính hủy muộn nên không gọi
     * {@code evaluateAfterViolation}.
     */
    @Transactional
    public BookingResponse cancelNoKennel(Long bookingId) {
        BookingKeys keys = authorize(bookingId);
        branchLocks.lockForBooking(keys.getBranchId())
                .orElseThrow(() -> new IllegalStateException("Đặt chỗ #" + bookingId + " trỏ tới chi nhánh không có"));
        BoardingBooking booking = bookings.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("đặt chỗ lưu trú", bookingId));
        LocalDate today = LocalDate.now(clock);

        transitions.validateTransition(booking.getStatus(), BoardingBookingStatus.CANCELLED);
        if (today.isBefore(booking.getCheckInDate())) {
            throw new BusinessRuleViolationException("BR-LT-08", "Chưa tới ngày nhận, chưa hủy vì hết chuồng được");
        }
        if (kennels.countAvailable(booking.getBranchId(), booking.getKennelTypeId()) > 0) {
            throw new BusinessRuleViolationException("BR-LT-08", "Vẫn còn chuồng trống đúng loại, hãy nhận thú");
        }
        booking.cancel(BookingCancelSource.CLINIC, BoardingClinicCause.NO_KENNEL.reason(), false);
        BoardingBooking saved = bookings.saveAndFlush(booking);
        customerNotices.enqueueFor(List.of(saved), BoardingClinicCause.NO_KENNEL);
        String petName = pets.findPet(saved.getPetId()).map(PetSummary::name)
                .orElseThrow(() -> new IllegalStateException("Đặt chỗ #" + bookingId + " trỏ tới thú không có"));
        managerNotices.enqueueNoKennel(saved, petName, kennelType(saved).name());
        return assembler.toResponse(saved, today);
    }

    /** Nhân viên chi nhánh khác → 403; đặt chỗ không có → 404. */
    private BookingKeys authorize(Long bookingId) {
        BookingKeys keys = bookings.findKeys(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("đặt chỗ lưu trú", bookingId));
        branchScope.check(keys.getBranchId());
        return keys;
    }

    private static void requireBooked(BoardingBooking booking) {
        if (booking.getStatus() != BoardingBookingStatus.BOOKED) {
            throw new BoardingConflictException("Đặt chỗ không còn ở trạng thái BOOKED");
        }
    }

    /** Nhận sớm: các đêm thêm {@code [hôm nay, ngày nhận cũ)} phải còn chỗ, tổng số đêm hợp lệ, không chồng đặt chỗ khác. */
    private void checkEarlyNights(BoardingBooking booking, LocalDate today) {
        if (!today.isBefore(booking.getCheckInDate())) {
            return;
        }
        availability.checkNights(today, booking.getCheckOutDate());
        if (bookings.existsActiveOverlap(booking.getPetId(), today, booking.getCheckInDate(), booking.getId())) {
            throw new BusinessRuleViolationException("BR-LT-02", "Thú cưng đã có đặt chỗ lưu trú trùng ngày");
        }
        List<LocalDate> shortNights = capacity.shortNights(booking.getBranchId(), booking.getKennelTypeId(), today,
                booking.getCheckInDate(), today);
        if (!shortNights.isEmpty()) {
            throw new BusinessRuleViolationException("BR-LT-03",
                    "Hết chỗ các đêm: " + BoardingRules.nightList(shortNights));
        }
    }

    /** Dạng không ném của {@link #checkEarlyNights} cho #13. */
    private boolean earlyNightsFit(BoardingBooking booking, LocalDate today) {
        try {
            checkEarlyNights(booking, today);
            return true;
        } catch (BusinessRuleViolationException e) {
            return false;
        }
    }

    private List<VaccineGapResponse> vaccineGaps(Long petId, LocalDate onDate) {
        List<BoardingVaccineGap> gaps = vaccinations.findBoardingVaccineGaps(petId, onDate);
        if (gaps.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = catalog.findVaccineTypeNames(gaps.stream().map(BoardingVaccineGap::vaccineTypeId)
                .toList());
        return gaps.stream().map(gap -> new VaccineGapResponse(gap.vaccineTypeId(),
                names.getOrDefault(gap.vaccineTypeId(), "Loại vaccine #" + gap.vaccineTypeId()),
                gap.lastAdministeredOn(), gap.nextDueDate())).toList();
    }

    private KennelTypeInfo kennelType(BoardingBooking booking) {
        return catalog.findKennelType(booking.getKennelTypeId()).orElseThrow(() -> new IllegalStateException(
                "Đặt chỗ #" + booking.getId() + " trỏ tới loại chuồng không có: " + booking.getKennelTypeId()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
