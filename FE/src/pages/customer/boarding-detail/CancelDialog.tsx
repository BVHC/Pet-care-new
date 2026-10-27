// Hủy (boarding-v1 #12, BR-LT-06): lý do tùy chọn tối đa 300 ký tự. Hủy muộn (BR-LH-09) được cảnh báo rõ TRƯỚC khi xác nhận.
import { useState } from 'react';
import { toast } from 'sonner';
import { Field, FormError } from '@/components/admin/form-ui';
import { formatSlotDate } from '@/components/appointments/format';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { boardingApi } from '@/shared/api/boarding.api';
import { useBoardingMutation, useRefreshBoarding } from '@/shared/hooks/useBoarding';
import { BOARDING_CFG, type Booking } from '@/shared/types/boarding';
import { isLateCancel } from '@/shared/utils/boarding';
import { lateCancelFromLabel, RESTRICTION_RULE } from './model';
import { BTN_DANGER, BTN_OUTLINE, DIALOG_CONTENT, TEXTAREA } from './styles';

const REASON_MAX = 300;

export function CancelDialog({ booking, open, onClose }: { booking: Booking; open: boolean; onClose: () => void }) {
  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent className={DIALOG_CONTENT}>
        <DialogHeader>
          <DialogTitle>Hủy đặt chỗ lưu trú</DialogTitle>
          <DialogDescription>Đặt chỗ đã hủy không khôi phục được; muốn lưu trú thì đặt chỗ mới.</DialogDescription>
        </DialogHeader>
        <CancelForm booking={booking} onClose={onClose} />
      </DialogContent>
    </Dialog>
  );
}

function CancelForm({ booking, onClose }: { booking: Booking; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const cancel = useBoardingMutation((body: { reason?: string }) => boardingApi.cancel(booking.bookingId, body));
  const refresh = useRefreshBoarding();
  const late = isLateCancel(booking.checkInDate);
  const tooLong = reason.length > REASON_MAX;

  const submit = () => {
    setError('');
    if (tooLong) return setError(`Lý do tối đa ${REASON_MAX} ký tự.`);
    const text = reason.trim();
    cancel.mutate(text ? { reason: text } : {}, {
      onSuccess: (updated) => {
        toast.success(updated.lateCancel ? 'Đã hủy đặt chỗ lưu trú (ghi nhận hủy muộn)' : 'Đã hủy đặt chỗ lưu trú');
        onClose();
      },
      onError: (e) => {
        const apiError = toApiError(e);
        setError(plainMessage(apiError));
        // 409 (người khác vừa nhận / hủy) hoặc BR-LT-06 (đã tới ngày nhận): dữ liệu trên màn đã cũ.
        if (apiError.statusCode === 409 || apiError.ruleId === 'BR-LT-06') void refresh();
      },
    });
  };

  return (
    <div className="space-y-4">
      <div className="rounded-xl border border-(--color-border-default) bg-(--color-surface-2) p-3 text-[13px]">
        <p className="font-bold">
          {booking.petName}, mã {booking.code}
        </p>
        <p className="mt-0.5 text-(--color-text-secondary)">
          Nhận {formatSlotDate(booking.checkInDate)}, trả {formatSlotDate(booking.checkOutDate)}
        </p>
      </div>

      {late ? (
        <div role="alert" className="rounded-xl border border-amber-300 bg-amber-50 p-3 text-[13px] leading-relaxed text-amber-950">
          <p className="font-bold">Hủy lúc này được tính là hủy muộn.</p>
          <p className="mt-1">
            Đã trong vòng {BOARDING_CFG.lateCancelHours} giờ trước ngày nhận. Hủy muộn tính vào hạn chế đặt online: {RESTRICTION_RULE}.
          </p>
        </div>
      ) : (
        <p role="note" className="rounded-xl bg-(--color-surface-2) p-3 text-[13px] leading-relaxed text-(--color-text-secondary)">
          Bây giờ hủy chưa bị tính là hủy muộn. Từ {lateCancelFromLabel(booking.checkInDate)} trở đi, hủy được tính là hủy muộn và tính vào hạn chế đặt online.
        </p>
      )}

      <Field label="Lý do hủy (không bắt buộc)" error={tooLong ? `Tối đa ${REASON_MAX} ký tự` : undefined}>
        {(id) => <textarea id={id} rows={3} value={reason} onChange={(e) => setReason(e.target.value)} className={TEXTAREA} />}
      </Field>
      <p className="-mt-2 text-right text-[11.5px] text-(--color-text-secondary)">
        {reason.length}/{REASON_MAX}
      </p>

      <FormError message={error} />
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <button type="button" onClick={onClose} disabled={cancel.isPending} className={BTN_OUTLINE}>
          Giữ đặt chỗ
        </button>
        <button type="button" onClick={submit} disabled={cancel.isPending} className={BTN_DANGER}>
          {cancel.isPending ? 'Đang hủy…' : 'Xác nhận hủy'}
        </button>
      </div>
    </div>
  );
}
