package com.petcare.module.boarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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

import com.petcare.module.appointment.api.BookingRestrictionApi;
import com.petcare.module.boarding.dto.CreateBookingRequest;
import com.petcare.module.boarding.entity.BoardingBooking;
import com.petcare.module.boarding.fsm.BoardingBookingTransitionHandler;
import com.petcare.module.boarding.repository.BoardingBookingRepository;
import com.petcare.module.branch.api.BranchApi;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.TimeRange;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.KennelTypeInfo;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.Species;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.visit.api.VaccinationQueryApi;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;
import com.petcare.platform.sequence.DocumentCodeGenerator;

class BoardingBookingServiceTest {

    private static final long BRANCH = 7L;
    private static final long PET = 21L;
    private static final long TYPE = 5L;
    private static final LocalDate IN = LocalDate.parse("2009-06-12");
    private static final LocalDate OUT = LocalDate.parse("2009-06-14");

    private PetQueryApi pets;
    private BranchApi branchLocks;
    private BoardingCapacity capacity;
    private DocumentCodeGenerator codes;
    private BoardingBookingRepository bookings;
    private BoardingBookingService service;

    private PetSummary pet(LocalDate deceasedOn, String weight) {
        return new PetSummary(PET, 14L, "Milu", Species.DOG, null, deceasedOn,
                weight == null ? null : new BigDecimal(weight));
    }

    @BeforeEach
    void setUp() {
        BranchScope scope = mock(BranchScope.class);
        SecurityPrincipal principal = mock(SecurityPrincipal.class);
        when(principal.role()).thenReturn("RECEPTIONIST");
        when(principal.accountId()).thenReturn(9L);
        when(scope.current()).thenReturn(principal);
        CustomerQueryApi customers = mock(CustomerQueryApi.class);
        when(customers.findContact(14L)).thenReturn(Optional.of(
                new CustomerContact(14L, "Khách", "0900000000", null, null, false)));
        pets = mock(PetQueryApi.class);
        when(pets.findPetForShare(PET)).thenReturn(Optional.of(pet(null, "10")));
        CatalogQueryApi catalog = mock(CatalogQueryApi.class);
        when(catalog.findKennelType(TYPE)).thenReturn(Optional.of(
                new KennelTypeInfo(TYPE, "Chuồng nhỏ", Species.DOG, new BigDecimal("30"), 150_000L, true)));
        branchLocks = mock(BranchApi.class);
        when(branchLocks.lockForBooking(BRANCH)).thenReturn(Optional.of(BranchStatus.ACTIVE));
        BranchQueryApi branches = mock(BranchQueryApi.class);
        when(branches.isServiceEnabled(BRANCH, TYPE)).thenReturn(true);
        when(branches.openingRangesOn(eq(BRANCH), any())).thenReturn(
                List.of(new TimeRange(LocalTime.of(8, 0), LocalTime.of(19, 0))));
        SystemConfigApi configs = mock(SystemConfigApi.class);
        when(configs.getInt(ConfigKey.BOARDING_MIN_NIGHTS)).thenReturn(1);
        when(configs.getInt(ConfigKey.BOARDING_MAX_NIGHTS)).thenReturn(30);
        capacity = mock(BoardingCapacity.class);
        when(capacity.shortNights(anyLong(), anyLong(), any(), any(), any())).thenReturn(List.of());
        codes = mock(DocumentCodeGenerator.class);
        when(codes.next(any(), any(), any())).thenReturn("LT-0906-00001");
        bookings = mock(BoardingBookingRepository.class);
        when(bookings.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        BoardingAssembler assembler = mock(BoardingAssembler.class);
        VaccinationQueryApi vaccinations = mock(VaccinationQueryApi.class);
        when(vaccinations.findBoardingVaccineGaps(anyLong(), any())).thenReturn(List.of());
        service = new BoardingBookingService(scope, customers, pets, catalog, branchLocks, branches,
                mock(BookingRestrictionApi.class), vaccinations, configs, capacity,
                new BoardingAvailabilityService(scope, branches, catalog, capacity, configs,
                        Clock.fixed(Instant.parse("2009-06-10T03:00:00Z"), ZoneOffset.UTC)),
                codes, bookings, new BoardingBookingTransitionHandler(), assembler,
                Clock.fixed(Instant.parse("2009-06-10T03:00:00Z"), ZoneOffset.UTC));
    }

    private CreateBookingRequest request() {
        return new CreateBookingRequest(PET, BRANCH, TYPE, IN, OUT);
    }

    @Test
    void locksPetThenBranchThenChecksCapacityThenCodeThenInserts() {
        service.createBooking(request());

        InOrder order = inOrder(pets, branchLocks, capacity, codes, bookings);
        order.verify(pets).findPetForShare(PET);
        order.verify(branchLocks).lockForBooking(BRANCH);
        order.verify(capacity).shortNights(eq(BRANCH), eq(TYPE), eq(IN), eq(OUT), any());
        order.verify(codes).next(eq("BOARDING_BOOKING"), eq("LT"), any());
        order.verify(bookings).saveAndFlush(any(BoardingBooking.class));
    }

    @Test
    void deceasedPetIsBrKh05AndWritesNothing() {
        when(pets.findPetForShare(PET)).thenReturn(Optional.of(pet(LocalDate.parse("2009-06-01"), "10")));

        assertThatThrownBy(() -> service.createBooking(request()))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-KH-05");

        verify(codes, never()).next(any(), any(), any());
        verify(bookings, never()).saveAndFlush(any());
    }

    @Test
    void overweightPetIsBrLt02AndWritesNothing() {
        when(pets.findPetForShare(PET)).thenReturn(Optional.of(pet(null, "31")));

        assertThatThrownBy(() -> service.createBooking(request()))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-LT-02");

        verify(bookings, never()).saveAndFlush(any());
    }

    @Test
    void unweighedPetIsAllowed() {
        when(pets.findPetForShare(PET)).thenReturn(Optional.of(pet(null, null)));

        assertThat(service.createBooking(request())).isNotNull();
    }

    @Test
    void shortNightsAreBrLt03ListingEachNightAndWriteNothing() {
        when(capacity.shortNights(anyLong(), anyLong(), any(), any(), any()))
                .thenReturn(List.of(IN, IN.plusDays(1)));

        assertThatThrownBy(() -> service.createBooking(request()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("BR-LT-03").hasMessageContaining("12/06, 13/06");

        verify(codes, never()).next(any(), any(), any());
        verify(bookings, never()).saveAndFlush(any());
    }
}
