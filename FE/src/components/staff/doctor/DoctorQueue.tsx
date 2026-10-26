// Hàng đợi của tôi (visit-v1 #3, UC46): chỉ lượt WAITING / IN_PROGRESS gán cho chính mình, đúng thứ tự mảng BE trả (BR-TN-04), lượt đang khám xen vào hạng của nó.
import { useRef, type KeyboardEvent } from 'react';
import type { UseQueryResult } from '@tanstack/react-query';
import { Stethoscope } from 'lucide-react';
import { SPECIES_LABEL } from '../../../domains/pets/labels';
import { cn } from '../../../lib/utils';
import { useFreshIds } from '../../../shared/hooks/useFreshIds';
import { useNow } from '../../../shared/hooks/useNow';
import { VISIT_GROUP_LABELS, type Visit } from '../../../shared/types/visit';
import { formatClock, formatElapsed } from '../../../shared/utils/clinic-format';
import { Panel, PanelTitle, QueryError, RowsSkeleton, StatePanel, VisitStatusChip, WsButton } from '../ui';
import { VisitBadges, VisitCode } from '../visit-ui';

interface Props {
  className?: string;
  query: UseQueryResult<Visit[], unknown>;
  /** Lượt Khám/Tiêm đã hoàn tất hôm nay của cả chi nhánh (visit-v1 #2, A1): đường vào bệnh án đã khóa để xem và thêm bản bổ sung (UC53). */
  completed: UseQueryResult<Visit[], unknown>;
  userId: number;
  selectedId: number | null;
  onSelect: (visit: Visit) => void;
  onCall: (visit: Visit) => void;
  callingId: number | null;
}

export function DoctorQueue({ className, query, completed, userId, selectedId, onSelect, onCall, callingId }: Props) {
  const now = useNow();
  const listRef = useRef<HTMLDivElement>(null);
  const visits = query.data ?? [];
  const waiting = visits.filter((v) => v.status === 'WAITING');
  // Baseline only once loaded, so the first list does not flash as "new".
  const fresh = useFreshIds(query.data, (v) => String(v.visitId));

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

  return (
    <Panel label="Hàng chờ khám" className={className}>
      <PanelTitle aside={<span className="ws-num text-sm text-(--ws-ink-2)">{waiting.length} đang chờ</span>}>Hàng chờ của bạn</PanelTitle>
      <div ref={listRef} onKeyDown={onKeyDown} className="min-h-0 flex-1 overflow-y-auto">
        {query.isLoading ? (
          <RowsSkeleton rows={4} />
        ) : query.isError ? (
          <QueryError error={query.error} onRetry={() => query.refetch()} what="hàng chờ" />
        ) : visits.length === 0 ? (
          <StatePanel icon={Stethoscope} title="Không còn ai chờ bạn" body="Lễ tân gán lượt mới thì lượt đó hiện ở đây kèm thông báo." />
        ) : (
          <ul aria-label="Hàng đợi của tôi" className="divide-y divide-(--ws-line)">
            {visits.map((v) => (
              <li key={v.visitId} className={cn('relative', fresh.has(String(v.visitId)) && 'ws-fresh')}>
                <button
                  type="button"
                  data-queue-row
                  aria-current={v.visitId === selectedId ? 'true' : undefined}
                  aria-label={`${v.petName}, ${v.code}`}
                  onClick={() => onSelect(v)}
                  className="flex w-full items-start gap-3 px-4 py-3 text-left hover:bg-(--ws-raised) aria-[current=true]:bg-(--ws-accent-soft) aria-[current=true]:shadow-[inset_3px_0_0_var(--ws-accent)]"
                >
                  <span className="min-w-0 flex-1">
                    <span className="flex flex-wrap items-center gap-1.5">
                      <VisitCode code={v.code} urgent={v.priorityClass === 'EMERGENCY'} />
                      <span data-testid="visit-pet" className="font-semibold">
                        {v.petName}
                      </span>
                      <VisitStatusChip status={v.status} label={v.status === 'IN_PROGRESS' ? 'Đang khám' : undefined} />
                      <VisitBadges visit={v} />
                    </span>
                    <span className="block truncate text-[13px] text-(--ws-ink-2)">
                      {SPECIES_LABEL[v.species]}, {VISIT_GROUP_LABELS[v.serviceGroup]}
                    </span>
                    <span className="block text-xs text-(--ws-ink-3)">
                      {v.status === 'WAITING' ? `Chờ ${formatElapsed(v.checkedInAt, now)}` : `Đang khám ${formatElapsed(v.calledAt ?? v.checkedInAt, now)}`}
                    </span>
                  </span>
                </button>
                {v.status === 'WAITING' && v.assigneeId === userId && (
                  <div className="px-4 pb-3">
                    <WsButton size="sm" variant="primary" disabled={callingId === v.visitId} onClick={() => onCall(v)}>
                      Gọi lượt
                    </WsButton>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}

        {(completed.data?.length ?? 0) > 0 && (
          <section aria-labelledby="exam-done-today" className="border-t border-(--ws-line)">
            <h3 id="exam-done-today" className="px-4 pb-1 pt-3 text-[13px] font-semibold text-(--ws-ink-2)">
              Đã khám hôm nay
            </h3>
            <ul aria-label="Đã khám hôm nay" className="divide-y divide-(--ws-line)">
              {completed.data!.map((v) => (
                <li key={v.visitId}>
                  <button
                    type="button"
                    data-queue-row
                    aria-current={v.visitId === selectedId ? 'true' : undefined}
                    aria-label={`${v.petName}, ${v.code}`}
                    onClick={() => onSelect(v)}
                    className="flex w-full flex-col items-start gap-0.5 px-4 py-2.5 text-left hover:bg-(--ws-raised) aria-[current=true]:bg-(--ws-accent-soft) aria-[current=true]:shadow-[inset_3px_0_0_var(--ws-accent)]"
                  >
                    <span className="flex flex-wrap items-center gap-1.5">
                      <VisitCode code={v.code} />
                      <span className="font-semibold">{v.petName}</span>
                    </span>
                    <span className="text-xs text-(--ws-ink-3)">
                      Hoàn tất lúc {formatClock(v.completedAt ?? v.checkedInAt)}
                      {v.assigneeName ? `, ${v.assigneeName}` : ''}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </section>
        )}
      </div>
    </Panel>
  );
}
