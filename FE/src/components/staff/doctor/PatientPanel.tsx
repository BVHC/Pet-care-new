import type { UseQueryResult } from '@tanstack/react-query';
import { AlertTriangle, PawPrint } from 'lucide-react';
import { KIND_LABEL } from '../../../shared/constants/clinic-labels';
import { useNow } from '../../../shared/hooks/useNow';
import type { VisitDetail } from '../../../shared/types/clinic';
import { ageLabel, formatClock, formatElapsed, formatShortDate, petKind } from '../../../shared/utils/clinic-format';
import { Panel, PriorityChip, QueryError, RowsSkeleton, StatePanel, Ticket, VisitStatusChip, WsButton } from '../ui';

interface Props {
  className?: string;
  selectedId: string | null;
  detail: UseQueryResult<VisitDetail, unknown>;
  userId: string;
  onCall: (visitId: string) => void;
  calling: boolean;
}

export function PatientPanel({ className, selectedId, detail, userId, onCall, calling }: Props) {
  const now = useNow();

  if (!selectedId) {
    return (
      <Panel label="Bệnh nhân" className={className}>
        <StatePanel icon={PawPrint} title="Chưa chọn bệnh nhân" body="Chọn một lượt trong hàng chờ để xem hồ sơ." />
      </Panel>
    );
  }
  if (detail.isLoading || !detail.data) {
    return (
      <Panel label="Bệnh nhân" className={className}>
        {detail.isError ? (
          <QueryError error={detail.error} onRetry={() => detail.refetch()} what="hồ sơ bệnh nhân" />
        ) : (
          <RowsSkeleton rows={5} />
        )}
      </Panel>
    );
  }

  const v = detail.data;
  const pet = v.petProfile;
  const facts = [
    petKind(pet.species, pet.breed),
    pet.birthYear ? ageLabel(pet.birthYear, now) : null,
    pet.weightKg ? `${pet.weightKg} kg` : null,
  ].filter(Boolean);

  return (
    <Panel label="Bệnh nhân" className={className}>
      <div className="flex items-start gap-4 border-b border-(--ws-line) p-4">
        <Ticket no={v.queueNo} size="lg" tone={v.emergency ? 'urgent' : undefined} />
        <div className="min-w-0">
          <h2 className="truncate text-2xl font-bold tracking-tight">{pet.name}</h2>
          <p className="text-sm text-(--ws-ink-2)">{facts.join(', ')}</p>
          <div className="mt-2 flex flex-wrap gap-1.5">
            <VisitStatusChip status={v.status} label={v.status === 'IN_PROGRESS' ? 'Đang khám' : undefined} />
            <PriorityChip priority={v.priority} />
            {v.services.map((s) => (
              <span key={s.id} className="ws-chip" data-tone="accent">
                {KIND_LABEL[s.kind]}
              </span>
            ))}
          </div>
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto">
        {pet.alert && (
          <p role="note" className="mx-4 mt-4 flex items-start gap-2 rounded-lg bg-(--ws-urgent-bg) px-3 py-2 text-sm font-semibold text-(--ws-urgent-ink)">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
            {pet.alert}
          </p>
        )}

        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 px-4 py-4 text-sm">
          <dt className="text-(--ws-ink-2)">Chủ nuôi</dt>
          <dd className="font-medium">{v.customer.name}</dd>
          <dt className="text-(--ws-ink-2)">Điện thoại</dt>
          <dd className="ws-num">{v.customer.phone}</dd>
          <dt className="text-(--ws-ink-2)">Dịch vụ</dt>
          <dd>{v.services.map((s) => s.name).join(', ')}</dd>
          <dt className="text-(--ws-ink-2)">Tiếp nhận</dt>
          <dd className="ws-num">
            {formatClock(v.checkedInAt)}
            {v.status === 'WAITING' && `, đã chờ ${formatElapsed(v.checkedInAt, now)}`}
          </dd>
          <dt className="text-(--ws-ink-2)">Phụ trách</dt>
          <dd>{v.assignee?.name ?? 'Chưa gán'}</dd>
        </dl>

        {v.status === 'WAITING' && v.assignee?.id === userId && (
          <div className="px-4 pb-4">
            <WsButton variant="primary" size="lg" className="w-full" disabled={calling} onClick={() => onCall(v.id)}>
              Gọi {pet.name} vào khám
            </WsButton>
          </div>
        )}

        <section aria-labelledby="pet-history" className="border-t border-(--ws-line) px-4 py-4">
          <h3 id="pet-history" className="mb-3 text-[13px] font-semibold text-(--ws-ink-2)">
            Các lần khám trước
          </h3>
          {v.history.length === 0 ? (
            <p className="text-sm text-(--ws-ink-3)">Lần đầu đến phòng khám.</p>
          ) : (
            <ol className="space-y-3 border-l-2 border-(--ws-line) pl-4">
              {v.history.map((h) => (
                <li key={h.visitId}>
                  <p className="ws-num text-xs text-(--ws-ink-3)">{formatShortDate(h.date)}</p>
                  <p className="text-sm font-medium">{h.services.join(', ')}</p>
                  {h.assessment && <p className="text-sm text-(--ws-ink-2)">{h.assessment}</p>}
                </li>
              ))}
            </ol>
          )}
        </section>
      </div>
    </Panel>
  );
}
