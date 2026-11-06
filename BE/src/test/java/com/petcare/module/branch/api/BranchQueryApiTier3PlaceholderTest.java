package com.petcare.module.branch.api;

import static com.petcare.module.PlaceholderAssert.assertPlaceholder;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

/** Placeholder tầng 3 của branch (T55c, docs/06 §6b hàng 15; docs/adr/0117, 07 I-4). */
class BranchQueryApiTier3PlaceholderTest {

    private final BranchQueryApi branches = mock(BranchQueryApi.class, CALLS_REAL_METHODS);

    @Test
    void findEmergencyBranchesThrowsUntilImplemented() {
        assertPlaceholder(branches::findEmergencyBranches, "BranchQueryApi.findEmergencyBranches", "T77");
    }

    @Test
    void findServiceIdsEnabledAnywhereThrowsUntilImplemented() {
        assertPlaceholder(branches::findServiceIdsEnabledAnywhere, "BranchQueryApi.findServiceIdsEnabledAnywhere", "T77");
    }
}
