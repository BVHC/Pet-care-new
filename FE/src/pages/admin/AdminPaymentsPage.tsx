import { useState } from 'react';
import { Field, FormError, NoticeBar, selectClass, type Notice } from '../../components/admin/form-ui';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Card, CardContent } from '../../components/ui/card';
import { Input } from '../../components/ui/input';
import { DetailModal, FormModal, InfoRow, StatusBadge } from '../../components/ui/modal-templates';
import { cn, formatCurrency, formatDateTime } from '../../lib/utils';
import { plainMessage, toApiError } from '../../shared/api/api-error';
import { useReconcileShift, useShift, useShifts } from '../../shared/hooks/useSales';
import { useAccount } from '../../shared/stores/session.store';
import { SHIFT_STATUS_LABELS, type CashierShift, type CashierShiftStatus } from '../../shared/types/sales';
import { formatClock } from '../../shared/utils/clinic-format';
import { describeDifference, shiftDifference } from '../../shared/utils/shift-format';

const PAGE_SIZE = 20;
const NOTE_MAX = 1000;
const STATUS_VARIANT = { OPEN: 'info', CLOSED: 'warning', RECONCILED: 'success' } as const;

/** "2026-10-21" → "21/10/2026" mà không qua `Date` (ngày nghiệp vụ là ngày Việt Nam, không đổi theo múi giờ máy). */
const formatBusinessDate = (iso: string) => iso.split('-').reverse().join('/');
const money = (value?: number | null) => (value == null ? '—' : formatCurrency(value));

/** BE chỉ ghi `difference` khi đối soát; ca đã chốt thì tự tính từ số thực đếm (ADR-0064 §3.2). */
function differenceOf(shift: CashierShift): { value: number; provisional: boolean } | null {
  if (shift.difference != null) return { value: shift.difference, provisional: false };
  if (shift.status === 'CLOSED' && shift.countedCash != null && shift.expectedCash != null) {
    return { value: shiftDifference(shift.countedCash, shift.expectedCash), provisional: true };
  }
  return null;
}

type View = { kind: 'detail' | 'reconcile'; shiftId: number };

/**
 * Ca thu ngân của chi nhánh (UC72, A05; sales-v1 #19–21; BR-TG-04, BR-TG-05): danh sách ca mới nhất trước, chi tiết, và đối soát ca đã chốt.
 * Ca tự chốt (ST13) chưa có số thực đếm nên bắt buộc nhập khi đối soát. Lễ tân mở / chốt ca và thu tiền ngay ở bàn Lễ tân (UC70, UC71).
 */
