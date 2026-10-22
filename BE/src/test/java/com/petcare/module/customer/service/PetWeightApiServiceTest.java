package com.petcare.module.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.api.RecordedWeight;
import com.petcare.module.customer.api.Species;
import com.petcare.module.customer.api.WeightSource;
import com.petcare.module.customer.entity.Pet;
import com.petcare.module.customer.entity.PetSex;
import com.petcare.module.customer.entity.WeightRecord;
import com.petcare.module.customer.repository.PetRepository;
import com.petcare.module.customer.repository.WeightRecordRepository;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;

class PetWeightApiServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-16T03:00:00Z");

    private final PetRepository pets = mock(PetRepository.class);
    private final WeightRecordRepository weights = mock(WeightRecordRepository.class);
    private final PetWeightApiService service = new PetWeightApiService(pets, weights,
            Clock.fixed(NOW.plusNanos(789), ZoneOffset.UTC));

    private static Pet alivePet() {
        return Pet.create(1L, "Mực", Species.DOG, PetSex.MALE, null, null, false, null, null, null);
    }

    @Test
    void recordsAfterShareLockingThePetAndReturnsTheRecordedWeight() {
        Pet pet = alivePet();
        when(pets.findByIdForShare(7L)).thenReturn(Optional.of(pet));
        WeightRecord saved = mock(WeightRecord.class);
        when(saved.getId()).thenReturn(55L);
        when(weights.saveAndFlush(any())).thenReturn(saved);

        RecordedWeight recorded = service.recordWeight(7L, new BigDecimal("12.346"), WeightSource.VET, 9L, null, 3L);

        assertThat(recorded.weightRecordId()).isEqualTo(55L);
        assertThat(recorded.weightKg()).isEqualByComparingTo("12.35");
        assertThat(recorded.measuredAt()).isEqualTo(NOW);
        verify(pets, never()).findByIdForUpdate(any());
        InOrder order = inOrder(pets, weights);
        order.verify(pets).findByIdForShare(7L);
        ArgumentCaptor<WeightRecord> captor = ArgumentCaptor.forClass(WeightRecord.class);
        order.verify(weights).saveAndFlush(captor.capture());
        WeightRecord record = captor.getValue();
        assertThat(record.getWeightKg()).isEqualByComparingTo("12.35");
        assertThat(record.getSource()).isEqualTo(WeightSource.VET);
        assertThat(record.getVisitId()).isEqualTo(9L);
        assertThat(record.getBoardingBookingId()).isNull();
        assertThat(record.getRecordedBy()).isEqualTo(3L);
        assertThat(record.getMeasuredAt()).isEqualTo(NOW);
    }

    @Test
    void nonPositiveWeightIsRejectedBeforeAnyRead() {
        for (String kg : new String[] {"0", "-1", "0.004"}) {
            assertThatThrownBy(() -> service.recordWeight(7L, new BigDecimal(kg), WeightSource.OWNER, null, null, 3L))
                    .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-KH-04");
        }
        assertThatThrownBy(() -> service.recordWeight(7L, null, WeightSource.OWNER, null, null, 3L))
                .isInstanceOf(BusinessRuleViolationException.class);
        verifyNoInteractions(pets, weights);
    }

    @Test
    void weightAboveColumnRangeIsRejected() {
        assertThatThrownBy(() -> service.recordWeight(7L, new BigDecimal("10000"), WeightSource.OWNER, null, null, 3L))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-KH-04");
        verifyNoInteractions(pets, weights);
    }

    @Test
    void largestValidWeightIsAccepted() {
        when(pets.findByIdForShare(7L)).thenReturn(Optional.of(alivePet()));
        when(weights.saveAndFlush(any())).thenReturn(mock(WeightRecord.class));

        service.recordWeight(7L, new BigDecimal("9999.99"), WeightSource.OWNER, null, null, 3L);

        verify(weights).saveAndFlush(any());
    }

    @Test
    void missingPetIs404() {
        when(pets.findByIdForShare(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordWeight(7L, BigDecimal.ONE, WeightSource.OWNER, null, null, 3L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(weights, never()).saveAndFlush(any());
    }

    @Test
    void deceasedPetIsReadOnly() {
        Pet pet = alivePet();
        pet.markDeceased(java.time.LocalDate.of(2026, 10, 1));
        when(pets.findByIdForShare(7L)).thenReturn(Optional.of(pet));

        assertThatThrownBy(() -> service.recordWeight(7L, BigDecimal.ONE, WeightSource.VET, 9L, null, 3L))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-KH-05");
        verify(weights, never()).saveAndFlush(any());
    }

    @Test
    void sourceMustMatchTheReferences() {
        assertThatThrownBy(() -> service.recordWeight(7L, BigDecimal.ONE, WeightSource.VET, null, null, 3L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recordWeight(7L, BigDecimal.ONE, WeightSource.VET, 1L, 2L, 3L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recordWeight(7L, BigDecimal.ONE, WeightSource.INTAKE, 1L, null, 3L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recordWeight(7L, BigDecimal.ONE, WeightSource.OWNER, 1L, null, 3L))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(pets, weights);
    }

    @Test
    void joinsTheCallersTransaction() throws Exception {
        Transactional tx = PetWeightApiService.class
                .getMethod("recordWeight", Long.class, BigDecimal.class, WeightSource.class, Long.class, Long.class,
                        Long.class)
                .getAnnotation(Transactional.class);

        assertThat(tx.propagation()).isEqualTo(Propagation.MANDATORY);
    }
}
