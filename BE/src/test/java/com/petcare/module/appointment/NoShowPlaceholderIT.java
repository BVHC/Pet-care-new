package com.petcare.module.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.job.NoShowJob;
import com.petcare.platform.config.TimeConfig;
import com.petcare.support.MutableClock;

/**
 * ST05 trên bean thật của {@code BoardingQueryApi} (placeholder ném lỗi — nợ D033, docs/adr/0048 §8): mỗi lịch quá hạn
 * rollback, vẫn {@code BOOKED}, không có hạn chế, log {@code NO_SHOW_FAILED}. Khi BE-2 cài thật, test này đổi thành
 * "chuyển NO_SHOW được".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, NoShowPlaceholderIT.TestBeans.class})
@ExtendWith(OutputCaptureExtension.class)
class NoShowPlaceholderIT {

    private static final Instant T0 = ZonedDateTime
            .of(LocalDateTime.of(LocalDate.of(2000, 7, 1), LocalTime.of(10, 30)), TimeConfig.BUSINESS_ZONE).toInstant();

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private NoShowJob job;
    @Autowired
    private MutableClock clock;

    private AppointmentItSupport data;

    @BeforeEach
    void setUp() {
        data = new AppointmentItSupport(jdbc);
        clock.set(T0);
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    @Test
    void overdueAppointmentStaysBookedWhileBoardingIsNotBuilt(CapturedOutput output) {
        long branch = data.branch("ACTIVE");
        long service = data.service("MEDICAL");
        long staff = data.receptionist();
        long customer = data.counterCustomer();
        long id = data.appointment(customer, data.pet(customer), branch, service, "MEDICAL", LocalDate.of(2000, 6, 20),
                LocalTime.of(9, 0), "BOOKED", staff);

        job.run();

        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, id))
                .isEqualTo("BOOKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_restrictions WHERE customer_id = ?",
                Integer.class, customer)).isZero();
        assertThat(output).contains("NO_SHOW_FAILED appointmentId=" + id)
                .contains("BoardingQueryApi chưa được cài");
    }
}
