import { Card, CardContent } from '../../components/ui/card';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Building2, Plus, Eye } from 'lucide-react';

interface Organization {
  id: string; name: string; email: string; phone: string;
  address: string; storeCount: number; userCount: number; status: 'ACTIVE' | 'SUSPENDED';
}

const MOCK_ORGS: Organization[] = [
  { id: '1', name: 'Pet Care VN', email: 'contact@petcare.vn', phone: '02812345678', address: '123 Nguyễn Huệ, Q1, HCM', storeCount: 3, userCount: 25, status: 'ACTIVE' },
  { id: '2', name: 'Pet Shop Plus', email: 'info@petshopplus.vn', phone: '02823456789', address: '456 Lê Lợi, Q5, HCM', storeCount: 2, userCount: 15, status: 'ACTIVE' },
];

export function AdminTenantsPage() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Tổ chức & Cửa hàng</h1>
        <p className="text-gray-500">Quản lý tenant (tổ chức) và cửa hàng</p>
      </div>

      <div className="grid grid-cols-3 gap-4">
        <Card><CardContent className="p-4"><p className="text-sm text-gray-500">Tổng tổ chức</p><p className="text-2xl font-bold">{MOCK_ORGS.length}</p></CardContent></Card>
        <Card><CardContent className="p-4"><p className="text-sm text-gray-500">Đang hoạt động</p><p className="text-2xl font-bold text-green-600">{MOCK_ORGS.filter(o => o.status === 'ACTIVE').length}</p></CardContent></Card>
        <Card><CardContent className="p-4"><p className="text-sm text-gray-500">Tổng cửa hàng</p><p className="text-2xl font-bold">{MOCK_ORGS.reduce((sum, o) => sum + o.storeCount, 0)}</p></CardContent></Card>
      </div>

      <div className="flex justify-end">
        <Button><Plus className="mr-2 h-4 w-4" />Thêm tổ chức</Button>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {MOCK_ORGS.map(org => (
          <Card key={org.id} className="overflow-hidden">
            <CardContent className="p-4">
              <div className="flex items-start justify-between mb-3">
                <div className="flex items-center gap-3">
                  <div className="w-12 h-12 rounded-lg bg-blue-100 flex items-center justify-center">
                    <Building2 className="h-6 w-6 text-blue-600" />
                  </div>
                  <div>
                    <h3 className="font-medium">{org.name}</h3>
                    <Badge className={org.status === 'ACTIVE' ? 'bg-green-100 text-green-700' : 'bg-yellow-100 text-yellow-700'}>
                      {org.status === 'ACTIVE' ? 'Hoạt động' : 'Tạm ngưng'}
                    </Badge>
                  </div>
                </div>
              </div>
              <div className="space-y-2 text-sm">
                <p className="text-gray-500">{org.email}</p>
                <p className="text-gray-500">{org.phone}</p>
                <p className="text-gray-500">{org.address}</p>
              </div>
              <div className="flex gap-4 mt-4 pt-4 border-t">
                <span className="text-sm">{org.storeCount} cửa hàng</span>
                <span className="text-sm">{org.userCount} người dùng</span>
              </div>
              <div className="flex gap-2 mt-4">
                <Button size="sm" variant="outline" className="flex-1"><Eye className="h-4 w-4 mr-1" />Chi tiết</Button>
                <Button size="sm" className="flex-1">Quản lý</Button>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>
    </div>
  );
}
