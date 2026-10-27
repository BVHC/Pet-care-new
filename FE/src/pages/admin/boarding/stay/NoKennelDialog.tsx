// UC59 "Hủy vì hết chuồng" (A2, BR-LT-08): lễ tân hủy đặt chỗ BOOKED khi tới ngày nhận mà không còn chuồng trống đúng loại.
// Chữ ký và tên truy cập của role="dialog" ("Hủy vì hết chuồng") là hợp đồng với AdminBoardingPage: không đổi. `onDone` là phần thêm tùy chọn
// (CheckInDialog dùng để đóng theo khi hủy xong).
import { useState } from 'react';
import { toast } from 'sonner';
import { FormError } from '@/components/admin/form-ui';
import { Button } from '@/components/ui/button';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { boardingApi } from '@/shared/api/boarding.api';
import { useBoardingMutation, useRefreshBoarding } from '@/shared/hooks/useBoarding';
import type { Booking } from '@/shared/types/boarding';
import { useAllowed } from './hooks';
import { BookingSummary, Callout, StayFooter, StayForbidden, StayModal } from './shared';

export function NoKennelDialog({ booking, onClose, onDone }: { booking: Booking; onClose: () => void; onDone?: () => void }) {
  const allowed = useAllowed(['RECEPTIONIST']);
  return (
    <StayModal title="Hủy vì hết chuồng" onClose={onClose}>
      {allowed ? <NoKennelBody booking={booking} onClose={onClose} onDone={onDone} /> : <StayForbidden who="Chỉ lễ tân được hủy đặt chỗ vì hết chuồng." onClose={onClose} />}
    </StayModal>
  );
}

function NoKennelBody({ booking, onClose, onDone }: { booking: Booking; onClose: () => void; onDone?: () => void }) {
  const cancel = useBoardingMutation(() => boardingApi.cancelNoKennel(booking.bookingId));
  const refresh = useRefreshBoarding();
  const [error, setError] = useState('');
  /** 409: người khác vừa xử lý đặt chỗ này; không còn gì để xác nhận. */
  const [conflict, setConflict] = useState(false);

  const confirm = () => {
    setError('');
    cancel.mutate(undefined, {
      onSuccess: () => {
        toast.success(`Đã hủy đặt chỗ ${booking.code} vì hết chuồng. Hệ thống đã báo khách và quản lý chi nhánh.`);
        onDone?.();
        onClose();
      },
      onError: (e) => {
        const apiError = toApiError(e);
        setError(plainMessage(apiError));
        setConflict(apiError.statusCode === 409);
        refresh();
      },
    });
  };

  return (
    <>
      <BookingSummary booking={booking} />
      <Callout tone="warning" title="Đây là hủy do phòng khám">
        <p>
          Dùng khi tới ngày nhận mà chi nhánh không còn chuồng trống đúng loại “{booking.kennelTypeName}” (BR-LT-08). Đặt chỗ được hủy như hủy do phòng khám:
          không tính hủy muộn nên không ảnh hưởng hạn chế đặt online của khách. Hệ thống tự báo khách và quản lý chi nhánh; nên gọi báo khách ngay.
        </p>
      </Callout>
      <FormError message={error} />
      {conflict && <p className="text-sm text-(--text-secondary)">Có người vừa xử lý đặt chỗ này. Đã tải lại danh sách lưu trú.</p>}
      <StayFooter>
        {conflict ? (
          <Button onClick={onClose}>Đóng</Button>
        ) : (
          <>
            <Button variant="outline" onClick={onClose} disabled={cancel.isPending}>
              Quay lại
            </Button>
            <Button variant="destructive" onClick={confirm} disabled={cancel.isPending}>
              {cancel.isPending ? 'Đang xử lý…' : 'Xác nhận hủy'}
            </Button>
          </>
        )}
      </StayFooter>
    </>
  );
}
