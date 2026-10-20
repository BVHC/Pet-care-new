package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.visit.service.VisitCodeGenerator;

/**
 * Mã lượt {@code TN-<yyMM>-<5 chữ số>} trên {@code document_counters} thật (docs/adr/0058, ADR-0043): hai lần gọi trong
 * một transaction cho hai số liền nhau của tháng nghiệp vụ; transaction rollback thì số cũng lùi (dãy liên tục); ngoài
 * transaction và trong transaction readOnly bị chặn.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class VisitCodeIT {

    @Autowired
    private VisitCodeGenerator codes;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private Clock clock;

    @Test
    void twoCodesInOneTransactionAreConsecutiveAndRollbackReturnsTheNumbers() {
        String prefix = "TN-" + LocalDate.now(clock).format(DateTimeFormatter.ofPattern("yyMM")) + "-";
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        String[] first = tx.execute(status -> {
            status.setRollbackOnly();
            return new String[] {codes.nextCode(), codes.nextCode()};
        });
        assertThat(first[0]).startsWith(prefix).matches("TN-\\d{4}-\\d{5}");
        assertThat(number(first[1])).isEqualTo(number(first[0]) + 1);

        String again = tx.execute(status -> {
            status.setRollbackOnly();
            return codes.nextCode();
        });
        assertThat(again).as("rollback trả số về").isEqualTo(first[0]);
    }

    @Test
    void outsideATransactionOrReadOnlyIsRejected() {
        assertThatThrownBy(codes::nextCode).isInstanceOf(IllegalTransactionStateException.class);

        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.execute(status -> codes.nextCode()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static int number(String code) {
        return Integer.parseInt(code.substring(code.lastIndexOf('-') + 1));
    }
}
