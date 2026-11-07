import { useState } from 'react';
import { Plus } from 'lucide-react';
import { EmptyRow, NoticeBar, selectClass, type Notice } from '../../../components/admin/form-ui';
import { QueryError } from '../../../components/staff/ui';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { StatusBadge } from '../../../components/ui/modal-templates';
import { cn, formatDateTime } from '../../../lib/utils';
import { useStaffBranch } from '../../../shared/hooks/useStaffBranch';
import { usePublicBranches } from '../../../shared/hooks/usePublicSite';
import { useStockTransfers } from '../../../shared/hooks/useStockOps';
import {
  STOCK_TRANSFER_STATUSES,
  STOCK_TRANSFER_STATUS_LABELS,
  type StockTransferStatus,
  type TransferDirection,
} from '../../../shared/types/stockops';
import { Pager } from './Pager';
import { TRANSFER_STATUS_VARIANT } from './transfer-ui';
import { TransferDetail } from './TransferDetail';
import { TransferForm } from './TransferForm';

const PAGE_SIZE = 20;

/** UC77 — phiếu chuyển kho của chi nhánh mình (đi và đến): tạo phiếu, mở xem, nhận hàng, hủy. Chỉ BRANCH_MANAGER. */
export function TransfersTab() {
  const { branchId: mine } = useStaffBranch();
  const [direction, setDirection] = useState<TransferDirection | ''>('');
  const [status, setStatus] = useState<StockTransferStatus | ''>('');
  const [page, setPage] = useState(0);
  const [openId, setOpenId] = useState<number | null>(null);
  const [creating, setCreating] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  const transfers = useStockTransfers({ direction: direction || undefined, status: status || undefined, page, size: PAGE_SIZE });
  const branches = usePublicBranches();
  // Phiếu tóm tắt chỉ có id chi nhánh; chi nhánh chưa ACTIVE không có trong danh sách công khai nên hiện theo số.
  const nameOf = (id: number) => branches.data?.find((b) => b.branchId === id)?.name ?? `Chi nhánh #${id}`;
  const data = transfers.data;

  return (
    <div className="space-y-4">
      <Card className="card">
        <CardContent className="flex flex-wrap items-center gap-3 p-4">
          <select
            aria-label="Lọc hướng chuyển"
            className={cn(selectClass, 'w-44')}
            value={direction}
            onChange={(e) => {
              setDirection(e.target.value as TransferDirection | '');
              setPage(0);
            }}
          >
            <option value="">Cả đi và đến</option>
            <option value="OUTGOING">Chuyển đi</option>
            <option value="INCOMING">Chuyển đến</option>
          </select>
          <select
            aria-label="Lọc trạng thái phiếu chuyển"
            className={cn(selectClass, 'w-44')}
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as StockTransferStatus | '');
              setPage(0);
            }}
          >
            <option value="">Mọi trạng thái</option>
            {STOCK_TRANSFER_STATUSES.map((s) => (
              <option key={s} value={s}>
                {STOCK_TRANSFER_STATUS_LABELS[s]}
              </option>
            ))}
          </select>
          <div className="flex-1" />
          <Button onClick={() => setCreating(true)}>
            <Plus className="h-4 w-4" />
            Tạo phiếu chuyển kho
          </Button>
        </CardContent>
      </Card>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="overflow-hidden p-0">
          {!data && transfers.isError ? (
            <QueryError error={transfers.error} onRetry={() => transfers.refetch()} what="phiếu chuyển kho" />
          ) : (
            <table className="table" aria-label="Phiếu chuyển kho">
              <thead>
                <tr>
                  <th>Mã phiếu</th>
                  <th>Hướng</th>
                  <th>Chi nhánh đối tác</th>
                  <th className="text-right">Số dòng</th>
                  <th>Tạo lúc</th>
                  <th>Trạng thái</th>
                  <th className="text-right">Thao tác</th>
                </tr>
              </thead>
              <tbody>
                {data?.content.length ? (
                  data.content.map((t) => {
                    const outgoing = t.sourceBranchId === mine;
                    return (
                      <tr key={t.transferId}>
                        <td className="font-mono text-xs">{t.code}</td>
                        <td>{outgoing ? 'Chuyển đi' : 'Chuyển đến'}</td>
                        <td className="font-medium text-(--text-primary)">{nameOf(outgoing ? t.destBranchId : t.sourceBranchId)}</td>
                        <td className="text-right tabular-nums">{t.lineCount}</td>
                        <td>{formatDateTime(t.createdAt)}</td>
                        <td>
                          <StatusBadge status={STOCK_TRANSFER_STATUS_LABELS[t.status]} variant={TRANSFER_STATUS_VARIANT[t.status]} />
                        </td>
                        <td className="text-right">
                          <Button size="sm" variant="outline" onClick={() => setOpenId(t.transferId)} aria-label={`Mở phiếu ${t.code}`}>
                            Mở
                          </Button>
                        </td>
                      </tr>
                    );
                  })
                ) : (
                  <EmptyRow colSpan={7} loading={transfers.isLoading} />
                )}
              </tbody>
            </table>
          )}
          {data && <Pager data={data} noun="phiếu" onPage={setPage} />}
        </CardContent>
      </Card>

      {openId != null && (
        <TransferDetail transferId={openId} myBranchId={mine} onClose={() => setOpenId(null)} onDone={(text) => setNotice({ kind: 'success', text })} />
      )}
      {creating && (
        <TransferForm
          myBranchId={mine}
          onClose={() => setCreating(false)}
          onSaved={(saved) => {
            setCreating(false);
            setNotice({ kind: 'success', text: `Đã tạo phiếu chuyển kho ${saved.code}` });
          }}
        />
      )}
    </div>
  );
}
