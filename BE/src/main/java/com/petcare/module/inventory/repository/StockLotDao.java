package com.petcare.module.inventory.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.petcare.module.inventory.entity.LockedLot;
import com.petcare.module.inventory.entity.MovementType;

/**
 * Đường ghi duy nhất của {@code inventory_items}, {@code stock_lots}, {@code stock_movements} (docs/adr/0042). SQL thuần
 * (không entity JPA) để số dư luôn lấy từ câu khóa, không có bản sao cũ trong persistence context. Không tự mở
 * transaction: chạy trên connection của use case ({@code JpaTransactionManager}); mọi câu khóa do bên gọi bảo đảm
 * {@code MANDATORY} qua {@code StockLedger}. Chỉ {@code StockLedger} gọi {@link #updateQuantity} và
 * {@link #insertMovement}.
 * <p>
 * <b>Khóa lô luôn bằng một câu, id tăng dần</b> ({@code ORDER BY id FOR NO KEY UPDATE}): không {@code LIMIT 1 … FOR UPDATE}
 * vì PostgreSQL kiểm lại {@code WHERE} sau khi chờ khóa và có thể trả 0 dòng dù còn lô khác đủ.
 */
@Repository
public class StockLotDao {

    private static final RowMapper<LockedLot> LOT_MAPPER = StockLotDao::mapLot;

