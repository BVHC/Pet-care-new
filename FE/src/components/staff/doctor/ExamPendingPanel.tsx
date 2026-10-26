// Khung bệnh nhân của lượt đang chọn, đọc thẳng từ lượt của API (visit-v1 Visit). Bệnh án, kê đơn, tiêm chủng và hoàn tất lượt (visit-v1 #10, #12–#20) là T37 và vẫn
// nằm trên clinic-db (SoapPanel / PatientPanel): panel này KHÔNG nhận mã lượt cho các panel đó, chỉ nói rõ phần nào chưa nối.
import { PawPrint } from 'lucide-react';
import { SPECIES_LABEL } from '../../../domains/pets/labels';
import { useNow } from '../../../shared/hooks/useNow';
import { VISIT_GROUP_LABELS, type Visit } from '../../../shared/types/visit';
import { formatElapsed } from '../../../shared/utils/clinic-format';
import { Panel, StatePanel, VisitStatusChip, WsButton } from '../ui';
import { VisitBadges, VisitCode } from '../visit-ui';

export const EXAM_PENDING_NOTICE = 'Ghi bệnh án, kê đơn, tiêm chủng và hoàn tất lượt: chưa nối API (T37)';

interface Props {
  className?: string;
  visit: Visit | null;
  userId: number;
  /** VET đánh dấu cấp cứu được (docs/api/visit-v1.md, ma trận vai trò); lượt phải còn WAITING và chưa là cấp cứu. */
  canMarkEmergency: boolean;
  onCall: (visit: Visit) => void;
  onMarkEmergency: (visit: Visit) => void;
  busy: boolean;
}

export function ExamPendingPanel({ className, visit, userId, canMarkEmergency, onCall, onMarkEmergency, busy }: Props) {
  const now = useNow();

  if (!visit) {
    return (
      <Panel label="Lượt đang chọn" className={className}>
        <StatePanel icon={PawPrint} title="Chưa chọn bệnh nhân" body="Chọn một lượt trong hàng chờ để xem thông tin." />
      </Panel>
    );
  }

  const waiting = visit.status === 'WAITING';
  return (
    <Panel label="Lượt đang chọn" className={className}>
      <div className="flex items-start gap-4 border-b border-(--ws-line) p-4">
        <div className="min-w-0">
          <h2 className="truncate text-2xl font-bold tracking-tight">{visit.petName}</h2>
          <p className="text-sm text-(--ws-ink-2)">{SPECIES_LABEL[visit.species]}</p>
          <div className="mt-2 flex flex-wrap gap-1.5">
            <VisitCode code={visit.code} urgent={visit.priorityClass === 'EMERGENCY'} />
            <VisitStatusChip status={visit.status} label={visit.status === 'IN_PROGRESS' ? 'Đang khám' : undefined} />
            <VisitBadges visit={visit} />
          </div>
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto">
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 px-4 py-4 text-sm">
          <dt className="text-(--ws-ink-2)">Chủ nuôi</dt>
          <dd className="font-medium">{visit.customerName}</dd>
          <dt className="text-(--ws-ink-2)">Dịch vụ</dt>
          <dd>{VISIT_GROUP_LABELS[visit.serviceGroup]}</dd>
          <dt className="text-(--ws-ink-2)">Tiếp nhận</dt>
          <dd className="ws-num">{waiting ? `Đã chờ ${formatElapsed(visit.checkedInAt, now)}` : `Đã khám ${formatElapsed(visit.calledAt ?? visit.checkedInAt, now)}`}</dd>
          <dt className="text-(--ws-ink-2)">Phụ trách</dt>
          <dd>{visit.assigneeName ?? 'Chưa gán'}</dd>
        </dl>

        {waiting && (
          <div className="flex flex-col gap-2 px-4 pb-4">
            {visit.assigneeId === userId && (
              <WsButton variant="primary" size="lg" className="w-full" disabled={busy} onClick={() => onCall(visit)}>
                Gọi {visit.petName} vào khám
              </WsButton>
            )}
            {canMarkEmergency && visit.priorityClass !== 'EMERGENCY' && (
              <WsButton variant="secondary" className="w-full" disabled={busy} onClick={() => onMarkEmergency(visit)}>
                Cấp cứu
              </WsButton>
            )}
          </div>
        )}

        <p role="note" className="mx-4 mb-4 rounded-lg bg-(--ws-raised) px-3 py-2 text-sm text-(--ws-ink-2)">
          {EXAM_PENDING_NOTICE}
        </p>
      </div>
    </Panel>
  );
}
