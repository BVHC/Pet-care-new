import { useState } from 'react';
import { EmptyRow, Field, FormError, NoticeBar, selectClass, type Notice } from '../../../components/admin/form-ui';
import { QueryError } from '../../../components/staff/ui';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { Input } from '../../../components/ui/input';
import { FormModal } from '../../../components/ui/modal-templates';
import { cn, formatDateTime } from '../../../lib/utils';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useMaterialShortages, useResolveShortage } from '../../../shared/hooks/useMaterialUse';
import { MATERIAL_USE_LIMITS, type MaterialUsage } from '../../../shared/types/materialuse';
import { Pager } from './Pager';

const PAGE_SIZE = 20;

/**
 * ST07 — vật tư tiêu hao bị thiếu tồn lúc hoàn tất lượt khám (BR-KB-13). Đánh dấu "đã xử lý" chỉ ghi nhận việc đã làm
 * (nhập thêm, điều chỉnh tồn…), không tự đổi tồn. Chỉ BRANCH_MANAGER.
 */
export function ShortagesTab() {
  const [resolved, setResolved] = useState(false);
  const [page, setPage] = useState(0);
  const [acting, setActing] = useState<MaterialUsage | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const shortages = useMaterialShortages({ resolved, page, size: PAGE_SIZE });
  const data = shortages.data;

  return (
    <div className="space-y-4">
      <Card className="card">
        <CardContent className="flex flex-wrap items-center gap-3 p-4">
          <select
            aria-label="Lọc thiếu hụt"
            className={cn(selectClass, 'w-48')}
            value={resolved ? 'done' : 'open'}
            onChange={(e) => {
              setResolved(e.target.value === 'done');
              setPage(0);
            }}
          >
            <option value="open">Chưa xử lý</option>
            <option value="done">Đã xử lý</option>
          </select>
          <p className="text-sm text-(--text-secondary)">Vật tư thiếu tồn khi hoàn tất lượt khám; lượt vẫn hoàn tất bình thường.</p>
        </CardContent>
      </Card>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="overflow-hidden p-0">
          {!data && shortages.isError ? (
            <QueryError error={shortages.error} onRetry={() => shortages.refetch()} what="thiếu hụt vật tư" />
          ) : (
            <table className="table" aria-label="Thiếu hụt vật tư">
              <thead>
                <tr>
                  <th>Lượt khám</th>
                  <th>Dịch vụ</th>
                  <th>Vật tư</th>
                  <th className="text-right">Định mức</th>
                  <th className="text-right">Đã xuất</th>
                  <th className="text-right">Thiếu</th>
                  <th>{resolved ? 'Xử lý' : 'Thao tác'}</th>
                </tr>
              </thead>
              <tbody>
                {data?.content.length ? (
                  data.content.map((u) => (
                    <tr key={u.usageId}>
                      <td className="tabular-nums">#{u.visitId}</td>
                      <td>{u.serviceName}</td>
                      <td className="font-medium text-(--text-primary)">{u.productName}</td>
                      <td className="text-right tabular-nums">
                        {u.normQty} {u.unit}
                      </td>
                      <td className="text-right tabular-nums">{u.issuedQty}</td>
                      <td className="text-right font-semibold tabular-nums text-red-600">{u.shortageQty}</td>
                      <td>
                        {u.resolvedAt ? (
                          <span className="text-sm text-(--text-secondary)">
                            {formatDateTime(u.resolvedAt)}
                            {u.resolvedNote && ` — ${u.resolvedNote}`}
                          </span>
                        ) : (
                          <Button size="sm" variant="outline" onClick={() => setActing(u)} aria-label={`Đánh dấu đã xử lý ${u.productName} lượt #${u.visitId}`}>
                            Đánh dấu đã xử lý
                          </Button>
                        )}
                      </td>
                    </tr>
                  ))
                ) : (
                  <EmptyRow colSpan={7} loading={shortages.isLoading} />
                )}
              </tbody>
            </table>
          )}
          {data && <Pager data={data} noun="dòng" onPage={setPage} />}
        </CardContent>
      </Card>

      {acting && (
        <ResolveForm
          usage={acting}
          onClose={() => setActing(null)}
          onSaved={() => {
            setNotice({ kind: 'success', text: `Đã ghi nhận xử lý thiếu hụt ${acting.productName} của lượt #${acting.visitId}` });
            setActing(null);
          }}
        />
      )}
    </div>
  );
}

function ResolveForm({ usage, onClose, onSaved }: { usage: MaterialUsage; onClose: () => void; onSaved: () => void }) {
  const [note, setNote] = useState('');
  const [error, setError] = useState('');
  const resolve = useResolveShortage();

  const submit = async () => {
    if (!note.trim()) return setError('Vui lòng ghi việc đã xử lý (nhập thêm, điều chỉnh tồn…)');
    setError('');
    try {
      await resolve.mutateAsync({ usageId: usage.usageId, note: note.trim() });
      onSaved();
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title="Đánh dấu đã xử lý thiếu hụt"
      description={`${usage.productName} — thiếu ${usage.shortageQty} ${usage.unit} ở lượt #${usage.visitId}. Chỉ ghi nhận, không tự điều chỉnh tồn.`}
      onSubmit={submit}
      submitText="Ghi nhận"
      loading={resolve.isPending}
    >
      <Field label="Đã xử lý bằng cách nào">
        {(id) => <Input id={id} value={note} maxLength={MATERIAL_USE_LIMITS.note} onChange={(e) => setNote(e.target.value)} autoFocus />}
      </Field>
      <FormError message={error} />
    </FormModal>
  );
}
