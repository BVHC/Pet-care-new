package com.petcare.platform.exception;

/**
 * Vi phạm một business rule {@code BR-<MODULE>-<n>} trong docs/02-business-rules.md.
 * Mã rule được nhúng vào message trả client, ví dụ {@code "Thú cưng đã có 2 lịch BOOKED (BR-LH-05)"}.
 */
public class BusinessRuleViolationException extends PlatformException {

    private final String ruleId;

    public BusinessRuleViolationException(String ruleId, String message) {
        super(ErrorCode.BUSINESS_RULE_VIOLATION, message + " (" + ruleId + ")");
        this.ruleId = ruleId;
    }

    public String getRuleId() {
        return ruleId;
    }
}
