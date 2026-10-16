package com.petcare.platform.sequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;

/**
 * {@link DocumentCodeGenerator} trên Postgres 17 thật (ADR-0043): định dạng, đổi tháng về 00001, các loại độc lập,
 * transaction rollback không để lỗ, tạo song song vẫn liên tục và không trùng, bên gọi phải có transaction.
 * Mỗi test dùng {@code doc_type} riêng nên không đụng dãy của IT khác.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class DocumentCodeIT {

    @Autowired
    private DocumentCodeGenerator generator;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private String newType() {
        return "it_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String next(String type, String prefix, LocalDate date) {
        return new TransactionTemplate(transactionManager).execute(s -> generator.next(type, prefix, date));
    }

    @Test
    void formatsPrefixMonthAndFiveDigits() {
        String type = newType();

        assertThat(next(type, "PN", LocalDate.of(2026, 10, 16))).isEqualTo("PN-2610-00001");
        assertThat(next(type, "PN", LocalDate.of(2026, 10, 31))).isEqualTo("PN-2610-00002");
    }

    @Test
    void newMonthRestartsAndTypesAreIndependent() {
        String receipts = newType();
        String adjustments = newType();

        next(receipts, "PN", LocalDate.of(2026, 10, 1));
        next(receipts, "PN", LocalDate.of(2026, 10, 2));

        assertThat(next(receipts, "PN", LocalDate.of(2026, 11, 1))).isEqualTo("PN-2611-00001");
        assertThat(next(adjustments, "DC", LocalDate.of(2026, 10, 3))).isEqualTo("DC-2610-00001");
        assertThat(next(receipts, "PN", LocalDate.of(2026, 10, 3))).isEqualTo("PN-2610-00003");
    }

    @Test
    void rolledBackTransactionLeavesNoGap() {
        String type = newType();
        LocalDate day = LocalDate.of(2026, 10, 16);
        next(type, "PN", day);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            assertThat(generator.next(type, "PN", day)).isEqualTo("PN-2610-00002");
            throw new IllegalStateException("tạo chứng từ lỗi");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(next(type, "PN", day)).isEqualTo("PN-2610-00002");
    }

    @Test
    void concurrentCreatorsGetDistinctConsecutiveNumbers() {
        String type = newType();
        LocalDate day = LocalDate.of(2026, 10, 16);
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> next(type, "PN", day)));
        }

        List<String> codes = futures.stream().map(CompletableFuture::join).sorted().toList();

        assertThat(codes).doesNotHaveDuplicates().hasSize(20);
        assertThat(codes.get(0)).isEqualTo("PN-2610-00001");
        assertThat(codes.get(19)).isEqualTo("PN-2610-00020");
        assertThat(jdbc.queryForObject("SELECT last_value FROM document_counters WHERE doc_type = ?", Integer.class,
                type)).isEqualTo(20);
    }

    @Test
    void exceedingTheMonthlyLimitFailsInsteadOfOverflowingTheColumn() {
        String type = newType();
        jdbc.update("INSERT INTO document_counters (doc_type, period, last_value) VALUES (?, '2610', ?)", type,
                DocumentCodeGenerator.MAX_PER_PERIOD);

        assertThatThrownBy(() -> next(type, "PN", LocalDate.of(2026, 10, 16)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT last_value FROM document_counters WHERE doc_type = ?", Integer.class,
                type)).as("rollback giữ nguyên giới hạn").isEqualTo(DocumentCodeGenerator.MAX_PER_PERIOD);
    }

    @Test
    void requiresTheCallersTransaction() {
        assertThatThrownBy(() -> generator.next(newType(), "PN", LocalDate.of(2026, 10, 16)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
