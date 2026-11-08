package com.petcare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Kiểm tra schema do Flyway tạo trên Postgres 17 thật so với docs/05-erd.md và docs/03-state-machines.md.
 * Mọi giá trị kỳ vọng là bản sao độc lập chép từ docs, không đọc lại từ V1__init_schema.sql — nếu không,
 * test sẽ tự khẳng định chính nó.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SchemaMigrationIT {

    /**
     * erd §12 (L1043–1073): 52 model + 3 bảng con = 55 bảng, cộng 4 bảng AI của erd §14.2 / docs/07 §8 (V34, docs/adr/0110)
     * = 59, cộng 10 bảng tầng 3 phần BE-1 (V36–V39: Nhân sự 3, phẫu thuật 1, vật tư tiêu hao 1, nội trú 3, khiếu nại 2;
     * docs/adr/0113), cộng 19 bảng tầng 3 phần BE-2 (V43–V48: phí 3, thương mại điện tử 4, thanh toán 3, trả hàng 3,
     * kho 4, gộp khách 1, bình luận 1; docs/adr/0117) = 88.
     */
    private static final Set<String> ERD_TABLES = Set.of(
            "accounts", "staff_profiles", "otp_tokens", "sessions", "audit_logs", "system_configs",
            "notification_templates", "branches", "opening_hours", "holidays", "branch_services",
            "branch_quota_defaults", "slot_quotas", "customers", "addresses", "pets", "weight_records",
            "product_categories", "products", "services", "kennel_types", "vaccine_types", "vaccination_protocols",
            "appointments", "booking_restrictions", "visits", "visit_assignments", "medical_records",
            "medical_record_addenda", "prescription_items", "vaccinations", "kennels", "boarding_bookings",
            "boarding_check_ins", "care_logs", "care_log_addenda", "orders", "order_lines", "payments",
            "cashier_shifts", "suppliers", "inventory_items", "stock_lots", "stock_receipts", "stock_receipt_lines",
            "stock_adjustments", "stock_adjustment_lines", "stock_movements", "article_categories", "articles",
            "page_contents", "feedbacks", "care_tasks", "notifications", "notification_outbox",
            "ai_documents", "ai_chunks", "ai_chat_logs", "ai_item_profiles",
            "shift_definitions", "shift_assignments", "shift_requests", "surgeries", "visit_material_usages",
            "admissions", "admission_orders", "admission_observations", "complaints", "complaint_messages",
            "shipping_fee_configs", "branch_home_visit_fees", "service_material_norms", "carts", "cart_items",
            "online_orders", "stock_reservations", "payment_transactions", "payment_reconciliations",
            "reconciliation_items", "return_requests", "return_request_lines", "refunds", "stock_transfers",
            "stock_transfer_lines", "stock_counts", "stock_count_lines", "customer_merges", "article_comments");

    /** Bảng loại LOG trong erd: chỉ có {@code created_at} (erd §0 L14). */
    private static final Set<String> LOG_TABLES = Set.of(
            "otp_tokens", "audit_logs", "weight_records", "visit_assignments", "medical_record_addenda",
            "care_log_addenda", "stock_movements", "notification_outbox", "ai_chat_logs",
            "admission_observations", "complaint_messages", "visit_material_usages", "customer_merges");

    /** Bảng có khóa chính khác {@code id}: 1–1 dùng FK làm PK, PK ghép, PK chuỗi. */
    private static final Set<String> TABLES_WITHOUT_ID = Set.of(
            "staff_profiles", "kennel_types", "medical_records", "boarding_check_ins",
            "branch_services", "branch_quota_defaults", "system_configs", "notification_templates", "page_contents",
            "branch_home_visit_fees");

    /** erd §0 L20: tiền là BIGINT. */
    private static final List<String> MONEY_COLUMNS = List.of(
            "products.price", "services.price", "boarding_bookings.nightly_price", "orders.total_amount",
            "order_lines.unit_price", "order_lines.line_total", "cashier_shifts.expected_cash",
            "cashier_shifts.expected_transfer", "cashier_shifts.counted_cash", "cashier_shifts.difference",
            "payments.amount", "stock_receipt_lines.unit_cost",
            "shipping_fee_configs.base_fee", "shipping_fee_configs.free_threshold", "branch_home_visit_fees.fee",
            "online_orders.shipping_fee", "payment_transactions.amount", "return_requests.refund_amount",
            "refunds.amount", "reconciliation_items.our_amount", "reconciliation_items.gateway_amount");

    /** erd §0 L23: giờ trong ngày là TIME. */
    private static final List<String> TIME_COLUMNS = List.of(
            "opening_hours.open_1", "opening_hours.close_1", "opening_hours.open_2", "opening_hours.close_2",
            "slot_quotas.slot_start", "appointments.slot_start");

    /** erd §0 L26: mã chứng từ hiển thị VARCHAR(20) UNIQUE. */
    private static final List<String> DOCUMENT_CODE_TABLES = List.of(
            "appointments", "visits", "boarding_bookings", "orders", "payments", "stock_receipts", "stock_adjustments",
            "admissions", "complaints",
            "online_orders", "return_requests", "refunds", "stock_transfers", "stock_counts");

    /** Tập trạng thái theo docs/03-state-machines.md (SM#1–#10) và 02 (BR-BV-02, BR-DG-04), mã ASCII theo erd L28–37. */
    private static final Map<String, Set<String>> STATE_COLUMNS = new LinkedHashMap<>();

    static {
        STATE_COLUMNS.put("accounts.role", Set.of("CUSTOMER", "ADMIN", "SUPER_MANAGER", "BRANCH_MANAGER",
                "RECEPTIONIST", "VET", "CARETAKER"));
        STATE_COLUMNS.put("accounts.status", Set.of("PENDING", "ACTIVE", "DISABLED"));
        STATE_COLUMNS.put("branches.status", Set.of("DRAFT", "ACTIVE", "SUSPENDED", "CLOSED")); // V48, 08 §F.1 FSM #20
        STATE_COLUMNS.put("appointments.status", Set.of("BOOKED", "CHECKED_IN", "COMPLETED", "CANCELLED", "NO_SHOW"));
        STATE_COLUMNS.put("visits.status", Set.of("WAITING", "IN_PROGRESS", "COMPLETED", "CANCELLED"));
        STATE_COLUMNS.put("orders.status", Set.of("OPEN", "PENDING", "PAID", "CANCELLED"));
        STATE_COLUMNS.put("orders.source", Set.of("VISIT", "RETAIL", "BOARDING", "ONLINE")); // ONLINE: V42, docs/08 §B
        STATE_COLUMNS.put("boarding_bookings.status", Set.of("BOOKED", "CHECKED_IN", "OVERDUE", "CHECKED_OUT",
                "CANCELLED", "NO_SHOW"));
        STATE_COLUMNS.put("kennels.status", Set.of("AVAILABLE", "OCCUPIED", "MAINTENANCE"));
        STATE_COLUMNS.put("cashier_shifts.status", Set.of("OPEN", "CLOSED", "RECONCILED"));
        STATE_COLUMNS.put("stock_receipts.status", Set.of("DRAFT", "CONFIRMED", "CANCELLED"));
        STATE_COLUMNS.put("care_tasks.status", Set.of("OPEN", "DONE", "CANCELLED"));
        STATE_COLUMNS.put("care_tasks.task_type", Set.of("VACCINE_DUE", "VACCINE_OVERDUE", "FOLLOW_UP_DUE",
                "PICKUP_OVERDUE"));
        STATE_COLUMNS.put("articles.status", Set.of("DRAFT", "PUBLISHED", "HIDDEN", "PENDING_REVIEW", "REJECTED")); // V48, 08 §F.3 FSM #21
        STATE_COLUMNS.put("feedbacks.status", Set.of("NEW", "SEEN", "RESOLVED"));
        // Tầng 3 phần BE-1 (docs/03 #11, #12, docs/09 FSM #23–#26)
        STATE_COLUMNS.put("shift_assignments.status", Set.of("SCHEDULED", "ON_LEAVE", "ABSENT", "CANCELLED"));
        STATE_COLUMNS.put("shift_requests.status", Set.of("PENDING", "APPROVED", "REJECTED", "WITHDRAWN"));
        STATE_COLUMNS.put("surgeries.status", Set.of("PLANNED", "CONSENTED", "IN_PROGRESS", "COMPLETED", "CANCELLED"));
        STATE_COLUMNS.put("admissions.status", Set.of("ADMITTED", "DISCHARGED"));
        STATE_COLUMNS.put("admission_orders.status", Set.of("ORDERED", "DONE", "CANCELLED"));
        STATE_COLUMNS.put("complaints.status", Set.of("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"));
        // Tầng 3 phần BE-2 (T55c, docs/08-tang3-be2-spec.md): FSM #14–#19, §A–§F
        STATE_COLUMNS.put("online_orders.status", Set.of("PENDING_PAYMENT", "PAID", "PROCESSING", "SHIPPED",
                "DELIVERY_FAILED", "DELIVERED", "COMPLETED", "CANCELLED"));
        STATE_COLUMNS.put("stock_reservations.status", Set.of("HELD", "CONSUMED", "RELEASED"));
        STATE_COLUMNS.put("payment_transactions.status", Set.of("PENDING", "SUCCEEDED", "FAILED", "EXPIRED", "CANCELLED"));
        STATE_COLUMNS.put("payment_reconciliations.status", Set.of("RUNNING", "MATCHED", "MISMATCHED", "FAILED"));
        STATE_COLUMNS.put("reconciliation_items.kind", Set.of("MISSING_LOCAL", "MISSING_GATEWAY", "AMOUNT_DIFF", "STATUS_DIFF"));
        STATE_COLUMNS.put("return_requests.status", Set.of("PENDING", "APPROVED", "REJECTED", "CANCELLED", "REFUNDED"));
        STATE_COLUMNS.put("return_requests.reason_code", Set.of("DEFECTIVE", "WRONG_ITEM", "CHANGED_MIND", "OTHER"));
        STATE_COLUMNS.put("refunds.status", Set.of("REQUESTED", "SUCCEEDED", "FAILED"));
        STATE_COLUMNS.put("refunds.method", Set.of("CASH", "TRANSFER", "GATEWAY"));
        STATE_COLUMNS.put("stock_transfers.status", Set.of("IN_TRANSIT", "RECEIVED", "CANCELLED"));
        STATE_COLUMNS.put("stock_counts.status", Set.of("OPEN", "CONFIRMED", "CANCELLED"));
        STATE_COLUMNS.put("stock_counts.scope", Set.of("ALL", "CATEGORY"));
        STATE_COLUMNS.put("article_comments.status", Set.of("ACTIVE", "HIDDEN", "DELETED"));
        // Ba CHECK của kho nới ở V47 (05 §13 mục 26)
        STATE_COLUMNS.put("stock_movements.movement_type", Set.of("RECEIPT", "RECEIPT_CANCEL", "SALE", "VACCINATION",
                "VACCINATION_REVERT", "ADJUSTMENT", "TRANSFER_OUT", "TRANSFER_IN", "TRANSFER_CANCEL"));
        STATE_COLUMNS.put("stock_movements.source_type", Set.of("stock_receipts", "orders", "vaccinations",
                "stock_adjustments", "stock_transfers"));
        STATE_COLUMNS.put("stock_adjustments.reason", Set.of("DAMAGED", "EXPIRED", "COUNT_MISMATCH", "OTHER",
                "COUNT_DIFF", "RETURN_DISCARD"));
    }

    private static final String SINGLE_COLUMN_CHECKS = """
            SELECT c.conrelid::regclass::text AS tbl, a.attname AS col, pg_get_constraintdef(c.oid) AS def
            FROM pg_constraint c
            JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
            WHERE c.contype = 'c' AND c.connamespace = 'public'::regnamespace AND array_length(c.conkey, 1) = 1
            """;

    /** Postgres hiển thị {@code col IN (...)} của cột VARCHAR thành {@code col::text = ANY ((ARRAY[...])::text[])}. */
    private static final Pattern ENUM_CHECK = Pattern.compile("= ANY \\(+ARRAY\\[");

    private static final Pattern CHECK_LITERAL = Pattern.compile("'([^']+)'::");

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private JdbcTemplate jdbc;

    // ---------------------------------------------------------------- D0–D5, D8, D9: catalog

    @Test
    void flywayAppliedAllMigrationsSuccessfully() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");

        // V1 schema, V2 seed system_configs (docs/adr/0004), V3 seed mẫu OTP_REGISTER (06 §8 Q4),
        // V4 index luồng gửi + câu chào OTP_REGISTER (docs/adr/0012), V5 hạn PENDING cho ST02 (docs/adr/0013),
        // V6 index partial luồng IN_APP (docs/adr/0014), V7 index cho FK trỏ tới accounts (docs/adr/0018),
        // V8 seed mẫu OTP_PASSWORD_RESET, LOGIN_LOCKED_WARNING, PASSWORD_CHANGED (docs/adr/0019),
        // V9 mẫu OTP_PROFILE_LINK + index otp_tokens.customer_id (docs/adr/0025),
        // V10 index addresses.customer_id (docs/adr/0028),
        // V11 mẫu STAFF_TEMP_PASSWORD, OTP_EMAIL_CHANGE, EMAIL_CHANGED_NOTICE (docs/adr/0029),
        // V12 mẫu ACCOUNT_LOCKED_FOLLOW_UP_APP, LAST_BRANCH_MANAGER_LOCKED_APP (docs/adr/0035),
        // V13 index FK trỏ tới pets / customers còn thiếu — trả nợ D013 (docs/adr/0041-customer-pet-management),
        // V14 bảng đếm mã chứng từ + index module kho (docs/adr/0042-inventory…, 0043-document-code…),
        // V15 index order_lines.order_id (docs/adr/0044-order-core…),
        // V16 sequence mã lịch hẹn appointment_code_seq (docs/adr/0045-appointment-booking-locks; V13 trước khi gộp nhánh),
        // V17 mẫu APPOINTMENT_CANCELLED_BY_CLINIC (+_APP) (docs/adr/0052),
        // V18 mẫu APPOINTMENT_REMINDER (+_APP) (docs/adr/0056),
        // V19 index thu ngân + mẫu ST13 CASHIER_SHIFT_AUTO_CLOSED_APP, PENDING_ORDERS_DIGEST_APP (docs/adr/0064),
        // V20 index ST05 lưu trú + mẫu BOARDING_CANCELLED_BY_CLINIC (+_APP) (docs/adr/0065),
        // V21 seed 4 trang tĩnh page_contents (docs/adr/0071),
        // V22 mẫu nhắc VACCINE_REMINDER, FOLLOW_UP_REMINDER (+_APP) + index / CHECK care_tasks (docs/adr/0081, 0082),
        // V23 cờ ST18 vaccinations.overdue_handled_at + index quét (docs/adr/0083),
        // V24 mẫu CARE_LOG_ABNORMAL (+_APP), BOARDING_NO_KENNEL_APP + 2 index nhận thú / nhật ký (docs/adr/0085),
        // V25 mẫu BOARDING_OVERDUE (+_APP), BOARDING_OVERDUE_MANAGER_APP, BOARDING_CAPACITY_WARNING_APP + 3 index trả thú / ST15 (docs/adr/0086),
        // V26 mẫu STOCK_ALERT_DIGEST_APP (ST08, docs/adr/0087),
        // V27 refresh_tokens + sessions.access_expires_at, bỏ accounts.last_seen_at, [CFG] session.access_ttl_minutes
        // (docs/adr/0091), V28 4 index FK bệnh án / mũi tiêm + CHECK lý do mua ngoài (docs/adr/0093, trả D043),
        // V29 index lịch hẹn theo chi nhánh × ngày (docs/adr/0094, trả D032),
        // V30 index lượt tiếp nhận theo chi nhánh × ngày check-in (docs/adr/0095, trả D041),
        // V31 index audit_logs (action, created_at) + gỡ 2 trigger chỉ-thêm-mới của audit_logs (docs/adr/0098, trả D021),
        // V32 index thứ tự danh sách thông báo (account_id, created_at DESC, id DESC) (docs/adr/0099, trả D022),
        // V33 tìm khách / thú theo tên không dấu: unaccent + pg_trgm, f_unaccent, 2 index GIN (docs/adr/0104, trả T17-D05),
        // V34 extension pgvector + 4 bảng AI ai_documents / ai_chunks / ai_chat_logs / ai_item_profiles (docs/adr/0110),
        // V35 seed 18 tham số [CFG] ai.* (docs/adr/0110),
        // V36 bảng Nhân sự, V37 khám tại nhà + phẫu thuật + vật tư tiêu hao, V38 nội trú, V39 khiếu nại (docs/adr/0113),
        // V40 seed 8 tham số [CFG] complaint.* / workforce.*, V41 giá trị mới medical_type / line_type,
        // V42 Order nguồn ONLINE: 4 CHECK của orders (docs/adr/0116, làm thay BE-2),
        // V43 phí vận chuyển / phí khám tại nhà / định mức vật tư (docs/adr/0117, T55c),
        // V44 giỏ hàng, đơn online, giữ hàng + 3 khóa shop.*, V45 giao dịch thanh toán, đối soát + 2 khóa payment.*,
        // V46 trả hàng, hoàn tiền + returns.window_days, V47 chuyển kho, kiểm kê + 3 CHECK của kho,
        // V48 chi nhánh tạm ngừng / đóng, gộp khách, bài viết của bác sĩ, bình luận + content.comment_per_hour,
        // V50 index refresh_tokens (expires_at, id) cho job dọn refresh token (docs/adr/0114, trả D054; V49 để dành cho BE-2),
        // V9000 chỉ ở test: baseline session.access_ttl_minutes = 720
        assertThat(rows).extracting(row -> row.get("version"))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15",
                        "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30",
                        "31", "32", "33", "34", "35", "36", "37", "38", "39", "40", "41", "42", "43", "44", "45", "46", "47", "48", "50", "9000");
        assertThat(rows).allSatisfy(row -> assertThat(row).containsEntry("success", true));
    }

    /** V16 (docs/adr/0045): 10 chữ số giữ mã lịch hẹn ≤ 20 ký tự; không quay vòng nên hết số thì lỗi, không trùng mã. */
    @Test
    void appointmentCodeSequenceIsBoundedAndDoesNotCycle() {
        assertThat(jdbc.queryForMap("""
                SELECT data_type::text AS data_type, min_value, max_value, increment_by, cycle
                FROM pg_sequences WHERE schemaname = 'public' AND sequencename = 'appointment_code_seq'"""))
                .containsEntry("data_type", "bigint")
                .containsEntry("min_value", 1L)
                .containsEntry("max_value", 9_999_999_999L)
                .containsEntry("increment_by", 1L)
                .containsEntry("cycle", false);
    }

    @Test
    void createsExactlyTheErdTables() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'
                """, String.class);

        assertThat(ERD_TABLES).hasSize(88);
        // document_counters (V14, ADR-0043) và refresh_tokens (V27, ADR-0091) là bảng hạ tầng ngoài ERD v16
        // (erd 13 muc 18)
        assertThat(tables).containsExactlyInAnyOrderElementsOf(
                java.util.stream.Stream.concat(ERD_TABLES.stream(),
                        java.util.stream.Stream.of("document_counters", "refresh_tokens")).toList());
    }

    @Test
    void commonTimestampColumnsMatchTableKind() {
        assertThat(LOG_TABLES).hasSize(13);
        for (String table : ERD_TABLES) {
            Map<String, Column> columns = columns(table);
            assertCommonTimestamp(table, columns.get("created_at"));
            if (LOG_TABLES.contains(table)) {
                assertThat(columns).as("%s là LOG, không có updated_at", table).doesNotContainKey("updated_at");
            } else {
                assertCommonTimestamp(table, columns.get("updated_at"));
            }
        }
    }

    @Test
    void everyForeignKeyIsOnDeleteRestrict() {
        List<Map<String, Object>> foreignKeys = jdbc.queryForList("""
                SELECT conname, confdeltype::text AS on_delete FROM pg_constraint
                WHERE contype = 'f' AND connamespace = 'public'::regnamespace
                """);

        assertThat(foreignKeys).isNotEmpty();
        assertThat(foreignKeys).allSatisfy(fk ->
                assertThat(fk.get("on_delete")).as("%s phải ON DELETE RESTRICT", fk.get("conname")).isEqualTo("r"));
        // [ERD 9] audit_logs.actor_account_id không có FK
        Integer auditForeignKeys = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE contype = 'f' AND conrelid = 'audit_logs'::regclass",
                Integer.class);
        assertThat(auditForeignKeys).isZero();
    }

    @Test
    void surrogateIdsAreIdentityAlways() {
        assertThat(ERD_TABLES).containsAll(TABLES_WITHOUT_ID);
        for (String table : ERD_TABLES) {
            Column id = columns(table).get("id");
            if (TABLES_WITHOUT_ID.contains(table)) {
                assertThat(id).as("%s không có cột id", table).isNull();
            } else {
                assertThat(id).as("%s.id", table).isNotNull();
                assertThat(id.dataType()).as("%s.id", table).isEqualTo("bigint");
                assertThat(id.identity()).as("%s.id", table).isEqualTo("YES");
                assertThat(id.identityGeneration()).as("%s.id", table).isEqualTo("ALWAYS");
            }
        }
    }

    @Test
    void enumCheckColumnsAreVarchar30() {
        List<Map<String, Object>> enumChecks = jdbc.queryForList(SINGLE_COLUMN_CHECKS).stream()
                .filter(row -> ENUM_CHECK.matcher((String) row.get("def")).find())
                .toList();

        assertThat(enumChecks).isNotEmpty();
        for (Map<String, Object> check : enumChecks) {
            String table = (String) check.get("tbl");
            String column = (String) check.get("col");
            Column info = columns(table).get(column);
            assertThat(info.dataType()).as("%s.%s", table, column).isEqualTo("character varying");
            assertThat(info.maxLength()).as("%s.%s", table, column).isEqualTo(30);
        }

        List<String> allCheckDefinitions = jdbc.queryForList("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE contype = 'c' AND connamespace = 'public'::regnamespace
                """, String.class);
        assertThat(allCheckDefinitions).allSatisfy(def ->
                assertThat(def.chars().allMatch(ch -> ch < 128)).as("CHECK chỉ dùng mã ASCII: %s", def).isTrue());
    }

    @Test
    void columnTypesFollowErdConventions() {
        List<String> forbidden = jdbc.queryForList("""
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
                  AND data_type IN ('timestamp without time zone', 'json', 'real', 'double precision', 'money')
                """, String.class);
        assertThat(forbidden).isEmpty();

        for (String money : MONEY_COLUMNS) {
            assertThat(column(money).dataType()).as(money).isEqualTo("bigint");
        }
        for (String weight : List.of("weight_records.weight_kg", "kennel_types.max_weight_kg")) {
            Column info = column(weight);
            assertThat(info.dataType()).as(weight).isEqualTo("numeric");
            assertThat(info.precision()).as(weight).isEqualTo(6);
            assertThat(info.scale()).as(weight).isEqualTo(2);
        }
        for (String time : TIME_COLUMNS) {
            assertThat(column(time).dataType()).as(time).isEqualTo("time without time zone");
        }
        for (String table : DOCUMENT_CODE_TABLES) {
            Column code = columns(table).get("code");
            assertThat(code.dataType()).as("%s.code", table).isEqualTo("character varying");
            assertThat(code.maxLength()).as("%s.code", table).isEqualTo(20);
            Integer uniques = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_constraint c
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                    WHERE c.contype = 'u' AND c.conrelid = ?::regclass
                      AND array_length(c.conkey, 1) = 1 AND a.attname = 'code'
                    """, Integer.class, table);
            assertThat(uniques).as("%s.code UNIQUE", table).isEqualTo(1);
        }
    }

    @Test
    void stateColumnsMatchStateMachines() {
        Map<String, Set<String>> actual = new HashMap<>();
        for (Map<String, Object> check : jdbc.queryForList(SINGLE_COLUMN_CHECKS)) {
            String def = (String) check.get("def");
            if (!ENUM_CHECK.matcher(def).find()) {
                continue;
            }
            Set<String> values = new TreeSet<>();
            Matcher matcher = CHECK_LITERAL.matcher(def);
            while (matcher.find()) {
                values.add(matcher.group(1));
            }
            actual.put(check.get("tbl") + "." + check.get("col"), values);
        }

        STATE_COLUMNS.forEach((column, expected) ->
                assertThat(actual.get(column)).as(column).containsExactlyInAnyOrderElementsOf(expected));
    }

    /**
     * erd §1 {@code sessions} (L121, L127): {@code token_hash} UNIQUE; index các phiên còn hiệu lực theo tài khoản
     * ({@code (account_id) WHERE revoked_at IS NULL}). Không dựa vào tên index vì erd không đặt tên.
     */
    @Test
    void sessionsHaveUniqueTokenHashAndActiveSessionIndex() {
        Integer uniques = jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint c
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                WHERE c.contype = 'u' AND c.conrelid = 'sessions'::regclass
                  AND array_length(c.conkey, 1) = 1 AND a.attname = 'token_hash'
                """, Integer.class);
        assertThat(uniques).as("sessions.token_hash UNIQUE").isEqualTo(1);

        List<String> indexes = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'sessions'", String.class);
        assertThat(indexes).as("sessions index (account_id) WHERE revoked_at IS NULL")
                .anySatisfy(def -> assertThat(def).contains("(account_id)").contains("WHERE (revoked_at IS NULL)"));
    }

    /**
     * V5, erd §13 mục 12 (docs/adr/0013): {@code accounts.pending_expires_at} có giá trị ⇔ {@code PENDING}; index
     * partial cho query của ST02; index {@code otp_tokens(account_id)} cho câu xóa và kiểm FK khi xóa tài khoản.
     */
    @Test
    void accountsPendingExpiryIsSetExactlyForPendingAccounts() {
        Timestamp expiry = Timestamp.from(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(column("accounts.pending_expires_at").dataType()).isEqualTo("timestamp with time zone");

        assertThatThrownBy(() -> customerAccount("PENDING", null))
                .as("PENDING thiếu hạn").isInstanceOf(DataAccessException.class)
                .satisfies(ex -> assertThat(sqlState(ex)).isEqualTo("23514"));
        assertThatThrownBy(() -> customerAccount("ACTIVE", expiry))
                .as("ACTIVE còn hạn").isInstanceOf(DataAccessException.class)
                .satisfies(ex -> assertThat(sqlState(ex)).isEqualTo("23514"));
        customerAccount("PENDING", expiry);
        customerAccount("ACTIVE", null);

        assertThat(jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'accounts'", String.class))
                .anySatisfy(def -> assertThat(def).contains("(pending_expires_at)")
                        .contains("WHERE ((status)::text = 'PENDING'::text)"));
        assertThat(jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'otp_tokens'", String.class))
                .anySatisfy(def -> assertThat(def).endsWith("(account_id)"));
    }

    /**
     * V6, erd §13 mục 13 (docs/adr/0014): index partial cho luồng IN_APP của ST20 — chỉ dòng IN_APP chưa giao, để câu
     * chọn không đọc qua tồn đọng email.
     */
    @Test
    void notificationOutboxHasPartialIndexForInAppLane() {
        assertThat(jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                String.class, "ix_notification_outbox_in_app_due"))
                .singleElement().asString()
                .contains("ON public.notification_outbox")
                .contains("(next_attempt_at, id)")
                .contains("WHERE (((status)::text = 'PENDING'::text) AND ((channel)::text = 'IN_APP'::text))");
    }

    /**
     * V22 (docs/adr/0081, trả D049): hai index một cột cho câu hủy hệ thống của Care Task và câu kiểm FK khi xóa mũi
     * tiêm ghi nhầm — không lọc {@code status} / {@code task_type} nên {@code WHERE vaccination_id = $1} dùng được.
     */
    @Test
    void careTasksHaveSourceIndexesForSystemCancelAndForeignKeyChecks() {
        assertThat(jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                String.class, "ix_care_tasks_vaccination_id"))
                .singleElement().asString()
                .contains("ON public.care_tasks").contains("(vaccination_id)")
                .contains("WHERE (vaccination_id IS NOT NULL)").doesNotContain("status").doesNotContain("task_type");
        assertThat(jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                String.class, "ix_care_tasks_medical_record_id"))
                .singleElement().asString()
                .contains("ON public.care_tasks").contains("(medical_record_id)")
                .contains("WHERE (medical_record_id IS NOT NULL)").doesNotContain("status")
                .doesNotContain("task_type");
    }

    /**
     * V23 (docs/adr/0083): cờ ST18 là {@code timestamptz} cho phép NULL (NULL = chưa xét, không backfill) và index quét
     * keyset chỉ chứa mũi còn chờ xét.
     */
    @Test
    void vaccinationsHaveTheSt18FlagAndItsScanIndex() {
        Column flag = column("vaccinations.overdue_handled_at");
        assertThat(flag.dataType()).isEqualTo("timestamp with time zone");
        assertThat(jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'vaccinations' AND column_name = 'overdue_handled_at'
                """, String.class)).isEqualTo("YES");
        assertThat(jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                String.class, "ix_vaccinations_overdue_scan"))
                .singleElement().asString()
                .contains("ON public.vaccinations").contains("(next_due_date, id)")
                .contains("superseded_at IS NULL").contains("overdue_handled_at IS NULL");
    }

    /**
     * V28 (docs/adr/0093, trả D043 nhóm 1): bốn cột FK của bệnh án / mũi tiêm có index một cột, không partial (cột đều
     * NOT NULL) để đọc theo bệnh án, {@code existsByVaccineTypeId} và kiểm FK khi xóa dòng cha không quét bảng.
     */
    @Test
    void medicalRecordAndVaccinationForeignKeysHaveSingleColumnIndexes() {
        record Expected(String index, String table, String column) {
        }
        List<Expected> expected = List.of(
                new Expected("ix_medical_record_addenda_medical_record_id", "medical_record_addenda",
                        "medical_record_id"),
                new Expected("ix_prescription_items_medical_record_id", "prescription_items", "medical_record_id"),
                new Expected("ix_vaccinations_visit_id", "vaccinations", "visit_id"),
                new Expected("ix_vaccinations_vaccine_type_id", "vaccinations", "vaccine_type_id"));
        for (Expected e : expected) {
            assertThat(jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                    String.class, e.index()))
                    .as(e.index()).singleElement().asString()
                    .contains("ON public." + e.table()).contains("(" + e.column() + ")").doesNotContain("WHERE");
        }
    }

    /**
     * V29 (docs/adr/0094, trả D032): index danh sách lịch hẹn của chi nhánh bắt đầu bằng (branch_id, slot_date), không
     * partial (phủ cả CANCELLED, NO_SHOW); hai index cũ (quota partial, status_slot của ST03 / ST05) còn nguyên.
     */
    @Test
    void appointmentBranchDayIndexCoversEveryStatus() {
        assertThat(jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'ix_appointments_branch_slot'",
                String.class))
                .singleElement().asString()
                .contains("ON public.appointments").contains("(branch_id, slot_date, slot_start)")
                .doesNotContain("WHERE");
        assertThat(jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'appointments'"
                        + " AND indexname IN ('ix_appointments_quota', 'ix_appointments_status_slot')",
                String.class))
                .containsExactlyInAnyOrder("ix_appointments_quota", "ix_appointments_status_slot");
    }

    /**
     * V30 (docs/adr/0095, trả D041): index lượt tiếp nhận của chi nhánh bắt đầu bằng (branch_id, checked_in_at), không
     * partial (phủ mọi trạng thái); năm index cũ của {@code visits} còn nguyên.
     */
    @Test
    void visitBranchCheckedInAtIndexCoversEveryStatus() {
        assertThat(jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'ix_visits_branch_checked_in_at'",
                String.class))
                .singleElement().asString()
                .contains("ON public.visits").contains("(branch_id, checked_in_at)")
                .doesNotContain("WHERE");
        assertThat(jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'visits'"
                        + " AND indexname IN ('ix_visits_queue', 'ix_visits_assignee_status',"
                        + " 'ix_visits_pet_checked_in_at', 'ix_visits_checked_in_by', 'ix_visits_customer_id')",
                String.class))
                .containsExactlyInAnyOrder("ix_visits_queue", "ix_visits_assignee_status",
                        "ix_visits_pet_checked_in_at", "ix_visits_checked_in_by", "ix_visits_customer_id");
    }

    /**
     * V32 (docs/adr/0099, trả D022): index thứ tự danh sách thông báo (account_id, created_at DESC, id DESC), không
     * partial; index V1 {@code ix_notifications_account_read_created} còn nguyên cho countUnread / markAllRead.
     */
    @Test
    void notificationAccountCreatedIndexKeepsTheV1Index() {
        assertThat(jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'ix_notifications_account_created'",
                String.class))
                .singleElement().asString()
                .contains("ON public.notifications").contains("(account_id, created_at DESC, id DESC)")
                .doesNotContain("WHERE");
        assertThat(jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public'"
                        + " AND indexname = 'ix_notifications_account_read_created'",
                String.class))
                .singleElement().asString()
                .contains("(account_id, read_at, created_at DESC)");
    }

    /**
     * V28 (docs/adr/0093, trả D043 nhóm 3): CHECK buộc lý do mua ngoài theo cờ mua ngoài; ca vi phạm ở
     * {@code PrescriptionItemConstraintIT}.
     */
    @Test
    void prescriptionItemsTieTheExternalReasonToTheExternalFlag() {
        assertThat(jdbc.queryForList("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conrelid = 'prescription_items'::regclass AND conname = 'ck_prescription_items_external_reason_tied'
                """, String.class)).singleElement().asString()
                .contains("is_external_purchase").contains("external_reason IS NOT NULL");
    }

    /**
     * ST18 và ST04 dựa vào việc lỗi DB nổi ngay ở câu ghi, trước lúc COMMIT (docs/adr/0083 mục RT): schema không có ràng
     * buộc {@code DEFERRABLE}. Constraint trigger tạm của IT khác (contype {@code t}) không tính.
     */
    @Test
    void noConstraintIsDeferrable() {
        assertThat(jdbc.queryForList("""
                SELECT conrelid::regclass || '.' || conname FROM pg_constraint
                WHERE connamespace = 'public'::regnamespace AND condeferrable AND contype <> 't'
                """, String.class)).isEmpty();
    }

    /** V22 (docs/adr/0081): ba CHECK trạng thái ↔ cột xử lý của {@code care_tasks} có mặt; ca vi phạm ở {@code CareTaskConstraintIT}. */
    @Test
    void careTasksHaveStatusHandlingChecks() {
        assertThat(jdbc.queryForList("""
                SELECT conname FROM pg_constraint
                WHERE conrelid = 'care_tasks'::regclass AND contype = 'c' AND conname IN
                  ('ck_care_tasks_open_unhandled', 'ck_care_tasks_done_handled', 'ck_care_tasks_cancelled_reason')
                """, String.class))
                .containsExactlyInAnyOrder("ck_care_tasks_open_unhandled", "ck_care_tasks_done_handled",
                        "ck_care_tasks_cancelled_reason");
    }

    /**
     * V3, V4, V8, V9, V11, V12, V17–V20, V22 (06-module-contracts §8 Q4, BR-QT-14): mẫu seed bằng migration khôi phục được về mặc định
     * ({@code body = default_body}, {@code subject = default_subject}), biến bắt buộc nằm trong biến cho phép và có mặt
     * trong nội dung, mẫu OTP bắt buộc {@code {ma_otp}}. Mẫu {@code IT_*} do các IT khác chèn nên bị loại.
     */
    @Test
    void seededNotificationTemplatesAreRestorableAndConsistent() {
        String seeded = "code NOT LIKE 'IT\\_%'";

        assertThat(jdbc.queryForList("SELECT code FROM notification_templates WHERE " + seeded, String.class))
                .containsExactlyInAnyOrder("OTP_REGISTER", "OTP_PASSWORD_RESET", "LOGIN_LOCKED_WARNING",
                        "PASSWORD_CHANGED", "OTP_PROFILE_LINK", "STAFF_TEMP_PASSWORD", "OTP_EMAIL_CHANGE",
                        "EMAIL_CHANGED_NOTICE", "ACCOUNT_LOCKED_FOLLOW_UP_APP", "LAST_BRANCH_MANAGER_LOCKED_APP",
                        "APPOINTMENT_CANCELLED_BY_CLINIC", "APPOINTMENT_CANCELLED_BY_CLINIC_APP",
                        "APPOINTMENT_REMINDER", "APPOINTMENT_REMINDER_APP", "CASHIER_SHIFT_AUTO_CLOSED_APP",
                        "PENDING_ORDERS_DIGEST_APP", "BOARDING_CANCELLED_BY_CLINIC", "BOARDING_CANCELLED_BY_CLINIC_APP",
                        "VACCINE_REMINDER", "VACCINE_REMINDER_APP", "FOLLOW_UP_REMINDER", "FOLLOW_UP_REMINDER_APP",
                        "CARE_LOG_ABNORMAL", "CARE_LOG_ABNORMAL_APP", "BOARDING_NO_KENNEL_APP",
                        "BOARDING_OVERDUE", "BOARDING_OVERDUE_APP", "BOARDING_OVERDUE_MANAGER_APP",
                        "BOARDING_CAPACITY_WARNING_APP", "STOCK_ALERT_DIGEST_APP",
                        // V40 (docs/adr/0113): 40 mẫu tầng 3 — NS 9, khiếu nại 7, vật tư 1, đơn online 10, thanh toán 4,
                        // trả hàng + hoàn tiền 6, duyệt bài viết 3
                        "SHIFT_REQUEST_SUBMITTED_APP", "SHIFT_REQUEST_APPROVED", "SHIFT_REQUEST_APPROVED_APP",
                        "SHIFT_REQUEST_REJECTED", "SHIFT_REQUEST_REJECTED_APP", "SHIFT_ASSIGNMENT_CANCELLED",
                        "SHIFT_ASSIGNMENT_CANCELLED_APP", "SHIFT_ABSENCE_RECORDED", "SHIFT_ABSENCE_RECORDED_APP",
                        "COMPLAINT_CREATED_APP", "COMPLAINT_TAKEN", "COMPLAINT_TAKEN_APP", "COMPLAINT_RESOLVED",
                        "COMPLAINT_RESOLVED_APP", "COMPLAINT_REOPENED_APP", "COMPLAINT_ESCALATED_APP",
                        "MATERIAL_SHORTAGE_APP",
                        "ONLINE_ORDER_PLACED", "ONLINE_ORDER_PLACED_APP", "ONLINE_ORDER_PAID", "ONLINE_ORDER_PAID_APP",
                        "ONLINE_ORDER_SHIPPED", "ONLINE_ORDER_SHIPPED_APP", "ONLINE_ORDER_DELIVERY_FAILED",
                        "ONLINE_ORDER_DELIVERY_FAILED_APP", "ONLINE_ORDER_CANCELLED", "ONLINE_ORDER_CANCELLED_APP",
                        "PAYMENT_FAILED", "PAYMENT_FAILED_APP", "PAYMENT_EXPIRED", "PAYMENT_EXPIRED_APP",
                        "RETURN_REQUESTED_APP", "RETURN_APPROVED_APP", "RETURN_REJECTED_APP", "REFUND_REQUESTED",
                        "REFUND_REQUESTED_APP", "REFUND_FAILED_APP",
                        "ARTICLE_REVIEW_SUBMITTED_APP", "ARTICLE_REVIEW_APPROVED_APP", "ARTICLE_REVIEW_REJECTED_APP");
        assertThat(jdbc.queryForList("""
                SELECT code FROM notification_templates
                WHERE %s AND (body <> default_body OR subject IS DISTINCT FROM default_subject)
                """.formatted(seeded), String.class))
                .as("mẫu không khôi phục được về mặc định").isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT code FROM notification_templates
                WHERE %s AND NOT (allowed_vars @> required_vars)
                """.formatted(seeded), String.class))
                .as("biến bắt buộc ngoài biến cho phép").isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT t.code || ':' || v FROM notification_templates t, jsonb_array_elements_text(t.required_vars) v
                WHERE t.%s AND position('{' || v || '}' IN t.body) = 0
                """.formatted(seeded), String.class))
                .as("biến bắt buộc không có trong nội dung").isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT code FROM notification_templates
                WHERE %s AND code LIKE 'OTP\\_%%' AND NOT (required_vars @> '["ma_otp"]'::jsonb)
                """.formatted(seeded), String.class))
                .as("mẫu OTP thiếu biến bắt buộc ma_otp").isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT code FROM notification_templates
                WHERE %s AND channel = 'IN_APP'
                  AND (code NOT LIKE '%%\\_APP' OR subject IS NULL OR btrim(subject) = '' OR length(subject) > 200)
                """.formatted(seeded), String.class))
                .as("mẫu IN_APP phải có mã _APP và subject 1–200 ký tự (docs/adr/0014)").isEmpty();
    }

    /** Mọi hằng số của {@code NotificationTemplateCode} có dòng seed: thiếu thì enqueue ghi outbox lỗi FK (06 §8 Q4). */
    @Test
    void everyNotificationTemplateCodeConstantHasASeededRow() throws IllegalAccessException {
        List<String> constants = new java.util.ArrayList<>();
        for (java.lang.reflect.Field field : com.petcare.module.identity.api.NotificationTemplateCode.class
                .getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                constants.add((String) field.get(null));
            }
        }
        List<String> seeded = jdbc.queryForList("SELECT code FROM notification_templates", String.class);

        assertThat(constants).as("hằng số trùng giá trị").doesNotHaveDuplicates();
        assertThat(seeded).containsAll(constants);
    }

    /**
     * V7 (docs/adr/0018, nợ D009): mỗi cột FK trỏ tới {@code accounts} là cột đầu của một index mà câu kiểm FK
     * ({@code WHERE <cột> = $1}) dùng được — index không partial, hoặc partial đúng {@code <cột> IS NOT NULL}. Thiếu thì
     * xóa một tài khoản (ST02) quét toàn bảng đó. Chặn cả cột FK mới thêm sau này.
     */
    @Test
    void everyForeignKeyToAccountsHasUsableIndex() {
        List<String> foreignKeys = jdbc.queryForList("""
                SELECT c.conrelid::regclass || '.' || a.attname
                FROM pg_constraint c
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                WHERE c.contype = 'f' AND c.confrelid = 'accounts'::regclass
                """, String.class);
        assertThat(foreignKeys).as("số cột FK trỏ tới accounts (V1: 43 cột ở 34 bảng; V34: + ai_documents.updated_by, "
                + "ai_item_profiles.updated_by; V36–V39: + 16 cột — shift_assignments 2, shift_requests 2, surgeries 3, "
                + "visit_material_usages 1, admissions 2, admission_orders 2, admission_observations 1, complaints 2, "
                + "complaint_messages 1; V43–V48 của BE-2: + 16 cột ở 14 bảng)").hasSize(77);

        List<String> unindexed = jdbc.queryForList("""
                SELECT c.conrelid::regclass || '.' || a.attname
                FROM pg_constraint c
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                WHERE c.contype = 'f' AND c.confrelid = 'accounts'::regclass
                  AND NOT EXISTS (
                      SELECT 1 FROM pg_index i
                      WHERE i.indrelid = c.conrelid AND i.indkey[0] = c.conkey[1]
                        AND (i.indpred IS NULL
                             OR pg_get_expr(i.indpred, i.indrelid) = '(' || a.attname || ' IS NOT NULL)'))
                ORDER BY 1
                """, String.class);
        assertThat(unindexed).as("cột FK → accounts không có index dùng được cho phép kiểm FK").isEmpty();
    }

    /**
     * V9 (docs/adr/0025): {@code otp_tokens.customer_id} có index dùng được cho kiểm FK khi xóa hồ sơ online (BR-TK-19,
     * ST02); V10 (docs/adr/0028): {@code addresses.customer_id} cũng có; V13 (docs/adr/0041) thêm bốn index còn lại, trả hết nợ
     * D013. Cột FK mới trỏ tới {@code customers} mà thiếu index sẽ làm test này đỏ.
     */
    @Test
    void everyForeignKeyToCustomersHasUsableIndex() {
        List<String> unindexed = jdbc.queryForList("""
                SELECT c.conrelid::regclass || '.' || a.attname
                FROM pg_constraint c
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                WHERE c.contype = 'f' AND c.confrelid = 'customers'::regclass
                  AND NOT EXISTS (
                      SELECT 1 FROM pg_index i
                      WHERE i.indrelid = c.conrelid AND i.indkey[0] = c.conkey[1]
                        AND (i.indpred IS NULL
                             OR pg_get_expr(i.indpred, i.indrelid) = '(' || a.attname || ' IS NOT NULL)'))
                ORDER BY 1
                """, String.class);

        assertThat(unindexed).as("D013 đã trả bằng V13 (docs/adr/0041)").isEmpty();
    }

    // ---------------------------------------------------------------- D6, D7, D10: hành vi ràng buộc

    /**
     * V31 (docs/adr/0098): hai trigger chỉ-thêm-mới của V1 đã gỡ theo yêu cầu của chủ dự án; bảo đảm BR-QT-16 nằm ở
     * tầng ứng dụng. Index {@code action} có mặt. Không UPDATE / DELETE / TRUNCATE bảng thật trong IT chung database.
     */
    @Test
    void auditLogsTriggersAreDroppedAndActionIndexExists() {
        assertThat(jdbc.queryForList("""
                SELECT tgname FROM pg_trigger
                WHERE tgrelid = 'audit_logs'::regclass AND NOT tgisinternal
                """, String.class)).as("không còn trigger nào trên audit_logs").isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_proc WHERE proname = 'forbid_audit_log_change'",
                Long.class)).as("hàm trigger đã xóa").isZero();
        assertThat(jdbc.queryForObject("""
                SELECT pg_get_indexdef(indexrelid) FROM pg_index
                WHERE indrelid = 'audit_logs'::regclass
                  AND indexrelid = 'ix_audit_logs_action_created_at'::regclass
                """, String.class)).contains("(action, created_at)");
    }

    @Test
    void boardingBookingsRejectOverlappingStayOfSamePet() {
        long staff = staffAccount();
        long branch = branch();
        long customer = counterCustomer();
        long pet = pet(customer);
        long kennelType = kennelType();
        LocalDate in = LocalDate.of(2026, 11, 1);

        booking(pet, customer, branch, kennelType, in, in.plusDays(4), "BOOKED", staff);

        // Chồng ngày với đặt chỗ đang chiếm chỗ của cùng thú (BR-LT-02)
        assertThatThrownBy(() -> booking(pet, customer, branch, kennelType, in.plusDays(2), in.plusDays(6),
                "BOOKED", staff))
                .isInstanceOf(DataAccessException.class)
                .satisfies(ex -> assertThat(sqlState(ex)).isEqualTo("23P01"));
        // Nối tiếp: ngày nhận = ngày trả của đặt chỗ trước
        booking(pet, customer, branch, kennelType, in.plusDays(4), in.plusDays(7), "BOOKED", staff);
        // Đặt chỗ CANCELLED không chiếm chỗ
        booking(pet, customer, branch, kennelType, in.plusDays(1), in.plusDays(3), "CANCELLED", staff);
        // Thú khác cùng ngày
        booking(pet(customer), customer, branch, kennelType, in, in.plusDays(4), "BOOKED", staff);
    }

    @Test
    void zeroAmountPaymentIsAllowed() {
        long cashier = staffAccount();
        long branch = branch();
        long customer = counterCustomer();
        long shift = jdbc.queryForObject("""
                INSERT INTO cashier_shifts (branch_id, cashier_id, business_date, status, opened_at)
                VALUES (?, ?, ?, 'OPEN', now()) RETURNING id
                """, Long.class, branch, cashier, LocalDate.of(2026, 11, 1));

        // [ERD 10] Order 0đ vẫn thu được (BR-TG-02)
        payment(branch, customer, shift, cashier, 0);

        assertThatThrownBy(() -> payment(branch, customer, shift, cashier, -1))
                .isInstanceOf(DataAccessException.class)
                .satisfies(ex -> assertThat(sqlState(ex)).isEqualTo("23514"));
    }

    // ---------------------------------------------------------------- fixture

    private long staffAccount() {
        return jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0900000000', 'hash', 'RECEPTIONIST', 'ACTIVE') RETURNING id
                """, Long.class, "it-" + SEQ.incrementAndGet() + "@petcare.test");
    }

    private long customerAccount(String status, Timestamp pendingExpiresAt) {
        return jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status, pending_expires_at)
                VALUES (?, 'hash', 'CUSTOMER', ?, ?) RETURNING id
                """, Long.class, "it-" + SEQ.incrementAndGet() + "@petcare.test", status, pendingExpiresAt);
    }

    private long branch() {
        return jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, 'ACTIVE') RETURNING id
                """, Long.class, "IT chi nhánh " + SEQ.incrementAndGet());
    }

    private long counterCustomer() {
        return jdbc.queryForObject("""
                INSERT INTO customers (full_name, phone, created_channel) VALUES ('Khách IT', '0911111111', 'COUNTER')
                RETURNING id
                """, Long.class);
    }

    private long pet(long customer) {
        return jdbc.queryForObject("""
                INSERT INTO pets (customer_id, name, species, sex) VALUES (?, ?, 'DOG', 'MALE') RETURNING id
                """, Long.class, customer, "Thú IT " + SEQ.incrementAndGet());
    }

    private long kennelType() {
        long service = jdbc.queryForObject("""
                INSERT INTO services (name, service_group, price) VALUES (?, 'BOARDING', 200000) RETURNING id
                """, Long.class, "Chuồng IT " + SEQ.incrementAndGet());
        jdbc.update("INSERT INTO kennel_types (service_id, species, max_weight_kg) VALUES (?, 'DOG', 20.00)", service);
        return service;
    }

    private void booking(long pet, long customer, long branch, long kennelType, LocalDate checkIn, LocalDate checkOut,
            String status, long bookedBy) {
        jdbc.update("""
                INSERT INTO boarding_bookings (code, customer_id, pet_id, branch_id, kennel_type_id, check_in_date,
                                               check_out_date, nightly_price, status, channel, booked_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, 200000, ?, 'COUNTER', ?)
                """, "LT-IT-" + SEQ.incrementAndGet(), customer, pet, branch, kennelType, checkIn, checkOut, status,
                bookedBy);
    }

    private void payment(long branch, long customer, long shift, long cashier, long amount) {
        jdbc.update("""
                INSERT INTO payments (code, branch_id, customer_id, cashier_shift_id, method, amount, received_by, paid_at)
                VALUES (?, ?, ?, ?, 'CASH', ?, ?, now())
                """, "PT-IT-" + SEQ.incrementAndGet(), branch, customer, shift, amount, cashier);
    }

    // ---------------------------------------------------------------- catalog helpers

    private record Column(String dataType, String nullable, String defaultValue, Integer maxLength,
            Integer precision, Integer scale, String identity, String identityGeneration) {
    }

    private Map<String, Column> columns(String table) {
        Map<String, Column> result = new HashMap<>();
        jdbc.query("""
                SELECT column_name, data_type, is_nullable, column_default, character_maximum_length,
                       numeric_precision, numeric_scale, is_identity, identity_generation
                FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?
                """, rs -> {
            result.put(rs.getString("column_name"), new Column(
                    rs.getString("data_type"),
                    rs.getString("is_nullable"),
                    rs.getString("column_default"),
                    (Integer) rs.getObject("character_maximum_length"),
                    (Integer) rs.getObject("numeric_precision"),
                    (Integer) rs.getObject("numeric_scale"),
                    rs.getString("is_identity"),
                    rs.getString("identity_generation")));
        }, table);
        return result;
    }

    private Column column(String qualified) {
        String[] parts = qualified.split("\\.");
        Column info = columns(parts[0]).get(parts[1]);
        assertThat(info).as(qualified).isNotNull();
        return info;
    }

    private static void assertCommonTimestamp(String table, Column column) {
        assertThat(column).as("%s phải có cột thời gian chung", table).isNotNull();
        assertThat(column.dataType()).as(table).isEqualTo("timestamp with time zone");
        assertThat(column.nullable()).as(table).isEqualTo("NO");
        assertThat(column.defaultValue()).as(table).isEqualTo("now()");
    }

    private static String rootMessage(Throwable ex) {
        return NestedExceptionUtils.getMostSpecificCause(ex).getMessage();
    }

    private static String sqlState(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
