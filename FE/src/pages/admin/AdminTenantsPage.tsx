import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, Plus, Building2, MapPin, Users, Eye, Edit, Trash2, PauseCircle, PlayCircle } from 'lucide-react';
import { ConfirmModal, FormModal, DetailModal, InfoRow } from '../../components/ui/modal-templates';

/* ================================================================
   Types.
   ================================================================ */

type TenantStatus = 'ACTIVE' | 'INACTIVE' | 'PENDING';

interface Tenant {
  id: string;
  name: string;
  code: string;
  address: string;
  phone: string;
  email: string;
  storeCount: number;
  userCount: number;
  status: TenantStatus;
  createdAt: string;
  description?: string;
}

interface TenantFormData {
  name: string;
  code: string;
  address: string;
  phone: string;
  email: string;
  description: string;
}

/* ================================================================
   Mock Data.
   ================================================================ */

const MOCK_TENANTS: Tenant[] = [
  { id: '1', name: 'Pet Care VN', code: 'PC-VN', address: '123 Nguyễn Trãi, Q.1, TP.HCM', phone: '0901234567', email: 'contact@petcare.vn', storeCount: 5, userCount: 45, status: 'ACTIVE', createdAt: '2024-01-15', description: 'Chuỗi cửa hàng thú cưng hàng đầu Việt Nam' },
  { id: '2', name: 'Pet House HCM', code: 'PH-HCM', address: '456 Lê Văn Việt, Q.9, TP.HCM', phone: '0912345678', email: 'info@pethousehcm.vn', storeCount: 2, userCount: 20, status: 'ACTIVE', createdAt: '2024-06-01', description: 'Chuỗi cửa hàng thú cưng tại TP.HCM' },
  { id: '3', name: 'VetCare Đà Nẵng', code: 'VC-DN', address: '789 Nguyễn Văn Linh, Q.Hải Châu, Đà Nẵng', phone: '0923456789', email: 'contact@vetcaredn.vn', storeCount: 1, userCount: 12, status: 'PENDING', createdAt: '2025-03-01', description: 'Chuỗi phòng khám thú y tại Đà Nẵng' },
  { id: '4', name: 'Pet Mall Hà Nội', code: 'PM-HN', address: '123 Hoàng Quốc Việt, Q.Cầu Giấy, Hà Nội', phone: '0934567890', email: 'contact@petmallhn.vn', storeCount: 3, userCount: 30, status: 'INACTIVE', createdAt: '2024-09-01', description: 'Trung tâm thú cưng lớn tại Hà Nội' },
];

/* ================================================================
   Components.
   ================================================================ */

function StatusBadgeLocal({ status }: { status: TenantStatus }) {
  const config: Record<TenantStatus, { label: string; className: string }> = {
    ACTIVE: { label: 'Hoạt động', className: 'bg-green-100 text-green-700' },
    INACTIVE: { label: 'Ngừng', className: 'bg-red-100 text-red-700' },
    PENDING: { label: 'Chờ duyệt', className: 'bg-amber-100 text-amber-700' },
  };
  return <Badge className={config[status].className}>{config[status].label}</Badge>;
}

/* ================================================================
   Main Component.
   ================================================================ */

