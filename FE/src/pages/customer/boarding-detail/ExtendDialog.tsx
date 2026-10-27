// Gia hạn (boarding-v1 #11, BR-LT-06 / 02 / 03 / 04): chọn ngày trả mới, xem số đêm mới và tạm tính thêm trước khi xác nhận.
// Chỉ kiểm cái chắc chắn sai ở máy (bỏ trống, không sau ngày trả hiện tại); mọi luật còn lại BE quyết định và câu của BE được hiện nguyên.
import { useState } from 'react';
import { toast } from 'sonner';
import { Field, FormError } from '@/components/admin/form-ui';
import { formatShortDate, formatSlotDate } from '@/components/appointments/format';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { formatCurrency } from '@/lib/utils';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { boardingApi } from '@/shared/api/boarding.api';
import { useBoardingMutation, useRefreshBoarding } from '@/shared/hooks/useBoarding';
import { BOARDING_CFG, type Booking } from '@/shared/types/boarding';
import { shiftDate } from '@/shared/utils/boarding';
import { bookingNights, extendPreview, isIsoDate } from './model';
import { BTN_OUTLINE, BTN_PRIMARY, DATE_INPUT, DIALOG_CONTENT } from './styles';

export function ExtendDialog({ booking, open, onClose }: { booking: Booking; open: boolean; onClose: () => void }) {
  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent className={DIALOG_CONTENT}>
        <DialogHeader>
          <DialogTitle>Gia hạn lưu trú</DialogTitle>
          <DialogDescription>
            Chọn ngày trả mới sau ngày trả hiện tại. Số đêm tính từ ngày nhận ban đầu, tối đa {BOARDING_CFG.maxNights} đêm.
          </DialogDescription>
        </DialogHeader>
        <ExtendForm booking={booking} onClose={onClose} />
      </DialogContent>
    </Dialog>
  );
}

function ExtendForm({ booking, onClose }: { booking: Booking; onClose: () => void }) {
  const [date, setDate] = useState('');
  const [error, setError] = useState('');
  const extend = useBoardingMutation((checkOutDate: string) => boardingApi.extend(booking.bookingId, { checkOutDate }));
  const refresh = useRefreshBoarding();
  const preview = isIsoDate(date) && date > booking.checkOutDate ? extendPreview(booking, date) : null;

  const submit = () => {
    setError('');
    if (!isIsoDate(date)) return setError('Vui lòng chọn ngày trả mới');
    if (date <= booking.checkOutDate) return setError('Ngày trả mới phải sau ngày trả hiện tại');
    extend.mutate(date, {
      onSuccess: (updated) => {
        toast.success(`Đã gia hạn lưu trú đến ${formatShortDate(updated.checkOutDate)}`);
        onClose();
      },
      onError: (e) => {
        const apiError = toApiError(e);
        setError(plainMessage(apiError));
        // 409 (người khác vừa đổi / hủy) hoặc BR-LT-06 (đã tới ngày trả): dữ liệu trên màn đã cũ.
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
          Nhận {formatSlotDate(booking.checkInDate)}, trả hiện tại {formatSlotDate(booking.checkOutDate)} ({bookingNights(booking)} đêm)
        </p>
      </div>

      <Field label="Ngày trả mới" hint={`Từ ${formatShortDate(shiftDate(booking.checkOutDate, 1))} đến ${formatShortDate(shiftDate(booking.checkInDate, BOARDING_CFG.maxNights))}`}>
        {(id) => (
          <input
            id={id}
            type="date"
            value={date}
            min={shiftDate(booking.checkOutDate, 1)}
            max={shiftDate(booking.checkInDate, BOARDING_CFG.maxNights)}
            onChange={(e) => setDate(e.target.value)}
            className={DATE_INPUT}
          />
        )}
      </Field>

      <div role="status" aria-label="Tóm tắt gia hạn" className="rounded-xl bg-(--color-surface-2) p-3 text-[13px] leading-relaxed">
        {preview ? (
          <ul className="space-y-0.5">
            <li>
              Số đêm mới: <strong>{preview.newNights} đêm</strong>
            </li>
            <li>Thêm {preview.addedNights} đêm so với hiện tại</li>
            <li>
              Tạm tính thêm: <strong>{formatCurrency(preview.extraAmount)}</strong>
            </li>
            {preview.overMax && <li className="font-bold text-amber-800">Tổng tối đa {BOARDING_CFG.maxNights} đêm cho mỗi đặt chỗ.</li>}
            <li className="pt-1 text-[12px] text-(--color-text-secondary)">Số tiền chốt khi trả thú, tính theo đêm thực tế.</li>
          </ul>
        ) : (
          <p className="text-(--color-text-secondary)">Chọn ngày trả mới để xem số đêm và tạm tính.</p>
        )}
      </div>

      <FormError message={error} />
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <button type="button" onClick={onClose} disabled={extend.isPending} className={BTN_OUTLINE}>
          Đóng
        </button>
        <button type="button" onClick={submit} disabled={extend.isPending} className={BTN_PRIMARY}>
          {extend.isPending ? 'Đang gia hạn…' : 'Xác nhận gia hạn'}
        </button>
      </div>
    </div>
  );
}
