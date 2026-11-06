package com.petcare.module.customer.api;

import static com.petcare.module.PlaceholderAssert.assertPlaceholder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * Placeholder tầng 3 của customer (T55c, docs/06 §6b hàng 3, 14, 15; docs/adr/0117): method {@code default} ném
 * {@link UnsupportedOperationException} tới khi bản thật ghi đè; {@link CustomerMergedEvent} chỉ khai báo.
 */
class CustomerApiTier3PlaceholderTest {

    private final PetQueryApi pets = mock(PetQueryApi.class, CALLS_REAL_METHODS);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class, CALLS_REAL_METHODS);

    @Test
    void findPetProfileThrowsUntilImplemented() {
        assertPlaceholder(() -> pets.findPetProfile(1L), "PetQueryApi.findPetProfile", "T77");
    }

    @Test
    void findLivingPetsOfCustomerThrowsUntilImplemented() {
        assertPlaceholder(() -> pets.findLivingPetsOfCustomer(1L), "PetQueryApi.findLivingPetsOfCustomer", "T77");
    }

    @Test
    void findAddressThrowsUntilImplemented() {
        assertPlaceholder(() -> customers.findAddress(1L, 2L), "CustomerQueryApi.findAddress", "T63");
    }

    @Test
    void customerMergedEventCarriesSourceAndTarget() {
        CustomerMergedEvent event = new CustomerMergedEvent(10L, 20L);

        assertThat(event.sourceId()).isEqualTo(10L);
        assertThat(event.targetId()).isEqualTo(20L);
    }

    @Test
    void petProfileKnowsWhetherThePetIsDeceased() {
        PetQueryApi.PetProfile alive = new PetQueryApi.PetProfile(1L, 2L, Species.DOG, "Poodle", LocalDate.of(2020, 1, 1),
                BigDecimal.valueOf(4.5), null);
        PetQueryApi.PetProfile gone = new PetQueryApi.PetProfile(1L, 2L, Species.CAT, null, null, null, LocalDate.of(2026, 10, 1));

        assertThat(alive.deceased()).isFalse();
        assertThat(gone.deceased()).isTrue();
        assertThat(new PetQueryApi.LivingPet(1L, "Mochi").name()).isEqualTo("Mochi");
    }
}
