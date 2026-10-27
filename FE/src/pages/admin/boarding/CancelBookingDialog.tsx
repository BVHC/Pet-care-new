// Lễ tân hủy đặt chỗ BOOKED hộ khách (boarding-v1 #12, cancelSource STAFF). Lý do tùy chọn ≤ 300 ký tự. Hủy muộn (BR-LT-06) vẫn hủy được
// nhưng tính vào hạn chế đặt online của khách (BR-LH-09), nên cảnh báo trước. 409 (người khác vừa thao tác): hiện câu BE và làm mới danh sách.
import { useState } from 'react';
import { toast } from 'sonner';
import { Field, FormError } from '@/components/admin/form-ui';
import { formatShortDate } from '@/components/appointments/format';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { boardingApi } from '@/shared/api/boarding.api';
import { useBoardingMutation, useRefreshBoarding } from '@/shared/hooks/useBoarding';
import { BOARDING_CFG, type Booking } from '@/shared/types/boarding';
import { isLateCancel } from '@/shared/utils/boarding';

const REASON_MAX = 300;

export function CancelBookingDialog({ booking, onClose }: { booking: Booking; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const cancel = useBoardingMutation((body: { reason?: string }) => boardingApi.cancel(booking.bookingId, body));
  const refresh = useRefreshBoarding();
  const late = isLateCancel(booking.checkInDate);

  const submit = () => {
    setError('');
    const text = reason.trim();
    cancel.mutate(text ? { reason: text } : {}, {
      onSuccess: (b) => {
        toast.success(`Đã hủy đặt chỗ ${b.code}`);
        onClose();
      },
      onError: (e) => {
        const apiError = toApiError(e);
        setError(plainMessage(apiError));
        if (apiError.statusCode === 409) refresh();
      },
    });
  };

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto bg-white sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Hủy đặt chỗ lưu trú</DialogTitle>
          <DialogDescription>{`${booking.petName} · ${booking.code} · nhận ${formatShortDate(booking.checkInDate)}`}</DialogDescription>
        </DialogHeader>
        {late && (
          <p role="note" className="rounded-lg bg-amber-50 px-3 py-2 text-sm font-medium text-amber-800">
            {`Hủy muộn: còn dưới ${BOARDING_CFG.lateCancelHours} giờ tính đến 00:00 ngày nhận, nên lần hủy này được tính vào hạn chế đặt online của khách (BR-LH-09).`}
          </p>
        )}
        <Field label="Lý do hủy (không bắt buộc)" hint={`${reason.length}/${REASON_MAX}`}>
          {(id) => (
            <textarea
              id={id}
              rows={3}
              maxLength={REASON_MAX}
              value={reason}
              onChange={(e) => {
                setReason(e.target.value);
                setError('');
              }}
              className="w-full rounded-lg border border-(--color-border-default) bg-white px-3 py-2 text-sm"
            />
          )}
        </Field>
        <FormError message={error} />
        <div className="flex justify-end gap-2">
          <Button variant="outline" className="min-h-10" onClick={onClose} disabled={cancel.isPending}>
            Đóng
          </Button>
          <Button variant="destructive" className="min-h-10" onClick={submit} disabled={cancel.isPending}>
            {cancel.isPending ? 'Đang hủy…' : 'Xác nhận hủy'}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
