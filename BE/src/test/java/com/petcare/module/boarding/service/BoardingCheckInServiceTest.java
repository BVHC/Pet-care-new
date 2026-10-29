package com.petcare.module.boarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.boarding.dto.CheckInRequest;
import com.petcare.module.boarding.entity.BoardingBooking;
import com.petcare.module.boarding.entity.BoardingBookingStatus;
import com.petcare.module.boarding.entity.BookingChannel;
import com.petcare.module.boarding.entity.Kennel;
import com.petcare.module.boarding.entity.KennelStatus;
import com.petcare.module.boarding.fsm.BoardingBookingTransitionHandler;
import com.petcare.module.boarding.fsm.KennelTransitionHandler;
import com.petcare.module.boarding.repository.BoardingBookingRepository;
import com.petcare.module.boarding.repository.BoardingBookingRepository.BookingKeys;
import com.petcare.module.boarding.repository.BoardingCheckInRepository;
import com.petcare.module.boarding.repository.KennelRepository;
import com.petcare.module.branch.api.BranchApi;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.TimeRange;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.customer.api.PetApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.RecordedWeight;
import com.petcare.module.customer.api.Species;
import com.petcare.module.customer.api.WeightSource;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.visit.api.VaccinationQueryApi;
import com.petcare.module.visit.api.VaccinationQueryApi.BoardingVaccineGap;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

class BoardingCheckInServiceTest {

    private static final long ID = 88L;
    private static final long BRANCH = 7L;
    private static final long TYPE = 5L;
    private static final long PET = 21L;
    private static final long CUSTOMER = 14L;
    private static final long KENNEL = 12L;
    private static final long ACTOR = 3L;

    private PetQueryApi pets;
    private PetApi petWeights;
    private BranchApi branchLocks;
    private VaccinationQueryApi vaccinations;
    private BoardingBookingRepository bookings;
    private KennelRepository kennels;
    private BoardingCheckInRepository checkIns;
    private BoardingCheckInService service;
    private BoardingBooking booking;
    private Kennel kennel;

