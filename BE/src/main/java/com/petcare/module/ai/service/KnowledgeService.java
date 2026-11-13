package com.petcare.module.ai.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.ai.dto.AiDocumentResponse;
import com.petcare.module.ai.dto.AiDocumentSummaryResponse;
import com.petcare.module.ai.dto.CreateAiDocumentRequest;
import com.petcare.module.ai.dto.UpdateAiDocumentRequest;
import com.petcare.module.ai.entity.AiDocument;
import com.petcare.module.ai.entity.DocumentSource;
import com.petcare.module.ai.entity.DocumentSpecies;
import com.petcare.module.ai.entity.DocumentStatus;
import com.petcare.module.ai.entity.DocumentTopic;
import com.petcare.module.ai.fsm.AiDocumentTransitionHandler;
import com.petcare.module.ai.repository.AiChunkRepository;
import com.petcare.module.ai.repository.AiDocumentRepository;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;

import jakarta.persistence.criteria.Predicate;

/**
 * UC93 — quản lý tài liệu tri thức (ai-v1 #5–#11, FSM #13; BR-AI-13, 14, 16, 17; docs/adr/0130). Chỉ SUPER_MANAGER
 * (kiểm ở controller). Thứ tự của mọi lệnh ghi: khóa dòng ({@code FOR NO KEY UPDATE}, đọc sau khi khóa) → guard nghiệp
 * vụ (400) → {@link AiDocumentTransitionHandler} (409) → ghi → audit cùng transaction → phát {@link AiIndexRequested}
 * (chuyển cho ST22 chỉ sau commit). Hết thảy cùng khóa dòng với bước hoán đổi của ST22, nên một lượt nạp phiên bản cũ
 * không bao giờ ghi đè tài liệu vừa được sửa.
 */
