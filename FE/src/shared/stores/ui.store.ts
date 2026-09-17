import { create } from 'zustand';

interface Modal {
  isOpen: boolean;
  type?: string;
  data?: unknown;
}

interface Toast {
  id: string;
  type: 'success' | 'error' | 'info' | 'warning';
  message: string;
}

interface UIState {
  // Modal
  modal: Modal;
  openModal: (type: string, data?: unknown) => void;
  closeModal: () => void;

  // Toasts
  toasts: Toast[];
  addToast: (type: Toast['type'], message: string) => void;
  removeToast: (id: string) => void;

  // Sidebar (mobile)
  sidebarOpen: boolean;
  toggleSidebar: () => void;
  setSidebarOpen: (open: boolean) => void;

  // Global loading
  isGlobalLoading: boolean;
  setGlobalLoading: (loading: boolean) => void;
}

let toastId = 0;

export const useUIStore = create<UIState>()((set, get) => ({
  // Modal
  modal: { isOpen: false },
  openModal: (type, data) => set({ modal: { isOpen: true, type, data } }),
  closeModal: () => set({ modal: { isOpen: false } }),

  // Toasts
  toasts: [],
  addToast: (type, message) => {
    const id = `toast-${++toastId}`;
    set({ toasts: [...get().toasts, { id, type, message }] });

    // Auto remove after 4s
    setTimeout(() => {
      get().removeToast(id);
    }, 4000);
  },
  removeToast: (id) => {
    set({ toasts: get().toasts.filter(t => t.id !== id) });
  },

  // Sidebar
  sidebarOpen: false,
  toggleSidebar: () => set({ sidebarOpen: !get().sidebarOpen }),
  setSidebarOpen: (open) => set({ sidebarOpen: open }),

  // Global loading
  isGlobalLoading: false,
  setGlobalLoading: (loading) => set({ isGlobalLoading: loading }),
}));
