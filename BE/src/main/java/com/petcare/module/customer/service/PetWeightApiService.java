package com.petcare.module.customer.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.api.PetApi;
import com.petcare.module.customer.api.RecordedWeight;
import com.petcare.module.customer.api.WeightSource;
import com.petcare.module.customer.entity.Pet;
import com.petcare.module.customer.entity.WeightRecord;
import com.petcare.module.customer.repository.PetRepository;
import com.petcare.module.customer.repository.WeightRecordRepository;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;

/**
 * {@link PetApi} thật (BR-KH-04, docs/adr/0041): visit ghi cân nặng khi khám (VET), boarding ghi khi nhận thú (INTAKE).
 * {@code MANDATORY}: cùng transaction với bên gọi. Khóa chia sẻ dòng thú ({@code FOR SHARE}, docs/adr/0066) trước khi kiểm đã mất: xung đột với
 * {@code NO KEY UPDATE} của đánh dấu đã mất nên không ghi cân cho thú vừa được đánh dấu ở transaction khác.
 */
@Service
public class PetWeightApiService implements PetApi {

    /** {@code weight_records.weight_kg} là NUMERIC(6,2): tối đa 9999.99. */
    static final BigDecimal MAX_WEIGHT_KG = new BigDecimal("9999.99");

    private final PetRepository pets;
    private final WeightRecordRepository weights;
    private final Clock clock;

    public PetWeightApiService(PetRepository pets, WeightRecordRepository weights, Clock clock) {
        this.pets = pets;
        this.weights = weights;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RecordedWeight recordWeight(Long petId, BigDecimal weightKg, WeightSource source, Long visitId,
            Long boardingBookingId, Long actorId) {
        requireConsistentSource(source, visitId, boardingBookingId);
        BigDecimal normalized = normalize(weightKg);
        Pet pet = pets.findByIdForShare(petId).orElseThrow(() -> new ResourceNotFoundException("thú cưng", petId));
        pet.requireAlive();
        Instant measuredAt = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        Long id = weights.saveAndFlush(WeightRecord.create(petId, normalized, source, visitId, boardingBookingId,
                actorId, measuredAt)).getId();
        return new RecordedWeight(id, normalized, measuredAt);
    }

    /** BR-KH-04: ≤ 0 bị từ chối; làm tròn 2 chữ số thập phân, vượt NUMERIC(6,2) thì từ chối thay vì để DB báo lỗi. */
    static BigDecimal normalize(BigDecimal weightKg) {
        if (weightKg == null || weightKg.signum() <= 0) {
            throw new BusinessRuleViolationException("BR-KH-04", "Cân nặng phải lớn hơn 0");
        }
        BigDecimal rounded = weightKg.setScale(2, RoundingMode.HALF_UP);
        if (rounded.signum() <= 0 || rounded.compareTo(MAX_WEIGHT_KG) > 0) {
            throw new BusinessRuleViolationException("BR-KH-04", "Cân nặng phải trong khoảng 0.01 đến 9999.99 kg");
        }
        return rounded;
    }

    /** VET gắn Visit, INTAKE gắn đặt chỗ, OWNER không gắn gì — sai là lỗi lập trình của bên gọi, không phải lỗi nghiệp vụ. */
    private static void requireConsistentSource(WeightSource source, Long visitId, Long boardingBookingId) {
        boolean valid = switch (source) {
            case VET -> visitId != null && boardingBookingId == null;
            case INTAKE -> boardingBookingId != null && visitId == null;
            case OWNER -> visitId == null && boardingBookingId == null;
        };
        if (!valid) {
            throw new IllegalArgumentException("Weight source " + source + " does not match visitId/boardingBookingId");
        }
    }
}
