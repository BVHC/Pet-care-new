import { beforeEach, describe, expect, it } from 'vitest';
import type { MedicalRecord } from '../types/clinic';
import { useConsultationStore } from './consultation.store';

const record = (assessment = ''): MedicalRecord => ({
  subjective: 'Ho, sổ mũi 3 ngày',
  objective: { findings: 'Phổi rale nhẹ' },
  assessment,
  plan: '',
  internalNote: '',
});

const soap = () => useConsultationStore.getState();

beforeEach(() => {
  useConsultationStore.setState({ drafts: {}, activeVisitId: null });
});

describe('SOAP drafts (useConsultation)', () => {
  it('starts a draft from the saved record', () => {
    soap().ensureDraft('v1', record('Cảm cúm'));
    expect(soap().drafts.v1.record.assessment).toBe('Cảm cúm');
  });

  it('starts an empty draft when nothing was saved yet', () => {
    soap().ensureDraft('v1', null);
    expect(soap().drafts.v1.record).toMatchObject({ subjective: '', assessment: '', objective: { findings: '' } });
  });

  it('keeps unsaved local edits over the server copy', () => {
    soap().ensureDraft('v1', record());
    soap().update('v1', { assessment: 'Viêm da dị ứng' });
    soap().ensureDraft('v1', record('bản cũ'));
    expect(soap().drafts.v1.record.assessment).toBe('Viêm da dị ứng');
  });

  it('refreshes a clean draft from the server', () => {
    soap().ensureDraft('v1', record('A'));
    soap().ensureDraft('v1', record('B'));
    expect(soap().drafts.v1.record.assessment).toBe('B');
  });

  it('merges objective fields instead of replacing them', () => {
    soap().ensureDraft('v1', record());
    soap().update('v1', { objective: { temperatureC: 39.2 } });
    expect(soap().drafts.v1.record.objective).toEqual({ findings: 'Phổi rale nhẹ', temperatureC: 39.2 });
  });

  it('marks the draft clean once its latest revision is saved', () => {
    soap().ensureDraft('v1', record());
    soap().update('v1', { plan: 'Tái khám sau 5 ngày' });
    soap().markSaved('v1', soap().drafts.v1.rev, '2026-10-02T02:00:00.000Z');
    expect(soap().drafts.v1).toMatchObject({ dirty: false, savedAt: '2026-10-02T02:00:00.000Z' });
  });

  it('stays dirty when edits arrive while a save is in flight', () => {
    soap().ensureDraft('v1', record());
    soap().update('v1', { plan: 'A' });
    const sentRev = soap().drafts.v1.rev;
    soap().update('v1', { plan: 'AB' });
    soap().markSaved('v1', sentRev, '2026-10-02T02:00:00.000Z');
    expect(soap().drafts.v1.dirty).toBe(true);
  });
});
