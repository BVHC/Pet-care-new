package com.petcare.module.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.petcare.module.AiReadFixtures;
import com.petcare.module.AiReadItSupport;
import com.petcare.module.inventory.api.StockQueryApi;

/**
 * {@link StockQueryApi#findChainAvailableQuantity} (task 116, docs/adr/0141): tồn khả dụng toàn chuỗi = Σ lô chưa hết hạn ở
 * mọi chi nhánh − Σ phần {@code HELD}, không âm, một câu SQL. Truy vấn chỉ xét các id được hỏi nên khẳng định chính xác được.
 */
class StockChainAvailabilityIT extends AiReadItSupport {

    @Autowired private StockQueryApi stock;

    @Test
    void sumsNonExpiredLotsOfEveryBranchMinusHeldAndNeverGoesBelowZero() {
        long a = fx.branch("ChainIT ", "ACTIVE", false);
        long b = fx.branch("ChainIT ", "ACTIVE", false);
        long draft = fx.branch("ChainIT ", "DRAFT", false);
        LocalDate t = today();
        long split = fx.product("GOODS", false, true);
        long expiredMixed = fx.product("GOODS", false, true);
        long expiresToday = fx.product("GOODS", false, true);
        long held = fx.product("GOODS", false, true);
        long heldTwoBranches = fx.product("GOODS", false, true);
        long releasedConsumed = fx.product("GOODS", false, true);
        long overHeld = fx.product("GOODS", false, true);
        long zeroLot = fx.product("GOODS", false, true);
        long noLots = fx.product("GOODS", false, true);
        long unknown = AiReadFixtures.next();

        fx.lot(a, split, 3, null);
        fx.lot(b, split, 4, t.plusDays(5));
        fx.lot(draft, split, 5, null);
        fx.lot(a, expiredMixed, 10, t.minusDays(1));
        fx.lot(a, expiredMixed, 2, t.plusDays(30));
        fx.lot(a, expiresToday, 6, t);
        fx.lot(a, held, 9, null);
        fx.reservation(a, held, 4, "HELD");
        fx.lot(a, heldTwoBranches, 5, null);
        fx.lot(b, heldTwoBranches, 5, null);
        fx.reservation(a, heldTwoBranches, 3, "HELD");
        fx.reservation(b, heldTwoBranches, 2, "HELD");
        fx.lot(a, releasedConsumed, 5, null);
        fx.reservation(a, releasedConsumed, 2, "RELEASED");
        fx.reservation(a, releasedConsumed, 1, "CONSUMED");
        fx.lot(a, overHeld, 2, null);
        fx.reservation(a, overHeld, 5, "HELD");
        fx.lot(a, zeroLot, 0, null);

        Map<Long, Long> result = stock.findChainAvailableQuantity(
                Set.of(split, expiredMixed, expiresToday, held, heldTwoBranches, releasedConsumed, overHeld, zeroLot,
                        noLots, unknown));

        assertThat(result).containsOnlyKeys(split, expiredMixed, expiresToday, held, heldTwoBranches, releasedConsumed,
                overHeld, zeroLot, noLots, unknown);
        assertThat(result.get(split)).as("mọi chi nhánh, kể cả chi nhánh chưa ACTIVE").isEqualTo(12L);
        assertThat(result.get(expiredMixed)).as("lô hết hạn bị loại").isEqualTo(2L);
        assertThat(result.get(expiresToday)).as("hạn đúng hôm nay vẫn dùng được").isEqualTo(6L);
        assertThat(result.get(held)).isEqualTo(5L);
        assertThat(result.get(heldTwoBranches)).isEqualTo(5L);
        assertThat(result.get(releasedConsumed)).as("RELEASED / CONSUMED không trừ").isEqualTo(5L);
        assertThat(result.get(overHeld)).as("không âm").isZero();
        assertThat(result.get(zeroLot)).isZero();
        assertThat(result.get(noLots)).isZero();
        assertThat(result.get(unknown)).isZero();
    }

    @Test
    void anEmptyCollectionGivesAnEmptyMapWithoutTouchingTheDatabase() {
        assertThat(stock.findChainAvailableQuantity(List.of())).isEmpty();
    }
}
