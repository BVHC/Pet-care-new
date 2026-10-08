package com.petcare.module.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.catalog.dto.ProductCategoryRequest;
import com.petcare.module.catalog.dto.ProductCategoryResponse;
import com.petcare.module.catalog.dto.UpdateProductCategoryRequest;
import com.petcare.module.catalog.entity.ProductCategory;
import com.petcare.module.catalog.exception.DuplicateCatalogEntryException;
import com.petcare.module.catalog.mapper.CatalogMapperImpl;
import com.petcare.module.catalog.repository.ProductCategoryRepository;
import com.petcare.platform.exception.ResourceNotFoundException;

/** UC28: danh mục sản phẩm — tạo, đổi tên, ẩn (không xóa). */
@ExtendWith(MockitoExtension.class)
class ProductCategoryServiceTest {

    @Mock ProductCategoryRepository categories;

    ProductCategoryService service;

    @BeforeEach
    void setUp() {
        service = new ProductCategoryService(categories, new CatalogMapperImpl());
    }

    private ProductCategory existing() {
        ProductCategory category = new ProductCategory("Thức ăn");
        ReflectionTestUtils.setField(category, "id", 2L);
        when(categories.findById(2L)).thenReturn(Optional.of(category));
        return category;
    }

    @Test
    void createsAndTrimsName() {
        when(categories.save(any(ProductCategory.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductCategoryResponse response = service.createCategory(new ProductCategoryRequest("  Thuốc  "));

        assertThat(response.name()).isEqualTo("Thuốc");
        assertThat(response.isActive()).isTrue();
    }

    @Test
    void duplicateNameIsRejected() {
        when(categories.existsByName("Thuốc")).thenReturn(true);

        assertThatThrownBy(() -> service.createCategory(new ProductCategoryRequest("Thuốc")))
                .isInstanceOf(DuplicateCatalogEntryException.class);
    }

    @Test
    void renameChecksDuplicateExcludingSelf() {
        existing();
        when(categories.existsByNameAndIdNot("Thuốc", 2L)).thenReturn(true);

        assertThatThrownBy(() -> service.updateCategory(2L, new UpdateProductCategoryRequest("Thuốc", null)))
                .isInstanceOf(DuplicateCatalogEntryException.class);
    }

    @Test
    void hidesCategory() {
        existing();
        when(categories.saveAndFlush(any(ProductCategory.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductCategoryResponse response = service.updateCategory(2L, new UpdateProductCategoryRequest(null, false));

        assertThat(response.isActive()).isFalse();
        assertThat(response.name()).isEqualTo("Thức ăn");
        verify(categories).saveAndFlush(any(ProductCategory.class));
    }

    @Test
    void updateUnknownIsNotFound() {
        when(categories.findById(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateCategory(2L, new UpdateProductCategoryRequest("x", null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
