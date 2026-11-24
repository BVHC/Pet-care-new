package com.petcare.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.inpatient.api.AdmissionQueryApi;
import com.petcare.module.inpatient.service.AdmissionQueryService;
import com.petcare.module.visit.api.VisitApi;
import com.petcare.module.visit.service.TreatmentVisitService;

/**
 * Interface chéo cho nội trú (T69 N2, N3; docs/adr/0149 mục 12–14, 16, 19; docs/06 §6b hàng 10): mỗi interface có đúng một
 * bean, là bản thật (không placeholder), và từ chối chạy ngoài transaction của bên gọi ({@code MANDATORY}) trước khi
 * chạm DB. Không ghi dữ liệu nên không cần era.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class Tier3Be1ContractWiringIT {

    @Autowired private ApplicationContext context;
    @Autowired private VisitApi visits;
    @Autowired private AdmissionQueryApi admissions;
    @Autowired private com.petcare.module.boarding.api.BoardingQueryApi boarding;
    @Autowired private com.petcare.module.boarding.api.KennelApi kennels;
    @Autowired private com.petcare.module.visit.api.PrescriptionApi prescriptions;

    @Test
    void eachInterfaceHasExactlyOneRealBean() {
        assertThat(context.getBeansOfType(VisitApi.class)).hasSize(1);
        assertThat(context.getBeansOfType(AdmissionQueryApi.class)).hasSize(1);
        assertThat(AopUtils.getTargetClass(visits)).isEqualTo(TreatmentVisitService.class);
        assertThat(AopUtils.getTargetClass(admissions)).isEqualTo(AdmissionQueryService.class);
    }

    @Test
    void callsOutsideATransactionAreRejected() {
        assertThatThrownBy(() -> visits.startTreatmentVisit(1L, 2L, 3L, 4L))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> admissions.hasActiveAdmission(1L))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> admissions.hasActiveAdmissionForVisit(1L))
                .isInstanceOf(IllegalTransactionStateException.class);
        // N3: hai hàm khóa mới (khóa phải giữ tới commit của bên gọi) và hai hàm đọc mã chuồng.
        assertThatThrownBy(() -> visits.lockVisitForAdmission(1L))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> boarding.findBookingForShare(1L))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> kennels.findCodesByKennelIds(java.util.List.of(1L)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> kennels.findCodesByBookingIds(java.util.List.of(1L)))
                .isInstanceOf(IllegalTransactionStateException.class);
        // N5: khóa FOR KEY SHARE dòng đơn thuốc phải giữ tới commit của bên gọi (docs/adr/0149 mục 28).
        assertThatThrownBy(() -> prescriptions.findItemOfVisitForKeyShare(1L, 2L))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
