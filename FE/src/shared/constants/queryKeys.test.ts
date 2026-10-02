import { describe, expect, it } from 'vitest';
import { INVALIDATE, QUERY_KEYS } from './queryKeys';

describe('QUERY_KEYS', () => {
  it('prefixes every key of a resource with its root so invalidating the root reaches them', () => {
    const families: (readonly unknown[])[][] = [
      [QUERY_KEYS.visits.all, QUERY_KEYS.visits.today(), QUERY_KEYS.visits.detail('v1')],
      [QUERY_KEYS.appointments.all, QUERY_KEYS.appointments.arrivals()],
      [QUERY_KEYS.orders.all, QUERY_KEYS.orders.pending()],
      [QUERY_KEYS.customers.all, QUERY_KEYS.customers.search('mochi')],
      [QUERY_KEYS.shift.all, QUERY_KEYS.shift.current('4')],
      [QUERY_KEYS.dashboard.all, QUERY_KEYS.dashboard.summary()],
    ];
    for (const [root, ...keys] of families) {
      for (const key of keys) expect(key.slice(0, root.length)).toEqual(root);
    }
  });

  it('keeps resources apart', () => {
    const roots = Object.values(QUERY_KEYS).map((family) => family.all[0]);
    expect(new Set(roots).size).toBe(roots.length);
  });
});

describe('INVALIDATE', () => {
  it('refreshes the cashier queue when a visit is completed in another workspace', () => {
    expect(INVALIDATE.completeVisit).toContainEqual(QUERY_KEYS.orders.all);
  });

  it('refreshes the grooming board and stock after a payment', () => {
    expect(INVALIDATE.collectPayment).toEqual(
      expect.arrayContaining([QUERY_KEYS.visits.all, QUERY_KEYS.catalog.all, QUERY_KEYS.shift.all]),
    );
  });

  it('refreshes arrivals and the queue after a check-in', () => {
    expect(INVALIDATE.checkIn).toEqual(
      expect.arrayContaining([QUERY_KEYS.visits.all, QUERY_KEYS.appointments.all, QUERY_KEYS.orders.all]),
    );
  });
});
