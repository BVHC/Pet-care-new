/**
 * Lịch hẹn của tôi (UC40, A02) — appointment-v1 #3, #5, #6, #7.
 * "Sắp tới" là lịch BOOKED; "Đã qua / đã hủy" là các trạng thái còn lại. Nút đổi giờ / hủy chỉ hiện theo cờ
 * `canReschedule` / `canCancel` của BE (BR-LH-06, 07). Không chặn khi hồ sơ còn chờ liên kết (ADR-0055).
 */
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Calendar, CalendarClock, Clock, MapPin, PawPrint, Phone, RefreshCw, X } from 'lucide-react';
import { AccountSidebar } from '@/components/customer/AccountSidebar';
import { CancelAppointmentDialog, RescheduleDialog } from '@/components/appointments/ChangeAppointmentDialogs';
import { RestrictionNotice } from '@/components/appointments/RestrictionNotice';
import { formatSlotDate, formatVnDateTime, slotEnd, STATUS_TONE } from '@/components/appointments/format';
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero';
import { cn } from '@/lib/utils';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { useAppointmentList, useMyBookingRestriction, usePublicBranches } from '@/shared/hooks/useAppointments';
import {
  APPOINTMENT_CFG,
  APPOINTMENT_STATUS_LABELS,
  CANCEL_SOURCE_LABELS,
  CHANNEL_LABELS,
  type Appointment,
} from '@/shared/types/appointment';

type Tab = 'upcoming' | 'past';

const TONE_CLASS = {
  info: 'bg-blue-100 text-blue-700',
  warning: 'bg-amber-100 text-amber-800',
  success: 'bg-emerald-100 text-emerald-700',
  neutral: 'bg-gray-100 text-gray-600',
  danger: 'bg-red-100 text-red-700',
} as const;

/** Lý do không đổi giờ online được nữa (BR-LH-06), để hướng dẫn khách liên hệ chi nhánh. */
function rescheduleBlocker(a: Appointment) {
  if (a.rescheduleCount >= APPOINTMENT_CFG.maxReschedules) return `lịch đã đổi đủ ${APPOINTMENT_CFG.maxReschedules} lần`;
  return `còn dưới ${APPOINTMENT_CFG.rescheduleMinLeadHours} giờ trước giờ hẹn`;
}

