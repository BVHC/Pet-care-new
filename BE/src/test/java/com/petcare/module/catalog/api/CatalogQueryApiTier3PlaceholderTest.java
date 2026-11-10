package com.petcare.module.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Placeholder tầng 3 của catalog (T55c, docs/06 §6b hàng 15, 16; docs/adr/0117, 07 I-3). */
class CatalogQueryApiTier3PlaceholderTest {

    @Test
    void recommendableGroupsTheThreeSources() {
        CatalogQueryApi.Recommendable recommendable = new CatalogQueryApi.Recommendable(List.of(), List.of(), List.of());

        assertThat(recommendable.groomingServices()).isEmpty();
        assertThat(recommendable.kennelTypes()).isEmpty();
        assertThat(recommendable.products()).isEmpty();
    }
}
