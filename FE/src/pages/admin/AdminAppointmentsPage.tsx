/**
 * Lịch hẹn của chi nhánh (UC39, UC40 phía lễ tân) — appointment-v1 #1–6, #8, #9.
 * Danh sách theo ngày (mặc định hôm nay) và trạng thái (5 trạng thái của 03 §3); đặt lịch hộ (kênh COUNTER);
 * đổi giờ / hủy hộ theo cờ `canReschedule` / `canCancel` (hủy hộ: cancelSource STAFF); xem và gỡ hạn chế đặt online.
 * Check-in không làm ở đây: thuộc tiếp nhận (POST /visits, T36).
 */
import { useState } from 'react';
import { Eye, Plus, RefreshCw, X } from 'lucide-react';
import { Checkbox, EmptyRow, FormError, selectClass } from '@/components/admin/form-ui';
import { BookOnBehalfDialog } from '@/components/appointments/BookOnBehalfDialog';
import { CancelAppointmentDialog, RescheduleDialog } from '@/components/appointments/ChangeAppointmentDialogs';
import { CustomerRestrictionPanel } from '@/components/appointments/CustomerRestrictionPanel';
import { formatShortDate, formatSlot, formatVnDateTime, slotEnd, STATUS_TONE, todayVn } from '@/components/appointments/format';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { InfoRow, StatusBadge } from '@/components/ui/modal-templates';
import { cn } from '@/lib/utils';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { useAppointment, useAppointmentList } from '@/shared/hooks/useAppointments';
import { useStaffBranchId } from '@/shared/hooks/useStaffBranch';
import { useSession } from '@/shared/stores/session.store';
import {
  APPOINTMENT_CFG,
  APPOINTMENT_STATUSES,
  APPOINTMENT_STATUS_LABELS,
  CANCEL_SOURCE_LABELS,
  CHANNEL_LABELS,
  type Appointment,
  type AppointmentStatus,
} from '@/shared/types/appointment';
import { QUOTA_GROUP_LABELS } from '@/shared/types/branch';

const PAGE_SIZE = 20;

