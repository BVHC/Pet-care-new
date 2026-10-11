import type { ReactNode } from 'react';
import { Navigate, useNavigate, useParams } from 'react-router-dom';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../../components/ui/tabs';
import { CategoriesTab } from './catalog/CategoriesTab';
import { ProductsTab } from './catalog/ProductsTab';
import { ProtocolsTab } from './catalog/ProtocolsTab';
import { ServicesTab } from './catalog/ServicesTab';
import { VaccineTypesTab } from './catalog/VaccineTypesTab';

const TABS: { key: string; label: string; render: () => ReactNode }[] = [
  { key: 'products', label: 'Sản phẩm', render: () => <ProductsTab /> },
  { key: 'categories', label: 'Danh mục sản phẩm', render: () => <CategoriesTab /> },
  { key: 'services', label: 'Dịch vụ & loại chuồng', render: () => <ServicesTab /> },
  { key: 'vaccine-types', label: 'Loại vaccine', render: () => <VaccineTypesTab /> },
  { key: 'protocols', label: 'Phác đồ tiêm', render: () => <ProtocolsTab /> },
];

/** T21 — quản lý danh mục dùng chung toàn chuỗi (UC28–UC31, chỉ SUPER_MANAGER). Mỗi tab một URL. */
export function AdminCatalogPage() {
  const { tab } = useParams();
  const navigate = useNavigate();
  if (!TABS.some((t) => t.key === tab)) return <Navigate to="/admin/catalog/products" replace />;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Sản phẩm & dịch vụ</h1>
        <p className="text-(--text-secondary)">
          Danh mục dùng chung toàn chuỗi. Đổi giá không ảnh hưởng đơn đã có; ngừng kinh doanh thay cho xóa.
        </p>
      </div>
      <Tabs value={tab} onValueChange={(value) => navigate(`/admin/catalog/${value}`)}>
        <TabsList>
          {TABS.map((t) => (
            <TabsTrigger key={t.key} value={t.key}>
              {t.label}
            </TabsTrigger>
          ))}
        </TabsList>
        {TABS.map((t) => (
          <TabsContent key={t.key} value={t.key} className="mt-4">
            {t.render()}
          </TabsContent>
        ))}
      </Tabs>
    </div>
  );
}
