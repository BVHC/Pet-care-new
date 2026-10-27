// UC59 "Trả thú" (A3, A4, A6, A10, A11; BR-LT-09): lễ tân bắt đầu trả thú (sinh Order lưu trú chờ thu) → thu tiền Ở BÀN LỄ TÂN → giao thú chỉ khi Order PAID;
// khách không trả thì hủy phiên (lý do bắt buộc) rồi bắt đầu lại. Mở hộp thoại KHÔNG ghi gì: mọi lệnh ghi do lễ tân bấm.
// Bước suy ra từ dữ liệu tải về, không lưu thêm: đặt chỗ mới nhất (`activeOrderId`) + Order đó. Thu tiền xảy ra ở màn khác nên hai truy vấn này luôn hỏi lại
// khi mở, khi bấm "Kiểm tra lại" và khi cửa sổ lấy lại tiêu điểm (hooks.ts).
// Chữ ký và tên truy cập của role="dialog" ("Trả thú") là hợp đồng với AdminBoardingPage: không đổi.
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { Field, FormError } from '@/components/admin/form-ui';
import { Button } from '@/components/ui/button';
import { InfoRow } from '@/components/ui/modal-templates';
import { formatCurrency } from '@/lib/utils';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { boardingApi } from '@/shared/api/boarding.api';
import { salesApi } from '@/shared/api/sales.api';
import { useBoardingMutation } from '@/shared/hooks/useBoarding';
import { usePOS } from '@/shared/stores/pos.store';
import { BOOKING_STATUS_LABELS, type Booking } from '@/shared/types/boarding';
import type { Order } from '@/shared/types/sales';
import { isInStay } from '@/shared/utils/boarding';
import { useAllowed, useRefreshOnFocus, useRefreshStay, useStayBooking, useStayOrder } from './hooks';
import { BookingSummary, Callout, StayError, StayFooter, StayForbidden, StayLoading, StayModal } from './shared';

const REASON_MAX = 300;
const LOCK_REASON = 'Order lưu trú chưa thanh toán (BR-LT-09)';
const textareaClass =
  'w-full rounded-lg border border-(--color-border-default) px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-accent';

/** Số đêm của Order lưu trú = tổng số lượng các dòng BOARDING (A3: một dòng "Lưu trú <loại chuồng> × N đêm"). */
const nightsOf = (order: Order) => order.lines.filter((l) => l.lineType === 'BOARDING').reduce((sum, l) => sum + l.quantity, 0);

export function CheckoutDialog({ booking, onClose }: { booking: Booking; onClose: () => void }) {
  const allowed = useAllowed(['RECEPTIONIST']);
  return (
    <StayModal title="Trả thú" onClose={onClose}>
      {allowed ? <CheckoutBody booking={booking} onClose={onClose} /> : <StayForbidden who="Chỉ lễ tân được trả thú (phải thu tiền trong ca thu ngân)." onClose={onClose} />}
    </StayModal>
  );
}

