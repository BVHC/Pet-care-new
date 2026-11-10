package com.petcare.module.ai.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.petcare.module.ai.entity.AiDocument;
import com.petcare.module.ai.entity.DocumentSpecies;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProtocolDose;
import com.petcare.module.customer.api.Species;

import lombok.extern.slf4j.Slf4j;

/**
 * ST22 — đồng bộ tài liệu {@code CATALOG_PROTOCOL} với phác đồ tiêm đang hoạt động (07 §5.3 bước 2, BR-AI-13): mỗi loại
 * vaccine một tài liệu, dựng từ danh mục qua {@link CatalogQueryApi} (I-3), không nhập entity của catalog. Không
 * {@code @Transactional}: mỗi loại vaccine một transaction ở {@link CatalogProtocolWriter}, nên một loại lỗi chỉ bị log
 * ({@code CATALOG_PROTOCOL_SYNC_FAILED}) và lượt sau thử lại, không chặn các loại khác. Loại vaccine không còn dòng phác
 * đồ nào đang dùng thì không có tài liệu mới; tài liệu cũ của nó (nếu có) giữ nguyên, ghi ở docs/adr/0130 là điểm TBD.
 */
@Slf4j
@Service
public class CatalogProtocolSync {

    private final CatalogQueryApi catalog;
    private final CatalogProtocolWriter writer;

    public CatalogProtocolSync(CatalogQueryApi catalog, CatalogProtocolWriter writer) {
        this.catalog = catalog;
        this.writer = writer;
    }

    /** Trả số tài liệu đã tạo mới hoặc đổi nội dung. */
    public int sync() {
        int changed = 0;
        for (Species species : Species.values()) {
            Map<Long, List<ProtocolDose>> byType = catalog.listActiveProtocols(species).stream()
                    .collect(Collectors.groupingBy(ProtocolDose::vaccineTypeId, LinkedHashMap::new, Collectors.toList()));
            Map<Long, String> names = catalog.findVaccineTypeNames(byType.keySet());
            for (Map.Entry<Long, List<ProtocolDose>> entry : byType.entrySet()) {
                String name = names.getOrDefault(entry.getKey(), "#" + entry.getKey());
                try {
                    if (writer.upsert(entry.getKey(), DocumentSpecies.valueOf(species.name()), title(name, species),
                            render(name, species, entry.getValue()))) {
                        changed++;
                    }
                } catch (RuntimeException e) {
                    log.error("CATALOG_PROTOCOL_SYNC_FAILED vaccineTypeId={}", entry.getKey(), e);
                }
            }
        }
        return changed;
    }

    static String title(String vaccineName, Species species) {
        String title = "Phác đồ tiêm " + vaccineName + " (" + speciesName(species) + ")";
        return title.length() <= AiDocument.TITLE_MAX_LENGTH ? title : title.substring(0, AiDocument.TITLE_MAX_LENGTH);
    }

    /** Markdown tất định (cùng phác đồ thì cùng chữ, nên mã băm ổn định): mỗi mũi một mục. */
    static String render(String vaccineName, Species species, List<ProtocolDose> doses) {
        StringBuilder text = new StringBuilder("# Phác đồ tiêm ").append(vaccineName).append(" cho ")
                .append(speciesName(species)).append("\n\nPhác đồ hiện áp dụng gồm ").append(doses.size()).append(" mũi.\n");
        for (ProtocolDose dose : doses) {
            text.append("\n## Mũi ").append(dose.doseNumber()).append('\n')
                    .append("- Tuổi tối thiểu để tiêm: ").append(dose.minAgeWeeks()).append(" tuần.\n")
                    .append("- Mũi kế tiếp (hoặc tái chủng) cách mũi này ").append(dose.intervalDays()).append(" ngày.\n")
                    .append("- Bắt buộc khi gửi lưu trú: ").append(dose.requiredForBoarding() ? "có" : "không").append(".\n");
        }
        return text.toString().trim();
    }

    private static String speciesName(Species species) {
        return switch (species) {
            case DOG -> "chó";
            case CAT -> "mèo";
            case OTHER -> "loài khác";
        };
    }
}
