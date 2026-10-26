import { useState } from 'react';
import { createPortal } from 'react-dom';
import { GripVertical } from 'lucide-react';
import { toast } from 'sonner';
import { SPECIES_LABEL } from '../../../domains/pets/labels';
import { cn } from '../../../lib/utils';
import { moveRejection, useKanban, type GroomColumn } from '../../../shared/hooks/useKanban';
import { useNow } from '../../../shared/hooks/useNow';
import type { Visit } from '../../../shared/types/visit';
import { announce } from '../../../shared/utils/announce';
import { formatClock, formatElapsed } from '../../../shared/utils/clinic-format';
import type { BoardScope } from '../../../shared/utils/grooming-board';
import { track } from '../../../shared/utils/telemetry';
import { QueryError, Skeleton, WsButton } from '../ui';
import { VisitBadges, VisitCode } from '../visit-ui';
import { useBoardDrag } from './useBoardDrag';

const COLUMN_TITLE: Record<GroomColumn, string> = {
  WAITING: 'Chờ làm',
  IN_PROGRESS: 'Đang làm',
  READY: 'Xong hôm nay',
};

const EMPTY_TEXT: Record<GroomColumn, string> = { WAITING: 'Không có bé nào đang chờ.', IN_PROGRESS: 'Trống', READY: 'Chưa có bé nào xong hôm nay.' };

export function GroomingBoard({ userId, scope }: { userId: number; scope: BoardScope }) {
  const { columns, query, moveCard, pending } = useKanban(userId, scope);
  const now = useNow();
  const [shaking, setShaking] = useState<number | null>(null);
  const byId = new Map(columns.flatMap((c) => c.items).map((v) => [v.visitId, v]));

  const attempt = (visit: Visit, to: GroomColumn) => {
    const rejection = moveCard(visit, to);
    if (!rejection) {
      track('board_move', { to });
      announce(`Đã chuyển ${visit.petName} sang ${COLUMN_TITLE[to]}`);
      return;
    }
    if (rejection.code === 'NO_MOVE') return;
    track('board_move_rejected', { code: rejection.code });
    toast.error(rejection.message);
    announce(rejection.message);
    setShaking(visit.visitId);
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
          </header>
          <ul aria-label={COLUMN_TITLE[col.id]} className="flex min-h-24 flex-1 flex-col gap-2 overflow-y-auto px-2 pb-2">
            {query.isLoading ? (
              <li aria-hidden className="flex flex-col gap-2">
                <Skeleton className="h-24" />
                <Skeleton className="h-24" />
              </li>
            ) : col.items.length === 0 ? (
              <li className="rounded-lg border border-dashed border-(--ws-line-strong) px-3 py-6 text-center text-sm text-(--ws-ink-3)">{EMPTY_TEXT[col.id]}</li>
            ) : (
              col.items.map((v) => (
                <li key={v.visitId}>
                  <GroomCard
                    visit={v}
                    column={col.id}
                    userId={userId}
                    showAssignee={scope === 'all'}
                    now={now}
                    moving={pending.has(v.visitId)}
                    shaking={shaking === v.visitId}
                    dragging={drag.ghost?.visitId === v.visitId}
                    handleProps={drag.handleProps(v.visitId, col.id)}
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
            <VisitCode code={ghostVisit.code} />
            {ghostVisit.petName}
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
  visit: Visit;
  column: GroomColumn;
  userId: number;
  showAssignee: boolean;
  now: number;
  moving: boolean;
  shaking: boolean;
  dragging: boolean;
  handleProps: ReturnType<ReturnType<typeof useBoardDrag>['handleProps']>;
  onMove: (to: GroomColumn) => void;
}) {
  const mine = visit.assigneeId === userId;
  const movable = mine && column !== 'READY';
  const timing =
    column === 'WAITING'
      ? `Chờ ${formatElapsed(visit.checkedInAt, now)}`
      : column === 'READY'
        ? `Xong lúc ${formatClock(visit.completedAt ?? visit.checkedInAt)}`
        : `Đang làm ${formatElapsed(visit.calledAt ?? visit.checkedInAt, now)}`;

  return (
    <article
      aria-label={`${visit.petName}, ${visit.code}`}
      className={cn('rounded-lg border border-(--ws-line) bg-(--ws-surface) p-3 transition-opacity', shaking && 'ws-shake', dragging && 'opacity-40')}
    >
      <div className="flex items-start gap-2.5">
        <div className="min-w-0 flex-1">
          <p className="flex flex-wrap items-center gap-1.5">
            <VisitCode code={visit.code} urgent={visit.priorityClass === 'EMERGENCY'} />
            <VisitBadges visit={visit} />
          </p>
          <p className="mt-1 truncate font-semibold">
            {visit.petName} <span className="font-normal text-(--ws-ink-3)">{SPECIES_LABEL[visit.species]}</span>
          </p>
          <p className="truncate text-xs text-(--ws-ink-3)">{visit.customerName}</p>
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

      <div className="mt-2.5 flex flex-wrap items-center gap-x-2 gap-y-1">
        <span className="ws-num text-xs text-(--ws-ink-2)">{timing}</span>
        {showAssignee && visit.assigneeName && !mine && <span className="text-xs text-(--ws-ink-3)">{visit.assigneeName}</span>}
        {visit.assigneeId === null && column === 'WAITING' && <span className="ml-auto text-xs text-(--ws-ink-3)">Chờ lễ tân gán</span>}
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
