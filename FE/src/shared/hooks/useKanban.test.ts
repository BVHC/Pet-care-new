import { describe, expect, it } from 'vitest';
import type { VisitView } from '../types/clinic';
import { moveRejection } from './useKanban';

const ME = '6';
const card = (over: Partial<VisitView>): VisitView => ({
  id: 'v1',
  queueNo: 7,
  status: 'WAITING',
  priority: 'WALK_IN',
  emergency: false,
  pet: { id: 'p', name: 'Rocky', species: 'DOG', breed: 'Husky' },
  customer: { id: 'c', name: 'Tuấn', phone: '0981' },
  services: [{ id: 's', name: 'Tắm và cắt tỉa trọn gói', kind: 'GROOMING' }],
  assignee: { id: ME, name: 'Thu Hà' },
  checkedInAt: '2026-10-02T02:00:00.000Z',
  orderId: 'o',
  orderStatus: 'OPEN',
  orderTotal: 350_000,
  ...over,
});

describe('moveRejection', () => {
  it('lets me start my waiting card', () => {
    expect(moveRejection(card({}), 'IN_PROGRESS', ME)).toBeNull();
  });

  it('lets me finish my card in progress', () => {
    expect(moveRejection(card({ status: 'IN_PROGRESS' }), 'READY', ME)).toBeNull();
  });

  it('sends payment to the front desk', () => {
    expect(moveRejection(card({ status: 'COMPLETED', orderStatus: 'PENDING' }), 'PAID', ME)?.code).toBe('BR-TG-01');
  });

  it('never moves a card backwards (BR-TN-09)', () => {
    expect(moveRejection(card({ status: 'IN_PROGRESS' }), 'WAITING', ME)?.code).toBe('BR-TN-09');
  });

  it('does not skip "Đang làm"', () => {
    expect(moveRejection(card({}), 'READY', ME)?.code).toBe('BR-TN-09');
  });

  it("refuses to start a colleague's card (BR-TN-05)", () => {
    expect(moveRejection(card({ assignee: { id: 'care-2', name: 'Gia Huy' } }), 'IN_PROGRESS', ME)?.code).toBe('BR-TN-05');
  });

  it("refuses to finish a colleague's card", () => {
    const theirs = card({ status: 'IN_PROGRESS', assignee: { id: 'care-2', name: 'Gia Huy' } });
    expect(moveRejection(theirs, 'READY', ME)?.code).toBe('ACCESS_DENIED_SCOPE');
  });
});
