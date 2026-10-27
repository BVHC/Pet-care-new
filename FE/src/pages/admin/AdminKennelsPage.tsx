/**
 * Chuồng lưu trú của chi nhánh (UC54) — boarding-v1 #1–6, chỉ BRANCH_MANAGER. Chi nhánh lấy từ phiên (BE tự khóa phạm vi, không có ô chọn).
 * Tạo / sửa mã + ghi chú / xóa chuồng; chuyển bảo trì và hết bảo trì. Sức chứa = số chuồng không bảo trì (BR-LT-01).
 * Chuyển bảo trì chỉ CẢNH BÁO đặt chỗ thiếu chỗ (không tự hủy). Xóa làm giảm sức chứa ngay mà không cảnh báo (ADR-0065, mục Nợ): giao diện khuyên bảo trì.
 */
import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { toast } from 'sonner';
import { FormError, NoticeBar, selectClass } from '@/components/admin/form-ui';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { ConfirmModal } from '@/components/ui/modal-templates';
import { cn } from '@/lib/utils';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { useKennels, useRefreshBoarding } from '@/shared/hooks/useBoarding';
import { useSession } from '@/shared/stores/session.store';
import { KENNEL_STATUSES, KENNEL_STATUS_LABELS, type Kennel, type KennelQuery, type KennelStatus } from '@/shared/types/boarding';
import { summarizeCapacity } from './kennels/capacity';
import { useDeleteKennel, useEndMaintenance, useKennelTypes, useStartMaintenance, useStayLookup } from './kennels/hooks';
import { KennelFormDialog, type KennelFormMode } from './kennels/KennelFormDialog';
import { KennelSummary } from './kennels/KennelSummary';
import { KennelTable } from './kennels/KennelTable';
import { MaintenanceNotice, type MaintenanceOutcome } from './kennels/MaintenanceNotice';

type Dialog = { kind: 'form'; mode: KennelFormMode } | { kind: 'maintenance'; kennel: Kennel } | { kind: 'delete'; kennel: Kennel };

