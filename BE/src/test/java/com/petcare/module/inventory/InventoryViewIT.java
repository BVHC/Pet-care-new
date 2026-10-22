package com.petcare.module.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.inventory.api.StockQueryApi;

/**
 * Xem tồn và tồn tối thiểu — {@code /api/inventory} (UC75; inventory-v1 #11–14; BR-KO-01, 05, 07) và
 * {@link StockQueryApi} thật (docs/adr/0042) qua HTTP trên Postgres 17 thật: tồn khả dụng chỉ tính lô chưa hết hạn, tập
 * sản phẩm, bộ lọc, thứ tự FEFO của lô, phạm vi chi nhánh, quyền, và hồi quy BR-SP-05 của catalog (trước đây 500).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InventoryViewIT extends InventoryItSupport {

    private static final String PATH = "/api/inventory";
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired
    private StockQueryApi stockQuery;

    private long branch;
    private long managerId;
    private String manager;

    @BeforeEach
    void setUp() {
        branch = newBranch();
        managerId = staff("BRANCH_MANAGER", branch);
        manager = token(managerId);
    }

    private static String inDays(int days) {
        return LocalDate.now(ZONE).plusDays(days).toString();
    }

    private Map<String, Object> item(long productId) {
        return data(call(HttpMethod.GET, PATH + "/" + productId, manager, null), HttpStatus.OK);
    }

    // ---------------------------------------------------------------- quyền

    @Test
    void permissionMatrix() {
        long product = newProduct("GOODS", false);
        defaultLot(branch, product, 5, managerId);
        for (String role : List.of("RECEPTIONIST", "VET", "CARETAKER")) {
            String token = token(staff(role, branch));
            data(call(HttpMethod.GET, PATH + "/" + product, token, null), HttpStatus.OK);
            assertThat(call(HttpMethod.GET, PATH, token, null).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertError(call(HttpMethod.PUT, PATH + "/" + product + "/min-quantity", token, Map.of("minQuantity", 1)),
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        assertThat(call(HttpMethod.GET, PATH + "/" + product + "/lots", token(staff("RECEPTIONIST", branch)), null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        for (String role : List.of("VET", "CARETAKER")) {
            assertError(call(HttpMethod.GET, PATH + "/" + product + "/lots", token(staff(role, branch)), null),
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        for (String role : List.of("ADMIN", "SUPER_MANAGER")) {
            String token = token(staff(role, null));
            assertError(call(HttpMethod.GET, PATH, token, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(call(HttpMethod.GET, PATH + "/" + product, token, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        assertError(call(HttpMethod.GET, PATH, null, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    // ---------------------------------------------------------------- tồn của một sản phẩm

    @Test
    void availableQuantityIgnoresExpiredLotsAndTheShapeMatchesTheContract() {
        long drug = newProduct("DRUG", true);
        lot(branch, drug, "OLD", inDays(-1), 10, managerId);
        lot(branch, drug, "L2", inDays(20), 20, managerId);
        lot(branch, drug, "L3", inDays(100), 30, managerId);
        call(HttpMethod.PUT, PATH + "/" + drug + "/min-quantity", manager, Map.of("minQuantity", 60));

        Map<String, Object> item = item(drug);

        assertThat(item).containsOnlyKeys("productId", "productName", "sku", "unit", "productType", "tracksExpiry",
                        "totalQuantity", "availableQuantity", "minQuantity", "belowMin", "nearestExpiryDate",
                        "expiredQuantity")
                .containsEntry("productType", "DRUG").containsEntry("tracksExpiry", true)
                .containsEntry("totalQuantity", 60).containsEntry("availableQuantity", 50)
                .containsEntry("expiredQuantity", 10).containsEntry("minQuantity", 60)
                .containsEntry("belowMin", true).containsEntry("nearestExpiryDate", inDays(20));
    }

    @Test
    void expiryBoundaryTodayIsStillAvailableYesterdayIsNot() {
        long drug = newProduct("DRUG", true);
        lot(branch, drug, "TODAY", inDays(0), 7, managerId);
        lot(branch, drug, "YESTERDAY", inDays(-1), 3, managerId);

        Map<String, Object> item = item(drug);

        assertThat(item).containsEntry("availableQuantity", 7).containsEntry("expiredQuantity", 3)
                .containsEntry("nearestExpiryDate", inDays(0));
    }

    @Test
    void productWithoutStockRowShowsZerosAndUnknownProductIs404() {
        long product = newProduct("GOODS", false);

        assertThat(item(product)).containsEntry("totalQuantity", 0).containsEntry("availableQuantity", 0)
                .containsEntry("minQuantity", 0).containsEntry("belowMin", false)
                .containsEntry("nearestExpiryDate", null).containsEntry("expiredQuantity", 0);
        assertError(call(HttpMethod.GET, PATH + "/999999999", manager, null), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
    }

    @Test
    void stockOfAnotherBranchDoesNotCount() {
        long product = newProduct("GOODS", false);
        long otherBranch = newBranch();
        defaultLot(otherBranch, product, 99, managerId);
        defaultLot(branch, product, 4, managerId);

        assertThat(item(product)).containsEntry("availableQuantity", 4);
        assertThat(stockQuery.availableQuantity(branch, product)).isEqualTo(4);
        assertThat(stockQuery.availableQuantity(otherBranch, product)).isEqualTo(99);
        assertThat(stockQuery.branchIdsWithAvailableStock(product)).containsExactlyInAnyOrder(branch, otherBranch);
    }

    // ---------------------------------------------------------------- danh sách

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> content(String query) {
        return (List<Map<String, Object>>) data(call(HttpMethod.GET, PATH + query, manager, null), HttpStatus.OK)
                .get("content");
    }

    private List<Long> productIds(String query) {
        return content(query).stream().map(i -> number(i.get("productId"))).toList();
    }

    @Test
    void listCoversActiveProductsAndInactiveOnesThatStillHaveStock() {
        long active = newProduct("GOODS", false);
        long inactiveWithStock = newProduct("GOODS", false);
        long inactiveNoStock = newProduct("GOODS", false);
        jdbc.update("UPDATE products SET is_active = false WHERE id IN (?, ?)", inactiveWithStock, inactiveNoStock);
        defaultLot(branch, inactiveWithStock, 5, managerId);

        List<Long> ids = productIds("?size=100&q=Sản phẩm kho");

        assertThat(ids).contains(active, inactiveWithStock).doesNotContain(inactiveNoStock);
    }

    @Test
    void filtersByNameSkuTypeBelowMinAndExpiringWindow() {
        long goods = newProduct("GOODS", false);
        long drug = newProduct("DRUG", true);
        long vaccine = newProduct("VACCINE", true);
        jdbc.update("UPDATE products SET name = 'Zeta lọc kho' WHERE id = ?", goods);
        jdbc.update("UPDATE products SET name = 'Zeta thuốc kho' WHERE id = ?", drug);
        jdbc.update("UPDATE products SET name = 'Zeta vaccine kho' WHERE id = ?", vaccine);
        defaultLot(branch, goods, 3, managerId);
        call(HttpMethod.PUT, PATH + "/" + goods + "/min-quantity", manager, Map.of("minQuantity", 10));
        lot(branch, drug, "D1", inDays(10), 5, managerId);
        lot(branch, vaccine, "V1", inDays(200), 5, managerId);
        String sku = (String) item(drug).get("sku");

        assertThat(productIds("?q=zeta")).containsExactly(goods, drug, vaccine);
        assertThat(productIds("?q=" + sku.toLowerCase())).containsExactly(drug);
        assertThat(productIds("?q=zeta&productType=VACCINE")).containsExactly(vaccine);
        assertThat(productIds("?q=zeta&belowMin=true")).containsExactly(goods);
        assertThat(productIds("?q=zeta&expiringWithinDays=30")).containsExactly(drug);
        assertThat(productIds("?q=zeta&expiringWithinDays=365")).containsExactly(drug, vaccine);
        assertThat(productIds("?q=zeta&expiringWithinDays=-5")).as("âm coi như 0").isEmpty();
        assertThat(productIds("?q=%25")).as("% là ký tự thường").isEmpty();
        assertError(call(HttpMethod.GET, PATH + "?productType=TOY", manager, null), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
    }

    @Test
    void expiredLotWithStockAppearsInTheExpiringFilter() {
        long drug = newProduct("DRUG", true);
        jdbc.update("UPDATE products SET name = 'Eta hết hạn kho' WHERE id = ?", drug);
        lot(branch, drug, "OLD", inDays(-3), 4, managerId);

        assertThat(productIds("?q=eta hết hạn&expiringWithinDays=0")).containsExactly(drug);
    }

    @Test
    void pagingAndClamping() {
        for (int i = 0; i < 3; i++) {
            long product = newProduct("GOODS", false);
            jdbc.update("UPDATE products SET name = ? WHERE id = ?", "Theta trang kho " + i, product);
        }

        Map<String, Object> page = data(call(HttpMethod.GET, PATH + "?q=theta trang&size=2", manager, null),
                HttpStatus.OK);
        Map<String, Object> clamped = data(call(HttpMethod.GET, PATH + "?q=theta trang&size=1000&page=-2", manager,
                null), HttpStatus.OK);

        assertThat(page).containsOnlyKeys("content", "page", "size", "totalElements", "totalPages")
                .containsEntry("totalElements", 3).containsEntry("totalPages", 2);
        assertThat(clamped).containsEntry("size", 100).containsEntry("page", 0);
    }

    // ---------------------------------------------------------------- lô

    @Test
    void lotsAreInFefoOrderWithFlagsAndHideEmptyOnes() {
        long drug = newProduct("DRUG", true);
        long late = lot(branch, drug, "LATE", inDays(100), 30, managerId);
        long soon = lot(branch, drug, "SOON", inDays(10), 20, managerId);
        long old = lot(branch, drug, "OLD", inDays(-2), 10, managerId);
        lot(branch, drug, "EMPTY", inDays(50), 0, managerId);
        long goods = newProduct("GOODS", false);
        long defaultLot = defaultLot(branch, goods, 8, managerId);

        List<Map<String, Object>> lots = list(call(HttpMethod.GET, PATH + "/" + drug + "/lots", manager, null),
                HttpStatus.OK);
        List<Map<String, Object>> defaults = list(call(HttpMethod.GET, PATH + "/" + goods + "/lots", manager, null),
                HttpStatus.OK);

        assertThat(lots).extracting(l -> number(l.get("stockLotId"))).containsExactly(old, soon, late);
        assertThat(lots.get(0)).containsOnlyKeys("stockLotId", "lotNumber", "expiryDate", "quantity", "isDefault",
                "expired").containsEntry("expired", true).containsEntry("isDefault", false);
        assertThat(lots.get(1)).containsEntry("expired", false).containsEntry("quantity", 20);
        assertThat(defaults).singleElement().satisfies(l -> {
            assertThat(number(l.get("stockLotId"))).isEqualTo(defaultLot);
            assertThat(l).containsEntry("isDefault", true).containsEntry("lotNumber", null)
                    .containsEntry("expiryDate", null).containsEntry("expired", false);
        });
        assertThat(list(call(HttpMethod.GET, PATH + "/" + newProduct("GOODS", false) + "/lots", manager, null),
                HttpStatus.OK)).isEmpty();
        assertError(call(HttpMethod.GET, PATH + "/999999999/lots", manager, null), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
    }

    // ---------------------------------------------------------------- tồn tối thiểu

    @Test
    void minQuantityIsPerBranchAndCreatesTheRow() {
        long product = newProduct("GOODS", false);
        String other = token(staff("BRANCH_MANAGER", newBranch()));

        Map<String, Object> saved = data(call(HttpMethod.PUT, PATH + "/" + product + "/min-quantity", manager,
                Map.of("minQuantity", 25)), HttpStatus.OK);

        assertThat(saved).containsEntry("minQuantity", 25).containsEntry("belowMin", true);
        assertThat(item(product)).containsEntry("minQuantity", 25);
        assertThat(data(call(HttpMethod.GET, PATH + "/" + product, other, null), HttpStatus.OK))
                .containsEntry("minQuantity", 0);
        data(call(HttpMethod.PUT, PATH + "/" + product + "/min-quantity", manager, Map.of("minQuantity", 0)),
                HttpStatus.OK);
        assertThat(item(product)).containsEntry("minQuantity", 0);
    }

    @Test
    void minQuantityRejectsInvalidInput() {
        long product = newProduct("GOODS", false);
        String path = PATH + "/" + product + "/min-quantity";

        assertError(call(HttpMethod.PUT, path, manager, Map.of("minQuantity", -1)), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(call(HttpMethod.PUT, path, manager, Map.of("minQuantity", 1_000_001)), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(call(HttpMethod.PUT, path, manager, Map.of()), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(call(HttpMethod.PUT, path, manager, Map.of("minQuantity", 1.5)), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertError(call(HttpMethod.PUT, PATH + "/999999999/min-quantity", manager, Map.of("minQuantity", 1)),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    // ---------------------------------------------------------------- StockQueryApi, BR-SP-05

    @Test
    void hasStockAnywhereCountsExpiredLotsToo() {
        long product = newProduct("DRUG", true);
        assertThat(stockQuery.hasStockAnywhere(product)).isFalse();

        lot(branch, product, "OLD", inDays(-30), 2, managerId);

        assertThat(stockQuery.hasStockAnywhere(product)).isTrue();
        assertThat(stockQuery.availableQuantity(branch, product)).isZero();
        assertThat(stockQuery.branchIdsWithAvailableStock(product)).isEmpty();
    }

    @Test
    void turningOffExpiryTrackingIsRefusedWhileStockRemainsInsteadOfFailingWithA500() {
        long product = newProduct("DRUG", true);
        String superManager = token(staff("SUPER_MANAGER", null));
        lot(branch, product, "L1", inDays(60), 5, managerId);

        assertRule(call(HttpMethod.PATCH, "/api/products/" + product, superManager, Map.of("tracksExpiry", false)),
                "BR-SP-05");
        assertThat(jdbc.queryForObject("SELECT tracks_expiry FROM products WHERE id = ?", Boolean.class, product))
                .isTrue();

        jdbc.update("UPDATE stock_lots SET quantity = 0 WHERE inventory_item_id IN "
                + "(SELECT id FROM inventory_items WHERE product_id = ?)", product);
        data(call(HttpMethod.PATCH, "/api/products/" + product, superManager, Map.of("tracksExpiry", false)),
                HttpStatus.OK);
    }
}
