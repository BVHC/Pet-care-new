// Chi tiết một đặt chỗ lưu trú (chỉ xem): mọi trường của Booking, kể cả nguồn / lý do hủy, lý do kết thúc và giá đêm đã chốt.
// Dùng Dialog (Radix) thay DetailModal vì Modal của modal-templates chưa gắn tên truy cập cho role="dialog".
import { formatShortDate, formatVnDateTime } from '@/components/appointments/format';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { InfoRow, StatusBadge } from '@/components/ui/modal-templates';
import { formatCurrency } from '@/lib/utils';
import {
  BOOKING_CANCEL_SOURCE_LABELS,
  BOOKING_CHANNEL_LABELS,
  BOOKING_STATUS_LABELS,
  END_REASON_LABELS,
  type Booking,
} from '@/shared/types/boarding';
import { BOOKING_STATUS_TONE, nightsBetween } from '@/shared/utils/boarding';

const NONE = '—';

export function BookingDetailDialog({ booking: b, onClose }: { booking: Booking; onClose: () => void }) {
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto bg-white sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{`Chi tiết lưu trú ${b.code}`}</DialogTitle>
          <DialogDescription>{`${b.petName} · ${b.customerName}`}</DialogDescription>
        </DialogHeader>
        <div>
          <InfoRow label="Mã" value={b.code} />
          <InfoRow label="Trạng thái" value={<StatusBadge status={BOOKING_STATUS_LABELS[b.status]} variant={BOOKING_STATUS_TONE[b.status]} />} />
          <InfoRow label="Thú cưng" value={b.petName} />
          <InfoRow label="Khách" value={b.customerName} />
          <InfoRow label="Loại chuồng" value={b.kennelTypeName} />
          <InfoRow label="Chuồng" value={b.kennelCode ?? NONE} />
          <InfoRow label="Ngày nhận" value={formatShortDate(b.checkInDate)} />
          <InfoRow label="Ngày trả" value={formatShortDate(b.checkOutDate)} />
          <InfoRow label="Số đêm" value={nightsBetween(b.checkInDate, b.checkOutDate)} />
          <InfoRow label="Giá đêm" value={formatCurrency(b.nightlyPrice)} />
          <InfoRow label="Kênh đặt" value={BOOKING_CHANNEL_LABELS[b.channel]} />
          <InfoRow label="Nhận thực tế" value={b.actualCheckInAt ? formatVnDateTime(b.actualCheckInAt) : NONE} />
          <InfoRow label="Trả thực tế" value={b.actualCheckOutAt ? formatVnDateTime(b.actualCheckOutAt) : NONE} />
          <InfoRow label="Kết thúc" value={b.endReason ? END_REASON_LABELS[b.endReason] : NONE} />
          <InfoRow label="Quá hạn từ" value={b.overdueSince ? formatVnDateTime(b.overdueSince) : NONE} />
          <InfoRow label="Hủy muộn" value={b.lateCancel ? 'Có' : 'Không'} />
          <InfoRow label="Nguồn hủy" value={b.cancelSource ? BOOKING_CANCEL_SOURCE_LABELS[b.cancelSource] : NONE} />
          <InfoRow label="Lý do hủy" value={<span className="break-words text-right">{b.cancelReason ?? NONE}</span>} />
          <InfoRow label="Order lưu trú" value={b.activeOrderId != null ? `#${b.activeOrderId}` : NONE} />
        </div>
        <div className="flex justify-end">
          <Button variant="outline" onClick={onClose}>
            Đóng
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
