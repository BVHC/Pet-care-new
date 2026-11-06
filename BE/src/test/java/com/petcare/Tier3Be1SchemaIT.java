package com.petcare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Schema tầng 3 phần BE-1 (V36–V41, T55b, docs/adr/0113). Mọi kỳ vọng là bản sao độc lập chép từ đặc tả
 * (docs/05 §14.1 cho Nhân sự; docs/09 §A–E cho phần còn lại), không đọc lại từ file migration. Mỗi test chạy toàn bộ
 * INSERT trong một transaction rồi hoàn tác, nên không để lại dữ liệu; một INSERT bị từ chối được bọc trong SAVEPOINT để
 * transaction còn dùng tiếp (Postgres hủy transaction sau lỗi). Tên ràng buộc đặt theo khuôn của V22 / V34.
 *
 * <p>Có cả ràng buộc trên bảng của BE-2 mà BE-1 làm thay (V41: {@code medical_type}, {@code line_type}; V42: Order
 * {@code ONLINE}, docs/adr/0116).
 *
 * <p>Danh sách bảng, kiểu cột, FK RESTRICT, index cho FK → accounts / customers do {@link SchemaMigrationIT} giữ; lớp này
 * chứng minh các CHECK, UNIQUE (kể cả partial) và giá trị enum mới thực sự chặn đúng.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class Tier3Be1SchemaIT {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final LocalDate DAY = LocalDate.of(2026, 12, 1);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    // ------------------------------------------------------------------ V36: Nhân sự (05 §14.1)

    @Test
    void shiftDefinitionRejectsEmptyOrOvernightRangeAndDuplicateNamePerBranch() {
        inTx(() -> {
            long branch = branch();
            Map<String, Object> base = row("branch_id", branch, "name", "Ca sáng", "start_time", LocalTime.of(8, 0),
                    "end_time", LocalTime.of(12, 0));

            insert("shift_definitions", base);
            assertThat(jdbc.queryForObject("SELECT is_active FROM shift_definitions WHERE branch_id = ?",
                    Boolean.class, branch)).as("mặc định đang dùng").isTrue();

            rejects("ck_shift_definitions_time_order", () -> insert("shift_definitions",
                    with(base, "name", "Ca bằng", "start_time", LocalTime.of(12, 0))));
            rejects("ck_shift_definitions_time_order", () -> insert("shift_definitions",
                    with(base, "name", "Ca qua đêm", "start_time", LocalTime.of(22, 0), "end_time", LocalTime.of(6, 0))));
            // BR-NS-01: không phân biệt hoa thường, nhưng khác chi nhánh thì được
            rejects("ux_shift_definitions_branch_name", () -> insert("shift_definitions", with(base, "name", "CA SÁNG")));
            insert("shift_definitions", with(base, "branch_id", branch(), "name", "CA SÁNG"));
        });
    }

    @Test
    void shiftAssignmentStatusColumnsAndScheduledUniquenessFollowTheSpec() {
        inTx(() -> {
            long branch = branch();
            long staff = account("RECEPTIONIST");
            long shift = shiftDefinition(branch, "Ca sáng", 8, 12);
            Map<String, Object> base = row("branch_id", branch, "staff_id", staff, "shift_definition_id", shift,
                    "work_date", DAY, "status", "SCHEDULED");

            insert("shift_assignments", base);
            rejects("ck_shift_assignments_status", () -> insert("shift_assignments",
                    with(base, "work_date", DAY.plusDays(1), "status", "DONE")));
            // SCHEDULED ⇔ chưa rời trạng thái (status_changed_at NULL)
            rejects("ck_shift_assignments_status_changed", () -> insert("shift_assignments",
                    with(base, "work_date", DAY.plusDays(1), "status_changed_at", OffsetDateTime.now())));
            rejects("ck_shift_assignments_status_changed", () -> insert("shift_assignments",
                    with(base, "work_date", DAY.plusDays(1), "status", "ON_LEAVE")));
            // BR-NS-02, 07: ABSENT và CANCELLED bắt buộc có lý do
            for (String status : List.of("ABSENT", "CANCELLED")) {
                rejects("ck_shift_assignments_reason", () -> insert("shift_assignments", with(base,
                        "work_date", DAY.plusDays(2), "status", status, "status_changed_at", OffsetDateTime.now())));
            }
            insert("shift_assignments", with(base, "work_date", DAY.plusDays(2), "status", "ON_LEAVE",
                    "status_changed_at", OffsetDateTime.now()));

            // Trùng đúng cặp ca – ngày khi còn SCHEDULED bị chặn; sau khi hủy thì phân lại được
            rejects("ux_shift_assignments_scheduled", () -> insert("shift_assignments", base));
            long other = shiftDefinition(branch, "Ca chiều", 13, 17);
            insert("shift_assignments", with(base, "shift_definition_id", other));
            insert("shift_assignments", with(base, "work_date", DAY.plusDays(3), "status", "CANCELLED",
                    "reason", "Đổi lịch", "status_changed_at", OffsetDateTime.now()));
            insert("shift_assignments", with(base, "work_date", DAY.plusDays(3)));
        });
    }

    @Test
    void shiftRequestChecksAndSinglePendingPerAssignment() {
        inTx(() -> {
            long branch = branch();
            long staff = account("CARETAKER");
            long manager = account("BRANCH_MANAGER");
            long morning = shiftDefinition(branch, "Ca sáng", 8, 12);
            long evening = shiftDefinition(branch, "Ca chiều", 13, 17);
            long assignment = insert("shift_assignments", row("branch_id", branch, "staff_id", staff,
                    "shift_definition_id", morning, "work_date", DAY, "status", "SCHEDULED"));
            Map<String, Object> pending = row("assignment_id", assignment, "requester_id", staff,
                    "request_type", "LEAVE", "reason", "Việc gia đình", "status", "PENDING");

            insert("shift_requests", pending);
            // Tối đa 1 yêu cầu PENDING mỗi phân ca (BR-NS-04)
            rejects("ux_shift_requests_pending", () -> insert("shift_requests", pending));

            rejects("ck_shift_requests_type", () -> insert("shift_requests", with(pending, "request_type", "SKIP")));
            rejects("ck_shift_requests_status", () -> insert("shift_requests",
                    with(pending, "status", "DONE", "closed_at", OffsetDateTime.now())));
            // SWAP ⇔ có ca đích
            rejects("ck_shift_requests_swap_target", () -> insert("shift_requests",
                    with(pending, "request_type", "SWAP")));
            rejects("ck_shift_requests_swap_target", () -> insert("shift_requests",
                    with(pending, "target_shift_definition_id", evening)));
            // PENDING ⇔ chưa đóng
            rejects("ck_shift_requests_closed_at", () -> insert("shift_requests",
                    with(pending, "closed_at", OffsetDateTime.now())));
            rejects("ck_shift_requests_closed_at", () -> insert("shift_requests",
                    with(pending, "status", "WITHDRAWN")));
            // REJECTED cần lời giải thích, APPROVED cần người duyệt
            rejects("ck_shift_requests_rejected_note", () -> insert("shift_requests",
                    with(pending, "status", "REJECTED", "closed_at", OffsetDateTime.now())));
            rejects("ck_shift_requests_approved_by", () -> insert("shift_requests",
                    with(pending, "status", "APPROVED", "closed_at", OffsetDateTime.now())));

            insert("shift_requests", with(pending, "status", "APPROVED", "decided_by", manager,
                    "closed_at", OffsetDateTime.now()));
            insert("shift_requests", with(pending, "request_type", "SWAP", "target_shift_definition_id", evening,
                    "status", "REJECTED", "decision_note", "Thiếu người", "decided_by", manager,
                    "closed_at", OffsetDateTime.now()));
            insert("shift_requests", with(pending, "status", "WITHDRAWN", "closed_at", OffsetDateTime.now()));
        });
    }

    // ------------------------------------------------------------------ V37: khám tại nhà (09 §A)

    @Test
    void homeVisitColumnsOfAppointmentsAndVisitsDefaultToFalseAndKeepTheirChecks() {
        inTx(() -> {
            Fixture f = fixture();
            long exam = service("MEDICAL", "EXAM");
            long grooming = service("GROOMING", null);
            Map<String, Object> normal = appointment(f, exam, "MEDICAL");

            long normalId = insert("appointments", normal);
            assertThat(jdbc.queryForObject("SELECT is_home_visit FROM appointments WHERE id = ?", Boolean.class,
                    normalId)).isFalse();
            assertThat(jdbc.queryForObject("SELECT is_home_visit FROM visits WHERE id = ?", Boolean.class,
                    visit(f, null))).isFalse();

            Map<String, Object> home = with(normal, "code", "LH-T3-H" + SEQ.incrementAndGet(), "is_home_visit", true, "home_address", "12 Nguyễn Huệ, Q1",
                    "home_contact_phone", "0901234567", "home_fee", 50_000L, "slot_start", LocalTime.of(10, 0));
            insert("appointments", home);
            // is_home_visit ⇔ có địa chỉ và phí
            rejects("ck_appointments_home_visit_fields", () -> insert("appointments",
                    with(home, "slot_start", LocalTime.of(10, 30), "home_fee", null)));
            rejects("ck_appointments_home_visit_fields", () -> insert("appointments",
                    with(home, "slot_start", LocalTime.of(10, 30), "home_address", null)));
            rejects("ck_appointments_home_visit_fields", () -> insert("appointments",
                    with(home, "slot_start", LocalTime.of(10, 30), "is_home_visit", false)));
            // Chỉ nhóm Khám / Tiêm
            rejects("ck_appointments_home_visit_medical", () -> insert("appointments",
                    with(home, "slot_start", LocalTime.of(11, 0), "service_id", grooming, "service_group", "GROOMING")));
            // Phí khám tại nhà không âm; 0 đồng hợp lệ
            rejects("ck_appointments_home_fee", () -> insert("appointments",
                    with(home, "slot_start", LocalTime.of(11, 30), "home_fee", -1L)));
            insert("appointments", with(home, "code", "LH-T3-H" + SEQ.incrementAndGet(), "slot_start",
                    LocalTime.of(11, 30), "home_fee", 0L));
        });
    }

    // ------------------------------------------------------------------ V37: phẫu thuật (09 §B, FSM #23)

    @Test
    void surgeryStatusColumnsFollowTheStateMachine() {
        inTx(() -> {
            Fixture f = fixture();
            long visit = visit(f, null);
            long vet = account("VET");
            long manager = account("RECEPTIONIST");
            long service = service("MEDICAL", "EXAM");
            Map<String, Object> planned = row("visit_id", visit, "pet_id", f.pet, "branch_id", f.branch,
                    "service_id", service, "status", "PLANNED", "planned_by", vet);
            Map<String, Object> consent = with(planned, "status", "CONSENTED", "consent_text", "Rủi ro gây mê",
                    "consent_signer_name", "Nguyễn Văn A", "consent_signer_relation", "Chủ thú",
                    "consented_by", manager, "consented_at", OffsetDateTime.now());
            Map<String, Object> started = with(consent, "status", "IN_PROGRESS", "started_at", OffsetDateTime.now());
            Map<String, Object> completed = with(started, "status", "COMPLETED", "completed_at", OffsetDateTime.now(),
                    "result_note", "Ổn định", "order_line_id", orderLine(f, service));

            insert("surgeries", planned);
            rejects("ck_surgeries_status", () -> insert("surgeries", with(planned, "status", "DONE")));
            // CONSENTED trở đi phải có đủ cam kết; PLANNED thì chưa có
            for (String column : List.of("consent_text", "consent_signer_name", "consent_signer_relation",
                    "consented_by", "consented_at")) {
                rejects("ck_surgeries_consent_fields", () -> insert("surgeries", with(consent, column, null)));
            }
            rejects("ck_surgeries_planned_unconsented", () -> insert("surgeries",
                    with(planned, "consented_at", OffsetDateTime.now(), "consented_by", manager)));
            insert("surgeries", consent);
            // IN_PROGRESS / COMPLETED có started_at
            rejects("ck_surgeries_started_at", () -> insert("surgeries", with(started, "started_at", null)));
            insert("surgeries", started);
            // COMPLETED ⇔ có kết quả, thời điểm xong và dòng Order
            for (String column : List.of("completed_at", "result_note", "order_line_id")) {
                rejects("ck_surgeries_completed_fields", () -> insert("surgeries", with(completed, column, null)));
            }
            insert("surgeries", completed);
            // Mỗi dòng Order chỉ thuộc một phẫu thuật
            rejects("uq_surgeries_order_line_id", () -> insert("surgeries", completed));
            // CANCELLED ⇔ có lý do; cancelled_by NULL là hệ thống (FSM #23 dòng 6)
            rejects("ck_surgeries_cancelled_reason", () -> insert("surgeries", with(planned, "status", "CANCELLED")));
            rejects("ck_surgeries_cancelled_reason", () -> insert("surgeries",
                    with(planned, "cancel_reason", "Không cần nữa")));
            insert("surgeries", with(planned, "status", "CANCELLED", "cancel_reason", "Hủy lượt khám"));
            insert("surgeries", with(started, "status", "CANCELLED", "cancel_reason", "Dừng giữa chừng",
                    "cancelled_by", vet));
        });
    }

    // ------------------------------------------------------------------ V37: vật tư tiêu hao (09 §E)

    @Test
    void visitMaterialUsageKeepsQuantitiesConsistentAndUniquePerVisitServiceProduct() {
        inTx(() -> {
            Fixture f = fixture();
            long visit = visit(f, null);
            long service = service("MEDICAL", "EXAM");
            long product = product();
            Map<String, Object> usage = row("visit_id", visit, "service_id", service, "product_id", product,
                    "branch_id", f.branch, "norm_qty", 5, "issued_qty", 3, "shortage_qty", 2);

            insert("visit_material_usages", usage);
            rejects("uq_visit_material_usages_visit_service_product", () -> insert("visit_material_usages", usage));
            long otherProduct = product();
            // norm = issued + shortage, norm > 0, hai số kia ≥ 0
            rejects("ck_visit_material_usages_balance", () -> insert("visit_material_usages",
                    with(usage, "product_id", otherProduct, "issued_qty", 4)));
            rejects("ck_visit_material_usages_norm_qty", () -> insert("visit_material_usages",
                    with(usage, "product_id", otherProduct, "norm_qty", 0, "issued_qty", 0, "shortage_qty", 0)));
            rejects("ck_visit_material_usages_issued_qty", () -> insert("visit_material_usages",
                    with(usage, "product_id", otherProduct, "norm_qty", 4, "issued_qty", -1, "shortage_qty", 5)));
            rejects("ck_visit_material_usages_shortage_qty", () -> insert("visit_material_usages",
                    with(usage, "product_id", otherProduct, "norm_qty", 4, "issued_qty", 5, "shortage_qty", -1)));
            insert("visit_material_usages", with(usage, "product_id", otherProduct, "norm_qty", 4, "issued_qty", 4,
                    "shortage_qty", 0));
        });
    }

    // ------------------------------------------------------------------ V38: nội trú (09 §C, FSM #24, #25)

    @Test
    void admissionAllowsOneAdmittedPerPetAndDischargeColumnsFollowTheStateMachine() {
        inTx(() -> {
            Fixture f = fixture();
            long visit = visit(f, null);
            long vet = account("VET");
            long service = service("MEDICAL", "EXAM");
            Map<String, Object> admitted = row("code", "NT-" + SEQ.incrementAndGet(), "visit_id", visit,
                    "pet_id", f.pet, "branch_id", f.branch, "service_id", service, "unit_price", 300_000L,
                    "status", "ADMITTED", "admitted_by", vet, "admitted_at", OffsetDateTime.now());
            Map<String, Object> discharged = with(admitted, "status", "DISCHARGED", "discharge_outcome", "HOME",
                    "discharge_summary", "Ổn định", "discharged_by", vet, "discharged_at", OffsetDateTime.now(),
                    "order_line_id", orderLine(f, service));

            insert("admissions", admitted);
            // Mỗi thú tối đa một ca ADMITTED (BR-LT-13)
            rejects("ux_admissions_pet_admitted", () -> insert("admissions", with(admitted, "code", "NT-X1")));
            rejects("ck_admissions_status", () -> insert("admissions", with(admitted, "code", "NT-X2", "status", "OPEN")));
            rejects("ck_admissions_unit_price", () -> insert("admissions", with(admitted, "code", "NT-X3",
                    "pet_id", pet(f.customer), "unit_price", -1L)));
            // DISCHARGED ⇔ có kết cục; DISCHARGED còn phải có tóm tắt, người xuất, thời điểm và dòng Order
            rejects("ck_admissions_discharge_outcome_tied", () -> insert("admissions",
                    with(discharged, "code", "NT-Y0", "pet_id", pet(f.customer), "discharge_outcome", null)));
            for (String column : List.of("discharge_summary", "discharged_by", "discharged_at", "order_line_id")) {
                rejects("ck_admissions_discharge_fields", () -> insert("admissions",
                        with(discharged, "code", "NT-Y" + SEQ.incrementAndGet(), "pet_id", pet(f.customer), column, null)));
            }
            rejects("ck_admissions_discharge_outcome_tied", () -> insert("admissions",
                    with(admitted, "code", "NT-X4", "pet_id", pet(f.customer), "discharge_outcome", "HOME")));
            rejects("ck_admissions_discharge_outcome", () -> insert("admissions",
                    with(discharged, "code", "NT-X5", "order_line_id", orderLine(f, service),
                            "discharge_outcome", "TRANSFERRED")));
            // Xuất viện rồi thì nhập lại được; mã duy nhất
            long returning = pet(f.customer);
            insert("admissions", with(discharged, "code", "NT-X6", "pet_id", returning));
            insert("admissions", with(admitted, "code", "NT-X7", "pet_id", returning));
            rejects("uq_admissions_code", () -> insert("admissions", with(admitted, "code", "NT-X7",
                    "pet_id", pet(f.customer))));
        });
    }

    @Test
    void admissionOrderChecksFollowTheStateMachine() {
        inTx(() -> {
            Fixture f = fixture();
            long vet = account("VET");
            long service = service("MEDICAL", "EXAM");
            long admission = insert("admissions", row("code", "NT-" + SEQ.incrementAndGet(), "visit_id",
                    visit(f, null), "pet_id", f.pet, "branch_id", f.branch, "service_id", service,
                    "unit_price", 1L, "status", "ADMITTED", "admitted_by", vet, "admitted_at", OffsetDateTime.now()));
            Map<String, Object> feeding = row("admission_id", admission, "order_type", "FEEDING",
                    "content", "Cho ăn 50 g", "scheduled_at", OffsetDateTime.now().plusHours(1),
                    "status", "ORDERED", "created_by", vet);

            insert("admission_orders", feeding);
            rejects("ck_admission_orders_type", () -> insert("admission_orders", with(feeding, "order_type", "BATH")));
            rejects("ck_admission_orders_status", () -> insert("admission_orders", with(feeding, "status", "OPEN")));
            // MEDICATION bắt buộc liên kết dòng đơn thuốc; loại khác thì không cần
            rejects("ck_admission_orders_medication_item", () -> insert("admission_orders",
                    with(feeding, "order_type", "MEDICATION")));
            // DONE ⇔ có người làm, thời điểm và ghi chú; CANCELLED ⇔ có lý do
            for (String column : List.of("done_by", "done_at", "done_note")) {
                Map<String, Object> done = with(feeding, "status", "DONE", "done_by", vet,
                        "done_at", OffsetDateTime.now(), "done_note", "Đã cho ăn");
                rejects("ck_admission_orders_done_fields", () -> insert("admission_orders", with(done, column, null)));
            }
            rejects("ck_admission_orders_done_fields", () -> insert("admission_orders",
                    with(feeding, "done_note", "Chưa làm")));
            rejects("ck_admission_orders_cancelled_reason", () -> insert("admission_orders",
                    with(feeding, "status", "CANCELLED")));
            insert("admission_orders", with(feeding, "status", "DONE", "done_by", vet,
                    "done_at", OffsetDateTime.now(), "done_note", "Đã cho ăn"));
            insert("admission_orders", with(feeding, "status", "CANCELLED", "cancel_reason", "Thú nôn"));
        });
    }

    @Test
    void admissionObservationAcceptsOnlyKnownAppetiteAndDefaultsAbnormalToFalse() {
        inTx(() -> {
            Fixture f = fixture();
            long vet = account("VET");
            long service = service("MEDICAL", "EXAM");
            long admission = insert("admissions", row("code", "NT-" + SEQ.incrementAndGet(), "visit_id",
                    visit(f, null), "pet_id", f.pet, "branch_id", f.branch, "service_id", service,
                    "unit_price", 1L, "status", "ADMITTED", "admitted_by", vet, "admitted_at", OffsetDateTime.now()));
            Map<String, Object> observation = row("admission_id", admission, "recorded_by", vet,
                    "temperature_c", new java.math.BigDecimal("38.6"), "heart_rate", 120, "respiratory_rate", 30,
                    "weight_kg", new java.math.BigDecimal("12.40"), "appetite", "REDUCED", "note", "Ăn ít");

            long id = insert("admission_observations", observation);
            assertThat(jdbc.queryForObject("SELECT abnormal FROM admission_observations WHERE id = ?", Boolean.class,
                    id)).isFalse();
            for (String appetite : List.of("NORMAL", "REDUCED", "NONE")) {
                insert("admission_observations", with(observation, "appetite", appetite));
            }
            rejects("ck_admission_observations_appetite", () -> insert("admission_observations",
                    with(observation, "appetite", "GOOD")));
            // Cột nào cũng được bỏ trống ngoài người ghi (một lần ghi chỉ có thể là ghi chú)
            insert("admission_observations", row("admission_id", admission, "recorded_by", vet, "abnormal", true));
        });
    }

    // ------------------------------------------------------------------ V39: khiếu nại (09 §D, FSM #26)

    @Test
    void complaintChecksFollowTheStateMachine() {
        inTx(() -> {
            Fixture f = fixture();
            long manager = account("BRANCH_MANAGER");
            Map<String, Object> open = row("code", "KN-" + SEQ.incrementAndGet(), "customer_id", f.customer,
                    "branch_id", f.branch, "topic", "SERVICE_QUALITY", "content", "Chờ quá lâu ở quầy tiếp nhận",
                    "status", "OPEN", "last_activity_at", OffsetDateTime.now());
            Map<String, Object> inProgress = with(open, "status", "IN_PROGRESS", "handler_id", manager);
            Map<String, Object> resolved = with(inProgress, "status", "RESOLVED", "resolution", "Đã xin lỗi khách",
                    "resolved_at", OffsetDateTime.now());

            long id = insert("complaints", open);
            assertThat(jdbc.queryForObject("SELECT reopen_count FROM complaints WHERE id = ?", Integer.class, id))
                    .isZero();
            rejects("uq_complaints_code", () -> insert("complaints", open));
            rejects("ck_complaints_topic", () -> insert("complaints", with(open, "code", "KN-X1", "topic", "PRICE")));
            rejects("ck_complaints_status", () -> insert("complaints", with(open, "code", "KN-X2", "status", "DONE")));
            rejects("ck_complaints_reopen_count", () -> insert("complaints",
                    with(open, "code", "KN-X3", "reopen_count", -1)));
            // Đối tượng liên quan: cả hai cột cùng NULL hoặc cùng có, loại thuộc tập cho phép
            rejects("ck_complaints_related_pair", () -> insert("complaints",
                    with(open, "code", "KN-X4", "related_type", "ORDER")));
            rejects("ck_complaints_related_pair", () -> insert("complaints",
                    with(open, "code", "KN-X5", "related_id", 1L)));
            rejects("ck_complaints_related_type", () -> insert("complaints",
                    with(open, "code", "KN-X6", "related_type", "PET", "related_id", 1L)));
            insert("complaints", with(open, "code", "KN-X7", "related_type", "VISIT", "related_id", 99L));
            // Chi nhánh bắt buộc trừ chủ đề OTHER (BR-DG-05)
            rejects("ck_complaints_branch_unless_other", () -> insert("complaints",
                    with(open, "code", "KN-X8", "branch_id", null)));
            insert("complaints", with(open, "code", "KN-X9", "branch_id", null, "topic", "OTHER"));
            // OPEN chưa có người xử lý; IN_PROGRESS / RESOLVED phải có; CLOSED thì sao cũng được
            rejects("ck_complaints_handler", () -> insert("complaints", with(open, "code", "KN-Y1", "handler_id", manager)));
            rejects("ck_complaints_handler", () -> insert("complaints", with(inProgress, "code", "KN-Y2",
                    "handler_id", null)));
            insert("complaints", with(inProgress, "code", "KN-Y3"));
            // RESOLVED có kết luận và thời điểm
            rejects("ck_complaints_resolved_fields", () -> insert("complaints",
                    with(resolved, "code", "KN-Y4", "resolution", null)));
            rejects("ck_complaints_resolved_fields", () -> insert("complaints",
                    with(resolved, "code", "KN-Y5", "resolved_at", null)));
            insert("complaints", with(resolved, "code", "KN-Y6"));
            // CLOSED có lý do đóng và thời điểm đóng
            Map<String, Object> closed = with(resolved, "code", "KN-Y7", "status", "CLOSED", "closed_at",
                    OffsetDateTime.now(), "closed_reason", "AUTO");
            rejects("ck_complaints_closed_fields", () -> insert("complaints", with(closed, "closed_reason", null)));
            rejects("ck_complaints_closed_fields", () -> insert("complaints", with(closed, "closed_at", null)));
            rejects("ck_complaints_closed_reason", () -> insert("complaints", with(closed, "closed_reason", "OTHER")));
            insert("complaints", closed);
            // Rút khi còn OPEN: không người xử lý, không kết luận
            insert("complaints", with(open, "code", "KN-Y8", "status", "CLOSED", "closed_at", OffsetDateTime.now(),
                    "closed_reason", "WITHDRAWN"));
        });
    }

    @Test
    void complaintMessageAcceptsOnlyKnownAuthorTypesAndKinds() {
        inTx(() -> {
            Fixture f = fixture();
            long complaint = insert("complaints", row("code", "KN-" + SEQ.incrementAndGet(), "customer_id",
                    f.customer, "branch_id", f.branch, "topic", "STAFF", "content", "Nhân viên thiếu lịch sự",
                    "status", "OPEN", "last_activity_at", OffsetDateTime.now()));
            Map<String, Object> message = row("complaint_id", complaint, "author_type", "SYSTEM", "kind", "STATUS",
                    "body", "Khiếu nại quá hạn nhận xử lý");

            insert("complaint_messages", message);
            insert("complaint_messages", with(message, "author_type", "STAFF", "author_id", account("RECEPTIONIST"),
                    "kind", "MESSAGE"));
            rejects("ck_complaint_messages_author_type", () -> insert("complaint_messages",
                    with(message, "author_type", "BOT")));
            rejects("ck_complaint_messages_kind", () -> insert("complaint_messages", with(message, "kind", "NOTE")));
        });
    }

    // ------------------------------------------------------------------ V41: giá trị mới của bảng có sẵn (05 §13 mục 26)

    @Test
    void medicalTypeAcceptsSurgeryAndInpatientOnlyForMedicalServices() {
        inTx(() -> {
            for (String type : List.of("EXAM", "VACCINE", "SURGERY", "INPATIENT")) {
                service("MEDICAL", type);
            }
            rejects("ck_services_medical_type", () -> service("MEDICAL", "CHECKUP"));
            // Dịch vụ không thuộc nhóm Khám / Tiêm vẫn không được có loại (BR-SP-06)
            rejects("ck_services_medical_type_by_group", () -> service("GROOMING", "SURGERY"));
        });
    }

    @Test
    void homeFeeLineHasNeitherServiceNorProduct() {
        inTx(() -> {
            Fixture f = fixture();
            long order = jdbc.queryForObject("""
                    INSERT INTO orders (code, source, customer_id, branch_id, status, created_by)
                    VALUES (?, 'RETAIL', ?, ?, 'OPEN', ?) RETURNING id
                    """, Long.class, "DH-IT-" + SEQ.incrementAndGet(), f.customer, f.branch, account("RECEPTIONIST"));
            long staff = account("VET");
            Map<String, Object> fee = row("order_id", order, "line_type", "HOME_FEE", "description", "Phí khám tại nhà",
                    "quantity", 1, "unit_price", 50_000L, "line_total", 50_000L, "added_by", staff,
                    "owner_account_id", staff);

            assertThatCode(() -> insert("order_lines", fee)).doesNotThrowAnyException();
            // Hai CHECK sẵn có của V1 vẫn chặn HOME_FEE mang service_id hoặc product_id
            rejects("ck_order_lines_service_ref", () -> insert("order_lines", with(fee, "service_id", service("MEDICAL",
                    "EXAM"))));
            rejects("ck_order_lines_product_ref", () -> insert("order_lines", with(fee, "product_id", product())));
            rejects("ck_order_lines_line_type", () -> insert("order_lines", with(fee, "line_type", "TRAVEL_FEE")));
        });
    }

    // ------------------------------------------------------------------ V42: Order nguồn ONLINE (làm thay BE-2, ADR-0116)

    /**
     * Order {@code ONLINE} (08 §B #14b): không Visit, không đặt chỗ lưu trú, không bao giờ có {@code payment_id} (tiền đi
     * qua {@code payment_transactions}, không qua ca thu ngân). Ràng buộc của ba nguồn quầy giữ nguyên.
     */
    @Test
    void onlineOrderHasNoVisitBoardingOrCounterPaymentAndCounterInvariantsStay() {
        inTx(() -> {
            Fixture f = fixture();
            long buyer = customerAccount();
            long cashier = account("RECEPTIONIST");
            Map<String, Object> online = row("code", "DH-T3-" + SEQ.incrementAndGet(), "source", "ONLINE",
                    "customer_id", f.customer, "branch_id", f.branch, "status", "PENDING", "created_by", buyer);

            insert("orders", online);
            rejects("ck_orders_single_source", () -> insert("orders",
                    with(online, "code", "DH-T3-X" + SEQ.incrementAndGet(), "visit_id", visit(f, null))));
            // PAID không cần payment_id; có payment_id ở bất kỳ trạng thái nào thì bị chặn
            insert("orders", with(online, "code", "DH-T3-X" + SEQ.incrementAndGet(), "status", "PAID",
                    "paid_at", OffsetDateTime.now()));
            long payment = counterPayment(f, cashier);
            rejects("ck_orders_paid_payment", () -> insert("orders", with(online,
                    "code", "DH-T3-X" + SEQ.incrementAndGet(), "status", "PAID", "payment_id", payment)));
            rejects("ck_orders_paid_payment", () -> insert("orders", with(online,
                    "code", "DH-T3-X" + SEQ.incrementAndGet(), "payment_id", payment)));

            // Đơn quầy vẫn PAID ⇔ có payment_id
            Map<String, Object> retail = with(online, "source", "RETAIL", "created_by", cashier);
            rejects("ck_orders_paid_payment", () -> insert("orders", with(retail,
                    "code", "BL-T3-" + SEQ.incrementAndGet(), "status", "PAID")));
            rejects("ck_orders_paid_payment", () -> insert("orders", with(retail,
                    "code", "BL-T3-" + SEQ.incrementAndGet(), "payment_id", payment)));
            insert("orders", with(retail, "code", "BL-T3-" + SEQ.incrementAndGet(), "status", "PAID",
                    "payment_id", payment, "paid_at", OffsetDateTime.now()));

            // Hủy đơn online có loại riêng (ST10, khách hủy); UNPAID của quầy vẫn dùng được, loại lạ thì không
            for (String type : List.of("ONLINE_CUSTOMER", "ONLINE_EXPIRED", "UNPAID")) {
                insert("orders", with(online, "code", "DH-T3-X" + SEQ.incrementAndGet(), "status", "CANCELLED",
                        "cancel_type", type, "cancelled_at", OffsetDateTime.now()));
            }
            rejects("ck_orders_cancel_type", () -> insert("orders", with(online,
                    "code", "DH-T3-X" + SEQ.incrementAndGet(), "status", "CANCELLED", "cancel_type", "ONLINE_OTHER",
                    "cancelled_at", OffsetDateTime.now())));
        });
    }

    // ------------------------------------------------------------------ fixture và trợ giúp

    private record Fixture(long branch, long customer, long pet) {
    }

    private Fixture fixture() {
        long customer = jdbc.queryForObject("""
                INSERT INTO customers (full_name, phone, created_channel) VALUES ('Khách IT', '0911111111', 'COUNTER')
                RETURNING id
                """, Long.class);
        return new Fixture(branch(), customer, pet(customer));
    }

    private long branch() {
        return jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, 'ACTIVE') RETURNING id
                """, Long.class, "T3 chi nhánh " + SEQ.incrementAndGet());
    }

    private long account(String role) {
        return jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0900000000', 'hash', ?, 'ACTIVE') RETURNING id
                """, Long.class, "t3-" + SEQ.incrementAndGet() + "@petcare.test", role);
    }

    /** Tài khoản khách: không có SĐT ({@code ck_accounts_phone_by_role}). */
    private long customerAccount() {
        return jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status) VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE')
                RETURNING id
                """, Long.class, "t3-" + SEQ.incrementAndGet() + "@petcare.test");
    }

    private long pet(long customer) {
        return jdbc.queryForObject("""
                INSERT INTO pets (customer_id, name, species, sex) VALUES (?, ?, 'DOG', 'MALE') RETURNING id
                """, Long.class, customer, "Thú T3 " + SEQ.incrementAndGet());
    }

    private long service(String group, String medicalType) {
        return jdbc.queryForObject("""
                INSERT INTO services (name, service_group, medical_type, price) VALUES (?, ?, ?, 100000) RETURNING id
                """, Long.class, "T3 dịch vụ " + SEQ.incrementAndGet(), group, medicalType);
    }

    private long product() {
        long category = jdbc.queryForObject("INSERT INTO product_categories (name) VALUES (?) RETURNING id",
                Long.class, "T3 nhóm " + SEQ.incrementAndGet());
        return jdbc.queryForObject("""
                INSERT INTO products (category_id, sku, name, product_type, tracks_expiry, unit, price)
                VALUES (?, ?, ?, 'GOODS', false, 'gói', 1000) RETURNING id
                """, Long.class, category, "T3-" + SEQ.incrementAndGet(), "T3 sản phẩm " + SEQ.incrementAndGet());
    }

    private long shiftDefinition(long branch, String name, int from, int to) {
        return insert("shift_definitions", row("branch_id", branch, "name", name, "start_time", LocalTime.of(from, 0),
                "end_time", LocalTime.of(to, 0)));
    }

    private Map<String, Object> appointment(Fixture f, long service, String group) {
        return row("code", "LH-T3-" + SEQ.incrementAndGet(), "customer_id", f.customer, "pet_id", f.pet,
                "branch_id", f.branch, "service_id", service, "service_group", group, "slot_date", DAY,
                "slot_start", LocalTime.of(9, 0), "status", "BOOKED", "channel", "COUNTER",
                "booked_by", account("RECEPTIONIST"));
    }

    private long visit(Fixture f, Long appointment) {
        return insert("visits", row("code", "VT-T3-" + SEQ.incrementAndGet(), "branch_id", f.branch,
                "customer_id", f.customer, "pet_id", f.pet, "appointment_id", appointment, "service_group", "MEDICAL",
                "status", "WAITING", "priority_class", "WALK_IN", "queue_sort_key", OffsetDateTime.now(),
                "checked_in_by", account("RECEPTIONIST"), "checked_in_at", OffsetDateTime.now()));
    }

    /** Một dòng Order SERVICE trong Order bán lẻ: đủ để làm đích cho {@code order_line_id UNIQUE}. */
    private long orderLine(Fixture f, long service) {
        long staff = account("RECEPTIONIST");
        long order = jdbc.queryForObject("""
                INSERT INTO orders (code, source, customer_id, branch_id, status, created_by)
                VALUES (?, 'RETAIL', ?, ?, 'OPEN', ?) RETURNING id
                """, Long.class, "DH-T3-" + SEQ.incrementAndGet(), f.customer, f.branch, staff);
        return insert("order_lines", row("order_id", order, "line_type", "SERVICE", "service_id", service,
                "description", "Dịch vụ", "quantity", 1, "unit_price", 100_000L, "line_total", 100_000L,
                "added_by", staff, "owner_account_id", staff));
    }

    /** Một biên lai quầy 0 đồng trong ca mở mới, đủ để làm đích cho {@code orders.payment_id}. */
    private long counterPayment(Fixture f, long cashier) {
        long shift = jdbc.queryForObject("""
                INSERT INTO cashier_shifts (branch_id, cashier_id, business_date, status, opened_at)
                VALUES (?, ?, ?, 'OPEN', now()) RETURNING id
                """, Long.class, f.branch, cashier, DAY);
        return insert("payments", row("code", "PT-T3-" + SEQ.incrementAndGet(), "branch_id", f.branch,
                "customer_id", f.customer, "cashier_shift_id", shift, "method", "CASH", "amount", 0L,
                "received_by", cashier, "paid_at", OffsetDateTime.now()));
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> with(Map<String, Object> base, Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>(base);
        map.putAll(row(keyValues));
        return map;
    }

    /** INSERT chỉ các cột có giá trị khác null; trả về id. */
    private long insert(String table, Map<String, Object> columns) {
        List<String> names = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        columns.forEach((name, value) -> {
            if (value != null) {
                names.add(name);
                values.add(value);
            }
        });
        String sql = "INSERT INTO " + table + " (" + String.join(", ", names) + ") VALUES ("
                + "?, ".repeat(names.size() - 1) + "?) RETURNING id";
        return jdbc.queryForObject(sql, Long.class, values.toArray());
    }

    private void inTx(Runnable body) {
        tx.executeWithoutResult(status -> {
            body.run();
            status.setRollbackOnly();
        });
    }

    /** Câu lệnh phải bị Postgres từ chối đúng ràng buộc {@code constraint}; SAVEPOINT giữ transaction dùng tiếp được. */
    private void rejects(String constraint, Runnable statement) {
        jdbc.execute("SAVEPOINT t3");
        assertThatThrownBy(statement::run).as(constraint).hasMessageContaining("\"" + constraint + "\"");
        jdbc.execute("ROLLBACK TO SAVEPOINT t3");
    }
}
