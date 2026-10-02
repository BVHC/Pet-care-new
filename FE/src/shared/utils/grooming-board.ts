import type { VisitView } from '../types/clinic';
import { boardColumnOf, sortQueue, type BoardColumnId } from './clinic-rules';

export interface BoardColumn {
  id: BoardColumnId;
  items: VisitView[];
  total: number;
}

export type BoardScope = 'mine' | 'all';

const COLUMN_ORDER: BoardColumnId[] = ['WAITING', 'IN_PROGRESS', 'READY', 'PAID'];

const byTime = (field: 'calledAt' | 'completedAt') => (a: VisitView, b: VisitView) =>
  Date.parse(a[field] ?? a.checkedInAt) - Date.parse(b[field] ?? b.checkedInAt);

/** Grooming visits grouped into board columns (the plan's KanbanContext data). */
export function buildBoard(visits: VisitView[], opts: { scope: BoardScope; userId: string }): BoardColumn[] {
  const grooming = visits.filter(
    (v) => v.services.some((s) => s.kind === 'GROOMING') && (opts.scope === 'all' || v.assignee?.id === opts.userId),
  );
  return COLUMN_ORDER.map((id) => {
    const inColumn = grooming.filter((v) => boardColumnOf(v) === id);
    const items =
      id === 'WAITING' ? sortQueue(inColumn) : inColumn.sort(byTime(id === 'IN_PROGRESS' ? 'calledAt' : 'completedAt'));
    return { id, items, total: items.reduce((sum, v) => sum + v.orderTotal, 0) };
  });
}
