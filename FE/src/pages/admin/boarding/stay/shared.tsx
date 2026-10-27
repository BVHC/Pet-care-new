// Phần giao diện dùng chung của bốn hộp thoại nhận / trả thú (T48 UC59).
import type { ReactNode } from 'react';
import { FormError } from '@/components/admin/form-ui';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { formatShortDate } from '@/components/appointments/format';
import { cn } from '@/lib/utils';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import type { Booking } from '@/shared/types/boarding';
import { BOOKING_STATUS_LABELS } from '@/shared/types/boarding';

/** Tên truy cập của `role="dialog"` là `title` (Radix gắn aria-labelledby vào DialogTitle): AdminBoardingPage và test dựa vào đó. */
export function StayModal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto bg-white text-(--text-primary) sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        {children}
      </DialogContent>
    </Dialog>
  );
}

export function StayFooter({ children }: { children: ReactNode }) {
  return <DialogFooter className="items-center">{children}</DialogFooter>;
}

/** Vai trò không có việc này: không gọi API nào, chỉ báo và cho đóng. */
export function StayForbidden({ who, onClose }: { who: string; onClose: () => void }) {
  return (
    <>
      <p className="rounded-lg bg-(--bg-secondary) px-3 py-2 text-sm text-(--text-secondary)">{who}</p>
      <StayFooter>
        <Button variant="outline" onClick={onClose}>
          Đóng
        </Button>
      </StayFooter>
    </>
  );
}

export function StayLoading({ what }: { what: string }) {
  return (
    <p role="status" className="animate-pulse rounded-lg bg-(--bg-secondary) px-3 py-6 text-center text-sm text-(--text-secondary)">
      {what}
    </p>
  );
}

/** Lỗi tải: câu của BE + Thử lại + Đóng. */
export function StayError({ error, onRetry, onClose }: { error: unknown; onRetry: () => void; onClose: () => void }) {
  return (
    <>
      <FormError message={plainMessage(toApiError(error))} />
      <StayFooter>
        <Button variant="outline" onClick={onClose}>
          Đóng
        </Button>
        <Button onClick={onRetry}>Thử lại</Button>
      </StayFooter>
    </>
  );
}

const TONES = {
  warning: 'border-amber-200 bg-amber-50 text-amber-900',
  info: 'border-blue-200 bg-blue-50 text-blue-900',
  danger: 'border-red-200 bg-red-50 text-red-800',
  success: 'border-green-200 bg-green-50 text-green-800',
} as const;

export function Callout({ tone, title, children }: { tone: keyof typeof TONES; title?: string; children: ReactNode }) {
  return (
    <div className={cn('rounded-lg border px-3 py-2.5 text-sm', TONES[tone])}>
      {title && <p className="font-semibold">{title}</p>}
      <div className={cn(title && 'mt-0.5')}>{children}</div>
    </div>
  );
}

/** Thú / khách / chuồng / ngày của đặt chỗ, để lễ tân không nhầm khi hộp thoại mở ra. */
export function BookingSummary({ booking }: { booking: Booking }) {
  return (
    <div className="rounded-xl border border-(--color-border-default) bg-(--color-surface-2) p-3 text-sm">
      <p className="font-bold break-words">
        {booking.petName} · {booking.customerName}
      </p>
      <p className="mt-0.5 text-(--text-secondary)">
        {booking.kennelTypeName}
        {booking.kennelCode ? ` · chuồng ${booking.kennelCode}` : ''} · {formatShortDate(booking.checkInDate)} → {formatShortDate(booking.checkOutDate)}
      </p>
      <p className="mt-0.5 text-xs text-(--text-secondary) break-all">
        Mã {booking.code} · {BOOKING_STATUS_LABELS[booking.status]}
      </p>
    </div>
  );
}
