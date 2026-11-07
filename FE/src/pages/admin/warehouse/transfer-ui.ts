// Màu trạng thái của phiếu chuyển kho và phiên kiểm kê (luôn kèm chữ ở StatusBadge).
export const TRANSFER_STATUS_VARIANT = { IN_TRANSIT: 'info', RECEIVED: 'success', CANCELLED: 'danger' } as const;
export const COUNT_STATUS_VARIANT = { OPEN: 'info', CONFIRMED: 'success', CANCELLED: 'danger' } as const;
