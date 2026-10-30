package com.petcare.module.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.api.AppointmentReportApi;
import com.petcare.module.appointment.api.AppointmentReportApi.NoShowRow;

/**
 * {@code AppointmentReportApi} (BR-BC-03 (3); docs/adr/0087) trên Postgres 17 thật: lịch đến hạn trong kỳ trừ lịch phòng
 * khám hủy, NO_SHOW và hủy muộn đếm theo chi nhánh. Lịch chèn bằng SQL ở năm 2031 (không job nào quét tương lai).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AppointmentReportIT {

    private static final LocalDate FROM = LocalDate.parse("2031-03-01");
    private static final LocalDate TO = LocalDate.parse("2031-03-31");
    private static final LocalDate IN_PERIOD = LocalDate.parse("2031-03-15");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AppointmentReportApi api;

    private AppointmentItSupport data;
    private long branch;
    private long service;
    private long receptionist;
    private long customer;

    @BeforeEach
    void setUp() {
        data = new AppointmentItSupport(jdbc);
        branch = data.branch("ACTIVE");
        service = data.service("MEDICAL");
        receptionist = data.receptionist();
        customer = data.counterCustomer();
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    private long appointment(long branchId, LocalDate day, String status, String cancelSource, boolean late) {
        long id = data.appointment(customer, data.pet(customer), branchId, service, "MEDICAL", day,
                LocalTime.of(9, 0), "BOOKED", receptionist);
        if (!status.equals("BOOKED")) {
            jdbc.update("""
                    UPDATE appointments SET status = ?, cancel_source = ?, late_cancel = ?,
                           cancelled_at = CASE WHEN ? = 'CANCELLED' THEN now() END WHERE id = ?""",
                    status, cancelSource, late, status, id);
        }
        return id;
    }

    @Test
    void dueExcludesClinicCancelAndCountsNoShowAndLateCancel() {
        appointment(branch, IN_PERIOD, "COMPLETED", null, false);
        appointment(branch, IN_PERIOD, "NO_SHOW", null, false);
        appointment(branch, IN_PERIOD, "CANCELLED", "CUSTOMER", true);
        appointment(branch, IN_PERIOD, "CANCELLED", "CUSTOMER", false);
        appointment(branch, IN_PERIOD, "CANCELLED", "CLINIC", false);
        appointment(branch, IN_PERIOD, "BOOKED", null, false);
        appointment(branch, TO.plusDays(1), "NO_SHOW", null, false);
        appointment(branch, FROM.minusDays(1), "NO_SHOW", null, false);

        assertThat(api.noShowAndLateCancel(branch, FROM, TO)).containsExactly(new NoShowRow(branch, 5, 1, 1));
    }

    @Test
    void periodEndsAreInclusive() {
        appointment(branch, FROM, "NO_SHOW", null, false);
        appointment(branch, TO, "NO_SHOW", null, false);

        assertThat(api.noShowAndLateCancel(branch, FROM, TO)).containsExactly(new NoShowRow(branch, 2, 2, 0));
    }

    @Test
    void branchFilterAndChain() {
        long other = data.branch("ACTIVE");
        appointment(branch, IN_PERIOD, "COMPLETED", null, false);
        appointment(other, IN_PERIOD, "NO_SHOW", null, false);

        assertThat(api.noShowAndLateCancel(other, FROM, TO)).containsExactly(new NoShowRow(other, 1, 1, 0));
        assertThat(api.noShowAndLateCancel(null, FROM, TO)).contains(new NoShowRow(branch, 1, 0, 0),
                new NoShowRow(other, 1, 1, 0));
    }
}
