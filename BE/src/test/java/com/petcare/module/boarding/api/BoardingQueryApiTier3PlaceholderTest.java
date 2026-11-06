package com.petcare.module.boarding.api;

import static com.petcare.module.PlaceholderAssert.assertPlaceholder;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.petcare.module.customer.api.Species;
import com.petcare.module.visit.api.VisitQueryApi;

/** Placeholder tầng 3 của boarding (T55c, docs/06 §6b hàng 13, 16; docs/adr/0117, 07 I-7). */
class BoardingQueryApiTier3PlaceholderTest {

    private final BoardingQueryApi boarding = mock(BoardingQueryApi.class, CALLS_REAL_METHODS);

    @Test
    void findStaysOfPetThrowsUntilImplemented() {
        assertPlaceholder(() -> boarding.findStaysOfPet(1L), "BoardingQueryApi.findStaysOfPet", "T78");
    }

    @Test
    void countStaysByKennelTypeThrowsUntilImplemented() {
        assertPlaceholder(() -> boarding.countStaysByKennelType(Species.DOG, VisitQueryApi.AgeGroup.ADULT,
                LocalDate.of(2026, 8, 1)), "BoardingQueryApi.countStaysByKennelType", "T78");
    }

    @Test
    void countActiveStaysByBranchThrowsUntilImplemented() {
        assertPlaceholder(() -> boarding.countActiveStaysByBranch(1L), "BoardingQueryApi.countActiveStaysByBranch", "T75");
    }
}
