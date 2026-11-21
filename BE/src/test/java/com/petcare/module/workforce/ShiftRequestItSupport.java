package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

/**
 * Nền chung của các IT yêu cầu nghỉ / đổi ca (workforce-v1 #9–11, docs/adr/0138 bước 6): dùng lại hạ tầng của
 * {@link ShiftAssignmentItSupport} và thêm đường dẫn, body, khóa hợp đồng, dòng outbox thông báo "có yêu cầu mới" và
 * ảnh chụp dấu vết. Mọi lý do thử nghiệm bắt đầu bằng {@link #REASON_PREFIX} để dọn outbox của quản lý đúng phần của
 * mình (không có FK tới yêu cầu; người nhận SUPER_MANAGER dự phòng có thể là tài khoản không thuộc test). Mỗi lớp con có
 * era riêng (xem docs/architecture/testing-notes.md).
 */
abstract class ShiftRequestItSupport extends ShiftAssignmentItSupport {

    protected static final String REQUESTS = "/api/me/shift-requests";
    protected static final String REASON_PREFIX = "ITM";
    protected static final String REASON = REASON_PREFIX + " Việc gia đình";
    protected static final String TEMPLATE = "SHIFT_REQUEST_SUBMITTED_APP";
    /** Đúng 15 trường của {@code ShiftRequest} trong hợp đồng workforce-v1. */
    protected static final List<String> REQUEST_KEYS = List.of("shiftRequestId", "shiftAssignmentId", "requesterId",
            "requesterName", "workDate", "currentShiftName", "type", "targetShiftDefinitionId", "targetShiftName",
            "reason", "status", "decidedBy", "decisionNote", "closedAt", "createdAt");

    /** Body JSON thô của {@code POST /me/shift-requests} (dấu nháy đơn); {@code target} / {@code type} {@code null} = bỏ. */
    protected static String requestBody(Long assignmentId, String type, Long target, String reason) {
        StringBuilder json = new StringBuilder("{");
        if (assignmentId != null) {
            json.append("'shiftAssignmentId':").append(assignmentId).append(',');
        }
        if (type != null) {
            json.append("'type':'").append(type).append("',");
        }
        if (target != null) {
            json.append("'targetShiftDefinitionId':").append(target).append(',');
        }
        if (reason != null) {
            json.append("'reason':'").append(reason).append("',");
        }
        if (json.charAt(json.length() - 1) == ',') {
            json.setLength(json.length() - 1);
        }
        return json.append('}').toString();
    }

    protected static String leaveBody(long assignmentId) {
        return requestBody(assignmentId, "LEAVE", null, REASON);
    }

    protected static String swapBody(long assignmentId, long target) {
        return requestBody(assignmentId, "SWAP", target, REASON);
    }

    protected static String withdrawPath(long requestId) {
        return REQUESTS + "/" + requestId + "/withdraw";
    }

    protected int requestCountOf(long assignmentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM shift_requests WHERE assignment_id = ?", Integer.class,
                assignmentId);
    }

    protected Map<String, Object> fullRequestRow(long requestId) {
        return jdbc.queryForMap("""
                SELECT assignment_id, requester_id, request_type, target_shift_definition_id, reason, status,
                       decided_by, decision_note, closed_at
                FROM shift_requests WHERE id = ?""", requestId);
    }

    protected long onlyRequestIdOf(long assignmentId) {
        return jdbc.queryForObject("SELECT id FROM shift_requests WHERE assignment_id = ?", Long.class, assignmentId);
    }

    protected int auditCountOfActor(long accountId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE actor_account_id = ?", Integer.class,
                accountId);
    }

    /** Dòng outbox "có yêu cầu mới" của một người nhận (quản lý), theo thứ tự chèn. */
    protected List<Map<String, Object>> submittedOutbox(long recipientId) {
        return jdbc.queryForList("""
                SELECT channel, template_code, recipient_account_id, recipient_email, payload::text AS payload
                FROM notification_outbox
                WHERE template_code = ? AND recipient_account_id = ? ORDER BY id""", TEMPLATE, recipientId);
    }

    /** Tổng số dòng outbox "có yêu cầu mới" do test này sinh ra (lý do bắt đầu bằng {@link #REASON_PREFIX}). */
    protected int submittedOutboxCount() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification_outbox
                WHERE template_code = ? AND payload->>'ly_do' LIKE ?""", Integer.class, TEMPLATE, REASON_PREFIX + "%");
    }

    /** Dọn outbox của test; gọi <b>trước</b> {@code finish()} vì {@code recipient_account_id} có khóa ngoại tới tài khoản. */
    protected void purgeSubmittedOutbox() {
        jdbc.update("DELETE FROM notification_outbox WHERE template_code = ? AND payload->>'ly_do' LIKE ?", TEMPLATE,
                REASON_PREFIX + "%");
    }

    protected String payloadField(long recipientId, String key) {
        return jdbc.queryForObject("""
                SELECT payload->>? FROM notification_outbox WHERE template_code = ? AND recipient_account_id = ?""",
                String.class, key, TEMPLATE, recipientId);
    }

    /**
     * Payload khớp mẫu thật trong DB: đủ {@code required_vars}, không khóa ngoài {@code allowed_vars}, không giá trị
     * rỗng — nếu không, worker ST20 coi là lỗi và thử lại mãi.
     */
    protected void assertTemplateFit(long recipientId) {
        List<String> keys = jdbc.queryForList("""
                SELECT jsonb_object_keys(payload) FROM notification_outbox
                WHERE template_code = ? AND recipient_account_id = ?""", String.class, TEMPLATE, recipientId);
        List<String> required = jdbc.queryForList(
                "SELECT jsonb_array_elements_text(required_vars) FROM notification_templates WHERE code = ?",
                String.class, TEMPLATE);
        List<String> allowed = jdbc.queryForList(
                "SELECT jsonb_array_elements_text(allowed_vars) FROM notification_templates WHERE code = ?",
                String.class, TEMPLATE);
        assertThat(keys).as("thiếu biến bắt buộc").containsAll(required);
        assertThat(allowed).as("có biến ngoài allowed_vars").containsAll(keys);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification_outbox o, jsonb_each_text(o.payload) AS kv
                WHERE o.template_code = ? AND o.recipient_account_id = ? AND btrim(kv.value) = ''""",
                Integer.class, TEMPLATE, recipientId)).as("có giá trị rỗng").isZero();
    }

    /**
     * Ảnh chụp mọi dấu vết của các lệnh gửi / rút trên một phân ca: dòng phân ca, mọi yêu cầu của nó, số dòng outbox
     * "có yêu cầu mới" và số dòng audit của người gọi. Hai ảnh bằng nhau nghĩa là lệnh gọi không để lại gì.
     */
    protected String requestFootprint(long assignmentId, long actorAccountId) {
        return assignmentRow(assignmentId) + "|requests="
                + jdbc.queryForList("""
                        SELECT id || ':' || status || ':' || coalesce(decision_note, '') || ':' || coalesce(closed_at::text, '')
                        FROM shift_requests WHERE assignment_id = ? ORDER BY id""", String.class, assignmentId)
                + "|outbox=" + submittedOutboxCount() + "|audit=" + auditCountOfActor(actorAccountId);
    }
}
