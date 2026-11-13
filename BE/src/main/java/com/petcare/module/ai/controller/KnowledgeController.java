package com.petcare.module.ai.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.ai.dto.AiDocumentResponse;
import com.petcare.module.ai.dto.AiDocumentSummaryResponse;
import com.petcare.module.ai.dto.CreateAiDocumentRequest;
import com.petcare.module.ai.dto.ImportAiDocumentRequest;
import com.petcare.module.ai.dto.ImportAiDocumentResponse;
import com.petcare.module.ai.dto.UpdateAiDocumentRequest;
import com.petcare.module.ai.entity.DocumentSpecies;
import com.petcare.module.ai.entity.DocumentStatus;
import com.petcare.module.ai.entity.DocumentTopic;
import com.petcare.module.ai.service.KnowledgeImportService;
import com.petcare.module.ai.service.KnowledgeService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * Quản lý tài liệu tri thức của chatbot — ai-v1 #5–#11 (UC93, FSM #13; docs/adr/0130). Chỉ SUPER_MANAGER. Không chứa
 * logic và không biết mã rule: BR-AI-13 / 14 do {@link KnowledgeService} kiểm, nên request cố ý không ràng buộc độ dài
 * tiêu đề và nội dung. Chat (#1–#2), tra lịch tiêm (#3), gợi ý (#4) và hồ sơ gợi ý (#12–#13) thuộc các việc sau.
 */
@RestController
@RequestMapping("/api/ai/documents")
public class KnowledgeController {

    static final String SUPER_MANAGER = "hasRole('SUPER_MANAGER')";

    private final KnowledgeService knowledge;
    private final KnowledgeImportService importer;

    public KnowledgeController(KnowledgeService knowledge, KnowledgeImportService importer) {
        this.knowledge = knowledge;
        this.importer = importer;
    }

    /** ai-v1 #5. */
    @Operation(operationId = "listAiDocuments", tags = "Knowledge", summary = "Danh sách tài liệu tri thức",
            description = "UC93 · BR-AI-17. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: —. Mới sửa trước "
                + "(`updatedAt` giảm dần, trùng thì `documentId` giảm dần).",
            parameters = {
                    @Parameter(name = "status", in = ParameterIn.QUERY, example = "PUBLISHED"),
                    @Parameter(name = "topic", in = ParameterIn.QUERY, example = "VACCINATION"),
                    @Parameter(name = "species", in = ParameterIn.QUERY, example = "DOG"),
                    @Parameter(name = "q", in = ParameterIn.QUERY, example = "chó con",
                            description = "Tìm theo tiêu đề"),
                    @Parameter(name = "page", in = ParameterIn.QUERY, example = "0",
                            description = "Số trang, bắt đầu từ 0"),
                    @Parameter(name = "size", in = ParameterIn.QUERY, example = "20")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @GetMapping
    @PreAuthorize(SUPER_MANAGER)
    public ApiResponse<PageResponse<AiDocumentSummaryResponse>> listAiDocuments(
            @RequestParam(required = false) DocumentStatus status,
            @RequestParam(required = false) DocumentTopic topic,
            @RequestParam(required = false) DocumentSpecies species,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(knowledge.list(status, topic, species, q, page, size));
    }

    /** ai-v1 #6, Tài liệu#1. */
    @Operation(operationId = "createAiDocument", tags = "Knowledge", summary = "Tạo tài liệu tri thức (nháp)",
            description = "UC93 · BR-AI-14, 17. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: — → DRAFT "
                + "(Tài liệu#1).")
    @ApiErrorDoc(status = 400, description = "`BR-AI-14` tiêu đề rỗng / quá 200 ký tự · nội dung quá 20 000 ký tự")
    @ApiErrorDoc(status = 403)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(SUPER_MANAGER)
    public ApiResponse<AiDocumentResponse> createAiDocument(@Valid @RequestBody CreateAiDocumentRequest request) {
        return ApiResponse.created(knowledge.create(request), "Đã tạo tài liệu tri thức");
    }

    /** ai-v1 #7. */
    @Operation(operationId = "getAiDocument", tags = "Knowledge", summary = "Chi tiết tài liệu tri thức",
            description = "UC93 · BR-AI-17. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: —.",
            parameters = {@Parameter(name = "documentId", in = ParameterIn.PATH, example = "4")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @GetMapping("/{documentId}")
    @PreAuthorize(SUPER_MANAGER)
    public ApiResponse<AiDocumentResponse> getAiDocument(@PathVariable Long documentId) {
        return ApiResponse.ok(knowledge.get(documentId));
    }

    /** ai-v1 #8, Tài liệu#3. */
    @Operation(operationId = "updateAiDocument", tags = "Knowledge", summary = "Sửa tài liệu tri thức",
            description = "UC93 · BR-AI-14, 16, 17. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: PUBLISHED "
                + "→ PUBLISHED (Tài liệu#3) hoặc sửa nháp. Sửa tài liệu `PUBLISHED` tăng `version`; ST22 dựng đoạn mới "
                + "rồi hoán đổi trong một transaction, đoạn cũ phục vụ tới lúc đó (BR-AI-16).",
            parameters = {@Parameter(name = "documentId", in = ParameterIn.PATH, example = "4")})
    @ApiErrorDoc(status = 400, description = "`BR-AI-14` nội dung quá dài · tài liệu `CATALOG_PROTOCOL` không sửa tay "
            + "(BR-AI-13)")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Tài liệu `ARCHIVED` không sửa được")
    @PatchMapping("/{documentId}")
    @PreAuthorize(SUPER_MANAGER)
    public ApiResponse<AiDocumentResponse> updateAiDocument(@PathVariable Long documentId,
            @Valid @RequestBody UpdateAiDocumentRequest request) {
        return ApiResponse.ok(knowledge.update(documentId, request), "Đã cập nhật tài liệu tri thức");
    }

    /** ai-v1 #9, Tài liệu#2 và #5. */
    @Operation(operationId = "publishAiDocument", tags = "Knowledge", summary = "Xuất bản tài liệu",
            description = "UC93 · BR-AI-14, 16, 17. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: DRAFT / "
                + "ARCHIVED → PUBLISHED (Tài liệu#2, #5). `indexStatus` về `PENDING`; ST22 nạp chỉ mục sau commit.",
            parameters = {@Parameter(name = "documentId", in = ParameterIn.PATH, example = "4")})
    @ApiErrorDoc(status = 400, description = "`BR-AI-14` thiếu `sourceNote` (lớp 1) hoặc `reviewerName` / `reviewedOn` "
            + "(lớp 2) với tài liệu `MANUAL` · nội dung rỗng")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "`INVALID_STATE_TRANSITION` trạng thái hiện tại không cho phép (03 #13)")
    @PostMapping("/{documentId}/publish")
    @PreAuthorize(SUPER_MANAGER)
    public ApiResponse<AiDocumentResponse> publishAiDocument(@PathVariable Long documentId) {
        return ApiResponse.ok(knowledge.publish(documentId), "Đã xuất bản tài liệu tri thức");
    }

    /** ai-v1 #10, Tài liệu#4 và #6. */
    @Operation(operationId = "archiveAiDocument", tags = "Knowledge", summary = "Lưu trữ tài liệu",
            description = "UC93 · BR-AI-16, 17. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: PUBLISHED / "
                + "DRAFT → ARCHIVED (Tài liệu#4, #6). Có hiệu lực ngay khi commit: truy hồi lọc `status` trong SQL "
                + "(BR-AI-16).",
            parameters = {@Parameter(name = "documentId", in = ParameterIn.PATH, example = "4")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "`INVALID_STATE_TRANSITION` trạng thái hiện tại không cho phép (03 #13)")
    @PostMapping("/{documentId}/archive")
    @PreAuthorize(SUPER_MANAGER)
    public ApiResponse<AiDocumentResponse> archiveAiDocument(@PathVariable Long documentId) {
        return ApiResponse.ok(knowledge.archive(documentId), "Đã lưu trữ tài liệu tri thức");
    }

    /** ai-v1 #11. */
    @Operation(operationId = "reindexAiDocument", tags = "Knowledge", summary = "Nạp lại chỉ mục của tài liệu",
            description = "UC93 · BR-AI-15, 16, 17. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: —. Dùng để "
                + "thử lại khi `indexStatus = FAILED`. Trả ngay với `indexStatus = PENDING`; ST22 làm nền.",
            parameters = {@Parameter(name = "documentId", in = ParameterIn.PATH, example = "4")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Tài liệu không `PUBLISHED` nên không có gì để nạp (03 #13)")
    @PostMapping("/{documentId}/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(SUPER_MANAGER)
    public ApiResponse<AiDocumentResponse> reindexAiDocument(@PathVariable Long documentId) {
        return ApiResponse.accepted(knowledge.reindex(documentId), "Đã xếp hàng nạp lại chỉ mục");
    }

    /** ai-v1 #14, Tài liệu#1 từ file. */
    @Operation(operationId = "importAiDocument", tags = "Knowledge", summary = "Nạp tài liệu tri thức từ file",
            description = "UC93 · BR-AI-14, 17, 29. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: — → DRAFT "
                + "(Tài liệu#1). Nhận `.md`, `.txt`, `.docx`, `.pdf` (base64 trong JSON, tối đa 2 MB); tạo một hoặc nhiều "
                + "nháp `MANUAL`, không xuất bản. PDF scan không có lớp chữ bị từ chối. Xuất bản đi #9 với hai lớp BR-AI-14.")
    @ApiErrorDoc(status = 400, description = "`BR-AI-29` kiểu file không hỗ trợ, nội dung không khớp phần mở rộng, quá cỡ, "
            + "rỗng, hỏng, PDF không có chữ · `BR-AI-14` tiêu đề rỗng / quá 200 ký tự")
    @ApiErrorDoc(status = 403)
    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(SUPER_MANAGER)
    public ApiResponse<ImportAiDocumentResponse> importAiDocument(@Valid @RequestBody ImportAiDocumentRequest request) {
        return ApiResponse.created(importer.importFile(request), "Đã nạp file thành tài liệu nháp");
    }
}
