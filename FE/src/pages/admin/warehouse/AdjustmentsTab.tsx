import { useState } from 'react';
import { Plus } from 'lucide-react';
import { EmptyRow, NoticeBar, type Notice } from '../../../components/admin/form-ui';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { Input } from '../../../components/ui/input';
import { DetailModal } from '../../../components/ui/modal-templates';
import { formatDateTime } from '../../../lib/utils';
import { useAdjustments } from '../../../shared/hooks/useInventory';
import { ADJUSTMENT_REASON_LABELS, type StockAdjustment } from '../../../shared/types/inventory';
import { AdjustmentForm } from './AdjustmentForm';

const PAGE_SIZE = 20;

/** UC76 — phiếu điều chỉnh tồn của chi nhánh, mới nhất trước; không sửa / hủy được vì có hiệu lực ngay (BR-KO-06). */
export function AdjustmentsTab() {
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);
  const adjustments = useAdjustments({ from: from || undefined, to: to || undefined, page, size: PAGE_SIZE });
  const [creating, setCreating] = useState(false);
  const [viewing, setViewing] = useState<StockAdjustment | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const data = adjustments.data;

  return (
    <div className="space-y-4">
      <Card className="card">
        <CardContent className="p-4 flex flex-wrap items-center gap-3">
          <Input
            aria-label="Từ ngày"
            type="date"
            className="w-40"
            value={from}
            onChange={(e) => {
              setFrom(e.target.value);
              setPage(0);
            }}
          />
          <Input
            aria-label="Đến ngày"
            type="date"
            className="w-40"
            value={to}
            onChange={(e) => {
              setTo(e.target.value);
              setPage(0);
            }}
          />
          <div className="flex-1" />
          <Button onClick={() => setCreating(true)}>
            <Plus className="h-4 w-4" />
            Lập phiếu điều chỉnh
          </Button>
        </CardContent>
      </Card>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead>
              <tr>
                <th>Mã phiếu</th>
                <th>Thời điểm</th>
                <th>Lý do</th>
                <th className="text-right">Số dòng</th>
                <th>Ghi chú</th>
                <th className="text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {data?.content.length ? (
                data.content.map((a) => (
                  <tr key={a.adjustmentId}>
                    <td className="font-mono text-xs">{a.code}</td>
                    <td>{formatDateTime(a.createdAt)}</td>
                    <td>{ADJUSTMENT_REASON_LABELS[a.reason]}</td>
                    <td className="text-right tabular-nums">{a.lines.length}</td>
                    <td className="text-(--text-secondary)">{a.note ?? '—'}</td>
                    <td className="text-right">
                      <Button size="sm" variant="outline" onClick={() => setViewing(a)} aria-label={`Xem phiếu ${a.code}`}>
                        Xem
                      </Button>
                    </td>
                  </tr>
                ))
              ) : (
                <EmptyRow colSpan={6} loading={adjustments.isLoading} />
              )}
            </tbody>
          </table>
          {data && data.totalPages > 1 && (
            <div className="flex items-center justify-end gap-3 border-t border-(--border-color) px-4 py-3 text-sm text-(--text-secondary)">
              <span>
                Trang {data.page + 1}/{data.totalPages} · {data.totalElements} phiếu
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

      {creating && (
        <AdjustmentForm
          onClose={() => setCreating(false)}
          onSaved={(saved) => {
            setCreating(false);
            setNotice({ kind: 'success', text: `Đã lập phiếu điều chỉnh ${saved.code}` });
          }}
        />
      )}
      {viewing && (
        <DetailModal open onOpenChange={(open) => !open && setViewing(null)} title={`Phiếu điều chỉnh ${viewing.code}`} size="lg">
          <p className="mb-3 text-sm text-(--text-secondary)">
            {ADJUSTMENT_REASON_LABELS[viewing.reason]} · {formatDateTime(viewing.createdAt)}
            {viewing.note && ` · ${viewing.note}`}
          </p>
          <table className="table">
            <thead>
              <tr>
                <th>Sản phẩm</th>
                <th>Lô</th>
                <th className="text-right">Tăng / giảm</th>
                <th className="text-right">Còn lại</th>
              </tr>
            </thead>
            <tbody>
              {viewing.lines.map((l, i) => (
                <tr key={i}>
                  <td>{l.productName}</td>
                  <td className="font-mono text-xs">{l.lotNumber ?? 'Lô mặc định'}</td>
                  <td className="text-right tabular-nums">{l.quantityDelta > 0 ? `+${l.quantityDelta}` : l.quantityDelta}</td>
                  <td className="text-right tabular-nums">{l.balanceAfter}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </DetailModal>
      )}
    </div>
  );
}
