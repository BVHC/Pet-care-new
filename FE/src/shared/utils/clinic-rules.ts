// Pure business rules for the staff workspaces. Every guard cites its docs v13 rule id
// (docs/02-business-rules.md, docs/03-state-machines.md) as the violation code.
import type { OrderStatus, QueuePriority, ServiceKind, ShiftStatus, VisitStatus } from '../types/clinic';

export interface RuleViolation {
  code: string;
  message: string;
}

/** [CFG] defaults: BR-TN-02 (check-in window), BR-TN-03 (late), BR-LH-08 (no-show). */
export const CLINIC_CFG = {
  checkInEarlyMin: 30,
  latePriorityMin: 15,
  noShowAfterMin: 30,
  /** BR-KB-06: tái khám sau ngày khám, xa nhất 180 ngày. */
  followUpMaxDays: 180,
} as const;

// BR-TN-09 whitelist — anything not listed is refused.
const VISIT_TRANSITIONS: Readonly<Record<VisitStatus, readonly VisitStatus[]>> = {
  WAITING: ['IN_PROGRESS', 'CANCELLED'],
  IN_PROGRESS: ['COMPLETED'],
  COMPLETED: [],
  CANCELLED: [],
};

export function canTransitionVisit(from: VisitStatus, to: VisitStatus): boolean {
  return VISIT_TRANSITIONS[from].includes(to);
}

const invalidTransition = (message: string): RuleViolation => ({ code: 'INVALID_STATE_TRANSITION', message });

export function positionFor(kind: ServiceKind): 'VET' | 'CARETAKER' {
  return kind === 'GROOMING' ? 'CARETAKER' : 'VET';
}

export function callViolation(
  /** assigneeId is required on purpose: VisitView carries assignee.id, not assigneeId. */
  visit: { status: VisitStatus; assigneeId: string | undefined },
  staffId: string,
): RuleViolation | null {
  if (!canTransitionVisit(visit.status, 'IN_PROGRESS')) {
    return invalidTransition('Lượt này đã được gọi hoặc đã đóng.');
  }
  if (visit.assigneeId !== staffId) {
    return {
      code: 'BR-TN-05',
      message: visit.assigneeId
        ? 'Lượt này đã gán cho nhân viên khác.'
        : 'Lượt chưa được gán. Nhờ lễ tân gán cho bạn trước khi gọi.',
    };
  }
  return null;
}

export function completeViolation(input: {
  status: VisitStatus;
  kinds: ServiceKind[];
  assessment?: string;
  vaccineLines: number;
}): RuleViolation | null {
  if (!canTransitionVisit(input.status, 'COMPLETED')) {
    return invalidTransition('Chỉ hoàn tất được lượt đang làm.');
  }
  if (input.kinds.includes('EXAM') && !(input.assessment ?? '').trim()) {
    return { code: 'BR-KB-02', message: 'Ghi chẩn đoán trước khi hoàn tất lượt khám.' };
  }
  const vaccinationOnly = input.kinds.length > 0 && input.kinds.every((k) => k === 'VACCINATION');
  if (vaccinationOnly && input.vaccineLines < 1) {
    return { code: 'BR-KB-02', message: 'Ghi nhận ít nhất 1 mũi tiêm trước khi hoàn tất.' };
  }
  return null;
}

const minutesBetween = (fromIso: string, toIso: string) => (Date.parse(toIso) - Date.parse(fromIso)) / 60_000;

export function queuePriority(input: { emergency: boolean; scheduledAt?: string; checkedInAt: string }): QueuePriority {
  if (input.emergency) return 'EMERGENCY';
  if (input.scheduledAt && minutesBetween(input.scheduledAt, input.checkedInAt) <= CLINIC_CFG.latePriorityMin) {
    return 'APPOINTMENT';
  }
  return 'WALK_IN';
}

const BAND: Record<QueuePriority, number> = { EMERGENCY: 0, APPOINTMENT: 1, WALK_IN: 2 };

/** BR-TN-04: cấp cứu → lịch hẹn (theo giờ hẹn) → walk-in (theo giờ check-in). */
export function sortQueue<T extends { priority: QueuePriority; scheduledAt?: string; checkedInAt: string }>(
  items: T[],
): T[] {
  const time = (v: T) => Date.parse(v.priority === 'APPOINTMENT' && v.scheduledAt ? v.scheduledAt : v.checkedInAt);
  return [...items].sort((a, b) => BAND[a.priority] - BAND[b.priority] || time(a) - time(b));
}

export function arrivalState(startsAt: string, now: number): 'UPCOMING' | 'OPEN' | 'NO_SHOW' {
  const minutesUntil = (Date.parse(startsAt) - now) / 60_000;
  if (minutesUntil > CLINIC_CFG.checkInEarlyMin) return 'UPCOMING';
  if (-minutesUntil > CLINIC_CFG.noShowAfterMin) return 'NO_SHOW';
  return 'OPEN';
}

export function retailViolation(product: { name: string; prescriptionOnly: boolean }): RuleViolation | null {
  return product.prescriptionOnly
    ? { code: 'BR-SP-01', message: `${product.name} là thuốc kê đơn, chỉ bán theo đơn của bác sĩ.` }
    : null;
}

export function lineTotal(lines: { unitPrice: number; qty: number }[]): number {
  return lines.reduce((sum, l) => sum + l.unitPrice * l.qty, 0);
}

export function paymentViolation(input: {
  orders: { status: OrderStatus; customerId: string }[];
  shift: { status: ShiftStatus; receptionistId: string } | null;
  receptionistId: string;
}): RuleViolation | null {
  const { orders, shift, receptionistId } = input;
  if (orders.length === 0) return { code: 'NOTHING_TO_PAY', message: 'Chưa có đơn nào để thu.' };
  if (!shift || shift.status !== 'OPEN' || shift.receptionistId !== receptionistId) {
    return { code: 'BR-TG-01', message: 'Chưa mở ca thu ngân. Mở ca để thu tiền.' };
  }
  if (orders.some((o) => o.status !== 'PENDING')) {
    return { code: 'BR-TG-01', message: 'Chỉ thu được đơn đang chờ thanh toán.' };
  }
  if (new Set(orders.map((o) => o.customerId)).size > 1) {
    return { code: 'BR-TG-03', message: 'Chỉ thu gộp đơn của cùng một khách.' };
  }
  return null;
}

// ---------- Grooming board ----------

/** Visit status columns plus "đã thanh toán", derived from the visit's Order. */
export type BoardColumnId = 'WAITING' | 'IN_PROGRESS' | 'READY' | 'PAID';

export function boardColumnOf(visit: { status: VisitStatus; orderStatus: OrderStatus }): BoardColumnId | null {
  switch (visit.status) {
    case 'WAITING':
      return 'WAITING';
    case 'IN_PROGRESS':
      return 'IN_PROGRESS';
    case 'COMPLETED':
      if (visit.orderStatus === 'PAID') return 'PAID';
      return visit.orderStatus === 'PENDING' ? 'READY' : null;
    default:
      return null;
  }
}

const DROP_TRANSITIONS: Partial<Record<string, VisitStatus>> = {
  'WAITING>IN_PROGRESS': 'IN_PROGRESS',
  'IN_PROGRESS>READY': 'COMPLETED',
};

/** The visit transition a drop performs, or null when the FSM has no such edge. */
export function dropTransition(from: BoardColumnId, to: BoardColumnId): VisitStatus | null {
  return DROP_TRANSITIONS[`${from}>${to}`] ?? null;
}
