import { useState } from 'react';
import { Plus } from 'lucide-react';
import { EmptyRow, Field, FormError, NoticeBar, selectClass, type Notice } from '../../../components/admin/form-ui';
import { QueryError } from '../../../components/staff/ui';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { FormModal, StatusBadge } from '../../../components/ui/modal-templates';
import { cn, formatDateTime } from '../../../lib/utils';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { stockopsApi } from '../../../shared/api/stockops.api';
import { useCategories } from '../../../shared/hooks/useCatalog';
import { useStockCounts, useStockOpsMutation } from '../../../shared/hooks/useStockOps';
import {
  STOCK_COUNT_STATUSES,
  STOCK_COUNT_STATUS_LABELS,
  type OpenStockCountRequest,
  type StockCount,
  type StockCountScope,
  type StockCountStatus,
} from '../../../shared/types/stockops';
import { CountDetail } from './CountDetail';
import { COUNT_STATUS_VARIANT } from './transfer-ui';
import { Pager } from './Pager';

const PAGE_SIZE = 20;
const SCOPE_LABELS: Record<StockCountScope, string> = { ALL: 'Toàn kho', CATEGORY: 'Theo danh mục' };

/** UC78 — phiên kiểm kê của chi nhánh: mở phiên (toàn kho / theo danh mục), nhập số đếm theo lô, xác nhận hoặc hủy. Chỉ BRANCH_MANAGER. */
export function CountsTab() {
  const [status, setStatus] = useState<StockCountStatus | ''>('');
  const [page, setPage] = useState(0);
  const [openId, setOpenId] = useState<number | null>(null);
  const [opening, setOpening] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  const counts = useStockCounts({ status: status || undefined, page, size: PAGE_SIZE });
  const data = counts.data;

  return (
    <div className="space-y-4">
      <Card className="card">
        <CardContent className="flex flex-wrap items-center gap-3 p-4">
          <select
            aria-label="Lọc trạng thái phiên kiểm kê"
            className={cn(selectClass, 'w-44')}
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as StockCountStatus | '');
              setPage(0);
            }}
          >
            <option value="">Mọi trạng thái</option>
            {STOCK_COUNT_STATUSES.map((s) => (
              <option key={s} value={s}>
                {STOCK_COUNT_STATUS_LABELS[s]}
              </option>
            ))}
          </select>
          <div className="flex-1" />
          <Button onClick={() => setOpening(true)}>
            <Plus className="h-4 w-4" />
            Mở phiên kiểm kê
          </Button>
        </CardContent>
      </Card>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="overflow-hidden p-0">
          {!data && counts.isError ? (
            <QueryError error={counts.error} onRetry={() => counts.refetch()} what="phiên kiểm kê" />
          ) : (
            <table className="table" aria-label="Phiên kiểm kê">
              <thead>
                <tr>
                  <th>Mã phiên</th>
                  <th>Phạm vi</th>
                  <th>Mở lúc</th>
                  <th>Xác nhận lúc</th>
                  <th>Trạng thái</th>
                  <th className="text-right">Thao tác</th>
                </tr>
              </thead>
              <tbody>
                {data?.content.length ? (
                  data.content.map((c) => (
                    <tr key={c.countId}>
                      <td className="font-mono text-xs">{c.code}</td>
                      <td>{SCOPE_LABELS[c.scope]}</td>
                      <td>{formatDateTime(c.createdAt)}</td>
                      <td>{c.confirmedAt ? formatDateTime(c.confirmedAt) : '—'}</td>
                      <td>
                        <StatusBadge status={STOCK_COUNT_STATUS_LABELS[c.status]} variant={COUNT_STATUS_VARIANT[c.status]} />
                      </td>
                      <td className="text-right">
                        <Button size="sm" variant="outline" onClick={() => setOpenId(c.countId)} aria-label={`Mở phiên ${c.code}`}>
                          Mở
                        </Button>
                      </td>
                    </tr>
                  ))
                ) : (
                  <EmptyRow colSpan={6} loading={counts.isLoading} />
                )}
              </tbody>
            </table>
          )}
          {data && <Pager data={data} noun="phiên" onPage={setPage} />}
        </CardContent>
      </Card>

      {openId != null && <CountDetail countId={openId} onClose={() => setOpenId(null)} onDone={(text) => setNotice({ kind: 'success', text })} />}
      {opening && (
        <OpenCountForm
          onClose={() => setOpening(false)}
          onSaved={(saved) => {
            setOpening(false);
            setNotice({ kind: 'success', text: `Đã mở phiên kiểm kê ${saved.code}` });
            setOpenId(saved.countId);
          }}
        />
      )}
    </div>
  );
}

/** Mỗi chi nhánh chỉ có một phiên OPEN: phiên thứ hai bị 409, BE nói rõ phải xác nhận hoặc hủy phiên cũ. */
function OpenCountForm({ onClose, onSaved }: { onClose: () => void; onSaved: (saved: StockCount) => void }) {
  const categories = useCategories();
  const [scope, setScope] = useState<StockCountScope>('ALL');
  const [categoryId, setCategoryId] = useState('');
  const [error, setError] = useState('');
  const open = useStockOpsMutation((body: OpenStockCountRequest) => stockopsApi.openCount(body));

  const submit = async () => {
    if (scope === 'CATEGORY' && !categoryId) return setError('Kiểm kê theo danh mục phải chọn danh mục sản phẩm');
    setError('');
    try {
      onSaved(await open.mutateAsync({ scope, categoryId: scope === 'CATEGORY' ? Number(categoryId) : null }));
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  return (
    <FormModal
      open
      onOpenChange={(o) => !o && onClose()}
      title="Mở phiên kiểm kê"
      description="Phiên chụp danh sách lô còn tồn lúc mở. Tồn chỉ đổi khi xác nhận kiểm kê."
      onSubmit={submit}
      submitText="Mở phiên"
      loading={open.isPending}
    >
      <Field label="Phạm vi">
        {(id) => (
          <select id={id} className={selectClass} value={scope} onChange={(e) => setScope(e.target.value as StockCountScope)}>
            <option value="ALL">Toàn kho</option>
            <option value="CATEGORY">Theo danh mục</option>
          </select>
        )}
      </Field>
      {scope === 'CATEGORY' && (
        <Field label="Danh mục sản phẩm">
          {(id) => (
            <select id={id} className={selectClass} value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
              <option value="">Chọn danh mục…</option>
              {categories.data?.map((c) => (
                <option key={c.categoryId} value={c.categoryId}>
                  {c.name}
                </option>
              ))}
            </select>
          )}
        </Field>
      )}
      <FormError message={error} />
    </FormModal>
  );
}
