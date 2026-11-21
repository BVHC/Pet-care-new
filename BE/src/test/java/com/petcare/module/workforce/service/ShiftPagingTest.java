package com.petcare.module.workforce.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

/**
 * {@link ShiftAssignmentService#pageOf} (docs/adr/0138 bước 8): trang của mọi danh sách workforce (#4, #10, #12). Spring
 * Data JPA ném {@code InvalidDataAccessApiUsageException} (500) khi {@code page × size} vượt {@code Integer.MAX_VALUE},
 * nên {@code page} phải được kẹp; giá trị bình thường không đổi so với công thức cũ.
 */
class ShiftPagingTest {

    @Test
    void ordinaryValuesAreUnchanged() {
        PageRequest page = ShiftAssignmentService.pageOf(2, 20);

        assertThat(page.getPageNumber()).isEqualTo(2);
        assertThat(page.getPageSize()).isEqualTo(20);
        assertThat(page.getSort().isUnsorted()).isTrue();
    }

    @Test
    void aNegativePageBecomesTheFirstPage() {
        assertThat(ShiftAssignmentService.pageOf(-1, 20).getPageNumber()).isZero();
        assertThat(ShiftAssignmentService.pageOf(Integer.MIN_VALUE, 20).getPageNumber()).isZero();
    }

    @Test
    void theSizeIsClampedToOneThroughOneHundred() {
        assertThat(ShiftAssignmentService.pageOf(0, 0).getPageSize()).isEqualTo(1);
        assertThat(ShiftAssignmentService.pageOf(0, -5).getPageSize()).isEqualTo(1);
        assertThat(ShiftAssignmentService.pageOf(0, 1).getPageSize()).isEqualTo(1);
        assertThat(ShiftAssignmentService.pageOf(0, 100).getPageSize()).isEqualTo(100);
        assertThat(ShiftAssignmentService.pageOf(0, 101).getPageSize()).isEqualTo(100);
        assertThat(ShiftAssignmentService.pageOf(0, Integer.MAX_VALUE).getPageSize()).isEqualTo(100);
    }

    @Test
    void theOffsetNeverExceedsIntegerMaxValueForAnyPageOrSize() {
        for (int size : new int[] {-1, 0, 1, 2, 7, 20, 99, 100, 101, 1000, Integer.MAX_VALUE}) {
            for (int page : new int[] {0, 1, 21_474_837, 21_474_836, Integer.MAX_VALUE - 1, Integer.MAX_VALUE}) {
                PageRequest result = ShiftAssignmentService.pageOf(page, size);

                assertThat(result.getOffset()).as("page=%d size=%d", page, size)
                        .isBetween(0L, (long) Integer.MAX_VALUE);
            }
        }
    }

    @Test
    void theLastReachablePageIsKeptAndOneBeyondItIsClampedToIt() {
        int size = 100;
        int last = Integer.MAX_VALUE / size;

        assertThat(ShiftAssignmentService.pageOf(last, size).getPageNumber()).isEqualTo(last);
        assertThat(ShiftAssignmentService.pageOf(last + 1, size).getPageNumber()).isEqualTo(last);
        assertThat(ShiftAssignmentService.pageOf(Integer.MAX_VALUE, 1).getPageNumber()).isEqualTo(Integer.MAX_VALUE);
        assertThat(ShiftAssignmentService.pageOf(Integer.MAX_VALUE, 1).getOffset()).isEqualTo(Integer.MAX_VALUE);
    }
}
