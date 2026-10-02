import { useRef, type KeyboardEvent } from 'react';
import type { UseQueryResult } from '@tanstack/react-query';
import { Stethoscope } from 'lucide-react';
import { cn } from '../../../lib/utils';
import { useFreshIds } from '../../../shared/hooks/useFreshIds';
import { useNow } from '../../../shared/hooks/useNow';
import type { VisitView } from '../../../shared/types/clinic';
import { formatClock, formatElapsed, petKind } from '../../../shared/utils/clinic-format';
import { Panel, PanelTitle, PriorityChip, QueryError, RowsSkeleton, StatePanel, Ticket, WsButton } from '../ui';

interface Props {
  className?: string;
  query: UseQueryResult<VisitView[], unknown>;
  /** Exam and vaccination visits of today. */
  visits: VisitView[];
  userId: string;
  selectedId: string | null;
  onSelect: (visit: VisitView) => void;
  onCall: (visit: VisitView) => void;
  callingId: string | null;
}

export function DoctorQueue({ className, query, visits, userId, selectedId, onSelect, onCall, callingId }: Props) {
  const now = useNow();
  const listRef = useRef<HTMLDivElement>(null);
  const mine = visits.filter((v) => v.assignee?.id === userId);
  const inProgress = mine.filter((v) => v.status === 'IN_PROGRESS');
  const waiting = mine.filter((v) => v.status === 'WAITING');
  const done = mine.filter((v) => v.status === 'COMPLETED');
  const unassigned = visits.filter((v) => v.status === 'WAITING' && !v.assignee);
  const colleagues = visits.filter(
    (v) => v.assignee && v.assignee.id !== userId && (v.status === 'WAITING' || v.status === 'IN_PROGRESS'),
  );
  // Baseline only once loaded, so the first list does not flash as "new".
  const fresh = useFreshIds(query.data ? waiting : undefined, (v) => v.id);

  // ↑/↓ walk the queue and open each patient as they go.
  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
    const rows = Array.from(listRef.current?.querySelectorAll<HTMLButtonElement>('[data-queue-row]') ?? []);
    const index = rows.indexOf(document.activeElement as HTMLButtonElement);
    if (index < 0) return;
    e.preventDefault();
    const next = rows[Math.min(Math.max(index + (e.key === 'ArrowDown' ? 1 : -1), 0), rows.length - 1)];
    next?.focus();
    next?.click();
  };

  const row = (v: VisitView, opts: { muted?: boolean; canCall?: boolean } = {}) => (
    <li key={v.id} className={cn('relative', fresh.has(v.id) && 'ws-fresh')}>
      <button
        type="button"
        data-queue-row
        aria-current={v.id === selectedId ? 'true' : undefined}
        onClick={() => onSelect(v)}
        className="flex w-full items-start gap-3 px-4 py-3 text-left hover:bg-(--ws-raised) aria-[current=true]:bg-(--ws-accent-soft) aria-[current=true]:shadow-[inset_3px_0_0_var(--ws-accent)]"
      >
        <Ticket no={v.queueNo} size="sm" tone={v.emergency ? 'urgent' : opts.muted ? 'muted' : undefined} />
        <span className="min-w-0 flex-1">
          <span className="flex flex-wrap items-center gap-1.5">
            <span className="font-semibold">{v.pet.name}</span>
            <PriorityChip priority={v.priority} />
          </span>
          <span className="block truncate text-[13px] text-(--ws-ink-2)">
            {petKind(v.pet.species, v.pet.breed)}, {v.services.map((s) => s.name).join(', ')}
          </span>
          <span className="block text-xs text-(--ws-ink-3)">
            {v.status === 'WAITING' && `Chờ ${formatElapsed(v.checkedInAt, now)}`}
            {v.status === 'IN_PROGRESS' && `Đang khám ${formatElapsed(v.calledAt ?? v.checkedInAt, now)}`}
            {v.status === 'COMPLETED' && `Xong lúc ${formatClock(v.completedAt ?? v.checkedInAt)}`}
            {v.assignee && v.assignee.id !== userId && `, ${v.assignee.name}`}
          </span>
        </span>
      </button>
      {opts.canCall && (
        <div className="px-4 pb-3 pl-[3.85rem]">
          <WsButton size="sm" variant="primary" disabled={callingId === v.id} onClick={() => onCall(v)}>
            Gọi vào khám
          </WsButton>
        </div>
      )}
    </li>
  );

  const section = (title: string, items: VisitView[], render: (v: VisitView) => JSX.Element, note?: string) =>
    items.length > 0 && (
      <section aria-label={title} className="border-b border-(--ws-line) last:border-b-0">
        <h3 className="px-4 pb-1 pt-3 text-xs font-semibold text-(--ws-ink-3)">
          {title} <span className="ws-num">({items.length})</span>
        </h3>
        {note && <p className="px-4 text-xs text-(--ws-ink-3)">{note}</p>}
        <ul>{items.map(render)}</ul>
      </section>
    );

  return (
    <Panel label="Hàng chờ khám" className={className}>
      <PanelTitle aside={<span className="ws-num text-sm text-(--ws-ink-2)">{waiting.length} đang chờ</span>}>Hàng chờ của bạn</PanelTitle>
      <div ref={listRef} onKeyDown={onKeyDown} className="min-h-0 flex-1 overflow-y-auto">
        {query.isLoading ? (
          <RowsSkeleton rows={4} />
        ) : query.isError ? (
          <QueryError error={query.error} onRetry={() => query.refetch()} what="hàng chờ" />
        ) : (
          <>
            {section('Đang khám', inProgress, (v) => row(v))}
            {section('Chờ khám', waiting, (v) => row(v, { canCall: true }))}
            {inProgress.length === 0 && waiting.length === 0 && (
              <StatePanel icon={Stethoscope} title="Không còn ai chờ bạn" body="Lễ tân gán lượt mới thì lượt đó hiện ở đây kèm thông báo." />
            )}
            {section('Chưa gán bác sĩ', unassigned, (v) => row(v), 'Lễ tân gán lượt cho bạn thì mới gọi được.')}
            {section('Đã khám hôm nay', done, (v) => row(v, { muted: true }))}
            {section('Của bác sĩ khác', colleagues, (v) => row(v), 'Chỉ xem, không gọi được.')}
          </>
        )}
      </div>
    </Panel>
  );
}