function CheckoutBody({ booking, onClose }: { booking: Booking; onClose: () => void }) {
  const stay = useStayBooking(booking.bookingId);
  const live = stay.data;
  const inStay = !!live && isInStay(live.status);
  const orderId = inStay ? (live.activeOrderId ?? null) : null;
  const orderQuery = useStayOrder(orderId);
  const refresh = useRefreshStay();
  useRefreshOnFocus(refresh);
  /** Lỗi của lệnh vừa bấm: giữ ở đây vì làm mới có thể đổi bước, câu lỗi vẫn phải đọc được. */
  const [error, setError] = useState('');
  const fail = (e: unknown) => {
    setError(plainMessage(toApiError(e)));
    refresh();
  };

  if (!live) {
    if (stay.isError) return <StayError error={stay.error} onRetry={() => stay.refetch()} onClose={onClose} />;
    return <StayLoading what="Đang tải thông tin trả thú…" />;
  }
  if (!inStay) {
    return (
      <>
        <BookingSummary booking={live} />
        <FormError message={error} />
        <p className="text-sm text-(--text-secondary)">Thú không còn trong chuồng (đặt chỗ: {BOOKING_STATUS_LABELS[live.status]}), không cần trả thú nữa.</p>
        <StayFooter>
          <Button onClick={onClose}>Đóng</Button>
        </StayFooter>
      </>
    );
  }

  const order = orderQuery.data;
  return (
    <div className="space-y-4">
      <BookingSummary booking={live} />
      {live.status === 'OVERDUE' && (
        <Callout tone="warning" title="Quá hạn đón">
          <p>Thú ở quá ngày trả dự kiến; tiền lưu trú tiếp tục tính theo đêm (BR-LT-09).</p>
        </Callout>
      )}
      <FormError message={error} />
      {orderId == null || order?.status === 'CANCELLED' ? (
        <StartStep booking={live} onError={fail} onClearError={() => setError('')} onClose={onClose} />
      ) : orderQuery.isLoading ? (
        <StayLoading what="Đang tải Order lưu trú…" />
      ) : !order ? (
        <StayError error={orderQuery.error} onRetry={() => orderQuery.refetch()} onClose={onClose} />
      ) : (
        <OrderStep booking={live} order={order} onError={fail} onClearError={() => setError('')} onRefresh={refresh} onClose={onClose} />
      )}
    </div>
  );
}

/** Chưa có Order chờ thu: bắt đầu trả thú để hệ thống tính đêm và tiền. */
function StartStep({ booking, onError, onClearError, onClose }: { booking: Booking; onError: (e: unknown) => void; onClearError: () => void; onClose: () => void }) {
  const start = useBoardingMutation(() => boardingApi.startCheckout(booking.bookingId));
  return (
    <>
      <p className="text-sm text-(--text-secondary)">
        Bắt đầu trả thú sẽ tạo Order lưu trú chờ thanh toán. Số đêm tính từ ngày nhận thực tế đến hôm nay, tối thiểu 1 đêm; trả sau 12:00 tính thêm 1 đêm (BR-LT-09).
      </p>
      <StayFooter>
        <Button variant="outline" onClick={onClose} disabled={start.isPending}>
          Đóng
        </Button>
        <Button
          disabled={start.isPending}
          onClick={() => {
            onClearError();
            start.mutate(undefined, { onError });
          }}
        >
          {start.isPending ? 'Đang xử lý…' : 'Bắt đầu trả thú'}
        </Button>
      </StayFooter>
    </>
  );
}

