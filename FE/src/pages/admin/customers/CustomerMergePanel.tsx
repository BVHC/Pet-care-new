import { useState } from 'react';
import { AlertTriangle } from 'lucide-react';
import { Field, FormError, NoticeBar, type Notice } from '../../../components/admin/form-ui';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { Input } from '../../../components/ui/input';
import { Modal, ModalBody, ModalFooter, ModalHeader, ModalTitle } from '../../../components/ui/modal-templates';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useMergeCustomers, useMergePreview } from '../../../shared/hooks/useCustMerge';
import type { MergePreview } from '../../../shared/types/custmerge';

interface Pair {
  target: number;
  source: number;
}

/** Số bản ghi sẽ chuyển (nhãn + trường của #1); địa chỉ trùng tính riêng ở dòng địa chỉ. */
const MOVED: { label: string; of: (p: MergePreview) => number }[] = [
  { label: 'Thú cưng', of: (p) => p.petCount },
  { label: 'Địa chỉ giao hàng', of: (p) => p.addressCount - p.duplicateAddressCount },
  { label: 'Lịch hẹn', of: (p) => p.appointmentCount },
  { label: 'Đặt chỗ lưu trú', of: (p) => p.boardingCount },
  { label: 'Order', of: (p) => p.orderCount },
  { label: 'Đơn online', of: (p) => p.onlineOrderCount },
  { label: 'Phản hồi', of: (p) => p.feedbackCount },
  { label: 'Khiếu nại', of: (p) => p.complaintCount },
  { label: 'Việc gọi điện', of: (p) => p.careTaskCount },
];

const positiveInt = (text: string) => (/^[1-9]\d*$/.test(text.trim()) ? Number(text.trim()) : null);

/**
 * UC27 — gộp hồ sơ khách trùng (chỉ BRANCH_MANAGER). Chọn hồ sơ đích (giữ lại) và nguồn (bị gộp) theo mã, xem trước số bản ghi sẽ chuyển,
 * cảnh báo và điều kiện chặn (BR-KH-11), rồi xác nhận. Gộp không hoàn tác (BR-KH-13). Quản lý chưa tra cứu khách theo tên / SĐT được
 * (customer-v1 chỉ mở cho lễ tân, bác sĩ, nhân viên chăm sóc) nên chọn hồ sơ bằng mã.
 */
