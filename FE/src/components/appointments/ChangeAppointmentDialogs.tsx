/**
 * Đổi giờ (appointment-v1 #5, BR-LH-06) và hủy lịch hẹn (#6, BR-LH-07) — dùng chung cho khách và lễ tân.
 * Chỉ mở khi lịch có cờ `canReschedule` / `canCancel` theo người xem; BE vẫn kiểm lại mọi rule.
 */
import { useState } from 'react';
import { toast } from 'sonner';
import { Field, FormError } from '@/components/admin/form-ui';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { appointmentApi } from '@/shared/api/appointment.api';
import { useAppointmentMutation, useRefreshAppointments } from '@/shared/hooks/useAppointments';
import { APPOINTMENT_CFG, type Appointment } from '@/shared/types/appointment';
import { customerMaxDate, customerMinDate, formatSlot, isLateCancelNow, todayVn } from './format';
import { SlotPicker } from './SlotPicker';

/** Khách tự thao tác hay lễ tân thao tác hộ (khác nhau ở khoảng ngày và câu nhắc). */
export type Viewer = 'CUSTOMER' | 'RECEPTIONIST';

const CONTENT_CLASS = 'max-h-[90dvh] overflow-y-auto bg-white text-(--color-text-primary) sm:max-w-xl';

function Summary({ appointment }: { appointment: Appointment }) {
  return (
    <div className="rounded-xl border border-(--color-border-default) bg-(--color-surface-2) p-3 text-sm">
      <p className="font-bold">
        {appointment.serviceName} · {appointment.petName}
      </p>
      <p className="mt-0.5 text-(--color-text-secondary)">
        {formatSlot(appointment)} · {appointment.branchName}
      </p>
      <p className="mt-0.5 text-xs text-(--color-text-secondary)">Mã lịch hẹn {appointment.code}</p>
    </div>
  );
}

