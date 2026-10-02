import { useState } from 'react';
import { createPortal } from 'react-dom';
import { AlertTriangle, GripVertical } from 'lucide-react';
import { toast } from 'sonner';
import { cn, formatCurrency } from '../../../lib/utils';
import { moveRejection, useKanban } from '../../../shared/hooks/useKanban';
import { useNow } from '../../../shared/hooks/useNow';
import type { VisitView } from '../../../shared/types/clinic';
import { announce } from '../../../shared/utils/announce';
import { formatClock, formatElapsed } from '../../../shared/utils/clinic-format';
import type { BoardColumnId } from '../../../shared/utils/clinic-rules';
import type { BoardScope } from '../../../shared/utils/grooming-board';
import { track } from '../../../shared/utils/telemetry';
import { QueryError, Skeleton, Ticket, WsButton } from '../ui';
import { useBoardDrag } from './useBoardDrag';

const COLUMN_TITLE: Record<BoardColumnId, string> = {
  WAITING: 'Chờ làm',
  IN_PROGRESS: 'Đang làm',
  READY: 'Xong, chờ đón',
  PAID: 'Đã thanh toán',
};

export function GroomingBoard({ userId, scope }: { userId: string; scope: BoardScope }) {
  const { columns, query, moveCard, movingId } = useKanban(userId, scope);
  const now = useNow();
  const [shaking, setShaking] = useState<string | null>(null);
  const byId = new Map(columns.flatMap((c) => c.items).map((v) => [v.id, v]));

  const attempt = (visit: VisitView, to: BoardColumnId) => {
    const rejection = moveCard(visit, to);
    if (!rejection) {
      track('board_move', { to });
      announce(`Đã chuyển ${visit.pet.name} sang ${COLUMN_TITLE[to]}`);
      return;
    }
    if (rejection.code === 'NO_MOVE') return;
    track('board_move_rejected', { code: rejection.code });
    toast.error(rejection.message);
    announce(rejection.message);
    setShaking(visit.id);
    window.setTimeout(() => setShaking(null), 400);
  };

  const drag = useBoardDrag({
    onDrop: (visitId, to) => {
      const visit = byId.get(visitId);
      if (visit) attempt(visit, to);
    },
    canDrop: (visitId, to) => {
      const visit = byId.get(visitId);
      return !!visit && !moveRejection(visit, to, userId);
    },
  });

  if (query.isError) {
    return <QueryError error={query.error} onRetry={() => query.refetch()} what="bảng grooming" />;
  }

  const ghostVisit = drag.ghost ? byId.get(drag.ghost.visitId) : undefined;

  return (
    <div className="flex min-h-0 flex-1 snap-x snap-mandatory gap-3 overflow-x-auto p-3 lg:snap-none">
      {columns.map((col) => (
        <section
          key={col.id}
          data-column={col.id}
          data-drop={drag.dropState(col.id)}
          aria-labelledby={`col-${col.id}`}
          className="ws-column flex w-[min(86vw,320px)] shrink-0 snap-start flex-col rounded-xl border border-(--ws-line) bg-(--ws-raised) transition-[background-color,opacity] lg:w-auto lg:min-w-[220px] lg:flex-1"
        >
          <header className="flex items-baseline gap-2 px-3 pb-2 pt-3">
            <h2 id={`col-${col.id}`} className="font-semibold">
              {COLUMN_TITLE[col.id]}
            </h2>
            <span className="ws-num text-sm text-(--ws-ink-3)">{query.isLoading ? '' : col.items.length}</span>
            <span className="ws-num ml-auto text-xs text-(--ws-ink-3)">{col.total > 0 && formatCurrency(col.total)}</span>
          </header>
          <ul aria-label={COLUMN_TITLE[col.id]} className="flex min-h-24 flex-1 flex-col gap-2 overflow-y-auto px-2 pb-2">
            {query.isLoading ? (
              <li aria-hidden className="flex flex-col gap-2">
                <Skeleton className="h-24" />
                <Skeleton className="h-24" />
              </li>
            ) : col.items.length === 0 ? (
              <li className="rounded-lg border border-dashed border-(--ws-line-strong) px-3 py-6 text-center text-sm text-(--ws-ink-3)">
                {col.id === 'WAITING' ? 'Không có bé nào đang chờ.' : 'Trống'}
              </li>
            ) : (
              col.items.map((v) => (
                <li key={v.id}>
                  <GroomCard
                    visit={v}
                    column={col.id}
                    userId={userId}
                    showAssignee={scope === 'all'}
                    now={now}
                    moving={movingId === v.id}
                    shaking={shaking === v.id}
                    dragging={drag.ghost?.visitId === v.id}
                    handleProps={drag.handleProps(v.id, col.id)}
                    onMove={(to) => attempt(v, to)}
                  />
                </li>
              ))
            )}
          </ul>
        </section>
      ))}

      {drag.ghost &&
        ghostVisit &&
        createPortal(
          <div className="ws-ghost" style={{ left: drag.ghost.x + 14, top: drag.ghost.y + 10 }} aria-hidden>
            <Ticket no={ghostVisit.queueNo} size="sm" />
            {ghostVisit.pet.name}
          </div>,
          document.body,
        )}
    </div>
  );
}

