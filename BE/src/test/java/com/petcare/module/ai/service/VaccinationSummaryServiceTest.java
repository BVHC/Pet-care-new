package com.petcare.module.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.petcare.module.ai.chat.ChatTexts;
import com.petcare.module.ai.dto.VaccinationItem;
import com.petcare.module.ai.dto.VaccinationStatus;
import com.petcare.module.ai.dto.VaccinationSummary;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProtocolDose;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.Species;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.visit.api.VaccinationQueryApi;
import com.petcare.module.visit.api.VaccinationQueryApi.DoseRecord;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;

/**
 * Tra lịch tiêm tất định (BR-AI-11, ADR-0132) với các {@code *QueryApi} giả: mỗi loại vaccine theo phác đồ của loài lấy
 * mũi mới nhất chưa bị thay thế, năm trạng thái, ngưỡng {@code DUE_SOON} đọc từ [CFG] ở MỖI lần gọi (cùng biên với cửa sổ
 * nhắc ST04: hai cận đều tính), quyền sở hữu kiểm lại mỗi lần gọi.
 */
class VaccinationSummaryServiceTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final long OWNER = 7L;
    private static final long PET = 12L;
    private static final long RABIES = 1L;
    private static final long DHPP = 2L;

    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final VaccinationQueryApi doses = mock(VaccinationQueryApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final SystemConfigApi config = mock(SystemConfigApi.class);
    private final Clock clock = Clock.fixed(TODAY.atTime(9, 0).atZone(VN).toInstant(), VN);
    private VaccinationSummaryService service;

    @BeforeEach
    void setUp() {
        when(config.getInt(ConfigKey.REMINDER_VACCINE_DAYS_BEFORE)).thenReturn(7);
        when(pets.findPet(PET)).thenReturn(Optional.of(pet(OWNER, TODAY.minusYears(1), null)));
        when(catalog.listActiveProtocols(Species.DOG)).thenReturn(List.of(protocol(RABIES, 1, 12), protocol(RABIES, 2, 12)));
        when(catalog.findVaccineTypeNames(any())).thenReturn(Map.of(RABIES, "Dại", DHPP, "5 bệnh"));
        when(doses.findDoseHistory(PET)).thenReturn(List.of());
        when(customers.findCustomerIdByAccountId(99L)).thenReturn(Optional.of(OWNER));
        service = new VaccinationSummaryService(pets, doses, catalog, customers, config, clock);
    }

    // ---- quyền sở hữu: kiểm lại mỗi lần gọi, thú người khác là 404, kiểm chủ TRƯỚC kiểm đã mất ---------------------------

    @Test
    void anUnknownPetIsNotFound() {
        when(pets.findPet(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.summarize(OWNER, 404L)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy thú cưng #404");
    }

    @Test
    void aPetOfAnotherCustomerOrAFormerOwnerIsNotFoundWithTheSameMessage() {
        assertThatThrownBy(() -> service.summarize(OWNER + 1, PET)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy thú cưng #12");
    }

    @Test
    void anAccountWithoutACustomerProfileOwnsNothing() {
        assertThatThrownBy(() -> service.summarize(null, PET)).isInstanceOf(ResourceNotFoundException.class);
        when(customers.findCustomerIdByAccountId(5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.summarizeForAccount(5L, PET)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theAccountIsResolvedToTheCustomerOnEveryCall() {
        assertThat(service.summarizeForAccount(99L, PET).petId()).isEqualTo(PET);
        when(customers.findCustomerIdByAccountId(99L)).thenReturn(Optional.of(OWNER + 1));

        assertThatThrownBy(() -> service.summarizeForAccount(99L, PET)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aDeceasedPetOfTheOwnerIsABusinessRuleViolation() {
        when(pets.findPet(PET)).thenReturn(Optional.of(pet(OWNER, TODAY.minusYears(1), TODAY.minusDays(3))));

        assertThatThrownBy(() -> service.summarize(OWNER, PET)).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("BR-KH-05");
    }

    @Test
    void aDeceasedPetOfSomeoneElseIsStillNotFoundNotARuleViolation() {
        when(pets.findPet(PET)).thenReturn(Optional.of(pet(OWNER + 1, TODAY.minusYears(1), TODAY.minusDays(3))));

        assertThatThrownBy(() -> service.summarize(OWNER, PET)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- trạng thái -------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "hạn {0} ngày so với hôm nay, N = 7 → {1}")
    @CsvSource({"-1,OVERDUE", "0,DUE_SOON", "1,DUE_SOON", "7,DUE_SOON", "8,UP_TO_DATE", "300,UP_TO_DATE"})
    void theStatusFollowsTheNextDueDateWithBothBoundsInclusive(int offsetDays, VaccinationStatus expected) {
        when(doses.findDoseHistory(PET)).thenReturn(List.of(dose(1, RABIES, 1, TODAY.minusDays(20), TODAY.plusDays(offsetDays), false)));

        VaccinationItem item = onlyItem();

        assertThat(item.status()).isEqualTo(expected);
        assertThat(item.lastDoseNumber()).isEqualTo(1);
        assertThat(item.lastAdministeredOn()).isEqualTo(TODAY.minusDays(20));
        assertThat(item.nextDueDate()).isEqualTo(TODAY.plusDays(offsetDays));
        assertThat(item.nextDoseNumber()).as("mũi 2 có trong phác đồ").isEqualTo(2);
        assertThat(item.firstDoseFromAgeWeeks()).isNull();
    }

    @Test
    void theDueSoonWindowIsReadFromTheConfigOnEveryCall() {
        when(doses.findDoseHistory(PET)).thenReturn(List.of(dose(1, RABIES, 1, TODAY.minusDays(20), TODAY.plusDays(5), false)));

        when(config.getInt(ConfigKey.REMINDER_VACCINE_DAYS_BEFORE)).thenReturn(3);
        assertThat(onlyItem().status()).as("N = 3, hạn sau 5 ngày").isEqualTo(VaccinationStatus.UP_TO_DATE);

        when(config.getInt(ConfigKey.REMINDER_VACCINE_DAYS_BEFORE)).thenReturn(5);
        assertThat(onlyItem().status()).as("N = 5, đúng ngày cuối cửa sổ").isEqualTo(VaccinationStatus.DUE_SOON);

        when(config.getInt(ConfigKey.REMINDER_VACCINE_DAYS_BEFORE)).thenReturn(10);
        assertThat(onlyItem().status()).as("N = 10").isEqualTo(VaccinationStatus.DUE_SOON);

        verify(config, times(3)).getInt(ConfigKey.REMINDER_VACCINE_DAYS_BEFORE);
    }

    @Test
    void aSupersededDoseIsIgnoredEvenWhenItIsTheNewest() {
        when(doses.findDoseHistory(PET)).thenReturn(List.of(
                dose(3, RABIES, 2, TODAY.minusDays(2), TODAY.plusDays(300), true),
                dose(2, RABIES, 1, TODAY.minusDays(20), TODAY.minusDays(1), false)));

        VaccinationItem item = onlyItem();

        assertThat(item.status()).isEqualTo(VaccinationStatus.OVERDUE);
        assertThat(item.lastDoseNumber()).isEqualTo(1);
    }

    @Test
    void theLatestOpenDoseWinsWhateverTheOrderOfTheHistory() {
        when(doses.findDoseHistory(PET)).thenReturn(List.of(
                dose(2, RABIES, 1, TODAY.minusDays(60), TODAY.minusDays(30), false),
                dose(5, RABIES, 2, TODAY.minusDays(5), TODAY.plusDays(360), false)));

        VaccinationItem item = onlyItem();

        assertThat(item.status()).isEqualTo(VaccinationStatus.UP_TO_DATE);
        assertThat(item.lastDoseNumber()).isEqualTo(2);
        assertThat(item.nextDoseNumber()).as("phác đồ chỉ có 2 mũi nên không có mũi 3").isNull();
    }

    @Test
    void aDoseOfAVaccineOutsideTheSpeciesProtocolIsIgnored() {
        when(doses.findDoseHistory(PET)).thenReturn(List.of(dose(9, DHPP, 1, TODAY.minusDays(5), TODAY.plusDays(2), false)));

        assertThat(onlyItem().status()).isEqualTo(VaccinationStatus.NOT_STARTED);
    }

    @ParameterizedTest(name = "tuổi {0} tuần, mũi đầu từ 12 tuần → {1}")
    @CsvSource({"5,NOT_YET_ELIGIBLE", "11,NOT_YET_ELIGIBLE", "12,NOT_STARTED", "60,NOT_STARTED"})
    void withoutADoseTheStatusDependsOnTheAgeAgainstTheFirstDose(int ageWeeks, VaccinationStatus expected) {
        when(pets.findPet(PET)).thenReturn(Optional.of(pet(OWNER, TODAY.minusWeeks(ageWeeks), null)));

        VaccinationItem item = onlyItem();

        assertThat(item.status()).isEqualTo(expected);
        assertThat(item.firstDoseFromAgeWeeks()).isEqualTo(12);
        assertThat(item.lastDoseNumber()).isNull();
        assertThat(item.nextDueDate()).isNull();
    }

    @Test
    void withoutABirthDateTheAgeIsNotJudged() {
        when(pets.findPet(PET)).thenReturn(Optional.of(pet(OWNER, null, null)));

        assertThat(onlyItem().status()).isEqualTo(VaccinationStatus.NOT_STARTED);
    }

    @Test
    void theFirstDoseAgeIsTheMinimumOfTheDoseOneRowNotOfLaterDoses() {
        when(catalog.listActiveProtocols(Species.DOG)).thenReturn(List.of(protocol(RABIES, 1, 12), protocol(RABIES, 2, 20)));

        assertThat(onlyItem().firstDoseFromAgeWeeks()).isEqualTo(12);
    }

    // ---- nội dung ---------------------------------------------------------------------------------------------------

    @Test
    void oneItemPerVaccineOfTheProtocolInTheOrderOfTheCatalog() {
        when(catalog.listActiveProtocols(Species.DOG)).thenReturn(List.of(
                protocol(DHPP, 1, 6), protocol(DHPP, 2, 6), protocol(RABIES, 1, 12)));
        when(doses.findDoseHistory(PET)).thenReturn(List.of(dose(1, DHPP, 1, TODAY.minusDays(25), TODAY.plusDays(3), false)));

        VaccinationSummary summary = service.summarize(OWNER, PET);

        assertThat(summary.petId()).isEqualTo(PET);
        assertThat(summary.petName()).isEqualTo("Mochi");
        assertThat(summary.items()).extracting(VaccinationItem::vaccineTypeId, VaccinationItem::vaccineName,
                VaccinationItem::status).containsExactly(
                        org.assertj.core.groups.Tuple.tuple(DHPP, "5 bệnh", VaccinationStatus.DUE_SOON),
                        org.assertj.core.groups.Tuple.tuple(RABIES, "Dại", VaccinationStatus.NOT_STARTED));
        assertThat(summary.disclaimer()).isEqualTo(ChatTexts.DISCLAIMER);
    }

    @Test
    void theMessageIsAFixedTemplateOfOneLinePerVaccine() {
        when(catalog.listActiveProtocols(Species.DOG)).thenReturn(List.of(
                protocol(DHPP, 1, 6), protocol(DHPP, 2, 6), protocol(RABIES, 1, 12)));
        when(doses.findDoseHistory(PET)).thenReturn(List.of(dose(1, DHPP, 1, LocalDate.of(2026, 10, 16), LocalDate.of(2026, 11, 12), false)));

        assertThat(service.summarize(OWNER, PET).message()).isEqualTo("""
                Lịch tiêm của Mochi (chỉ tính các mũi ghi nhận tại hệ thống):
                - 5 bệnh: sắp đến hạn mũi tiếp theo vào 12/11/2026 (đã tiêm mũi 1 ngày 16/10/2026).
                - Dại: chưa tiêm tại hệ thống, mũi đầu tiên từ 12 tuần tuổi.""");
    }

    @Test
    void everyStatusHasItsOwnWording() {
        when(catalog.listActiveProtocols(Species.DOG)).thenReturn(List.of(protocol(RABIES, 1, 12)));
        when(pets.findPet(PET)).thenReturn(Optional.of(pet(OWNER, TODAY.minusWeeks(5), null)));
        assertThat(service.summarize(OWNER, PET).message()).contains("- Dại: bé còn nhỏ, mũi đầu tiên từ 12 tuần tuổi.");

        when(pets.findPet(PET)).thenReturn(Optional.of(pet(OWNER, TODAY.minusYears(1), null)));
        when(doses.findDoseHistory(PET)).thenReturn(List.of(dose(1, RABIES, 1, LocalDate.of(2026, 10, 16), TODAY.minusDays(1), false)));
        assertThat(service.summarize(OWNER, PET).message())
                .contains("- Dại: đã quá hạn mũi tiếp theo từ 09/11/2026 (đã tiêm mũi 1 ngày 16/10/2026).");

        when(doses.findDoseHistory(PET)).thenReturn(List.of(dose(1, RABIES, 1, LocalDate.of(2026, 10, 16), LocalDate.of(2027, 10, 16), false)));
        assertThat(service.summarize(OWNER, PET).message())
                .contains("- Dại: còn hạn, đã tiêm mũi 1 ngày 16/10/2026; mũi tiếp theo hạn 16/10/2027.");
    }

    @Test
    void aVaccineWithoutAKnownNameDoesNotBreakTheSummary() {
        when(catalog.findVaccineTypeNames(any())).thenReturn(Map.of());

        assertThat(onlyItem().vaccineName()).isEqualTo("Vaccine #1");
    }

    @Test
    void aSpeciesWithoutAnActiveProtocolGetsAnEmptyListAndAPlainSentence() {
        when(catalog.listActiveProtocols(Species.DOG)).thenReturn(List.of());

        VaccinationSummary summary = service.summarize(OWNER, PET);

        assertThat(summary.items()).isEmpty();
        assertThat(summary.message()).isEqualTo("Chưa có phác đồ tiêm cho loài của Mochi trong danh mục.");
    }

    // ---- hỗ trợ -----------------------------------------------------------------------------------------------------

    private VaccinationItem onlyItem() {
        return service.summarize(OWNER, PET).items().get(0);
    }

    private static PetSummary pet(long customerId, LocalDate birthDate, LocalDate deceasedOn) {
        return new PetSummary(PET, customerId, "Mochi", Species.DOG, birthDate, deceasedOn, new BigDecimal("8.0"));
    }

    private static ProtocolDose protocol(long vaccineTypeId, int doseNumber, int minAgeWeeks) {
        return new ProtocolDose(vaccineTypeId * 100 + doseNumber, Species.DOG, vaccineTypeId, doseNumber, 30, minAgeWeeks,
                false, true);
    }

    private static DoseRecord dose(long id, long vaccineTypeId, int doseNumber, LocalDate administeredOn, LocalDate nextDue,
            boolean superseded) {
        return new DoseRecord(id, vaccineTypeId, doseNumber, administeredOn, nextDue, superseded);
    }
}
