// "Phẫu thuật chờ cam kết" ở quầy lễ tân (UC50; surgery-v1 #3, #5, #8): phẫu thuật của chi nhánh lọc theo trạng thái (mặc định chờ cam kết). Lễ tân lập cam kết (PLANNED → CONSENTED)
// và hủy được khi chưa bắt đầu; bắt đầu / hoàn tất là việc của VET nên không có nút ở đây.
import { useState } from 'react';
import { toast } from 'sonner';
import { Scissors } from 'lucide-react';
import { useSurgeries } from '../../../shared/hooks/useSurgery';
import { SURGERY_STATUS_LABELS, type SurgerySummary } from '../../../shared/types/surgery';
import { formatClock } from '../../../shared/utils/clinic-format';
import { CancelSurgeryDialog, ConsentSurgeryDialog } from '../surgery/SurgeryDialogs';
import { QueryError, RowsSkeleton, StatePanel, WsButton } from '../ui';

const FILTERS = ['PLANNED', 'CONSENTED'] as const;
type Filter = (typeof FILTERS)[number];

export const SURGERY_LIST_SIZE = 50;

export function SurgeryConsentList() {
  const [status, setStatus] = useState<Filter>('PLANNED');
  const [dialog, setDialog] = useState<{ kind: 'consent' | 'cancel'; surgery: SurgerySummary } | null>(null);
  const query = useSurgeries({ status, size: SURGERY_LIST_SIZE });
  const done = (message: string) => {
    setDialog(null);
    toast.success(message);
  };

  const rows = query.data?.content ?? [];
  let body;
  if (query.isLoading || query.isPlaceholderData) body = <RowsSkeleton rows={3} />;
  else if (query.isError) body = <QueryError error={query.error} onRetry={() => query.refetch()} what="phẫu thuật" />;
  else if (rows.length === 0) {
    body = (
      <StatePanel
        icon={Scissors}
        title={status === 'PLANNED' ? 'Không có phẫu thuật chờ cam kết' : 'Không có phẫu thuật nào đã có cam kết'}
        body="Bác sĩ lập kế hoạch phẫu thuật trong lượt khám thì phẫu thuật hiện ở đây."
      />
    );
  } else {
    body = (
      <ul aria-label="Phẫu thuật" className="divide-y divide-(--ws-line)">
        {rows.map((s) => (
          <li key={s.surgeryId} className="flex items-start gap-3 px-4 py-3">
            <div className="min-w-0 flex-1">
              <p className="truncate font-semibold">{s.petName}</p>
              <p className="truncate text-[13px] text-(--ws-ink-2)">{s.serviceName}</p>
              <p className="mt-1 flex flex-wrap items-center gap-1.5 text-xs text-(--ws-ink-3)">
                <span className="ws-chip" data-tone={s.status === 'PLANNED' ? 'wait' : 'accent'}>
                  {SURGERY_STATUS_LABELS[s.status]}
                </span>
                Lập lúc {formatClock(s.createdAt)}
              </p>
            </div>
            <div className="flex flex-col gap-1.5">
              {s.status === 'PLANNED' && (
                <WsButton size="sm" variant="primary" aria-label={`Lập cam kết: ${s.petName}`} onClick={() => setDialog({ kind: 'consent', surgery: s })}>
                  Lập cam kết
                </WsButton>
              )}
              <WsButton size="sm" variant="danger" aria-label={`Hủy phẫu thuật: ${s.petName}`} onClick={() => setDialog({ kind: 'cancel', surgery: s })}>
                Hủy
              </WsButton>
            </div>
          </li>
        ))}
      </ul>
    );
  }

  return (
    <>
      <div role="group" aria-label="Lọc phẫu thuật" className="flex gap-1 border-b border-(--ws-line) p-2">
        {FILTERS.map((f) => (
          <button
            key={f}
            type="button"
            aria-pressed={status === f}
            onClick={() => setStatus(f)}
            className="h-8 flex-1 rounded-md text-[13px] font-medium text-(--ws-ink-2) aria-pressed:bg-(--ws-accent-soft) aria-pressed:font-semibold aria-pressed:text-(--ws-accent-ink)"
          >
            {f === 'PLANNED' ? 'Chờ cam kết' : 'Đã có cam kết'}
          </button>
        ))}
      </div>
      {body}
      {dialog?.kind === 'consent' && <ConsentSurgeryDialog surgery={dialog.surgery} onClose={() => setDialog(null)} onDone={done} />}
      {dialog?.kind === 'cancel' && <CancelSurgeryDialog surgery={dialog.surgery} onClose={() => setDialog(null)} onDone={done} />}
    </>
  );
}
