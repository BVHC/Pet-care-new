import { describe, expect, it } from 'vitest';
import type { BoardColumnId } from './clinic-rules';
import {
  arrivalState,
  boardColumnOf,
  callViolation,
  canTransitionVisit,
  completeViolation,
  dropTransition,
  lineTotal,
  paymentViolation,
  positionFor,
  queuePriority,
  retailViolation,
  sortQueue,
} from './clinic-rules';
import type { VisitStatus } from '../types/clinic';

const BASE = Date.parse('2026-10-02T09:00:00+07:00');
const at = (min: number) => new Date(BASE + min * 60_000).toISOString();

describe('Visit FSM (BR-TN-09)', () => {
  const STATES: VisitStatus[] = ['WAITING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'];
  const ALLOWED = new Set(['WAITING>IN_PROGRESS', 'WAITING>CANCELLED', 'IN_PROGRESS>COMPLETED']);

  for (const from of STATES) {
    for (const to of STATES) {
      const edge = `${from}>${to}`;
      it(`${ALLOWED.has(edge) ? 'allows' : 'rejects'} ${edge}`, () => {
        expect(canTransitionVisit(from, to)).toBe(ALLOWED.has(edge));
      });
    }
  }
});

describe('calling a visit (BR-TN-05)', () => {
  it('lets the assigned staff call a waiting visit', () => {
    expect(callViolation({ status: 'WAITING', assigneeId: 'vet-1' }, 'vet-1')).toBeNull();
  });

  it('refuses an unassigned visit', () => {
    expect(callViolation({ status: 'WAITING', assigneeId: undefined }, 'vet-1')?.code).toBe('BR-TN-05');
  });

  it("refuses a colleague's visit", () => {
    expect(callViolation({ status: 'WAITING', assigneeId: 'vet-2' }, 'vet-1')?.code).toBe('BR-TN-05');
  });

  it('refuses a visit that was already called', () => {
    expect(callViolation({ status: 'IN_PROGRESS', assigneeId: 'vet-1' }, 'vet-1')?.code).toBe(
      'INVALID_STATE_TRANSITION',
    );
  });
});

describe('completing a visit (BR-KB-02)', () => {
  const base = { status: 'IN_PROGRESS' as const, assessment: '', vaccineLines: 0 };

  it('requires a diagnosis for an exam', () => {
    expect(completeViolation({ ...base, kinds: ['EXAM'] })?.code).toBe('BR-KB-02');
  });

  it('treats a blank diagnosis as missing', () => {
    expect(completeViolation({ ...base, kinds: ['EXAM'], assessment: '   ' })?.code).toBe('BR-KB-02');
  });

  it('completes an exam once diagnosed', () => {
    expect(completeViolation({ ...base, kinds: ['EXAM'], assessment: 'Viêm da dị ứng' })).toBeNull();
  });

  it('requires one recorded injection for a vaccination-only visit', () => {
    expect(completeViolation({ ...base, kinds: ['VACCINATION'] })?.code).toBe('BR-KB-02');
  });

  it('completes a vaccination-only visit without a diagnosis once injected', () => {
    expect(completeViolation({ ...base, kinds: ['VACCINATION'], vaccineLines: 1 })).toBeNull();
  });

  it('puts no extra condition on grooming', () => {
    expect(completeViolation({ ...base, kinds: ['GROOMING'] })).toBeNull();
  });

  it('refuses a visit that is not in progress', () => {
    expect(completeViolation({ ...base, status: 'WAITING', kinds: ['GROOMING'] })?.code).toBe(
      'INVALID_STATE_TRANSITION',
    );
  });
});

describe('queue priority (BR-TN-03, BR-TN-04)', () => {
  it('puts emergencies in the emergency band even with an appointment', () => {
    expect(queuePriority({ emergency: true, scheduledAt: at(0), checkedInAt: at(0) })).toBe('EMERGENCY');
  });

  it('keeps appointment priority up to 15 minutes late', () => {
    expect(queuePriority({ emergency: false, scheduledAt: at(0), checkedInAt: at(15) })).toBe('APPOINTMENT');
  });

  it('queues an appointment more than 15 minutes late as walk-in', () => {
    expect(queuePriority({ emergency: false, scheduledAt: at(0), checkedInAt: at(16) })).toBe('WALK_IN');
  });

  it('queues a visit without appointment as walk-in', () => {
    expect(queuePriority({ emergency: false, checkedInAt: at(0) })).toBe('WALK_IN');
  });

  it('orders emergency, then appointments by time, then walk-ins by arrival', () => {
    const queue = sortQueue([
      { id: 'walk-early', priority: 'WALK_IN' as const, checkedInAt: at(-20) },
      { id: 'appt-late-slot', priority: 'APPOINTMENT' as const, scheduledAt: at(30), checkedInAt: at(5) },
      { id: 'walk-late', priority: 'WALK_IN' as const, checkedInAt: at(-5) },
      { id: 'emergency', priority: 'EMERGENCY' as const, checkedInAt: at(10) },
      { id: 'appt-early-slot', priority: 'APPOINTMENT' as const, scheduledAt: at(0), checkedInAt: at(8) },
    ]);
    expect(queue.map((v) => v.id)).toEqual([
      'emergency',
      'appt-early-slot',
      'appt-late-slot',
      'walk-early',
      'walk-late',
    ]);
  });
});

