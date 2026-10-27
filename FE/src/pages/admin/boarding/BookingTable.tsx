// Bảng đặt chỗ lưu trú của chi nhánh (UC58, UC59): một hàng một đặt chỗ, nút theo vai trò × trạng thái (rules.ts).
// Bảng cuộn ngang trong khung ở màn hẹp; hàng thao tác tự xuống dòng chứ không làm tràn trang.
import { Eye } from 'lucide-react';
import { formatShortDate, formatVnDateTime } from '@/components/appointments/format';
import { Button } from '@/components/ui/button';
import { StatusBadge } from '@/components/ui/modal-templates';
import { cn } from '@/lib/utils';
import type { Role } from '@/shared/types/auth';
import { BOOKING_CHANNEL_LABELS, BOOKING_STATUS_LABELS, type Booking } from '@/shared/types/boarding';
import { BOOKING_STATUS_TONE, nightsBetween } from '@/shared/utils/boarding';
import { ACTION_LABELS, actionsOf, type BookingAction } from './rules';

export type RowAction = BookingAction | 'detail';

const DANGER_ACTIONS: readonly BookingAction[] = ['noKennel', 'cancel', 'deceased'];

/** Chip phụ: không dùng màu đơn lẻ, luôn kèm chữ. */
function Chip({ tone, children }: { tone: 'warning' | 'danger' | 'neutral'; children: string }) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium',
        tone === 'warning' && 'bg-amber-100 text-amber-800',
        tone === 'danger' && 'bg-red-100 text-red-700',
        tone === 'neutral' && 'bg-gray-100 text-gray-700',
      )}
    >
      {children}
    </span>
  );
}

export function BookingTable({
  bookings,
  role,
  today,
  stale,
  onAction,
}: {
  bookings: Booking[];
  role: Role | undefined;
  today: string;
  /** Đang tải bộ lọc mới: còn hiện dữ liệu cũ nhưng mờ đi. */
  stale: boolean;
  onAction: (action: RowAction, booking: Booking) => void;
}) {
  return (
    <div className="overflow-x-auto" aria-busy={stale}>
      <table className={cn('table min-w-[880px]', stale && 'opacity-60')} aria-label="Danh sách đặt chỗ lưu trú">
        <thead>
          <tr>
            <th>Mã</th>
            <th>Thú cưng / Khách</th>
            <th>Loại chuồng / Chuồng</th>
            <th>Nhận → trả</th>
            <th>Trạng thái</th>
            <th>Kênh đặt</th>
            <th className="text-right">Thao tác</th>
          </tr>
        </thead>
        <tbody>
          {bookings.map((b) => (
            <tr key={b.bookingId}>
              <td>
                <span className="whitespace-nowrap font-mono text-xs">{b.code}</span>
              </td>
              <td>
                <p className="font-medium text-(--text-primary)">{b.petName}</p>
                <p className="text-xs text-(--text-tertiary)">{b.customerName}</p>
              </td>
              <td>
                <p>{b.kennelTypeName}</p>
                <p className="text-xs text-(--text-tertiary)">{b.kennelCode ? `Chuồng ${b.kennelCode}` : 'Chưa gán chuồng'}</p>
              </td>
              <td className="whitespace-nowrap tabular-nums">
                <p>
                  {formatShortDate(b.checkInDate)} → {formatShortDate(b.checkOutDate)}
                </p>
                <p className="text-xs text-(--text-tertiary)">{nightsBetween(b.checkInDate, b.checkOutDate)} đêm</p>
              </td>
              <td>
                <div className="flex flex-wrap items-center gap-1.5">
                  <span className="whitespace-nowrap">
                    <StatusBadge status={BOOKING_STATUS_LABELS[b.status]} variant={BOOKING_STATUS_TONE[b.status]} />
                  </span>
                  {b.missingCareLogToday && <Chip tone="warning">Chưa có nhật ký hôm nay</Chip>}
                  {b.status === 'OVERDUE' && b.overdueSince && <Chip tone="danger">{`Quá hạn từ ${formatVnDateTime(b.overdueSince)}`}</Chip>}
                  {b.lateCancel && <Chip tone="danger">Hủy muộn</Chip>}
                </div>
              </td>
              <td className="text-xs">{BOOKING_CHANNEL_LABELS[b.channel]}</td>
              <td>
                <div className="flex min-w-56 flex-wrap justify-end gap-1.5">
                  {actionsOf(role, b, today).map((action) => (
                    <Button
                      key={action}
                      size="sm"
                      variant="outline"
                      className={cn('min-h-10', DANGER_ACTIONS.includes(action) && 'border-red-200 text-red-600')}
                      aria-label={`${ACTION_LABELS[action]} ${b.code}`}
                      onClick={() => onAction(action, b)}
                    >
                      {ACTION_LABELS[action]}
                    </Button>
                  ))}
                  <Button size="sm" variant="outline" className="min-h-10 min-w-10" aria-label={`Chi tiết ${b.code}`} onClick={() => onAction('detail', b)}>
                    <Eye className="h-4 w-4" aria-hidden />
                  </Button>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
