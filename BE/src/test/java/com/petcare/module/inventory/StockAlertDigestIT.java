package com.petcare.module.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.inventory.job.StockAlertDigestJob;
import com.petcare.module.sales.CashierClockConfig;
import com.petcare.module.sales.CashierItSupport;

/**
 * ST08 (BR-KO-07; docs/adr/0087) trên Postgres 17 thật: mỗi chi nhánh ACTIVE có cảnh báo gửi quản lý đúng một thông báo
 * trong app với số liệu khớp báo cáo (5), chi nhánh DRAFT hoặc không có cảnh báo thì không, một chi nhánh lỗi chỉ rollback
 * chính nó. Job đi mọi chi nhánh ACTIVE nên chỉ khẳng định trên tài khoản của chính IT này.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CashierClockConfig.class})
class StockAlertDigestIT extends CashierItSupport {

    private static final String TEMPLATE = "STOCK_ALERT_DIGEST_APP";

    @Autowired
    private StockAlertDigestJob job;
    @Autowired
    private Clock clock;

    private final List<Long> managers = new ArrayList<>();
    private LocalDate today;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(clock);
    }

    @AfterEach
    void cleanOutbox() {
        for (long manager : managers) {
            jdbc.update("DELETE FROM notification_outbox WHERE recipient_account_id = ?", manager);
        }
        managers.clear();
    }

    private long manager(long branch) {
        long id = staff("BRANCH_MANAGER", branch);
        managers.add(id);
        return id;
    }

    /** Một sản phẩm dưới tồn tối thiểu (tồn 2 / tối thiểu 10, lô không hạn) và một lô đã hết hạn hôm qua còn tồn. */
    private void alerts(long branch, long actor) {
        long low = newProduct("GOODS", false);
        jdbc.update("UPDATE inventory_items SET min_quantity = 10 WHERE id = ?", inventoryItem(branch, low));
        defaultLot(branch, low, 2, actor);
        long expired = newProduct("GOODS", true);
        lot(branch, expired, "HH", today.minusDays(1).toString(), 4, actor);
    }

    private long notices(long manager) {
        return jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE template_code = ? "
                + "AND recipient_account_id = ?", Long.class, TEMPLATE, manager);
    }

    @Test
    void activeBranchManagerGetsOneDigestWithCounts() {
        long branch = newBranch();
        long manager = manager(branch);
        alerts(branch, manager);

        job.run();

        assertThat(notices(manager)).isEqualTo(1L);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT payload::text AS payload, channel FROM notification_outbox
                WHERE template_code = ? AND recipient_account_id = ?""", TEMPLATE, manager);
        assertThat(row).containsEntry("channel", "IN_APP");
        assertThat((String) row.get("payload")).contains("\"so_duoi_nguong\": \"1\"")
                .contains("\"so_sap_het_han\": \"0\"").contains("\"so_da_het_han\": \"1\"")
                .contains("\"so_ngay\": \"30\"");
    }

    @Test
    void draftBranchIsNotNotified() {
        long branch = newBranch();
        long manager = manager(branch);
        alerts(branch, manager);
        jdbc.update("UPDATE branches SET status = 'DRAFT' WHERE id = ?", branch);

        job.run();

        assertThat(notices(manager)).isZero();
    }

    @Test
    void branchWithoutAlertsIsNotNotified() {
        long branch = newBranch();
        long manager = manager(branch);
        long enough = newProduct("GOODS", false);
        jdbc.update("UPDATE inventory_items SET min_quantity = 3 WHERE id = ?", inventoryItem(branch, enough));
        defaultLot(branch, enough, 3, manager);

        job.run();

        assertThat(notices(manager)).isZero();
    }

    @Test
    void failingBranchRollsBackOnlyItself() {
        long branch1 = newBranch();
        long manager1 = manager(branch1);
        alerts(branch1, manager1);
        long branch2 = newBranch();
        long manager2 = manager(branch2);
        alerts(branch2, manager2);

        whileInsertsFail("notification_outbox", "NEW.recipient_account_id = " + manager1, job::run);

        assertThat(notices(manager1)).isZero();
        assertThat(notices(manager2)).isEqualTo(1L);
    }
}
