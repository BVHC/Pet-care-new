package com.petcare.module.visit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import com.petcare.module.customer.api.Species;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * Placeholder tầng 3 của visit (T55b, docs/06 §6b hàng 12, 13, 15, 16): method {@code default} ném
 * {@link UnsupportedOperationException} tới khi bản thật ghi đè (docs/adr/0113). {@code CALLS_REAL_METHODS} gọi đúng thân
 * {@code default}, nên test không cần lớp cài.
 */
class VisitApiTier3PlaceholderTest {

    private final VisitQueryApi visits = mock(VisitQueryApi.class, CALLS_REAL_METHODS);
    private final VaccinationQueryApi vaccinations = mock(VaccinationQueryApi.class, CALLS_REAL_METHODS);

    @Test
    void findLastServiceDatesThrowsUntilImplemented() {
        assertThatThrownBy(() -> visits.findLastServiceDates(1L))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("VisitQueryApi.findLastServiceDates")
                .hasMessageContaining("T78");
    }

    @Test
    void countCompletedByServiceThrowsUntilImplemented() {
        assertThatThrownBy(() -> visits.countCompletedByService(Species.DOG, VisitQueryApi.AgeGroup.ADULT,
                LocalDate.of(2026, 8, 1)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("VisitQueryApi.countCompletedByService")
                .hasMessageContaining("T78");
    }

    @Test
    void countOpenByBranchThrowsUntilImplemented() {
        assertThatThrownBy(() -> visits.countOpenByBranch(1L))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("VisitQueryApi.countOpenByBranch")
                .hasMessageContaining("T75");
    }

    @Test
    void findDoseHistoryThrowsUntilImplemented() {
        assertThatThrownBy(() -> vaccinations.findDoseHistory(1L))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("VaccinationQueryApi.findDoseHistory")
                .hasMessageContaining("T77");
    }

    @Test
    void findByStockLotThrowsUntilImplemented() {
        assertThatThrownBy(() -> vaccinations.findByStockLot(1L))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("VaccinationQueryApi.findByStockLot")
                .hasMessageContaining("T74");
    }

    /** Nhóm tuổi theo docs/07 §7.3 (L315): bốn giá trị, thứ tự từ trẻ đến già rồi UNKNOWN. */
    @Test
    void ageGroupHasTheFourValuesOfSpec07() {
        assertThat(VisitQueryApi.AgeGroup.values())
                .containsExactly(VisitQueryApi.AgeGroup.JUNIOR, VisitQueryApi.AgeGroup.ADULT,
                        VisitQueryApi.AgeGroup.SENIOR, VisitQueryApi.AgeGroup.UNKNOWN);
    }

    @Test
    void doseAndLotRecordsCarryTheFieldsOfTheContract() {
        LocalDate on = LocalDate.of(2026, 11, 1);
        LocalDate due = LocalDate.of(2027, 11, 1);

        VaccinationQueryApi.DoseRecord dose = new VaccinationQueryApi.DoseRecord(10L, 3L, 2, on, due, true);
        assertThat(dose.vaccinationId()).isEqualTo(10L);
        assertThat(dose.vaccineTypeId()).isEqualTo(3L);
        assertThat(dose.doseNumber()).isEqualTo(2);
        assertThat(dose.administeredOn()).isEqualTo(on);
        assertThat(dose.nextDueDate()).isEqualTo(due);
        assertThat(dose.superseded()).isTrue();

        VaccinationQueryApi.LotVaccination lot = new VaccinationQueryApi.LotVaccination(10L, 5L, 7L, on);
        assertThat(lot.vaccinationId()).isEqualTo(10L);
        assertThat(lot.petId()).isEqualTo(5L);
        assertThat(lot.visitId()).isEqualTo(7L);
        assertThat(lot.administeredOn()).isEqualTo(on);
    }
}
