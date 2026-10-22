import type { ReactNode } from 'react';
import { Navigate, useLocation, useNavigate, useParams } from 'react-router-dom';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../../components/ui/tabs';
import { useAccount } from '../../shared/stores/session.store';
import { AdjustmentsTab } from './warehouse/AdjustmentsTab';
import { ReceiptsTab } from './warehouse/ReceiptsTab';
import { StockTab } from './warehouse/StockTab';
import { SuppliersTab } from './warehouse/SuppliersTab';

/** Lễ tân chỉ xem tồn (UC75); nhập kho, điều chỉnh, nhà cung cấp, tồn tối thiểu chỉ quản lý chi nhánh (inventory-v1 mục D). */
const TABS: { key: string; label: string; managerOnly: boolean; render: (manager: boolean) => ReactNode }[] = [
  { key: 'stock', label: 'Tồn kho', managerOnly: false, render: (manager) => <StockTab canEdit={manager} /> },
  { key: 'receipts', label: 'Phiếu nhập', managerOnly: true, render: () => <ReceiptsTab /> },
  { key: 'adjustments', label: 'Điều chỉnh tồn', managerOnly: true, render: () => <AdjustmentsTab /> },
  { key: 'suppliers', label: 'Nhà cung cấp', managerOnly: true, render: () => <SuppliersTab /> },
];

/** T24 — kho chi nhánh (UC73–76). Chi nhánh lấy từ người đăng nhập; mỗi tab một URL. */
export function AdminWarehousePage() {
  const { tab } = useParams();
  const { search } = useLocation();
  const navigate = useNavigate();
  const manager = useAccount()?.role === 'BRANCH_MANAGER';
  const tabs = TABS.filter((t) => manager || !t.managerOnly);
  if (!tabs.some((t) => t.key === tab)) return <Navigate to={`/admin/warehouse/stock${search}`} replace />;

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