function AppointmentCard({
  appointment: a,
  branchPhone,
  onReschedule,
  onCancel,
}: {
  appointment: Appointment;
  branchPhone?: string;
  onReschedule: () => void;
  onCancel: () => void;
}) {
  return (
    <article
      aria-label={`Lịch hẹn ${a.code}`}
      className="overflow-hidden rounded-2xl border border-(--color-border-default) bg-white shadow-[0_1px_2px_rgba(56,36,23,0.06),0_4px_12px_-8px_rgba(56,36,23,0.4)]"
    >
      <header className="flex items-center justify-between border-b border-(--color-border-default) bg-(--color-surface-2) px-5 py-3">
        <span className={cn('rounded-full px-2.5 py-0.5 text-[11px] font-bold', TONE_CLASS[STATUS_TONE[a.status]])}>
          {APPOINTMENT_STATUS_LABELS[a.status]}
        </span>
        <span className="text-[12px] font-bold text-accent">{a.code}</span>
      </header>

      <div className="space-y-3 p-5">
        <div>
          <h3 className="text-[15px] font-bold text-(--color-text-primary)">{a.serviceName}</h3>
          <p className="mt-1 flex items-center gap-1.5 text-[12.5px] text-(--color-text-secondary)">
            <PawPrint size={13} aria-hidden /> {a.petName}
          </p>
        </div>
        <div className="flex flex-wrap gap-x-5 gap-y-1 text-[12.5px] text-(--color-text-secondary)">
          <span className="flex items-center gap-1">
            <Calendar size={13} aria-hidden /> {formatSlotDate(a.slotDate)}
          </span>
          <span className="flex items-center gap-1 font-bold text-(--color-text-primary)">
            <Clock size={13} aria-hidden /> {a.slotStart}–{slotEnd(a.slotStart)}
          </span>
          <span className="flex items-center gap-1">
            <MapPin size={13} aria-hidden /> {a.branchName}
          </span>
          {branchPhone && (
            <span className="flex items-center gap-1">
              <Phone size={13} aria-hidden /> {branchPhone}
            </span>
          )}
        </div>
        <p className="text-[12px] text-(--color-text-secondary)">
          {CHANNEL_LABELS[a.channel]}
          {a.rescheduleCount > 0 && ` · đã đổi giờ ${a.rescheduleCount}/${APPOINTMENT_CFG.maxReschedules} lần`}
        </p>

        {a.note && (
          <p className="rounded-xl bg-(--color-surface-2) p-3 text-[12.5px]">
            <strong>Ghi chú:</strong> {a.note}
          </p>
        )}

        {a.status === 'CANCELLED' && (
          <div className="rounded-xl border border-(--color-border-default) p-3 text-[12.5px] text-(--color-text-secondary)">
            <p>
              <strong className="text-(--color-text-primary)">{a.cancelSource ? CANCEL_SOURCE_LABELS[a.cancelSource] : 'Đã hủy'}</strong>
              {a.cancelledAt && ` lúc ${formatVnDateTime(a.cancelledAt)}`}
              {a.cancelReason && ` — ${a.cancelReason}`}
            </p>
            {a.lateCancel && (
              <p className="mt-1 font-semibold text-amber-800">Hủy muộn (dưới {APPOINTMENT_CFG.lateCancelHours} giờ trước giờ hẹn): tính vào hạn chế đặt lịch online.</p>
            )}
          </div>
        )}
        {a.status === 'NO_SHOW' && (
          <p className="rounded-xl border border-red-200 bg-red-50 p-3 text-[12.5px] text-red-800">
            Không đến và chưa tiếp nhận sau giờ hẹn: tính vào hạn chế đặt lịch online.
          </p>
        )}

        {a.status === 'BOOKED' && (
          <div className="flex flex-wrap items-center gap-2 pt-1">
            {a.canReschedule && (
              <button
                type="button"
                onClick={onReschedule}
                className="inline-flex items-center gap-1.5 rounded-xl border border-(--color-border-default) px-4 py-2 text-[12.5px] font-bold text-(--color-text-secondary) transition-colors hover:border-(--color-accent) hover:text-accent"
              >
                <RefreshCw size={13} aria-hidden /> Đổi giờ
              </button>
            )}
            {a.canCancel && (
              <button
                type="button"
                onClick={onCancel}
                className="inline-flex items-center gap-1.5 rounded-xl border border-red-200 px-4 py-2 text-[12.5px] font-bold text-red-600 transition-colors hover:bg-red-50"
              >
                <X size={13} aria-hidden /> Hủy lịch
              </button>
            )}
            {!a.canReschedule && (
              <p className="text-[12px] text-(--color-text-secondary)">
                Không đổi giờ online được nữa ({rescheduleBlocker(a)}); cần đổi xin liên hệ chi nhánh.
              </p>
            )}
          </div>
        )}
      </div>
    </article>
  );
}

