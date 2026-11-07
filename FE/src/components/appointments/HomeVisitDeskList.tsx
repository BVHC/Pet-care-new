// Lịch khám tại nhà của chi nhánh cho lễ tân (UC43; homevisit-v1 #3). Lịch tại nhà không nằm trong GET /appointments nên cần bảng riêng để lễ tân thấy lịch vừa đặt hộ.
// Đổi giờ / hủy hộ dùng lại appointment-v1 (BR-LH-14): nút theo cờ `canReschedule` / `canCancel` của lịch hẹn tương ứng (đọc bằng `useAppointment`, chỉ cho lịch BOOKED).
// Bắt đầu khám và ghi "không gặp được khách" là việc của bác sĩ nên không có nút ở đây.
import { useState } from 'react';
import { RefreshCw, X } from 'lucide-react';
import { EmptyRow, FormError } from '@/components/admin/form-ui';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { StatusBadge } from '@/components/ui/modal-templates';
import { formatCurrency } from '@/lib/utils';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { useAppointment } from '@/shared/hooks/useAppointments';
import { useHomeVisits, useInvalidateHomeVisits } from '@/shared/hooks/useHomeVisit';
import type { Appointment } from '@/shared/types/appointment';
import { HOME_VISIT_STATUS_LABELS, type HomeVisitAppointmentSummary, type HomeVisitStatus } from '@/shared/types/homevisit';
import { CancelAppointmentDialog, RescheduleDialog } from './ChangeAppointmentDialogs';
import { formatShortDate, slotEnd, todayVn } from './format';

const TONE: Record<HomeVisitStatus, 'info' | 'warning' | 'success' | 'neutral' | 'danger'> = {
  BOOKED: 'info',
  CHECKED_IN: 'warning',
  COMPLETED: 'success',
  CANCELLED: 'neutral',
  NO_SHOW: 'danger',
};

/** Giữ nguyên lịch hẹn lúc mở hộp thoại: danh sách làm mới giữa chừng không được làm hộp biến mất khi còn câu báo lỗi. */
type Acting = { kind: 'reschedule' | 'cancel'; appointment: Appointment } | null;

function Row({ visit: v }: { visit: HomeVisitAppointmentSummary }) {
  const booked = v.status === 'BOOKED';
  const detail = useAppointment(booked ? v.appointmentId : null);
  const refresh = useInvalidateHomeVisits();
  const [acting, setActing] = useState<Acting>(null);
  const a = detail.data;
  const close = () => {
    setActing(null);
    void refresh();
  };
  return (
    <tr>
      <td className="whitespace-nowrap tabular-nums font-semibold text-(--text-primary)">
        {v.slotStart}–{slotEnd(v.slotStart)}
        <span className="block text-xs font-normal text-(--text-tertiary)">{formatShortDate(v.slotDate)}</span>
      </td>
      <td className="font-mono text-xs">{v.code}</td>
      <td>{v.petName}</td>
      <td>{v.serviceName}</td>
      <td className="max-w-64 text-sm">{v.homeAddress}</td>
      <td className="whitespace-nowrap tabular-nums">{formatCurrency(v.homeFee)}</td>
      <td>
        <StatusBadge status={HOME_VISIT_STATUS_LABELS[v.status]} variant={TONE[v.status]} />
      </td>
      <td>
        <div className="flex justify-end gap-1.5">
          {a?.canReschedule && (
            <Button size="sm" variant="outline" aria-label={`Đổi giờ ${v.code}`} onClick={() => setActing({ kind: 'reschedule', appointment: a })}>
              <RefreshCw className="h-4 w-4" aria-hidden /> Đổi giờ
            </Button>
          )}
          {a?.canCancel && (
            <Button size="sm" variant="outline" className="border-red-200 text-red-600" aria-label={`Hủy ${v.code}`} onClick={() => setActing({ kind: 'cancel', appointment: a })}>
              <X className="h-4 w-4" aria-hidden /> Hủy
            </Button>
          )}
        </div>
        <RescheduleDialog appointment={acting?.kind === 'reschedule' ? acting.appointment : null} viewer="RECEPTIONIST" onClose={close} />
        <CancelAppointmentDialog appointment={acting?.kind === 'cancel' ? acting.appointment : null} viewer="RECEPTIONIST" onClose={close} />
      </td>
    </tr>
  );
}

export function HomeVisitDeskList({ date }: { date: string }) {
  const day = date || todayVn();
  const list = useHomeVisits({ date: day, size: 50 });
  const rows = list.data?.content ?? [];
  return (
    <section aria-labelledby="home-visit-desk-title" className="space-y-3">
      <h2 id="home-visit-desk-title" className="text-lg font-semibold text-(--text-primary)">
        Lịch khám tại nhà ngày {formatShortDate(day)}
      </h2>
      {list.isError && <FormError message={plainMessage(toApiError(list.error))} />}
      <Card className="card">
        <CardContent className="overflow-x-auto p-0">
          <table className="table" aria-label="Danh sách lịch khám tại nhà">
            <thead>
              <tr>
                <th>Giờ hẹn</th>
                <th>Mã</th>
                <th>Thú cưng</th>
                <th>Dịch vụ</th>
                <th>Địa chỉ</th>
                <th>Phí</th>
                <th>Trạng thái</th>
                <th className="text-right!">Thao tác</th>
              </tr>
            </thead>
            <tbody>{rows.length ? rows.map((v) => <Row key={v.appointmentId} visit={v} />) : <EmptyRow colSpan={8} loading={list.isLoading} />}</tbody>
          </table>
        </CardContent>
      </Card>
    </section>
  );
}
