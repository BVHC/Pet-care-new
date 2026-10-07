package com.petcare.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import com.petcare.platform.config.TimeConfig;

/**
 * Clock dịch được cho IT: đăng ký làm bean {@code @Primary} trong {@code @TestConfiguration} của IT. Bản dùng chung
 * (IT cũ — {@code AuthenticationIT}, {@code RegistrationResendIT}, {@code RegistrationVerificationIT} — còn bản riêng).
 */
public final class MutableClock extends Clock {

    private volatile Instant now;

    public MutableClock(Instant start) {
        this.now = start;
    }

    public void set(Instant instant) {
        now = instant;
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return TimeConfig.BUSINESS_ZONE;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
