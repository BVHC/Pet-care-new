package com.petcare.module.customer.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.petcare.module.customer.api.Species;
import com.petcare.module.customer.api.WeightSource;
import com.petcare.module.customer.dto.PetResponse;
import com.petcare.module.customer.dto.WeightRecordResponse;
import com.petcare.module.customer.entity.Pet;
import com.petcare.module.customer.entity.PetSex;
import com.petcare.module.customer.entity.WeightRecord;

/** Mỗi trường của hợp đồng lấy từ đúng nguồn, JSON giữ đúng tên (kể cả {@code isNeutered}). */
class PetMapperTest {

    private final PetMapper mapper = new PetMapperImpl();
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void petResponseHasEveryContractKeyWithDistinctValues() throws Exception {
        Pet pet = Pet.create(315L, "Mực", Species.CAT, PetSex.FEMALE, "Anh lông ngắn", LocalDate.of(2024, 3, 15), true,
                "Xám", true, "https://cdn.petcare.vn/p.jpg");
        ReflectionTestUtils.setField(pet, "id", 88L);
        pet.markDeceased(LocalDate.of(2026, 9, 30));

        PetResponse response = mapper.toPetResponse(pet, true, new BigDecimal("4.25"), false);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.convertValue(response, Map.class);
        assertThat(body).containsOnlyKeys("petId", "customerId", "name", "species", "sex", "breed", "birthDate",
                        "birthDateEstimated", "color", "isNeutered", "photoUrl", "deceasedOn", "speciesLocked",
                        "currentWeightKg", "deletable")
                .containsEntry("petId", 88L).containsEntry("customerId", 315L).containsEntry("name", "Mực")
                .containsEntry("species", "CAT").containsEntry("sex", "FEMALE")
                .containsEntry("breed", "Anh lông ngắn").containsEntry("birthDate", "2024-03-15")
                .containsEntry("birthDateEstimated", true).containsEntry("color", "Xám")
                .containsEntry("isNeutered", true).containsEntry("photoUrl", "https://cdn.petcare.vn/p.jpg")
                .containsEntry("deceasedOn", "2026-09-30").containsEntry("speciesLocked", true)
                .containsEntry("currentWeightKg", new BigDecimal("4.25")).containsEntry("deletable", false);
    }

    @Test
    void nullablesStayNullAndKeysStayPresent() throws Exception {
        Pet pet = Pet.create(1L, "A", Species.DOG, PetSex.UNKNOWN, null, null, false, null, null, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.convertValue(mapper.toPetResponse(pet, false, null, true), Map.class);

        assertThat(body).containsEntry("breed", null).containsEntry("birthDate", null).containsEntry("color", null)
                .containsEntry("isNeutered", null).containsEntry("photoUrl", null)
                .containsEntry("deceasedOn", null).containsEntry("currentWeightKg", null)
                .containsEntry("deletable", true);
        assertThat(body).hasSize(15);
    }

    @Test
    void weightRecordResponseUsesTheContractKeys() throws Exception {
        WeightRecord record = WeightRecord.create(88L, new BigDecimal("9.50"), WeightSource.VET, 1204L, null, 3L,
                Instant.parse("2026-10-16T03:00:00Z"));
        ReflectionTestUtils.setField(record, "id", 501L);

        WeightRecordResponse response = mapper.toWeightResponse(record);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.convertValue(response, Map.class);
        assertThat(body).containsOnlyKeys("weightRecordId", "weightKg", "source", "visitId", "boardingBookingId",
                        "measuredAt")
                .containsEntry("weightRecordId", 501L).containsEntry("source", "VET").containsEntry("visitId", 1204L)
                .containsEntry("boardingBookingId", null).containsEntry("measuredAt", "2026-10-16T03:00:00Z");
    }
}