function GroomCard({
  visit,
  column,
  userId,
  showAssignee,
  now,
  moving,
  shaking,
  dragging,
  handleProps,
  onMove,
}: {
  visit: VisitView;
  column: BoardColumnId;
  userId: string;
  showAssignee: boolean;
  now: number;
  moving: boolean;
  shaking: boolean;
  dragging: boolean;
  handleProps: ReturnType<ReturnType<typeof useBoardDrag>['handleProps']>;
  onMove: (to: BoardColumnId) => void;
}) {
  const mine = visit.assignee?.id === userId;
  const movable = mine && (column === 'WAITING' || column === 'IN_PROGRESS');
  const timing = {
    WAITING: `Chờ ${formatElapsed(visit.checkedInAt, now)}`,
    IN_PROGRESS: `Đang làm ${formatElapsed(visit.calledAt ?? visit.checkedInAt, now)}`,
    READY: `Xong lúc ${formatClock(visit.completedAt ?? visit.checkedInAt)}, chờ đón ${formatElapsed(visit.completedAt ?? visit.checkedInAt, now)}`,
    PAID: 'Đã thanh toán, có thể giao bé',
  }[column];

  return (
    <article
      aria-label={`${visit.pet.name}, số ${visit.queueNo}`}
      className={cn(
        'rounded-lg border border-(--ws-line) bg-(--ws-surface) p-3 transition-opacity',
        shaking && 'ws-shake',
        dragging && 'opacity-40',
        column === 'PAID' && 'opacity-75',
      )}
    >
      <div className="flex items-start gap-2.5">
        <Ticket no={visit.queueNo} size="sm" tone={visit.emergency ? 'urgent' : column === 'PAID' ? 'muted' : undefined} />
        <div className="min-w-0 flex-1">
          <p className="truncate font-semibold">
            {visit.pet.name} <span className="font-normal text-(--ws-ink-3)">{visit.pet.breed}</span>
          </p>
          <p className="truncate text-[13px] text-(--ws-ink-2)">{visit.services.map((s) => s.name).join(', ')}</p>
          <p className="truncate text-xs text-(--ws-ink-3)">{visit.customer.name}</p>
        </div>
        {movable && (
          <span
            {...handleProps}
            aria-hidden
            title="Kéo sang cột khác"
            className="-mr-1 inline-flex h-9 w-7 shrink-0 cursor-grab touch-none items-center justify-center rounded-md text-(--ws-ink-3) hover:bg-(--ws-raised) active:cursor-grabbing"
          >
            <GripVertical className="h-4 w-4" />
          </span>
        )}
      </div>

      {visit.pet.alert && column !== 'PAID' && (
        <p className="mt-2 flex items-center gap-1.5 rounded-md bg-(--ws-urgent-bg) px-2 py-1 text-xs font-semibold text-(--ws-urgent-ink)">
          <AlertTriangle className="h-3.5 w-3.5 shrink-0" aria-hidden />
          {visit.pet.alert}
        </p>
      )}

      <div className="mt-2.5 flex flex-wrap items-center gap-x-2 gap-y-1">
        <span className="ws-num text-xs text-(--ws-ink-2)">{timing}</span>
        {showAssignee && visit.assignee && !mine && <span className="text-xs text-(--ws-ink-3)">{visit.assignee.name}</span>}
        {!visit.assignee && column === 'WAITING' && <span className="ml-auto text-xs text-(--ws-ink-3)">Chờ lễ tân gán</span>}
        {mine && column === 'WAITING' && (
          <WsButton size="sm" variant="primary" className="ml-auto" disabled={moving} onClick={() => onMove('IN_PROGRESS')}>
            Bắt đầu
          </WsButton>
        )}
        {mine && column === 'IN_PROGRESS' && (
          <WsButton size="sm" variant="primary" className="ml-auto" disabled={moving} onClick={() => onMove('READY')}>
            Xong
          </WsButton>
        )}
      </div>
    </article>
  );
}
