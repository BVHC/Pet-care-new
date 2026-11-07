// Lịch khám tại nhà của tôi (UC43; homevisit-v1 #2). Đổi giờ / hủy dùng lại appointment-v1 (#5, #6; BR-LH-14), nên nút theo cờ `canReschedule` / `canCancel` của lịch hẹn tương ứng,
// đọc bằng `useAppointment` chỉ cho lịch còn BOOKED. Không có lịch tại nhà nào thì không vẽ gì (đa số khách chỉ đặt khám tại chi nhánh).
import { useState } from 'react';
import { Calendar, Clock, Home, MapPin, Phone, RefreshCw, X } from 'lucide-react';
import { CancelAppointmentDialog, RescheduleDialog } from '@/components/appointments/ChangeAppointmentDialogs';
import { formatSlotDate, slotEnd } from '@/components/appointments/format';
import { cn } from '@/lib/utils';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { useAppointment } from '@/shared/hooks/useAppointments';
import type { Appointment } from '@/shared/types/appointment';
import { useInvalidateHomeVisits, useMyHomeVisits } from '@/shared/hooks/useHomeVisit';
import { HOME_VISIT_STATUS_LABELS, type HomeVisitAppointmentSummary, type HomeVisitStatus } from '@/shared/types/homevisit';

const vnd = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 });
const TONE: Record<HomeVisitStatus, string> = {
  BOOKED: 'bg-blue-100 text-blue-700',
  CHECKED_IN: 'bg-amber-100 text-amber-800',
  COMPLETED: 'bg-emerald-100 text-emerald-700',
  CANCELLED: 'bg-gray-100 text-gray-600',
  NO_SHOW: 'bg-red-100 text-red-700',
};
const BTN = 'inline-flex items-center gap-1.5 rounded-xl border px-4 py-2 text-[12.5px] font-bold transition-colors';

/** Giữ nguyên lịch hẹn lúc mở hộp thoại: danh sách làm mới giữa chừng (lịch vừa đổi trạng thái ở nơi khác) không được làm hộp thoại biến mất khi còn câu báo lỗi. */
type Acting = { kind: 'reschedule' | 'cancel'; appointment: Appointment } | null;

function HomeVisitCard({ visit: v }: { visit: HomeVisitAppointmentSummary }) {
  const booked = v.status === 'BOOKED';
  // Cờ đổi giờ / hủy nằm trên lịch hẹn của appointment-v1; chỉ hỏi cho lịch còn BOOKED.
  const detail = useAppointment(booked ? v.appointmentId : null);
  const refresh = useInvalidateHomeVisits();
  const [acting, setActing] = useState<Acting>(null);
  const close = () => {
    setActing(null);
    void refresh();
  };
  const a = detail.data;

  return (
    <div role="group" aria-label={`Lịch khám tại nhà ${v.code}`} className="overflow-hidden rounded-2xl border border-(--color-border-default) bg-white shadow-[0_1px_2px_rgba(56,36,23,0.06)]">
      <div className="flex items-center justify-between border-b border-(--color-border-default) bg-(--color-surface-2) px-5 py-3">
        <span className={cn('rounded-full px-2.5 py-0.5 text-[11px] font-bold', TONE[v.status])}>{HOME_VISIT_STATUS_LABELS[v.status]}</span>
        <span className="text-[12px] font-bold text-accent">{v.code}</span>
      </div>
      <div className="space-y-3 p-5">
        <div>
          <h3 className="text-[15px] font-bold text-(--color-text-primary)">{v.serviceName}</h3>
          <p className="mt-1 text-[12.5px] text-(--color-text-secondary)">{v.petName}</p>
        </div>
        <div className="flex flex-wrap gap-x-5 gap-y-1 text-[12.5px] text-(--color-text-secondary)">
          <span className="flex items-center gap-1">
            <Calendar size={13} aria-hidden /> {formatSlotDate(v.slotDate)}
          </span>
          <span className="flex items-center gap-1 font-bold text-(--color-text-primary)">
            <Clock size={13} aria-hidden /> {v.slotStart}–{slotEnd(v.slotStart)}
          </span>
          <span className="flex items-center gap-1">
            <MapPin size={13} aria-hidden /> {v.homeAddress}
          </span>
        </div>
        <p className="text-[12px] text-(--color-text-secondary)">
          Phí khám tại nhà {vnd.format(v.homeFee)}, chốt lúc đặt lịch và thu tại chi nhánh sau buổi khám.
        </p>
        {v.status === 'NO_SHOW' && (
          <p className="rounded-xl border border-red-200 bg-red-50 p-3 text-[12.5px] text-red-800">
            Bác sĩ đến nhưng không gặp được bạn: tính vào hạn chế đặt lịch online.
          </p>
        )}
        {booked && a && (
          <div className="flex flex-wrap items-center gap-2 pt-1">
            {a.canReschedule && (
              <button type="button" onClick={() => setActing({ kind: 'reschedule', appointment: a })} className={cn(BTN, 'border-(--color-border-default) text-(--color-text-secondary) hover:border-(--color-accent) hover:text-accent')}>
                <RefreshCw size={13} aria-hidden /> Đổi giờ
              </button>
            )}
            {a.canCancel && (
              <button type="button" onClick={() => setActing({ kind: 'cancel', appointment: a })} className={cn(BTN, 'border-red-200 text-red-600 hover:bg-red-50')}>
                <X size={13} aria-hidden /> Hủy lịch
              </button>
            )}
            {!a.canReschedule && (
              <p className="flex items-center gap-1 text-[12px] text-(--color-text-secondary)">
                <Phone size={12} aria-hidden /> Không đổi giờ online được nữa; cần đổi xin liên hệ chi nhánh.
              </p>
            )}
          </div>
        )}
      </div>
      <RescheduleDialog appointment={acting?.kind === 'reschedule' ? acting.appointment : null} viewer="CUSTOMER" onClose={close} />
      <CancelAppointmentDialog appointment={acting?.kind === 'cancel' ? acting.appointment : null} viewer="CUSTOMER" onClose={close} />
    </div>
  );
}

export function HomeVisitSection() {
  const list = useMyHomeVisits({ size: 50 });
  const rows = list.data?.content ?? [];
  if (list.isError) {
    return (
      <p role="alert" className="rounded-xl bg-red-50 px-4 py-3 text-sm text-red-700">
        {plainMessage(toApiError(list.error))}
      </p>
    );
  }
  if (rows.length === 0) return null;
  return (
    <section aria-labelledby="home-visits-title" className="space-y-4">
      <h2 id="home-visits-title" className="flex items-center gap-2 text-[17px] font-bold text-(--color-text-primary)">
        <Home size={18} aria-hidden /> Lịch khám tại nhà
      </h2>
      <div className="space-y-4">
        {rows.map((v) => (
          <HomeVisitCard key={v.appointmentId} visit={v} />
        ))}
      </div>
    </section>
  );
}
