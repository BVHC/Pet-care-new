/**
 * Thẻ một lượt lưu trú trong "Lưu trú của tôi" (UC60). Chỉ đọc: gia hạn / hủy nằm ở trang chi tiết.
 * Tiền "tạm tính" = số đêm đã đặt × giá đêm lúc đặt (BR-LT-02); số tiền thật chốt khi trả thú (BR-LT-09), nên chỉ hiện khi còn lưu trú.
 */
import { Link } from 'react-router-dom';
import { ChevronRight, MapPin, PawPrint } from 'lucide-react';
import { formatShortDate } from '@/components/appointments/format';
import { cn } from '@/lib/utils';
import {
  BOARDING_CFG,
  BOOKING_CANCEL_SOURCE_LABELS,
  BOOKING_STATUS_LABELS,
  END_REASON_LABELS,
  type Booking,
} from '@/shared/types/boarding';
import { BOOKING_STATUS_TONE, isActiveBooking, nightsBetween, stayAmount } from '@/shared/utils/boarding';
import { vnDate } from '@/shared/utils/vn-time';
import { nightsLabel, vnd, weekdayShort } from './format';

const TONE_CLASS = {
  info: 'bg-blue-100 text-blue-700',
  warning: 'bg-amber-100 text-amber-800',
  success: 'bg-emerald-100 text-emerald-700',
  neutral: 'bg-gray-100 text-gray-600',
  danger: 'bg-red-100 text-red-700',
} as const;

const chip = 'inline-flex items-center rounded-full border border-(--color-border-default) bg-white px-2.5 py-0.5 text-[11.5px] font-bold text-(--color-text-secondary)';

function DateBlock({ label, date }: { label: string; date: string }) {
  return (
    <div className="min-w-0 shrink-0">
      <p className="text-[12px] font-semibold text-(--color-text-secondary)">{label}</p>
      <p className="font-friendly text-[17px] leading-tight font-extrabold text-(--color-text-primary)">{formatShortDate(date)}</p>
      <p className="text-[12px] text-(--color-text-secondary)">{weekdayShort(date)}</p>
    </div>
  );
}

