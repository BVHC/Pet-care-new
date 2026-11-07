// Khung "Phẫu thuật" trong lượt khám (UC50; surgery-v1 #1–2, #6–8). VET phụ trách lượt đang khám lập kế hoạch, bắt đầu (khi đã có cam kết), hoàn tất, hủy / dừng; lễ tân lập cam kết ở quầy
// (SurgeryConsentList). Lượt đã khóa hoặc người xem không phụ trách: chỉ xem lịch sử. Không có phẫu thuật nào và không được lập thì không vẽ gì (lượt thường không cần khung này).
import { useState } from 'react';
import { toast } from 'sonner';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useStartSurgery, useVisitSurgeries } from '../../../shared/hooks/useSurgery';
import { SURGERY_STATUS_LABELS, type Surgery, type SurgeryStatus } from '../../../shared/types/surgery';
import type { Visit } from '../../../shared/types/visit';
import { formatClock } from '../../../shared/utils/clinic-format';
import { CancelSurgeryDialog, CompleteSurgeryDialog, PlanSurgeryDialog } from '../surgery/SurgeryDialogs';
import { WsButton } from '../ui';

const TONE: Record<SurgeryStatus, string> = { PLANNED: 'wait', CONSENTED: 'accent', IN_PROGRESS: 'busy', COMPLETED: 'ready', CANCELLED: 'done' };

type Dialog = { kind: 'plan' } | { kind: 'complete' | 'cancel'; surgery: Surgery };

export function SurgerySection({ visit, canManage }: { visit: Visit; canManage: boolean }) {
  const query = useVisitSurgeries(visit.visitId);
  const start = useStartSurgery();
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [error, setError] = useState<string | null>(null);
  const surgeries = query.data ?? [];
  if (!canManage && surgeries.length === 0) return null;

  const startSurgery = async (s: Surgery) => {
    setError(null);
    try {
      await start.mutateAsync(s.surgeryId);
      toast.success(`Đã bắt đầu phẫu thuật cho ${s.petName}.`);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };
  const done = (message: string) => {
    setDialog(null);
    setError(null);
    toast.success(message);
  };

  return (
    <section aria-labelledby={`surgery-${visit.visitId}`} className="flex flex-col gap-3 rounded-lg border border-(--ws-line) p-3">
      <div className="flex items-center gap-2">
        <h3 id={`surgery-${visit.visitId}`} className="mr-auto text-sm font-semibold">
          Phẫu thuật
        </h3>
        {canManage && (
          <WsButton size="sm" onClick={() => setDialog({ kind: 'plan' })}>
            Lập kế hoạch phẫu thuật
          </WsButton>
        )}
      </div>
      {surgeries.length === 0 ? (
        <p className="text-sm text-(--ws-ink-3)">{query.isError ? 'Không tải được danh sách phẫu thuật.' : 'Chưa có phẫu thuật nào trong lượt này.'}</p>
      ) : (
        <ul aria-label="Phẫu thuật trong lượt" className="flex flex-col gap-2">
          {surgeries.map((s) => (
            <li key={s.surgeryId} className="rounded-md bg-(--ws-raised) px-3 py-2 text-sm">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-medium">{s.serviceName}</span>
                <span className="ws-chip" data-tone={TONE[s.status]}>
                  {SURGERY_STATUS_LABELS[s.status]}
                </span>
              </div>
              {s.planNote && <p className="text-(--ws-ink-2)">Kế hoạch: {s.planNote}</p>}
              {s.consentedAt && (
                <p className="text-(--ws-ink-2)">
                  Cam kết lúc {formatClock(s.consentedAt)}, người ký {s.consentSignerName} ({s.consentSignerRelation})
                </p>
              )}
              {s.resultNote && <p className="text-(--ws-ink-2)">Kết quả: {s.resultNote}</p>}
              {s.cancelReason && <p className="text-(--ws-ink-2)">Lý do hủy: {s.cancelReason}</p>}
              {s.status === 'PLANNED' && canManage && <p className="text-(--ws-ink-3)">Chờ lễ tân lập cam kết.</p>}
              {canManage && (s.status === 'PLANNED' || s.status === 'CONSENTED' || s.status === 'IN_PROGRESS') && (
                <div className="mt-2 flex flex-wrap gap-2">
                  {s.status === 'CONSENTED' && (
                    <WsButton size="sm" variant="primary" disabled={start.isPending} onClick={() => void startSurgery(s)}>
                      Bắt đầu phẫu thuật
                    </WsButton>
                  )}
                  {s.status === 'IN_PROGRESS' && (
                    <WsButton size="sm" variant="primary" onClick={() => setDialog({ kind: 'complete', surgery: s })}>
                      Hoàn tất phẫu thuật
                    </WsButton>
                  )}
                  <WsButton size="sm" variant="danger" onClick={() => setDialog({ kind: 'cancel', surgery: s })}>
                    {s.status === 'IN_PROGRESS' ? 'Dừng phẫu thuật' : 'Hủy phẫu thuật'}
                  </WsButton>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      {error && (
        <p role="alert" className="text-sm font-medium text-(--ws-urgent-ink)">
          {error}
        </p>
      )}
      {dialog?.kind === 'plan' && <PlanSurgeryDialog visitId={visit.visitId} petName={visit.petName} onClose={() => setDialog(null)} onDone={done} />}
      {dialog?.kind === 'complete' && <CompleteSurgeryDialog surgery={dialog.surgery} onClose={() => setDialog(null)} onDone={done} />}
      {dialog?.kind === 'cancel' && <CancelSurgeryDialog surgery={dialog.surgery} onClose={() => setDialog(null)} onDone={done} />}
    </section>
  );
}