export function AdminAppointmentsPage() {
  const account = useSession((s) => s.account);
  const branchId = useStaffBranchId();
  const isDesk = account?.role === 'RECEPTIONIST';
  const [date, setDate] = useState(todayVn());
  const [status, setStatus] = useState<AppointmentStatus | ''>('');
  const [checkInOnly, setCheckInOnly] = useState(false);
  const [page, setPage] = useState(0);
  const list = useAppointmentList({
    date: date || undefined,
    status: status || undefined,
    checkInReady: checkInOnly || undefined,
    page,
    size: PAGE_SIZE,
  });
  const [openId, setOpenId] = useState<number | null>(null);
  const [rescheduling, setRescheduling] = useState<Appointment | null>(null);
  const [cancelling, setCancelling] = useState<Appointment | null>(null);
  const [booking, setBooking] = useState(false);
  const data = list.data;
  const reset = <T,>(set: (v: T) => void) => (v: T) => {
    set(v);
    setPage(0);
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-(--text-primary)">Quản lý lịch hẹn</h1>
          <p className="text-(--text-secondary)">
            Lịch hẹn khám và thẩm mỹ của chi nhánh{data?.content[0] ? ` ${data.content[0].branchName}` : ''}. Check-in thực hiện ở quầy tiếp nhận.
          </p>
        </div>
        {isDesk && branchId != null && (
          <Button onClick={() => setBooking(true)}>
            <Plus className="h-4 w-4" aria-hidden /> Đặt lịch hộ
          </Button>
        )}
      </div>

      <Card className="card">
        <CardContent className="flex flex-wrap items-center gap-3 p-4">
          <Input aria-label="Ngày hẹn" type="date" className="w-44" value={date} onChange={(e) => reset(setDate)(e.target.value)} />
          <Button size="sm" variant="outline" onClick={() => reset(setDate)(todayVn())}>
            Hôm nay
          </Button>
          <Button size="sm" variant="ghost" onClick={() => reset(setDate)('')} disabled={!date}>
            Mọi ngày
          </Button>
          <select
            aria-label="Lọc trạng thái"
            className={cn(selectClass, 'w-44')}
            value={status}
            onChange={(e) => reset(setStatus)(e.target.value as AppointmentStatus | '')}
          >
            <option value="">Mọi trạng thái</option>
            {APPOINTMENT_STATUSES.map((s) => (
              <option key={s} value={s}>
                {APPOINTMENT_STATUS_LABELS[s]}
              </option>
            ))}
          </select>
          {/* A3: lịch BOOKED hôm nay trong cửa sổ check-in (BR-TN-02). */}
          <Checkbox label="Chỉ lịch đang trong giờ check-in" checked={checkInOnly} onChange={reset(setCheckInOnly)} />
        </CardContent>
      </Card>

      {list.isError && <FormError message={plainMessage(toApiError(list.error))} />}

      <Card className="card">
        <CardContent className="overflow-x-auto p-0">
          <table className="table" aria-label="Danh sách lịch hẹn">
            <thead>
              <tr>
                <th>Giờ hẹn</th>
                <th>Mã</th>
                <th>Khách</th>
                <th>Thú cưng</th>
                <th>Dịch vụ</th>
                <th>Kênh</th>
                <th>Trạng thái</th>
                <th className="text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {data?.content.length ? (
                data.content.map((a) => (
                  <tr key={a.appointmentId}>
                    <td className="whitespace-nowrap tabular-nums">
                      <span className="font-semibold text-(--text-primary)">
                        {a.slotStart}–{slotEnd(a.slotStart)}
                      </span>
                      {!date && <span className="block text-xs text-(--text-tertiary)">{formatShortDate(a.slotDate)}</span>}
                    </td>
                    <td className="font-mono text-xs">{a.code}</td>
                    <td className="font-medium text-(--text-primary)">{a.customerName}</td>
                    <td>{a.petName}</td>
                    <td>
                      {a.serviceName}
                      <span className="block text-xs text-(--text-tertiary)">{QUOTA_GROUP_LABELS[a.serviceGroup]}</span>
                    </td>
                    <td className="text-xs">{CHANNEL_LABELS[a.channel]}</td>
                    <td>
                      <StatusBadge status={APPOINTMENT_STATUS_LABELS[a.status]} variant={STATUS_TONE[a.status]} />
                    </td>
                    <td>
                      <div className="flex justify-end gap-1.5">
                        <Button size="sm" variant="outline" onClick={() => setOpenId(a.appointmentId)} aria-label={`Chi tiết ${a.code}`}>
                          <Eye className="h-4 w-4" aria-hidden />
                        </Button>
                        {a.canReschedule && (
                          <Button size="sm" variant="outline" onClick={() => setRescheduling(a)} aria-label={`Đổi giờ ${a.code}`}>
                            <RefreshCw className="h-4 w-4" aria-hidden /> Đổi giờ
                          </Button>
                        )}
                        {a.canCancel && (
                          <Button size="sm" variant="outline" className="border-red-200 text-red-600" onClick={() => setCancelling(a)} aria-label={`Hủy ${a.code}`}>
                            <X className="h-4 w-4" aria-hidden /> Hủy
                          </Button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))
              ) : (
                <EmptyRow colSpan={8} loading={list.isLoading} />
              )}
            </tbody>
          </table>
          {data && data.totalPages > 1 && (
            <div className="flex items-center justify-end gap-3 border-t border-(--border-color) px-4 py-3 text-sm text-(--text-secondary)">
              <span>
                Trang {data.page + 1}/{data.totalPages} · {data.totalElements} lịch
              </span>
              <Button size="sm" variant="outline" disabled={data.page === 0} onClick={() => setPage(data.page - 1)}>
                Trước
              </Button>
              <Button size="sm" variant="outline" disabled={data.page + 1 >= data.totalPages} onClick={() => setPage(data.page + 1)}>
                Sau
              </Button>
            </div>
          )}
        </CardContent>
      </Card>

      <AppointmentDetailDialog
        appointmentId={openId}
        onClose={() => setOpenId(null)}
        onReschedule={(a) => {
          setOpenId(null);
          setRescheduling(a);
        }}
        onCancel={(a) => {
          setOpenId(null);
          setCancelling(a);
        }}
      />
      <RescheduleDialog appointment={rescheduling} viewer="RECEPTIONIST" onClose={() => setRescheduling(null)} />
      <CancelAppointmentDialog appointment={cancelling} viewer="RECEPTIONIST" onClose={() => setCancelling(null)} />
      {branchId != null && <BookOnBehalfDialog open={booking} branchId={branchId} onClose={() => setBooking(false)} />}
    </div>
  );
}

