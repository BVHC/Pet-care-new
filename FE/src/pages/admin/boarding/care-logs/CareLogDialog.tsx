// UC57 "Nhật ký chăm sóc" (A7, A8, BR-LT-11, BR-LT-12): xem nhật ký theo ngày, ghi mới (kèm cờ bất thường), sửa trong hạn, bản bổ sung.
// Chữ ký và tên truy cập của role="dialog" ("Nhật ký chăm sóc") là hợp đồng với AdminBoardingPage: không đổi.
//
// ASSUMPTION (UI): (1) đặt chỗ đã trả thú (CHECKED_OUT) chỉ xem + thêm bản bổ sung (hợp đồng A4 "bất cứ lúc nào"), không ghi mới / sửa; BOOKED, CANCELLED, NO_SHOW chưa có nhật ký nên không gọi API;
// (2) mục hoàn toàn trống bị chặn ở form; (3) hộp thoại dựa vào `booking` được truyền vào, không tải lại đặt chỗ — 409 khi ghi (thú không còn lưu trú) chuyển hộp thoại sang chỉ xem.
import { X } from 'lucide-react';
import { useState } from 'react';
import { FormError } from '@/components/admin/form-ui';
import { formatShortDate, formatSlotDate, todayVn } from '@/components/appointments/format';
import { Button } from '@/components/ui/button';
import { Dialog, DialogClose, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { useCareLogs, useRefreshBoarding } from '@/shared/hooks/useBoarding';
import { useAccount } from '@/shared/stores/session.store';
import { BOOKING_STATUS_LABELS, type Booking, type BookingStatus } from '@/shared/types/boarding';
import { CareLogEntry, type EntryForm } from './CareLogEntry';
import { CareLogForm } from './CareLogForm';
import { useNow } from './hooks';
import { Notice } from './parts';
import { careLogMode, groupByDate } from './rules';

/** Đặt chỗ chưa từng ở trong chuồng: chưa thể có nhật ký, không gọi API. */
const NO_LOGS: Partial<Record<BookingStatus, string>> = {
  BOOKED: 'Thú chưa vào chuồng nên chưa có nhật ký chăm sóc.',
  CANCELLED: 'Đặt chỗ đã hủy nên không có nhật ký chăm sóc.',
  NO_SHOW: 'Khách không đến nhận chỗ nên không có nhật ký chăm sóc.',
};

/** Biểu mẫu đang mở: ghi mới (đầu danh sách) hoặc sửa / bổ sung một mục. Chỉ một biểu mẫu một lúc. */
type Active = { kind: 'new' } | { kind: EntryForm; careLogId: number } | null;

export function CareLogDialog({ booking, canWrite, onClose }: { booking: Booking; canWrite: boolean; onClose: () => void }) {
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent showCloseButton={false} className="max-h-[90dvh] overflow-y-auto bg-white p-4 text-(--text-primary) sm:max-w-2xl sm:p-6">
        <div className="flex items-start justify-between gap-2">
          <DialogHeader className="min-h-10 min-w-0 justify-center">
            <DialogTitle>Nhật ký chăm sóc</DialogTitle>
          </DialogHeader>
          {/* Nút X mặc định của Dialog chỉ 16 px: dùng nút 40 px (mục tiêu chạm). */}
          <DialogClose asChild>
            <Button variant="ghost" size="icon" aria-label="Đóng hộp thoại" className="-mt-1 -mr-2 shrink-0">
              <X aria-hidden className="size-4" />
            </Button>
          </DialogClose>
        </div>
        <CareLogBody booking={booking} canWrite={canWrite} />
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Đóng
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function CareLogBody({ booking, canWrite }: { booking: Booking; canWrite: boolean }) {
  const baseMode = careLogMode(booking.status);
  const logs = useCareLogs(booking.bookingId, baseMode !== 'none');
  const [now, syncNow] = useNow();
  const today = todayVn(now);
  const accountId = useAccount()?.id ?? null;
  const refresh = useRefreshBoarding();
  /** 409 "thú không đang lưu trú": đặt chỗ đã đổi ở máy khác, trong khi `booking` ở trang còn cũ. */
  const [ended, setEnded] = useState(false);
  const [active, setActive] = useState<Active>(null);
  const [notice, setNotice] = useState('');

  const mode = ended && baseMode === 'inStay' ? 'finished' : baseMode;
  const inStay = mode === 'inStay';
  const open = (next: Active) => {
    setNotice('');
    setActive(next);
  };
  const done = () => {
    syncNow();
    setActive(null);
  };
  const conflict = (next: Active, markEnded: boolean) => (message: string) => {
    syncNow();
    setNotice(message);
    setActive(next);
    if (markEnded) setEnded(true);
    refresh();
  };

  // BR-LT-11: có dữ liệu thì tính từ danh sách (tự cập nhật sau khi ghi và khi qua nửa đêm); chưa có thì dùng cờ của đặt chỗ.
  const todayCount = logs.data?.filter((l) => l.logDate === today).length;
  const missingToday = todayCount === undefined ? booking.missingCareLogToday : todayCount === 0;

  return (
    <>
      <div className="rounded-xl border border-(--color-border-default) bg-(--color-surface-2) p-3 text-sm">
        <p className="font-bold break-words">{`${booking.petName} · ${booking.customerName}`}</p>
        <p className="mt-0.5 text-xs text-(--text-secondary) break-all">
          {`Mã ${booking.code} · ${BOOKING_STATUS_LABELS[booking.status]}${booking.kennelCode ? ` · chuồng ${booking.kennelCode}` : ''}`}
        </p>
      </div>

      {inStay ? (
        missingToday ? (
          <Notice tone="warning" title={`Hôm nay chưa có nhật ký chăm sóc (${formatShortDate(today)})`}>
            Mỗi ngày lưu trú cần tối thiểu 1 nhật ký (BR-LT-11).
          </Notice>
        ) : (
          <Notice tone="success" title={todayCount === undefined ? 'Hôm nay đã có nhật ký chăm sóc' : `Hôm nay đã có ${todayCount} nhật ký chăm sóc`} />
        )
      ) : mode === 'finished' ? (
        <Notice tone="info" title="Lưu trú đã kết thúc: không ghi nhật ký mới.">
          {canWrite ? 'Vẫn thêm được bản bổ sung (BR-LT-11).' : undefined}
        </Notice>
      ) : (
        <Notice tone="info" title={NO_LOGS[booking.status] ?? 'Chưa có nhật ký chăm sóc.'} />
      )}

      {!canWrite && <p className="text-sm text-(--text-secondary)">Bạn chỉ xem nhật ký. Chỉ nhân viên chăm sóc và bác sĩ thú y được ghi, sửa hoặc bổ sung nhật ký.</p>}
      <FormError message={notice} />

      {canWrite && inStay && active?.kind !== 'new' && (
        <div>
          <Button onClick={() => open({ kind: 'new' })}>Ghi nhật ký mới</Button>
        </div>
      )}
      {active?.kind === 'new' && (
        <CareLogForm kind="create" bookingId={booking.bookingId} today={today} onDone={done} onCancel={done} onConflict={conflict(null, true)} />
      )}

      {baseMode !== 'none' && (
        <section aria-label="Danh sách nhật ký" className="space-y-4">
          {!logs.data ? (
            logs.isError ? (
              <>
                <FormError message={plainMessage(toApiError(logs.error))} />
                <Button variant="outline" onClick={() => logs.refetch()}>
                  Thử lại
                </Button>
              </>
            ) : (
              <p role="status" className="animate-pulse rounded-lg bg-(--bg-secondary) px-3 py-6 text-center text-sm text-(--text-secondary)">
                Đang tải nhật ký…
              </p>
            )
          ) : logs.data.length === 0 ? (
            <p className="rounded-lg bg-(--bg-secondary) px-3 py-6 text-center text-sm text-(--text-secondary)">Chưa có nhật ký nào.</p>
          ) : (
            groupByDate(logs.data).map((group) => (
              <div key={group.date} className="space-y-2">
                <h3 className="text-sm font-semibold">
                  {group.date === today && 'Hôm nay · '}
                  {formatSlotDate(group.date)}
                </h3>
                {group.logs.map((log) => (
                  <CareLogEntry
                    key={log.careLogId}
                    log={log}
                    petName={booking.petName}
                    now={now}
                    accountId={accountId}
                    canWrite={canWrite}
                    inStay={inStay}
                    open={active && active.kind !== 'new' && active.careLogId === log.careLogId ? active.kind : null}
                    onOpen={(form) => open({ kind: form, careLogId: log.careLogId })}
                    onClose={done}
                    onExpired={conflict({ kind: 'addendum', careLogId: log.careLogId }, false)}
                  />
                ))}
              </div>
            ))
          )}
        </section>
      )}
    </>
  );
}
