package com.petcare.module.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.petcare.module.AiReadFixtures;
import com.petcare.module.AiReadItSupport;
import com.petcare.module.inventory.api.StockQueryApi;

/**
 * I-5 (07 §11, AI-0): sản phẩm còn tồn khả dụng ở ít nhất một chi nhánh. Tồn khả dụng = lô chưa hết hạn trừ phần đang giữ
 * {@code HELD} (BR-KO-01, BR-BH-09, 08 §G). Truy vấn chỉ xét các id được hỏi, nên khẳng định chính xác được.
 */
class StockAiReadIT extends AiReadItSupport {

    @Autowired private StockQueryApi stock;

    @Test
    void reportsProductsWithPositiveAvailableStockNetOfHeldReservationsInAtLeastOneBranch() {
        long a = AiReadFixtures.next();
        long b = AiReadFixtures.next();
        LocalDate t = today();
        long inStock = AiReadFixtures.next();
        long zeroQuantity = AiReadFixtures.next();
        long expiredOnly = AiReadFixtures.next();
        long fullyHeld = AiReadFixtures.next();
        long partlyHeld = AiReadFixtures.next();
        long heldInAButStockedInB = AiReadFixtures.next();
        long releasedHold = AiReadFixtures.next();
        long expiresToday = AiReadFixtures.next();
        long expiredPlusValidAllHeld = AiReadFixtures.next();
        long neverStocked = AiReadFixtures.next();

        fx.lot(a, inStock, 5, null);
        fx.lot(a, zeroQuantity, 0, null);
        fx.lot(a, expiredOnly, 5, t.minusDays(1));
        fx.lot(a, fullyHeld, 3, null);
        fx.reservation(a, fullyHeld, 3, "HELD");
        fx.lot(a, partlyHeld, 3, null);
        fx.reservation(a, partlyHeld, 2, "HELD");
        fx.lot(a, heldInAButStockedInB, 3, null);
        fx.reservation(a, heldInAButStockedInB, 3, "HELD");
        fx.lot(b, heldInAButStockedInB, 2, null);
        fx.lot(a, releasedHold, 3, null);
        fx.reservation(a, releasedHold, 3, "RELEASED");
        fx.lot(a, expiresToday, 1, t);
        fx.lot(a, expiredPlusValidAllHeld, 5, t.minusDays(2));
        fx.lot(a, expiredPlusValidAllHeld, 2, t.plusDays(30));
        fx.reservation(a, expiredPlusValidAllHeld, 2, "HELD");

        Set<Long> asked = Set.of(inStock, zeroQuantity, expiredOnly, fullyHeld, partlyHeld, heldInAButStockedInB,
                releasedHold, expiresToday, expiredPlusValidAllHeld, neverStocked);

        assertThat(stock.findProductIdsInStockAnywhere(asked))
                .as("hết hạn, số lượng 0, bị giữ hết ở mọi chi nhánh thì không còn khả dụng")
                .containsExactlyInAnyOrder(inStock, partlyHeld, heldInAButStockedInB, releasedHold, expiresToday);
    }

    @Test
    void onlyTheAskedProductsAreConsideredAndAnEmptyAskIsEmpty() {
        long branch = AiReadFixtures.next();
        long asked = AiReadFixtures.next();
        long notAsked = AiReadFixtures.next();
        fx.lot(branch, asked, 1, null);
        fx.lot(branch, notAsked, 1, null);

        assertThat(stock.findProductIdsInStockAnywhere(List.of(asked))).containsExactly(asked);
        assertThat(stock.findProductIdsInStockAnywhere(Set.of())).isEmpty();
    }
}
