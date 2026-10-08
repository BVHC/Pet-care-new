package com.petcare.module.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.catalog.dto.CreateProductRequest;
import com.petcare.module.catalog.dto.ProductResponse;
import com.petcare.module.catalog.dto.UpdateProductRequest;
import com.petcare.module.catalog.entity.Product;
import com.petcare.module.catalog.exception.DuplicateCatalogEntryException;
import com.petcare.module.catalog.mapper.CatalogMapperImpl;
import com.petcare.module.catalog.repository.ProductCategoryRepository;
import com.petcare.module.catalog.repository.ProductRepository;
import com.petcare.module.catalog.repository.VaccineTypeRepository;
import com.petcare.module.inventory.api.StockQueryApi;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;

/** UC29: BR-SP-01, BR-SP-05, BR-SP-07; đổi giá ghi audit PRICE_CHANGED (BR-QT-15). */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock ProductRepository products;
    @Mock ProductCategoryRepository categories;
    @Mock VaccineTypeRepository vaccineTypes;
    @Mock StockQueryApi stock;
    @Mock AuditRecorder auditRecorder;

    ProductService service;

    @BeforeEach
    void setUp() {
        service = new ProductService(products, categories, vaccineTypes, stock, auditRecorder,
                new CatalogMapperImpl());
    }

    private static CreateProductRequest create(ProductType type, boolean prescription, boolean tracksExpiry,
            Long vaccineTypeId) {
        return new CreateProductRequest(1L, " SKU-1 ", "Sản phẩm", type, prescription, tracksExpiry, vaccineTypeId,
                "hộp", 100_000L, null, null);
    }

    private static Product existing(ProductType type, boolean prescription, boolean tracksExpiry, Long vaccineTypeId,
            long price) {
        Product product = new Product(1L, "SKU-1", "Sản phẩm", type, prescription, tracksExpiry, vaccineTypeId,
                "hộp", price, null, null);
        ReflectionTestUtils.setField(product, "id", 7L);
        return product;
    }

    private void productExists(Product product) {
        when(products.findById(7L)).thenReturn(Optional.of(product));
        lenient().when(products.saveAndFlush(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createsGoodsAndTrimsSku() {
        when(products.existsBySku("SKU-1")).thenReturn(false);
        when(categories.existsById(1L)).thenReturn(true);
        when(products.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductResponse response = service.createProduct(create(ProductType.GOODS, false, false, null));

        assertThat(response.sku()).isEqualTo("SKU-1");
        assertThat(response.isActive()).isTrue();
    }

    @Test
    void duplicateSkuIsRejected() {
        when(products.existsBySku("SKU-1")).thenReturn(true);

        assertThatThrownBy(() -> service.createProduct(create(ProductType.GOODS, false, false, null)))
                .isInstanceOf(DuplicateCatalogEntryException.class);
        verify(products, never()).save(any());
    }

    @Test
    void unknownCategoryIsNotFound() {
        when(categories.existsById(1L)).thenReturn(false);

        assertThatThrownBy(() -> service.createProduct(create(ProductType.GOODS, false, false, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void prescriptionFlagOnlyForDrugs_BR_SP_01() {
        when(categories.existsById(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.createProduct(create(ProductType.GOODS, true, true, null)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getRuleId()).isEqualTo("BR-SP-01"));
    }

    @Test
    void prescriptionDrugMustTrackExpiry_BR_SP_05() {
        when(categories.existsById(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.createProduct(create(ProductType.DRUG, true, false, null)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getRuleId()).isEqualTo("BR-SP-05"));
    }

    @Test
    void vaccineMustTrackExpiry_BR_SP_05() {
        when(categories.existsById(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.createProduct(create(ProductType.VACCINE, false, false, 3L)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getRuleId()).isEqualTo("BR-SP-05"));
    }

    @Test
    void nonPrescriptionDrugMaySkipExpiry() {
        when(categories.existsById(1L)).thenReturn(true);
        when(products.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductResponse response = service.createProduct(create(ProductType.DRUG, false, false, null));

        assertThat(response.tracksExpiry()).isFalse();
    }

    @Test
    void vaccineMustHaveVaccineType_BR_SP_07() {
        when(categories.existsById(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.createProduct(create(ProductType.VACCINE, false, true, null)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getRuleId()).isEqualTo("BR-SP-07"));
    }

    @Test
    void vaccineTypeMustExist_BR_SP_07() {
        when(categories.existsById(1L)).thenReturn(true);
        when(vaccineTypes.existsById(3L)).thenReturn(false);

        assertThatThrownBy(() -> service.createProduct(create(ProductType.VACCINE, false, true, 3L)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getRuleId()).isEqualTo("BR-SP-07"));
    }

    @Test
    void onlyVaccinesCarryVaccineType_BR_SP_07() {
        when(categories.existsById(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.createProduct(create(ProductType.GOODS, false, false, 3L)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getRuleId()).isEqualTo("BR-SP-07"));
    }

    @Test
    void createsVaccineWithType() {
        when(categories.existsById(1L)).thenReturn(true);
        when(vaccineTypes.existsById(3L)).thenReturn(true);
        when(products.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductResponse response = service.createProduct(create(ProductType.VACCINE, false, true, 3L));

        assertThat(response.vaccineTypeId()).isEqualTo(3L);
    }

    @Test
    void turningOffExpiryWhileStockExistsIsRejected_BR_SP_05() {
        productExists(existing(ProductType.GOODS, false, true, null, 100));
        when(stock.hasStockAnywhere(7L)).thenReturn(true);

        assertThatThrownBy(() -> service.updateProduct(7L,
                new UpdateProductRequest(null, null, null, false, null, null, null, null, null, null)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getRuleId()).isEqualTo("BR-SP-05"));
        verify(products, never()).saveAndFlush(any());
    }

    @Test
    void turningOffExpiryWithoutStockIsAllowed() {
        productExists(existing(ProductType.GOODS, false, true, null, 100));
        when(stock.hasStockAnywhere(7L)).thenReturn(false);

        ProductResponse response = service.updateProduct(7L,
                new UpdateProductRequest(null, null, null, false, null, null, null, null, null, null));

        assertThat(response.tracksExpiry()).isFalse();
    }

    @Test
    void cannotTurnOffExpiryOfVaccineEvenWithoutStock_BR_SP_05() {
        productExists(existing(ProductType.VACCINE, false, true, 3L, 100));

        assertThatThrownBy(() -> service.updateProduct(7L,
                new UpdateProductRequest(null, null, null, false, null, null, null, null, null, null)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getRuleId()).isEqualTo("BR-SP-05"));
        verifyNoInteractions(stock);
    }

    @Test
    void priceChangeIsAudited() {
        productExists(existing(ProductType.GOODS, false, false, null, 100));

        service.updateProduct(7L, new UpdateProductRequest(null, null, null, null, null, null, 250L, null, null, null));

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditRecorder).record(captor.capture());
        AuditEntry entry = captor.getValue();
        assertThat(entry.action()).isEqualTo("PRICE_CHANGED");
        assertThat(entry.entityType()).isEqualTo("products");
        assertThat(entry.entityId()).isEqualTo(7L);
        assertThat(entry.before()).isEqualTo(new PriceAuditSnapshot(100));
        assertThat(entry.after()).isEqualTo(new PriceAuditSnapshot(250));
    }

    @Test
    void updateWithoutPriceChangeIsNotAudited() {
        productExists(existing(ProductType.GOODS, false, false, null, 100));

        ProductResponse response = service.updateProduct(7L,
                new UpdateProductRequest(null, " Tên mới ", null, null, null, null, 100L, null, null, false));

        assertThat(response.name()).isEqualTo("Tên mới");
        assertThat(response.isActive()).isFalse();
        verifyNoInteractions(auditRecorder, stock);
    }

    @Test
    void updateUnknownProductIsNotFound() {
        when(products.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateProduct(7L,
                new UpdateProductRequest(null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
