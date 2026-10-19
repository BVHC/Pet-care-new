package com.petcare.module.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.service.AppointmentCodeGenerator;

/**
<<<<<<< HEAD
 * Mã lịch hẹn trên PostgreSQL 17 (docs/adr/0045, V16): chỉ trong transaction ghi; 50 lượt song song ra 50 mã khác nhau;
=======
 * Mã lịch hẹn trên PostgreSQL 17 (docs/adr/0041, V16): chỉ trong transaction ghi; 50 lượt song song ra 50 mã khác nhau;
>>>>>>> e18c4ed (feat: triển khai hủy do phòng khám)
 * rollback không trả số lại (nhảy cóc là hành vi có chủ đích); hết {@code MAXVALUE} thì lỗi, không quay vòng.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AppointmentCodeGeneratorIT {

    private static final String CODE = "^LH-\\d{6}-\\d{4,10}$";

    @Autowired
    private AppointmentCodeGenerator generator;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private String nextCodeInTransaction() {
        return tx().execute(status -> generator.nextCode());
    }

    private static long number(String code) {
        return Long.parseLong(code.substring(code.lastIndexOf('-') + 1));
    }

    @Test
    void outsideTransactionFails() {
        assertThatThrownBy(() -> generator.nextCode()).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void readOnlyTransactionFails() {
        TransactionTemplate readOnly = tx();
        readOnly.setReadOnly(true);

        assertThatThrownBy(() -> readOnly.execute(status -> generator.nextCode()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("readOnly");
    }

    @Test
    void codesHaveTheFormatAndIncrease() {
        String first = nextCodeInTransaction();
        String second = nextCodeInTransaction();

        assertThat(first).matches(CODE);
        assertThat(second).matches(CODE);
        assertThat(number(second)).isGreaterThan(number(first));
    }

    @Test
    void concurrentCallsNeverRepeat() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                calls.add(this::nextCodeInTransaction);
            }
            Set<String> codes = new HashSet<>();
            for (Future<String> code : pool.invokeAll(calls, 60, TimeUnit.SECONDS)) {
                codes.add(code.get());
            }
            assertThat(codes).hasSize(50).allMatch(c -> c.matches(CODE));
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** Sequence không thuộc transaction: số đã cấp cho transaction bị rollback bỏ trống vĩnh viễn (docs/adr/0045). */
    @Test
    void numberConsumedByRolledBackTransactionIsNotReused() {
        String rolledBack = tx().execute(status -> {
            String code = generator.nextCode();
            status.setRollbackOnly();
            return code;
        });

        assertThat(number(nextCodeInTransaction())).isGreaterThan(number(rolledBack));
    }

    /**
     * Hết số: lỗi DB, không quay vòng về 1 (sinh mã trùng). Hibernate xếp SQLState 2200H vào nhóm lỗi dữ liệu nên Spring
     * dịch thành đúng {@code DataIntegrityViolationException} → {@code GlobalExceptionHandler} trả 409 message chung
     * (docs/adr/0045 ghi nhận; thực tế không chạm tới — 10¹⁰ mã). Khôi phục vị trí sequence sau test — sequence dùng
     * chung cho cả lượt chạy.
     */
    @Test
    void exhaustedSequenceFailsInsteadOfCycling() {
        Map<String, Object> saved = jdbc.queryForMap("SELECT last_value, is_called FROM appointment_code_seq");
        try {
            jdbc.queryForObject("SELECT setval('appointment_code_seq', 9999999999)", Long.class);

            assertThatThrownBy(this::nextCodeInTransaction).isExactlyInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("appointment_code_seq");
        } finally {
            jdbc.queryForObject("SELECT setval('appointment_code_seq', ?, ?)", Long.class,
                    saved.get("last_value"), saved.get("is_called"));
        }
    }
}
