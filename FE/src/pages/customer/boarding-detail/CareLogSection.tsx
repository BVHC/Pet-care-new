// Nhật ký chăm sóc (UC60): khách chỉ xem. Nhóm theo ngày mới → cũ; bất thường nổi bật bằng chữ + biểu tượng; bản bổ sung hiện
// như ghi thêm sau (BR-LT-11: nội dung gốc không bị sửa lại); ảnh là URL https do nhân viên nhập (không tải lên, hợp đồng Q1 còn TBD).
import { useId, useState, type ReactNode } from 'react';
import { ImageOff, RefreshCw, TriangleAlert } from 'lucide-react';
import { formatShortDate, formatSlotDate, formatVnDateTime } from '@/components/appointments/format';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { cn } from '@/lib/utils';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { useRefreshBoarding } from '@/shared/hooks/useBoarding';
import type { Booking, CareLog } from '@/shared/types/boarding';
import { isInStay } from '@/shared/utils/boarding';
import { CARE_LOG_REFRESH_MS, useStayCareLogs } from './hooks';
import { groupCareLogs, hasCareLogs } from './model';
import { BTN_OUTLINE, CARD, H2 } from './styles';

const FIELDS = [
  ['eating', 'Ăn'],
  ['drinking', 'Uống'],
  ['hygiene', 'Vệ sinh'],
  ['activity', 'Hoạt động'],
] as const;

interface PhotoView {
  url: string;
  alt: string;
  date: string;
}

