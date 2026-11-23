package com.petcare.module.workforce.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.workforce.dto.ShiftRequestAuditSnapshot;
import com.petcare.module.workforce.dto.ShiftRequestResponse;
import com.petcare.module.workforce.entity.ShiftRequest;
import com.petcare.module.workforce.entity.ShiftRequestStatus;
import com.petcare.module.workforce.entity.ShiftRequestType;

/**
 * {@link ShiftRequestMapper} (workforce-v1 #9–11, docs/adr/0149 bước 6): đủ 15 trường của hợp đồng cho từng trạng thái,
 * đổi tên {@code requestType → type}, và khóa nullable luôn có mặt. Khớp tên / cấu trúc với yaml do
 * {@code OpenApiContractParityIT} ghim.
 */
class ShiftRequestMapperTest {

    private static final Instant SENT = Instant.parse("2026-11-20T02:30:00Z");
    private static final Instant CLOSED = Instant.parse("2026-11-20T03:05:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);

    private final ShiftRequestMapper mapper = Mappers.getMapper(ShiftRequestMapper.class);

    @Test
    void theResponseHasExactlyTheFifteenContractComponentsInContractOrder() {
        assertThat(ShiftRequestResponse.class.getRecordComponents()).extracting(c -> c.getName()).containsExactly(
                "shiftRequestId", "shiftAssignmentId", "requesterId", "requesterName", "workDate", "currentShiftName",
                "type", "targetShiftDefinitionId", "targetShiftName", "reason", "status", "decidedBy", "decisionNote",
                "closedAt", "createdAt");
    }

    @Test
    void aPendingLeaveHasNoTargetNoDeciderNoNoteAndNoCloseTime() {
        ShiftRequestResponse response = map(leave());

        assertThat(response.shiftRequestId()).isEqualTo(15L);
        assertThat(response.shiftAssignmentId()).isEqualTo(31L);
        assertThat(response.requesterId()).isEqualTo(12L);
        assertThat(response.requesterName()).isEqualTo("Nguyễn Văn An");
        assertThat(response.workDate()).isEqualTo(DAY);
        assertThat(response.currentShiftName()).isEqualTo("Ca sáng");
        assertThat(response.type()).isEqualTo(ShiftRequestType.LEAVE);
        assertThat(response.targetShiftDefinitionId()).isNull();
        assertThat(response.targetShiftName()).isNull();
        assertThat(response.reason()).isEqualTo("Việc gia đình");
        assertThat(response.status()).isEqualTo(ShiftRequestStatus.PENDING);
        assertThat(response.decidedBy()).isNull();
        assertThat(response.decisionNote()).isNull();
        assertThat(response.closedAt()).isNull();
        assertThat(response.createdAt()).isEqualTo(SENT);
    }

    @Test
    void aPendingSwapCarriesTheTargetIdAndName() {
        ShiftRequest swap = ShiftRequest.submit(31L, 12L, 7L, ShiftRequestType.SWAP, 8L, "Đổi ca");
        ReflectionTestUtils.setField(swap, "id", 16L);
        ReflectionTestUtils.setField(swap, "createdAt", SENT);

        ShiftRequestResponse response = mapper.toResponse(swap, "Nguyễn Văn An", DAY, "Ca sáng", "Ca chiều");

        assertThat(response.type()).isEqualTo(ShiftRequestType.SWAP);
        assertThat(response.targetShiftDefinitionId()).isEqualTo(8L);
        assertThat(response.targetShiftName()).isEqualTo("Ca chiều");
    }

    @Test
    void aWithdrawnRequestIsClosedWithoutADecider() {
        ShiftRequest request = leave();
        request.withdraw(CLOSED);

        ShiftRequestResponse response = map(request);

        assertThat(response.status()).isEqualTo(ShiftRequestStatus.WITHDRAWN);
        assertThat(response.closedAt()).isEqualTo(CLOSED);
        assertThat(response.decidedBy()).isNull();
        assertThat(response.decisionNote()).isNull();
    }

    @Test
    void aRequestRejectedByAManagerCarriesTheDeciderAndTheNote() {
        ShiftRequest request = leave();
        request.reject(5L, "Thiếu người trực", CLOSED);

        ShiftRequestResponse response = map(request);

        assertThat(response.status()).isEqualTo(ShiftRequestStatus.REJECTED);
        assertThat(response.decidedBy()).isEqualTo(5L);
        assertThat(response.decisionNote()).isEqualTo("Thiếu người trực");
        assertThat(response.closedAt()).isEqualTo(CLOSED);
    }

    @Test
    void aRequestRejectedBySystemHasANoteButNoDecider() {
        ShiftRequest request = leave();
        request.rejectBySystem("Phân ca đã bị quản lý hủy", CLOSED);

        ShiftRequestResponse response = map(request);

        assertThat(response.status()).isEqualTo(ShiftRequestStatus.REJECTED);
        assertThat(response.decidedBy()).isNull();
        assertThat(response.decisionNote()).isEqualTo("Phân ca đã bị quản lý hủy");
    }

    @Test
    void anApprovedRequestCarriesTheDeciderAndNoNote() {
        ShiftRequest request = leave();
        request.approve(5L, CLOSED);

        ShiftRequestResponse response = map(request);

        assertThat(response.status()).isEqualTo(ShiftRequestStatus.APPROVED);
        assertThat(response.decidedBy()).isEqualTo(5L);
        assertThat(response.decisionNote()).isNull();
        assertThat(response.closedAt()).isEqualTo(CLOSED);
    }

    @Test
    void theAuditSnapshotHasExactlyTheSevenAgreedKeys() {
        assertThat(ShiftRequestAuditSnapshot.class.getRecordComponents()).extracting(c -> c.getName())
                .containsExactly("shiftAssignmentId", "requesterId", "type", "targetShiftDefinitionId", "status",
                        "decidedBy", "decisionNote");
    }

    @Test
    void toSnapshotCopiesEveryColumnFromItsOwnSourceForARejectedSwap() {
        ShiftRequest swap = ShiftRequest.submit(31L, 12L, 7L, ShiftRequestType.SWAP, 8L, "Đổi ca");
        swap.reject(5L, "Thiếu người trực", CLOSED);

        ShiftRequestAuditSnapshot snapshot = mapper.toSnapshot(swap);

        assertThat(snapshot).isEqualTo(new ShiftRequestAuditSnapshot(31L, 12L, "SWAP", 8L, "REJECTED", 5L,
                "Thiếu người trực"));
    }

    @Test
    void toSnapshotOfAPendingLeaveHasNoTargetNoDeciderAndNoNote() {
        ShiftRequestAuditSnapshot snapshot = mapper.toSnapshot(leave());

        assertThat(snapshot).isEqualTo(new ShiftRequestAuditSnapshot(31L, 12L, "LEAVE", null, "PENDING", null,
                null));
    }

    private ShiftRequestResponse map(ShiftRequest request) {
        return mapper.toResponse(request, "Nguyễn Văn An", DAY, "Ca sáng", null);
    }

    private static ShiftRequest leave() {
        ShiftRequest request = ShiftRequest.submit(31L, 12L, 7L, ShiftRequestType.LEAVE, null, "Việc gia đình");
        ReflectionTestUtils.setField(request, "id", 15L);
        ReflectionTestUtils.setField(request, "createdAt", SENT);
        return request;
    }
}
