// Ca thu ngân của lễ tân (UC71; BR-TG-01, BR-TG-05, BR-CN-05; docs/adr/0064 §3): chip trạng thái ở đầu trang, mở ca khi chưa có ca,
// chốt ca bằng số tiền mặt thực đếm. Lệnh ghi dùng `plainMessage` chứ không dùng `paymentErrorMessage` vì câu 409 của thu tiền
// ("Order không còn chờ thu") sai với ca thu ngân. Quản lý chi nhánh không thấy gì ở đây và không gửi lệnh gọi nào (403): đối soát ở /admin/payments.
import { useId, useState, type FormEvent } from 'react';
import { toast } from 'sonner';
import { formatCurrency } from '../../../lib/utils';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useShiftOptions } from '../../../shared/hooks/useShiftOptions';
import { useCloseCashierShift, useMyShift, useOpenCashierShift } from '../../../shared/hooks/useSales';
import { useAccount } from '../../../shared/stores/session.store';
import type { CashierShift } from '../../../shared/types/sales';
import { formatClock } from '../../../shared/utils/clinic-format';
import { describeDifference, shiftDifference } from '../../../shared/utils/shift-format';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '../../ui/dialog';
import { Skeleton, WsButton } from '../ui';

const useIsReceptionist = () => useAccount()?.role === 'RECEPTIONIST';

/** Chip ở đầu trang: "Ca mở lúc HH:mm · N phiếu thu" (tiền chỉ có sau khi chốt, ca đang mở chưa có expected*). */
export function ShiftBadge() {
  const receptionist = useIsReceptionist();
  const query = useMyShift(receptionist);
  if (!receptionist) return null;
  if (query.isLoading) return <Skeleton className="h-6 w-40 rounded-full" />;
  if (query.isError) {
    return (
      <span className="ws-chip" data-tone="urgent">
        Không tải được ca thu ngân
      </span>
    );
  }
  const shift = query.data?.shift;
  if (!shift) {
    return (
      <span className="ws-chip" data-tone="wait">
        Chưa mở ca thu ngân
      </span>
    );
  }
  return (
    <>
      <span className="ws-chip ws-num" data-tone="ready">{`Ca mở lúc ${formatClock(shift.openedAt)} · ${shift.paymentCount} phiếu thu`}</span>
      {shift.isAfterHours && (
        <span className="ws-chip" data-tone="accent">
          Ca ngoài giờ
        </span>
      )}
    </>
  );
}

/** Phần "chưa có ca" của phiếu thu: nhắc mở ca (BR-TG-01) và chỉ hiện nút mở ca hợp lệ theo giờ chi nhánh (BR-TG-05, BR-CN-05). */
export function OpenShiftPanel() {
  const { options, isLoading, isError, refetch } = useShiftOptions();
  const open = useOpenCashierShift();
  const [error, setError] = useState('');

  const openShift = (afterHours: boolean) => {
    setError('');
    open.mutate(afterHours, {
      onSuccess: () => toast.success('Đã mở ca thu ngân'),
      // BE vẫn kiểm lại luật giờ (400 BR-TG-05 / BR-CN-05) và ca đang mở (409).
      onError: (e) => setError(plainMessage(toApiError(e))),
    });
  };

  return (
    <div className="flex flex-col gap-2 rounded-lg border border-dashed border-(--ws-line-strong) p-3">
      <p className="text-sm font-semibold">Chưa mở ca thu ngân</p>
      <p className="text-[13px] text-(--ws-ink-2)">Mở ca thu ngân để bắt đầu thu tiền.</p>
      {isLoading ? (
        <Skeleton className="h-10 w-full" />
      ) : isError || !options ? (
        <div className="flex flex-col gap-2">
          <p role="alert" className="text-[13px] text-(--ws-urgent-ink)">
            Không tải được giờ mở cửa của chi nhánh nên chưa biết mở được ca nào.
          </p>
          <WsButton size="sm" onClick={refetch}>
            Tải lại
          </WsButton>
        </div>
      ) : (
        <>
          {options.normal && (
            <WsButton variant="primary" disabled={open.isPending} onClick={() => openShift(false)}>
              Mở ca thường
            </WsButton>
          )}
          {options.afterHours && (
            <WsButton variant={options.normal ? 'secondary' : 'primary'} disabled={open.isPending} onClick={() => openShift(true)}>
              Mở ca ngoài giờ
            </WsButton>
          )}
          {options.afterHours && !options.normal && (
            <p className="text-xs text-(--ws-ink-3)">Đang ngoài giờ mở cửa. Ca ngoài giờ dùng để thu tiền ca cấp cứu.</p>
          )}
          {options.reason && <p className="text-[13px] text-(--ws-ink-2)">{options.reason}</p>}
        </>
      )}
      {error && (
        <p role="alert" className="rounded-md bg-(--ws-urgent-bg) px-3 py-2 text-sm text-(--ws-urgent-ink)">
          {error}
        </p>
      )}
    </div>
  );
}

