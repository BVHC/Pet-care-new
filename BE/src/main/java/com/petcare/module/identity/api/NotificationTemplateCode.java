package com.petcare.module.identity.api;

/**
 * Owner: identity (QT, NotificationTemplate) · nội dung mẫu seed dần, cùng PR với lần gửi đầu tiên dùng mẫu (06 §8 Q4);
 * module gửi dùng hằng số này khi gọi {@code NotificationApi.enqueue}. Mỗi mã một kênh ({@code notification_templates.channel}); sự kiện gửi cả email
 * lẫn trong app có hai mã, mã trong app thêm hậu tố {@code _APP}. Thêm mã mới = thêm hằng số + migration seed.
 * Danh sách đầy đủ và kênh xem 06-module-contracts §8 Q4.
 */
public final class NotificationTemplateCode {

    private NotificationTemplateCode() {
    }

    // TK — email
    public static final String OTP_REGISTER = "OTP_REGISTER";
    public static final String OTP_PASSWORD_RESET = "OTP_PASSWORD_RESET";
    public static final String OTP_EMAIL_CHANGE = "OTP_EMAIL_CHANGE";
    public static final String OTP_PROFILE_LINK = "OTP_PROFILE_LINK";
    public static final String LOGIN_LOCKED_WARNING = "LOGIN_LOCKED_WARNING";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String EMAIL_CHANGED_NOTICE = "EMAIL_CHANGED_NOTICE";

    // QT — email (nhân viên) và trong app (quản lý)
    public static final String STAFF_TEMP_PASSWORD = "STAFF_TEMP_PASSWORD";
    public static final String ACCOUNT_LOCKED_FOLLOW_UP_APP = "ACCOUNT_LOCKED_FOLLOW_UP_APP";
    public static final String LAST_BRANCH_MANAGER_LOCKED_APP = "LAST_BRANCH_MANAGER_LOCKED_APP";

    // LH
    public static final String APPOINTMENT_REMINDER = "APPOINTMENT_REMINDER";
    public static final String APPOINTMENT_REMINDER_APP = "APPOINTMENT_REMINDER_APP";
    public static final String APPOINTMENT_CANCELLED_BY_CLINIC = "APPOINTMENT_CANCELLED_BY_CLINIC";
    public static final String APPOINTMENT_CANCELLED_BY_CLINIC_APP = "APPOINTMENT_CANCELLED_BY_CLINIC_APP";

    // LT
    public static final String BOARDING_CANCELLED_BY_CLINIC = "BOARDING_CANCELLED_BY_CLINIC";
    public static final String BOARDING_CANCELLED_BY_CLINIC_APP = "BOARDING_CANCELLED_BY_CLINIC_APP";
    public static final String BOARDING_OVERDUE = "BOARDING_OVERDUE";
    public static final String BOARDING_OVERDUE_APP = "BOARDING_OVERDUE_APP";
    public static final String CARE_LOG_ABNORMAL = "CARE_LOG_ABNORMAL";
    public static final String CARE_LOG_ABNORMAL_APP = "CARE_LOG_ABNORMAL_APP";
    public static final String BOARDING_NO_KENNEL_APP = "BOARDING_NO_KENNEL_APP";
    public static final String BOARDING_OVERDUE_MANAGER_APP = "BOARDING_OVERDUE_MANAGER_APP";
    public static final String BOARDING_CAPACITY_WARNING_APP = "BOARDING_CAPACITY_WARNING_APP";

    // TB
    public static final String VACCINE_REMINDER = "VACCINE_REMINDER";
    public static final String VACCINE_REMINDER_APP = "VACCINE_REMINDER_APP";
    public static final String FOLLOW_UP_REMINDER = "FOLLOW_UP_REMINDER";
    public static final String FOLLOW_UP_REMINDER_APP = "FOLLOW_UP_REMINDER_APP";

    // TG, BH, KO — trong app cho BRANCH_MANAGER
    public static final String CASHIER_SHIFT_AUTO_CLOSED_APP = "CASHIER_SHIFT_AUTO_CLOSED_APP";
    public static final String PENDING_ORDERS_DIGEST_APP = "PENDING_ORDERS_DIGEST_APP";
    public static final String STOCK_ALERT_DIGEST_APP = "STOCK_ALERT_DIGEST_APP";
}
