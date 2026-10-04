package com.petcare.module.identity.api;

import static com.petcare.module.identity.api.ConfigValueType.INT;
import static com.petcare.module.identity.api.ConfigValueType.TIME;

/**
 * Owner: identity (QT) · BE-1. Danh mục đầy đủ tham số [CFG] (BR-QT-13), mỗi hằng số là một dòng
 * {@code system_configs} do {@code V2__seed_system_configs.sql} seed (docs/adr/0004). Lúc khởi động
 * {@code SystemConfigService} kiểm tra DB có đủ mọi hằng số, đúng kiểu, giá trị trong min–max; sai thì app dừng.
 * Thêm tham số = thêm hằng số + migration seed. Không hard-code số [CFG] trong code nghiệp vụ (convention 06).
 */
public enum ConfigKey {

    // TK, QT
    PASSWORD_MIN_LENGTH("password.min_length", INT),                       // BR-TK-03
    OTP_CODE_LENGTH("otp.code_length", INT),                               // BR-TK-05
    OTP_TTL_MINUTES("otp.ttl_minutes", INT),                               // BR-TK-05
    OTP_MAX_FAILED_ATTEMPTS("otp.max_failed_attempts", INT),               // BR-TK-06
    OTP_RESEND_INTERVAL_SECONDS("otp.resend_interval_seconds", INT),       // BR-TK-07
    OTP_MAX_SENDS_PER_WINDOW("otp.max_sends_per_window", INT),             // BR-TK-07
    OTP_SEND_WINDOW_MINUTES("otp.send_window_minutes", INT),               // BR-TK-07
    ACCOUNT_PENDING_TTL_HOURS("account.pending_ttl_hours", INT),           // BR-TK-08
    LOGIN_MAX_FAILED_ATTEMPTS("login.max_failed_attempts", INT),           // BR-TK-09
    LOGIN_FAILED_WINDOW_MINUTES("login.failed_window_minutes", INT),       // BR-TK-09
    LOGIN_LOCK_MINUTES("login.lock_minutes", INT),                         // BR-TK-09
    ADDRESS_MAX_PER_CUSTOMER("address.max_per_customer", INT),             // BR-TK-18
    VET_BIO_MAX_LENGTH("vet.bio_max_length", INT),                         // BR-TK-20
    AUDIT_RETENTION_YEARS("audit.retention_years", INT),                   // BR-QT-16
    SESSION_TTL_HOURS("session.ttl_hours", INT),                           // docs/adr/0003

    // LH
    APPOINTMENT_MIN_LEAD_HOURS("appointment.min_lead_hours", INT),                     // BR-LH-04
    APPOINTMENT_MAX_ADVANCE_DAYS("appointment.max_advance_days", INT),                 // BR-LH-04
    APPOINTMENT_MAX_BOOKED_PER_PET("appointment.max_booked_per_pet", INT),             // BR-LH-05
    APPOINTMENT_RESCHEDULE_MIN_LEAD_HOURS("appointment.reschedule_min_lead_hours", INT), // BR-LH-06
    APPOINTMENT_MAX_RESCHEDULES("appointment.max_reschedules", INT),                   // BR-LH-06
    APPOINTMENT_LATE_CANCEL_HOURS("appointment.late_cancel_hours", INT),               // BR-LH-07, BR-LH-06
    APPOINTMENT_NO_SHOW_AFTER_MINUTES("appointment.no_show_after_minutes", INT),       // BR-LH-08, BR-TN-03
    APPOINTMENT_REMINDER_HOURS_BEFORE("appointment.reminder_hours_before", INT),       // BR-LH-12
    BOOKING_RESTRICTION_VIOLATION_THRESHOLD("booking_restriction.violation_threshold", INT), // BR-LH-09
    BOOKING_RESTRICTION_WINDOW_DAYS("booking_restriction.window_days", INT),           // BR-LH-09
    BOOKING_RESTRICTION_DURATION_DAYS("booking_restriction.duration_days", INT),       // BR-LH-09

    // TN, KB
    VISIT_CHECK_IN_EARLY_MINUTES("visit.check_in_early_minutes", INT),     // BR-TN-02
    VISIT_LATE_PRIORITY_MINUTES("visit.late_priority_minutes", INT),       // BR-TN-03
    STAFF_ONLINE_WINDOW_MINUTES("staff.online_window_minutes", INT),       // BR-TN-06
    MEDICAL_RECORD_FOLLOW_UP_MAX_DAYS("medical_record.follow_up_max_days", INT), // BR-KB-06

    // LT
    BOARDING_MIN_NIGHTS("boarding.min_nights", INT),                       // BR-LT-02
    BOARDING_MAX_NIGHTS("boarding.max_nights", INT),                       // BR-LT-02
    BOARDING_OVERDUE_HOLD_NIGHTS("boarding.overdue_hold_nights", INT),     // BR-LT-03
    BOARDING_MIN_LEAD_DAYS("boarding.min_lead_days", INT),                 // BR-LT-04
    BOARDING_MAX_ADVANCE_DAYS("boarding.max_advance_days", INT),           // BR-LT-04
    BOARDING_CHECKOUT_TIME("boarding.checkout_time", TIME),                // BR-LT-04
    BOARDING_LATE_CANCEL_HOURS("boarding.late_cancel_hours", INT),         // BR-LT-06
    BOARDING_OVERDUE_CARE_TASK_DAYS("boarding.overdue_care_task_days", INT),           // BR-LT-10
    BOARDING_OVERDUE_MANAGER_ALERT_DAYS("boarding.overdue_manager_alert_days", INT),   // BR-LT-10
    BOARDING_CAPACITY_WARNING_DAYS("boarding.capacity_warning_days", INT), // BR-LT-10
    CARE_LOG_MIN_PER_DAY("care_log.min_per_day", INT),                     // BR-LT-11
    CARE_LOG_MAX_PHOTOS("care_log.max_photos", INT),                       // BR-LT-11
    CARE_LOG_EDIT_WINDOW_MINUTES("care_log.edit_window_minutes", INT),     // BR-LT-11

    // BH, TG
    ORDER_PENDING_ALERT_DAYS("order.pending_alert_days", INT),             // BR-BH-05
    CASHIER_SHIFT_AUTO_CLOSE_GRACE_MINUTES("cashier_shift.auto_close_grace_minutes", INT), // BR-TG-05

    // KO
    STOCK_EXPIRY_WARNING_DAYS("stock.expiry_warning_days", INT),           // BR-KO-07

    // DG
    FEEDBACK_MAX_PER_DAY("feedback.max_per_day", INT),                     // BR-DG-01

    // TB
    REMINDER_VACCINE_DAYS_BEFORE("reminder.vaccine_days_before", INT),     // BR-TB-01
    REMINDER_VACCINE_OVERDUE_DAYS("reminder.vaccine_overdue_days", INT),   // BR-TB-04
    REMINDER_FOLLOW_UP_DAYS_BEFORE("reminder.follow_up_days_before", INT), // BR-TB-06

    // BC
    REPORT_MAX_PERIOD_MONTHS("report.max_period_months", INT),             // BR-BC-01
    REPORT_REVACCINATION_GRACE_DAYS("report.revaccination_grace_days", INT); // BR-BC-04

    private final String key;
    private final ConfigValueType type;

    ConfigKey(String key, ConfigValueType type) {
        this.key = key;
        this.type = type;
    }

    /** {@code system_configs.key}, dạng {@code <nhóm>.<tên>} (convention 03). */
    public String key() {
        return key;
    }

    public ConfigValueType type() {
        return type;
    }
}