export function AdminKennelsPage() {
  const account = useSession((s) => s.account);
  const isManager = account?.role === 'BRANCH_MANAGER';
  const branchId = isManager ? (account?.branchId ?? null) : null;

  const [typeFilter, setTypeFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState<KennelStatus | ''>('');
  const filter: KennelQuery = {
    ...(typeFilter ? { kennelTypeId: Number(typeFilter) } : {}),
    ...(statusFilter ? { status: statusFilter } : {}),
  };
  const filtered = !!typeFilter || !!statusFilter;
  // Danh sách chưa lọc nuôi dải sức chứa và bộ lọc loại; không lọc thì hai truy vấn trùng khóa nên chỉ gọi một lần.
  const all = useKennels({}, isManager);
  const list = useKennels(filter, isManager);
  const { types, isLoading: typesLoading, isError: typesError, error: typesErr } = useKennelTypes(branchId);
  const stays = useStayLookup(isManager && !!all.data?.some((k) => k.currentBookingId != null));
  const capacity = useMemo(() => summarizeCapacity(all.data ?? [], types), [all.data, types]);

  const refresh = useRefreshBoarding();
  const startMaintenance = useStartMaintenance();
  const endMaintenance = useEndMaintenance();
  const remove = useDeleteKennel();
  const busy = startMaintenance.isPending || endMaintenance.isPending || remove.isPending;
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [error, setError] = useState('');
  const [maintenance, setMaintenance] = useState<MaintenanceOutcome | null>(null);

  const canCreate = types.length > 0;
  const capacityOf = (typeId: number) => capacity.find((r) => r.kennelTypeId === typeId)?.capacity ?? 0;

  /** Chạy một lệnh ghi. 404 / 409 nghĩa là dữ liệu đã đổi sau khi trang tải: hiện câu của BE rồi làm mới để hàng đúng với BE. */
  async function run(action: () => Promise<void>) {
    setError('');
    setMaintenance((m) => (m?.shortageBookings.length ? m : null));
    try {
      await action();
    } catch (e) {
      const err = toApiError(e);
      setError(plainMessage(err));
      toast.error(plainMessage(err));
      if (err.statusCode === 404 || err.statusCode === 409) void refresh();
    } finally {
      // Chỉ đóng hộp xác nhận của lệnh vừa chạy; hộp Tạo / Sửa mở trong lúc lệnh chạy nền (Hết bảo trì không có hộp) thì giữ.
      setDialog((d) => (d?.kind === 'form' ? d : null));
    }
  }

  const doStartMaintenance = (k: Kennel) =>
    run(async () => {
      const result = await startMaintenance.mutateAsync(k.kennelId);
      setMaintenance({ kennelId: k.kennelId, code: result.kennel.code, shortageBookings: result.shortageBookings });
      toast.success(`Đã chuyển chuồng ${k.code} sang bảo trì`);
    });
  const doEndMaintenance = (k: Kennel) =>
    run(async () => {
      await endMaintenance.mutateAsync(k.kennelId);
      setMaintenance((m) => (m?.kennelId === k.kennelId ? null : m));
      toast.success(`Đã hết bảo trì chuồng ${k.code}`);
    });
  const doDelete = (k: Kennel) =>
    run(async () => {
      await remove.mutateAsync(k.kennelId);
      setMaintenance((m) => (m?.kennelId === k.kennelId ? null : m));
      toast.success(`Đã xóa chuồng ${k.code}`);
    });

  const clearFilter = () => {
    setTypeFilter('');
    setStatusFilter('');
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-(--text-primary)">Chuồng lưu trú</h1>
          <p className="text-(--text-secondary)">Chuồng của chi nhánh bạn quản lý. Sức chứa mỗi loại = số chuồng không bảo trì (BR-LT-01).</p>
        </div>
        {isManager && (
          <Button
            className="h-10"
            disabled={!canCreate}
            title={canCreate ? undefined : 'Chưa có loại chuồng lưu trú nào đang bật tại chi nhánh'}
            onClick={() => setDialog({ kind: 'form', mode: { kind: 'create' } })}
          >
            <Plus className="h-4 w-4" aria-hidden /> Tạo chuồng
          </Button>
        )}
      </div>

      {!isManager ? (
        <FormError message="Chỉ quản lý chi nhánh được quản lý chuồng lưu trú." />
      ) : (
        <>
          {branchId == null && <FormError message="Phiên đăng nhập không có chi nhánh nên không tải được loại chuồng. Hãy đăng nhập lại." />}
          {typesError && <FormError message={plainMessage(toApiError(typesErr))} />}
          {branchId != null && !typesLoading && !typesError && !canCreate && (
            <p role="status" className="rounded-lg bg-amber-50 px-4 py-3 text-sm text-amber-900">
              Chưa có loại chuồng lưu trú nào đang bật tại chi nhánh nên chưa tạo được chuồng. Loại chuồng lấy từ dịch vụ lưu trú công khai: dịch vụ nhóm Lưu trú,
              đã bật tại chi nhánh và chi nhánh đã kích hoạt. Bật ở{' '}
              <Link to={`/admin/branches/${branchId}/services`} className="font-medium underline">
                Dịch vụ của chi nhánh
              </Link>
              .
            </p>
          )}
          {error && <NoticeBar notice={{ kind: 'error', text: error }} />}
          {maintenance && <MaintenanceNotice outcome={maintenance} onDismiss={() => setMaintenance(null)} />}

          <KennelSummary rows={capacity} />

          <Card>
            <CardContent className="flex flex-wrap items-center gap-3 p-4">
              <select aria-label="Lọc loại chuồng" className={cn(selectClass, 'w-52')} value={typeFilter} onChange={(e) => setTypeFilter(e.target.value)}>
                <option value="">Mọi loại chuồng</option>
                {capacity.map((r) => (
                  <option key={r.kennelTypeId} value={r.kennelTypeId}>
                    {r.name}
                  </option>
                ))}
              </select>
              <select
                aria-label="Lọc trạng thái"
                className={cn(selectClass, 'w-44')}
                value={statusFilter}
                onChange={(e) => setStatusFilter(e.target.value as KennelStatus | '')}
              >
                <option value="">Mọi trạng thái</option>
                {KENNEL_STATUSES.map((s) => (
                  <option key={s} value={s}>
                    {KENNEL_STATUS_LABELS[s]}
                  </option>
                ))}
              </select>
            </CardContent>
          </Card>

          {list.isError ? (
            <Card>
              <CardContent className="space-y-3 p-4">
                <FormError message={plainMessage(toApiError(list.error))} />
                <Button
                  variant="outline"
                  className="h-10"
                  onClick={() => {
                    void all.refetch();
                    void list.refetch();
                  }}
                >
                  Thử lại
                </Button>
              </CardContent>
            </Card>
          ) : list.isLoading ? (
            <Card>
              <CardContent className="py-8 text-center text-(--text-tertiary)">Đang tải…</CardContent>
            </Card>
          ) : list.data && list.data.length > 0 ? (
            <KennelTable
              kennels={list.data}
              stays={stays}
              busy={busy}
              onEdit={(k) => setDialog({ kind: 'form', mode: { kind: 'edit', kennel: k } })}
              onStartMaintenance={(k) => setDialog({ kind: 'maintenance', kennel: k })}
              onEndMaintenance={(k) => void doEndMaintenance(k)}
              onDelete={(k) => setDialog({ kind: 'delete', kennel: k })}
            />
          ) : (
            <Card>
              <CardContent className="space-y-3 py-10 text-center">
                {filtered ? (
                  <>
                    <p className="font-medium text-(--text-primary)">Không có chuồng nào khớp bộ lọc</p>
                    <Button variant="outline" className="h-10" onClick={clearFilter}>
                      Xóa bộ lọc
                    </Button>
                  </>
                ) : (
                  <>
                    <p className="font-medium text-(--text-primary)">Chi nhánh chưa có chuồng nào</p>
                    <p className="text-sm text-(--text-secondary)">
                      {canCreate
                        ? 'Tạo chuồng đầu tiên để khách có thể đặt chỗ lưu trú.'
                        : 'Cần có ít nhất một loại chuồng lưu trú đang bật tại chi nhánh trước khi tạo chuồng.'}
                    </p>
                    {canCreate && (
                      <Button className="h-10" onClick={() => setDialog({ kind: 'form', mode: { kind: 'create' } })}>
                        <Plus className="h-4 w-4" aria-hidden /> Tạo chuồng đầu tiên
                      </Button>
                    )}
                  </>
                )}
              </CardContent>
            </Card>
          )}
        </>
      )}

      {dialog?.kind === 'form' && <KennelFormDialog mode={dialog.mode} types={types} onClose={() => setDialog(null)} />}
      {dialog?.kind === 'maintenance' && (
        <ConfirmModal
          open
          type="warning"
          onOpenChange={(open) => !open && setDialog(null)}
          title={`Chuyển ${dialog.kennel.code} sang bảo trì?`}
          description={`Sức chứa ${dialog.kennel.kennelTypeName} giảm từ ${capacityOf(dialog.kennel.kennelTypeId)} xuống ${capacityOf(dialog.kennel.kennelTypeId) - 1}. Nếu thiếu chỗ cho đặt chỗ đã có, hệ thống chỉ cảnh báo, không tự hủy đặt chỗ nào (BR-LT-01).`}
          confirmText="Chuyển bảo trì"
          loading={startMaintenance.isPending}
          onConfirm={() => void doStartMaintenance(dialog.kennel)}
        />
      )}
      {dialog?.kind === 'delete' && (
        <ConfirmModal
          open
          type="danger"
          onOpenChange={(open) => !open && setDialog(null)}
          title={`Xóa chuồng ${dialog.kennel.code}?`}
          description={deleteText(dialog.kennel, capacityOf(dialog.kennel.kennelTypeId))}
          confirmText="Xóa chuồng"
          loading={remove.isPending}
          onConfirm={() => void doDelete(dialog.kennel)}
        />
      )}
    </div>
  );
}

/** ADR-0065 Nợ: xóa chuồng trống làm giảm sức chứa ngay mà không có cảnh báo, nên khuyên bảo trì. Chuồng đang bảo trì vốn không tính vào sức chứa. */
function deleteText(k: Kennel, capacity: number): string {
  if (k.status === 'MAINTENANCE') {
    return `Chuồng ${k.code} đang bảo trì nên không tính vào sức chứa; xóa không đổi sức chứa nhưng không hoàn tác được. Nếu sau này còn dùng lại, hãy giữ ở trạng thái bảo trì rồi chọn "Hết bảo trì".`;
  }
  return `Xóa chuồng ${k.code} làm giảm sức chứa ${k.kennelTypeName} ngay, từ ${capacity} xuống ${capacity - 1}, và hệ thống không cảnh báo đặt chỗ nào bị thiếu chỗ. Nên chuyển bảo trì thay vì xóa: bảo trì có cảnh báo và hoàn tác được. Chuồng đã có lịch sử lưu trú không xóa được.`;
}
