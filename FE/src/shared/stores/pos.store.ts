import { create } from 'zustand';
import type { PaymentMethod } from '../types/clinic';

export interface RetailLine {
  productId: string;
  qty: number;
}

interface PosState {
  customerId: string | null;
  /** PENDING orders handed over by the exam room / grooming, paid together (BR-TG-03). */
  orderIds: string[];
  retail: RetailLine[];
  method: PaymentMethod;
  addProduct: (productId: string) => void;
  setQty: (productId: string, qty: number) => void;
  attachOrder: (orderId: string, customerId: string) => void;
  detachOrder: (orderId: string) => void;
  setCustomer: (customerId: string | null) => void;
  setMethod: (method: PaymentMethod) => void;
  clear: () => void;
}

const empty = () => ({ customerId: null, orderIds: [], retail: [], method: 'CASH' as PaymentMethod });

/** Reception counter (the plan's POSContext). Survives workspace switches until paid or cleared. */
export const usePosStore = create<PosState>()((set) => ({
  ...empty(),
  addProduct: (productId) =>
    set((s) => ({
      retail: s.retail.some((l) => l.productId === productId)
        ? s.retail.map((l) => (l.productId === productId ? { ...l, qty: l.qty + 1 } : l))
        : [...s.retail, { productId, qty: 1 }],
    })),
  setQty: (productId, qty) =>
    set((s) => ({
      retail:
        qty <= 0
          ? s.retail.filter((l) => l.productId !== productId)
          : s.retail.map((l) => (l.productId === productId ? { ...l, qty } : l)),
    })),
  attachOrder: (orderId, customerId) =>
    set((s) => {
      // One receipt = one customer (BR-TG-03): another customer's order starts a new selection.
      // Items picked for a previous customer stay with them; an anonymous sale joins the new customer.
      if (s.customerId !== customerId) return { customerId, orderIds: [orderId], retail: s.customerId ? [] : s.retail };
      return s.orderIds.includes(orderId) ? {} : { orderIds: [...s.orderIds, orderId] };
    }),
  detachOrder: (orderId) =>
    set((s) => {
      const orderIds = s.orderIds.filter((id) => id !== orderId);
      return { orderIds, customerId: orderIds.length > 0 ? s.customerId : null };
    }),
  setCustomer: (customerId) =>
    set((s) => (s.customerId === customerId ? {} : { customerId, orderIds: [], retail: s.customerId ? [] : s.retail })),
  setMethod: (method) => set({ method }),
  clear: () => set(empty()),
}));

/** Plan name for the reception workspace hook. */
export const usePOS = usePosStore;

export function posTotal(
  cart: { retail: RetailLine[]; orderIds: string[] },
  products: { id: string; price: number }[],
  pending: { id: string; total: number }[],
): number {
  const retail = cart.retail.reduce(
    (sum, l) => sum + (products.find((p) => p.id === l.productId)?.price ?? 0) * l.qty,
    0,
  );
  const handedOver = pending.filter((o) => cart.orderIds.includes(o.id)).reduce((sum, o) => sum + o.total, 0);
  return retail + handedOver;
}
