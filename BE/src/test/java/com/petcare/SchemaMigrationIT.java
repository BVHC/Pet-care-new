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

    /** erd §12 (L1043–1073): 52 model + 3 bảng con = 55 bảng. */
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
            "page_contents", "feedbacks", "care_tasks", "notifications", "notification_outbox");

    /** Bảng loại LOG trong erd: chỉ có {@code created_at} (erd §0 L14). */
    private static final Set<String> LOG_TABLES = Set.of(
            "otp_tokens", "audit_logs", "weight_records", "visit_assignments", "medical_record_addenda",
            "care_log_addenda", "stock_movements", "notification_outbox");

    /** Bảng có khóa chính khác {@code id}: 1–1 dùng FK làm PK, PK ghép, PK chuỗi. */
    private static final Set<String> TABLES_WITHOUT_ID = Set.of(
            "staff_profiles", "kennel_types", "medical_records", "boarding_check_ins",
            "branch_services", "branch_quota_defaults", "system_configs", "notification_templates", "page_contents");

    /** erd §0 L20: tiền là BIGINT. */
    private static final List<String> MONEY_COLUMNS = List.of(
            "products.price", "services.price", "boarding_bookings.nightly_price", "orders.total_amount",
            "order_lines.unit_price", "order_lines.line_total", "cashier_shifts.expected_cash",
            "cashier_shifts.expected_transfer", "cashier_shifts.counted_cash", "cashier_shifts.difference",
            "payments.amount", "stock_receipt_lines.unit_cost");

    /** erd §0 L23: giờ trong ngày là TIME. */
    private static final List<String> TIME_COLUMNS = List.of(
            "opening_hours.open_1", "opening_hours.close_1", "opening_hours.open_2", "opening_hours.close_2",
            "slot_quotas.slot_start", "appointments.slot_start");

    /** erd §0 L26: mã chứng từ hiển thị VARCHAR(20) UNIQUE. */
    private static final List<String> DOCUMENT_CODE_TABLES = List.of(
            "appointments", "visits", "boarding_bookings", "orders", "payments", "stock_receipts", "stock_adjustments");

    /** Tập trạng thái theo docs/03-state-machines.md (SM#1–#10) và 02 (BR-BV-02, BR-DG-04), mã ASCII theo erd L28–37. */
    private static final Map<String, Set<String>> STATE_COLUMNS = new LinkedHashMap<>();

    static {
        STATE_COLUMNS.put("accounts.role", Set.of("CUSTOMER", "ADMIN", "SUPER_MANAGER", "BRANCH_MANAGER",
                "RECEPTIONIST", "VET", "CARETAKER"));
        STATE_COLUMNS.put("accounts.status", Set.of("PENDING", "ACTIVE", "DISABLED"));
        STATE_COLUMNS.put("branches.status", Set.of("DRAFT", "ACTIVE"));
        STATE_COLUMNS.put("appointments.status", Set.of("BOOKED", "CHECKED_IN", "COMPLETED", "CANCELLED", "NO_SHOW"));
        STATE_COLUMNS.put("visits.status", Set.of("WAITING", "IN_PROGRESS", "COMPLETED", "CANCELLED"));
        STATE_COLUMNS.put("orders.status", Set.of("OPEN", "PENDING", "PAID", "CANCELLED"));
        STATE_COLUMNS.put("orders.source", Set.of("VISIT", "RETAIL", "BOARDING"));
        STATE_COLUMNS.put("boarding_bookings.status", Set.of("BOOKED", "CHECKED_IN", "OVERDUE", "CHECKED_OUT",
                "CANCELLED", "NO_SHOW"));
        STATE_COLUMNS.put("kennels.status", Set.of("AVAILABLE", "OCCUPIED", "MAINTENANCE"));
        STATE_COLUMNS.put("cashier_shifts.status", Set.of("OPEN", "CLOSED", "RECONCILED"));
        STATE_COLUMNS.put("stock_receipts.status", Set.of("DRAFT", "CONFIRMED", "CANCELLED"));
        STATE_COLUMNS.put("care_tasks.status", Set.of("OPEN", "DONE", "CANCELLED"));
        STATE_COLUMNS.put("care_tasks.task_type", Set.of("VACCINE_DUE", "VACCINE_OVERDUE", "FOLLOW_UP_DUE",
                "PICKUP_OVERDUE"));
        STATE_COLUMNS.put("articles.status", Set.of("DRAFT", "PUBLISHED", "HIDDEN"));
        STATE_COLUMNS.put("feedbacks.status", Set.of("NEW", "SEEN", "RESOLVED"));
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
        // V10 index addresses.customer_id (docs/adr/0028)
        assertThat(rows).extracting(row -> row.get("version"))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        assertThat(rows).allSatisfy(row -> assertThat(row).containsEntry("success", true));
    }

    @Test
    void createsExactlyTheErdTables() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'
                """, String.class);

        assertThat(ERD_TABLES).hasSize(55);
        assertThat(tables).containsExactlyInAnyOrderElementsOf(ERD_TABLES);
    }

    @Test
    void commonTimestampColumnsMatchTableKind() {
        assertThat(LOG_TABLES).hasSize(8);
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
     * V3, V4, V8, V9 (06-module-contracts §8 Q4, BR-QT-14): mẫu seed bằng migration khôi phục được về mặc định
     * ({@code body = default_body}, {@code subject = default_subject}), biến bắt buộc nằm trong biến cho phép và có mặt
     * trong nội dung, mẫu OTP bắt buộc {@code {ma_otp}}. Mẫu {@code IT_*} do các IT khác chèn nên bị loại.
     */
    @Test
    void seededNotificationTemplatesAreRestorableAndConsistent() {
        String seeded = "code NOT LIKE 'IT\\_%'";

        assertThat(jdbc.queryForList("SELECT code FROM notification_templates WHERE " + seeded, String.class))
                .containsExactlyInAnyOrder("OTP_REGISTER", "OTP_PASSWORD_RESET", "LOGIN_LOCKED_WARNING",
                        "PASSWORD_CHANGED", "OTP_PROFILE_LINK");
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
        assertThat(foreignKeys).as("số cột FK trỏ tới accounts (V1: 43 cột ở 34 bảng)").hasSize(43);

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
     * ST02); V10 (docs/adr/0028): {@code addresses.customer_id} cũng có. Các cột FK → {@code customers} còn thiếu index
     * là đúng danh sách nợ D013; trả nợ (hoặc thêm cột FK mới) thì sửa danh sách này cùng sổ nợ.
     */
    @Test
    void foreignKeysToCustomersWithoutUsableIndexAreExactlyDebtD013() {
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

        assertThat(unindexed).doesNotContain("otp_tokens.customer_id", "addresses.customer_id")
                .as("nợ D013").containsExactlyInAnyOrder("boarding_bookings.customer_id",
                        "care_tasks.customer_id", "payments.customer_id", "visits.customer_id");
    }

    // ---------------------------------------------------------------- D6, D7, D10: hành vi ràng buộc

    @Test
    void auditLogsAreInsertOnly() {
        Long id = jdbc.queryForObject(
                "INSERT INTO audit_logs (actor_email, action) VALUES ('it@petcare.test', 'IT_AUDIT') RETURNING id",
                Long.class);

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_logs SET reason = 'sửa' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .satisfies(ex -> assertThat(rootMessage(ex)).contains("BR-QT-16"));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_logs WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .satisfies(ex -> assertThat(rootMessage(ex)).contains("BR-QT-16"));
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_logs"))
                .isInstanceOf(DataAccessException.class)
                .satisfies(ex -> assertThat(rootMessage(ex)).contains("BR-QT-16"));

        Map<String, Object> row = jdbc.queryForMap("SELECT action, reason FROM audit_logs WHERE id = ?", id);
        assertThat(row).containsEntry("action", "IT_AUDIT").containsEntry("reason", null);
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
