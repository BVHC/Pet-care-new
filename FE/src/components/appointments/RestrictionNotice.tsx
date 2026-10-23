/**
 * Khách đang bị hạn chế đặt online (BR-LH-09, appointment-v1 #7): hiện lý do và thời điểm hết hạn chế (giờ Việt Nam).
 * Lịch đã đặt trước đó giữ nguyên; trong thời gian này chỉ đặt được qua lễ tân.
 */
import { Ban } from 'lucide-react';
import { APPOINTMENT_CFG, type BookingRestriction } from '@/shared/types/appointment';
import { formatVnDateTime } from './format';

export function RestrictionNotice({ restriction }: { restriction: BookingRestriction }) {
  return (
    <section
      role="alert"
      aria-label="Hạn chế đặt lịch online"
      className="flex gap-4 rounded-2xl border border-amber-300 bg-amber-50 p-5 text-amber-950"
    >
      <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-700">
        <Ban className="h-5 w-5" aria-hidden />
      </span>
      <div className="space-y-1.5 text-sm leading-relaxed">
        <p className="text-[15px] font-bold">Bạn đang tạm ngừng đặt lịch online đến {formatVnDateTime(restriction.endsAt)}</p>
        <p>
          Lý do: có {restriction.violationCount} lần không đến hoặc hủy muộn (hủy khi còn dưới {APPOINTMENT_CFG.lateCancelHours} giờ trước giờ hẹn)
          trong {APPOINTMENT_CFG.violationWindowDays} ngày.
        </p>
        <p>Trong thời gian này, vui lòng gọi chi nhánh để lễ tân đặt lịch giúp bạn. Các lịch đã đặt trước đó vẫn được giữ nguyên.</p>
      </div>
    </section>
  );
}
