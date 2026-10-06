package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;

/**
 * Seed V2 khớp danh mục [CFG] (docs/adr/0004). Bảng kỳ vọng là bản sao độc lập chép từ docs/02-business-rules.md
 * (giá trị mặc định) và khoảng min–max đã duyệt trong plan, không đọc lại từ V2 hay từ ConfigKey.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SystemConfigIT {

    /** key → {value, value_type, min, max}. */
    private static final Map<String, List<String>> EXPECTED = new HashMap<>();

    static {
        expect("password.min_length", "8", "INT", "8", "64");                     // BR-TK-03
        expect("otp.code_length", "6", "INT", "4", "8");                          // BR-TK-05
        expect("otp.ttl_minutes", "5", "INT", "1", "15");                         // BR-TK-05, BR-QT-13
        expect("otp.max_failed_attempts", "5", "INT", "1", "10");                 // BR-TK-06
        expect("otp.resend_interval_seconds", "60", "INT", "30", "300");          // BR-TK-07
        expect("otp.max_sends_per_window", "5", "INT", "1", "20");                // BR-TK-07
        expect("otp.send_window_minutes", "60", "INT", "15", "1440");             // BR-TK-07
        expect("account.pending_ttl_hours", "24", "INT", "1", "168");             // BR-TK-08
        expect("login.max_failed_attempts", "5", "INT", "3", "10");               // BR-TK-09
        expect("login.failed_window_minutes", "15", "INT", "5", "60");            // BR-TK-09
        expect("login.lock_minutes", "15", "INT", "5", "1440");                   // BR-TK-09
        expect("address.max_per_customer", "5", "INT", "1", "20");                // BR-TK-18
        expect("vet.bio_max_length", "500", "INT", "100", "500");                 // BR-TK-20
        expect("audit.retention_years", "2", "INT", "2", "10");                   // BR-QT-16
        expect("session.ttl_hours", "12", "INT", "1", "72");                      // ADR-0003
        expect("appointment.min_lead_hours", "24", "INT", "0", "72");             // BR-LH-04
        expect("appointment.max_advance_days", "30", "INT", "7", "90");           // BR-LH-04
        expect("appointment.max_booked_per_pet", "2", "INT", "1", "5");           // BR-LH-05
        expect("appointment.reschedule_min_lead_hours", "12", "INT", "0", "48");  // BR-LH-06
        expect("appointment.max_reschedules", "3", "INT", "0", "10");             // BR-LH-06
        expect("appointment.late_cancel_hours", "12", "INT", "0", "48");          // BR-LH-07
        expect("appointment.no_show_after_minutes", "30", "INT", "15", "120");    // BR-LH-08
        expect("appointment.reminder_hours_before", "24", "INT", "1", "72");      // BR-LH-12
        expect("booking_restriction.violation_threshold", "3", "INT", "1", "10"); // BR-LH-09
        expect("booking_restriction.window_days", "90", "INT", "30", "365");      // BR-LH-09
        expect("booking_restriction.duration_days", "30", "INT", "1", "180");     // BR-LH-09
        expect("visit.check_in_early_minutes", "30", "INT", "0", "120");          // BR-TN-02
        expect("visit.late_priority_minutes", "15", "INT", "0", "30");            // BR-TN-03
        expect("staff.online_window_minutes", "10", "INT", "1", "60");            // BR-TN-06
        expect("medical_record.follow_up_max_days", "180", "INT", "30", "365");   // BR-KB-06
        expect("boarding.min_nights", "1", "INT", "1", "7");                      // BR-LT-02
        expect("boarding.max_nights", "30", "INT", "1", "90");                    // BR-LT-02
        expect("boarding.overdue_hold_nights", "2", "INT", "0", "7");             // BR-LT-03
        expect("boarding.min_lead_days", "1", "INT", "0", "7");                   // BR-LT-04
        expect("boarding.max_advance_days", "60", "INT", "7", "180");             // BR-LT-04
        expect("boarding.checkout_time", "12:00", "TIME", "06:00", "22:00");      // BR-LT-04
        expect("boarding.late_cancel_hours", "24", "INT", "0", "72");             // BR-LT-06
        expect("boarding.overdue_care_task_days", "1", "INT", "1", "7");          // BR-LT-10
        expect("boarding.overdue_manager_alert_days", "7", "INT", "1", "30");     // BR-LT-10
        expect("boarding.capacity_warning_days", "2", "INT", "1", "7");           // BR-LT-10
        expect("care_log.min_per_day", "1", "INT", "1", "5");                     // BR-LT-11
        expect("care_log.max_photos", "5", "INT", "0", "10");                     // BR-LT-11
        expect("care_log.edit_window_minutes", "60", "INT", "0", "1440");         // BR-LT-11
        expect("order.pending_alert_days", "1", "INT", "1", "30");                // BR-BH-05
        expect("cashier_shift.auto_close_grace_minutes", "30", "INT", "0", "180"); // BR-TG-05
        expect("stock.expiry_warning_days", "30", "INT", "7", "180");             // BR-KO-07
        expect("feedback.max_per_day", "5", "INT", "1", "20");                    // BR-DG-01
        expect("reminder.vaccine_days_before", "7", "INT", "1", "30");            // BR-TB-01
        expect("reminder.vaccine_overdue_days", "7", "INT", "1", "60");           // BR-TB-04
        expect("reminder.follow_up_days_before", "3", "INT", "1", "14");          // BR-TB-06
        expect("report.max_period_months", "12", "INT", "1", "24");               // BR-BC-01
        expect("report.revaccination_grace_days", "7", "INT", "0", "30");         // BR-BC-04
    }

    private static void expect(String key, String value, String type, String min, String max) {
        EXPECTED.put(key, List.of(value, type, min, max));
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SystemConfigApi configs;

    @Test
    void seedMatchesCatalogExactly() {
        Map<String, List<String>> actual = new HashMap<>();
        jdbc.query("SELECT key, value, value_type, min_value, max_value, unit, description FROM system_configs",
                rs -> {
                    actual.put(rs.getString("key"), List.of(rs.getString("value"), rs.getString("value_type"),
                            rs.getString("min_value"), rs.getString("max_value")));
                    assertThat(rs.getString("unit")).isNotBlank();
                    assertThat(rs.getString("description")).isNotBlank();
                });

        assertThat(EXPECTED).hasSize(52);
        assertThat(actual).isEqualTo(EXPECTED);
    }

    @Test
    void everyConfigKeyHasASeedRowOfTheSameType() {
        for (ConfigKey key : ConfigKey.values()) {
            assertThat(EXPECTED).containsKey(key.key());
            assertThat(EXPECTED.get(key.key()).get(1)).as(key.key()).isEqualTo(key.type().name());
        }
        assertThat(Arrays.stream(ConfigKey.values()).map(ConfigKey::key)).hasSameSizeAs(EXPECTED.keySet());
    }

    @Test
    void applicationReadsSeededValues() {
        assertThat(configs.getInt(ConfigKey.SESSION_TTL_HOURS)).isEqualTo(12);
        assertThat(configs.getInt(ConfigKey.OTP_TTL_MINUTES)).isEqualTo(5);
        assertThat(configs.getTime(ConfigKey.BOARDING_CHECKOUT_TIME)).isEqualTo(LocalTime.NOON);
    }
}
