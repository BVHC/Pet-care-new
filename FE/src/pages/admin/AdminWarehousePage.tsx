import type { ReactNode } from 'react';
import { Navigate, useLocation, useNavigate, useParams } from 'react-router-dom';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../../components/ui/tabs';
import { useAccount } from '../../shared/stores/session.store';
import type { Role } from '../../shared/types/auth';
import { AdjustmentsTab } from './warehouse/AdjustmentsTab';
import { CountsTab } from './warehouse/CountsTab';
import { LotTraceTab } from './warehouse/LotTraceTab';
import { ReceiptsTab } from './warehouse/ReceiptsTab';
import { ShortagesTab } from './warehouse/ShortagesTab';
import { StockTab } from './warehouse/StockTab';
import { SuppliersTab } from './warehouse/SuppliersTab';
import { TransfersTab } from './warehouse/TransfersTab';

const MANAGER: Role[] = ['BRANCH_MANAGER'];

/**
 * Lễ tân chỉ xem tồn (UC75); nhập kho, điều chỉnh, nhà cung cấp, tồn tối thiểu, chuyển kho, kiểm kê, thiếu hụt chỉ quản lý chi nhánh;
 * bác sĩ chỉ truy vết lô thuốc, vaccine (UC79). Tab đầu tiên của vai trò là nơi mở mặc định.
 */
const TABS: { key: string; label: string; roles: Role[]; render: (manager: boolean) => ReactNode }[] = [
  { key: 'stock', label: 'Tồn kho', roles: ['BRANCH_MANAGER', 'RECEPTIONIST'], render: (manager) => <StockTab canEdit={manager} /> },
  { key: 'receipts', label: 'Phiếu nhập', roles: MANAGER, render: () => <ReceiptsTab /> },
  { key: 'adjustments', label: 'Điều chỉnh tồn', roles: MANAGER, render: () => <AdjustmentsTab /> },
  { key: 'suppliers', label: 'Nhà cung cấp', roles: MANAGER, render: () => <SuppliersTab /> },
  { key: 'transfers', label: 'Chuyển kho', roles: MANAGER, render: () => <TransfersTab /> },
  { key: 'counts', label: 'Kiểm kê', roles: MANAGER, render: () => <CountsTab /> },
  { key: 'trace', label: 'Truy vết lô', roles: ['BRANCH_MANAGER', 'VET'], render: () => <LotTraceTab /> },
  { key: 'shortages', label: 'Thiếu hụt vật tư', roles: MANAGER, render: () => <ShortagesTab /> },
];

/** T24 — kho chi nhánh (UC73–79). Chi nhánh lấy từ người đăng nhập; mỗi tab một URL. */
export function AdminWarehousePage() {
  const { tab } = useParams();
  const { search } = useLocation();
  const navigate = useNavigate();
  const role = useAccount()?.role;
  const manager = role === 'BRANCH_MANAGER';
  const tabs = TABS.filter((t) => role && t.roles.includes(role));
  if (!tabs.some((t) => t.key === tab)) return <Navigate to={`/admin/warehouse/${tabs[0]?.key ?? 'stock'}${search}`} replace />;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Kho hàng</h1>
        <p className="text-(--text-secondary)">
          Tồn theo lô và hạn dùng của chi nhánh. Lô đã hết hạn không tính vào tồn khả dụng; tồn không bao giờ âm.
        </p>
      </div>
      <Tabs value={tab} onValueChange={(value) => navigate(`/admin/warehouse/${value}`)}>
        <TabsList>
          {tabs.map((t) => (
            <TabsTrigger key={t.key} value={t.key}>
              {t.label}
            </TabsTrigger>
          ))}
        </TabsList>
        {tabs.map((t) => (
          <TabsContent key={t.key} value={t.key} className="mt-4">
            {t.render(manager)}
          </TabsContent>
        ))}
      </Tabs>
    </div>
  );
}
