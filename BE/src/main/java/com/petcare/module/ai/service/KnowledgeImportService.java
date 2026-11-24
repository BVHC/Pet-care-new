package com.petcare.module.ai.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.springframework.stereotype.Service;

import com.petcare.module.ai.dto.CreateAiDocumentRequest;
import com.petcare.module.ai.dto.ImportAiDocumentRequest;
import com.petcare.module.ai.dto.ImportAiDocumentResponse;
import com.petcare.module.ai.entity.AiDocument;
import com.petcare.module.ai.service.DocumentFileReader.Extracted;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * ai-v1 #14 (BR-AI-29, BR-AI-30, docs/adr/0134 D1): file → một hoặc nhiều tài liệu {@code MANUAL} {@code DRAFT}, qua đúng đường
 * tạo của {@link KnowledgeService} (khóa, kiểm độ dài, audit {@code AI_DOCUMENT_CREATED}). Trần cỡ đọc từ [CFG]
 * {@code ai.ingest.max_file_kb} mỗi lần nạp; trích văn bản chạy trên luồng riêng có hết giờ 20 giây
 * ({@link DocumentExtractor}) và <b>không</b> nằm trong transaction (không giữ kết nối DB suốt lúc trích): giải mã, trích, tách và
 * băm xong mới mở một transaction cho cả file ({@link KnowledgeService#createFromFile}), nên lỗi hay hết giờ ở bất kỳ bước nào
 * thì không tài liệu nào được tạo. File đã nạp (cùng SHA-256 của byte file) thì 409 {@code BR-AI-30}. Không xuất bản, không
 * người rà soát: tài liệu chưa vào truy hồi cho tới khi SUPER_MANAGER điền nguồn, người và ngày rà soát rồi xuất bản (BR-AI-14).
 */
@Service
public class KnowledgeImportService {

    private static final int TITLE_STEM_MAX = AiDocument.TITLE_MAX_LENGTH - 20;   // chừa chỗ cho " (phần 12/12)"
    private static final int HASH_HEX_LENGTH = 16;

    private final KnowledgeService knowledge;
    private final DocumentExtractor extractor;
    private final SystemConfigApi config;

    public KnowledgeImportService(KnowledgeService knowledge, DocumentExtractor extractor, SystemConfigApi config) {
        this.knowledge = knowledge;
        this.extractor = extractor;
        this.config = config;
    }

    public ImportAiDocumentResponse importFile(ImportAiDocumentRequest request) {
        int maxBytes = config.getInt(ConfigKey.AI_INGEST_MAX_FILE_KB) * 1024;
        byte[] bytes = decode(request.contentBase64(), maxBytes);
        Extracted extracted = extractor.run(() -> DocumentFileReader.read(request.fileName(), bytes, maxBytes));
        List<String> parts = DocumentFileReader.split(extracted.text());
        String fileStem = titleStem(request.fileName());
        String stem = extracted.titleHint() != null && !extracted.titleHint().isBlank() ? clip(extracted.titleHint()) : fileStem;
        String note = request.sourceNote() == null || request.sourceNote().isBlank()
                ? "Nạp từ file " + fileStem + " (" + extracted.kind() + ", " + Math.max(1, bytes.length / 1024) + " kB)"
                : request.sourceNote().trim();
        List<CreateAiDocumentRequest> drafts = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            String title = parts.size() == 1 ? stem : stem + " (phần " + (i + 1) + "/" + parts.size() + ")";
            drafts.add(new CreateAiDocumentRequest(title, request.topic(), request.species(), parts.get(i), note, null, null,
                    null));
        }
        return new ImportAiDocumentResponse(extracted.kind(), knowledge.createFromFile(fileHash(bytes), drafts));
    }

    private static byte[] decode(String base64, int maxBytes) {
        // base64 phình ≈ 4/3: chặn trước khi giải mã để không cấp phát vô ích cho request rõ ràng quá cỡ
        if (base64.length() > (long) maxBytes * 4 / 3 + 16) {
            throw new BusinessRuleViolationException(DocumentFileReader.RULE, "File vượt quá tối đa " + maxBytes / 1024 + " kB");
        }
        try {
            return Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleViolationException(DocumentFileReader.RULE, "Nội dung file không phải base64 hợp lệ");
        }
    }

    /** 16 ký tự hex đầu của SHA-256 trên byte file gốc (không phải văn bản đã chuẩn hóa): cùng byte là cùng file. */
    private static String fileHash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, HASH_HEX_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 luôn có trong JDK", e);
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
