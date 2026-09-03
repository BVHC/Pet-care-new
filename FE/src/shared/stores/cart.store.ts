import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { CartItem } from '../types';

interface CartState {
  items: CartItem[];
  subtotal: number;
  discount: number;
  total: number;
  isLoading: boolean;

  // Actions
  setCart: (items: CartItem[], subtotal: number, discount: number, total: number) => void;
  addItem: (item: CartItem) => void;
  updateItem: (itemId: number, quantity: number) => void;
  removeItem: (itemId: number) => void;
  clear: () => void;
  clearCart: () => void;
  count: () => number;
  recalculate: () => void;
}

export const useCartStore = create<CartState>()(
  persist(
    (set, get) => ({
      items: [],
      subtotal: 0,
      discount: 0,
      total: 0,
      isLoading: false,

      setCart: (items, subtotal, discount, total) => set({ items, subtotal, discount, total }),

      addItem: (item) => {
        const { items } = get();
        const existing = items.find(i => i.productId === item.productId);

        if (existing) {
          // Update quantity
          set({
            items: items.map(i =>
              i.productId === item.productId
                ? { ...i, quantity: i.quantity + item.quantity, subtotal: (i.quantity + item.quantity) * i.unitPrice }
                : i
            ),
          });
        } else {
          set({ items: [...items, item] });
        }
        get().recalculate();
      },

      updateItem: (itemId, quantity) => {
        const { items } = get();
        if (quantity <= 0) {
          get().removeItem(itemId);
          return;
        }
        set({
          items: items.map(i =>
            i.id === itemId
              ? { ...i, quantity, subtotal: quantity * i.unitPrice }
              : i
          ),
        });
        get().recalculate();
      },

      removeItem: (itemId) => {
        set({ items: get().items.filter(i => i.id !== itemId) });
        get().recalculate();
      },

      clear: () => set({ items: [], subtotal: 0, discount: 0, total: 0 }),

      clearCart: () => set({ items: [], subtotal: 0, discount: 0, total: 0 }),

      count: () => get().items.reduce((sum, item) => sum + item.quantity, 0),

      recalculate: () => {
        const { items } = get();
        const subtotal = items.reduce((sum, item) => sum + item.subtotal, 0);
        const discount = 0; // Will be updated when voucher is applied
        const total = subtotal - discount;
        set({ subtotal, discount, total });
      },
    }),
    {
      name: 'cart-storage',
    }
  )
);
