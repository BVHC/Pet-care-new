package com.petcare.module;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.config.TimeConfig;

/**
 * Nền chung cho IT của các interface đọc cho module {@code ai} (AI-0): Postgres thật (Testcontainers), đồng hồ thật của
 * ứng dụng (không {@code MutableClock}, nên không cần một "era" riêng), dữ liệu qua {@link AiReadFixtures} và dọn sau mỗi
 * test. Các hàm đếm "toàn chuỗi" quét cả dữ liệu của IT khác: test chỉ khẳng định trên id dịch vụ, sản phẩm, loại chuồng do
 * chính nó tạo, không bao giờ khẳng định kích thước của map.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class AiReadItSupport {

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactionManager;
    @Autowired protected Clock clock;
    @Autowired protected SystemConfigApi config;

    protected AiReadFixtures fx;

    @BeforeEach
    protected void openFixtures() {
        fx = new AiReadFixtures(jdbc, transactionManager);
    }

    @AfterEach
    protected void closeFixtures() {
        fx.cleanup();
    }

    protected LocalDate today() {
        return LocalDate.now(clock);
    }

    /** 12:00 giờ Việt Nam của ngày, cách xa mọi ranh giới ngày. */
    protected static Instant noonVn(LocalDate day) {
        return day.atTime(12, 0).atZone(TimeConfig.BUSINESS_ZONE).toInstant();
    }

    /** 00:00 giờ Việt Nam của ngày: ranh giới "{@code since} tính cả ngày đó". */
    protected static Instant startOfDayVn(LocalDate day) {
        return day.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
    }

    /** Số ngày tuổi bắt đầu SENIOR theo một khóa [CFG] {@code ai.reco.senior_age_years_*}: {@code năm × 364} (52 tuần). */
    protected long seniorDays(ConfigKey key) {
        return config.getInt(key) * 364L;
    }

    /** Ngưỡng SENIOR của loài chó (năm), đọc từ [CFG] để test không phụ thuộc giá trị mặc định. */
    protected int seniorYearsOfDog() {
        return config.getInt(ConfigKey.AI_RECO_SENIOR_AGE_YEARS_DOG);
    }
}
