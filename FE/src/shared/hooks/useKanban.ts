import { useMemo } from 'react';
import type { VisitView } from '../types/clinic';
import {
  boardColumnOf,
  callViolation,
  dropTransition,
  type BoardColumnId,
  type RuleViolation,
} from '../utils/clinic-rules';
import { buildBoard, type BoardScope } from '../utils/grooming-board';
import { useMoveVisit, useTodayVisits } from './useClinic';

/** Why a card may not go to a column, or null when the visit FSM and assignment allow it. */
export function moveRejection(visit: VisitView, to: BoardColumnId, userId: string): RuleViolation | null {
  const from = boardColumnOf(visit);
  if (!from || from === to) return { code: 'NO_MOVE', message: '' };
  if (to === 'PAID') return { code: 'BR-TG-01', message: 'Thanh toán làm ở quầy lễ tân.' };
  const status = dropTransition(from, to);
  if (!status) {
    return {
      code: 'BR-TN-09',
      message: from === 'WAITING' ? 'Bắt đầu làm trước khi báo xong.' : 'Thẻ đã bắt đầu không quay lại cột trước được.',
    };
  }
  if (status === 'IN_PROGRESS') return callViolation({ status: visit.status, assigneeId: visit.assignee?.id }, userId);
  if (visit.assignee?.id !== userId) return { code: 'ACCESS_DENIED_SCOPE', message: 'Chỉ người phụ trách được báo xong.' };
  return null;
}

/** Grooming board state (the plan's KanbanContext): columns, and moves that respect the FSM. */
export function useKanban(userId: string, scope: BoardScope) {
  const visits = useTodayVisits();
  const move = useMoveVisit(userId);
  const columns = useMemo(() => buildBoard(visits.data ?? [], { scope, userId }), [visits.data, scope, userId]);

  const moveCard = (visit: VisitView, to: BoardColumnId): RuleViolation | null => {
    const rejection = moveRejection(visit, to, userId);
    if (rejection) return rejection;
    move.mutate({ visitId: visit.id, to: to === 'IN_PROGRESS' ? 'IN_PROGRESS' : 'COMPLETED' });
    return null;
  };

  return {
    columns,
    query: visits,
    moveCard,
    movingId: move.isPending ? move.variables?.visitId : undefined,
  };
}
