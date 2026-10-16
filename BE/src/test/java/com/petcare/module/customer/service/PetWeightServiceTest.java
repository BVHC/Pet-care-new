package com.petcare.module.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.customer.api.Species;
import com.petcare.module.customer.api.WeightSource;
import com.petcare.module.customer.dto.AddWeightRequest;
import com.petcare.module.customer.dto.WeightRecordResponse;
import com.petcare.module.customer.entity.Pet;
import com.petcare.module.customer.entity.PetSex;
import com.petcare.module.customer.entity.WeightRecord;
import com.petcare.module.customer.mapper.PetMapperImpl;
import com.petcare.module.customer.repository.PetRepository;
import com.petcare.module.customer.repository.WeightRecordRepository;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.SecurityPrincipal;

class PetWeightServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-16T03:00:00Z");

    private final PetRepository pets = mock(PetRepository.class);
    private final WeightRecordRepository weights = mock(WeightRecordRepository.class);
    private final PetAccess access = mock(PetAccess.class);
    private final PetWeightService service = new PetWeightService(pets, weights, access, new PetMapperImpl(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private Pet alive() {
        Pet pet = Pet.create(7L, "Mực", Species.DOG, PetSex.MALE, null, null, false, null, null, null);
        ReflectionTestUtils.setField(pet, "id", 1L);
        when(pets.findByIdForUpdate(1L)).thenReturn(Optional.of(pet));
        when(access.viewer()).thenReturn(new PetAccess.Viewer(7L));
        SecurityPrincipal principal = mock(SecurityPrincipal.class);
        when(principal.accountId()).thenReturn(42L);
        when(access.current()).thenReturn(principal);
        when(weights.saveAndFlush(any())).thenAnswer(call -> {
            WeightRecord saved = call.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 500L);
            return saved;
        });
        return pet;
    }

    @Test
    void ownerWeightIsSavedAfterLockAndOwnerCheckWithOwnerSource() {
        Pet pet = alive();

        WeightRecordResponse response = service.addOwnerWeight(1L, new AddWeightRequest(new BigDecimal("8.456"),
                null));

        assertThat(response.weightRecordId()).isEqualTo(500L);
        assertThat(response.weightKg()).isEqualByComparingTo("8.46");
        assertThat(response.source()).isEqualTo(WeightSource.OWNER);
        assertThat(response.measuredAt()).isEqualTo(NOW);
        assertThat(response.visitId()).isNull();
        assertThat(response.boardingBookingId()).isNull();
        InOrder order = inOrder(access, pets, weights);
        order.verify(access).viewer();
        order.verify(pets).findByIdForUpdate(1L);
        order.verify(access).requireVisible(any(), eq(pet));
        ArgumentCaptor<WeightRecord> saved = ArgumentCaptor.forClass(WeightRecord.class);
        order.verify(weights).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getRecordedBy()).isEqualTo(42L);
    }

    @Test
    void measuredAtInThePastIsKeptAndSmallClockSkewIsTolerated() {
        alive();
        Instant past = NOW.minusSeconds(86_400);

        assertThat(service.addOwnerWeight(1L, new AddWeightRequest(BigDecimal.ONE, past)).measuredAt())
                .isEqualTo(past);
        assertThat(service.addOwnerWeight(1L, new AddWeightRequest(BigDecimal.ONE, NOW.plusSeconds(200)))
                .measuredAt()).isEqualTo(NOW.plusSeconds(200));
    }

    @Test
    void measuredAtBeyondTheSkewIsRefused() {
        alive();

        assertThatThrownBy(() -> service.addOwnerWeight(1L, new AddWeightRequest(BigDecimal.ONE,
                NOW.plusSeconds(301)))).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("BR-KH-04");
        verify(weights, never()).saveAndFlush(any());
    }

    @Test
    void zeroOrNegativeWeightIsBrKh04() {
        alive();

        for (String kg : new String[] {"0", "-3", "0.001"}) {
            assertThatThrownBy(() -> service.addOwnerWeight(1L, new AddWeightRequest(new BigDecimal(kg), null)))
                    .as(kg).isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-KH-04");
        }
        verify(weights, never()).saveAndFlush(any());
    }

    @Test
    void deceasedPetIsRefusedBeforeTheWeightIsLookedAt() {
        Pet pet = alive();
        pet.markDeceased(LocalDate.of(2026, 9, 1));

        assertThatThrownBy(() -> service.addOwnerWeight(1L, new AddWeightRequest(BigDecimal.ZERO, null)))
                .hasMessageContaining("BR-KH-05");
        verify(weights, never()).saveAndFlush(any());
    }

    @Test
    void missingOrForeignPetIs404() {
        when(access.viewer()).thenReturn(new PetAccess.Viewer(7L));
        when(pets.findByIdForUpdate(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.addOwnerWeight(9L, new AddWeightRequest(BigDecimal.ONE, null)))
                .isInstanceOf(ResourceNotFoundException.class);

        Pet pet = alive();
        doThrow(new ResourceNotFoundException("thú cưng", 1L)).when(access).requireVisible(any(), eq(pet));
        assertThatThrownBy(() -> service.addOwnerWeight(1L, new AddWeightRequest(BigDecimal.ONE, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(weights, never()).saveAndFlush(any());
    }

    @Test
    void listIsNewestFirstAsReturnedByTheRepository() {
        when(access.viewer()).thenReturn(new PetAccess.Viewer(null));
        Pet pet = Pet.create(7L, "Mực", Species.DOG, PetSex.MALE, null, null, false, null, null, null);
        when(pets.findById(1L)).thenReturn(Optional.of(pet));
        WeightRecord newer = WeightRecord.create(1L, new BigDecimal("9.00"), WeightSource.VET, 5L, null, 3L, NOW);
        WeightRecord older = WeightRecord.create(1L, new BigDecimal("8.00"), WeightSource.OWNER, null, null, 4L,
                NOW.minusSeconds(60));
        when(weights.findByPet(1L)).thenReturn(List.of(newer, older));

        assertThat(service.listWeights(1L)).extracting(WeightRecordResponse::weightKg)
                .containsExactly(new BigDecimal("9.00"), new BigDecimal("8.00"));
        verify(access).requireVisible(any(), eq(pet));
    }
}
