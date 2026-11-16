package com.petcare.module.returns.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.petcare.module.returns.repository.ReturnRequestDao;

class ReturnRequestQueryServiceTest {

    private final ReturnRequestDao dao = mock(ReturnRequestDao.class);
    private final ReturnRequestQueryService service = new ReturnRequestQueryService(dao);

    @Test
    void delegatesToTheDao() {
        when(dao.existsOpen(5L)).thenReturn(true);

        assertThat(service.hasOpenRequestForOrder(5L)).isTrue();
        assertThat(service.hasOpenRequestForOrder(6L)).isFalse();
    }

    @Test
    void aNullOrderIdIsAProgrammingError() {
        assertThatThrownBy(() -> service.hasOpenRequestForOrder(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