/** Có Order: PENDING → thu ở bàn Lễ tân (hoặc tính lại / hủy phiên); PAID → giao thú. */
function OrderStep({
  booking,
  order,
  onError,
  onClearError,
  onRefresh,
  onClose,
}: {
  booking: Booking;
  order: Order;
  onError: (e: unknown) => void;
  onClearError: () => void;
  onRefresh: () => void;
  onClose: () => void;
}) {
  const navigate = useNavigate();
  const attachOrder = usePOS((s) => s.attachOrder);
  const restart = useBoardingMutation(() => boardingApi.startCheckout(booking.bookingId));
  const abort = useBoardingMutation((args: { orderId: number; reason: string }) => salesApi.abortCheckout(args.orderId, args.reason));
  const handOver = useBoardingMutation(() => boardingApi.handOver(booking.bookingId));
  const [aborting, setAborting] = useState(false);
  const [reason, setReason] = useState('');
  const [reasonError, setReasonError] = useState('');
  const paid = order.status === 'PAID';
  const busy = restart.isPending || abort.isPending || handOver.isPending;

  /** Thu tiền là việc của bàn Lễ tân (phiếu thu đọc khách + Order đang chọn trong store `usePOS`): đặt Order vào phiếu rồi sang đó. */
  const collect = () => {
    attachOrder(order.orderId, order.customerId);
    navigate('/staff/reception');
  };
  const confirmAbort = () => {
    const text = reason.trim();
    if (!text) {
      setReasonError('Vui lòng nhập lý do hủy phiên trả thú');
      return;
    }
    onClearError();
    abort.mutate(
      { orderId: order.orderId, reason: text },
      {
        onSuccess: () => {
          toast.success(`Đã hủy phiên trả thú ${order.code}. Thú vẫn ở trong chuồng, bắt đầu lại khi khách sẵn sàng.`);
          setAborting(false);
          setReason('');
        },
        onError,
      },
    );
  };

  return (
    <>
      <div>
        <InfoRow label="Mã Order" value={<span className="break-all">{order.code}</span>} />
        <InfoRow label="Số đêm" value={`${nightsOf(order)} đêm`} />
        <InfoRow label="Thành tiền" value={formatCurrency(order.totalAmount)} />
        <InfoRow label="Trạng thái" value={paid ? 'Đã thanh toán' : 'Chờ thanh toán'} />
      </div>

      {paid ? (
        <Callout tone="success">
          <p>Order lưu trú đã thanh toán. Giao thú cho khách rồi bấm Giao thú để trả chuồng.</p>
        </Callout>
      ) : aborting ? (
        <div className="space-y-3 rounded-lg border border-red-200 bg-red-50/50 p-3">
          <Field label="Lý do hủy phiên" error={reasonError} hint={`Khách không thanh toán. Thú vẫn ở trong chuồng và tiếp tục tính đêm. ${reason.length}/${REASON_MAX}`}>
            {(id) => (
              <textarea
                id={id}
                rows={2}
                maxLength={REASON_MAX}
                className={textareaClass}
                value={reason}
                aria-invalid={reasonError ? true : undefined}
                onChange={(e) => {
                  setReason(e.target.value);
                  setReasonError('');
                }}
              />
            )}
          </Field>
          <div className="flex flex-wrap justify-end gap-2">
            <Button variant="outline" disabled={abort.isPending} onClick={() => setAborting(false)}>
              Quay lại
            </Button>
            <Button variant="destructive" disabled={abort.isPending} onClick={confirmAbort}>
              {abort.isPending ? 'Đang xử lý…' : 'Xác nhận hủy phiên'}
            </Button>
          </div>
        </div>
      ) : (
        <>
          <p className="text-sm text-(--text-secondary)">
            Khách thanh toán ở bàn Lễ tân (cần ca thu ngân đang mở; màn hình nhỏ thì mở tab Phiếu thu). Thu xong, quay lại trang Lưu trú và mở lại Trả thú để Giao thú (thu ở cửa sổ khác thì bấm Kiểm tra lại).
          </p>
          <div className="flex flex-wrap gap-2">
            <Button onClick={collect} disabled={busy}>
              Thu tiền tại bàn Lễ tân
            </Button>
            <Button variant="outline" onClick={onRefresh}>
              Kiểm tra lại
            </Button>
            <Button
              variant="outline"
              disabled={busy}
              onClick={() => {
                onClearError();
                restart.mutate(undefined, { onError });
              }}
            >
              {restart.isPending ? 'Đang xử lý…' : 'Tính lại tiền'}
            </Button>
            <Button
              variant="outline"
              className="h-auto min-h-10 whitespace-normal border-red-200 py-2 text-red-700 hover:bg-red-50"
              disabled={busy}
              onClick={() => setAborting(true)}
            >
              Khách không thanh toán → Hủy phiên
            </Button>
          </div>
        </>
      )}

      <StayFooter>
        <Button variant="outline" onClick={onClose} disabled={busy}>
          Đóng
        </Button>
        <Button
          disabled={!paid || busy}
          aria-describedby={paid ? undefined : 'handover-lock'}
          onClick={() => {
            onClearError();
            handOver.mutate(undefined, {
              onSuccess: () => {
                toast.success(`Đã giao ${booking.petName} cho khách.`);
                onClose();
              },
              onError,
            });
          }}
        >
          {handOver.isPending ? 'Đang xử lý…' : 'Giao thú'}
        </Button>
      </StayFooter>
      {!paid && (
        <p id="handover-lock" className="text-right text-xs text-(--text-secondary)">
          {LOCK_REASON}
        </p>
      )}
    </>
  );
}
