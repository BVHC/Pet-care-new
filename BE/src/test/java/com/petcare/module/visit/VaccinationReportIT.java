package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.visit.api.VaccinationReportApi;
import com.petcare.module.visit.api.VaccinationReportApi.ReturnRateRow;

/**
 * {@code VaccinationReportApi} (BR-BC-04, BR-BC-03 (4); docs/adr/0087) trên Postgres 17 thật: mẫu số là mũi có ngày tái
 * chủng trong kỳ trừ thú mất trước ngày đó; tử số là mũi đã tiêm lại cùng loại không muộn quá 7 ngày [CFG] sau ngày tái
 * chủng; tính cho chi nhánh của mũi trước. {@code pendingCount} (docs/adr/0087 G11, ADR-0090): mũi trong mẫu số chưa
 * quay lại mà {@code next_due_date} + grace chưa qua "hôm nay" (bên gọi truyền, tính cả ngày cuối). Dữ liệu năm 2031
 * (không job nào quét tương lai).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class VaccinationReportIT {

    private static final LocalDate FROM = LocalDate.parse("2031-03-01");
    private static final LocalDate TO = LocalDate.parse("2031-03-31");
    private static final LocalDate DUE = LocalDate.parse("2031-03-10");
    /** Mũi lại có hạn xa để không tự thành mẫu số của kỳ. */
    private static final LocalDate FAR = LocalDate.parse("2032-03-10");
    private static final Instant AT = Instant.parse("2031-03-05T03:00:00Z");
    /** "Hôm nay" muộn hơn {@code DUE} + grace: không mũi nào còn trong thời gian chờ nên {@code pendingCount} = 0. */
    private static final LocalDate LATER = LocalDate.parse("2031-04-01");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private VaccinationReportApi api;
    @Autowired
    private SystemConfigApi configs;

    private ReminderItFixture fx;

    @BeforeEach
    void setUp() {
        fx = new ReminderItFixture(jdbc);
        fx.setUp();
    }

    @AfterEach
    void cleanUp() {
        fx.cleanUp();
    }

    /** Một thú của khách tại quầy với mũi hạn {@code due}; trả {@code [petId, doseId]}. */
    private long[] petWithDose(long branchId, LocalDate due, Instant supersededAt) {
        long pet = fx.pet(fx.counter);
        long visit = fx.completedVisit(branchId, fx.counter, pet, AT);
        long dose = fx.vaccines.dose(visit, fx.type, fx.protocol, 1, fx.product, fx.lot, due.minusDays(365), due,
                fx.vet, supersededAt);
        return new long[] {pet, dose};
    }

    /** Mũi lại của {@code pet} tiêm ngày {@code on} tại {@code branchId}, loại {@code type}. */
    private void reDose(long pet, long branchId, long type, LocalDate on) {
        long visit = fx.completedVisit(branchId, fx.counter, pet, AT.plusSeconds(60));
        fx.vaccines.dose(visit, type, fx.protocol, 2, fx.product, fx.lot, on, FAR, fx.vet, null);
    }

    @Test
    void returnedWithinGraceCountsLateBeyondGraceDoesNot() {
        long a = petWithDose(fx.branch, DUE, null)[0];
        reDose(a, fx.branch, fx.type, DUE.plusDays(7));
        long b = petWithDose(fx.branch, DUE, null)[0];
        reDose(b, fx.branch, fx.type, DUE.plusDays(8));

        assertThat(api.revaccinationReturn(fx.branch, FROM, TO, LATER))
                .containsExactly(new ReturnRateRow(fx.branch, fx.type, 2, 1, 0));
    }

    @Test
    void deceasedBeforeDueDateIsExcluded() {
        long gone = petWithDose(fx.branch, DUE, null)[0];
        jdbc.update("UPDATE pets SET deceased_on = ? WHERE id = ?", DUE.minusDays(1), gone);
        long dyingOnTheDay = petWithDose(fx.branch, DUE, null)[0];
        jdbc.update("UPDATE pets SET deceased_on = ? WHERE id = ?", DUE, dyingOnTheDay);

        assertThat(api.revaccinationReturn(fx.branch, FROM, TO, LATER))
                .containsExactly(new ReturnRateRow(fx.branch, fx.type, 1, 0, 0));
    }

    @Test
    void supersededDoseStaysInDenominatorAndCountsAsReturned() {
        long[] pet = petWithDose(fx.branch, DUE, AT);
        reDose(pet[0], fx.branch, fx.type, DUE.minusDays(5));

        assertThat(api.revaccinationReturn(fx.branch, FROM, TO, LATER))
                .containsExactly(new ReturnRateRow(fx.branch, fx.type, 1, 1, 0));
    }

    @Test
    void otherVaccineTypeIsNotAReturn() {
        long pet = petWithDose(fx.branch, DUE, null)[0];
        long otherType = fx.vaccines.vaccineType("DOG", "Khác ST-BC " + pet);
        reDose(pet, fx.branch, otherType, DUE);

        assertThat(api.revaccinationReturn(fx.branch, FROM, TO, LATER))
                .containsExactly(new ReturnRateRow(fx.branch, fx.type, 1, 0, 0));
    }

    @Test
    void creditedToBranchOfPreviousDose() {
        long pet = petWithDose(fx.branch, DUE, null)[0];
        reDose(pet, fx.otherBranch, fx.type, DUE);

        assertThat(api.revaccinationReturn(fx.branch, FROM, TO, LATER))
                .containsExactly(new ReturnRateRow(fx.branch, fx.type, 1, 1, 0));
        assertThat(api.revaccinationReturn(fx.otherBranch, FROM, TO, LATER)).isEmpty();
    }

    @Test
    void dueDateOutsidePeriodIsNotCounted() {
        petWithDose(fx.branch, FROM.minusDays(1), null);
        petWithDose(fx.branch, TO.plusDays(1), null);

        assertThat(api.revaccinationReturn(fx.branch, FROM, TO, LATER)).isEmpty();
    }

    /** Hạn tiêm lại muộn tối đa [CFG] {@code report.revaccination_grace_days} (seed 7), đọc như service. */
    private int grace() {
        return configs.getInt(ConfigKey.REPORT_REVACCINATION_GRACE_DAYS);
    }

    @Test
    void pendingCountsNotReturnedDosesStillInsideGraceWindow() {
        petWithDose(fx.branch, DUE, null);
        long back = petWithDose(fx.branch, DUE, null)[0];
        reDose(back, fx.branch, fx.type, DUE);

        assertThat(api.revaccinationReturn(fx.branch, FROM, TO, DUE))
                .containsExactly(new ReturnRateRow(fx.branch, fx.type, 2, 1, 1));
        // cùng dữ liệu, hết thời gian chờ: mẫu số và tử số không đổi, chỉ pendingCount về 0
        assertThat(api.revaccinationReturn(fx.branch, FROM, TO, LATER))
                .containsExactly(new ReturnRateRow(fx.branch, fx.type, 2, 1, 0));
    }

    @Test
    void pendingBoundaryIsInclusiveOnLastGraceDay() {
        petWithDose(fx.branch, DUE, null);

        assertThat(api.revaccinationReturn(fx.branch, FROM, TO, DUE.plusDays(grace())))
                .containsExactly(new ReturnRateRow(fx.branch, fx.type, 1, 0, 1));
        assertThat(api.revaccinationReturn(fx.branch, FROM, TO, DUE.plusDays(grace() + 1L)))
                .containsExactly(new ReturnRateRow(fx.branch, fx.type, 1, 0, 0));
    }

    @Test
    void returnedDoseIsNeverPending() {
        long pet = petWithDose(fx.branch, DUE, null)[0];
        reDose(pet, fx.branch, fx.type, DUE.plusDays(grace()));

        assertThat(api.revaccinationReturn(fx.branch, FROM, TO, DUE))
                .containsExactly(new ReturnRateRow(fx.branch, fx.type, 1, 1, 0));
    }

    @Test
    void doseNotYetDueAtAllIsPending() {
        petWithDose(fx.branch, DUE, null);

        assertThat(api.revaccinationReturn(fx.branch, FROM, TO, DUE.minusDays(30)))
                .containsExactly(new ReturnRateRow(fx.branch, fx.type, 1, 0, 1));
    }
}
