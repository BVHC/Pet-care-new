// Một mục nhật ký: bốn ô, ghi chú, ảnh, người ghi + giờ ghi (giờ VN), cờ bất thường, các bản bổ sung và nút Sửa / Thêm bản bổ sung theo quyền (BR-LT-11).
import { ImageOff, TriangleAlert } from 'lucide-react';
import { useState } from 'react';
import { formatShortDate, formatVnDateTime } from '@/components/appointments/format';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import type { CareLog, CareLogAddendum } from '@/shared/types/boarding';
import { AddendumForm } from './AddendumForm';
import { CareLogForm } from './CareLogForm';
import { FIELD_LABELS, TEXT_FIELDS, canEditEntry, minutesLeft } from './rules';

export type EntryForm = 'edit' | 'addendum';

interface Props {
  log: CareLog;
  petName: string;
  now: number;
  /** Id tài khoản đang đăng nhập (so với `recordedBy` / `createdBy`). */
  accountId: number | null;
  /** VET / CARETAKER: được sửa mục của mình và thêm bản bổ sung. */
  canWrite: boolean;
  /** Thú còn trong chuồng: chỉ khi đó mới sửa được (đã kết thúc → chỉ xem + bản bổ sung). */
  inStay: boolean;
  /** Biểu mẫu đang mở trong mục này (cả hộp thoại chỉ mở một biểu mẫu một lúc). */
  open: EntryForm | null;
  onOpen: (form: EntryForm) => void;
  onClose: () => void;
  /** 409 khi sửa: quá hạn (người khác / đồng hồ lệch) — cha chuyển sang chế độ bổ sung. */
  onExpired: (message: string) => void;
}

/** Hợp đồng chỉ trả `createdBy` (không có tên): "Bạn", tên người ghi nếu cùng người, còn lại nhãn trung tính. */
function authorOf(addendum: CareLogAddendum, log: CareLog, accountId: number | null): string {
  if (addendum.createdBy === accountId) return 'Bạn';
  return addendum.createdBy === log.recordedBy ? log.recordedByName : `Nhân viên #${addendum.createdBy}`;
}

function Photo({ url, alt }: { url: string; alt: string }) {
  const [failed, setFailed] = useState(false);
  return (
    <a
      href={url}
      target="_blank"
      rel="noopener noreferrer"
      className="flex aspect-square min-h-16 items-center justify-center overflow-hidden rounded-lg border border-(--color-border-default) bg-(--bg-secondary)"
    >
      {failed ? (
        <span className="flex flex-col items-center gap-1 px-1 text-center text-xs text-(--text-secondary)">
          <ImageOff aria-hidden className="size-5" />
          <span>Không tải được ảnh</span>
        </span>
      ) : (
        <img src={url} alt={alt} loading="lazy" referrerPolicy="no-referrer" onError={() => setFailed(true)} className="size-full object-cover" />
      )}
    </a>
  );
}

export function CareLogEntry({ log, petName, now, accountId, canWrite, inStay, open, onOpen, onClose, onExpired }: Props) {
  const time = formatVnDateTime(log.createdAt);
  const editable = canWrite && canEditEntry(log, { accountId, inStay, now });
  const mine = accountId !== null && log.recordedBy === accountId;
  const left = minutesLeft(log, now);
  const filled = TEXT_FIELDS.filter((field) => log[field]);

  return (
    <article
      aria-label={`Nhật ký của ${log.recordedByName} lúc ${time}`}
      className={cn('rounded-xl border bg-white p-3 text-sm', log.isAbnormal ? 'border-red-300' : 'border-(--color-border-default)')}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="min-w-0 break-words text-xs text-(--text-secondary)">{`${log.recordedByName} · ghi lúc ${time}`}</p>
        {log.isAbnormal && (
          <span className="inline-flex items-center gap-1 rounded-full border border-red-300 bg-red-50 px-2 py-0.5 text-xs font-semibold text-red-700">
            <TriangleAlert aria-hidden className="size-3.5" />
            Bất thường
          </span>
        )}
      </div>

      {open === 'edit' ? (
        <div className="mt-2">
          <CareLogForm kind="edit" log={log} onDone={onClose} onCancel={onClose} onConflict={onExpired} />
        </div>
      ) : (
        <>
          {filled.length > 0 && (
            <dl className="mt-2 grid gap-x-4 gap-y-2 sm:grid-cols-2">
              {filled.map((field) => (
                <div key={field} className="min-w-0">
                  <dt className="text-xs font-semibold text-(--text-secondary)">{FIELD_LABELS[field]}</dt>
                  <dd className="break-words whitespace-pre-line">{log[field]}</dd>
                </div>
              ))}
            </dl>
          )}
          {log.note && <p className="mt-2 break-words whitespace-pre-line">{log.note}</p>}
          {log.photoUrls.length > 0 && (
            <ul className="mt-2 grid grid-cols-3 gap-2 sm:grid-cols-5">
              {log.photoUrls.map((url, i) => (
                <li key={`${i}-${url}`}>
                  <Photo url={url} alt={`Ảnh ${i + 1}/${log.photoUrls.length} nhật ký ngày ${formatShortDate(log.logDate)} của ${petName}`} />
                </li>
              ))}
            </ul>
          )}
        </>
      )}

      {log.addenda.length > 0 && (
        <div className="mt-3 space-y-2 border-t border-(--color-border-default) pt-3">
          <p className="text-xs font-semibold text-(--text-secondary)">Bản bổ sung</p>
          <ul className="space-y-2">
            {log.addenda.map((a) => (
              <li key={a.addendumId} className="rounded-lg bg-(--bg-secondary) px-3 py-2">
                <p className="break-words whitespace-pre-line">{a.content}</p>
                <p className="mt-1 text-xs text-(--text-secondary)">{`${authorOf(a, log, accountId)} · ${formatVnDateTime(a.createdAt)}`}</p>
              </li>
            ))}
          </ul>
        </div>
      )}

      {open === 'addendum' && <AddendumForm careLogId={log.careLogId} onDone={onClose} onCancel={onClose} />}

      {canWrite && open === null && (
        <div className="mt-3 flex flex-wrap items-center gap-2">
          {editable && (
            <>
              <Button variant="outline" onClick={() => onOpen('edit')}>
                Sửa
              </Button>
              <span className="text-xs text-(--text-secondary)">còn {left} phút để sửa</span>
            </>
          )}
          {!editable && inStay && mine && <span className="text-xs text-(--text-secondary)">Đã quá thời hạn sửa — thêm bản bổ sung</span>}
          <Button variant="outline" onClick={() => onOpen('addendum')}>
            Thêm bản bổ sung
          </Button>
        </div>
      )}
    </article>
  );
}