export function AdminPaymentsPage() {
  const manager = useAccount()?.role === 'BRANCH_MANAGER';
  const [status, setStatus] = useState('');
  const [date, setDate] = useState('');
  const [page, setPage] = useState(0);
  const [view, setView] = useState<View | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const shifts = useShifts(
    { status: (status || undefined) as CashierShiftStatus | undefined, businessDate: date || undefined, page, size: PAGE_SIZE },
    manager,
  );
  const data = shifts.data;
  const reset = (set: (v: string) => void) => (v: string) => {
    set(v);
    setPage(0);
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Ca thu ngân</h1>
        <p className="text-(--text-secondary)">
          Ca của chi nhánh, mới nhất trước. Ca đã chốt chờ quản lý nhập hoặc xác nhận số tiền mặt thực đếm, ghi chênh lệch và ghi chú rồi đối soát.
        </p>
      </div>

      <Card className="card">
        <CardContent className="flex flex-wrap items-center gap-3 p-4">
          <select aria-label="Lọc trạng thái" className={cn(selectClass, 'w-44')} value={status} onChange={(e) => reset(setStatus)(e.target.value)}>
            <option value="">Mọi trạng thái</option>
            {(Object.keys(SHIFT_STATUS_LABELS) as CashierShiftStatus[]).map((s) => (
              <option key={s} value={s}>
                {SHIFT_STATUS_LABELS[s]}
              </option>
            ))}
          </select>
          <Input aria-label="Ngày nghiệp vụ" type="date" className="w-44" value={date} onChange={(e) => reset(setDate)(e.target.value)} />
        </CardContent>
      </Card>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="overflow-x-auto p-0">
          <table className="table">
            <thead>
              <tr>
                <th>Ngày</th>
                <th>Lễ tân</th>
                <th>Loại ca</th>
                <th>Trạng thái</th>
                <th>Mở / chốt lúc</th>
                <th className="text-right">Tiền mặt</th>
                <th className="text-right">Chuyển khoản</th>
                <th className="text-right">Thực đếm</th>
                <th className="text-right">Chênh lệch</th>
                <th className="text-right">Phiếu thu</th>
                <th className="text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {data?.content.length ? (
                data.content.map((s) => <ShiftRow key={s.shiftId} shift={s} onView={() => setView({ kind: 'detail', shiftId: s.shiftId })} onReconcile={() => setView({ kind: 'reconcile', shiftId: s.shiftId })} />)
              ) : (
                <tr>
                  <td colSpan={11} className="py-8 text-center text-(--text-tertiary)">
                    {shifts.isLoading ? 'Đang tải…' : 'Không có ca nào khớp bộ lọc.'}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
          {shifts.isError && <FormError message={plainMessage(toApiError(shifts.error))} />}
          {data && data.totalPages > 1 && (
            <div className="flex items-center justify-end gap-3 border-t border-(--border-color) px-4 py-3 text-sm text-(--text-secondary)">
              <span>
                Trang {data.page + 1}/{data.totalPages} · {data.totalElements} ca
              </span>
              <Button size="sm" variant="outline" disabled={data.page === 0} onClick={() => setPage(data.page - 1)}>
                Trước
              </Button>
              <Button size="sm" variant="outline" disabled={data.page + 1 >= data.totalPages} onClick={() => setPage(data.page + 1)}>
                Sau
              </Button>
            </div>
          )}
        </CardContent>
      </Card>

      {view?.kind === 'detail' && (
        <ShiftDetail
          shiftId={view.shiftId}
          onClose={() => setView(null)}
          onReconcile={() => setView({ kind: 'reconcile', shiftId: view.shiftId })}
        />
      )}
      {view?.kind === 'reconcile' && (
        <ReconcileDialog
          // Ca lấy từ danh sách đang xem; nếu đã đổi trang thì đọc lại theo id.
          key={view.shiftId}
          shiftId={view.shiftId}
          onClose={() => setView(null)}
          onDone={(shift) => {
            setNotice({ kind: 'success', text: `Đã đối soát ca #${shift.shiftId}.` });
            setView({ kind: 'detail', shiftId: shift.shiftId });
          }}
        />
      )}
    </div>
  );
}

function ShiftRow({ shift: s, onView, onReconcile }: { shift: CashierShift; onView: () => void; onReconcile: () => void }) {
  const diff = differenceOf(s);
  return (
    <tr>
      <td className="whitespace-nowrap">{formatBusinessDate(s.businessDate)}</td>
      <td className="font-medium text-(--text-primary)">{s.cashierName}</td>
      <td>{s.isAfterHours ? 'Ca ngoài giờ' : 'Ca thường'}</td>
      <td>
        <div className="flex flex-wrap items-center gap-1.5">
          <StatusBadge status={SHIFT_STATUS_LABELS[s.status]} variant={STATUS_VARIANT[s.status]} />
          {s.autoClosed && <Badge variant="outline">Tự chốt</Badge>}
        </div>
      </td>
      <td className="whitespace-nowrap text-xs">
        {formatClock(s.openedAt)} – {s.closedAt ? formatClock(s.closedAt) : '…'}
      </td>
      <td className="text-right tabular-nums">{money(s.expectedCash)}</td>
      <td className="text-right tabular-nums">{money(s.expectedTransfer)}</td>
      <td data-col="counted" className="text-right tabular-nums">
        {money(s.countedCash)}
      </td>
      <td className={cn('text-right tabular-nums', diff && diff.value !== 0 && 'font-medium text-red-700')}>
        {diff ? describeDifference(diff.value) : '—'}
        {diff?.provisional && <span className="block text-xs font-normal text-(--text-tertiary)">tạm tính</span>}
      </td>
      <td className="text-right tabular-nums">{s.paymentCount}</td>
      <td className="whitespace-nowrap text-right">
        <Button size="sm" variant="outline" onClick={onView} aria-label={`Xem ca #${s.shiftId}`}>
          Xem
        </Button>
        {s.status === 'CLOSED' && (
          <Button size="sm" className="ml-2" onClick={onReconcile} aria-label={`Đối soát ca #${s.shiftId}`}>
            Đối soát
          </Button>
        )}
      </td>
    </tr>
  );
}

