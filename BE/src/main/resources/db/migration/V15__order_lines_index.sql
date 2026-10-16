-- T19 (Order loi, ADR-0044). V1 khong co index cho order_lines.order_id: moi thao tac tren dong cua mot Order,
-- tinh lai tong tien va kiem FK khi xoa Order deu quet ca bang.
CREATE INDEX ix_order_lines_order_id ON order_lines (order_id);
