/**
 * Hạn chế đặt online của một khách, phía nhân viên (appointment-v1 #8, #9; BR-LH-09, ADR-0055):
 * lễ tân / quản lý chi nhánh xem và gỡ sớm, toàn chuỗi. Gỡ bắt buộc lý do ≤ 300 ký tự; khách không còn bị hạn chế → 409.
 * Hạn chế chỉ chặn đặt online — lễ tân vẫn đặt hộ được.
 */
import { useState } from 'react';
import { Ban } from 'lucide-react';
import { toast } from 'sonner';
import { Field, FormError } from '@/components/admin/form-ui';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { appointmentApi } from '@/shared/api/appointment.api';
import { useAppointmentMutation, useCustomerBookingRestriction } from '@/shared/hooks/useAppointments';
import { formatVnDateTime } from './format';

const REASON_MAX = 300;

export function CustomerRestrictionPanel({ customerId, customerName }: { customerId: number; customerName: string }) {
  const status = useCustomerBookingRestriction(customerId);
  const [lifting, setLifting] = useState(false);

  if (status.isPending) return <p className="text-sm text-(--text-secondary)">Đang kiểm tra hạn chế đặt online…</p>;
  if (status.isError) return <FormError message={plainMessage(toApiError(status.error))} />;
  const r = status.data.restricted ? status.data.restriction : null;
  if (!r) return <p className="text-sm text-(--text-secondary)">Khách không bị hạn chế đặt lịch online.</p>;

  return (
    <section aria-label="Hạn chế đặt online" className="space-y-2 rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm text-amber-950">
      <p className="flex items-center gap-2 font-semibold">
        <Ban className="h-4 w-4" aria-hidden /> Đang bị hạn chế đặt online đến {formatVnDateTime(r.endsAt)}
      </p>
      <p>
        Bắt đầu {formatVnDateTime(r.startsAt)} do {r.violationCount} lần không đến / hủy muộn. Lễ tân vẫn đặt hộ được.
      </p>
      <Button size="sm" variant="outline" onClick={() => setLifting(true)}>
        Gỡ hạn chế
      </Button>
      <LiftRestrictionDialog open={lifting} customerId={customerId} customerName={customerName} onClose={() => setLifting(false)} />
    </section>
  );
}

function LiftRestrictionDialog({
  open,
  customerId,
  customerName,
  onClose,
}: {
  open: boolean;
  customerId: number;
  customerName: string;
  onClose: () => void;
}) {
  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="bg-white sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Gỡ hạn chế đặt online</DialogTitle>
          <DialogDescription>
            {customerName} đặt lịch online lại được ngay. Thao tác được ghi nhật ký; khách không nhận thông báo.
          </DialogDescription>
        </DialogHeader>
        {open && <LiftForm customerId={customerId} onClose={onClose} />}
      </DialogContent>
    </Dialog>
  );
}

function LiftForm({ customerId, onClose }: { customerId: number; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const lift = useAppointmentMutation((text: string) => appointmentApi.liftRestriction(customerId, { reason: text }));
  const trimmed = reason.trim();

  const submit = () => {
    setError('');
    if (!trimmed) {
      setError('Vui lòng nhập lý do');
      return;
    }
    if (trimmed.length > REASON_MAX) {
      setError(`Lý do tối đa ${REASON_MAX} ký tự`);
      return;
    }
    lift.mutate(trimmed, {
      onSuccess: () => {
        toast.success('Đã gỡ hạn chế đặt online');
        onClose();
      },
      onError: (e) => {
        const apiError = toApiError(e);
        setError(apiError.statusCode === 409 ? 'Khách không còn bị hạn chế (đã hết hạn hoặc đã được gỡ).' : plainMessage(apiError));
      },
    });
  };

  return (
    <div className="space-y-4">
      <Field label="Lý do gỡ hạn chế" hint={`${trimmed.length}/${REASON_MAX} ký tự`}>
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
        <Button variant="outline" onClick={onClose} disabled={lift.isPending}>
          Đóng
        </Button>
        <Button onClick={submit} disabled={lift.isPending}>
          {lift.isPending ? 'Đang gỡ…' : 'Gỡ hạn chế'}
        </Button>
      </div>
    </div>
  );
}
