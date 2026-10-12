import { useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { Edit, Plus, Power, Settings } from 'lucide-react';
import { Button } from '../../components/ui/button';
import { Card, CardContent } from '../../components/ui/card';
import { StatusBadge } from '../../components/ui/modal-templates';
import { EmptyRow, NoticeBar, selectClass, type Notice } from '../../components/admin/form-ui';
import { cn } from '../../lib/utils';
import { useBranches } from '../../shared/hooks/useBranch';
import { useAccount } from '../../shared/stores/session.store';
import { BRANCH_STATUS_LABELS, type Branch, type BranchStatus } from '../../shared/types/branch';
import { ActivateBranchModal } from './branch/ActivateBranchModal';
import { BranchFormModal } from './branch/BranchFormModal';

/**
 * T20 — danh sách chi nhánh (UC12). SUPER_MANAGER tạo, sửa, kích hoạt; BRANCH_MANAGER chỉ thấy chi nhánh của mình
 * (GET /branches trả đúng một chi nhánh) nên vào thẳng trang cấu hình.
 */
export function AdminBranchesPage() {
  const account = useAccount();
  const navigate = useNavigate();
  const branches = useBranches();
  const [status, setStatus] = useState<BranchStatus | ''>('');
  const [editing, setEditing] = useState<Branch | 'new' | null>(null);
  const [activating, setActivating] = useState<Branch | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);

  if (account?.role === 'BRANCH_MANAGER' && branches.data?.length === 1) {
    return <Navigate to={`/admin/branches/${branches.data[0].branchId}`} replace />;
  }

  const canManage = account?.role === 'SUPER_MANAGER';
  const rows = (branches.data ?? []).filter((b) => !status || b.status === status);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Chi nhánh</h1>
        <p className="text-(--text-secondary)">
          Tạo, sửa, kích hoạt chi nhánh. Giờ mở cửa, ngày nghỉ, dịch vụ và quota do quản lý của từng chi nhánh cấu hình.
        </p>
      </div>

      <Card className="card">
        <CardContent className="p-4 flex flex-wrap items-center justify-between gap-3">
          <select
            aria-label="Lọc trạng thái"
            className={cn(selectClass, 'w-48')}
            value={status}
            onChange={(e) => setStatus(e.target.value as BranchStatus | '')}
          >
            <option value="">Mọi trạng thái</option>
            <option value="DRAFT">{BRANCH_STATUS_LABELS.DRAFT}</option>
            <option value="ACTIVE">{BRANCH_STATUS_LABELS.ACTIVE}</option>
          </select>
          {canManage && (
            <Button onClick={() => setEditing('new')}>
              <Plus className="h-4 w-4" />
              Thêm chi nhánh
            </Button>
          )}
        </CardContent>
      </Card>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead>
              <tr>
                <th>Tên chi nhánh</th>
                <th>Địa chỉ</th>
                <th>Số điện thoại</th>
                <th>Trạng thái</th>
                <th>Cấp cứu ngoài giờ</th>
                <th className="text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {rows.length ? (
                rows.map((b) => (
                  <tr key={b.branchId}>
                    <td className="font-medium text-(--text-primary)">{b.name}</td>
                    <td className="text-(--text-secondary)">{b.address}</td>
                    <td className="text-(--text-secondary)">{b.phone}</td>
                    <td>
                      <StatusBadge status={BRANCH_STATUS_LABELS[b.status]} variant={b.status === 'ACTIVE' ? 'success' : 'neutral'} />
                    </td>
                    <td>{b.acceptsAfterHoursEmergency ? 'Có' : '—'}</td>
                    <td>
                      <div className="flex justify-end gap-1">
                        <Button size="sm" variant="ghost" onClick={() => navigate(`/admin/branches/${b.branchId}`)} aria-label={`Cấu hình ${b.name}`} title="Giờ mở cửa, ngày nghỉ, dịch vụ">
                          <Settings className="h-4 w-4" />
                        </Button>
                        {canManage && (
                          <Button size="sm" variant="ghost" onClick={() => setEditing(b)} aria-label={`Sửa ${b.name}`} title="Sửa">
                            <Edit className="h-4 w-4" />
                          </Button>
                        )}
                        {canManage && b.status === 'DRAFT' && (
                          <Button size="sm" variant="ghost" onClick={() => setActivating(b)} aria-label={`Kích hoạt ${b.name}`} title="Kích hoạt">
                            <Power className="h-4 w-4" />
                          </Button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))
              ) : (
                <EmptyRow colSpan={6} loading={branches.isLoading} />
              )}
            </tbody>
          </table>
        </CardContent>
      </Card>

      {editing && (
        <BranchFormModal
          branch={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={(text) => {
            setEditing(null);
            setNotice({ kind: 'success', text });
          }}
        />
      )}
      <ActivateBranchModal branch={activating} onClose={() => setActivating(null)} onDone={setNotice} />
    </div>
  );
}