export function CustomerMergePanel() {
  const [targetText, setTargetText] = useState('');
  const [sourceText, setSourceText] = useState('');
  const [pair, setPair] = useState<Pair | null>(null);
  const [formError, setFormError] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  const preview = useMergePreview(pair?.target ?? null, pair?.source ?? null);

  const submit = () => {
    const target = positiveInt(targetText);
    const source = positiveInt(sourceText);
    setNotice(null);
    if (!target || !source) return setFormError('Mã hồ sơ là số nguyên dương');
    if (target === source) return setFormError('Hồ sơ nguồn phải khác hồ sơ đích');
    setFormError('');
    setPair({ target, source });
  };

  const p = preview.data;
  return (
    <div className="space-y-4">
      <Card className="card">
        <CardContent className="space-y-3 p-5">
          <p className="text-sm text-(--text-secondary)">
            Dữ liệu của hồ sơ <strong>nguồn</strong> (thú cưng, địa chỉ, lịch hẹn, đơn…) được chuyển sang hồ sơ <strong>đích</strong>; hồ sơ nguồn bị khóa và không hiện khi tra cứu.
            Gộp xong không hoàn tác được. Hỏi lễ tân mã hai hồ sơ trùng.
          </p>
          <form
            noValidate
            className="flex flex-wrap items-start gap-3"
            onSubmit={(e) => {
              e.preventDefault();
              submit();
            }}
          >
            <div className="w-56">
              <Field label="Mã hồ sơ đích (giữ lại)">{(id) => <Input id={id} inputMode="numeric" value={targetText} onChange={(e) => setTargetText(e.target.value)} />}</Field>
            </div>
            <div className="w-56">
              <Field label="Mã hồ sơ nguồn (bị gộp)">{(id) => <Input id={id} inputMode="numeric" value={sourceText} onChange={(e) => setSourceText(e.target.value)} />}</Field>
            </div>
            <Button type="submit" className="mt-7">
              Xem trước gộp
            </Button>
          </form>
          <FormError message={formError} />
        </CardContent>
      </Card>

      <NoticeBar notice={notice} />

      {pair && preview.isError && <FormError message={plainMessage(toApiError(preview.error))} />}
      {pair && p && (
        <Card className="card">
          <CardContent className="space-y-4 p-5">
            <h2 className="font-semibold text-(--text-primary)">
              Gộp hồ sơ #{p.sourceCustomerId} vào hồ sơ #{p.targetCustomerId}
            </h2>
            <table className="table" aria-label="Dữ liệu sẽ chuyển">
              <tbody>
                {MOVED.map(({ label, of }) => (
                  <tr key={label}>
                    <td>{label}</td>
                    <td className="text-right font-medium tabular-nums">{of(p)}</td>
                  </tr>
                ))}
                {p.duplicateAddressCount > 0 && (
                  <tr>
                    <td>Địa chỉ trùng (bỏ khi gộp)</td>
                    <td className="text-right font-medium tabular-nums">{p.duplicateAddressCount}</td>
                  </tr>
                )}
              </tbody>
            </table>
            {p.warnings.length > 0 && (
              <ul aria-label="Cảnh báo" className="space-y-1 rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-900">
                {p.warnings.map((w) => (
                  <li key={w}>{w}</li>
                ))}
              </ul>
            )}
            {p.blockers.length > 0 && (
              <ul aria-label="Điều kiện chặn" className="space-y-1 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">
                {p.blockers.map((b) => (
                  <li key={b} className="flex gap-2">
                    <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
                    {b}
                  </li>
                ))}
              </ul>
            )}
            <Button disabled={p.blockers.length > 0} onClick={() => setConfirming(true)}>
              Gộp hồ sơ
            </Button>
          </CardContent>
        </Card>
      )}

      {confirming && p && (
        <ConfirmMerge
          preview={p}
          onClose={() => setConfirming(false)}
          onDone={(text) => {
            setConfirming(false);
            setPair(null);
            setTargetText('');
            setSourceText('');
            setNotice({ kind: 'success', text });
          }}
        />
      )}
    </div>
  );
}

/** Hộp xác nhận mạnh: gửi `confirm: true` chỉ sau khi người dùng đã xem #1 (BR-KH-14). */
function ConfirmMerge({ preview, onClose, onDone }: { preview: MergePreview; onClose: () => void; onDone: (text: string) => void }) {
  const merge = useMergeCustomers();
  const [error, setError] = useState('');
  const run = async () => {
    setError('');
    try {
      const result = await merge.mutateAsync({ sourceCustomerId: preview.sourceCustomerId, targetCustomerId: preview.targetCustomerId, confirm: true });
      const moved = Object.values(result.moved).reduce((sum, n) => sum + n, 0);
      onDone(`Đã gộp hồ sơ #${result.sourceCustomerId} vào hồ sơ #${result.targetCustomerId} (chuyển ${moved} bản ghi)`);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };
  return (
    <Modal open onOpenChange={(open) => !open && onClose()} className="max-w-md">
      <ModalHeader onClose={onClose}>
        <ModalTitle>
          Gộp hồ sơ #{preview.sourceCustomerId} vào #{preview.targetCustomerId}?
        </ModalTitle>
      </ModalHeader>
      <ModalBody className="space-y-3">
        <p className="rounded-lg bg-red-50 px-3 py-2 text-sm font-medium text-red-700">Không thể hoàn tác. Hồ sơ #{preview.sourceCustomerId} sẽ bị khóa sau khi gộp.</p>
        <FormError message={error} />
      </ModalBody>
      <ModalFooter>
        <Button variant="outline" onClick={onClose} disabled={merge.isPending}>
          Quay lại
        </Button>
        <Button variant="destructive" onClick={run} disabled={merge.isPending}>
          Xác nhận gộp
        </Button>
      </ModalFooter>
    </Modal>
  );
}
