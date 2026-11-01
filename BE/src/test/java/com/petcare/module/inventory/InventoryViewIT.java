package com.petcare.module.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.service.SystemConfigService;
import com.petcare.module.inventory.api.StockQueryApi;

/**
 * Xem tồn và tồn tối thiểu — {@code /api/inventory} (UC75; inventory-v1 #11–14; BR-KO-01, 05, 07) và
 * {@link StockQueryApi} thật (docs/adr/0042) qua HTTP trên Postgres 17 thật: tồn khả dụng chỉ tính lô chưa hết hạn, tập
 * sản phẩm, bộ lọc, thứ tự FEFO của lô, phạm vi chi nhánh, quyền, và hồi quy BR-SP-05 của catalog (trước đây 500).
 * Cờ {@code expiringSoon} của dòng tồn và của lô theo [CFG] {@code stock.expiry_warning_days} (docs/adr/0088).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InventoryViewIT extends InventoryItSupport {

    private static final String PATH = "/api/inventory";
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String WARNING_DAYS_KEY = "stock.expiry_warning_days";

    @Autowired
    private StockQueryApi stockQuery;
    @Autowired
    private SystemConfigService configs;

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

    /**
     * Chạy {@code body} với [CFG] {@code stock.expiry_warning_days} = {@code days} (phải trong 7–180, ngoài biên
     * {@code reload()} ném và giữ bản cũ), rồi khôi phục giá trị gốc và nạp lại.
     */
    private void withWarningDays(int days, Runnable body) {
        String original = jdbc.queryForObject("SELECT value FROM system_configs WHERE key = ?", String.class,
                WARNING_DAYS_KEY);
        try {
            jdbc.update("UPDATE system_configs SET value = ? WHERE key = ?", String.valueOf(days), WARNING_DAYS_KEY);
            configs.reload();
            body.run();
        } finally {
            jdbc.update("UPDATE system_configs SET value = ? WHERE key = ?", original, WARNING_DAYS_KEY);
            configs.reload();
        }
    }

    /** DRUG riêng tên "Iota cờ kho …" (lọc bằng {@code q=}); có một lô còn {@code quantity} nếu {@code days != null}. */
    private long flaggedDrug(Integer days, int quantity) {
        long drug = newProduct("DRUG", true);
        jdbc.update("UPDATE products SET name = ? WHERE id = ?", "Iota cờ kho " + drug, drug);
        if (days != null) {
            lot(branch, drug, "L" + drug, inDays(days), quantity, managerId);
        }
        return drug;
    }

    private List<Boolean> lotFlags(long productId) {
        return list(call(HttpMethod.GET, PATH + "/" + productId + "/lots", manager, null), HttpStatus.OK).stream()
                .map(l -> (Boolean) l.get("expiringSoon")).toList();
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
                        "expiredQuantity", "expiringSoon")
                .containsEntry("productType", "DRUG").containsEntry("tracksExpiry", true)
                .containsEntry("totalQuantity", 60).containsEntry("availableQuantity", 50)
                .containsEntry("expiredQuantity", 10).containsEntry("minQuantity", 60)
                .containsEntry("belowMin", true).containsEntry("nearestExpiryDate", inDays(20))
                .containsEntry("expiringSoon", true);
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
                .containsEntry("nearestExpiryDate", null).containsEntry("expiredQuantity", 0)
                .containsEntry("expiringSoon", false);
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
                "expired", "expiringSoon").containsEntry("expired", true).containsEntry("isDefault", false)
                .containsEntry("expiringSoon", true);
        assertThat(lots.get(1)).containsEntry("expired", false).containsEntry("quantity", 20);
        assertThat(defaults).singleElement().satisfies(l -> {
            assertThat(number(l.get("stockLotId"))).isEqualTo(defaultLot);
            assertThat(l).containsEntry("isDefault", true).containsEntry("lotNumber", null)
                    .containsEntry("expiryDate", null).containsEntry("expired", false)
                    .containsEntry("expiringSoon", false);
        });
        assertThat(list(call(HttpMethod.GET, PATH + "/" + newProduct("GOODS", false) + "/lots", manager, null),
                HttpStatus.OK)).isEmpty();
        assertError(call(HttpMethod.GET, PATH + "/999999999/lots", manager, null), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
    }

    // ---------------------------------------------------------------- cờ expiringSoon (docs/adr/0088)

    @Test
    void expiringSoonOfAnItemFollowsTheWarningWindowOnEveryRead() {
        withWarningDays(30, () -> {
            Map<Long, Boolean> expected = new LinkedHashMap<>();
            long in10 = flaggedDrug(10, 5);
            long in31 = flaggedDrug(31, 5);
            expected.put(in10, true);
            expected.put(flaggedDrug(30, 5), true);
            expected.put(in31, false);
            expected.put(flaggedDrug(200, 5), false);
            long expired = flaggedDrug(-3, 4);
            expected.put(expired, true);
            expected.put(flaggedDrug(10, 0), false);
            long goods = newProduct("GOODS", false);
            jdbc.update("UPDATE products SET name = ? WHERE id = ?", "Iota cờ kho " + goods, goods);
            defaultLot(branch, goods, 8, managerId);
            expected.put(goods, false);
            long noRow = newProduct("GOODS", false);
            jdbc.update("UPDATE products SET name = ? WHERE id = ?", "Iota cờ kho " + noRow, noRow);
            expected.put(noRow, false);

            expected.forEach((id, flag) -> assertThat(item(id)).as("GET /inventory/%d", id)
                    .containsEntry("expiringSoon", flag));
            assertThat(item(expired)).containsEntry("expiredQuantity", 4);
            assertThat(content("?q=iota cờ kho&size=100").stream().collect(Collectors.toMap(
                    i -> number(i.get("productId")), i -> (Boolean) i.get("expiringSoon"))))
                    .as("GET /inventory?q=").containsExactlyInAnyOrderEntriesOf(expected);
            assertThat(data(call(HttpMethod.PUT, PATH + "/" + in10 + "/min-quantity", manager,
                    Map.of("minQuantity", 3)), HttpStatus.OK)).as("PUT min-quantity (+10 ngày)")
                    .containsEntry("expiringSoon", true);
            assertThat(data(call(HttpMethod.PUT, PATH + "/" + in31 + "/min-quantity", manager,
                    Map.of("minQuantity", 3)), HttpStatus.OK)).as("PUT min-quantity (+31 ngày)")
                    .containsEntry("expiringSoon", false);
        });
    }

    @Test
    void expiringSoonFollowsAChangedConfigWithoutExposingTheDays() {
        long in7 = flaggedDrug(7, 5);
        long in10 = flaggedDrug(10, 5);
        long in100 = flaggedDrug(100, 5);

        withWarningDays(7, () -> {
            assertThat(item(in7)).containsEntry("expiringSoon", true);
            assertThat(item(in10)).containsEntry("expiringSoon", false).doesNotContainKey("warningDays");
            assertThat(lotFlags(in7)).containsExactly(true);
            assertThat(lotFlags(in10)).containsExactly(false);
        });
        withWarningDays(180, () -> {
            assertThat(item(in100)).containsEntry("expiringSoon", true);
            assertThat(item(in10)).containsEntry("expiringSoon", true);
            assertThat(lotFlags(in100)).containsExactly(true);
        });
    }

    @Test
    void expiringSoonOfALotFollowsTheWindowIncludingExpiredLotsButNeverTheDefaultLot() {
        withWarningDays(30, () -> {
            long drug = newProduct("DRUG", true);
            long expired = lot(branch, drug, "OLD", inDays(-3), 4, managerId);
            long soon = lot(branch, drug, "SOON", inDays(10), 5, managerId);
            long edge = lot(branch, drug, "EDGE", inDays(30), 5, managerId);
            long past = lot(branch, drug, "PAST", inDays(31), 5, managerId);
            long far = lot(branch, drug, "FAR", inDays(40), 5, managerId);
            long goods = newProduct("GOODS", false);
            defaultLot(branch, goods, 8, managerId);

            List<Map<String, Object>> lots = list(call(HttpMethod.GET, PATH + "/" + drug + "/lots", manager, null),
                    HttpStatus.OK);
            Map<Long, Boolean> flags = lots.stream().collect(Collectors.toMap(l -> number(l.get("stockLotId")),
                    l -> (Boolean) l.get("expiringSoon")));

            assertThat(flags).containsOnlyKeys(expired, soon, edge, past, far).containsEntry(expired, true)
                    .containsEntry(soon, true).containsEntry(edge, true).containsEntry(past, false)
                    .containsEntry(far, false);
            assertThat(lots.get(0)).containsEntry("expired", true).containsEntry("expiringSoon", true);
            assertThat(lotFlags(goods)).as("lô mặc định không hạn").containsExactly(false);
        });
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
    void availableStockBatchMatchesTheSingleCallAndKeepsEveryKey() {
        long otherBranch = newBranch();
        long a = newProduct("GOODS", false);
        long b = newProduct("DRUG", true);
        long none = newProduct("GOODS", false);
        defaultLot(branch, a, 3, managerId);
        defaultLot(otherBranch, a, 1, managerId);
        lot(branch, b, "TODAY", inDays(0), 2, managerId);
        lot(branch, b, "OLD", inDays(-1), 9, managerId);
        lot(otherBranch, b, "OLD2", inDays(-1), 9, managerId);

        Map<Long, List<Long>> batch = stockQuery.branchIdsWithAvailableStock(List.of(a, b, none));

        assertThat(batch).containsOnlyKeys(a, b, none);
        assertThat(batch.get(a)).containsExactlyInAnyOrder(branch, otherBranch);
        assertThat(batch.get(b)).containsExactly(branch);
        assertThat(batch.get(none)).isEmpty();
        for (long id : List.of(a, b, none)) {
            assertThat(batch.get(id)).isEqualTo(stockQuery.branchIdsWithAvailableStock(id));
        }
        assertThat(stockQuery.branchIdsWithAvailableStock(List.<Long>of())).isEmpty();
    }

    @Test
    void productIdsWithAvailableStockAtABranchIgnoreExpiredAndEmptyLots() {
        long inStock = newProduct("GOODS", false);
        long expired = newProduct("DRUG", true);
        long elsewhere = newProduct("GOODS", false);
        defaultLot(branch, inStock, 4, managerId);
        lot(branch, expired, "OLD", inDays(-1), 5, managerId);
        defaultLot(newBranch(), elsewhere, 4, managerId);

        assertThat(stockQuery.productIdsWithAvailableStock(branch)).contains(inStock)
                .doesNotContain(expired, elsewhere);
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
