package com.petcare.module.catalog.api;

import static com.petcare.module.PlaceholderAssert.assertPlaceholder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.petcare.module.customer.api.Species;

/** Placeholder tầng 3 của catalog (T55c, docs/06 §6b hàng 15, 16; docs/adr/0117, 07 I-3). */
class CatalogQueryApiTier3PlaceholderTest {

    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class, CALLS_REAL_METHODS);

    @Test
    void listActiveProtocolsThrowsUntilImplemented() {
        assertPlaceholder(() -> catalog.listActiveProtocols(Species.DOG), "CatalogQueryApi.listActiveProtocols", "T77");
    }

    @Test
    void listRecommendableThrowsUntilImplemented() {
        assertPlaceholder(catalog::listRecommendable, "CatalogQueryApi.listRecommendable", "T78");
    }

    @Test
    void recommendableGroupsTheThreeSources() {
        CatalogQueryApi.Recommendable recommendable = new CatalogQueryApi.Recommendable(List.of(), List.of(), List.of());

        assertThat(recommendable.groomingServices()).isEmpty();
        assertThat(recommendable.kennelTypes()).isEmpty();
        assertThat(recommendable.products()).isEmpty();
    }
}