function ShiftDetail({ shiftId, onClose, onReconcile }: { shiftId: number; onClose: () => void; onReconcile: () => void }) {
  const query = useShift(shiftId);
  const s = query.data;
  const diff = s ? differenceOf(s) : null;
  return (
    <DetailModal
      open
      onOpenChange={(open) => !open && onClose()}
      title={s ? `Ca #${s.shiftId} · ${formatBusinessDate(s.businessDate)}` : `Ca #${shiftId}`}
      onEdit={s?.status === 'CLOSED' ? onReconcile : undefined}
      editText="Đối soát ca"
    >
      {!s ? (
        query.isError ? <FormError message={plainMessage(toApiError(query.error))} /> : <p className="text-sm text-(--text-secondary)">Đang tải…</p>
      ) : (
        <div className="space-y-1">
          <InfoRow label="Lễ tân" value={s.cashierName} />
          <InfoRow label="Loại ca" value={s.isAfterHours ? 'Ca ngoài giờ' : 'Ca thường'} />
          <InfoRow label="Trạng thái" value={<StatusBadge status={SHIFT_STATUS_LABELS[s.status]} variant={STATUS_VARIANT[s.status]} />} />
          <InfoRow label="Mở lúc" value={formatDateTime(s.openedAt)} />
          <InfoRow label="Chốt lúc" value={s.closedAt ? `${formatDateTime(s.closedAt)}${s.autoClosed ? ' · hệ thống tự chốt' : ''}` : '—'} />
          <InfoRow label="Số phiếu thu" value={s.paymentCount} />
          <InfoRow label="Tiền mặt theo phiếu thu" value={money(s.expectedCash)} />
          <InfoRow label="Chuyển khoản theo phiếu thu" value={money(s.expectedTransfer)} />
          <InfoRow label="Tiền mặt thực đếm" value={money(s.countedCash)} />
          <InfoRow label="Chênh lệch" value={diff ? `${describeDifference(diff.value)}${diff.provisional ? ' · tạm tính' : ''}` : '—'} />
          {s.status === 'RECONCILED' && (
            <>
              <InfoRow label="Đối soát lúc" value={s.reconciledAt ? formatDateTime(s.reconciledAt) : '—'} />
              <InfoRow label="Ghi chú đối soát" value={s.reconcileNote ?? '—'} className="gap-4" />
            </>
          )}
        </div>
      )}
    </DetailModal>
  );
}

function ReconcileDialog({ shiftId, onClose, onDone }: { shiftId: number; onClose: () => void; onDone: (shift: CashierShift) => void }) {
  const query = useShift(shiftId);
  const shift = query.data;
  if (!shift) {
    return (
      <FormModal open onOpenChange={(open) => !open && onClose()} title={`Đối soát ca #${shiftId}`} submitText="Đối soát" loading={!query.isError}>
        {query.isError ? <FormError message={plainMessage(toApiError(query.error))} /> : <p className="text-sm text-(--text-secondary)">Đang tải…</p>}
      </FormModal>
    );
  }
  return <ReconcileForm shift={shift} onClose={onClose} onDone={onDone} />;
}

