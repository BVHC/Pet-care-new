package com.petcare.module.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.inventory.InventoryItSupport;

/**
 * UC15, UC16 — dịch vụ và sản phẩm công khai (catalog-v1 #19–22; BR-CK-01, 02, 03, BR-SP-01) trên Postgres 17 thật:
 * chỉ dịch vụ bật ở chi nhánh ACTIVE, thuốc kê đơn / ngừng kinh doanh / danh mục ngừng không bao giờ lộ, chi nhánh còn
 * hàng chỉ gồm ACTIVE và tồn khả dụng, không có trường số lượng.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PublicCatalogIT extends InventoryItSupport {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final List<Long> services = new ArrayList<>();
    private long branch;
    private long otherBranch;
    private long actor;
    private final String tag = UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void setUp() {
        branch = newBranch();
        otherBranch = newBranch();
        actor = staff("BRANCH_MANAGER", branch);
    }

    @AfterEach
    void cleanServices() {
        if (!services.isEmpty()) {
            String ids = String.join(",", services.stream().map(String::valueOf).toList());
            jdbc.update("DELETE FROM branch_services WHERE service_id IN (" + ids + ")");
            jdbc.update("DELETE FROM kennel_types WHERE service_id IN (" + ids + ")");
            jdbc.update("DELETE FROM services WHERE id IN (" + ids + ")");
        }
    }

    /** Đặt tên có tag riêng của lớp test để lọc bằng {@code q}, không phụ thuộc dữ liệu IT khác. */
    private long named(long productId, String suffix) {
        jdbc.update("UPDATE products SET name = ? WHERE id = ?", "pub-" + tag + "-" + suffix, productId);
        return productId;
    }

    private static String inDays(int days) {
        return LocalDate.now(ZONE).plusDays(days).toString();
    }

    private long service(String group, String medicalType, long price, boolean active, boolean priceIsFrom) {
        long id = jdbc.queryForObject("""
                INSERT INTO services (name, service_group, medical_type, price, price_is_from, is_active)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, "Public " + UUID.randomUUID(), group, medicalType, price, priceIsFrom, active);
        services.add(id);
        return id;
    }

    private void enable(long branchId, long serviceId) {
        jdbc.update("INSERT INTO branch_services (branch_id, service_id, is_enabled) VALUES (?, ?, true)",
                branchId, serviceId);
    }

    private List<Map<String, Object>> publicServices(String query) {
        return list(call(HttpMethod.GET, "/api/public/services" + query, null, null), HttpStatus.OK);
    }

    private Map<String, Object> serviceById(List<Map<String, Object>> all, long id) {
        return all.stream().filter(s -> number(s.get("serviceId")) == id).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> productPage(String query) {
        return (List<Map<String, Object>>) data(call(HttpMethod.GET, "/api/public/products" + query, null, null),
                HttpStatus.OK).get("content");
    }

    private boolean contains(List<Map<String, Object>> page, long productId) {
        return page.stream().anyMatch(p -> number(p.get("productId")) == productId);
    }

    // ---------------------------------------------------------------- dịch vụ

    @Test
    void servicesOnlyActiveAndEnabledAtAnActiveBranch() {
        long enabled = service("MEDICAL", "EXAM", 150_000, true, true);
        long nowhere = service("MEDICAL", "EXAM", 150_000, true, true);
        long stopped = service("GROOMING", null, 90_000, false, true);
        long atDraft = service("GROOMING", null, 90_000, true, true);
        long draft = jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'a', '0280000000', 10.7, 106.6, 'DRAFT') RETURNING id
                """, Long.class, "PublicIT draft " + UUID.randomUUID());
        enable(branch, enabled);
        enable(otherBranch, enabled);
        enable(branch, stopped);
        enable(draft, atDraft);

        List<Map<String, Object>> all = publicServices("");

        assertThat(serviceById(all, nowhere)).isNull();
        assertThat(serviceById(all, stopped)).isNull();
        assertThat(serviceById(all, atDraft)).isNull();
        Map<String, Object> found = serviceById(all, enabled);
        assertThat(found).containsOnlyKeys("serviceId", "name", "group", "price", "priceIsFrom", "description",
                "kennelType", "branchIds");
        assertThat(found).containsEntry("group", "MEDICAL").containsEntry("priceIsFrom", true);
        assertThat(((List<?>) found.get("branchIds")).stream().map(v -> ((Number) v).longValue()).toList())
                .containsExactlyInAnyOrder(branch, otherBranch);
        jdbc.update("DELETE FROM branch_services WHERE branch_id = ?", draft);
        jdbc.update("DELETE FROM branches WHERE id = ?", draft);
    }

    @Test
    void servicesGroupAndBranchFiltersKeepOnlyMatches() {
        long exam = service("MEDICAL", "EXAM", 150_000, true, true);
        long spa = service("GROOMING", null, 200_000, true, false);
        enable(branch, exam);
        enable(branch, spa);
        enable(otherBranch, spa);

        assertThat(serviceById(publicServices("?group=GROOMING"), spa)).isNotNull();
        assertThat(serviceById(publicServices("?group=GROOMING"), exam)).isNull();
        assertThat(serviceById(publicServices("?branchId=" + branch), exam)).isNotNull();
        assertThat(serviceById(publicServices("?branchId=" + otherBranch), exam)).isNull();
        assertThat(serviceById(publicServices(""), spa)).containsEntry("priceIsFrom", false);
    }

    @Test
    void boardingServiceCarriesKennelTypeAndNightlyPrice() {
        long kennel = service("BOARDING", null, 120_000, true, false);
        jdbc.update("INSERT INTO kennel_types (service_id, species, max_weight_kg) VALUES (?, 'DOG', 12.50)", kennel);
        enable(branch, kennel);

        Map<String, Object> found = serviceById(publicServices("?group=BOARDING"), kennel);

        assertThat(number(found.get("price"))).isEqualTo(120_000);
        assertThat(map(found.get("kennelType"))).containsEntry("species", "DOG");
        assertThat(((Number) map(found.get("kennelType")).get("maxWeightKg")).doubleValue()).isEqualTo(12.5);
    }

    // ---------------------------------------------------------------- danh mục sản phẩm

    @Test
    void productCategoriesListOnlyActiveOnes() {
        long product = newProduct("GOODS", false);
        long activeCategory = jdbc.queryForObject("SELECT category_id FROM products WHERE id = ?", Long.class, product);
        long stoppedCategory = jdbc.queryForObject("""
                INSERT INTO product_categories (name, is_active) VALUES (?, false) RETURNING id
                """, Long.class, "PublicIT stopped " + UUID.randomUUID());

        List<Map<String, Object>> categories = list(
                call(HttpMethod.GET, "/api/public/product-categories", null, null), HttpStatus.OK);

        assertThat(categories.stream().map(c -> number(c.get("categoryId"))).toList()).contains(activeCategory)
                .doesNotContain(stoppedCategory);
        jdbc.update("DELETE FROM product_categories WHERE id = ?", stoppedCategory);
    }

    // ---------------------------------------------------------------- sản phẩm

    @Test
    void prescriptionInactiveAndInactiveCategoryProductsAreNeverListedAndDetailIs404() {
        long normal = named(newProduct("GOODS", false), "normal");
        long prescription = named(newProduct("DRUG", true), "rx");
        long stopped = named(newProduct("GOODS", false), "stopped");
        long inStoppedCategory = named(newProduct("GOODS", false), "cat");
        jdbc.update("UPDATE products SET is_prescription = true WHERE id = ?", prescription);
        jdbc.update("UPDATE products SET is_active = false WHERE id = ?", stopped);
        long stoppedCategory = jdbc.queryForObject("""
                INSERT INTO product_categories (name, is_active) VALUES (?, false) RETURNING id
                """, Long.class, "PublicIT stopped " + UUID.randomUUID());
        jdbc.update("UPDATE products SET category_id = ? WHERE id = ?", stoppedCategory, inStoppedCategory);

        List<Map<String, Object>> page = productPage("?size=100&q=pub-" + tag);

        assertThat(contains(page, normal)).isTrue();
        for (long hidden : List.of(prescription, stopped, inStoppedCategory)) {
            assertThat(contains(page, hidden)).isFalse();
            assertThat(call(HttpMethod.GET, "/api/public/products/" + hidden, null, null).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(call(HttpMethod.GET, "/api/public/products/" + normal, null, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/public/products/999999999", null, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        jdbc.update("UPDATE products SET category_id = (SELECT category_id FROM products WHERE id = ?) WHERE id = ?",
                normal, inStoppedCategory);
        jdbc.update("DELETE FROM product_categories WHERE id = ?", stoppedCategory);
    }

    @Test
    void inStockBranchIdsExcludeDraftBranchesAndExpiredLots() {
        long product = newProduct("DRUG", true);
        long draft = jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'a', '0280000000', 10.7, 106.6, 'DRAFT') RETURNING id
                """, Long.class, "PublicIT draft " + UUID.randomUUID());
        lot(branch, product, "OK", inDays(30), 5, actor);
        lot(otherBranch, product, "OLD", inDays(-1), 5, actor);
        defaultLotAtDraft(draft, product);

        Map<String, Object> found = data(call(HttpMethod.GET, "/api/public/products/" + product, null, null),
                HttpStatus.OK);

        assertThat(((List<?>) found.get("inStockBranchIds")).stream().map(v -> ((Number) v).longValue()).toList())
                .containsExactly(branch);
        jdbc.update("DELETE FROM stock_movements WHERE stock_lot_id IN (SELECT l.id FROM stock_lots l "
                + "JOIN inventory_items i ON i.id = l.inventory_item_id WHERE i.branch_id = ?)", draft);
        jdbc.update("DELETE FROM stock_lots WHERE inventory_item_id IN (SELECT id FROM inventory_items "
                + "WHERE branch_id = ?)", draft);
        jdbc.update("DELETE FROM inventory_items WHERE branch_id = ?", draft);
        jdbc.update("DELETE FROM branches WHERE id = ?", draft);
    }

    private void defaultLotAtDraft(long draftBranch, long product) {
        defaultLot(draftBranch, product, 9, actor);
    }

    @Test
    void branchIdFilterKeepsOnlyProductsInStockThere() {
        long here = newProduct("GOODS", false);
        long there = newProduct("GOODS", false);
        long nowhere = newProduct("GOODS", false);
        defaultLot(branch, here, 3, actor);
        defaultLot(otherBranch, there, 3, actor);

        List<Map<String, Object>> page = productPage("?size=100&branchId=" + branch);

        assertThat(contains(page, here)).isTrue();
        assertThat(contains(page, there)).isFalse();
        assertThat(contains(page, nowhere)).isFalse();
    }

    @Test
    void draftOrUnknownBranchIdGivesAnEmptyPage() {
        long product = newProduct("GOODS", false);
        long draft = jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'a', '0280000000', 10.7, 106.6, 'DRAFT') RETURNING id
                """, Long.class, "PublicIT draft " + UUID.randomUUID());
        defaultLot(draft, product, 5, actor);

        assertThat(productPage("?branchId=" + draft)).isEmpty();
        assertThat(productPage("?branchId=999999999")).isEmpty();
        jdbc.update("DELETE FROM stock_movements WHERE stock_lot_id IN (SELECT l.id FROM stock_lots l "
                + "JOIN inventory_items i ON i.id = l.inventory_item_id WHERE i.branch_id = ?)", draft);
        jdbc.update("DELETE FROM stock_lots WHERE inventory_item_id IN (SELECT id FROM inventory_items "
                + "WHERE branch_id = ?)", draft);
        jdbc.update("DELETE FROM inventory_items WHERE branch_id = ?", draft);
        jdbc.update("DELETE FROM branches WHERE id = ?", draft);
    }

    @Test
    void qEscapesLikeWildcardsAndOverlongQIs400() {
        long percent = named(newProduct("GOODS", false), "x%y");
        long plain = named(newProduct("GOODS", false), "xzzy");
        long underscore = named(newProduct("GOODS", false), "a_b");
        long letters = named(newProduct("GOODS", false), "aXb");

        List<Map<String, Object>> percentPage = productPage("?size=100&q=pub-" + tag + "-x%y");
        List<Map<String, Object>> underscorePage = productPage("?size=100&q=pub-" + tag + "-a_b");

        assertThat(contains(percentPage, percent)).isTrue();
        assertThat(contains(percentPage, plain)).isFalse();
        assertThat(contains(underscorePage, underscore)).isTrue();
        assertThat(contains(underscorePage, letters)).isFalse();
        assertError(call(HttpMethod.GET, "/api/public/products?q=" + "a".repeat(101), null, null),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test
    void sizeIsClampedAndPublicProductHasNoQuantityField() {
        long product = newProduct("GOODS", false);
        defaultLot(branch, product, 7, actor);

        Map<String, Object> page = data(call(HttpMethod.GET, "/api/public/products?size=500&branchId=" + branch,
                null, null), HttpStatus.OK);
        Map<String, Object> found = data(call(HttpMethod.GET, "/api/public/products/" + product, null, null),
                HttpStatus.OK);

        assertThat(number(page.get("size"))).isEqualTo(100);
        assertThat(found).containsOnlyKeys("productId", "name", "categoryId", "categoryName", "price", "unit",
                "description", "imageUrl", "inStockBranchIds");
    }
}