export function BookingCard({ booking: b, branch }: { booking: Booking; branch?: { name: string; phone?: string } }) {
  const nights = nightsBetween(b.checkInDate, b.checkOutDate);
  const live = isActiveBooking(b.status);
  const overdueFrom = b.overdueSince ? vnDate(Date.parse(b.overdueSince)) : b.checkOutDate;

  return (
    <article
      aria-label={`Lưu trú ${b.code}`}
      className="overflow-hidden rounded-2xl border border-(--color-border-default) bg-white shadow-[0_1px_2px_rgba(56,36,23,0.06),0_4px_12px_-8px_rgba(56,36,23,0.4)]"
    >
      <header className="flex items-center justify-between gap-3 border-b border-(--color-border-default) bg-(--color-surface-2) px-5 py-3">
        <span className={cn('rounded-full px-2.5 py-0.5 text-[11px] font-bold', TONE_CLASS[BOOKING_STATUS_TONE[b.status]])}>
          {BOOKING_STATUS_LABELS[b.status]}
        </span>
        <span className="text-[12px] font-bold break-all text-accent">{b.code}</span>
      </header>

      <div className="grid gap-5 p-5 md:grid-cols-[minmax(0,1fr)_auto] md:items-start">
        <div className="min-w-0 space-y-4">
          <div className="min-w-0">
            <h3 className="flex items-center gap-1.5 text-[16px] font-bold break-words text-(--color-text-primary)">
              <PawPrint size={15} className="shrink-0 text-accent" aria-hidden />
              <span className="min-w-0 break-words">{b.petName}</span>
            </h3>
            <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-[12.5px] text-(--color-text-secondary)">
              <span>{b.kennelTypeName}</span>
              {b.kennelCode && <span className={chip}>Mã chuồng {b.kennelCode}</span>}
              {branch && (
                <span className="flex min-w-0 items-center gap-1">
                  <MapPin size={13} className="shrink-0" aria-hidden /> <span className="min-w-0 break-words">{branch.name}</span>
                </span>
              )}
            </div>
          </div>

          <div className="flex items-center gap-3">
            <DateBlock label="Nhận" date={b.checkInDate} />
            <div className="flex min-w-10 flex-1 items-center gap-2">
              <span className="flex-1 border-t border-dashed border-(--color-border-strong)" aria-hidden />
              <span className="shrink-0 rounded-full bg-(--color-accent-soft) px-2.5 py-0.5 text-[12px] font-bold text-accent">{nightsLabel(nights)}</span>
              <span className="flex-1 border-t border-dashed border-(--color-border-strong)" aria-hidden />
            </div>
            <DateBlock label="Trả" date={b.checkOutDate} />
          </div>
        </div>

        <div className="rounded-xl bg-(--color-surface-2) p-4 md:min-w-[200px] md:text-right">
          <p className="font-friendly text-[18px] font-extrabold text-accent">
            {vnd.format(b.nightlyPrice)}
            <span className="text-[12px] font-semibold text-(--color-text-secondary)">/đêm</span>
          </p>
          {live && (
            <>
              <p className="mt-1 text-[13px] text-(--color-text-secondary)">
                Tạm tính <strong className="text-(--color-text-primary)">{vnd.format(stayAmount(nights, b.nightlyPrice))}</strong>
              </p>
              <p className="mt-1 text-[11.5px] leading-snug text-(--color-text-secondary)">Tiền chốt khi trả thú, theo số đêm thực tế.</p>
            </>
          )}
        </div>
      </div>

      {(b.status === 'CANCELLED' || b.status === 'NO_SHOW' || b.status === 'OVERDUE' || b.endReason === 'PET_DECEASED') && (
        <div className="space-y-2 border-t border-(--color-border-default) px-5 py-4 text-[12.5px] text-(--color-text-secondary)">
          {b.status === 'CANCELLED' && (
            <>
              <div className="flex flex-wrap items-center gap-2">
                {b.cancelSource && <span className={chip}>{BOOKING_CANCEL_SOURCE_LABELS[b.cancelSource]}</span>}
                {b.lateCancel && <span className={cn(chip, 'border-amber-300 bg-amber-50 text-amber-800')}>Hủy muộn</span>}
              </div>
              {b.cancelReason && <p>Lý do: {b.cancelReason}</p>}
              {b.lateCancel && (
                <p className="font-semibold text-amber-800">
                  Hủy khi còn dưới {BOARDING_CFG.lateCancelHours} giờ trước 00:00 ngày nhận, tính vào hạn chế đặt online.
                </p>
              )}
            </>
          )}
          {b.status === 'NO_SHOW' && (
            <p className="font-semibold text-red-800">Không đến nhận chỗ trong ngày nhận, tính vào hạn chế đặt online.</p>
          )}
          {b.status === 'OVERDUE' && (
            <p role="note" className="rounded-xl border border-red-200 bg-red-50 p-3 text-red-800">
              Đã quá hạn đón từ {formatShortDate(overdueFrom)}. Vui lòng liên hệ chi nhánh{branch?.phone ? ` (${branch.phone})` : ''} để đón thú sớm.
            </p>
          )}
          {b.endReason === 'PET_DECEASED' && (
            <div className="flex flex-wrap items-center gap-2">
              <span className={chip}>{END_REASON_LABELS.PET_DECEASED}</span>
            </div>
          )}
        </div>
      )}

      <footer className="flex justify-end border-t border-(--color-border-default) px-5 py-2">
        <Link
          to={`/boarding-bookings/${b.bookingId}`}
          aria-label={`Xem chi tiết lưu trú ${b.code}`}
          className="inline-flex min-h-10 items-center gap-1 rounded-xl px-3 text-[13px] font-bold text-accent transition-colors hover:bg-(--color-accent-soft)"
        >
          Xem chi tiết <ChevronRight size={15} aria-hidden />
        </Link>
      </footer>
    </article>
  );
}
