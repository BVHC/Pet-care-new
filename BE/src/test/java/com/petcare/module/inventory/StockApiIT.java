package com.petcare.module.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.inventory.api.StockApi;
import com.petcare.module.inventory.api.StockApi.SaleLine;
import com.petcare.module.inventory.api.StockApi.Shortage;
import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * {@link StockApi} thật cho thu ngân (Order#8) và tiêm (docs/adr/0042; BR-KO-01, 05; 06 §5) trên Postgres 17 thật:
 * FEFO nhiều lô và nhiều sản phẩm, không bao giờ xuất lô hết hạn, thiếu thì không trừ gì, hai lần bán món cuối,
 * khóa lô → trừ → hoàn đúng lô, rollback theo bên gọi, và không deadlock với điều chỉnh tồn chạm lô theo thứ tự khác.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StockApiIT extends InventoryItSupport {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired
    private StockApi stock;

    private long branch;
    private long actor;
    private long drug;
    private long vaccine;
    private long goods;

    @BeforeEach
    void setUp() {
        branch = newBranch();
        actor = staff("BRANCH_MANAGER", branch);
        drug = newProduct("DRUG", true);
        vaccine = newProduct("VACCINE", true);
        goods = newProduct("GOODS", false);
    }

    private static String inDays(int days) {
        return LocalDate.now(ZONE).plusDays(days).toString();
    }

    private <T> T inTx(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    private List<Shortage> sale(long orderId, SaleLine... lines) {
        return inTx(() -> stock.issueForSale(orderId, branch, List.of(lines), actor));
    }

    private List<Map<String, Object>> movements(long lotId) {
        return jdbc.queryForList("""
                SELECT movement_type, quantity_delta, balance_after, source_type, source_id, created_by
                FROM stock_movements WHERE stock_lot_id = ? ORDER BY id
                """, lotId);
    }

    // ---------------------------------------------------------------- bán

    @Test
    void saleTakesTheEarliestExpiryFirstAcrossLotsAndWritesOneMovementPerLot() {
        long late = lot(branch, drug, "LATE", inDays(120), 10, actor);
        long soon = lot(branch, drug, "SOON", inDays(10), 4, actor);
        long mid = lot(branch, drug, "MID", inDays(60), 5, actor);

        List<Shortage> shortages = sale(500L, new SaleLine(drug, 8));

        assertThat(shortages).isEmpty();
        assertThat(lotQuantity(soon)).isZero();
        assertThat(lotQuantity(mid)).isEqualTo(1);
        assertThat(lotQuantity(late)).isEqualTo(10);
        assertThat(movements(soon).get(1)).containsEntry("movement_type", "SALE").containsEntry("quantity_delta", -4)
                .containsEntry("balance_after", 0).containsEntry("source_type", "orders")
                .containsEntry("source_id", 500L).containsEntry("created_by", actor);
        assertThat(movements(mid).get(1)).containsEntry("quantity_delta", -4).containsEntry("balance_after", 1);
        assertThat(movementCount(late)).as("lô sau không bị đụng").isEqualTo(1L);
    }

    @Test
    void expiredLotsAreNeverIssued() {
        long expired = lot(branch, drug, "OLD", inDays(-1), 100, actor);
        long fresh = lot(branch, drug, "NEW", inDays(30), 3, actor);

        assertThat(sale(501L, new SaleLine(drug, 3))).isEmpty();
        assertThat(sale(502L, new SaleLine(drug, 1))).containsExactly(new Shortage(drug, 1, 0));

        assertThat(lotQuantity(expired)).isEqualTo(100);
        assertThat(lotQuantity(fresh)).isZero();
    }

    @Test
    void aLotExpiringTodayCanStillBeSold() {
        long today = lot(branch, drug, "TODAY", inDays(0), 2, actor);

        assertThat(sale(503L, new SaleLine(drug, 2))).isEmpty();
        assertThat(lotQuantity(today)).isZero();
    }

    @Test
    void shortageOfAnyLineMeansNothingIsDeducted() {
        long drugLot = lot(branch, drug, "L1", inDays(30), 10, actor);
        long goodsLot = defaultLot(branch, goods, 2, actor);

        List<Shortage> shortages = sale(504L, new SaleLine(drug, 4), new SaleLine(goods, 5));

        assertThat(shortages).containsExactly(new Shortage(goods, 5, 2));
        assertThat(lotQuantity(drugLot)).isEqualTo(10);
        assertThat(lotQuantity(goodsLot)).isEqualTo(2);
        assertThat(movementCount(drugLot)).isEqualTo(1L);
    }

    @Test
    void severalProductsAndDuplicateLinesAreHandledInOneCall() {
        long drugLot = lot(branch, drug, "L1", inDays(30), 10, actor);
        long goodsLot = defaultLot(branch, goods, 10, actor);

        assertThat(sale(505L, new SaleLine(goods, 2), new SaleLine(drug, 3), new SaleLine(goods, 3))).isEmpty();

        assertThat(lotQuantity(drugLot)).isEqualTo(7);
        assertThat(lotQuantity(goodsLot)).isEqualTo(5);
        assertThat(movements(goodsLot)).hasSize(2);
    }

    @Test
    void stockOfAnotherBranchIsNotUsed() {
        long other = newBranch();
        lot(other, drug, "OTHER", inDays(30), 99, actor);

        assertThat(sale(506L, new SaleLine(drug, 1))).containsExactly(new Shortage(drug, 1, 0));
    }

    @Test
    void aCallersRollbackRestoresStockAndRemovesTheMovements() {
        long lot = lot(branch, drug, "L1", inDays(30), 10, actor);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(stock.issueForSale(507L, branch, List.of(new SaleLine(drug, 4)), actor)).isEmpty();
            throw new IllegalStateException("thu tiền lỗi sau khi trừ kho");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(lotQuantity(lot)).isEqualTo(10);
        assertThat(movementCount(lot)).isEqualTo(1L);
    }

    @Test
    void twoSalesOfTheLastItemsNeverOversell() {
        long lot = lot(branch, drug, "L1", inDays(30), 5, actor);

        CompletableFuture<List<Shortage>> a = CompletableFuture.supplyAsync(() -> sale(508L, new SaleLine(drug, 3)));
        CompletableFuture<List<Shortage>> b = CompletableFuture.supplyAsync(() -> sale(509L, new SaleLine(drug, 3)));

        List<Shortage> first = a.join();
        List<Shortage> second = b.join();

        assertThat(first.isEmpty() ^ second.isEmpty()).as("đúng một bên mua được").isTrue();
        assertThat(lotQuantity(lot)).isEqualTo(2);
        List<Shortage> failed = first.isEmpty() ? second : first;
        assertThat(failed).containsExactly(new Shortage(drug, 3, 2));
    }

    @Test
    void manyParallelSalesSellExactlyWhatIsInStock() {
        long lot = lot(branch, drug, "L1", inDays(30), 10, actor);
        List<CompletableFuture<List<Shortage>>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long order = 600L + i;
            futures.add(CompletableFuture.supplyAsync(() -> sale(order, new SaleLine(drug, 1))));
        }

        long sold = futures.stream().map(CompletableFuture::join).filter(List::isEmpty).count();

        assertThat(sold).isEqualTo(10);
        assertThat(lotQuantity(lot)).isZero();
        assertThat(movementCount(lot)).isEqualTo(11L);
    }

    @Test
    void salesAndAdjustmentsTouchingLotsInOppositeOrderNeverDeadlock() {
        long lotA = lot(branch, drug, "A", inDays(30), 1000, actor);
        long lotB = lot(branch, vaccine, "B", inDays(30), 1000, actor);
        String manager = token(actor);
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            long order = 700L + i;
            futures.add(CompletableFuture.supplyAsync(() -> sale(order, new SaleLine(drug, 1), new SaleLine(vaccine, 1))));
            futures.add(CompletableFuture.supplyAsync(() -> call(HttpMethod.POST, "/api/stock-adjustments", manager,
                    Map.of("reason", "COUNT_MISMATCH", "lines", List.of(Map.of("stockLotId", lotB, "quantityDelta", 1),
                            Map.of("stockLotId", lotA, "quantityDelta", 1))))));
        }

        futures.forEach(f -> assertThat(f.join()).isNotNull());

        assertThat(lotQuantity(lotA)).isEqualTo(1000);
        assertThat(lotQuantity(lotB)).isEqualTo(1000);
    }

    // ---------------------------------------------------------------- tiêm

    @Test
    void vaccinationLocksTheFefoLotThenIssuesAndRevertsToTheSameLot() {
        long soon = lot(branch, vaccine, "SOON", inDays(5), 1, actor);
        long later = lot(branch, vaccine, "LATER", inDays(90), 10, actor);

        long chosen = inTx(() -> stock.lockFefoLot(branch, vaccine, 1));
        inTx(() -> {
            stock.issueForVaccination(chosen, 1, 900L, actor);
            return null;
        });
        long next = inTx(() -> stock.lockFefoLot(branch, vaccine, 1));

        assertThat(chosen).isEqualTo(soon);
        assertThat(next).as("lô cũ hết hàng nên chuyển sang lô sau").isEqualTo(later);
        assertThat(lotQuantity(soon)).isZero();
        assertThat(movements(soon).get(1)).containsEntry("movement_type", "VACCINATION")
                .containsEntry("source_type", "vaccinations").containsEntry("source_id", 900L);

        inTx(() -> {
            stock.revertVaccination(soon, 1, 900L, actor);
            return null;
        });

        assertThat(lotQuantity(soon)).isEqualTo(1);
        assertThat(movements(soon).get(2)).containsEntry("movement_type", "VACCINATION_REVERT")
                .containsEntry("quantity_delta", 1).containsEntry("balance_after", 1);
    }

    @Test
    void lockFefoLotFailsWithBrKo05WhenNoUnexpiredLotHoldsEnough() {
        lot(branch, vaccine, "A", inDays(10), 1, actor);
        lot(branch, vaccine, "OLD", inDays(-5), 50, actor);

        assertThatThrownBy(() -> inTx(() -> stock.lockFefoLot(branch, vaccine, 2)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-KO-05");
    }

    @Test
    void anExpiredLotCannotBeVaccinatedFromButCanTakeAVaccinationBack() {
        long expired = lot(branch, vaccine, "OLD", inDays(-1), 3, actor);

        assertThatThrownBy(() -> inTx(() -> {
            stock.issueForVaccination(expired, 1, 901L, actor);
            return null;
        })).isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-KO-05");

        inTx(() -> {
            stock.revertVaccination(expired, 2, 901L, actor);
            return null;
        });
        assertThat(lotQuantity(expired)).isEqualTo(5);
    }

    @Test
    void vaccinationCannotDrawMoreThanTheLotHolds() {
        long lot = lot(branch, vaccine, "A", inDays(10), 1, actor);

        assertThatThrownBy(() -> inTx(() -> {
            stock.issueForVaccination(lot, 2, 902L, actor);
            return null;
        })).isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-KO-01");
        assertThat(lotQuantity(lot)).isEqualTo(1);
    }

    @Test
    void everyMethodRequiresTheCallersTransaction() {
        assertThatThrownBy(() -> stock.issueForSale(1L, branch, List.of(), actor))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> stock.lockFefoLot(branch, vaccine, 1))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> stock.issueForVaccination(1L, 1, 1L, actor))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> stock.revertVaccination(1L, 1, 1L, actor))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void httpViewsAgreeWithWhatTheServiceSold() {
        lot(branch, drug, "L1", inDays(30), 10, actor);
        sale(800L, new SaleLine(drug, 4));
        String manager = token(actor);

        Map<String, Object> item = data(call(HttpMethod.GET, "/api/inventory/" + drug, manager, null), HttpStatus.OK);

        assertThat(item).containsEntry("availableQuantity", 6).containsEntry("totalQuantity", 6);
    }
}