export function AdminTenantsPage() {
  const [tenants, setTenants] = useState<Tenant[]>(MOCK_TENANTS);
  const [searchQuery, setSearchQuery] = useState('');

  // Modal states
  const [detailTenant, setDetailTenant] = useState<Tenant | null>(null);
  const [editTenant, setEditTenant] = useState<Tenant | null>(null);
  const [addTenantOpen, setAddTenantOpen] = useState(false);
  const [suspendTenant, setSuspendTenant] = useState<Tenant | null>(null);
  const [deleteTenant, setDeleteTenant] = useState<Tenant | null>(null);

  // Form state
  const [formData, setFormData] = useState<TenantFormData>({
    name: '',
    code: '',
    address: '',
    phone: '',
    email: '',
    description: '',
  });

  const filteredTenants = tenants.filter(t =>
    t.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
    t.code.toLowerCase().includes(searchQuery.toLowerCase())
  );

  // Handlers
  const handleAddTenant = () => {
    setFormData({ name: '', code: '', address: '', phone: '', email: '', description: '' });
    setAddTenantOpen(true);
  };

  const handleEditTenant = (tenant: Tenant) => {
    setEditTenant(tenant);
    setFormData({
      name: tenant.name,
      code: tenant.code,
      address: tenant.address,
      phone: tenant.phone,
      email: tenant.email,
      description: tenant.description || '',
    });
  };

  const handleSaveTenant = () => {
    if (editTenant) {
      setTenants(prev => prev.map(t => t.id === editTenant.id ? { ...t, ...formData } : t));
      setEditTenant(null);
    } else {
      const newTenant: Tenant = {
        id: String(Date.now()),
        ...formData,
        storeCount: 0,
        userCount: 0,
        status: 'PENDING',
        createdAt: new Date().toISOString().split('T')[0],
      };
      setTenants(prev => [...prev, newTenant]);
      setAddTenantOpen(false);
    }
  };

  const handleSuspendTenant = () => {
    if (suspendTenant) {
      setTenants(prev => prev.map(t =>
        t.id === suspendTenant.id
          ? { ...t, status: t.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE' }
          : t
      ));
      setSuspendTenant(null);
    }
  };

  const handleDeleteTenant = () => {
    if (deleteTenant) {
      setTenants(prev => prev.filter(t => t.id !== deleteTenant.id));
      setDeleteTenant(null);
    }
  };

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Tổ chức</h1>
        <p className="text-(--text-secondary)">Quản lý tổ chức và chuỗi cửa hàng</p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-blue-100">
              <Building2 className="h-6 w-6 text-blue-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-(--text-primary)">{tenants.length}</p>
              <p className="text-sm text-(--text-secondary)">Tổng tổ chức</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-green-100">
              <Building2 className="h-6 w-6 text-green-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-green-600">{tenants.filter(t => t.status === 'ACTIVE').length}</p>
              <p className="text-sm text-(--text-secondary)">Đang hoạt động</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-amber-100">
              <MapPin className="h-6 w-6 text-amber-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-amber-600">{tenants.reduce((sum, t) => sum + t.storeCount, 0)}</p>
              <p className="text-sm text-(--text-secondary)">Tổng cửa hàng</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-purple-100">
              <Users className="h-6 w-6 text-purple-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-purple-600">{tenants.reduce((sum, t) => sum + t.userCount, 0)}</p>
              <p className="text-sm text-(--text-secondary)">Tổng nhân viên</p>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Search & Actions */}
      <Card className="card">
        <CardContent className="p-4 flex items-center justify-between gap-4">
          <div className="relative max-w-md">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-(--text-tertiary)" />
            <Input
              placeholder="Tìm tổ chức..."
              className="pl-10 input"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
          <Button onClick={handleAddTenant}>
            <Plus className="mr-2 h-4 w-4" />
            Thêm tổ chức
          </Button>
        </CardContent>
      </Card>

      {/* Tenants List */}
      <Card className="card">
        <CardContent className="p-0">
          <div className="divide-y divide-(--border-subtle)">
            {filteredTenants.map(tenant => (
              <div key={tenant.id} className="p-5 hover:bg-(--bg-secondary) transition-colors">
                <div className="flex items-start justify-between">
                  <div className="flex items-start gap-4">
                    <div className="w-14 h-14 rounded-xl bg-linear-to-br from-blue-500 to-indigo-600 flex items-center justify-center text-white font-bold text-sm">
                      {tenant.code}
                    </div>
                    <div>
                      <div className="flex items-center gap-3 mb-1">
                        <h3 className="font-semibold text-(--text-primary)">{tenant.name}</h3>
                        <StatusBadgeLocal status={tenant.status} />
                      </div>
                      <p className="text-sm text-(--text-secondary) flex items-center gap-2">
                        <MapPin className="h-4 w-4" /> {tenant.address}
                      </p>
                      <div className="flex items-center gap-4 mt-2 text-sm text-(--text-tertiary)">
                        <span>{tenant.phone}</span>
                        <span>{tenant.email}</span>
                      </div>
                    </div>
                  </div>
                  <div className="flex items-center gap-4">
                    <div className="text-center min-w-[60px]">
                      <p className="text-2xl font-semibold text-(--text-primary)">{tenant.storeCount}</p>
                      <p className="text-(--text-tertiary) text-xs">Cửa hàng</p>
                    </div>
                    <div className="text-center min-w-[60px]">
                      <p className="text-2xl font-semibold text-(--text-primary)">{tenant.userCount}</p>
                      <p className="text-(--text-tertiary) text-xs">Nhân viên</p>
                    </div>
                    <div className="flex gap-1">
                      <Button size="sm" variant="ghost" onClick={() => setDetailTenant(tenant)} title="Xem chi tiết">
                        <Eye className="h-4 w-4" />
                      </Button>
                      <Button size="sm" variant="ghost" onClick={() => handleEditTenant(tenant)} title="Sửa">
                        <Edit className="h-4 w-4" />
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => setSuspendTenant(tenant)}
                        title={tenant.status === 'ACTIVE' ? 'Tạm ngưng' : 'Kích hoạt'}
                      >
                        {tenant.status === 'ACTIVE' ? <PauseCircle className="h-4 w-4" /> : <PlayCircle className="h-4 w-4" />}
                      </Button>
                      <Button size="sm" variant="ghost" onClick={() => setDeleteTenant(tenant)} className="text-red-500 hover:text-red-600" title="Xóa">
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    </div>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* ================================================================
         MODALS
         ================================================================ */}

      {/* 1. Detail Modal */}
      <DetailModal
        open={!!detailTenant}
        onOpenChange={(open) => !open && setDetailTenant(null)}
        title="Chi tiết tổ chức"
        size="lg"
        onEdit={() => detailTenant && handleEditTenant(detailTenant)}
        onDelete={() => detailTenant && setDeleteTenant(detailTenant)}
        editText="Sửa"
        deleteText="Xóa"
      >
        {detailTenant && (
          <div className="space-y-4">
            {/* Header */}
            <div className="flex items-center gap-4 pb-4 border-b border-(--color-border-light)">
              <div className="w-16 h-16 rounded-xl bg-linear-to-br from-blue-500 to-indigo-600 flex items-center justify-center text-white font-bold text-lg">
                {detailTenant.code}
              </div>
              <div>
                <h3 className="text-lg font-semibold text-(--text-primary)">{detailTenant.name}</h3>
                <p className="text-sm text-(--text-secondary)">{detailTenant.code}</p>
                <StatusBadgeLocal status={detailTenant.status} />
              </div>
            </div>

            {/* Stats */}
            <div className="grid grid-cols-3 gap-4">
              <div className="text-center p-3 rounded-xl bg-(--color-surface-2)">
                <p className="text-2xl font-bold text-(--text-primary)">{detailTenant.storeCount}</p>
                <p className="text-xs text-(--text-secondary)">Cửa hàng</p>
              </div>
              <div className="text-center p-3 rounded-xl bg-(--color-surface-2)">
                <p className="text-2xl font-bold text-(--text-primary)">{detailTenant.userCount}</p>
                <p className="text-xs text-(--text-secondary)">Nhân viên</p>
              </div>
              <div className="text-center p-3 rounded-xl bg-(--color-surface-2)">
                <p className="text-sm font-medium text-(--text-primary)">{detailTenant.createdAt}</p>
                <p className="text-xs text-(--text-secondary)">Ngày tạo</p>
              </div>
            </div>

            {/* Info */}
            <div className="space-y-1">
              <InfoRow label="Địa chỉ" value={detailTenant.address} />
              <InfoRow label="Số điện thoại" value={detailTenant.phone} />
              <InfoRow label="Email" value={detailTenant.email} />
              <InfoRow label="Mã tổ chức" value={detailTenant.code} />
            </div>

            {detailTenant.description && (
              <div className="pt-2">
                <p className="text-xs font-medium text-(--text-secondary) mb-1">Mô tả</p>
                <p className="text-sm text-(--text-primary)">{detailTenant.description}</p>
              </div>
            )}
          </div>
        )}
      </DetailModal>

      {/* 2. Add/Edit Form Modal */}
      <FormModal
        open={addTenantOpen || !!editTenant}
        onOpenChange={(open) => {
          if (!open) {
            setAddTenantOpen(false);
            setEditTenant(null);
          }
        }}
        title={editTenant ? 'Sửa tổ chức' : 'Thêm tổ chức mới'}
        description={editTenant ? 'Cập nhật thông tin tổ chức' : 'Điền thông tin để tạo tổ chức mới'}
        onSubmit={handleSaveTenant}
        submitText={editTenant ? 'Lưu thay đổi' : 'Tạo tổ chức'}
        size="lg"
      >
        <div className="grid grid-cols-2 gap-4">
          <div className="col-span-2">
            <label className="block text-sm font-medium text-(--text-primary) mb-1.5">Tên tổ chức *</label>
            <Input
              value={formData.name}
              onChange={(e) => setFormData({ ...formData, name: e.target.value })}
              placeholder="Pet Care VN"
              required
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-(--text-primary) mb-1.5">Mã tổ chức *</label>
            <Input
              value={formData.code}
              onChange={(e) => setFormData({ ...formData, code: e.target.value.toUpperCase() })}
              placeholder="PC-VN"
              maxLength={10}
              required
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-(--text-primary) mb-1.5">Số điện thoại</label>
            <Input
              value={formData.phone}
              onChange={(e) => setFormData({ ...formData, phone: e.target.value })}
              placeholder="0901234567"
            />
          </div>

          <div className="col-span-2">
            <label className="block text-sm font-medium text-(--text-primary) mb-1.5">Email</label>
            <Input
              type="email"
              value={formData.email}
              onChange={(e) => setFormData({ ...formData, email: e.target.value })}
              placeholder="contact@petcare.vn"
            />
          </div>

          <div className="col-span-2">
            <label className="block text-sm font-medium text-(--text-primary) mb-1.5">Địa chỉ *</label>
            <Input
              value={formData.address}
              onChange={(e) => setFormData({ ...formData, address: e.target.value })}
              placeholder="123 Nguyễn Trãi, Q.1, TP.HCM"
              required
            />
          </div>

          <div className="col-span-2">
            <label className="block text-sm font-medium text-(--text-primary) mb-1.5">Mô tả</label>
            <textarea
              className="w-full h-24 px-3 py-2 rounded-lg border border-(--color-border-default) bg-white text-sm focus:outline-none focus:ring-2 focus:ring-accent resize-none"
              value={formData.description}
              onChange={(e) => setFormData({ ...formData, description: e.target.value })}
              placeholder="Mô tả ngắn về tổ chức..."
            />
          </div>
        </div>
      </FormModal>

      {/* 3. Suspend/Activate Confirmation */}
      <ConfirmModal
        open={!!suspendTenant}
        onOpenChange={(open) => !open && setSuspendTenant(null)}
        type={suspendTenant?.status === 'ACTIVE' ? 'warning' : 'info'}
        title={suspendTenant?.status === 'ACTIVE' ? 'Tạm ngưng tổ chức?' : 'Kích hoạt tổ chức?'}
        description={
          suspendTenant?.status === 'ACTIVE'
            ? `Bạn có chắc muốn tạm ngưng "${suspendTenant?.name}"? Tổ chức sẽ không thể hoạt động.`
            : `Bạn có chắc muốn kích hoạt "${suspendTenant?.name}"? Tổ chức sẽ có thể hoạt động trở lại.`
        }
        confirmText={suspendTenant?.status === 'ACTIVE' ? 'Tạm ngưng' : 'Kích hoạt'}
        cancelText="Hủy"
        onConfirm={handleSuspendTenant}
      />

      {/* 4. Delete Confirmation */}
      <ConfirmModal
        open={!!deleteTenant}
        onOpenChange={(open) => !open && setDeleteTenant(null)}
        type="danger"
        title="Xóa tổ chức?"
        description={`Bạn có chắc muốn xóa tổ chức "${deleteTenant?.name}"? Hành động này không thể hoàn tác và sẽ xóa tất cả cửa hàng, nhân viên liên quan.`}
        confirmText="Xóa tổ chức"
        cancelText="Hủy"
        onConfirm={handleDeleteTenant}
      />
    </div>
  );
}
