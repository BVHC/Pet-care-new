import { AlertTriangle } from 'lucide-react';
import { Link } from 'react-router-dom';
import { NoticeBar } from '@/components/admin/form-ui';
import { formatShortDate } from '@/components/appointments/format';
import { Button } from '@/components/ui/button';
import type { AffectedBookingBrief } from '@/shared/types/boarding';

export interface MaintenanceOutcome {
  kennelId: number;
  code: string;
  shortageBookings: AffectedBookingBrief[];
}

/**
 * Kết quả chuyển bảo trì (BR-LT-01): thiếu chỗ chỉ CẢNH BÁO, không đặt chỗ nào bị hủy. Giữ trên trang tới khi quản lý đóng
 * hoặc chuồng hết bảo trì, để còn dữ liệu mà liên hệ khách (toast tự tắt nên không đủ).
 */
export function MaintenanceNotice({ outcome, onDismiss }: { outcome: MaintenanceOutcome; onDismiss: () => void }) {
  const { code, shortageBookings: bookings } = outcome;
  if (!bookings.length) {
    return <NoticeBar notice={{ kind: 'success', text: `Đã chuyển chuồng ${code} sang bảo trì. Không có đặt chỗ nào bị thiếu chỗ.` }} />;
  }
  return (
    <section aria-label="Cảnh báo thiếu chỗ" className="space-y-3 rounded-xl border border-amber-300 bg-amber-50 p-4">
      <div className="flex items-start gap-3">
        <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-amber-600" aria-hidden />
        <div className="space-y-1">
          <h2 className="font-semibold text-amber-900">
            Chuồng {code} đã chuyển sang bảo trì: {bookings.length} đặt chỗ sắp tới bị thiếu chỗ
          </h2>
          <p className="text-sm text-amber-900">
            Hệ thống chỉ cảnh báo, không tự hủy đặt chỗ nào (BR-LT-01). Liên hệ khách để đổi ngày, hoặc hết bảo trì chuồng để khôi phục sức chứa.{' '}
            <Link to="/admin/boarding" className="font-medium underline">
              Xem màn Lưu trú
            </Link>
          </p>
        </div>
      </div>
      <ul className="space-y-1.5 text-sm text-amber-950">
        {bookings.map((b) => (
          <li key={b.bookingId} className="break-words rounded-lg bg-white/70 px-3 py-2">
            <span className="font-mono text-xs">{b.code}</span> · <span className="font-medium">{b.petName}</span> · nhận {formatShortDate(b.checkInDate)} → trả{' '}
            {formatShortDate(b.checkOutDate)}
          </li>
        ))}
      </ul>
      <div className="flex justify-end">
        <Button size="sm" variant="outline" className="h-10" onClick={onDismiss}>
          Đã hiểu
        </Button>
      </div>
    </section>
  );
}