function AppointmentDetailDialog({
  appointmentId,
  onClose,
  onReschedule,
  onCancel,
}: {
  appointmentId: number | null;
  onClose: () => void;
  onReschedule: (a: Appointment) => void;
  onCancel: (a: Appointment) => void;
}) {
  const detail = useAppointment(appointmentId);
  const a = detail.data;
  return (
    <Dialog open={appointmentId != null} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto bg-white sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{a ? `Lịch hẹn ${a.code}` : 'Lịch hẹn'}</DialogTitle>
          <DialogDescription>{a ? `${a.customerName} · ${a.petName}` : 'Đang tải…'}</DialogDescription>
        </DialogHeader>
        {detail.isError && <FormError message={plainMessage(toApiError(detail.error))} />}
        {a && (
          <div className="space-y-4">
            <div>
              <InfoRow label="Trạng thái" value={<StatusBadge status={APPOINTMENT_STATUS_LABELS[a.status]} variant={STATUS_TONE[a.status]} />} />
              <InfoRow label="Thời gian" value={formatSlot(a)} />
              <InfoRow label="Dịch vụ" value={`${a.serviceName} (${QUOTA_GROUP_LABELS[a.serviceGroup]})`} />
              <InfoRow label="Chi nhánh" value={a.branchName} />
              <InfoRow label="Kênh đặt" value={CHANNEL_LABELS[a.channel]} />
              <InfoRow label="Số lần đổi giờ" value={`${a.rescheduleCount}/${APPOINTMENT_CFG.maxReschedules}`} />
              {a.note && <InfoRow label="Ghi chú của khách" value={a.note} />}
              {a.status === 'CANCELLED' && (
                <>
                  <InfoRow label="Nguồn hủy" value={a.cancelSource ? CANCEL_SOURCE_LABELS[a.cancelSource] : '—'} />
                  {a.cancelledAt && <InfoRow label="Hủy lúc" value={formatVnDateTime(a.cancelledAt)} />}
                  {a.cancelReason && <InfoRow label="Lý do hủy" value={a.cancelReason} />}
                  <InfoRow label="Hủy muộn" value={a.lateCancel ? 'Có (tính vào hạn chế đặt online)' : 'Không'} />
                </>
              )}
            </div>
            <div>
              <p className="mb-1.5 text-sm font-medium">Đặt lịch online của khách</p>
              <CustomerRestrictionPanel customerId={a.customerId} customerName={a.customerName} />
            </div>
            {(a.canReschedule || a.canCancel) && (
              <div className="flex justify-end gap-2">
                {a.canReschedule && (
                  <Button variant="outline" onClick={() => onReschedule(a)}>
                    Đổi giờ
                  </Button>
                )}
                {a.canCancel && (
                  <Button variant="destructive" onClick={() => onCancel(a)}>
                    Hủy lịch
                  </Button>
                )}
              </div>
            )}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
