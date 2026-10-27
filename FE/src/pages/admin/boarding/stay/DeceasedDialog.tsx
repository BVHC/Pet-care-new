// UC59 "Thú mất" (A5, BR-LT-12): lễ tân kết thúc lưu trú với lý do THÚ_MẤT, ghi rõ diễn biến. Không giao thú nên không cần Order PAID (BR-LT-09 không áp dụng);
// Order lưu trú tính đến hôm nay ở PENDING như Order thường (thu sau, hoặc BRANCH_MANAGER hủy theo BR-BH-05).
// Chữ ký và tên truy cập của role="dialog" ("Thú mất") là hợp đồng với AdminBoardingPage: không đổi.
import { useState, type FormEvent } from 'react';
import { toast } from 'sonner';
import { Field, FormError } from '@/components/admin/form-ui';
import { Button } from '@/components/ui/button';
import { formatCurrency } from '@/lib/utils';
import { fieldErrorsOf, plainMessage, toApiError } from '@/shared/api/api-error';
import { boardingApi } from '@/shared/api/boarding.api';
import { useBoardingMutation, useRefreshBoarding } from '@/shared/hooks/useBoarding';
import type { Booking, CheckoutStarted } from '@/shared/types/boarding';
import { useAllowed } from './hooks';
import { BookingSummary, Callout, StayFooter, StayForbidden, StayModal } from './shared';

const textareaClass =
  'w-full rounded-lg border border-(--color-border-default) px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-accent';

export function DeceasedDialog({ booking, onClose }: { booking: Booking; onClose: () => void }) {
  const allowed = useAllowed(['RECEPTIONIST']);
  return (
    <StayModal title="Thú mất" onClose={onClose}>
      {allowed ? <DeceasedBody booking={booking} onClose={onClose} /> : <StayForbidden who="Chỉ lễ tân được kết thúc lưu trú do thú mất." onClose={onClose} />}
    </StayModal>
  );
}

function DeceasedBody({ booking, onClose }: { booking: Booking; onClose: () => void }) {
  const end = useBoardingMutation((note: string) => boardingApi.endDeceased(booking.bookingId, { note }));
  const refresh = useRefreshBoarding();
  const [note, setNote] = useState('');
  const [noteError, setNoteError] = useState('');
  const [error, setError] = useState('');
  const [conflict, setConflict] = useState(false);
  /** Giữ kết quả ở đây: sau lệnh ghi, đặt chỗ rời khỏi "đang lưu trú" nên không thể dựng lại từ dữ liệu tải về. */
  const [result, setResult] = useState<CheckoutStarted | null>(null);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const text = note.trim();
    setError('');
    if (!text) {
      setNoteError('Vui lòng ghi rõ diễn biến');
      return;
    }
    end.mutate(text, {
      onSuccess: (started) => {
        toast.success(`Đã kết thúc lưu trú ${booking.code} do thú mất.`);
        setResult(started);
      },
      onError: (err) => {
        const apiError = toApiError(err);
        const fields = fieldErrorsOf(apiError);
        if (fields.note) setNoteError(fields.note);
        else setError(plainMessage(apiError));
        setConflict(apiError.statusCode === 409);
        refresh();
      },
    });
  };

  if (result) {
    return (
      <>
        <div role="status">
          <Callout tone="success" title="Đã kết thúc lưu trú do thú mất">
            <p>
              Order #{result.orderId} (chờ thu) · lưu trú {result.nights} đêm, tổng {formatCurrency(result.amount)}. Thu ở bàn Lễ tân, hoặc quản lý chi nhánh hủy nếu khách
              không thanh toán (BR-BH-05).
            </p>
            <p className="mt-1 font-medium">Sau đó đánh dấu thú đã mất ở trang thú (BR-KH-05).</p>
          </Callout>
        </div>
        <StayFooter>
          <Button onClick={onClose}>Đóng</Button>
        </StayFooter>
      </>
    );
  }

  return (
    <form onSubmit={submit} className="space-y-4">
      <BookingSummary booking={booking} />
      <Callout tone="danger" title="Kết thúc lưu trú vì thú mất">
        <p>
          Đặt chỗ kết thúc với lý do Thú mất và chuồng được giải phóng (BR-LT-12). Không giao thú nên không cần Order đã thanh toán. Hệ thống tạo Order lưu trú tính
          đến hôm nay ở trạng thái chờ thu: thu sau ở bàn Lễ tân, hoặc quản lý chi nhánh hủy nếu khách không thanh toán (BR-BH-05).
        </p>
      </Callout>
      <Field label="Diễn biến" error={noteError} hint="Ghi rõ thú mất lúc nào, thế nào, ai chứng kiến.">
        {(id) => (
          <textarea
            id={id}
            rows={4}
            className={textareaClass}
            value={note}
            aria-invalid={noteError ? true : undefined}
            onChange={(e) => {
              setNote(e.target.value);
              setNoteError('');
            }}
          />
        )}
      </Field>
      <FormError message={error} />
      {conflict && <p className="text-sm text-(--text-secondary)">Có người vừa xử lý đặt chỗ này. Đã tải lại danh sách lưu trú.</p>}
      <StayFooter>
        {conflict ? (
          <Button type="button" onClick={onClose}>
            Đóng
          </Button>
        ) : (
          <>
            <Button type="button" variant="outline" onClick={onClose} disabled={end.isPending}>
              Quay lại
            </Button>
            <Button type="submit" variant="destructive" disabled={end.isPending}>
              {end.isPending ? 'Đang xử lý…' : 'Xác nhận thú đã mất'}
            </Button>
          </>
        )}
      </StayFooter>
    </form>
  );
}
