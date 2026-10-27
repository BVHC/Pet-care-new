// Lễ tân gia hạn đặt chỗ (boarding-v1 #11, A9): BOOKED và CHECKED_IN, trước ngày trả dự kiến. Số đêm tính từ ngày nhận gốc (BR-LT-02),
// tiền tạm tính = số đêm × giá đêm đã chốt. Lỗi BR-LT-02/03/04 và 409 của BE hiện nguyên câu; 409 thì làm mới danh sách.
import { useState } from 'react';
import { toast } from 'sonner';
import { Field, FormError } from '@/components/admin/form-ui';
import { formatShortDate } from '@/components/appointments/format';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { formatCurrency } from '@/lib/utils';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { boardingApi } from '@/shared/api/boarding.api';
import { useBoardingMutation, useRefreshBoarding } from '@/shared/hooks/useBoarding';
import type { Booking } from '@/shared/types/boarding';
import { nightsBetween, shiftDate, stayAmount } from '@/shared/utils/boarding';

export function ExtendBookingDialog({ booking, onClose }: { booking: Booking; onClose: () => void }) {
  const [checkOut, setCheckOut] = useState('');
  const [error, setError] = useState('');
  const extend = useBoardingMutation((checkOutDate: string) => boardingApi.extend(booking.bookingId, { checkOutDate }));
  const refresh = useRefreshBoarding();
  const minDate = shiftDate(booking.checkOutDate, 1);
  const valid = checkOut >= minDate;
  const nights = nightsBetween(booking.checkInDate, checkOut);
  const extra = nightsBetween(booking.checkOutDate, checkOut);

  const submit = () => {
    setError('');
    extend.mutate(checkOut, {
      onSuccess: (b) => {
        toast.success(`Đã gia hạn ${b.code} đến ${formatShortDate(b.checkOutDate)}`);
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
          <DialogTitle>Gia hạn lưu trú</DialogTitle>
          <DialogDescription>{`${booking.petName} · ${booking.code} · nhận ${formatShortDate(booking.checkInDate)}, trả dự kiến ${formatShortDate(booking.checkOutDate)}`}</DialogDescription>
        </DialogHeader>
        <Field label="Ngày trả mới" hint="Phải sau ngày trả hiện tại.">
          {(id) => (
            <Input
              id={id}
              type="date"
              min={minDate}
              value={checkOut}
              onChange={(e) => {
                setCheckOut(e.target.value);
                setError('');
              }}
            />
          )}
        </Field>
        {valid && (
          <div className="space-y-1 rounded-lg bg-(--bg-tertiary) px-3 py-2 text-sm">
            <p>{`${nights} đêm (thêm ${extra} đêm)`}</p>
            <p className="font-medium">{`Tạm tính ${formatCurrency(stayAmount(nights, booking.nightlyPrice))}`}</p>
            <p className="text-xs text-(--text-tertiary)">{`${formatCurrency(booking.nightlyPrice)} mỗi đêm, tính từ ngày nhận`}</p>
          </div>
        )}
        <FormError message={error} />
        <div className="flex justify-end gap-2">
          <Button variant="outline" className="min-h-10" onClick={onClose} disabled={extend.isPending}>
            Đóng
          </Button>
          <Button className="min-h-10" onClick={submit} disabled={!valid || extend.isPending}>
            {extend.isPending ? 'Đang gia hạn…' : 'Gia hạn'}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
