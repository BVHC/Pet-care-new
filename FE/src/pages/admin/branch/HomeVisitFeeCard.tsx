import { useState } from 'react';
import { Checkbox, Field, NoticeBar, type Notice } from '../../../components/admin/form-ui';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { Input } from '../../../components/ui/input';
import { formatCurrency } from '../../../lib/utils';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { feesApi } from '../../../shared/api/fees.api';
import { useFeesMutation, useHomeVisitFee } from '../../../shared/hooks/useFees';
import type { Branch } from '../../../shared/types/branch';

/**
 * UC32 — phí khám tại nhà của chi nhánh (BR-SP-09): chi nhánh chưa bật thì không nhận đặt lịch khám tại nhà. Chỉ SUPER_MANAGER;
 * BRANCH_MANAGER bị BE từ chối (403) nên không hiện khối này. Phí chỉ áp cho lịch đặt SAU khi lưu (BR-SP-11).
 */
export function HomeVisitFeeCard({ branch }: { branch: Branch }) {
  const current = useHomeVisitFee(branch.branchId);
  const [draft, setDraft] = useState<{ enabled: boolean; fee: string } | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState<Notice | null>(null);
  const save = useFeesMutation((body: { enabled: boolean; fee: number }) => feesApi.saveHomeVisitFee(branch.branchId, body));
  const shown = draft ?? (current.data ? { enabled: current.data.enabled, fee: String(current.data.fee) } : null);

  const submit = async () => {
    if (!shown) return;
    if (shown.fee.trim() === '') return setError('Vui lòng nhập phí khám tại nhà');
    const fee = Number(shown.fee);
    setError('');
    setNotice(null);
    try {
      const saved = await save.mutateAsync({ enabled: shown.enabled, fee });
      setDraft(null);
      setNotice({ kind: 'success', text: `Đã lưu phí khám tại nhà: ${saved.enabled ? `bật, ${formatCurrency(saved.fee)}` : 'tắt'}` });
    } catch (e) {
      setNotice({ kind: 'error', text: plainMessage(toApiError(e)) });
    }
  };

  return (
    <Card className="card">
      <CardContent className="space-y-3 p-5">
        <h3 className="font-semibold text-(--text-primary)">Phí khám tại nhà</h3>
        {current.isError && <NoticeBar notice={{ kind: 'error', text: plainMessage(toApiError(current.error)) }} />}
        {shown && (
          <form
            noValidate
            className="flex flex-wrap items-end gap-4"
            onSubmit={(e) => {
              e.preventDefault();
              void submit();
            }}
          >
            <Checkbox label="Nhận đặt lịch khám tại nhà" checked={shown.enabled} onChange={(enabled) => setDraft({ ...shown, enabled })} />
            <div className="w-48">
              <Field label="Phí khám tại nhà (VND)" error={error}>
                {(id) => <Input id={id} type="number" min={0} step={1000} value={shown.fee} onChange={(e) => setDraft({ ...shown, fee: e.target.value })} />}
              </Field>
            </div>
            <Button type="submit" disabled={save.isPending || !draft}>
              Lưu phí khám tại nhà
            </Button>
          </form>
        )}
        <NoticeBar notice={notice} />
      </CardContent>
    </Card>
  );
}
