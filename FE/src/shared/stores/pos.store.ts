import { create } from 'zustand';
import type { PaymentMethod } from '../types/sales';

interface PosState {
  /** Khách của phiếu thu (một phiếu = một khách, BR-TG-03). */
  customerId: number | null;
  /** Order PENDING chọn thu gộp; id số của sales-v1. */
  orderIds: number[];
  method: PaymentMethod;
  /** Nút "Thu tiền" ở tab Chờ thu: chọn Order này; Order của khách khác thì bắt đầu lại phiếu mới. */
  attachOrder: (orderId: number, customerId: number) => void;
  /** Thay cả danh sách chọn (ô tick trên phiếu thu). Khách được giữ kể cả khi bỏ chọn hết. */
  setOrderIds: (orderIds: number[]) => void;
  setCustomer: (customerId: number | null) => void;
  setMethod: (method: PaymentMethod) => void;
  clear: () => void;
}

const empty = () => ({ customerId: null, orderIds: [], method: 'CASH' as PaymentMethod });

/** Quầy thu tiền của lễ tân (POSContext của kế hoạch). Còn nguyên khi đổi bàn làm việc, tới khi thu xong hoặc xóa phiếu. */
export const usePosStore = create<PosState>()((set) => ({
  ...empty(),
  attachOrder: (orderId, customerId) =>
    set((s) => {
      if (s.customerId !== customerId) return { customerId, orderIds: [orderId] };
      return s.orderIds.includes(orderId) ? {} : { orderIds: [...s.orderIds, orderId] };
    }),
  setOrderIds: (orderIds) => set({ orderIds }),
  setCustomer: (customerId) => set((s) => (s.customerId === customerId ? {} : { customerId, orderIds: [] })),
  setMethod: (method) => set({ method }),
  clear: () => set(empty()),
}));

/** Tên theo kế hoạch cho hook của bàn lễ tân. */
export const usePOS = usePosStore;