@Service
public class KnowledgeService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String RULE_DOCUMENT = "BR-AI-14";
    private static final String RULE_SOURCE = "BR-AI-13";

    private final AiDocumentRepository docs;
    private final AiChunkRepository chunks;
    private final AiDocumentTransitionHandler fsm;
    private final AuditRecorder auditRecorder;
    private final BranchScope scope;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public KnowledgeService(AiDocumentRepository docs, AiChunkRepository chunks, AiDocumentTransitionHandler fsm,
            AuditRecorder auditRecorder, BranchScope scope, Clock clock, ApplicationEventPublisher events) {
        this.docs = docs;
        this.chunks = chunks;
        this.fsm = fsm;
        this.auditRecorder = auditRecorder;
        this.scope = scope;
        this.clock = clock;
        this.events = events;
    }

    // ---------------------------------------------------------------- đọc

    /** ai-v1 #5. Mới sửa trước ({@code updatedAt} giảm dần, trùng thì {@code id} giảm dần); {@code size} kẹp 1–100. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<AiDocumentSummaryResponse> list(DocumentStatus status, DocumentTopic topic,
            DocumentSpecies species, String q, int page, int size) {
        Specification<AiDocument> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (topic != null) {
                predicates.add(cb.equal(root.get("topic"), topic));
            }
            if (species != null) {
                predicates.add(cb.equal(root.get("species"), species));
            }
            if (q != null && !q.isBlank()) {
                String pattern = "%" + escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.like(cb.lower(root.get("title")), pattern, '\\'));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<AiDocument> result = docs.findAll(spec, PageRequest.of(Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE), Sort.by(Sort.Direction.DESC, "updatedAt", "id")));
        return PageResponse.of(new PageImpl<>(result.getContent().stream().map(AiDocumentSummaryResponse::from).toList(),
                result.getPageable(), result.getTotalElements()));
    }

    /** ai-v1 #7. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AiDocumentResponse get(Long documentId) {
        return respond(docs.findById(documentId).orElseThrow(() -> notFound(documentId)));
    }

    // ---------------------------------------------------------------- ghi

    /** ai-v1 #6, Tài liệu#1: luôn là nháp soạn tay. */
    @Transactional
    public AiDocumentResponse create(CreateAiDocumentRequest request) {
        Long actor = scope.current().accountId();
        String title = trim(request.title());
        requireTitle(title);
        requireContentLength(request.content());
        // V34 bắt source_note NOT NULL với MANUAL; nháp chưa có nguồn lưu chuỗi rỗng, publish kiểm lại (docs/adr/0130).
        AiDocument doc = AiDocument.manualDraft(title, request.topic(), request.species(), request.content(),
                request.sourceNote() == null ? "" : request.sourceNote().trim(), blankToNull(request.reviewerName()),
                request.reviewedOn(), actor);
        docs.saveAndFlush(doc);
        audit(AiAuditActions.DOCUMENT_CREATED, doc, null);
        return respond(doc);
    }

    /** ai-v1 #8, Tài liệu#3 (hoặc sửa nháp). Trường vắng hoặc null = giữ nguyên. */
    @Transactional
    public AiDocumentResponse update(Long documentId, UpdateAiDocumentRequest request) {
        AiDocument doc = lock(documentId);
        requireHandEditable(doc, "Tài liệu phác đồ sinh từ danh mục, không sửa tay");
        String title = request.title() == null ? doc.getTitle() : trim(request.title());
        String content = request.content() == null ? doc.getContent() : request.content();
        requireTitle(title);
        requireContentLength(content);
        boolean published = doc.getStatus() == DocumentStatus.PUBLISHED;
        boolean contentChanged = !content.equals(doc.getContent());
        boolean textChanged = contentChanged || !title.equals(doc.getTitle());
        // BR-AI-14 chỉ đòi người và ngày rà soát mới khi sửa NỘI DUNG; đổi tiêu đề vẫn tăng version (BR-AI-15) mà giữ người cũ.
        if (published && contentChanged && (isBlank(request.reviewerName()) || request.reviewedOn() == null)) {
            throw new BusinessRuleViolationException(RULE_DOCUMENT,
                    "Sửa nội dung tài liệu đã xuất bản phải kèm người và ngày rà soát mới");
        }
        if (doc.getStatus() != DocumentStatus.DRAFT) {
            fsm.validateTransition(doc.getStatus(), doc.getStatus());   // #3; ARCHIVED không có cạnh tự lặp → 409
        }

        Map<String, Object> before = snapshot(doc);
        doc.edit(title, request.topic() == null ? doc.getTopic() : request.topic(),
                request.species() == null ? doc.getSpecies() : request.species(), content,
                request.sourceNote() == null ? doc.getSourceNote() : request.sourceNote().trim(),
                request.reviewerName() == null ? doc.getReviewerName() : blankToNull(request.reviewerName()),
                request.reviewedOn() == null ? doc.getReviewedOn() : request.reviewedOn(), scope.current().accountId());
        if (published) {
            requirePublishable(doc);
            if (textChanged) {
                doc.bumpVersion();
            }
        }
        docs.saveAndFlush(doc);
        audit(AiAuditActions.DOCUMENT_UPDATED, doc, before);
        if (published && textChanged) {
            events.publishEvent(new AiIndexRequested(documentId));
        }
        return respond(doc);
    }

    /** ai-v1 #9, Tài liệu#2 (từ nháp) và #5 (từ lưu trữ). */
    @Transactional
    public AiDocumentResponse publish(Long documentId) {
        AiDocument doc = lock(documentId);
        requireHandEditable(doc, "Tài liệu phác đồ do hệ thống quản lý, không xuất bản tay");
        requirePublishable(doc);
        if (doc.getStatus() == DocumentStatus.PUBLISHED) {
            // PUBLISHED → PUBLISHED có trong bảng (#3, #7) nhưng là sửa / ST22, không phải sự kiện xuất bản.
            throw new InvalidStateTransitionException(AiDocumentTransitionHandler.FSM_NAME, DocumentStatus.PUBLISHED.name(),
                    DocumentStatus.PUBLISHED.name());
        }
        fsm.validateTransition(doc.getStatus(), DocumentStatus.PUBLISHED);

        Map<String, Object> before = snapshot(doc);
        doc.publish(Instant.now(clock), scope.current().accountId());
        docs.saveAndFlush(doc);
        audit(AiAuditActions.DOCUMENT_PUBLISHED, doc, before);
        events.publishEvent(new AiIndexRequested(documentId));
        return respond(doc);
    }

    /** ai-v1 #10, Tài liệu#4 (từ xuất bản) và #6 (từ nháp). Có hiệu lực ngay khi commit: truy hồi lọc {@code status}. */
    @Transactional
    public AiDocumentResponse archive(Long documentId) {
        AiDocument doc = lock(documentId);
        fsm.validateTransition(doc.getStatus(), DocumentStatus.ARCHIVED);

        Map<String, Object> before = snapshot(doc);
        doc.archive(scope.current().accountId());
        docs.saveAndFlush(doc);
        audit(AiAuditActions.DOCUMENT_ARCHIVED, doc, before);
        return respond(doc);
    }

    /** ai-v1 #11: đặt {@code PENDING} và xếp ST22; idempotent. Đoạn đang phục vụ giữ nguyên tới khi hoán đổi. */
    @Transactional
    public AiDocumentResponse reindex(Long documentId) {
        AiDocument doc = lock(documentId);
        if (doc.getStatus() != DocumentStatus.PUBLISHED) {
            throw new InvalidStateTransitionException(AiDocumentTransitionHandler.FSM_NAME, doc.getStatus().name(),
                    "nạp lại chỉ mục");
        }
        Map<String, Object> before = snapshot(doc);
        doc.requestIndex();
        docs.saveAndFlush(doc);
        audit(AiAuditActions.DOCUMENT_REINDEX_REQUESTED, doc, before);
        events.publishEvent(new AiIndexRequested(documentId));
        return respond(doc);
    }

    // ---------------------------------------------------------------- guard và tiện ích

    private AiDocument lock(Long documentId) {
        return docs.findByIdForUpdate(documentId).orElseThrow(() -> notFound(documentId));
    }

    private static ResourceNotFoundException notFound(Long documentId) {
        return new ResourceNotFoundException("Tài liệu tri thức", documentId);
    }

    /** Phác đồ sinh từ danh mục chỉ lưu trữ được (#4); không sửa hay xuất bản tay (BR-AI-13). */
    private static void requireHandEditable(AiDocument doc, String message) {
        if (doc.getSourceType() == DocumentSource.CATALOG_PROTOCOL) {
            throw new BusinessRuleViolationException(RULE_SOURCE, message);
        }
    }

    /** Hai lớp kiểm soát của BR-AI-14 cộng nội dung không rỗng; nêu mọi trường thiếu. */
    private static void requirePublishable(AiDocument doc) {
        List<String> missing = new ArrayList<>();
        if (isBlank(doc.getSourceNote())) {
            missing.add("sourceNote (lớp 1 — nguồn)");
        }
        if (isBlank(doc.getReviewerName())) {
            missing.add("reviewerName (lớp 2 — rà soát)");
        }
        if (doc.getReviewedOn() == null) {
            missing.add("reviewedOn (lớp 2 — rà soát)");
        }
        if (isBlank(doc.getContent())) {
            missing.add("content (nội dung)");
        }
        if (!missing.isEmpty()) {
            throw new BusinessRuleViolationException(RULE_DOCUMENT,
                    "Thiếu " + String.join(", ", missing) + " (bắt buộc với tài liệu xuất bản)");
        }
    }

    private static void requireTitle(String title) {
        if (isBlank(title)) {
            throw new BusinessRuleViolationException(RULE_DOCUMENT, "Tiêu đề không được rỗng");
        }
        if (title.length() > AiDocument.TITLE_MAX_LENGTH) {
            throw new BusinessRuleViolationException(RULE_DOCUMENT,
                    "Tiêu đề tối đa " + AiDocument.TITLE_MAX_LENGTH + " ký tự");
        }
    }

    private static void requireContentLength(String content) {
        if (content != null && content.length() > AiDocument.CONTENT_MAX_LENGTH) {
            throw new BusinessRuleViolationException(RULE_DOCUMENT, "Nội dung tối đa 20 000 ký tự");
        }
    }

    private AiDocumentResponse respond(AiDocument doc) {
        long chunkCount = doc.getIndexedVersion() == 0 ? 0
                : chunks.countByDocumentIdAndDocVersion(doc.getId(), doc.getIndexedVersion());
        return AiDocumentResponse.from(doc, chunkCount);
    }

    /**
     * Ảnh chụp để audit (BR-AI-17): trường nghiệp vụ, độ dài và SHA-256 của nội dung, không chép cả nội dung (tối đa 20 000
     * ký tự mỗi lần sửa). Luôn là Map, không bao giờ là entity (docs/adr/0001).
     */
    static Map<String, Object> snapshot(AiDocument doc) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", doc.getTitle());
        snapshot.put("sourceType", doc.getSourceType().name());
        snapshot.put("topic", doc.getTopic().name());
        snapshot.put("species", doc.getSpecies().name());
        snapshot.put("status", doc.getStatus().name());
        snapshot.put("version", doc.getVersion());
        snapshot.put("indexStatus", doc.getIndexStatus().name());
        snapshot.put("sourceNote", doc.getSourceNote());
        snapshot.put("reviewerName", doc.getReviewerName());
        LocalDate reviewedOn = doc.getReviewedOn();
        snapshot.put("reviewedOn", reviewedOn == null ? null : reviewedOn.toString());
        snapshot.put("contentLength", doc.getContent().length());
        snapshot.put("contentSha256", Sha256.hex(doc.getContent()));
        return snapshot;
    }

    private void audit(String action, AiDocument doc, Map<String, Object> before) {
        AuditEntry entry = AuditEntry.of(action).entity(AiAuditActions.ENTITY_TYPE, doc.getId()).after(snapshot(doc));
        auditRecorder.record(before == null ? entry : entry.before(before));
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private static String trim(String text) {
        return text == null ? null : text.trim();
    }

    private static String blankToNull(String text) {
        return isBlank(text) ? null : text.trim();
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
