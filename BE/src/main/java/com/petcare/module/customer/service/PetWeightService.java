package com.petcare.module.customer.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.api.WeightSource;
import com.petcare.module.customer.dto.AddWeightRequest;
import com.petcare.module.customer.dto.WeightRecordResponse;
import com.petcare.module.customer.entity.Pet;
import com.petcare.module.customer.entity.WeightRecord;
import com.petcare.module.customer.mapper.PetMapper;
import com.petcare.module.customer.repository.PetRepository;
import com.petcare.module.customer.repository.WeightRecordRepository;
import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * UC24, UC25 — lịch sử cân nặng và chủ tự khai (customer-v1 #20, #21; BR-KH-04, 05, 07; docs/adr/0041). Cân do VET /
 * người nhận thú ghi đi qua {@code PetApi.recordWeight} ({@code PetWeightApiService}); ở đây chỉ có nguồn {@code OWNER}.
 * Không audit.
 * <ul>
 *   <li>Đọc: nhân viên mọi chi nhánh; khách chỉ thú của mình (người khác / chủ cũ → 404); khách còn cờ chờ liên kết →
 *       400 BR-TK-19. Mới nhất trước.</li>
 *   <li>Ghi: khóa dòng thú → chủ (404) → thú đã mất (BR-KH-05) → cân ≤ 0 hoặc ngoài NUMERIC(6,2) hoặc {@code measuredAt}
 *       ở tương lai (BR-KH-04). Khóa dòng thú nên không ghi cân cho thú vừa được đánh dấu đã mất ở transaction khác.</li>
 * </ul>
 */
@Service
public class PetWeightService {

    /** Sai lệch đồng hồ tối đa giữa trình duyệt và máy chủ mà {@code measuredAt} còn được chấp nhận. */
    static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private final PetRepository pets;
    private final WeightRecordRepository weights;
    private final PetAccess access;
    private final PetMapper mapper;
    private final Clock clock;

    public PetWeightService(PetRepository pets, WeightRecordRepository weights, PetAccess access, PetMapper mapper,
            Clock clock) {
        this.pets = pets;
        this.weights = weights;
        this.access = access;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<WeightRecordResponse> listWeights(Long petId) {
        PetAccess.Viewer viewer = access.viewer();
        Pet pet = pets.findById(petId).orElseThrow(() -> PetAccess.notFound(petId));
        access.requireVisible(viewer, pet);
        return mapper.toWeightResponses(weights.findByPet(petId));
    }

    @Transactional
    public WeightRecordResponse addOwnerWeight(Long petId, AddWeightRequest request) {
        PetAccess.Viewer viewer = access.viewer();
        Pet pet = pets.findByIdForUpdate(petId).orElseThrow(() -> PetAccess.notFound(petId));
        access.requireVisible(viewer, pet);
        pet.requireAlive();

        var weightKg = PetWeightApiService.normalize(request.weightKg());
        Instant now = Instant.now(clock);
        Instant measuredAt = request.measuredAt() != null ? request.measuredAt() : now;
        if (measuredAt.isAfter(now.plus(CLOCK_SKEW))) {
            throw new BusinessRuleViolationException("BR-KH-04", "Thời điểm cân không được ở tương lai");
        }
        WeightRecord saved = weights.saveAndFlush(WeightRecord.create(petId, weightKg, WeightSource.OWNER, null, null,
                access.current().accountId(), measuredAt));
        return mapper.toWeightResponse(saved);
    }
}
