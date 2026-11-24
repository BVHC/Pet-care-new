package com.petcare.module.inpatient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.petcare.module.inpatient.repository.AdmissionRepository;

/** {@code AdmissionQueryApi} (docs/adr/0149 mục 13): chuyển thẳng câu hỏi xuống repository; id null là lỗi lập trình. */
class AdmissionQueryServiceTest {

    private final AdmissionRepository admissions = mock(AdmissionRepository.class);
    private final AdmissionQueryService service = new AdmissionQueryService(admissions);

    @Test
    void asksTheRepositoryByPet() {
        when(admissions.existsActiveByPetId(12L)).thenReturn(true);

        assertThat(service.hasActiveAdmission(12L)).isTrue();
        assertThat(service.hasActiveAdmission(13L)).isFalse();
    }

    @Test
    void asksTheRepositoryByVisit() {
        when(admissions.existsActiveByVisitId(9001L)).thenReturn(true);

        assertThat(service.hasActiveAdmissionForVisit(9001L)).isTrue();
        assertThat(service.hasActiveAdmissionForVisit(9002L)).isFalse();
    }

    @Test
    void aNullIdIsRejectedWithoutQuerying() {
        assertThatThrownBy(() -> service.hasActiveAdmission(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.hasActiveAdmissionForVisit(null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(admissions);
    }
}
