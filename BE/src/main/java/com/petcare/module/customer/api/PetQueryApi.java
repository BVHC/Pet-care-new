package com.petcare.module.customer.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/** Owner: customer (KH) · BE-2. */
public interface PetQueryApi {

    /** {@code currentWeightKg} = bản ghi cân nặng mới nhất, null nếu chưa cân (BR-KH-04). */
    record PetSummary(Long petId, Long customerId, String name, Species species, LocalDate birthDate,
                      LocalDate deceasedOn, BigDecimal currentWeightKg) {

        public boolean deceased() {
            return deceasedOn != null;
        }
    }

    Optional<PetSummary> findPet(Long petId);
}
