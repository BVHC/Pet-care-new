package com.petcare.module.visit.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.module.visit.repository.VisitRepository.AssignedRow;
import com.petcare.module.visit.repository.VisitRepository.SummaryRow;

/**
 * {@link VisitQueryMapper}: mỗi trường một giá trị riêng nên map nhầm nguồn là đỏ; enum trạng thái của entity và của
 * {@code api} có đúng cùng tập tên (docs/adr/0058) — MapStruct chỉ bắt chiều entity → api lúc biên dịch.
 */
class VisitQueryMapperTest {

    private final VisitQueryMapper mapper = Mappers.getMapper(VisitQueryMapper.class);

    @Test
    void summaryMapsEveryFieldFromItsOwnSource() {
        VisitQueryApi.VisitSummary summary = mapper.toSummary(
                new SummaryRow(1L, 2L, 3L, 4L, ServiceGroup.GROOMING, VisitStatus.IN_PROGRESS, 5L));

        assertThat(summary).isEqualTo(new VisitQueryApi.VisitSummary(1L, 2L, 3L, 4L, ServiceGroup.GROOMING,
                VisitQueryApi.VisitStatus.IN_PROGRESS, 5L));
    }

    @Test
    void assignedMapsEveryField() {
        assertThat(mapper.toAssigned(new AssignedRow(7L, 8L, VisitStatus.WAITING)))
                .isEqualTo(new VisitQueryApi.AssignedVisit(7L, 8L, VisitQueryApi.VisitStatus.WAITING));
    }

    @Test
    void statusEnumsHaveTheSameConstantsBothWays() {
        assertThat(Arrays.stream(VisitQueryApi.VisitStatus.values()).map(Enum::name))
                .containsExactly(Arrays.stream(VisitStatus.values()).map(Enum::name).toArray(String[]::new));
        for (VisitStatus status : VisitStatus.values()) {
            assertThat(mapper.toApiStatus(status).name()).isEqualTo(status.name());
        }
    }
}
