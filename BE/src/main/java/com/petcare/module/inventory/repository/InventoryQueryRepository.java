package com.petcare.module.inventory.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.inventory.dto.InventoryItemResponse;
import com.petcare.module.inventory.dto.StockLotResponse;

/**
 * Đường đọc của tồn kho (inventory-v1 #11–13; {@code StockQueryApi}; docs/adr/0042). <b>Lệch có chủ đích khỏi ranh
 * giới module:</b> JOIN thẳng bảng {@code products} của catalog (chỉ đọc, chỉ các cột {@code id, sku, name, unit,
 * product_type, tracks_expiry, is_active}) để lọc theo tên / SKU / loại và phân trang trong một câu SQL — cả hai module
 * cùng BE-2; đổi các cột đó ở catalog phải sửa lớp này ({@code InventoryViewIT} bắt). Truy vấn động chỉ ghép điều kiện
 * đã gửi (không {@code :x IS NULL OR …}, ADR-0038). Hết hạn = {@code expiry_date < :today}.
 */
@Repository
public class InventoryQueryRepository {

    /** Điều kiện lọc danh sách; trường null = không gửi. */
    public record Filter(long branchId, LocalDate today, String q, ProductType productType, boolean belowMin,
            Integer expiringWithinDays) {
    }

    private static final RowMapper<InventoryItemResponse> ITEM_MAPPER = InventoryQueryRepository::mapItem;
    private static final RowMapper<StockLotResponse> LOT_MAPPER = InventoryQueryRepository::mapLot;

    private static final String ITEM_SELECT = """
            SELECT p.id AS product_id, p.name, p.sku, p.unit, p.product_type, p.tracks_expiry,
                   COALESCE(i.min_quantity, 0) AS min_quantity,
                   COALESCE(t.total, 0) AS total, COALESCE(t.available, 0) AS available,
                   COALESCE(t.expired, 0) AS expired, t.nearest
            """;

