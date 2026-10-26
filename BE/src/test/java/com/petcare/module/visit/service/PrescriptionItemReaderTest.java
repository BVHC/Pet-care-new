package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.visit.dto.PrescriptionItemResponse;
import com.petcare.module.visit.entity.ExternalPurchaseReason;
import com.petcare.module.visit.entity.PrescriptionItem;
import com.petcare.module.visit.mapper.MedicalRecordMapper;
import com.petcare.module.visit.repository.PrescriptionItemRepository;

/**
 * {@link PrescriptionItemReader} (docs/adr/0076): đơn thuốc của chính bệnh án theo thứ tự kê, tên tra một lần cho cả
 * lô (không N+1), không câu catalog khi rỗng, thiếu tên là dữ liệu hỏng.
 */
class PrescriptionItemReaderTest {

    private static final long VISIT = 9001L;

    private final PrescriptionItemRepository items = mock(PrescriptionItemRepository.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final PrescriptionItemReader reader = new PrescriptionItemReader(items, catalog,
            Mappers.getMapper(MedicalRecordMapper.class));

    private static PrescriptionItem row(long id, long productId, boolean external) {
        PrescriptionItem item = BeanUtils.instantiateClass(PrescriptionItem.class);
        ReflectionTestUtils.setField(item, "id", id);
        ReflectionTestUtils.setField(item, "medicalRecordId", VISIT);
        ReflectionTestUtils.setField(item, "productId", productId);
        ReflectionTestUtils.setField(item, "quantity", 2);
        ReflectionTestUtils.setField(item, "dosageInstructions", "Ngày 2 lần");
        if (external) {
            ReflectionTestUtils.setField(item, "externalPurchase", true);
            ReflectionTestUtils.setField(item, "externalReason", ExternalPurchaseReason.OUT_OF_STOCK_AT_PRESCRIBE);
        } else {
            ReflectionTestUtils.setField(item, "orderLineId", 9100L + id);
        }
        return item;
    }

    private static ProductInfo product(long id, String name, boolean active) {
        return new ProductInfo(id, name, ProductType.DRUG, true, true, null, 30_000L, active);
    }

    @Test
    void aRecordWithoutPrescriptionIsAnEmptyListAndNeverCallsTheCatalog() {
        doReturn(List.of()).when(items).findByMedicalRecordIdOrderByIdAsc(VISIT);

        assertThat(reader.forRecord(VISIT)).isEmpty();

        verifyNoInteractions(catalog);
    }

    @Test
    void namesAreLookedUpOncePerBatchWithDistinctIdsAndInactiveProductsKeepTheirName() {
        doReturn(List.of(row(1L, 77L, false), row(2L, 77L, true), row(3L, 78L, false))).when(items)
                .findByMedicalRecordIdOrderByIdAsc(VISIT);
        doReturn(List.of(product(77L, "Amoxicillin", true), product(78L, "Cefalexin", false))).when(catalog)
                .findProducts(List.of(77L, 78L));

        List<PrescriptionItemResponse> result = reader.forRecord(VISIT);

        assertThat(result).extracting("itemId", "productName", "isExternalPurchase", "externalReason", "orderLineId")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1L, "Amoxicillin", false, null, 9101L),
                        org.assertj.core.groups.Tuple.tuple(2L, "Amoxicillin", true,
                                ExternalPurchaseReason.OUT_OF_STOCK_AT_PRESCRIBE, null),
                        org.assertj.core.groups.Tuple.tuple(3L, "Cefalexin", false, null, 9103L));
        verify(catalog, times(1)).findProducts(List.of(77L, 78L));
    }

    @Test
    void aMissingProductNameIsCorruptData() {
        doReturn(List.of(row(1L, 77L, false))).when(items).findByMedicalRecordIdOrderByIdAsc(VISIT);
        doReturn(List.of()).when(catalog).findProducts(List.of(77L));

        assertThatThrownBy(() -> reader.forRecord(VISIT)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Thuốc #77");
    }
}
