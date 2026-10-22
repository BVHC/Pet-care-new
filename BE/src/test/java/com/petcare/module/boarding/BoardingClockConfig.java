package com.petcare.module.boarding;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.petcare.support.MutableClock;

/** Đồng hồ cho IT lưu trú: era năm 2009, {@link #START} = 2009-06-10 (thứ tư) 10:00 giờ Việt Nam. */
@TestConfiguration
public class BoardingClockConfig {

    public static final Instant START = Instant.parse("2009-06-10T03:00:00Z");

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock(START);
    }
}
