// Đầu trang: mã, thú, loại chuồng (+ mã chuồng khi đang ở), trạng thái và hành động của khách (gia hạn / hủy).
import { CalendarPlus, CircleCheck, X } from 'lucide-react';
import { cn } from '@/lib/utils';
import type { Booking } from '@/shared/types/boarding';
import { canCancel, canExtend, isInStay } from '@/shared/utils/boarding';
import { formatShortDate } from '@/components/appointments/format';
import { bookingNights, isDeceasedEnd, statusLabel, statusTone } from './model';
import { BTN_DANGER_OUTLINE, BTN_PRIMARY, CARD, STATUS_ICON, TONE_CLASS } from './styles';

export function BookingStatusBadge({ booking }: { booking: Pick<Booking, 'status' | 'endReason'> }) {
  const Icon = isDeceasedEnd(booking) ? CircleCheck : STATUS_ICON[booking.status];
  return (
    <span className={cn('inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-[12px] font-bold', TONE_CLASS[statusTone(booking)])}>
      <Icon size={13} aria-hidden />
      {statusLabel(booking)}
    </span>
  );
}

export function BookingHeader({ booking: b, onExtend, onCancel }: { booking: Booking; onExtend: () => void; onCancel: () => void }) {
  const extendable = canExtend(b);
  const cancellable = canCancel(b);
  return (
    <section aria-label="Tổng quan đặt chỗ" className={cn(CARD, 'p-5 sm:p-6')}>
      <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-4">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
            <BookingStatusBadge booking={b} />
            <span className="break-all text-[12.5px] font-bold text-accent">{b.code}</span>
          </div>
          <h2 className="mt-3 break-words font-(family-name:--font-friendly) text-[28px] font-bold leading-tight text-(--color-text-primary)">{b.petName}</h2>
          <p className="mt-1.5 flex flex-wrap gap-x-5 gap-y-1 text-[13.5px] text-(--color-text-secondary)">
            <span>{b.kennelTypeName}</span>
            <span>
              Nhận {formatShortDate(b.checkInDate)}, trả {formatShortDate(b.checkOutDate)} ({bookingNights(b)} đêm)
            </span>
            {isInStay(b.status) && b.kennelCode && (
              <span>
                Mã chuồng <strong className="text-(--color-text-primary)">{b.kennelCode}</strong>
              </span>
            )}
          </p>
        </div>
        {(extendable || cancellable) && (
          <div className="flex w-full flex-col gap-2 min-[420px]:flex-row sm:w-auto">
            {extendable && (
              <button type="button" onClick={onExtend} className={cn(BTN_PRIMARY, 'min-[420px]:flex-1 sm:flex-none')}>
                <CalendarPlus size={16} aria-hidden /> Gia hạn
              </button>
            )}
            {cancellable && (
              <button type="button" onClick={onCancel} className={cn(BTN_DANGER_OUTLINE, 'min-[420px]:flex-1 sm:flex-none')}>
                <X size={16} aria-hidden /> Hủy đặt chỗ
              </button>
            )}
          </div>
        )}
      </div>
    </section>
  );
}
