package com.petcare.module.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.inventory.api.StockReportApi;
import com.petcare.module.inventory.api.StockReportApi.ExpiringLotRow;
import com.petcare.module.inventory.api.StockReportApi.LowStockRow;

/**
 * {@code StockReportApi} (BR-KO-07, BR-BC-03 (5); docs/adr/0087) trên Postgres 17 thật: tồn khả dụng chỉ tính lô chưa
 * hết hạn so với tồn tối thiểu, lô còn tồn sắp / đã hết hạn trong cửa sổ, sản phẩm ngừng kinh doanh không cảnh báo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StockReportIT extends InventoryItSupport {

    @Autowired
    private StockReportApi api;
    @Autowired
    private Clock clock;

    private LocalDate today;
    private long branch;
    private long actor;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(clock);
        branch = newBranch();
        actor = staff("BRANCH_MANAGER", branch);
    }

    private long product(int minQuantity) {
        long product = newProduct("GOODS", true);
        long item = inventoryItem(branch, product);
        jdbc.update("UPDATE inventory_items SET min_quantity = ? WHERE id = ?", minQuantity, item);
        return product;
    }

    private String in(int days) {
        return today.plusDays(days).toString();
    }

    @Test
    void lowStockCountsOnlyUnexpiredLotsAgainstMinQuantity() {
        long p = product(10);
        lot(branch, p, "A", in(5), 4, actor);
        lot(branch, p, "B", in(-1), 20, actor);

        assertThat(api.lowStock(branch)).containsExactly(new LowStockRow(branch, p, 4, 10));
    }

    @Test
    void lowStockIgnoresZeroMinEnoughStockAndInactiveProducts() {
        product(0);
        long enough = product(5);
        lot(branch, enough, "E", in(30), 5, actor);
        long inactive = product(5);
        lot(branch, inactive, "I", in(30), 1, actor);
        jdbc.update("UPDATE products SET is_active = false WHERE id = ?", inactive);

        assertThat(api.lowStock(branch)).isEmpty();
    }

    @Test
    void productWithMinButNoLotAtAllIsLow() {
        long p = product(3);

        assertThat(api.lowStock(branch)).containsExactly(new LowStockRow(branch, p, 0, 3));
    }

    @Test
    void defaultLotWithoutExpiryCountsAsAvailable() {
        long p = product(5);
        defaultLot(branch, p, 5, actor);

        assertThat(api.lowStock(branch)).isEmpty();
    }

    @Test
    void expiringLotsIncludeExpiredAndWindowOnlyWithQuantity() {
        long p = newProduct("GOODS", true);
        long expired = lot(branch, p, "EXP", in(-1), 3, actor);
        long edge = lot(branch, p, "EDGE", in(30), 1, actor);
        lot(branch, p, "FAR", in(31), 1, actor);
        lot(branch, p, "EMPTY", in(-5), 0, actor);
        defaultLot(branch, p, 9, actor);

        assertThat(api.expiringLots(branch, 30)).containsExactly(
                new ExpiringLotRow(branch, p, expired, "EXP", today.minusDays(1), 3),
                new ExpiringLotRow(branch, p, edge, "EDGE", today.plusDays(30), 1));
    }

    @Test
    void nullBranchCoversEveryBranch() {
        long other = newBranch();
        long p1 = product(5);
        long item2 = inventoryItem(other, p1);
        jdbc.update("UPDATE inventory_items SET min_quantity = 5 WHERE id = ?", item2);

        assertThat(api.lowStock(null)).extracting(LowStockRow::branchId).contains(branch, other);
        assertThat(api.lowStock(other)).extracting(LowStockRow::branchId).containsOnly(other);
    }

    @Test
    void expiringLotsFilterByBranchAndChainIncludesBoth() {
        long other = newBranch();
        long p = newProduct("GOODS", true);
        // Chi nhánh `branch`: id lô tăng NGƯỢC thứ tự hạn và có hai lô cùng hạn, để bắt ORDER BY thiếu expiry_date / id.
        long late = lot(branch, p, "B1-LATE", in(20), 2, actor);
        long expired = lot(branch, p, "B1-EXP", in(-2), 4, actor);
        long tieA = lot(branch, p, "B1-TIE-A", in(10), 1, actor);
        long tieB = lot(branch, p, "B1-TIE-B", in(10), 3, actor);
        lot(branch, p, "B1-FAR", in(31), 7, actor);
        // Chi nhánh `other`: có lô hạn sớm hơn mọi lô của `branch` nên thiếu branch_id đứng đầu ORDER BY sẽ đảo thứ tự.
        long old = lot(other, p, "B2-OLD", in(-10), 5, actor);
        long dueToday = lot(other, p, "B2-TODAY", in(0), 2, actor);
        long edge = lot(other, p, "B2-EDGE", in(30), 6, actor);
        lot(other, p, "B2-EMPTY", in(-3), 0, actor);
        lot(other, p, "B2-FAR", in(45), 9, actor);
        defaultLot(other, p, 8, actor);
        ExpiringLotRow rowExpired = new ExpiringLotRow(branch, p, expired, "B1-EXP", today.minusDays(2), 4);
        ExpiringLotRow rowTieA = new ExpiringLotRow(branch, p, tieA, "B1-TIE-A", today.plusDays(10), 1);
        ExpiringLotRow rowTieB = new ExpiringLotRow(branch, p, tieB, "B1-TIE-B", today.plusDays(10), 3);
        ExpiringLotRow rowLate = new ExpiringLotRow(branch, p, late, "B1-LATE", today.plusDays(20), 2);
        ExpiringLotRow rowOld = new ExpiringLotRow(other, p, old, "B2-OLD", today.minusDays(10), 5);
        ExpiringLotRow rowToday = new ExpiringLotRow(other, p, dueToday, "B2-TODAY", today, 2);
        ExpiringLotRow rowEdge = new ExpiringLotRow(other, p, edge, "B2-EDGE", today.plusDays(30), 6);

        assertThat(api.expiringLots(branch, 30)).containsExactly(rowExpired, rowTieA, rowTieB, rowLate);
        assertThat(api.expiringLots(other, 30)).containsExactly(rowOld, rowToday, rowEdge);
        assertThat(api.expiringLots(null, 30)).filteredOn(row -> row.productId() == p)
                .containsExactly(rowExpired, rowTieA, rowTieB, rowLate, rowOld, rowToday, rowEdge);
        // Đã hết hạn = hạn < hôm nay (lô hết hạn đúng hôm nay chưa tính là đã hết hạn).
        assertThat(api.expiringLots(null, 30)).filteredOn(row -> row.productId() == p)
                .extracting(row -> row.expiryDate().isBefore(today))
                .containsExactly(true, false, false, false, true, false, false);
    }
}
