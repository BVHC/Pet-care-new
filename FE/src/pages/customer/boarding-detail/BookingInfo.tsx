// Khối thông tin lưu trú: ngày nhận / trả dự kiến, số đêm, giá đêm đã chốt, tạm tính, kênh đặt, chi nhánh.
import { useId, type ReactNode } from 'react';
import { Phone } from 'lucide-react';
import { formatSlotDate } from '@/components/appointments/format';
import { cn } from '@/lib/utils';
import { formatCurrency } from '@/lib/utils';
import type { PublicBranch } from '@/shared/types/branch';
import { BOOKING_CHANNEL_LABELS, type Booking } from '@/shared/types/boarding';
import { AMOUNT_NOTE, bookingAmount, bookingNights, showsAmount } from './model';
import { CARD, H2 } from './styles';

export type BranchContact = Pick<PublicBranch, 'name' | 'phone'> | undefined;

/** Số điện thoại bấm gọi được trên điện thoại. Trong câu văn là liên kết chữ; ở khối thông tin truyền `className="min-h-10"` cho đủ mục tiêu chạm 40 px. */
export function PhoneLink({ phone, className }: { phone: string; className?: string }) {
  return (
    <a href={`tel:${phone}`} className={cn('inline-flex items-center gap-1 font-bold text-accent underline-offset-4 hover:underline', className)}>
      <Phone size={13} aria-hidden /> {phone}
    </a>
  );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5 sm:flex-row sm:items-baseline sm:justify-between sm:gap-4">
      <dt className="shrink-0 text-[12.5px] text-(--color-text-secondary)">{label}</dt>
      <dd className="min-w-0 break-words text-[13.5px] font-semibold text-(--color-text-primary) sm:text-right">{children}</dd>
    </div>
  );
}

export function BookingInfo({ booking: b, branch, branchLoading }: { booking: Booking; branch: BranchContact; branchLoading: boolean }) {
  const titleId = useId();
  const nights = bookingNights(b);
  return (
    <section aria-labelledby={titleId} className={cn(CARD, 'p-5 sm:p-6')}>
      <h2 id={titleId} className={H2}>
        Thông tin lưu trú
      </h2>
      <dl className="mt-4 space-y-3">
        <Row label="Ngày nhận dự kiến">{formatSlotDate(b.checkInDate)}</Row>
        <Row label="Ngày trả dự kiến">{formatSlotDate(b.checkOutDate)}</Row>
        <Row label="Số đêm">{nights} đêm</Row>
        <Row label="Giá mỗi đêm">
          {formatCurrency(b.nightlyPrice)}
          <span className="block text-[11.5px] font-normal text-(--color-text-secondary)">Giá đã chốt lúc đặt chỗ</span>
        </Row>
        {showsAmount(b) && (
          <Row label={b.status === 'CHECKED_OUT' ? 'Tạm tính theo lịch đặt' : 'Tạm tính'}>{formatCurrency(bookingAmount(b))}</Row>
        )}
        <Row label="Kênh đặt">{BOOKING_CHANNEL_LABELS[b.channel]}</Row>
        <Row label="Chi nhánh">
          {branch ? branch.name : branchLoading ? 'Đang tải…' : `Chi nhánh số ${b.branchId}`}
          {branch?.phone && (
            <span className="mt-0.5 block text-[12.5px] font-normal">
              <PhoneLink phone={branch.phone} className="min-h-10" />
            </span>
          )}
        </Row>
      </dl>
      {showsAmount(b) && <p className="mt-4 rounded-xl bg-(--color-surface-2) px-3 py-2.5 text-[12.5px] leading-relaxed text-(--color-text-secondary)">{AMOUNT_NOTE}</p>}
    </section>
  );
}
