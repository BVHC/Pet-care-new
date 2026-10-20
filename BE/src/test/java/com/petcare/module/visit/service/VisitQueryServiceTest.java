package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.petcare.module.visit.mapper.VisitQueryMapper;
import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.module.visit.repository.VisitRepository.AppointmentVisitRow;

/** {@link VisitQueryService#findVisitIdsByAppointmentIds} (nợ D028, docs/adr/0058): tập rỗng không chạy SQL. */
class VisitQueryServiceTest {

    private final VisitRepository visits = mock(VisitRepository.class);
    private final VisitQueryService service = new VisitQueryService(visits, Mappers.getMapper(VisitQueryMapper.class));

    @Test
    void emptyAppointmentIdsReadNothing() {
        assertThat(service.findVisitIdsByAppointmentIds(Set.of())).isEmpty();
        verifyNoInteractions(visits);
    }

    @Test
    void rowsBecomeAppointmentToVisitMap() {
        when(visits.findByAppointmentIds(Set.of(1L, 2L, 3L)))
                .thenReturn(List.of(new AppointmentVisitRow(1L, 10L), new AppointmentVisitRow(3L, 30L)));

        assertThat(service.findVisitIdsByAppointmentIds(Set.of(1L, 2L, 3L))).isEqualTo(Map.of(1L, 10L, 3L, 30L));
    }
}
