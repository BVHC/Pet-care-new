import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type PaymentMethod = 'VNPAY' | 'MOMO' | 'COD';
export type PaymentStatus = 'PENDING' | 'SUCCESS' | 'FAILED';
export type OrderStatus = 'PENDING' | 'CONFIRMED' | 'PROCESSING' | 'SHIPPED' | 'DELIVERED' | 'CANCELLED';

export interface ShipTo {
  fullName: string;
  phone: string;
  address: string;
  city: string;
  note?: string;
}

export interface OrderLine {
  id: number;
  productId: number;
  productName: string;
  quantity: number;
  unitPrice: number;
  subtotal: number;
  image?: string;
}

export interface Order {
  id: string;
  code: string;
  lines: OrderLine[];
  subtotal: number;
  shipping: number;
  discount: number;
  total: number;
  paymentMethod: PaymentMethod;
  paymentStatus: PaymentStatus;
  status: OrderStatus;
  shipTo: ShipTo;
  createdAt: string;
}

interface OrderState {
  orders: Order[];
  currentOrder: Order | null;

  createOrder: (data: {
    lines: { id: number; qty: number }[];
    subtotal: number;
    shipping: number;
    total: number;
    paymentMethod: PaymentMethod;
    shipTo: ShipTo;
  }) => Order;
  getOrderById: (id: string) => Order | undefined;
  clearCurrentOrder: () => void;
}

let orderCounter = 1;

export const useOrderStore = create<OrderState>()(
  persist(
    (set, get) => ({
      orders: [],
      currentOrder: null,

      createOrder: (data) => {
        const order: Order = {
          id: `order-${Date.now()}-${orderCounter++}`,
          code: `PET${String(orderCounter).padStart(6, '0')}`,
          lines: data.lines.map((l) => ({
            id: l.id,
            productId: l.id,
            productName: `Sản phẩm #${l.id}`,
            quantity: l.qty,
            unitPrice: 0,
            subtotal: 0,
          })),
          subtotal: data.subtotal,
          shipping: data.shipping,
          discount: 0,
          total: data.total,
          paymentMethod: data.paymentMethod,
          paymentStatus: data.paymentMethod === 'COD' ? 'SUCCESS' : 'PENDING',
          status: 'PENDING',
          shipTo: data.shipTo,
          createdAt: new Date().toISOString(),
        };

        set((state) => ({
          orders: [order, ...state.orders],
          currentOrder: order,
        }));

        return order;
      },

      getOrderById: (id) => {
        const { orders, currentOrder } = get();
        return orders.find((o) => o.id === id) || (currentOrder?.id === id ? currentOrder : undefined);
      },

      clearCurrentOrder: () => set({ currentOrder: null }),
    }),
    {
      name: 'order-storage',
    }
  )
);
