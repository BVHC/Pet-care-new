import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { MedicalRecord } from '../types/clinic';

export interface SoapDraft {
  record: MedicalRecord;
  /** Local edits not yet acknowledged by the server. */
  dirty: boolean;
  /** Bumped on every edit, so a save only clears `dirty` for the revision it carried. */
  rev: number;
  savedAt?: string;
}

export type RecordPatch = Partial<Omit<MedicalRecord, 'objective'>> & {
  objective?: Partial<MedicalRecord['objective']>;
};

interface ConsultationState {
  drafts: Record<string, SoapDraft>;
  activeVisitId: string | null;
  select: (visitId: string | null) => void;
  ensureDraft: (visitId: string, saved: MedicalRecord | null) => void;
  update: (visitId: string, patch: RecordPatch) => void;
  markSaved: (visitId: string, rev: number, savedAt: string) => void;
  discard: (visitId: string) => void;
}

export const emptyRecord = (): MedicalRecord => ({
  subjective: '',
  objective: { findings: '' },
  assessment: '',
  plan: '',
  internalNote: '',
});

/** Exam-room state (the plan's ConsultationContext). Drafts persist so a reload never loses notes. */
export const useConsultationStore = create<ConsultationState>()(
  persist(
    (set) => ({
      drafts: {},
      activeVisitId: null,
      select: (activeVisitId) => set({ activeVisitId }),
      ensureDraft: (visitId, saved) =>
        set((s) => {
          const current = s.drafts[visitId];
          if (current?.dirty) return {}; // never overwrite unsaved typing
          return {
            drafts: {
              ...s.drafts,
              [visitId]: {
                record: saved ? structuredClone(saved) : emptyRecord(),
                dirty: false,
                rev: current?.rev ?? 0,
                savedAt: saved?.updatedAt ?? current?.savedAt,
              },
            },
          };
        }),
      update: (visitId, patch) =>
        set((s) => {
          const current = s.drafts[visitId] ?? { record: emptyRecord(), dirty: false, rev: 0 };
          const record = {
            ...current.record,
            ...patch,
            objective: { ...current.record.objective, ...patch.objective },
          };
          return { drafts: { ...s.drafts, [visitId]: { ...current, record, dirty: true, rev: current.rev + 1 } } };
        }),
      markSaved: (visitId, rev, savedAt) =>
        set((s) => {
          const current = s.drafts[visitId];
          if (!current) return {};
          return { drafts: { ...s.drafts, [visitId]: { ...current, savedAt, dirty: current.rev !== rev } } };
        }),
      discard: (visitId) =>
        set((s) => {
          const drafts = { ...s.drafts };
          delete drafts[visitId];
          return { drafts };
        }),
    }),
    { name: 'petcare-soap-drafts', partialize: (s) => ({ drafts: s.drafts }) },
  ),
);

/** Plan name for the exam-room hook. */
export const useConsultation = useConsultationStore;