    private static final String LOT_COLUMNS = """
            l.id, l.inventory_item_id, i.branch_id, i.product_id, l.lot_number, l.expiry_date, l.quantity, l.is_default
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public StockLotDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- tạo dòng tồn / lô (không khóa dòng đã có)

    /**
     * Bảo đảm có dòng tồn của (chi nhánh, sản phẩm) và trả id. {@code ON CONFLICT DO NOTHING} + SELECT thay vì
     * {@code DO UPDATE}: không khóa dòng đã có, nên không đảo thứ tự khóa so với luồng chỉ khóa lô. Bên gọi gọi theo
     * thứ tự sản phẩm tăng dần để hai phiếu cùng chèn không chờ nhau vòng tròn.
     */
    public long ensureItem(long branchId, long productId) {
        MapSqlParameterSource params = new MapSqlParameterSource("branchId", branchId).addValue("productId", productId);
        jdbc.update("""
                INSERT INTO inventory_items (branch_id, product_id) VALUES (:branchId, :productId)
                ON CONFLICT (branch_id, product_id) DO NOTHING
                """, params);
        return jdbc.queryForObject("SELECT id FROM inventory_items WHERE branch_id = :branchId AND product_id = :productId",
                params, Long.class);
    }

    /** Lô thường theo (dòng tồn, số lô, hạn): nhập lại cùng số lô và hạn thì dùng lại lô cũ (erd §9). Số dư lô mới là 0. */
    public long ensureLot(long inventoryItemId, String lotNumber, LocalDate expiryDate) {
        MapSqlParameterSource params = new MapSqlParameterSource("item", inventoryItemId)
                .addValue("lot", lotNumber).addValue("expiry", expiryDate);
        jdbc.update("""
                INSERT INTO stock_lots (inventory_item_id, lot_number, expiry_date, quantity, is_default)
                VALUES (:item, :lot, :expiry, 0, false)
                ON CONFLICT (inventory_item_id, lot_number, expiry_date) WHERE NOT is_default DO NOTHING
                """, params);
        return jdbc.queryForObject("""
                SELECT id FROM stock_lots
                WHERE inventory_item_id = :item AND lot_number = :lot AND expiry_date = :expiry AND NOT is_default
                """, params, Long.class);
    }

    /** Lô mặc định duy nhất của sản phẩm không quản lý hạn dùng (04 nguyên tắc 4). */
    public long ensureDefaultLot(long inventoryItemId) {
        MapSqlParameterSource params = new MapSqlParameterSource("item", inventoryItemId);
        jdbc.update("""
                INSERT INTO stock_lots (inventory_item_id, quantity, is_default) VALUES (:item, 0, true)
                ON CONFLICT (inventory_item_id) WHERE is_default DO NOTHING
                """, params);
        return jdbc.queryForObject("SELECT id FROM stock_lots WHERE inventory_item_id = :item AND is_default", params,
                Long.class);
    }

    // ---------------------------------------------------------------- khóa lô

    /** Khóa các lô theo id tăng dần; id không tồn tại bị bỏ qua. */
    public List<LockedLot> lockLots(Collection<Long> lotIds) {
        if (lotIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT " + LOT_COLUMNS + """
                FROM stock_lots l JOIN inventory_items i ON i.id = l.inventory_item_id
                WHERE l.id IN (:ids) ORDER BY l.id FOR NO KEY UPDATE OF l
                """, new MapSqlParameterSource("ids", lotIds), LOT_MAPPER);
    }

    /** Như {@link #lockLots} nhưng chỉ lô thuộc chi nhánh {@code branchId}: lô chi nhánh khác coi như không có. */
    public List<LockedLot> lockLotsInBranch(Collection<Long> lotIds, long branchId) {
        if (lotIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT " + LOT_COLUMNS + """
                FROM stock_lots l JOIN inventory_items i ON i.id = l.inventory_item_id
                WHERE l.id IN (:ids) AND i.branch_id = :branchId ORDER BY l.id FOR NO KEY UPDATE OF l
                """, new MapSqlParameterSource("ids", lotIds).addValue("branchId", branchId), LOT_MAPPER);
    }

    /**
     * Lô ứng viên để xuất của (chi nhánh, sản phẩm): còn số dư và chưa hết hạn ({@code expiry_date >= today}), khóa theo
     * id tăng dần. Kiểm lại {@code WHERE} sau khi chờ khóa nên lô vừa bị trừ hết bị loại đúng.
     */
    public List<LockedLot> lockIssuableLots(long branchId, long productId, LocalDate today) {
        return jdbc.query("SELECT " + LOT_COLUMNS + """
                FROM stock_lots l JOIN inventory_items i ON i.id = l.inventory_item_id
                WHERE i.branch_id = :branchId AND i.product_id = :productId AND l.quantity > 0
                  AND (l.expiry_date IS NULL OR l.expiry_date >= :today)
                ORDER BY l.id FOR NO KEY UPDATE OF l
                """, new MapSqlParameterSource("branchId", branchId).addValue("productId", productId)
                .addValue("today", today), LOT_MAPPER);
    }

    /**
     * Lô ứng viên để xuất của <b>nhiều</b> sản phẩm cùng chi nhánh, khóa trong <b>một câu</b> theo id lô tăng dần (không
     * theo sản phẩm): cùng thứ tự với {@link #lockLots} nên bán nhiều mặt hàng không deadlock với xác nhận phiếu nhập /
     * điều chỉnh chạm các lô đó theo thứ tự khác (docs/adr/0042).
     */
    public List<LockedLot> lockIssuableLots(long branchId, Collection<Long> productIds, LocalDate today) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT " + LOT_COLUMNS + """
                FROM stock_lots l JOIN inventory_items i ON i.id = l.inventory_item_id
                WHERE i.branch_id = :branchId AND i.product_id IN (:productIds) AND l.quantity > 0
                  AND (l.expiry_date IS NULL OR l.expiry_date >= :today)
                ORDER BY l.id FOR NO KEY UPDATE OF l
                """, new MapSqlParameterSource("branchId", branchId).addValue("productIds", productIds)
                .addValue("today", today), LOT_MAPPER);
    }

    // ---------------------------------------------------------------- ghi (chỉ StockLedger gọi)

    public void updateQuantity(long lotId, int quantity, Instant now) {
        jdbc.update("UPDATE stock_lots SET quantity = :quantity, updated_at = :now WHERE id = :id",
                new MapSqlParameterSource("quantity", quantity).addValue("now", Timestamp.from(now))
                        .addValue("id", lotId));
    }

    public void insertMovement(long lotId, MovementType type, int delta, int balanceAfter, String sourceType,
            long sourceId, long actorId, Instant now) {
        jdbc.update("""
                INSERT INTO stock_movements (stock_lot_id, movement_type, quantity_delta, balance_after, source_type,
                                             source_id, created_by, created_at)
                VALUES (:lot, :type, :delta, :balance, :sourceType, :sourceId, :actor, :now)
                """, new MapSqlParameterSource("lot", lotId).addValue("type", type.name()).addValue("delta", delta)
                .addValue("balance", balanceAfter).addValue("sourceType", sourceType)
                .addValue("sourceId", sourceId).addValue("actor", actorId).addValue("now", Timestamp.from(now)));
    }

    private static LockedLot mapLot(ResultSet rs, int rowNum) throws SQLException {
        java.sql.Date expiry = rs.getDate("expiry_date");
        return new LockedLot(rs.getLong("id"), rs.getLong("inventory_item_id"), rs.getLong("branch_id"),
                rs.getLong("product_id"), rs.getString("lot_number"), expiry == null ? null : expiry.toLocalDate(),
                rs.getInt("quantity"), rs.getBoolean("is_default"));
    }
}
