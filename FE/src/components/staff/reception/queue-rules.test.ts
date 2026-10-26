import { describe, expect, it } from 'vitest';
import type { Visit } from '../../../shared/types/visit';
import { moveTargets, reassignable } from './queue-rules';

const visit = (visitId: number, over: Partial<Visit> = {}): Visit => ({
  visitId,
  code: `TN-2610-${String(visitId).padStart(5, '0')}`,
  branchId: 1,
  customerId: 1,
  customerName: 'Khách',
  petId: visitId,
  petName: `Thú ${visitId}`,
  species: 'DOG',
  appointmentId: null,
  serviceGroup: 'MEDICAL',
  status: 'WAITING',
  priorityClass: 'WALK_IN',
  queueSortKey: '2026-10-22T03:00:00Z',
  assigneeId: null,
  assigneeName: null,
  needsReassign: false,
  orderId: visitId,
  checkedInAt: '2026-10-22T03:00:00Z',
  calledAt: null,
  completedAt: null,
  cancelledAt: null,
  cancelReason: null,
  ...over,
});

describe('moveTargets (BR-TN-04: chỉ dùng thứ tự mảng BE trả, không so khóa sắp xếp)', () => {
  const e1 = visit(1, { priorityClass: 'EMERGENCY' });
  const e2 = visit(2, { priorityClass: 'EMERGENCY', status: 'IN_PROGRESS', assigneeId: 5 });
  const a1 = visit(3, { priorityClass: 'APPOINTMENT', appointmentId: 9 });
  const w1 = visit(4, { status: 'IN_PROGRESS', assigneeId: 5 });
  const w2 = visit(5);
  // Khóa của w3 nhỏ hơn w2: nếu FE tự sắp lại theo khóa thì w3 đứng trước w2; BE đặt w3 sau w2 nên danh sách đích phải giữ thứ tự mảng.
  const w3 = visit(6, { queueSortKey: '2026-10-22T02:00:00Z' });
  const queue = [e1, e2, a1, w1, w2, w3];

  it('lượt walk-in: các lượt WAITING đứng trước nó ở cùng phía ranh giới cấp cứu, theo đúng thứ tự mảng', () => {
    expect(moveTargets(queue, w3).map((v) => v.visitId)).toEqual([3, 5]);
  });

  it('bỏ lượt đã gọi (đích phải còn WAITING) và lượt ở phía bên kia ranh giới cấp cứu', () => {
    expect(moveTargets(queue, w2).map((v) => v.visitId)).toEqual([3]);
  });

  it('đã ở đầu nhóm: không có đích nào', () => {
    expect(moveTargets(queue, e1)).toEqual([]);
    expect(moveTargets(queue, a1)).toEqual([]);
  });

  it('ca cấp cứu chỉ sắp trong nhóm cấp cứu', () => {
    const e3 = visit(7, { priorityClass: 'EMERGENCY' });
    expect(moveTargets([e1, e3, w2], e3).map((v) => v.visitId)).toEqual([1]);
  });

  it('lượt đã gọi hoặc không nằm trong hàng thì không sắp lại được', () => {
    expect(moveTargets(queue, w1)).toEqual([]);
    expect(moveTargets(queue, visit(99))).toEqual([]);
  });
});

describe('reassignable (BR-TN-08, A15: chỉ gán lại lượt đã gọi khi người phụ trách không còn đủ điều kiện)', () => {
  const called = visit(1, { status: 'IN_PROGRESS', assigneeId: 5 });

  it('cờ needsReassign của BE → gán lại được', () => {
    expect(reassignable({ ...called, needsReassign: true }, new Set([5]))).toBe(true);
    expect(reassignable({ ...called, needsReassign: true }, null)).toBe(true);
  });

  it('người phụ trách không còn trong danh sách nhân viên gán được (khóa / vô hiệu hóa / đổi chức vụ) → gán lại được', () => {
    expect(reassignable(called, new Set([6, 7]))).toBe(true);
  });

  it('người phụ trách vẫn đủ điều kiện → ẩn (BR-TN-08)', () => {
    expect(reassignable(called, new Set([5, 6]))).toBe(false);
  });

  it('chưa tải được danh sách nhân viên và không có cờ → không đoán', () => {
    expect(reassignable(called, null)).toBe(false);
  });

  it('lượt chưa gọi dùng "Gán", không phải "Gán lại"', () => {
    expect(reassignable(visit(2, { needsReassign: true }), new Set())).toBe(false);
  });
});
