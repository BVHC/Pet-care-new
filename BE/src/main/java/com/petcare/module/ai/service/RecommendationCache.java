package com.petcare.module.ai.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.petcare.module.ai.dto.RecoGroup;
import com.petcare.module.ai.dto.RecommendedItem;

/**
 * Bộ nhớ đệm gợi ý theo tiến trình (BR-AI-23, docs/adr/0133): khóa là {@code petId} + mã băm đặc điểm thú và dấu vết lịch sử,
 * giá trị là danh sách đã xếp hạng ĐỦ của từng nhóm ({@code group} và {@code limit} áp sau khi lấy, nên không nằm trong khóa).
 * Giới hạn đã biết: mỗi instance của ứng dụng giữ bộ nhớ riêng (đặc tả không đòi dùng chung), và dữ liệu không nằm trong khóa
 * (độ phổ biến, tồn kho, giá, hồ sơ UC94) có thể cũ tới hết hạn; UC94 xóa cả bộ đệm sau khi lưu hồ sơ.
 */
@Component
public class RecommendationCache {

    private static final int PRUNE_THRESHOLD = 1000;

    /** Kết quả đã xếp hạng, chưa cắt theo nhóm hay giới hạn. */
    record Ranked(Instant generatedAt, Map<RecoGroup, List<RecommendedItem>> byGroup) {
    }

    private record Entry(Ranked ranked, Instant expiresAt) {
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    Optional<Ranked> get(String key, Instant now) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!now.isBefore(entry.expiresAt())) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.ranked());
    }

    void put(String key, Ranked ranked, Instant now, Instant expiresAt) {
        if (entries.size() >= PRUNE_THRESHOLD) {
            entries.values().removeIf(entry -> !now.isBefore(entry.expiresAt()));
        }
        entries.put(key, new Entry(ranked, expiresAt));
    }

    /** Xóa hết: UC94 gọi sau khi hồ sơ thay đổi để người quản lý thấy ngay kết quả. */
    public void clear() {
        entries.clear();
    }
}
