package com.petcare.module.inpatient;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * Nền chung của các IT y lệnh nội trú (inpatient-v1 #5–#8, docs/adr/0149 mục 26–30): dựng ca {@code ADMITTED} bằng API
 * nhập viện thật (nên Visit, Order, chuồng đúng như production), ra / thực hiện / hủy y lệnh bằng HTTP thật, và đọc DB để
 * khẳng định "không ghi gì". Mỗi lớp con có era riêng.
 */
abstract class AdmissionOrderItBase extends AdmissionItBase {

    protected static final List<String> ORDER_KEYS = List.of("admissionOrderId", "admissionId", "orderType", "content",
            "scheduledAt", "status", "prescriptionItemId", "doneAt", "doneNote", "cancelReason");
    protected static final String SCHEDULED = "2100-03-14T05:00:00Z";

    /** Ca nội trú đã nhập (đường a) bằng API thật. */
    protected record Admitted(long id, long visit, long pet, long kennel) {}

    protected Admitted admitFromVisit(long branch, long customer, long service, long vet, String vetToken) {
        long pet = data.pet(customer);
        long visit = data.visit(branch, customer, pet, vet);
        long kennel = data.kennel(branch);
        ResponseEntity<Map<String, Object>> response = admit(vetToken, fromVisit(visit, service, kennel));
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(201);
        return new Admitted(idOf(dataOf(response), "admissionId"), visit, pet, kennel);
    }

    protected static String ordersPath(long admissionId) {
        return ADMISSIONS + "/" + admissionId + "/orders";
    }

    protected static String completePath(long orderId) {
        return "/api/admission-orders/" + orderId + "/complete";
    }

    protected static String cancelPath(long orderId) {
        return "/api/admission-orders/" + orderId + "/cancel";
    }

    protected static String feeding(String content) {
        return "{'orderType':'FEEDING','content':'" + content + "','scheduledAt':'" + SCHEDULED + "'}";
    }

    protected static String medication(long itemId) {
        return "{'orderType':'MEDICATION','content':'Tiêm kháng sinh','scheduledAt':'" + SCHEDULED
                + "','prescriptionItemId':" + itemId + "}";
    }

    protected ResponseEntity<Map<String, Object>> createOrder(String token, long admissionId, String json) {
        return call(HttpMethod.POST, ordersPath(admissionId), token, json);
    }

    protected ResponseEntity<Map<String, Object>> complete(String token, long orderId, String note) {
        return call(HttpMethod.POST, completePath(orderId), token, "{'note':'" + note + "'}");
    }

    protected ResponseEntity<Map<String, Object>> cancel(String token, long orderId, String reason) {
        return call(HttpMethod.POST, cancelPath(orderId), token, "{'reason':'" + reason + "'}");
    }

    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> listOf(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    /** Ra một y lệnh không phải thuốc bằng API và trả id. */
    protected long newFeeding(String token, long admissionId) {
        ResponseEntity<Map<String, Object>> response = createOrder(token, admissionId, feeding("Cho ăn"));
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(201);
        return idOf(dataOf(response), "admissionOrderId");
    }

    /** Ra một y lệnh thuốc (dòng đơn của phòng khám) bằng API và trả id. */
    protected long newMedication(String token, long admissionId, long itemId) {
        ResponseEntity<Map<String, Object>> response = createOrder(token, admissionId, medication(itemId));
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(201);
        return idOf(dataOf(response), "admissionOrderId");
    }

    // ---------------------------------------------------------------- đọc DB

    protected int orderRows(long admissionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admission_orders WHERE admission_id = ?", Integer.class,
                admissionId);
    }

    /** Toàn bộ cột có thể đổi của một y lệnh, để so "không đổi" trước / sau một lời gọi bị từ chối. */
    protected String orderFootprint(long orderId) {
        return jdbc.queryForObject("""
                SELECT status || '|' || COALESCE(done_by::text, '') || '|' || COALESCE(done_at::text, '') || '|'
                       || COALESCE(done_note, '') || '|' || COALESCE(cancel_reason, '')
                FROM admission_orders WHERE id = ?""", String.class, orderId);
    }

    protected String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM admission_orders WHERE id = ?", String.class, orderId);
    }

    protected long orderCreatedBy(long orderId) {
        return jdbc.queryForObject("SELECT created_by FROM admission_orders WHERE id = ?", Long.class, orderId);
    }

    protected String admissionStatus(long admissionId) {
        return jdbc.queryForObject("SELECT status FROM admissions WHERE id = ?", String.class, admissionId);
    }
}
