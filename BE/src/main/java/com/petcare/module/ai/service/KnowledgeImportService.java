package com.petcare.module.ai.service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.ai.dto.AiDocumentResponse;
import com.petcare.module.ai.dto.CreateAiDocumentRequest;
import com.petcare.module.ai.dto.ImportAiDocumentRequest;
import com.petcare.module.ai.dto.ImportAiDocumentResponse;
import com.petcare.module.ai.entity.AiDocument;
import com.petcare.module.ai.service.DocumentFileReader.Extracted;
import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * ai-v1 #14 (BR-AI-29, docs/adr/0134 D1): file → một hoặc nhiều tài liệu {@code MANUAL} {@code DRAFT}, qua đúng đường
 * {@link KnowledgeService#create} (khóa, kiểm độ dài, audit {@code AI_DOCUMENT_CREATED}). Một transaction cho cả file:
 * lỗi ở phần nào thì không phần nào được tạo. Không xuất bản, không người rà soát: tài liệu chưa vào truy hồi cho tới khi
 * SUPER_MANAGER điền nguồn, người và ngày rà soát rồi xuất bản (BR-AI-14).
 *
 * <p>shortcut: chưa chống nạp trùng (ADR-0134 D1 dự kiến {@code external_id} ở V52); nạp lại cùng file tạo thêm nháp.
 */
@Service
public class KnowledgeImportService {

    private static final int TITLE_STEM_MAX = AiDocument.TITLE_MAX_LENGTH - 20;   // chừa chỗ cho " (phần 12/12)"

    private final KnowledgeService knowledge;

    public KnowledgeImportService(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @Transactional
    public ImportAiDocumentResponse importFile(ImportAiDocumentRequest request) {
        byte[] bytes = decode(request.contentBase64());
        Extracted extracted = DocumentFileReader.read(request.fileName(), bytes);
        List<String> parts = DocumentFileReader.split(extracted.text());
        String fileStem = titleStem(request.fileName());
        String stem = extracted.titleHint() != null && !extracted.titleHint().isBlank() ? clip(extracted.titleHint()) : fileStem;
        String note = request.sourceNote() == null || request.sourceNote().isBlank()
                ? "Nạp từ file " + fileStem + " (" + extracted.kind() + ", " + Math.max(1, bytes.length / 1024) + " kB)"
                : request.sourceNote().trim();
        List<AiDocumentResponse> created = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            String title = parts.size() == 1 ? stem : stem + " (phần " + (i + 1) + "/" + parts.size() + ")";
            created.add(knowledge.create(new CreateAiDocumentRequest(title, request.topic(), request.species(),
                    parts.get(i), note, null, null)));
        }
        return new ImportAiDocumentResponse(extracted.kind(), created);
    }

    private static byte[] decode(String base64) {
        // base64 phình ≈ 4/3: chặn trước khi giải mã để không cấp phát vô ích cho request rõ ràng quá cỡ
        if (base64.length() > (long) DocumentFileReader.MAX_FILE_BYTES * 4 / 3 + 16) {
            throw new BusinessRuleViolationException(DocumentFileReader.RULE,
                    "File vượt quá tối đa " + DocumentFileReader.MAX_FILE_BYTES / 1024 + " kB");
        }
        try {
            return Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleViolationException(DocumentFileReader.RULE, "Nội dung file không phải base64 hợp lệ");
        }
    }

    private static String clip(String title) {
        String t = title.strip();
        return t.length() > TITLE_STEM_MAX ? t.substring(0, TITLE_STEM_MAX) : t;
    }

    private static String titleStem(String fileName) {
        String name = fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        String stem = (dot > 0 ? name.substring(0, dot) : name).strip();
        if (stem.isEmpty()) {
            throw new BusinessRuleViolationException(DocumentFileReader.RULE, "Tên file không hợp lệ");
        }
        return stem.length() > TITLE_STEM_MAX ? stem.substring(0, TITLE_STEM_MAX) : stem;
    }
}