/** Nút "Chốt ca" + hộp thoại nhập tiền mặt thực đếm; xong hiện tóm tắt (giữ trạng thái ở đây vì ca đóng thì nút biến mất). */
export function CloseShiftControl() {
  const receptionist = useIsReceptionist();
  const query = useMyShift(receptionist);
  // Giữ ca đang chốt trong state: chốt xong (hoặc ca đã đóng ở nơi khác) thì `useMyShift` về trống nhưng hộp thoại vẫn phải còn để đọc kết quả / lỗi.
  const [target, setTarget] = useState<CashierShift | null>(null);
  const [closed, setClosed] = useState<CashierShift | null>(null);
  if (!receptionist) return null;
  const shift = query.data?.shift ?? null;

  const dismiss = () => {
    setTarget(null);
    setClosed(null);
  };

  return (
    <>
      {shift && <WsButton onClick={() => setTarget(shift)}>Chốt ca</WsButton>}
      <Dialog open={target != null} onOpenChange={(open) => !open && dismiss()}>
        <DialogContent className="ws-portal border-(--ws-line) bg-(--ws-surface) text-(--ws-ink) sm:max-w-sm">
          {closed ? (
            <ClosedSummary shift={closed} onDone={dismiss} />
          ) : (
            target && <CloseForm shift={target} onClosed={setClosed} onCancel={dismiss} />
          )}
        </DialogContent>
      </Dialog>
    </>
  );
}

function CloseForm({ shift, onClosed, onCancel }: { shift: CashierShift; onClosed: (shift: CashierShift) => void; onCancel: () => void }) {
  const close = useCloseCashierShift();
  const fieldId = useId();
  const [text, setText] = useState('');
  const [error, setError] = useState('');

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!/^\d+$/.test(text.trim())) {
      setError('Nhập số tiền mặt thực đếm, là số nguyên từ 0 (chuyển khoản không tính vào đây).');
      return;
    }
    setError('');
    close.mutate(
      { shiftId: shift.shiftId, countedCash: Number(text.trim()) },
      {
        onSuccess: (result) => {
          toast.success('Đã chốt ca thu ngân');
          onClosed(result);
        },
        onError: (err) => setError(plainMessage(toApiError(err))),
      },
    );
  };

  return (
    <form onSubmit={submit} noValidate className="flex flex-col gap-4">
      <div>
        <DialogTitle className="text-lg font-bold">Chốt ca thu ngân</DialogTitle>
        <DialogDescription className="text-sm text-(--ws-ink-2)">
          Đếm tiền mặt trong két rồi nhập vào đây. Chuyển khoản không đếm.
        </DialogDescription>
      </div>
      <p role="note" className="rounded-md bg-(--ws-wait-bg) px-3 py-2 text-sm font-medium text-(--ws-wait-ink)">
        Chốt xong không thu thêm tiền được nữa. Muốn thu tiếp phải mở ca mới.
      </p>
      <div className="flex flex-col gap-1.5">
        <label htmlFor={fieldId} className="text-sm font-medium">
          Tiền mặt thực đếm
        </label>
        <input
          id={fieldId}
          inputMode="numeric"
          autoComplete="off"
          autoFocus
          value={text}
          onChange={(e) => setText(e.target.value)}
          aria-invalid={error ? true : undefined}
          placeholder="0"
          className="ws-num h-10 rounded-md border border-(--ws-line-strong) bg-(--ws-surface) px-3 text-right text-(--ws-ink)"
        />
      </div>
      {error && (
        <p role="alert" className="rounded-md bg-(--ws-urgent-bg) px-3 py-2 text-sm text-(--ws-urgent-ink)">
          {error}
        </p>
      )}
      <div className="flex justify-end gap-2">
        <WsButton onClick={onCancel} disabled={close.isPending}>
          Quay lại
        </WsButton>
        <WsButton type="submit" variant="primary" disabled={close.isPending}>
          {close.isPending ? 'Đang chốt…' : 'Xác nhận chốt ca'}
        </WsButton>
      </div>
    </form>
  );
}

function ClosedSummary({ shift, onDone }: { shift: CashierShift; onDone: () => void }) {
  const expectedCash = shift.expectedCash ?? 0;
  const counted = shift.countedCash ?? 0;
  const rows: [string, string][] = [
    ['Tiền mặt theo phiếu thu', formatCurrency(expectedCash)],
    ['Chuyển khoản theo phiếu thu', formatCurrency(shift.expectedTransfer ?? 0)],
    ['Tiền mặt thực đếm', formatCurrency(counted)],
    ['Chênh lệch tiền mặt', describeDifference(shiftDifference(counted, expectedCash))],
  ];
  return (
    <div className="flex flex-col gap-4">
      <div>
        <DialogTitle className="text-lg font-bold">Đã chốt ca thu ngân</DialogTitle>
        <DialogDescription className="text-sm text-(--ws-ink-2)">{shift.paymentCount} phiếu thu trong ca.</DialogDescription>
      </div>
      <dl className="divide-y divide-(--ws-line) border-y border-(--ws-line) text-sm">
        {rows.map(([label, value]) => (
          <div key={label} className="flex justify-between gap-3 py-1.5">
            <dt className="text-(--ws-ink-2)">{label}</dt>
            <dd className="ws-num font-semibold">{value}</dd>
          </div>
        ))}
      </dl>
      <p className="ws-chip justify-center" data-tone="wait">
        Chờ quản lý đối soát
      </p>
      <WsButton variant="primary" autoFocus onClick={onDone}>
        Xong
      </WsButton>
    </div>
  );
}
