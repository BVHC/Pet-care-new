import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, Plus, Building2, MapPin, Users } from 'lucide-react';

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
}

const MOCK_TENANTS: Tenant[] = [
  { id: '1', name: 'Pet Care VN', code: 'PC-VN', address: '123 Nguyễn Trãi, Q.1, TP.HCM', phone: '0901234567', email: 'contact@petcare.vn', storeCount: 5, userCount: 45, status: 'ACTIVE', createdAt: '2024-01-15' },
  { id: '2', name: 'Pet House HCM', code: 'PH-HCM', address: '456 Lê Văn Việt, Q.9, TP.HCM', phone: '0912345678', email: 'info@pethousehcm.vn', storeCount: 2, userCount: 20, status: 'ACTIVE', createdAt: '2024-06-01' },
  { id: '3', name: 'VetCare Đà Nẵng', code: 'VC-DN', address: '789 Nguyễn Văn Linh, Q.Hải Châu, Đà Nẵng', phone: '0923456789', email: 'contact@vetcaredn.vn', storeCount: 1, userCount: 12, status: 'PENDING', createdAt: '2025-03-01' },
];

function StatusBadge({ status }: { status: TenantStatus }) {
  const config: Record<TenantStatus, { label: string; className: string }> = {
    ACTIVE: { label: 'Hoạt động', className: 'bg-green-100 text-green-700' },
    INACTIVE: { label: 'Ngừng', className: 'bg-red-100 text-red-700' },
    PENDING: { label: 'Chờ duyệt', className: 'bg-amber-100 text-amber-700' },
  };
  return <Badge className={config[status].className}>{config[status].label}</Badge>;
}

export function AdminTenantsPage() {
  const [tenants] = useState(MOCK_TENANTS);
  const [searchQuery, setSearchQuery] = useState('');

  const filteredTenants = tenants.filter(t =>
    t.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
    t.code.toLowerCase().includes(searchQuery.toLowerCase())
  );

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-[var(--text-primary)]">Tổ chức</h1>
        <p className="text-[var(--text-secondary)]">Quản lý tổ chức và chuỗi cửa hàng</p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-blue-100">
              <Building2 className="h-6 w-6 text-blue-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-[var(--text-primary)]">{tenants.length}</p>
              <p className="text-sm text-[var(--text-secondary)]">Tổng tổ chức</p>
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
              <p className="text-sm text-[var(--text-secondary)]">Đang hoạt động</p>
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
              <p className="text-sm text-[var(--text-secondary)]">Tổng cửa hàng</p>
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
              <p className="text-sm text-[var(--text-secondary)]">Tổng nhân viên</p>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Search */}
      <Card className="card">
        <CardContent className="p-4 flex items-center justify-between">
          <div className="relative max-w-md">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-[var(--text-tertiary)]" />
            <Input
              placeholder="Tìm tổ chức..."
              className="pl-10 input"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
          <Button><Plus className="mr-2 h-4 w-4" />Thêm tổ chức</Button>
        </CardContent>
      </Card>

      {/* Tenants List */}
      <Card className="card">
        <CardContent className="p-0">
          <div className="divide-y divide-[var(--border-subtle)]">
            {filteredTenants.map(tenant => (
              <div key={tenant.id} className="p-5 hover:bg-[var(--bg-secondary)] transition-colors">
                <div className="flex items-start justify-between">
                  <div className="flex items-start gap-4">
                    <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-blue-500 to-indigo-600 flex items-center justify-center text-white font-bold">
                      {tenant.code}
                    </div>
                    <div>
                      <div className="flex items-center gap-3 mb-1">
                        <h3 className="font-semibold text-[var(--text-primary)]">{tenant.name}</h3>
                        <StatusBadge status={tenant.status} />
                      </div>
                      <p className="text-sm text-[var(--text-secondary)] flex items-center gap-2">
                        <MapPin className="h-4 w-4" /> {tenant.address}
                      </p>
                      <div className="flex items-center gap-4 mt-2 text-sm text-[var(--text-tertiary)]">
                        <span>{tenant.phone}</span>
                        <span>{tenant.email}</span>
                      </div>
                    </div>
                  </div>
                  <div className="flex items-center gap-6 text-sm">
                    <div className="text-center">
                      <p className="text-2xl font-semibold text-[var(--text-primary)]">{tenant.storeCount}</p>
                      <p className="text-[var(--text-tertiary)]">Cửa hàng</p>
                    </div>
                    <div className="text-center">
                      <p className="text-2xl font-semibold text-[var(--text-primary)]">{tenant.userCount}</p>
                      <p className="text-[var(--text-tertiary)]">Nhân viên</p>
                    </div>
                    <div className="text-center">
                      <p className="text-sm text-[var(--text-tertiary)]">Ngày tạo</p>
                      <p className="text-[var(--text-primary)]">{tenant.createdAt}</p>
                    </div>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