export function RescheduleDialog({ appointment, viewer, onClose }: { appointment: Appointment | null; viewer: Viewer; onClose: () => void }) {
  return (
    <Dialog open={appointment != null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className={CONTENT_CLASS}>
        <DialogHeader>
          <DialogTitle>Đổi giờ hẹn</DialogTitle>
          <DialogDescription>
            Chỉ đổi được ngày giờ trong cùng chi nhánh, cùng dịch vụ và thú cưng. Muốn đổi chi nhánh hoặc dịch vụ, hãy hủy lịch này rồi đặt lại.
          </DialogDescription>
        </DialogHeader>
        {appointment && <RescheduleForm key={appointment.appointmentId} appointment={appointment} viewer={viewer} onClose={onClose} />}
      </DialogContent>
    </Dialog>
  );
}

function RescheduleForm({ appointment, viewer, onClose }: { appointment: Appointment; viewer: Viewer; onClose: () => void }) {
  const [date, setDate] = useState<string | null>(appointment.slotDate);
  const [slotStart, setSlotStart] = useState<string | null>(null);
  const [error, setError] = useState('');
  const reschedule = useAppointmentMutation((body: { slotDate: string; slotStart: string }) =>
    appointmentApi.reschedule(appointment.appointmentId, body),
  );
  const refreshAppointments = useRefreshAppointments();
  const left = APPOINTMENT_CFG.maxReschedules - appointment.rescheduleCount;

  const submit = () => {
    setError('');
    if (!date || !slotStart) {
      setError('Vui lòng chọn ngày và khung giờ mới.');
      return;
    }
    reschedule.mutate(
      { slotDate: date, slotStart },
      {
        onSuccess: (updated) => {
          toast.success(`Đã đổi sang ${formatSlot(updated)}`);
          onClose();
        },
        onError: (e) => {
          const apiError = toApiError(e);
          refreshAppointments();
          setError(apiError.ruleId === 'BR-LH-03' ? 'Khung giờ vừa hết chỗ, vui lòng chọn khung khác.' : plainMessage(apiError));
        },
      },
    );
  };

  return (
    <div className="space-y-4">
      <Summary appointment={appointment} />
      <p className="text-sm text-(--color-text-secondary)">
        Còn {left} lần đổi (tối đa {APPOINTMENT_CFG.maxReschedules} lần cho mỗi lịch, tính cả lần lễ tân đổi hộ). Mã lịch hẹn giữ nguyên.
      </p>
      <SlotPicker
        branchId={appointment.branchId}
        serviceId={appointment.serviceId}
        date={date}
        onDateChange={setDate}
        value={slotStart}
        onChange={setSlotStart}
        minDate={viewer === 'CUSTOMER' ? customerMinDate() : todayVn()}
        maxDate={viewer === 'CUSTOMER' ? customerMaxDate() : undefined}
        current={appointment}
        hint={
          viewer === 'CUSTOMER'
            ? `Khung mới phải cách lúc này ít nhất ${APPOINTMENT_CFG.minLeadHours} giờ, trong vòng ${APPOINTMENT_CFG.maxAdvanceDays} ngày tới.`
            : 'Lễ tân đổi được sang mọi khung chưa bắt đầu.'
        }
      />
      <FormError message={error} />
      <div className="flex justify-end gap-2">
        <Button variant="outline" onClick={onClose} disabled={reschedule.isPending}>
          Đóng
        </Button>
        <Button onClick={submit} disabled={reschedule.isPending || !slotStart}>
          {reschedule.isPending ? 'Đang đổi…' : 'Xác nhận đổi giờ'}
        </Button>
      </div>
    </div>
  );
}

export function CancelAppointmentDialog({
  appointment,
  viewer,
  onClose,
}: {
  appointment: Appointment | null;
  viewer: Viewer;
  onClose: () => void;
}) {
  return (
    <Dialog open={appointment != null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className={CONTENT_CLASS}>
        <DialogHeader>
          <DialogTitle>Hủy lịch hẹn</DialogTitle>
          <DialogDescription>Lịch đã hủy không khôi phục được; muốn đến khám thì đặt lịch mới.</DialogDescription>
        </DialogHeader>
        {appointment && <CancelForm key={appointment.appointmentId} appointment={appointment} viewer={viewer} onClose={onClose} />}
      </DialogContent>
    </Dialog>
  );
}

const REASON_MAX = 300;

function CancelForm({ appointment, viewer, onClose }: { appointment: Appointment; viewer: Viewer; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const cancel = useAppointmentMutation((body: { reason?: string }) => appointmentApi.cancel(appointment.appointmentId, body));
  const late = isLateCancelNow(appointment);
  const { lateCancelHours, violationThreshold, violationWindowDays, restrictionDays } = APPOINTMENT_CFG;

  const submit = () => {
    setError('');
    if (reason.trim().length > REASON_MAX) {
      setError(`Lý do tối đa ${REASON_MAX} ký tự.`);
      return;
    }
    cancel.mutate(
      { reason: reason.trim() || undefined },
      {
        onSuccess: (updated) => {
          toast.success(updated.lateCancel ? 'Đã hủy lịch hẹn (ghi nhận hủy muộn)' : 'Đã hủy lịch hẹn');
          onClose();
        },
        onError: (e) => setError(plainMessage(toApiError(e))),
      },
    );
  };

  return (
    <div className="space-y-4">
      <Summary appointment={appointment} />
      {/* BR-LH-07, 09: hủy khi còn dưới 12 giờ là hủy muộn, cộng dồn với không đến để xét hạn chế đặt online. */}
      <div
        role={late ? 'alert' : 'note'}
        className={late ? 'rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900' : 'rounded-lg bg-(--color-surface-2) p-3 text-sm'}
      >
        {late && (
          <p className="font-bold">
            Còn dưới {lateCancelHours} giờ trước giờ hẹn: {viewer === 'CUSTOMER' ? 'hủy lúc này' : 'hủy hộ lúc này vẫn'} được tính là hủy muộn
            {viewer === 'RECEPTIONIST' && ' của khách'}.
          </p>
        )}
        <p>
          Hủy khi còn dưới {lateCancelHours} giờ là hủy muộn. Khách có {violationThreshold} lần hủy muộn hoặc không đến trong {violationWindowDays} ngày
          sẽ bị tạm ngừng đặt lịch online {restrictionDays} ngày (vẫn đặt được qua lễ tân).
        </p>
      </div>
      <Field label="Lý do hủy (không bắt buộc)" error={reason.length > REASON_MAX ? `Tối đa ${REASON_MAX} ký tự` : undefined}>
        {(id) => (
          <textarea
            id={id}
            rows={3}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            className="w-full rounded-lg border border-(--color-border-default) bg-white px-3 py-2 text-sm"
          />
        )}
      </Field>
      <FormError message={error} />
      <div className="flex justify-end gap-2">
        <Button variant="outline" onClick={onClose} disabled={cancel.isPending}>
          Giữ lịch
        </Button>
        <Button variant="destructive" onClick={submit} disabled={cancel.isPending}>
          {cancel.isPending ? 'Đang hủy…' : 'Xác nhận hủy'}
        </Button>
      </div>
    </div>
  );
}