    @BeforeEach
    void setUp() {
        BranchScope scope = mock(BranchScope.class);
        SecurityPrincipal principal = mock(SecurityPrincipal.class);
        when(principal.accountId()).thenReturn(ACTOR);
        when(scope.current()).thenReturn(principal);
        BookingKeys keys = mock(BookingKeys.class);
        when(keys.getBranchId()).thenReturn(BRANCH);
        when(keys.getPetId()).thenReturn(PET);
        bookings = mock(BoardingBookingRepository.class);
        when(bookings.findKeys(ID)).thenReturn(Optional.of(keys));
        booking = BoardingBooking.book("LT-0906-00001", CUSTOMER, PET, BRANCH, TYPE, LocalDate.parse("2009-06-10"),
                LocalDate.parse("2009-06-12"), 150_000L, BookingChannel.COUNTER, 9L);
        ReflectionTestUtils.setField(booking, "id", ID);
        when(bookings.findByIdForUpdate(ID)).thenReturn(Optional.of(booking));
        when(bookings.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        kennel = Kennel.create(BRANCH, TYPE, "C-01", null);
        ReflectionTestUtils.setField(kennel, "id", KENNEL);
        kennels = mock(KennelRepository.class);
        when(kennels.findByIdForUpdate(KENNEL)).thenReturn(Optional.of(kennel));
        when(kennels.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        checkIns = mock(BoardingCheckInRepository.class);
        pets = mock(PetQueryApi.class);
        when(pets.findPetForShare(PET)).thenReturn(Optional.of(
                new PetSummary(PET, CUSTOMER, "Milu", Species.DOG, null, null, null)));
        petWeights = mock(PetApi.class);
        when(petWeights.recordWeight(anyLong(), any(), any(), any(), any(), any())).thenReturn(
                new RecordedWeight(99L, new BigDecimal("4.30"), Instant.parse("2009-06-10T03:00:00Z")));
        branchLocks = mock(BranchApi.class);
        when(branchLocks.lockForBooking(BRANCH)).thenReturn(Optional.of(BranchStatus.ACTIVE));
        BranchQueryApi branches = mock(BranchQueryApi.class);
        when(branches.openingRangesOn(anyLong(), any())).thenReturn(List.of(new TimeRange(LocalTime.of(8, 0),
                LocalTime.of(19, 0))));
        vaccinations = mock(VaccinationQueryApi.class);
        when(vaccinations.findBoardingVaccineGaps(anyLong(), any())).thenReturn(List.of());
        CatalogQueryApi catalog = mock(CatalogQueryApi.class);
        BoardingCapacity capacity = mock(BoardingCapacity.class);
        when(capacity.shortNights(anyLong(), anyLong(), any(), any(), any())).thenReturn(List.of());
        SystemConfigApi configs = mock(SystemConfigApi.class);
        when(configs.getInt(ConfigKey.BOARDING_MIN_NIGHTS)).thenReturn(1);
        when(configs.getInt(ConfigKey.BOARDING_MAX_NIGHTS)).thenReturn(30);
        Clock clock = Clock.fixed(Instant.parse("2009-06-10T03:00:00Z"), ZoneOffset.UTC);   // 10:00 giờ VN
        service = new BoardingCheckInService(scope, pets, petWeights, catalog, branchLocks, branches, vaccinations,
                capacity, new BoardingAvailabilityService(scope, branches, null, capacity, configs, clock), bookings,
                kennels, checkIns, new BoardingBookingTransitionHandler(), new KennelTransitionHandler(),
                mock(BoardingAssembler.class), mock(BoardingClinicCancellationNotices.class),
                mock(BoardingManagerNotices.class), clock);
    }

    private CheckInRequest request(Long kennelId, BigDecimal weight) {
        return new CheckInRequest(kennelId, weight, " Tỉnh táo ", " ", null, "0901234567");
    }

    @Test
    void locksPetThenBranchThenBookingThenKennelThenChecksVaccineThenWrites() {
        service.checkIn(ID, request(KENNEL, new BigDecimal("4.3")));

        InOrder order = inOrder(pets, branchLocks, bookings, vaccinations, kennels, petWeights, checkIns);
        order.verify(pets).findPetForShare(PET);
        order.verify(branchLocks).lockForBooking(BRANCH);
        order.verify(bookings).findByIdForUpdate(ID);
        order.verify(vaccinations).findBoardingVaccineGaps(PET, LocalDate.parse("2009-06-10"));
        order.verify(kennels).findByIdForUpdate(KENNEL);
        order.verify(petWeights).recordWeight(PET, new BigDecimal("4.3"), WeightSource.INTAKE, null, ID, ACTOR);
        order.verify(bookings).saveAndFlush(booking);
        order.verify(kennels).saveAndFlush(kennel);
        order.verify(checkIns).saveAndFlush(any());
        assertThat(booking.getStatus()).isEqualTo(BoardingBookingStatus.CHECKED_IN);
        assertThat(kennel.getStatus()).isEqualTo(KennelStatus.OCCUPIED);
    }

    @Test
    void missingVaccineWritesNothing() {
        when(vaccinations.findBoardingVaccineGaps(anyLong(), any())).thenReturn(
                List.of(new BoardingVaccineGap(3L, null, null)));

        assertThatThrownBy(() -> service.checkIn(ID, request(KENNEL, BigDecimal.ONE)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getRuleId()).isEqualTo("BR-LT-05"));

        assertNothingWritten();
        verify(kennels, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void wrongTypeOrBusyKennelOrMissingInputsAreBrLt08AndWriteNothing() {
        Kennel wrongType = Kennel.create(BRANCH, TYPE + 1, "D-01", null);
        ReflectionTestUtils.setField(wrongType, "id", 13L);
        when(kennels.findByIdForUpdate(13L)).thenReturn(Optional.of(wrongType));
        kennel.occupy();
        Kennel busy = kennel;

        for (CheckInRequest bad : List.of(request(13L, BigDecimal.ONE), request(busy.getId(), BigDecimal.ONE),
                request(null, BigDecimal.ONE), request(KENNEL, null))) {
            assertThatThrownBy(() -> service.checkIn(ID, bad)).isInstanceOfSatisfying(
                    BusinessRuleViolationException.class, e -> assertThat(e.getRuleId()).isEqualTo("BR-LT-08"));
        }

        assertNothingWritten();
    }

    @Test
    void outsideOpeningHoursIsBrLt04BeforeAnyKennelLock() {
        BranchQueryApi closed = mock(BranchQueryApi.class);
        when(closed.openingRangesOn(anyLong(), any())).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "branches", closed);

        assertThatThrownBy(() -> service.checkIn(ID, request(KENNEL, BigDecimal.ONE)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getRuleId()).isEqualTo("BR-LT-04"));

        verify(kennels, never()).findByIdForUpdate(anyLong());
        assertNothingWritten();
    }

    private void assertNothingWritten() {
        verify(petWeights, never()).recordWeight(anyLong(), any(), any(), any(), any(), any());
        verify(bookings, never()).saveAndFlush(any());
        verify(kennels, never()).saveAndFlush(any());
        verify(checkIns, never()).saveAndFlush(any());
    }
}
