package com.petcare.module.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.module.AiReadFixtures;
import com.petcare.module.AiReadItSupport;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.KennelTypeInfo;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductDisplay;
import com.petcare.module.catalog.api.CatalogQueryApi.ProtocolDose;
import com.petcare.module.catalog.api.CatalogQueryApi.RecommendDisplay;
import com.petcare.module.catalog.api.CatalogQueryApi.Recommendable;
import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.customer.api.Species;

/** I-3 (07 §11, AI-0): phác đồ đang dùng và nguồn gợi ý, trên Postgres thật. */
class CatalogAiReadIT extends AiReadItSupport {

    @Autowired private CatalogQueryApi catalog;

    @Test
    void activeProtocolsOfTheSpeciesComeByVaccineTypeThenDose() {
        long type1 = AiReadFixtures.next();
        long type2 = AiReadFixtures.next();
        long dose2 = fx.protocol("DOG", type1, 2, true);
        long dose1 = fx.protocol("DOG", type1, 1, true);
        fx.protocol("DOG", type1, 3, false);
        long otherType = fx.protocol("DOG", type2, 1, true);
        fx.protocol("CAT", type1, 1, true);

        List<ProtocolDose> mine = catalog.listActiveProtocols(Species.DOG).stream()
                .filter(p -> p.vaccineTypeId() == type1 || p.vaccineTypeId() == type2).toList();

        assertThat(mine).extracting(ProtocolDose::protocolId)
                .as("bỏ dòng ngừng dùng và dòng của loài khác").containsExactly(dose1, dose2, otherType);
        assertThat(catalog.listActiveProtocols(Species.DOG)).allSatisfy(p -> {
            assertThat(p.active()).isTrue();
            assertThat(p.species()).isEqualTo(Species.DOG);
        });
    }

    @Test
    void recommendDisplayCarriesCategoryImageAndThePriceFromColumnOfTheGivenIdsOnly() {
        long fromService = fx.service("GROOMING", true);
        long flatService = fx.service("GROOMING", true);
        long skipped = fx.service("GROOMING", true);
        jdbc.update("UPDATE services SET price_is_from = false WHERE id = ?", flatService);
        long withImage = fx.product("GOODS", false, true);
        long withoutImage = fx.product("GOODS", false, true);
        long skippedProduct = fx.product("GOODS", false, true);
        // Danh mục 4242 không có thật (fixture cũng dùng id giả): bỏ kiểm khóa ngoại trong đúng transaction này.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("UPDATE products SET category_id = 4242, image_url = 'https://img.example/a.png' WHERE id = ?", withImage);
        });

        RecommendDisplay display = catalog.findRecommendDisplay(List.of(fromService, flatService),
                List.of(withImage, withoutImage));

        assertThat(display.priceFromServiceIds()).containsExactly(fromService);
        assertThat(display.products()).containsOnlyKeys(withImage, withoutImage).doesNotContainKey(skippedProduct);
        assertThat(display.products().get(withImage)).isEqualTo(new ProductDisplay(4242L, "https://img.example/a.png"));
        assertThat(display.products().get(withoutImage).imageUrl()).isNull();
        assertThat(display.priceFromServiceIds()).doesNotContain(skipped);
    }

    @Test
    void recommendableHoldsOnlyActiveGroomingKennelsAndOverTheCounterGoods() {
        long groom = fx.service("GROOMING", true);
        long groomOff = fx.service("GROOMING", false);
        long exam = fx.service("MEDICAL", true);
        long board = fx.service("BOARDING", true);
        fx.kennelType(board, "DOG", "12.50");
        long boardOff = fx.service("BOARDING", false);
        fx.kennelType(boardOff, "CAT", "6.00");
        long goods = fx.product("GOODS", false, true);
        long goodsOff = fx.product("GOODS", false, false);
        long drugRx = fx.product("DRUG", true, true);
        long drugOtc = fx.product("DRUG", false, true);
        long vaccine = fx.product("VACCINE", false, true);

        Recommendable r = catalog.listRecommendable();

        assertThat(r.groomingServices()).extracting(s -> s.serviceId()).contains(groom)
                .doesNotContain(groomOff, exam, board);
        assertThat(r.kennelTypes()).extracting(KennelTypeInfo::serviceId).contains(board).doesNotContain(boardOff);
        KennelTypeInfo kennel = r.kennelTypes().stream().filter(k -> k.serviceId() == board).findFirst().orElseThrow();
        assertThat(kennel.species()).isEqualTo(Species.DOG);
        assertThat(kennel.maxWeightKg()).isEqualByComparingTo(new BigDecimal("12.50"));
        assertThat(kennel.nightlyPrice()).isEqualTo(120000L);
        assertThat(r.products()).extracting(p -> p.productId()).contains(goods)
                .doesNotContain(goodsOff, drugRx, drugOtc, vaccine);
        assertThat(r.groomingServices()).allSatisfy(s -> assertThat(s.active()).isTrue());
        assertThat(r.kennelTypes()).allSatisfy(k -> assertThat(k.active()).isTrue());
        assertThat(r.products()).allSatisfy(p -> {
            assertThat(p.active()).isTrue();
            assertThat(p.type()).isEqualTo(ProductType.GOODS);
            assertThat(p.prescription()).isFalse();
        });
    }
}
