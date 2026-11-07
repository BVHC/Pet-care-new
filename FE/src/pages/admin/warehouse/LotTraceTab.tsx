import { useState } from 'react';
import { Search } from 'lucide-react';
import { EmptyRow, Field, FormError, selectClass } from '../../../components/admin/form-ui';
import { QueryError } from '../../../components/staff/ui';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { Input } from '../../../components/ui/input';
import { formatDate, formatDateTime } from '../../../lib/utils';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useInventoryItems } from '../../../shared/hooks/useInventory';
import { useLotTrace, useStockLotSearch } from '../../../shared/hooks/useStockOps';
import { useAccount } from '../../../shared/stores/session.store';
import { LOT_TRACE_EVENT_LABELS, type LotSearchQuery } from '../../../shared/types/stockops';
import { Pager } from './Pager';

const PAGE_SIZE = 20;

/**
 * UC79 — truy vết lô: tìm lô theo số lô (khớp một phần) hoặc theo sản phẩm, bắt buộc một trong hai (BR-KO-13), rồi xem đường đi của lô.
 * BRANCH_MANAGER thấy mọi lô của chi nhánh; bác sĩ chỉ thấy lô thuốc, vaccine nên ô chọn sản phẩm cũng chỉ liệt kê hai loại đó.
 * Tên thú, tên khách chỉ có ở lô thuốc, vaccine. Truy vấn chỉ chạy khi bấm "Tìm lô", không chạy theo từng phím gõ.
 */
export function LotTraceTab() {
  const vet = useAccount()?.role === 'VET';
  const items = useInventoryItems({ page: 0, size: 100 });
  const [lotNumber, setLotNumber] = useState('');
  const [productId, setProductId] = useState('');
  const [submitted, setSubmitted] = useState<LotSearchQuery>({});
  const [page, setPage] = useState(0);
  const [traceId, setTraceId] = useState<number | null>(null);
  const [error, setError] = useState('');
  const lots = useStockLotSearch({ ...submitted, page, size: PAGE_SIZE });
  const trace = useLotTrace(traceId);
  const products = (items.data?.content ?? []).filter((it) => !vet || it.productType !== 'GOODS');
  const searched = Boolean(submitted.lotNumber) || submitted.productId != null;
  const data = lots.data;
  const t = trace.data;
  const withContact = t?.events.some((e) => e.petName || e.customerName) ?? false;

  const search = () => {
    const number = lotNumber.trim();
    if (!number && !productId) return setError('Nhập số lô hoặc chọn sản phẩm để tìm lô');
    setError('');
    setPage(0);
    setTraceId(null);
    setSubmitted({ lotNumber: number || undefined, productId: productId ? Number(productId) : undefined });
  };

  return (
    <div className="space-y-4">
      <Card className="card">
        <CardContent className="p-4">
          <form
            className="flex flex-wrap items-end gap-3"
            onSubmit={(e) => {
              e.preventDefault();
              search();
            }}
          >
            <div className="w-56">
              <Field label="Số lô">{(id) => <Input id={id} value={lotNumber} onChange={(e) => setLotNumber(e.target.value)} placeholder="VD: AMX2405" />}</Field>
            </div>
            <div className="w-72">
              <Field label="Sản phẩm" hint={vet ? 'Chỉ thuốc và vaccine.' : undefined}>
                {(id) => (
                  <select id={id} className={selectClass} value={productId} onChange={(e) => setProductId(e.target.value)}>
                    <option value="">Mọi sản phẩm</option>
                    {products.map((it) => (
                      <option key={it.productId} value={it.productId}>
                        {it.productName}
                      </option>
                    ))}
                  </select>
                )}
              </Field>
            </div>
            <Button type="submit">
              <Search className="h-4 w-4" />
              Tìm lô
            </Button>
          </form>
          <div className="mt-3">
            <FormError message={error} />
          </div>
        </CardContent>
      </Card>

      {!searched ? (
        <p className="text-sm text-(--text-secondary)">Nhập số lô hoặc chọn sản phẩm rồi bấm "Tìm lô". Lô đã hết hàng vẫn tìm được.</p>
      ) : (
        <Card className="card">
          <CardContent className="overflow-hidden p-0">
            {!data && lots.isError ? (
              <QueryError error={lots.error} onRetry={() => lots.refetch()} what="danh sách lô" />
            ) : (
              <table className="table" aria-label="Kết quả tìm lô">
                <thead>
                  <tr>
                    <th>Sản phẩm</th>
                    <th>Số lô</th>
                    <th>Hạn dùng</th>
                    <th className="text-right">Còn lại</th>
                    <th className="text-right">Thao tác</th>
                  </tr>
                </thead>
                <tbody>
                  {data?.content.length ? (
                    data.content.map((l) => (
                      <tr key={l.lotId}>
                        <td className="font-medium text-(--text-primary)">{l.productName}</td>
                        <td className="font-mono text-xs">{l.lotNumber ?? 'Lô mặc định'}</td>
                        <td>{l.expiryDate ? formatDate(l.expiryDate) : '—'}</td>
                        <td className="text-right tabular-nums">{l.quantity}</td>
                        <td className="text-right">
                          <Button size="sm" variant="outline" onClick={() => setTraceId(l.lotId)} aria-label={`Xem đường đi lô ${l.lotNumber ?? 'mặc định'} ${l.productName}`}>
                            Xem đường đi
                          </Button>
                        </td>
                      </tr>
                    ))
                  ) : (
                    <EmptyRow colSpan={5} loading={lots.isLoading} />
                  )}
                </tbody>
              </table>
            )}
            {data && <Pager data={data} noun="lô" onPage={setPage} />}
          </CardContent>
        </Card>
      )}

      {traceId != null && (
        <Card className="card">
          <CardContent className="space-y-3 p-4">
            {trace.isError && <FormError message={plainMessage(toApiError(trace.error))} />}
            {t && (
              <>
                <h2 className="font-semibold text-(--text-primary)">
                  Đường đi của lô {t.lot.lotNumber ?? 'mặc định'} — {t.lot.productName}
                </h2>
                <p className="text-sm text-(--text-secondary)">
                  Hạn dùng {t.lot.expiryDate ? formatDate(t.lot.expiryDate) : '—'} · còn lại {t.remaining}
                </p>
                <table className="table" aria-label="Đường đi của lô">
                  <thead>
                    <tr>
                      <th>Thời điểm</th>
                      <th>Sự kiện</th>
                      <th className="text-right">Số lượng</th>
                      <th>Chứng từ</th>
                      {withContact && <th>Thú / khách</th>}
                    </tr>
                  </thead>
                  <tbody>
                    {t.events.map((e, i) => (
                      <tr key={i}>
                        <td>{formatDateTime(e.occurredAt)}</td>
                        <td>{LOT_TRACE_EVENT_LABELS[e.kind]}</td>
                        <td className="text-right tabular-nums">{e.quantity > 0 ? `+${e.quantity}` : e.quantity}</td>
                        <td className="font-mono text-xs">{e.documentCode ?? '—'}</td>
                        {withContact && <td>{e.petName ? `${e.petName}${e.customerName ? ` — ${e.customerName}` : ''}` : '—'}</td>}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  );
}