describe('check-in window (BR-TN-02, BR-LH-08)', () => {
  it('hides appointments more than 30 minutes ahead', () => {
    expect(arrivalState(at(31), BASE)).toBe('UPCOMING');
  });

  it('opens check-in 30 minutes before the slot', () => {
    expect(arrivalState(at(30), BASE)).toBe('OPEN');
  });

  it('still accepts check-in 30 minutes late', () => {
    expect(arrivalState(at(-30), BASE)).toBe('OPEN');
  });

  it('marks the appointment no-show after 30 minutes', () => {
    expect(arrivalState(at(-31), BASE)).toBe('NO_SHOW');
  });
});

describe('counter sales and payment', () => {
  it('blocks prescription-only products from retail (BR-SP-01)', () => {
    expect(retailViolation({ name: 'Amoxicillin', prescriptionOnly: true })?.code).toBe('BR-SP-01');
    expect(retailViolation({ name: 'Pate', prescriptionOnly: false })).toBeNull();
  });

  it('sums unit price times quantity', () => {
    expect(lineTotal([{ unitPrice: 120_000, qty: 2 }, { unitPrice: 50_000, qty: 1 }])).toBe(290_000);
  });

  const shift = { status: 'OPEN' as const, receptionistId: 'rec-1' };
  const pending = { status: 'PENDING' as const, customerId: 'c1' };

  it("needs the cashier's own open shift (BR-TG-01)", () => {
    expect(paymentViolation({ orders: [pending], shift: null, receptionistId: 'rec-1' })?.code).toBe('BR-TG-01');
    expect(paymentViolation({ orders: [pending], shift, receptionistId: 'rec-2' })?.code).toBe('BR-TG-01');
  });

  it('only collects pending orders (BR-TG-01)', () => {
    const open = { status: 'OPEN' as const, customerId: 'c1' };
    expect(paymentViolation({ orders: [open], shift, receptionistId: 'rec-1' })?.code).toBe('BR-TG-01');
  });

  it('only combines orders of one customer (BR-TG-03)', () => {
    const other = { status: 'PENDING' as const, customerId: 'c2' };
    expect(paymentViolation({ orders: [pending, other], shift, receptionistId: 'rec-1' })?.code).toBe('BR-TG-03');
  });

  it('refuses an empty payment', () => {
    expect(paymentViolation({ orders: [], shift, receptionistId: 'rec-1' })?.code).toBe('NOTHING_TO_PAY');
  });

  it('accepts pending orders of one customer on an open shift', () => {
    expect(paymentViolation({ orders: [pending, pending], shift, receptionistId: 'rec-1' })).toBeNull();
  });
});

describe('grooming board', () => {
  it('places visits by visit status and payment', () => {
    expect(boardColumnOf({ status: 'WAITING', orderStatus: 'OPEN' })).toBe('WAITING');
    expect(boardColumnOf({ status: 'IN_PROGRESS', orderStatus: 'OPEN' })).toBe('IN_PROGRESS');
    expect(boardColumnOf({ status: 'COMPLETED', orderStatus: 'PENDING' })).toBe('READY');
    expect(boardColumnOf({ status: 'COMPLETED', orderStatus: 'PAID' })).toBe('PAID');
    expect(boardColumnOf({ status: 'CANCELLED', orderStatus: 'CANCELLED' })).toBeNull();
  });

  const COLUMNS: BoardColumnId[] = ['WAITING', 'IN_PROGRESS', 'READY', 'PAID'];
  const DROPS: Record<string, VisitStatus> = {
    'WAITING>IN_PROGRESS': 'IN_PROGRESS',
    'IN_PROGRESS>READY': 'COMPLETED',
  };
  for (const from of COLUMNS) {
    for (const to of COLUMNS) {
      const edge = `${from}>${to}`;
      it(`maps drop ${edge} to ${DROPS[edge] ?? 'nothing'}`, () => {
        expect(dropTransition(from, to)).toBe(DROPS[edge] ?? null);
      });
    }
  }
});

describe('staff assignment (BR-TN-05)', () => {
  it('sends exams and vaccinations to a VET and grooming to a CARETAKER', () => {
    expect(positionFor('EXAM')).toBe('VET');
    expect(positionFor('VACCINATION')).toBe('VET');
    expect(positionFor('GROOMING')).toBe('CARETAKER');
  });
});
