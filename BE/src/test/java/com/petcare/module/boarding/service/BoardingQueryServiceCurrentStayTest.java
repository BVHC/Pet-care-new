package com.petcare.module.boarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.lang.reflect.Method;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.boarding.api.BoardingQueryApi.StayStatus;
import com.petcare.module.boarding.api.BoardingQueryApi.StaySummary;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.identity.api.SystemConfigApi;

/**
 * {@code findCurrentStay} (docs/adr/0148): đặt chỗ đang giữ thú trong chuồng, gồm cả {@code OVERDUE}.
 * {@code findBookingForShare} (docs/adr/0149 mục 16): khóa chia sẻ chỉ dòng đặt chỗ, theo id, {@code MANDATORY}.
 */
class BoardingQueryServiceCurrentStayTest {

    private JdbcTemplate jdbc;
    private BoardingQueryService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new BoardingQueryService(jdbc, mock(PetQueryApi.class), mock(SystemConfigApi.class),
                Clock.fixed(Instant.parse("2026-11-22T03:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh")));
    }

    @SuppressWarnings("unchecked")
    @Test
    void asksForCheckedInAndOverdueStaysOfThePetOnly() {
        StaySummary stay = new StaySummary(11L, 1L, 2L, 3L, StayStatus.OVERDUE, "0900000000");
        when(jdbc.query(anyString(), any(RowMapper.class), eq(3L))).thenReturn(List.of(stay));

        Optional<StaySummary> found = service.findCurrentStay(3L);

        assertThat(found).contains(stay);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq(3L));
        assertThat(sql.getValue()).contains("b.pet_id = ?").contains("'CHECKED_IN'").contains("'OVERDUE'")
                .doesNotContain("'BOOKED'").doesNotContain("'CHECKED_OUT'");
    }

    @SuppressWarnings("unchecked")
    @Test
    void isEmptyWhenThePetIsNotInAKennel() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(4L))).thenReturn(List.of());

        assertThat(service.findCurrentStay(4L)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void findBookingForShareLocksOnlyTheBookingRowById() {
        StaySummary stay = new StaySummary(11L, 1L, 2L, 3L, StayStatus.CHECKED_IN, null);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(11L))).thenReturn(List.of(stay));

        assertThat(service.findBookingForShare(11L)).contains(stay);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq(11L));
        assertThat(sql.getValue()).contains("b.id = ?").endsWith("FOR SHARE OF b");
    }

    @SuppressWarnings("unchecked")
    @Test
    void findBookingForShareOfAMissingBookingIsEmpty() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(12L))).thenReturn(List.of());

        assertThat(service.findBookingForShare(12L)).isEmpty();
    }

    @Test
    void findBookingForShareRejectsANullIdAndRunsNoSql() {
        assertThatThrownBy(() -> service.findBookingForShare(null)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(jdbc);
    }

    @Test
    void findBookingForShareRequiresTheCallersTransaction() throws Exception {
        Method method = BoardingQueryService.class.getMethod("findBookingForShare", Long.class);

        assertThat(method.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
    }
}
