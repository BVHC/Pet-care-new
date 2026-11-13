package com.petcare.module.ai.service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.petcare.module.ai.entity.AiDocument;
import com.petcare.module.ai.entity.DocumentSpecies;
import com.petcare.module.ai.repository.AiDocumentRepository;
import com.petcare.module.ai.repository.AiDocumentRepository.PublishedProtocol;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProtocolDose;
import com.petcare.module.customer.api.Species;

import lombok.extern.slf4j.Slf4j;

/**
 * ST22 — đồng bộ tài liệu {@code CATALOG_PROTOCOL} với phác đồ tiêm đang hoạt động (07 §5.3 bước 2, BR-AI-13): mỗi loại
 * vaccine một tài liệu, dựng từ danh mục qua {@link CatalogQueryApi} (I-3), không nhập entity của catalog. Không
 * {@code @Transactional}: mỗi loại vaccine một transaction ở {@link CatalogProtocolWriter}, nên một loại lỗi chỉ bị log
 * ({@code CATALOG_PROTOCOL_SYNC_FAILED}) và lượt sau thử lại, không chặn các loại khác. Loại vaccine không còn dòng phác
 * đồ nào đang dùng (ở loài nào cũng vậy) thì tài liệu {@code PUBLISHED} của nó bị lưu trữ ({@code
 * CATALOG_PROTOCOL_ARCHIVE_FAILED} khi lỗi, cùng cách cô lập); tài liệu {@code MANUAL} không bao giờ bị đụng. Phác đồ có
 * lại thì tài liệu do chính ST22 lưu trữ được xuất bản lại ({@link CatalogProtocolWriter#upsert}); tài liệu do người
 * quản lý lưu trữ không bao giờ tự quay lại (docs/adr/0130 #4, #5).
 */
@Slf4j
@Service
public class CatalogProtocolSync {

    private final CatalogQueryApi catalog;
    private final CatalogProtocolWriter writer;
    private final AiDocumentRepository docs;

    public CatalogProtocolSync(CatalogQueryApi catalog, CatalogProtocolWriter writer, AiDocumentRepository docs) {
        this.catalog = catalog;
        this.writer = writer;
        this.docs = docs;
    }

    /** Trả số tài liệu đã tạo mới, đổi nội dung, xuất bản lại hoặc lưu trữ. */
    public int sync() {
        int changed = 0;
        // Liệt kê TRƯỚC khi đọc phác đồ: tài liệu do một lượt khác (bản BE khác) tạo trong lúc này không có trong danh sách,
        // nên không bị coi nhầm là của loại đã hết phác đồ (lưu trữ không tự quay lại được).
        List<PublishedProtocol> published = docs.findPublishedProtocols();
        Set<Long> activeTypes = new HashSet<>();
        for (Species species : Species.values()) {
            Map<Long, List<ProtocolDose>> byType = catalog.listActiveProtocols(species).stream()
                    .collect(Collectors.groupingBy(ProtocolDose::vaccineTypeId, LinkedHashMap::new, Collectors.toList()));
            activeTypes.addAll(byType.keySet());
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
        return changed + archiveRetired(published, activeTypes);
    }

    /** Chạy sau khi đã đọc phác đồ của mọi loài: một lần đọc danh mục lỗi làm {@link #sync()} ném trước bước này. */
    private int archiveRetired(List<PublishedProtocol> published, Set<Long> activeTypes) {
        int archived = 0;
        for (PublishedProtocol document : published) {
            if (activeTypes.contains(document.getVaccineTypeId())) {
                continue;
            }
            try {
                if (writer.archiveRetired(document.getId())) {
                    archived++;
                }
            } catch (RuntimeException e) {
                log.error("CATALOG_PROTOCOL_ARCHIVE_FAILED documentId={}", document.getId(), e);
            }
        }
        return archived;
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