    private static final String ITEM_FROM = """
            FROM products p
            LEFT JOIN inventory_items i ON i.product_id = p.id AND i.branch_id = :branchId
            LEFT JOIN LATERAL (
                SELECT SUM(l.quantity) AS total,
                       SUM(l.quantity) FILTER (WHERE l.expiry_date IS NULL OR l.expiry_date >= :today) AS available,
                       SUM(l.quantity) FILTER (WHERE l.expiry_date < :today) AS expired,
                       MIN(l.expiry_date) FILTER (WHERE l.quantity > 0 AND l.expiry_date >= :today) AS nearest
                FROM stock_lots l WHERE l.inventory_item_id = i.id) t ON true
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public InventoryQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- danh sách / chi tiết tồn

    public long count(Filter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        Long total = jdbc.queryForObject("SELECT count(*) " + ITEM_FROM + where(filter, params), params, Long.class);
        return total == null ? 0 : total;
    }

    public List<InventoryItemResponse> findPage(Filter filter, long offset, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = ITEM_SELECT + ITEM_FROM + where(filter, params)
                + " ORDER BY p.name, p.id LIMIT :limit OFFSET :offset";
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql, params, ITEM_MAPPER);
    }

    /** Một sản phẩm (kể cả ngừng kinh doanh); chưa có dòng tồn thì toàn 0. */
    public Optional<InventoryItemResponse> findItem(long branchId, long productId, LocalDate today) {
        return jdbc.query(ITEM_SELECT + ITEM_FROM + " WHERE p.id = :productId",
                new MapSqlParameterSource("branchId", branchId).addValue("today", today)
                        .addValue("productId", productId), ITEM_MAPPER).stream().findFirst();
    }

    /**
     * Sản phẩm đang kinh doanh, cộng sản phẩm đã ngừng nhưng chi nhánh còn số dư (quyết định T18): không để hàng còn tồn
     * biến mất khỏi màn hình kho.
     */
    private static String where(Filter filter, MapSqlParameterSource params) {
        StringBuilder sql = new StringBuilder(" WHERE (p.is_active OR COALESCE(t.total, 0) > 0)");
        params.addValue("branchId", filter.branchId()).addValue("today", filter.today());
        if (filter.q() != null) {
            sql.append(" AND (p.name ILIKE :q ESCAPE '!' OR p.sku ILIKE :q ESCAPE '!')");
            params.addValue("q", "%" + filter.q().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        if (filter.productType() != null) {
            sql.append(" AND p.product_type = :productType");
            params.addValue("productType", filter.productType().name());
        }
        if (filter.belowMin()) {
            sql.append(" AND COALESCE(t.available, 0) < COALESCE(i.min_quantity, 0)");
        }
        if (filter.expiringWithinDays() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM stock_lots x WHERE x.inventory_item_id = i.id AND x.quantity > 0"
                    + " AND x.expiry_date IS NOT NULL AND x.expiry_date <= :limitDate)");
            params.addValue("limitDate", filter.today().plusDays(filter.expiringWithinDays()));
        }
        return sql.toString();
    }

    // ---------------------------------------------------------------- lô

    /** Lô còn số dư của (chi nhánh, sản phẩm), thứ tự FEFO: hạn tăng dần, lô mặc định (không hạn) sau cùng. */
    public List<StockLotResponse> findLots(long branchId, long productId, LocalDate today) {
        return jdbc.query("""
                SELECT l.id, l.lot_number, l.expiry_date, l.quantity, l.is_default,
                       (l.expiry_date IS NOT NULL AND l.expiry_date < :today) AS expired
                FROM stock_lots l JOIN inventory_items i ON i.id = l.inventory_item_id
                WHERE i.branch_id = :branchId AND i.product_id = :productId AND l.quantity > 0
                ORDER BY l.expiry_date ASC NULLS LAST, l.id
                """, new MapSqlParameterSource("branchId", branchId).addValue("productId", productId)
                .addValue("today", today), LOT_MAPPER);
    }

    // ---------------------------------------------------------------- StockQueryApi

    /** Tồn khả dụng: tổng lô chưa hết hạn (BR-KO-01). */
    public int availableQuantity(long branchId, long productId, LocalDate today) {
        Long total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(l.quantity), 0)
                FROM stock_lots l JOIN inventory_items i ON i.id = l.inventory_item_id
                WHERE i.branch_id = :branchId AND i.product_id = :productId
                  AND (l.expiry_date IS NULL OR l.expiry_date >= :today)
                """, new MapSqlParameterSource("branchId", branchId).addValue("productId", productId)
                .addValue("today", today), Long.class);
        return total == null ? 0 : Math.toIntExact(Math.min(total, Integer.MAX_VALUE));
    }

    /** Như {@link #availableQuantity} cho nhiều sản phẩm bằng một câu; sản phẩm không có lô có giá trị 0. */
    public Map<Long, Integer> availableQuantities(long branchId, Collection<Long> productIds, LocalDate today) {
        Map<Long, Integer> result = new LinkedHashMap<>();
        if (productIds.isEmpty()) {
            return result;
        }
        productIds.forEach(id -> result.put(id, 0));
        jdbc.query("""
                SELECT i.product_id AS product_id, COALESCE(SUM(l.quantity), 0) AS total
                FROM stock_lots l JOIN inventory_items i ON i.id = l.inventory_item_id
                WHERE i.branch_id = :branchId AND i.product_id IN (:productIds)
                  AND (l.expiry_date IS NULL OR l.expiry_date >= :today)
                GROUP BY i.product_id
                """, new MapSqlParameterSource("branchId", branchId).addValue("productIds", productIds)
                .addValue("today", today), rs -> {
                    result.put(rs.getLong("product_id"), Math.toIntExact(Math.min(rs.getLong("total"),
                            Integer.MAX_VALUE)));
                });
        return result;
    }

    public List<Long> branchIdsWithAvailableStock(long productId, LocalDate today) {
        return jdbc.queryForList("""
                SELECT i.branch_id
                FROM stock_lots l JOIN inventory_items i ON i.id = l.inventory_item_id
                WHERE i.product_id = :productId AND l.quantity > 0
                  AND (l.expiry_date IS NULL OR l.expiry_date >= :today)
                GROUP BY i.branch_id ORDER BY i.branch_id
                """, new MapSqlParameterSource("productId", productId).addValue("today", today), Long.class);
    }

    /** Còn số dư ở bất kỳ chi nhánh nào, kể cả lô đã hết hạn (BR-SP-05: vẫn phải xử lý lô đó). */
    public boolean hasStockAnywhere(long productId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM stock_lots l JOIN inventory_items i ON i.id = l.inventory_item_id
                               WHERE i.product_id = :productId AND l.quantity > 0)
                """, new MapSqlParameterSource("productId", productId), Boolean.class));
    }

    // ---------------------------------------------------------------- tồn tối thiểu

    /** Đặt tồn tối thiểu (BR-KO-07); tạo dòng tồn nếu chưa có. */
    public void setMinQuantity(long branchId, long productId, int minQuantity, Instant now) {
        jdbc.update("""
                INSERT INTO inventory_items (branch_id, product_id, min_quantity) VALUES (:branchId, :productId, :min)
                ON CONFLICT (branch_id, product_id) DO UPDATE
                SET min_quantity = EXCLUDED.min_quantity, updated_at = :now
                """, new MapSqlParameterSource("branchId", branchId).addValue("productId", productId)
                .addValue("min", minQuantity).addValue("now", Timestamp.from(now)));
    }

    private static InventoryItemResponse mapItem(ResultSet rs, int rowNum) throws SQLException {
        int total = rs.getInt("total");
        int available = rs.getInt("available");
        int min = rs.getInt("min_quantity");
        java.sql.Date nearest = rs.getDate("nearest");
        return new InventoryItemResponse(rs.getLong("product_id"), rs.getString("name"), rs.getString("sku"),
                rs.getString("unit"), ProductType.valueOf(rs.getString("product_type")),
                rs.getBoolean("tracks_expiry"), total, available, min, available < min,
                nearest == null ? null : nearest.toLocalDate(), rs.getInt("expired"));
    }

    private static StockLotResponse mapLot(ResultSet rs, int rowNum) throws SQLException {
        java.sql.Date expiry = rs.getDate("expiry_date");
        return new StockLotResponse(rs.getLong("id"), rs.getString("lot_number"),
                expiry == null ? null : expiry.toLocalDate(), rs.getInt("quantity"), rs.getBoolean("is_default"),
                rs.getBoolean("expired"));
    }
}
