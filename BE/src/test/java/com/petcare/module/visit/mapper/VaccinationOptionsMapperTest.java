package com.petcare.module.visit.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.petcare.module.visit.dto.ProtocolDoseOptionResponse;
import com.petcare.module.visit.dto.RecordVaccinationResultResponse;
import com.petcare.module.visit.dto.VaccinationOptionsResponse;
import com.petcare.module.visit.dto.VaccinationRecordResponse;
import com.petcare.module.visit.dto.VaccineProductOptionResponse;

/** {@link VaccinationOptionsMapper} (docs/adr/0073): mỗi trường một giá trị riêng để lấy nhầm nguồn là đỏ. */
class VaccinationOptionsMapperTest {

    private final VaccinationOptionsMapper mapper = Mappers.getMapper(VaccinationOptionsMapper.class);

    @Test
    void doseOptionKeepsEveryValue() {
        assertThat(mapper.toDoseOption(12L, 2, 21, 8, true))
                .isEqualTo(new ProtocolDoseOptionResponse(12L, 2, 21, 8, true));
    }

    @Test
    void productOptionKeepsEveryValue() {
        assertThat(mapper.toProductOption(81L, "Rabisin", 24))
                .isEqualTo(new VaccineProductOptionResponse(81L, "Rabisin", 24));
    }

    @Test
    void optionsTakeTheTypeIdFromItsParameterNotFromTheLastDose() {
        VaccinationRecordResponse last = new VaccinationRecordResponse(701L, 8000L, 9L, 77L, "Dại", 999L, 1, 81L,
                "Rabisin", null, null, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 22), false, 46L, 9102L);
        List<ProtocolDoseOptionResponse> protocol = List.of(new ProtocolDoseOptionResponse(12L, 1, 21, 8, false));
        List<VaccineProductOptionResponse> products = List.of(new VaccineProductOptionResponse(81L, "Rabisin", 3));

        VaccinationOptionsResponse options = mapper.toOptions(5L, protocol, 13L, last, products, true);

        assertThat(options).isEqualTo(new VaccinationOptionsResponse(5L, protocol, 13L, last, products, true));
    }

    @Test
    void recordResultKeepsTheDoseAndTheWarnings() {
        VaccinationRecordResponse dose = new VaccinationRecordResponse(701L, 8000L, 9L, 5L, "Dại", 12L, 2, 81L,
                "Rabisin", null, null, LocalDate.of(2026, 10, 25), LocalDate.of(2026, 11, 15), false, 45L, 9102L);

        assertThat(mapper.toRecordResult(dose, List.of("UNDER_MIN_AGE")))
                .isEqualTo(new RecordVaccinationResultResponse(dose, List.of("UNDER_MIN_AGE")));
        assertThat(mapper.toRecordResult(dose, List.of()).warnings()).isEmpty();
    }

    @Test
    void nullSuggestionAndLastDoseStayNull() {
        VaccinationOptionsResponse options = mapper.toOptions(5L, List.of(), null, null, List.of(), false);

        assertThat(options.suggestedProtocolId()).isNull();
        assertThat(options.lastDose()).isNull();
        assertThat(options.protocol()).isEmpty();
        assertThat(options.products()).isEmpty();
    }
}