export function AppointmentsPage() {
  // Khách thường có ít lịch: lấy một trang tối đa (100) rồi chia tab ở client.
  const list = useAppointmentList({ size: 100 });
  const restriction = useMyBookingRestriction();
  const branches = usePublicBranches();
  const [tab, setTab] = useState<Tab>('upcoming');
  const [rescheduling, setRescheduling] = useState<Appointment | null>(null);
  const [cancelling, setCancelling] = useState<Appointment | null>(null);

  const all = list.data?.content ?? [];
  const upcoming = all.filter((a) => a.status === 'BOOKED');
  // Đã qua: mới nhất lên đầu (API trả tăng dần theo giờ hẹn).
  const past = all.filter((a) => a.status !== 'BOOKED').reverse();
  const shown = tab === 'upcoming' ? upcoming : past;
  const phoneOf = (branchId: number) => branches.data?.find((b) => b.branchId === branchId)?.phone;
  const blocked = restriction.data?.restricted ? restriction.data.restriction : null;

  const tabs: { id: Tab; label: string; count: number }[] = [
    { id: 'upcoming', label: 'Sắp tới', count: upcoming.length },
    { id: 'past', label: 'Đã qua / đã hủy', count: past.length },
  ];

  return (
    <div className="bg-(--color-surface-page) pb-24">
      <CommonPageHero
        eyebrow="Lịch hẹn của tôi"
        title="Lịch hẹn"
        subtitle="Xem lịch đã đặt, đổi giờ hoặc hủy lịch hẹn của thú cưng."
        breadcrumbs={[{ label: 'Lịch hẹn' }]}
      />

      <div className="mx-auto max-w-[1280px] px-5 pt-8 sm:px-8">
        <div className="grid gap-8 lg:grid-cols-[260px_1fr] xl:grid-cols-[280px_1fr]">
          <AccountSidebar active="appointments" />

          <div className="min-w-0 space-y-6">
            {blocked && <RestrictionNotice restriction={blocked} />}

            <div className="flex flex-wrap items-center justify-between gap-3">
              <div
                role="tablist"
                aria-label="Nhóm lịch hẹn"
                className="flex items-center gap-1 overflow-x-auto rounded-2xl border border-(--color-border-default) bg-white p-1.5 shadow-[0_1px_2px_rgba(56,36,23,0.06)]"
              >
                {tabs.map((t) => {
                  const active = tab === t.id;
                  return (
                    <button
                      key={t.id}
                      type="button"
                      role="tab"
                      aria-selected={active}
                      onClick={() => setTab(t.id)}
                      className={cn(
                        'flex shrink-0 items-center gap-2 rounded-xl px-4 py-2.5 text-[13px] font-bold transition-all',
                        active
                          ? 'bg-accent text-white shadow-[0_4px_12px_-6px_rgba(164,51,36,0.7)]'
                          : 'text-(--color-text-secondary) hover:bg-(--color-surface-2) hover:text-(--color-text-primary)',
                      )}
                    >
                      {t.label}
                      <span className={cn('rounded-full px-2 py-0.5 text-[10.5px] font-bold', active ? 'bg-white/20 text-white' : 'bg-(--color-surface-2)')}>
                        {t.count}
                      </span>
                    </button>
                  );
                })}
              </div>
              {!blocked && (
                <Link
                  to="/booking"
                  className="inline-flex items-center gap-1.5 rounded-full bg-accent px-5 py-2.5 text-[13px] font-bold text-white transition-colors hover:bg-(--color-accent-hover)"
                >
                  <Calendar size={14} aria-hidden /> Đặt lịch mới
                </Link>
              )}
            </div>

            {list.isError && (
              <p role="alert" className="rounded-xl bg-red-50 px-4 py-3 text-sm text-red-700">
                {plainMessage(toApiError(list.error))}
              </p>
            )}
            {list.isPending && <p className="text-sm text-(--color-text-secondary)">Đang tải lịch hẹn…</p>}

            {list.isSuccess && shown.length === 0 && (
              <div className="rounded-3xl border border-dashed border-(--color-border-default) bg-white p-16 text-center">
                <CalendarClock size={48} className="mx-auto mb-4 text-(--color-text-secondary) opacity-30" aria-hidden />
                <p className="text-[15px] font-bold text-(--color-text-secondary)">
                  {tab === 'upcoming' ? 'Chưa có lịch hẹn sắp tới' : 'Chưa có lịch hẹn đã qua'}
                </p>
              </div>
            )}

            {shown.length > 0 && (
              <div className="space-y-4">
                {shown.map((a) => (
                  <AppointmentCard
                    key={a.appointmentId}
                    appointment={a}
                    branchPhone={phoneOf(a.branchId)}
                    onReschedule={() => setRescheduling(a)}
                    onCancel={() => setCancelling(a)}
                  />
                ))}
              </div>
            )}
          </div>
        </div>
      </div>

      <RescheduleDialog appointment={rescheduling} viewer="CUSTOMER" onClose={() => setRescheduling(null)} />
      <CancelAppointmentDialog appointment={cancelling} viewer="CUSTOMER" onClose={() => setCancelling(null)} />
    </div>
  );
}
