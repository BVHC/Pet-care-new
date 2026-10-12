import { useState, type ReactNode } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Edit, HeartPulse, MapPin, Phone, Power } from 'lucide-react';
import { Button } from '../../components/ui/button';
import { StatusBadge } from '../../components/ui/modal-templates';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../../components/ui/tabs';
import { NoticeBar, type Notice } from '../../components/admin/form-ui';
import { plainMessage, toApiError } from '../../shared/api/api-error';
import { useBranch } from '../../shared/hooks/useBranch';
import { useAccount } from '../../shared/stores/session.store';
import { BRANCH_STATUS_LABELS, type Branch } from '../../shared/types/branch';
import { ActivateBranchModal } from './branch/ActivateBranchModal';
import { BranchFormModal } from './branch/BranchFormModal';
import { BranchServicesTab } from './branch/BranchServicesTab';
import { HolidaysTab } from './branch/HolidaysTab';
import { OpeningHoursTab } from './branch/OpeningHoursTab';
import { QuotaTab } from './branch/QuotaTab';

interface TabDef {
  key: string;
  label: string;
  /** Quota chỉ BRANCH_MANAGER đọc được (SUPER_MANAGER bị 403). */
  managerOnly?: boolean;
  render: (branch: Branch, canEdit: boolean) => ReactNode;
}

const TABS: TabDef[] = [
  { key: 'hours', label: 'Giờ mở cửa', render: (b, canEdit) => <OpeningHoursTab branch={b} canEdit={canEdit} /> },
  { key: 'holidays', label: 'Ngày nghỉ', render: (b, canEdit) => <HolidaysTab branch={b} canEdit={canEdit} /> },
  { key: 'services', label: 'Dịch vụ', render: (b, canEdit) => <BranchServicesTab branch={b} canEdit={canEdit} /> },
  { key: 'quota', label: 'Quota lịch hẹn', managerOnly: true, render: (b) => <QuotaTab branch={b} /> },
];

/**
 * T20 — cấu hình một chi nhánh (UC14, UC33, UC42). BRANCH_MANAGER của chi nhánh sửa được; SUPER_MANAGER chỉ xem
 * và sửa thông tin / kích hoạt chi nhánh (UC12). Mỗi tab một URL.
 */
export function AdminBranchDetailPage() {
  const { branchId, tab } = useParams();
  const id = Number(branchId);
  const navigate = useNavigate();
  const account = useAccount();
  const branch = useBranch(id);
  const [editing, setEditing] = useState(false);
  const [activating, setActivating] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);

  const isManager = account?.role === 'BRANCH_MANAGER';
  const isChain = account?.role === 'SUPER_MANAGER';
  const tabs = TABS.filter((t) => !t.managerOnly || isManager);
  if (!tabs.some((t) => t.key === tab)) return <Navigate to={`/admin/branches/${id}/hours`} replace />;

  if (branch.isError) return <NoticeBar notice={{ kind: 'error', text: plainMessage(toApiError(branch.error)) }} />;
  if (!branch.data) return <p className="text-(--text-secondary)">Đang tải…</p>;
  const b = branch.data;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="space-y-1">
          {isChain && (
            <Link to="/admin/branches" className="inline-flex items-center gap-1 text-sm text-(--text-secondary) hover:text-(--text-primary)">
              <ArrowLeft className="h-4 w-4" />
              Danh sách chi nhánh
            </Link>
          )}
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="text-2xl font-semibold text-(--text-primary)">{b.name}</h1>
            <StatusBadge status={BRANCH_STATUS_LABELS[b.status]} variant={b.status === 'ACTIVE' ? 'success' : 'neutral'} />
          </div>
          <p className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-(--text-secondary)">
            <span className="inline-flex items-center gap-1">
              <MapPin className="h-4 w-4" />
              {b.address}
            </span>
            <span className="inline-flex items-center gap-1">
              <Phone className="h-4 w-4" />
              {b.phone}
            </span>
            {b.acceptsAfterHoursEmergency && (
              <span className="inline-flex items-center gap-1 text-red-600">
                <HeartPulse className="h-4 w-4" />
                Nhận cấp cứu ngoài giờ
              </span>
            )}
          </p>
        </div>
        {isChain && (
          <div className="flex gap-2">
            <Button variant="outline" onClick={() => setEditing(true)}>
              <Edit className="h-4 w-4" />
              Sửa
            </Button>
            {b.status === 'DRAFT' && (
              <Button onClick={() => setActivating(true)}>
                <Power className="h-4 w-4" />
                Kích hoạt
              </Button>
            )}
          </div>
        )}
      </div>

      <NoticeBar notice={notice} />

      <Tabs value={tab} onValueChange={(value) => navigate(`/admin/branches/${id}/${value}`)}>
        <TabsList>
          {tabs.map((t) => (
            <TabsTrigger key={t.key} value={t.key}>
              {t.label}
            </TabsTrigger>
          ))}
        </TabsList>
        {tabs.map((t) => (
          <TabsContent key={t.key} value={t.key} className="mt-4">
            {t.render(b, isManager)}
          </TabsContent>
        ))}
      </Tabs>

      {editing && (
        <BranchFormModal
          branch={b}
          onClose={() => setEditing(false)}
          onSaved={(text) => {
            setEditing(false);
            setNotice({ kind: 'success', text });
          }}
        />
      )}
      <ActivateBranchModal branch={activating ? b : null} onClose={() => setActivating(false)} onDone={setNotice} />
    </div>
  );
}