function ReconcileForm({ shift, onClose, onDone }: { shift: CashierShift; onClose: () => void; onDone: (shift: CashierShift) => void }) {
  const reconcile = useReconcileShift();
  const [counted, setCounted] = useState(shift.countedCash != null ? String(shift.countedCash) : '');
  const [note, setNote] = useState('');
  const [errors, setErrors] = useState<{ counted?: string; note?: string }>({});
  const [error, setError] = useState('');

  const typed = counted.trim();
  const valid = /^\d+$/.test(typed);
  const expected = shift.expectedCash ?? 0;

  const submit = () => {
    const next: { counted?: string; note?: string } = {};
    // BR-TG-05: ca tự chốt chưa có số thực đếm thì phải nhập; ca khác để trống nghĩa là giữ số lễ tân đã nhập.
    if (!typed && shift.autoClosed && shift.countedCash == null) next.counted = 'Ca tự chốt chưa có số thực đếm: nhập số tiền mặt thực đếm để đối soát.';
    else if (typed && !valid) next.counted = 'Số tiền thực đếm là số nguyên từ 0.';
    if (!note.trim()) next.note = 'Nhập ghi chú đối soát.';
    else if (note.length > NOTE_MAX) next.note = `Ghi chú tối đa ${NOTE_MAX} ký tự.`;
    setErrors(next);
    setError('');
    if (next.counted || next.note) return;

    // Số thực đếm chỉ gửi khi đổi so với số lễ tân đã chốt (gửi lên là ghi đè, audit giữ số cũ).
    const countedCash = typed && Number(typed) !== shift.countedCash ? Number(typed) : undefined;
    reconcile.mutate(
      { shiftId: shift.shiftId, ...(countedCash !== undefined && { countedCash }), note: note.trim() },
      { onSuccess: onDone, onError: (e) => setError(plainMessage(toApiError(e))) },
    );
  };

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title={`Đối soát ca #${shift.shiftId}`}
      description={`${shift.cashierName} · ${formatBusinessDate(shift.businessDate)}`}
      submitText="Đối soát"
      loading={reconcile.isPending}
      onSubmit={submit}
    >
      <div className="space-y-1 rounded-lg bg-(--bg-tertiary) p-3 text-sm">
        <p>Tiền mặt theo phiếu thu: {money(shift.expectedCash)}</p>
        <p className="text-(--text-secondary)">Chuyển khoản theo phiếu thu: {money(shift.expectedTransfer)} (không đếm)</p>
      </div>
      {shift.autoClosed && shift.countedCash == null && (
        <p role="note" className="rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-800">
          Ca này tự chốt nên chưa có số tiền thực đếm. Nhập số tiền mặt đếm được trong két mới đối soát được.
        </p>
      )}
      <Field label="Tiền mặt thực đếm" error={errors.counted}>
        {(id) => (
          <Input id={id} inputMode="numeric" autoComplete="off" value={counted} onChange={(e) => setCounted(e.target.value)} placeholder="0" />
        )}
      </Field>
      {typed && valid && <p className="text-sm font-medium">Chênh lệch: {describeDifference(shiftDifference(Number(typed), expected))}</p>}
      <Field label="Ghi chú đối soát" error={errors.note}>
        {(id) => (
          <>
            <textarea
              id={id}
              rows={3}
              value={note}
              onChange={(e) => setNote(e.target.value)}
              className="w-full rounded-lg border border-(--color-border-default) bg-white px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-accent"
            />
            <span className={cn('block text-right text-xs', note.length > NOTE_MAX ? 'text-red-600' : 'text-(--text-tertiary)')}>{`${note.length}/${NOTE_MAX}`}</span>
          </>
        )}
      </Field>
      <FormError message={error} />
    </FormModal>
  );
}
