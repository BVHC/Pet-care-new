import { useState } from 'react';
import { AlertTriangle, Ban, PauseCircle, PlayCircle } from 'lucide-react';
import { NoticeBar, type Notice } from '../../../components/admin/form-ui';
import { QueryError } from '../../../components/staff/ui';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { branchlifeApi } from '../../../shared/api/branchlife.api';
import { useBranchLifeMutation, useClosureImpact } from '../../../shared/hooks/useBranchLife';
import type { Branch } from '../../../shared/types/branch';
import type { ClosureImpact } from '../../../shared/types/branchlife';
import { LifecycleDialog } from './LifecycleDialog';

/** Nhãn từng số đếm của #1; thứ tự theo mức độ ảnh hưởng tới việc tạm ngừng / đóng cửa (BR-CN-07). */
const COUNTS: { key: keyof ClosureImpact; label: string }[] = [
  { key: 'bookedAppointments', label: 'Lịch hẹn và đặt chỗ lưu trú đang chờ' },
  { key: 'activeBoardingStays', label: 'Thú đang trong chuồng lưu trú' },
  { key: 'openVisits', label: 'Lượt khám đang mở' },
  { key: 'openCashierShifts', label: 'Ca thu ngân đang mở' },
  { key: 'openOrders', label: 'Order chưa thanh toán' },
  { key: 'unfinishedOnlineOrders', label: 'Đơn online chưa hoàn tất' },
  { key: 'openCareTasks', label: 'Việc gọi điện đang chờ' },
  { key: 'pendingRefunds', label: 'Hoàn tiền chưa thành công' },
  { key: 'inTransitTransfers', label: 'Phiếu chuyển kho đang vận chuyển' },
  { key: 'openStockCounts', label: 'Phiên kiểm kê đang mở' },
  { key: 'stockItemCount', label: 'Mặt hàng còn tồn kho' },
  { key: 'stockTotalQuantity', label: 'Tổng số lượng tồn kho' },
  { key: 'activeStaff', label: 'Nhân viên đang hoạt động' },
];

/**
 * UC13 — vòng đời chi nhánh (chỉ SUPER_MANAGER): xem trước tồn đọng, tạm ngừng, mở lại, đóng cửa vĩnh viễn.
 * Số liệu tính lúc xem; BE kiểm lại khi ghi nên hộp thao tác vẫn hiện lỗi nếu tồn đọng đổi (BR-CN-08, BR-CN-09).
 */
export function LifecycleTab({ branch }: { branch: Branch }) {
  const impact = useClosureImpact(branch.branchId);
  const [dialog, setDialog] = useState<'suspend' | 'close' | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const resume = useBranchLifeMutation(() => branchlifeApi.resume(branch.branchId));
  const { status } = branch;

  const onResume = async () => {
    setNotice(null);
    try {
      await resume.mutateAsync(undefined);
      setNotice({ kind: 'success', text: `Đã mở lại chi nhánh ${branch.name}` });
    } catch (e) {
      setNotice({ kind: 'error', text: plainMessage(toApiError(e)) });
    }
  };

  const data = impact.data;
  return (
    <div className="space-y-4">
      <Card className="card">
        <CardContent className="flex flex-wrap items-center gap-3 p-4">
          <p className="flex-1 text-sm text-(--text-secondary)">
            {status === 'ACTIVE' && 'Tạm ngừng để chi nhánh ngưng nhận lịch mới nhưng giữ dữ liệu; đóng cửa là vĩnh viễn.'}
            {status === 'SUSPENDED' && 'Chi nhánh đang tạm ngừng: không nhận lịch hẹn, đặt chỗ hay đơn mới. Mở lại khi đã sẵn sàng, hoặc đóng cửa vĩnh viễn.'}
            {status === 'DRAFT' && 'Chi nhánh còn ở trạng thái nháp: bấm "Kích hoạt" ở đầu trang trước khi có thể tạm ngừng hay đóng cửa.'}
            {status === 'CLOSED' && 'Chi nhánh đã đóng cửa vĩnh viễn, không mở lại được. Dữ liệu cũ vẫn được giữ để tra cứu.'}
          </p>
          {status === 'ACTIVE' && (
            <Button variant="outline" onClick={() => setDialog('suspend')} disabled={!data}>
              <PauseCircle className="h-4 w-4" />
              Tạm ngừng chi nhánh
            </Button>
          )}
          {status === 'SUSPENDED' && (
            <Button onClick={onResume} disabled={resume.isPending}>
              <PlayCircle className="h-4 w-4" />
              Mở lại chi nhánh
            </Button>
          )}
          {(status === 'ACTIVE' || status === 'SUSPENDED') && (
            <Button variant="outline" className="border-red-200 text-red-600" onClick={() => setDialog('close')} disabled={!data}>
              <Ban className="h-4 w-4" />
              Đóng cửa vĩnh viễn
            </Button>
          )}
        </CardContent>
      </Card>

      <NoticeBar notice={notice} />

      {impact.isError && <QueryError error={impact.error} onRetry={() => impact.refetch()} what="tồn đọng của chi nhánh" />}
      {data && (
        <div className="grid gap-4 lg:grid-cols-2">
          <Card className="card">
            <CardContent className="p-5">
              <h3 className="mb-3 font-semibold text-(--text-primary)">Tồn đọng của chi nhánh</h3>
              <table className="table" aria-label="Tồn đọng của chi nhánh">
                <tbody>
                  {COUNTS.map(({ key, label }) => (
                    <tr key={key}>
                      <td>{label}</td>
                      <td className="text-right font-medium tabular-nums">{data[key] as number}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </CardContent>
          </Card>
          <div className="space-y-4">
            <Blockers title="Điều kiện chặn tạm ngừng" items={data.suspendBlockers} />
            <Blockers title="Điều kiện chặn đóng cửa" items={data.closeBlockers} />
          </div>
        </div>
      )}

      {dialog && data && (
        <LifecycleDialog
          mode={dialog}
          branch={branch}
          impact={data}
          onClose={() => setDialog(null)}
          onDone={(text) => {
            setDialog(null);
            setNotice({ kind: 'success', text });
          }}
        />
      )}
    </div>
  );
}

function Blockers({ title, items }: { title: string; items: string[] }) {
  return (
    <Card className="card">
      <CardContent className="p-5">
        <h3 className="mb-2 font-semibold text-(--text-primary)">{title}</h3>
        {items.length === 0 ? (
          <p className="text-sm text-green-700">Không có: làm được ngay.</p>
        ) : (
          <ul aria-label={title} className="space-y-1.5 text-sm text-red-700">
            {items.map((text) => (
              <li key={text} className="flex gap-2">
                <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
                {text}
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}
