// Bản bổ sung bệnh án (UC53, visit-v1 #15; BR-KB-01, ADR-0070): chỉ có khi lượt đã COMPLETED, bệnh án đã khóa. Mọi VET của chi nhánh thêm được (không cần là người phụ trách),
// chỉ thêm, không sửa / xóa nội dung cũ. CARETAKER chỉ xem (#12). Khách chỉ nhận bản không nội bộ ở hồ sơ sức khỏe (#21).
import { useState } from 'react';
import { toast } from 'sonner';
import { useAddAddendum } from '../../../shared/hooks/useExam';
import { visitErrorMessage } from '../../../shared/hooks/useVisits';
import type { Addendum } from '../../../shared/types/medical-record';
import { formatClock } from '../../../shared/utils/clinic-format';
import { WsButton } from '../ui';
import { fieldClass } from './field-class';

interface Props {
  visitId: number;
  addenda: Addendum[];
  /** VET (visitPermissions.addendum). */
  canAdd: boolean;
}

export function AddendaSection({ visitId, addenda, canAdd }: Props) {
  const add = useAddAddendum();
  const [content, setContent] = useState('');
  const [internal, setInternal] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    setError(null);
    try {
      await add.mutateAsync({ visitId, content: content.trim(), isInternal: internal });
      setContent('');
      setInternal(false);
      toast.success('Đã thêm bản bổ sung');
    } catch (e) {
      setError(visitErrorMessage(e, 'addendum'));
    }
  };

  return (
    <section aria-labelledby={`addenda-${visitId}`} className="flex flex-col gap-3 rounded-lg border border-(--ws-line) p-3">
      <h3 id={`addenda-${visitId}`} className="text-sm font-semibold">
        Bản bổ sung
      </h3>
      {addenda.length === 0 ? (
        <p className="text-sm text-(--ws-ink-3)">Chưa có bản bổ sung.</p>
      ) : (
        <ul aria-label="Bản bổ sung" className="flex flex-col gap-2">
          {addenda.map((a) => (
            <li key={a.addendumId} className="rounded-md bg-(--ws-raised) px-3 py-2 text-sm">
              <p className="whitespace-pre-wrap">{a.content}</p>
              <p className="mt-1 flex flex-wrap items-center gap-1.5 text-xs text-(--ws-ink-3)">
                <span>{a.createdByName}</span>
                <span className="ws-num">{formatClock(a.createdAt)}</span>
                {a.isInternal && (
                  <span className="ws-chip" data-tone="wait">
                    Nội bộ
                  </span>
                )}
              </p>
            </li>
          ))}
        </ul>
      )}

      {canAdd && (
        <div className="flex flex-col gap-2">
          <label htmlFor={`addendum-${visitId}`} className="text-sm font-medium">
            Nội dung bổ sung
          </label>
          <textarea id={`addendum-${visitId}`} rows={2} value={content} onChange={(e) => setContent(e.target.value)} className={fieldClass} />
          <label className="flex items-center gap-2 text-sm text-(--ws-ink-2)">
            <input type="checkbox" checked={internal} onChange={(e) => setInternal(e.target.checked)} />
            Chỉ nhân viên xem
          </label>
          {error && (
            <p role="alert" className="text-sm font-medium text-(--ws-urgent-ink)">
              {error}
            </p>
          )}
          <WsButton variant="primary" className="self-start" disabled={add.isPending || content.trim() === ''} onClick={() => void submit()}>
            Thêm bổ sung
          </WsButton>
        </div>
      )}
    </section>
  );
}