export function CareLogSection({ booking }: { booking: Booking }) {
  const titleId = useId();
  const logs = useStayCareLogs(booking);
  const refresh = useRefreshBoarding();
  const [viewing, setViewing] = useState<PhotoView | null>(null);
  const inStay = isInStay(booking.status);

  return (
    <section aria-labelledby={titleId} className={cn(CARD, 'p-5 sm:p-6')}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 flex-1 basis-64">
          <h2 id={titleId} className={H2}>
            Nhật ký chăm sóc
          </h2>
          {inStay && (
            <p className="mt-1 text-[12.5px] text-(--color-text-secondary)">
              Nhân viên cập nhật tình hình của {booking.petName} mỗi ngày. Trang tự làm mới mỗi {CARE_LOG_REFRESH_MS / 1000} giây khi bạn đang mở.
            </p>
          )}
        </div>
        {inStay && (
          <button type="button" onClick={() => void refresh()} className={cn(BTN_OUTLINE, 'shrink-0')}>
            <RefreshCw size={15} className={logs.isFetching ? 'animate-spin motion-reduce:animate-none' : undefined} aria-hidden /> Làm mới
          </button>
        )}
      </div>

      <div className="mt-4">
        {!hasCareLogs(booking.status) ? (
          <Plain>
            {booking.status === 'BOOKED'
              ? `Nhật ký chăm sóc sẽ xuất hiện khi ${booking.petName} bắt đầu lưu trú.`
              : 'Đặt chỗ này không có nhật ký chăm sóc.'}
          </Plain>
        ) : logs.data === undefined ? (
          logs.isError ? (
            <div className="space-y-3">
              <p role="alert" className="rounded-xl bg-red-50 px-4 py-3 text-[13.5px] text-red-800">
                {plainMessage(toApiError(logs.error))}
              </p>
              <button type="button" onClick={() => void logs.refetch()} className={BTN_OUTLINE}>
                Thử lại
              </button>
            </div>
          ) : (
            <LogSkeleton />
          )
        ) : logs.data.length === 0 ? (
          <Plain>
            {inStay ? (
              <>
                <strong className="block text-(--color-text-primary)">Hôm nay thú cưng chưa có nhật ký.</strong>
                Nhân viên sẽ ghi lại ăn uống, vệ sinh và hoạt động của bé trong ngày.
              </>
            ) : (
              'Lưu trú này không có nhật ký chăm sóc nào được ghi lại.'
            )}
          </Plain>
        ) : (
          <div className="space-y-6">
            {groupCareLogs(logs.data).map((day) => (
              <div key={day.date}>
                <h3 className="mb-3 text-[13.5px] font-bold text-(--color-text-primary)">{formatSlotDate(day.date)}</h3>
                <div className="space-y-3">
                  {day.logs.map((log) => (
                    <CareLogEntry key={log.careLogId} log={log} petName={booking.petName} onOpenPhoto={setViewing} />
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      <PhotoViewer view={viewing} onClose={() => setViewing(null)} />
    </section>
  );
}

function Plain({ children }: { children: ReactNode }) {
  return (
    <p className="rounded-xl border border-dashed border-(--color-border-default) bg-(--color-surface-1) px-4 py-8 text-center text-[13.5px] leading-relaxed text-(--color-text-secondary)">
      {children}
    </p>
  );
}

function LogSkeleton() {
  return (
    <div role="status" className="space-y-3">
      <span className="sr-only">Đang tải nhật ký chăm sóc…</span>
      {[0, 1].map((i) => (
        <div key={i} aria-hidden className="h-24 animate-pulse rounded-xl bg-(--color-surface-2) motion-reduce:animate-none" />
      ))}
    </div>
  );
}

function CareLogEntry({ log, petName, onOpenPhoto }: { log: CareLog; petName: string; onOpenPhoto: (view: PhotoView) => void }) {
  const filled = FIELDS.filter(([key]) => log[key]);
  return (
    <article
      aria-label={`Nhật ký ghi lúc ${formatVnDateTime(log.createdAt)}`}
      data-abnormal={log.isAbnormal ? '' : undefined}
      className={cn('rounded-xl border p-4', log.isAbnormal ? 'border-amber-300 bg-amber-50' : 'border-(--color-border-default) bg-(--color-surface-1)')}
    >
      {log.isAbnormal && (
        <div className="mb-3 text-amber-950">
          <p className="flex items-center gap-2 text-[14px] font-bold">
            <TriangleAlert size={17} aria-hidden />
            <span>Bất thường</span>
          </p>
          <p className="mt-0.5 text-[12.5px]">Nhân viên đánh dấu mục này cần lưu ý. Vui lòng xem ghi chú bên dưới hoặc liên hệ chi nhánh.</p>
        </div>
      )}

      <p className="text-[12px] text-(--color-text-secondary)">
        Ghi lúc {formatVnDateTime(log.createdAt)} bởi <strong className="text-(--color-text-primary)">{log.recordedByName}</strong>
      </p>

      {(filled.length > 0 || log.note) && (
        <dl className="mt-3 grid gap-x-6 gap-y-2.5 sm:grid-cols-2">
          {filled.map(([key, label]) => (
            <div key={key}>
              <dt className="text-[12px] font-bold text-(--color-text-secondary)">{label}</dt>
              <dd className="break-words text-[13.5px] text-(--color-text-primary)">{log[key]}</dd>
            </div>
          ))}
          {log.note && (
            <div className="sm:col-span-2">
              <dt className="text-[12px] font-bold text-(--color-text-secondary)">Ghi chú</dt>
              <dd className="break-words text-[13.5px] text-(--color-text-primary)">{log.note}</dd>
            </div>
          )}
        </dl>
      )}

      {log.photoUrls.length > 0 && <PhotoGrid log={log} petName={petName} onOpen={onOpenPhoto} />}

      {log.addenda.length > 0 && (
        <div role="group" aria-label="Bổ sung sau" className="mt-4 space-y-2.5 border-l-2 border-(--color-border-strong) pl-3">
          <p className="text-[12px] font-bold text-(--color-text-secondary)">Bổ sung sau. Nội dung gốc ở trên được giữ nguyên.</p>
          {log.addenda.map((addendum) => (
            <div key={addendum.addendumId}>
              <p className="break-words text-[13.5px] text-(--color-text-primary)">{addendum.content}</p>
              <p className="text-[11.5px] text-(--color-text-secondary)">Bổ sung lúc {formatVnDateTime(addendum.createdAt)}</p>
            </div>
          ))}
        </div>
      )}
    </article>
  );
}

function PhotoGrid({ log, petName, onOpen }: { log: CareLog; petName: string; onOpen: (view: PhotoView) => void }) {
  const [failed, setFailed] = useState<ReadonlySet<number>>(new Set());
  const date = formatShortDate(log.logDate);
  return (
    <ul className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-3">
      {log.photoUrls.map((url, i) => {
        const alt = `Ảnh ${i + 1} trong nhật ký ngày ${date} của ${petName}`;
        return (
          <li key={`${url}-${i}`} className="aspect-[4/3] overflow-hidden rounded-lg bg-(--color-surface-2)">
            {failed.has(i) ? (
              <div className="flex h-full w-full flex-col items-center justify-center gap-1 p-2 text-center text-[12px] text-(--color-text-secondary)">
                <ImageOff size={18} aria-hidden />
                Không tải được ảnh
              </div>
            ) : (
              <button
                type="button"
                aria-label={`Xem ảnh lớn: ${alt}`}
                onClick={() => onOpen({ url, alt, date })}
                className="block h-full w-full focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-accent"
              >
                <img
                  src={url}
                  alt={alt}
                  loading="lazy"
                  referrerPolicy="no-referrer"
                  onError={() => setFailed((prev) => new Set(prev).add(i))}
                  className="h-full w-full object-cover"
                />
              </button>
            )}
          </li>
        );
      })}
    </ul>
  );
}

/** Xem ảnh lớn bằng `ui/dialog` (Radix): Esc hoặc nút đóng thoát được, tiêu điểm trả về ảnh đã bấm. */
function PhotoViewer({ view, onClose }: { view: PhotoView | null; onClose: () => void }) {
  const [broken, setBroken] = useState(false);
  const close = () => {
    setBroken(false);
    onClose();
  };
  return (
    <Dialog open={view !== null} onOpenChange={(next) => !next && close()}>
      <DialogContent className="bg-white p-4 text-(--color-text-primary) sm:max-w-3xl">
        <DialogTitle>{view ? `Ảnh nhật ký ngày ${view.date}` : 'Ảnh nhật ký'}</DialogTitle>
        <DialogDescription className="sr-only">Ảnh chăm sóc do nhân viên ghi trong nhật ký.</DialogDescription>
        {view &&
          (broken ? (
            <p className="flex items-center justify-center gap-2 py-16 text-[13px] text-(--color-text-secondary)">
              <ImageOff size={18} aria-hidden /> Không tải được ảnh
            </p>
          ) : (
            <img
              src={view.url}
              alt={view.alt}
              referrerPolicy="no-referrer"
              onError={() => setBroken(true)}
              className="max-h-[70dvh] w-full rounded-lg object-contain"
            />
          ))}
        {/* Nút đóng đủ lớn cho cảm ứng (điện thoại không có Esc; dấu X của ui/dialog nhỏ hơn 40 px). */}
        <button type="button" onClick={close} className={BTN_OUTLINE}>
          Đóng
        </button>
      </DialogContent>
    </Dialog>
  );
}
