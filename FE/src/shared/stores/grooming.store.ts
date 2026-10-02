import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { BoardScope } from '../utils/grooming-board';

interface GroomingState {
  /** null until the user picks: groomers then default to their own cards, managers to all. */
  scope: BoardScope | null;
  setScope: (scope: BoardScope) => void;
}

export const useGroomingStore = create<GroomingState>()(
  persist((set) => ({ scope: null, setScope: (scope) => set({ scope }) }), { name: 'petcare-grooming-scope' }),
);
