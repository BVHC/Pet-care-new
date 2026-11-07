import { useState } from 'react';
import { Trash2 } from 'lucide-react';
import { FormError, selectClass, type Notice } from '../../../components/admin/form-ui';
import { QueryError, RowsSkeleton } from '../../../components/staff/ui';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { Input } from '../../../components/ui/input';
import { cn } from '../../../lib/utils';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { feesApi } from '../../../shared/api/fees.api';
import { useFeesMutation, useMaterialNorms } from '../../../shared/hooks/useFees';
import { useProducts, useServices } from '../../../shared/hooks/useCatalog';
import type { MaterialNorm } from '../../../shared/types/fees';

interface Draft {
  productId: number;
  name: string;
  unit: string;
  /** Chuỗi để gõ được ô trống; ô trống chặn ở FE, còn 0 / âm / thập phân để máy chủ từ chối (BR-SP-10). */
  quantity: string;
}

const draftOf = (norms: MaterialNorm[]): Draft[] => norms.map((n) => ({ productId: n.productId, name: n.productName, unit: n.unit, quantity: String(n.quantity) }));

/** Bảng của một dịch vụ. Chỉ gắn sau khi định mức đã tải (nháp nhập một lần từ máy chủ, `key` theo dịch vụ) nên lần làm mới do lệnh ghi khác không xóa phần đang sửa. */
function NormsEditor({ serviceId, norms, onNotice }: { serviceId: number; norms: MaterialNorm[]; onNotice: (notice: Notice | null) => void }) {
  const [rows, setRows] = useState<Draft[]>(() => draftOf(norms));
  const [adding, setAdding] = useState('');
  const [error, setError] = useState('');
  // shortcut: lấy tối đa 100 sản phẩm đầu của danh mục (giới hạn của trang); thêm ô tìm khi danh mục quá 100 sản phẩm.
  const products = useProducts({ isActive: true, size: 100 });
  const save = useFeesMutation((items: { productId: number; quantity: number }[]) => feesApi.saveMaterialNorms(serviceId, { items }));
  // Vaccine không đưa vào định mức (đã trừ kho khi tiêm, BR-SP-10); sản phẩm đã có trong bảng không chọn lại (tránh trùng).
  const choices = (products.data?.content ?? []).filter((p) => p.productType !== 'VACCINE' && !rows.some((r) => r.productId === p.productId));

  const edit = (productId: number, quantity: string) => {
    setRows(rows.map((r) => (r.productId === productId ? { ...r, quantity } : r)));
    setError('');
  };
  const add = (value: string) => {
    const p = choices.find((x) => x.productId === Number(value));
    if (p) setRows([...rows, { productId: p.productId, name: p.name, unit: p.unit, quantity: '1' }]);
    setAdding('');
  };
  const submit = async () => {
    onNotice(null);
    const blank = rows.find((r) => r.quantity.trim() === '');
    if (blank) return setError(`Nhập số lượng cho ${blank.name}.`);
    setError('');
    try {
      const saved = await save.mutateAsync(rows.map((r) => ({ productId: r.productId, quantity: Number(r.quantity) })));
      setRows(draftOf(saved));
      onNotice({ kind: 'success', text: saved.length ? 'Đã lưu định mức vật tư.' : 'Đã xóa hết định mức vật tư của dịch vụ.' });
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  return (
    <div className="space-y-3">
      {rows.length === 0 ? (
        <p className="text-sm text-(--text-secondary)">Dịch vụ này chưa có định mức vật tư.</p>
      ) : (
        <table className="table w-full" aria-label="Định mức vật tư">
          <thead>
            <tr>
              <th>Sản phẩm</th>
              <th className="w-32">Số lượng mỗi lần</th>
              <th>Đơn vị</th>
              <th className="text-right!">Thao tác</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.productId}>
                <td className="font-medium text-(--text-primary)">{r.name}</td>
                <td>
                  <Input type="number" min={1} step={1} aria-label={`Số lượng ${r.name}`} value={r.quantity} onChange={(e) => edit(r.productId, e.target.value)} />
                </td>
                <td className="text-(--text-secondary)">{r.unit}</td>
                <td className="text-right">
                  <Button
                    type="button"
                    size="sm"
                    variant="ghost"
                    aria-label={`Bỏ ${r.name}`}
                    onClick={() => {
                      setRows(rows.filter((x) => x.productId !== r.productId));
                      setError('');
                    }}
                  >
                    <Trash2 className="h-4 w-4" aria-hidden />
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <div className="flex flex-wrap items-center gap-3">
        <select aria-label="Thêm vật tư" className={cn(selectClass, 'w-72')} value={adding} onChange={(e) => add(e.target.value)} disabled={products.isLoading}>
          <option value="">Thêm vật tư…</option>
          {choices.map((p) => (
            <option key={p.productId} value={p.productId}>
              {p.name} ({p.unit})
            </option>
          ))}
        </select>
        <Button type="button" onClick={submit} disabled={save.isPending}>
          {save.isPending ? 'Đang lưu...' : 'Lưu định mức'}
        </Button>
      </div>
      <FormError message={error} />
    </div>
  );
}

/** #5/#6 Định mức vật tư theo dịch vụ (BR-SP-10): số lượng sản phẩm hao mỗi lần thực hiện dịch vụ, lưu đè toàn bộ; danh sách rỗng = xóa hết. */
export function MaterialNormsCard({ onNotice }: { onNotice: (notice: Notice | null) => void }) {
  const [serviceId, setServiceId] = useState<number | null>(null);
  const services = useServices();
  const norms = useMaterialNorms(serviceId);

  return (
    <Card className="card">
      <CardContent className="space-y-4 p-5">
        <div>
          <h2 className="text-lg font-semibold text-(--text-primary)">Định mức vật tư theo dịch vụ</h2>
          <p className="text-sm text-(--text-secondary)">Hệ thống trừ kho theo định mức mỗi lần hoàn tất dịch vụ. Vaccine không đưa vào định mức.</p>
        </div>
        <select
          aria-label="Chọn dịch vụ"
          className={cn(selectClass, 'w-80')}
          value={serviceId ?? ''}
          onChange={(e) => {
            setServiceId(e.target.value ? Number(e.target.value) : null);
            onNotice(null);
          }}
        >
          <option value="">Chọn dịch vụ…</option>
          {services.data?.map((s) => (
            <option key={s.serviceId} value={s.serviceId}>
              {s.name}
              {s.isActive ? '' : ' (ngừng)'}
            </option>
          ))}
        </select>
        {serviceId != null &&
          (norms.data ? (
            <NormsEditor key={serviceId} serviceId={serviceId} norms={norms.data} onNotice={onNotice} />
          ) : norms.isError ? (
            <QueryError error={norms.error} onRetry={() => norms.refetch()} what="định mức vật tư" />
          ) : (
            <RowsSkeleton rows={2} />
          ))}
      </CardContent>
    </Card>
  );
}
