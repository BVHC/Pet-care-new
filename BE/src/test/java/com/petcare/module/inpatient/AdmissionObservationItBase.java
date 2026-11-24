package com.petcare.module.inpatient;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * Nền chung của các IT theo dõi nội trú (inpatient-v1 #9–#10, docs/adr/0149 mục 31–36): dựng ca {@code ADMITTED} bằng API
 * nhập viện thật, ghi / đọc theo dõi bằng HTTP thật, đọc DB để khẳng định "không ghi gì". {@link #finish()} dọn thêm hồ
 * sơ cân (FK tới Visit) và outbox của ca <b>trước</b> khi dọn Visit. Mỗi lớp con có era riêng.
 */
abstract class AdmissionObservationItBase extends AdmissionOrderItBase {

    protected static final List<String> OBSERVATION_KEYS = List.of("observationId", "admissionId", "recordedByName",
            "temperatureC", "heartRate", "respiratoryRate", "weightKg", "appetite", "note", "abnormal", "createdAt");

    private final List<Long> pets = new ArrayList<>();
    private final List<Long> admissionIds = new ArrayList<>();

    @Override
    protected Admitted admitFromVisit(long branch, long customer, long service, long vet, String vetToken) {
        Admitted admitted = super.admitFromVisit(branch, customer, service, vet, vetToken);
        pets.add(admitted.pet());
        admissionIds.add(admitted.id());
        return admitted;
    }

    @Override
    protected void finish() {
        for (long admissionId : admissionIds) {
            jdbc.update("""
                    DELETE FROM notification_outbox
                    WHERE payload ->> 'ma_noi_tru' = (SELECT code FROM admissions WHERE id = ?)""", admissionId);
        }
        for (long pet : pets) {
            jdbc.update("DELETE FROM weight_records WHERE pet_id = ?", pet);
        }
        pets.clear();
        admissionIds.clear();
        super.finish();
    }

    // ---------------------------------------------------------------- HTTP

    protected static String observationsPath(long admissionId) {
        return ADMISSIONS + "/" + admissionId + "/observations";
    }

    protected ResponseEntity<Map<String, Object>> record(String token, long admissionId, String json) {
        return call(HttpMethod.POST, observationsPath(admissionId), token, json);
    }

    protected ResponseEntity<Map<String, Object>> listObservations(String token, long admissionId, String query) {
        return get(observationsPath(admissionId) + query, token);
    }

    /** Một mục đủ chỉ số; {@code abnormal} và cân nặng (null = không gửi) chọn được. */
    protected static String body(boolean abnormal, String weightKg) {
        return "{'temperatureC':38.6,'heartRate':120,'respiratoryRate':30,'appetite':'REDUCED','note':'Ăn ít'"
                + (weightKg == null ? "" : ",'weightKg':" + weightKg) + ",'abnormal':" + abnormal + "}";
    }

    protected static String noteOnly(String note, boolean abnormal) {
        return "{'note':'" + note + "','abnormal':" + abnormal + "}";
    }

    /** Ghi một mục thành công bằng API và trả id. */
    protected long newObservation(String token, long admissionId, String json) {
        ResponseEntity<Map<String, Object>> response = record(token, admissionId, json);
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(201);
        return idOf(dataOf(response), "observationId");
    }

    // ---------------------------------------------------------------- đọc DB

    protected int observationRows(long admissionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admission_observations WHERE admission_id = ?", Integer.class,
                admissionId);
    }

    protected int weightRows(long petId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM weight_records WHERE pet_id = ?", Integer.class, petId);
    }

    protected int outboxRows(long admissionId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification_outbox
                WHERE payload ->> 'ma_noi_tru' = (SELECT code FROM admissions WHERE id = ?)""", Integer.class,
                admissionId);
    }

    protected List<Map<String, Object>> outbox(long admissionId) {
        return jdbc.queryForList("""
                SELECT template_code, channel, recipient_account_id, recipient_email, payload ->> 'link_url' AS link_url,
                       payload ->> 'ten_khach' AS ten_khach, payload ->> 'ten_thu_cung' AS ten_thu_cung,
                       payload ->> 'chi_nhanh' AS chi_nhanh, payload ->> 'ma_noi_tru' AS ma_noi_tru,
                       payload ->> 'ngay_ghi' AS ngay_ghi, (SELECT count(*) FROM jsonb_object_keys(payload)) AS keys
                FROM notification_outbox
                WHERE payload ->> 'ma_noi_tru' = (SELECT code FROM admissions WHERE id = ?)
                ORDER BY id""", admissionId);
    }

    /** Dấu vết ghi toàn bộ của ca: số mục theo dõi / số dòng cân của thú / số dòng outbox. */
    protected String footprintOf(Admitted a) {
        return observationRows(a.id()) + "/" + weightRows(a.pet()) + "/" + outboxRows(a.id());
    }

    protected static BigDecimal decimal(Object json) {
        return new BigDecimal(String.valueOf(json));
    }
}
