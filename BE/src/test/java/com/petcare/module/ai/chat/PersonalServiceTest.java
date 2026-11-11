package com.petcare.module.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.petcare.module.ai.dto.PetChoice;
import com.petcare.module.ai.dto.VaccinationSummary;
import com.petcare.module.ai.entity.ChatResult;
import com.petcare.module.ai.service.VaccinationSummaryService;
import com.petcare.module.appointment.api.AppointmentQueryApi;
import com.petcare.module.appointment.api.AppointmentQueryApi.UpcomingBooked;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.customer.api.PetQueryApi.LivingPet;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;

/**
 * Trả lời cá nhân bằng mẫu (BR-AI-27, ADR-0132) với các {@code *QueryApi} giả. I-10 đã lọc {@code >= now} trong SQL, nên
 * lọc lại ở module {@code ai} chỉ kiểm được ở đây (lớp giả trả cả lịch đã quá giờ): đây là test duy nhất giữ lọc đó.
 */
class PersonalServiceTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final LocalDateTime NOW = TODAY.atTime(9, 0);
    private static final long CUSTOMER = 7L;
    private static final LivingPet MOCHI = new LivingPet(12L, "Mochi");
    private static final LivingPet KITTY = new LivingPet(15L, "Kitty");

    private final AppointmentQueryApi appointments = mock(AppointmentQueryApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final VaccinationSummaryService vaccinations = mock(VaccinationSummaryService.class);
    private final SystemConfigApi config = mock(SystemConfigApi.class);
    private final Clock clock = Clock.fixed(NOW.atZone(VN).toInstant(), VN);
    private PersonalService service;

    @BeforeEach
    void setUp() {
        when(config.getInt(ConfigKey.AI_PERSONAL_APPOINTMENTS_LIMIT)).thenReturn(5);
        when(catalog.findServices(any())).thenReturn(List.of(service(31L, "Tiêm phòng"), service(32L, "Tắm & vệ sinh")));
        when(branches.findBranch(2L)).thenReturn(Optional.of(new BranchSummary(2L, "Pet Care Cầu Giấy", "1 Cầu Giấy",
                "0901234567", BranchStatus.ACTIVE, true)));
        service = new PersonalService(appointments, catalog, branches, vaccinations, config, clock);
    }

    // ---- lịch hẹn sắp tới ------------------------------------------------------------------------------------------

    @Test
    void upcomingAppointmentsAreListedFromTheFixedTemplate() {
        when(appointments.findUpcomingBookedOfCustomer(eq(CUSTOMER), eq(NOW), anyInt())).thenReturn(List.of(
                booked(1L, "LH-261105-0012", MOCHI, 31L, TODAY.plusDays(1), LocalTime.of(9, 0)),
                booked(2L, "LH-261105-0013", KITTY, 32L, TODAY.plusDays(3), LocalTime.of(14, 30))));

        PersonalService.Reply reply = appointmentsFor(List.of(MOCHI, KITTY));

        assertThat(reply.result()).isEqualTo(ChatResult.PERSONAL);
        assertThat(reply.bookingHint()).isFalse();
        assertThat(reply.pets()).isEmpty();
        assertThat(reply.answer()).isEqualTo("""
                Bạn có 2 lịch hẹn sắp tới:
                1) Mochi — Tiêm phòng — 11/11/2026 09:00 — Pet Care Cầu Giấy (LH-261105-0012)
                2) Kitty — Tắm & vệ sinh — 13/11/2026 14:30 — Pet Care Cầu Giấy (LH-261105-0013)""");
    }

    @Test
    void anAppointmentAlreadyPastIsFilteredAgainEvenIfTheInterfaceReturnsIt() {
        when(appointments.findUpcomingBookedOfCustomer(eq(CUSTOMER), eq(NOW), anyInt())).thenReturn(List.of(
                booked(1L, "LH-1", MOCHI, 31L, TODAY, LocalTime.of(8, 59)),
                booked(2L, "LH-2", MOCHI, 31L, TODAY.minusDays(1), LocalTime.of(15, 0)),
                booked(3L, "LH-3", MOCHI, 31L, TODAY, LocalTime.of(9, 0)),
                booked(4L, "LH-4", MOCHI, 31L, TODAY.plusDays(1), LocalTime.of(8, 0))));

        PersonalService.Reply reply = appointmentsFor(List.of(MOCHI));

        assertThat(reply.answer()).contains("(LH-3)", "(LH-4)").doesNotContain("LH-1", "LH-2").startsWith("Bạn có 2 lịch");
    }

    @Test
    void theListIsSortedByTimeAndCappedAtTheConfiguredLimit() {
        when(config.getInt(ConfigKey.AI_PERSONAL_APPOINTMENTS_LIMIT)).thenReturn(2);
        when(appointments.findUpcomingBookedOfCustomer(eq(CUSTOMER), eq(NOW), anyInt())).thenReturn(List.of(
                booked(3L, "LH-C", MOCHI, 31L, TODAY.plusDays(5), LocalTime.of(9, 0)),
                booked(1L, "LH-A", MOCHI, 31L, TODAY.plusDays(1), LocalTime.of(9, 0)),
                booked(2L, "LH-B", MOCHI, 31L, TODAY.plusDays(1), LocalTime.of(8, 0))));

        PersonalService.Reply reply = appointmentsFor(List.of(MOCHI));

        verify(appointments).findUpcomingBookedOfCustomer(CUSTOMER, NOW, 2);
        assertThat(reply.answer()).startsWith("Bạn có 2 lịch").containsSubsequence("(LH-B)", "(LH-A)").doesNotContain("LH-C");
    }

    @Test
    void anAppointmentOfAPetThatIsNoLongerAliveOrNoLongerTheirsIsNotShown() {
        when(appointments.findUpcomingBookedOfCustomer(eq(CUSTOMER), eq(NOW), anyInt())).thenReturn(List.of(
                booked(1L, "LH-GONE", new LivingPet(99L, "Đã mất"), 31L, TODAY.plusDays(1), LocalTime.of(9, 0)),
                booked(2L, "LH-OK", MOCHI, 31L, TODAY.plusDays(1), LocalTime.of(10, 0))));

        assertThat(appointmentsFor(List.of(MOCHI)).answer()).contains("(LH-OK)").doesNotContain("LH-GONE");
    }

    @Test
    void noUpcomingAppointmentGetsTheStandardSentenceWithABookingHint() {
        when(appointments.findUpcomingBookedOfCustomer(eq(CUSTOMER), eq(NOW), anyInt())).thenReturn(List.of());

        PersonalService.Reply reply = appointmentsFor(List.of(MOCHI));

        assertThat(reply.result()).isEqualTo(ChatResult.PERSONAL);
        assertThat(reply.answer()).isEqualTo("Bạn chưa có lịch hẹn sắp tới.");
        assertThat(reply.bookingHint()).isTrue();
    }

    @Test
    void anAccountWithoutACustomerProfileHasNoAppointmentsAndNothingIsQueried() {
        PersonalService.Reply reply = service.answer(PersonalIntent.APPOINTMENT_UPCOMING, null, List.of(), List.of());

        assertThat(reply.answer()).isEqualTo("Bạn chưa có lịch hẹn sắp tới.");
        assertThat(reply.bookingHint()).isTrue();
        verifyNoInteractions(appointments);
    }

    @Test
    void aServiceOrBranchThatCannotBeResolvedDoesNotBreakTheLine() {
        when(catalog.findServices(any())).thenReturn(List.of());
        when(branches.findBranch(2L)).thenReturn(Optional.empty());
        when(appointments.findUpcomingBookedOfCustomer(eq(CUSTOMER), eq(NOW), anyInt())).thenReturn(List.of(
                booked(1L, "LH-1", MOCHI, 31L, TODAY.plusDays(1), LocalTime.of(9, 0))));

        assertThat(appointmentsFor(List.of(MOCHI)).answer())
                .contains("1) Mochi — Dịch vụ #31 — 11/11/2026 09:00 — Chi nhánh #2 (LH-1)");
    }

    // ---- lịch tiêm: chọn thú ---------------------------------------------------------------------------------------

    @Test
    void aNamedPetIsUsedEvenWhenTheCustomerHasSeveral() {
        when(vaccinations.summarize(CUSTOMER, 12L)).thenReturn(summary("Lịch tiêm của Mochi: …"));

        PersonalService.Reply reply = service.answer(PersonalIntent.VACCINATION_SCHEDULE, CUSTOMER, List.of(MOCHI, KITTY),
                List.of("mochi"));

        assertThat(reply.result()).isEqualTo(ChatResult.PERSONAL);
        assertThat(reply.answer()).isEqualTo("Lịch tiêm của Mochi: …");
        verify(vaccinations).summarize(CUSTOMER, 12L);
        verifyNoInteractions(appointments);
    }

    @Test
    void aCustomerWithExactlyOneLivingPetNeedsNoName() {
        when(vaccinations.summarize(CUSTOMER, 15L)).thenReturn(summary("Lịch tiêm của Kitty: …"));

        PersonalService.Reply reply = service.answer(PersonalIntent.VACCINATION_SCHEDULE, CUSTOMER, List.of(KITTY), List.of());

        assertThat(reply.answer()).isEqualTo("Lịch tiêm của Kitty: …");
    }

    @Test
    void severalPetsAndNoNameAsksWhichOneWithAllOfThem() {
        PersonalService.Reply reply = service.answer(PersonalIntent.VACCINATION_SCHEDULE, CUSTOMER, List.of(MOCHI, KITTY),
                List.of());

        assertThat(reply.result()).isEqualTo(ChatResult.NEED_PET_CHOICE);
        assertThat(reply.answer()).isEqualTo("Bạn muốn xem lịch tiêm của bé nào?");
        assertThat(reply.pets()).containsExactly(new PetChoice(12L, "Mochi"), new PetChoice(15L, "Kitty"));
        assertThat(reply.bookingHint()).isFalse();
        verifyNoInteractions(vaccinations);
    }

    @Test
    void twoNamedPetsAreAmbiguousAndOnlyThoseTwoAreOffered() {
        LivingPet third = new LivingPet(20L, "Lu");

        PersonalService.Reply reply = service.answer(PersonalIntent.VACCINATION_SCHEDULE, CUSTOMER,
                List.of(MOCHI, KITTY, third), List.of("mochi", "kitty"));

        assertThat(reply.result()).isEqualTo(ChatResult.NEED_PET_CHOICE);
        assertThat(reply.pets()).containsExactly(new PetChoice(12L, "Mochi"), new PetChoice(15L, "Kitty"));
    }

    @Test
    void twoPetsWithTheSameNameAreAskedBackBecauseTheNameDoesNotIdentifyOne() {
        LivingPet twin = new LivingPet(30L, "Mochi");

        PersonalService.Reply reply = service.answer(PersonalIntent.VACCINATION_SCHEDULE, CUSTOMER,
                List.of(MOCHI, twin, KITTY), List.of("mochi"));

        assertThat(reply.result()).isEqualTo(ChatResult.NEED_PET_CHOICE);
        assertThat(reply.pets()).containsExactly(new PetChoice(12L, "Mochi"), new PetChoice(30L, "Mochi"));
    }

    @Test
    void noLivingPetGetsTheAddAPetSentence() {
        PersonalService.Reply reply = service.answer(PersonalIntent.VACCINATION_SCHEDULE, CUSTOMER, List.of(), List.of());

        assertThat(reply.result()).isEqualTo(ChatResult.PERSONAL);
        assertThat(reply.answer()).isEqualTo("Bạn chưa có thú cưng nào trong hồ sơ. Hãy thêm thú cưng để xem lịch tiêm.");
        assertThat(reply.bookingHint()).isFalse();
        verifyNoInteractions(vaccinations);
    }

    // ---- hỗ trợ -----------------------------------------------------------------------------------------------------

    private PersonalService.Reply appointmentsFor(List<LivingPet> living) {
        return service.answer(PersonalIntent.APPOINTMENT_UPCOMING, CUSTOMER, living, List.of());
    }

    private static UpcomingBooked booked(long id, String code, LivingPet pet, long serviceId, LocalDate date, LocalTime start) {
        return new UpcomingBooked(id, code, pet.petId(), serviceId, 2L, date, start);
    }

    private static ServiceInfo service(long id, String name) {
        return new ServiceInfo(id, name, null, null, 100_000L, true);
    }

    private static VaccinationSummary summary(String message) {
        return new VaccinationSummary(0L, "x", List.of(), message, "d");
    }
}
