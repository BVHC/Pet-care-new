package com.petcare.module.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.content.api.FeedbackReportApi;
import com.petcare.module.content.api.FeedbackReportApi.FeedbackStatRow;

/**
 * {@code FeedbackReportApi} (BR-BC-03 (6), BR-DG-03; docs/adr/0087) trên Postgres 17 thật, era 2004: gộp theo chi nhánh,
 * điểm trung bình chỉ trên feedback có chấm điểm, nhóm không gắn chi nhánh chỉ ở góc nhìn toàn chuỗi, kỳ nửa mở giờ VN.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ContentItSupport.ContentClockConfig.class})
class FeedbackReportIT extends ContentItSupport {

    private static final LocalDate FROM = LocalDate.parse("2004-03-01");
    private static final LocalDate TO = LocalDate.parse("2004-03-31");
    private static final String IN_PERIOD = "2004-03-15T05:00:00Z";

    @Autowired
    private FeedbackReportApi api;

    private long branch;
    private long customer;

    @BeforeEach
    void setUp() {
        branch = newBranch("ACTIVE");
        customer = onlineCustomer(false)[1];
    }

    private void feedback(Long branchId, Integer rating, String createdAt) {
        jdbc.update("""
                INSERT INTO feedbacks (customer_id, topic, branch_id, rating, content, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'Góp ý đủ dài để hợp lệ.', 'NEW', CAST(? AS timestamptz), CAST(? AS timestamptz))
                """, customer, branchId == null ? "WEBSITE" : "BRANCH_SERVICE", branchId, rating, createdAt, createdAt);
    }

    @Test
    void averageIgnoresUnratedAndRoundsToTwoPlaces() {
        feedback(branch, 5, IN_PERIOD);
        feedback(branch, 4, IN_PERIOD);
        feedback(branch, 4, IN_PERIOD);
        feedback(branch, null, IN_PERIOD);

        assertThat(api.feedbackStats(branch, FROM, TO))
                .containsExactly(new FeedbackStatRow(branch, 4, new BigDecimal("4.33")));
    }

    @Test
    void averageIsNullWhenNoRating() {
        feedback(branch, null, IN_PERIOD);

        assertThat(api.feedbackStats(branch, FROM, TO)).containsExactly(new FeedbackStatRow(branch, 1, null));
    }

    @Test
    void chainIncludesUnbranchedGroupButBranchFilterDoesNot() {
        feedback(branch, 3, IN_PERIOD);
        feedback(null, 5, IN_PERIOD);

        assertThat(api.feedbackStats(null, FROM, TO)).extracting(FeedbackStatRow::branchId)
                .containsAnyOf((Long) null).contains(branch);
        assertThat(api.feedbackStats(branch, FROM, TO)).extracting(FeedbackStatRow::branchId).containsOnly(branch);
    }

    @Test
    void createdAtBoundaryIsHalfOpenInVietnamTime() {
        feedback(branch, 5, "2004-03-31T16:59:59.999999Z");   // 23:59:59.999999 VN 31/03: trong kỳ
        feedback(branch, 1, "2004-03-31T17:00:00Z");           // 00:00 VN 01/04: ngoài kỳ
        feedback(branch, 1, "2004-02-29T16:59:59Z");           // 23:59:59 VN 29/02: ngoài kỳ

        assertThat(api.feedbackStats(branch, FROM, TO))
                .containsExactly(new FeedbackStatRow(branch, 1, new BigDecimal("5.00")));
    }
}
