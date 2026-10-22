package com.petcare.module.appointment.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mapstruct.factory.Mappers;

import com.petcare.module.appointment.dto.SlotResponse;
import com.petcare.module.appointment.dto.SlotResponse.SlotStatus;
import com.petcare.module.appointment.service.SlotAvailability;

/** {@link SlotMapper} thật (MapStruct): {@code slotEnd} = bắt đầu + 30 phút; FULL khi {@code booked ≥ quota}. */
class SlotMapperTest {

    private final SlotMapper mapper = Mappers.getMapper(SlotMapper.class);

    @ParameterizedTest(name = "quota {0}, booked {1} → {2}")
    @CsvSource({
            "0, 0, FULL",
            "2, 1, AVAILABLE",
            "2, 2, FULL",
            "1, 3, FULL",
            "1, 0, AVAILABLE"})
    void statusIsFullWhenBookedReachesQuota(int quota, int booked, SlotStatus status) {
        SlotResponse response = mapper.toSlotResponse(new SlotAvailability(LocalTime.of(8, 15), quota, booked));

        assertThat(response).isEqualTo(new SlotResponse(LocalTime.of(8, 15), LocalTime.of(8, 45), quota, booked,
                status));
    }

    @ParameterizedTest
    @CsvSource({"08:00, 08:30", "08:15, 08:45", "23:00, 23:30", "23:29, 23:59"})
    void slotEndIsThirtyMinutesLater(LocalTime start, LocalTime end) {
        assertThat(mapper.toSlotResponse(new SlotAvailability(start, 1, 0)).slotEnd()).isEqualTo(end);
    }
}
