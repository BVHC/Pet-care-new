import { describe, expect, it } from 'vitest';
import type { VisitView } from '../types/clinic';
import { buildBoard } from './grooming-board';

const at = (min: number) => new Date(Date.parse('2026-10-02T09:00:00+07:00') + min * 60_000).toISOString();

const visit = (over: Partial<VisitView>): VisitView => ({
  id: 'v',
  queueNo: 1,
  status: 'WAITING',
  priority: 'WALK_IN',
  emergency: false,
  pet: { id: 'p', name: 'Bơ', species: 'DOG', breed: 'Corgi' },
  customer: { id: 'c', name: 'Trang', phone: '0903' },
  services: [{ id: 'svc-groom-bath', name: 'Tắm sấy', kind: 'GROOMING' }],
  assignee: { id: '6', name: 'Thu Hà' },
  checkedInAt: at(0),
  orderId: 'o',
  orderStatus: 'OPEN',
  orderTotal: 180_000,
  ...over,
});

const ids = (board: ReturnType<typeof buildBoard>, column: string) =>
  board.find((c) => c.id === column)!.items.map((v) => v.id);

describe('buildBoard (useKanban)', () => {
  it('keeps only grooming visits', () => {
    const board = buildBoard(
      [visit({ id: 'groom' }), visit({ id: 'exam', services: [{ id: 'svc-exam', name: 'Khám', kind: 'EXAM' }] })],
      { scope: 'all', userId: '6' },
    );
    expect(board.flatMap((c) => c.items.map((v) => v.id))).toEqual(['groom']);
  });

  it('places each visit by status and payment', () => {
    const board = buildBoard(
      [
        visit({ id: 'w', status: 'WAITING' }),
        visit({ id: 'p', status: 'IN_PROGRESS', calledAt: at(1) }),
        visit({ id: 'r', status: 'COMPLETED', orderStatus: 'PENDING', completedAt: at(2) }),
        visit({ id: 'd', status: 'COMPLETED', orderStatus: 'PAID', completedAt: at(3) }),
        visit({ id: 'x', status: 'CANCELLED', orderStatus: 'CANCELLED' }),
      ],
      { scope: 'all', userId: '6' },
    );
    expect(board.map((c) => [c.id, c.items.map((v) => v.id)])).toEqual([
      ['WAITING', ['w']],
      ['IN_PROGRESS', ['p']],
      ['READY', ['r']],
      ['PAID', ['d']],
    ]);
  });

  it('shows only my visits in "mine" scope', () => {
    const board = buildBoard(
      [visit({ id: 'mine' }), visit({ id: 'theirs', assignee: { id: 'care-2', name: 'Gia Huy' } }), visit({ id: 'nobody', assignee: undefined })],
      { scope: 'mine', userId: '6' },
    );
    expect(ids(board, 'WAITING')).toEqual(['mine']);
  });

  it('orders the waiting column by queue priority (BR-TN-04)', () => {
    const board = buildBoard(
      [
        visit({ id: 'walk-in', checkedInAt: at(-10) }),
        visit({ id: 'appointment', priority: 'APPOINTMENT', scheduledAt: at(5), checkedInAt: at(0) }),
      ],
      { scope: 'all', userId: '6' },
    );
    expect(ids(board, 'WAITING')).toEqual(['appointment', 'walk-in']);
  });

  it('sums what each column is worth', () => {
    const board = buildBoard(
      [visit({ id: 'a', orderTotal: 180_000 }), visit({ id: 'b', orderTotal: 350_000 })],
      { scope: 'all', userId: '6' },
    );
    expect(board.find((c) => c.id === 'WAITING')!.total).toBe(530_000);
  });
});
