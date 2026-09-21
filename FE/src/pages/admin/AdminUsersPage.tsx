import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Search, Plus, Eye, Edit } from 'lucide-react';
import { ROLE_LABELS } from '../../shared/types/admin';
import { cn } from '../../lib/utils';

interface User {
  id: string; name: string; email: string; phone: string;
  role: 'SUPER_ADMIN' | 'ORG_ADMIN' | 'STORE_MANAGER' | 'RECEPTIONIST' | 'VETERINARIAN' | 'GROOMER' | 'INVENTORY_STAFF' | 'FINANCE_STAFF';
  organization: string; status: 'ACTIVE' | 'INACTIVE'; lastLogin: string;
}

const MOCK_USERS: User[] = [
  { id: '1', name: 'Super Admin', email: 'admin@petcare.vn', phone: '0901234567', role: 'SUPER_ADMIN', organization: 'Pet Care VN', status: 'ACTIVE', lastLogin: '2026-09-18 15:30' },
  { id: '2', name: 'Nguyễn Văn A', email: 'nva@petcare.vn', phone: '0912345678', role: 'STORE_MANAGER', organization: 'Pet Care VN', status: 'ACTIVE', lastLogin: '2026-09-18 14:00' },
  { id: '3', name: 'Trần Thị B', email: 'ttb@petcare.vn', phone: '0923456789', role: 'RECEPTIONIST', organization: 'Pet Care VN', status: 'ACTIVE', lastLogin: '2026-09-18 12:30' },
];

function RoleBadge({ role }: { role: string }) {
  const colors: Record<string, string> = {
    SUPER_ADMIN: 'bg-red-100 text-red-700',
    ORG_ADMIN: 'bg-purple-100 text-purple-700',
    STORE_MANAGER: 'bg-blue-100 text-blue-700',
    RECEPTIONIST: 'bg-cyan-100 text-cyan-700',
    VETERINARIAN: 'bg-green-100 text-green-700',
    GROOMER: 'bg-pink-100 text-pink-700',
    INVENTORY_STAFF: 'bg-amber-100 text-amber-700',
    FINANCE_STAFF: 'bg-teal-100 text-teal-700',
  };
  return <Badge className={cn('text-xs', colors[role] || 'bg-gray-100 text-gray-700')}>{ROLE_LABELS[role as keyof typeof ROLE_LABELS] || role}</Badge>;
}

export function AdminUsersPage() {
  const [searchQuery, setSearchQuery] = useState('');

  const filteredUsers = MOCK_USERS.filter(u =>
    u.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
    u.email.toLowerCase().includes(searchQuery.toLowerCase())
  );

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-[var(--text-primary)]">Người dùng & Vai trò</h1>
        <p className="text-[var(--text-secondary)]">Quản lý tài khoản và phân quyền</p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <Card className="card-kpi">
          <CardContent className="p-5 text-center">
            <p className="text-2xl font-semibold text-[var(--text-primary)]">{MOCK_USERS.length}</p>
            <p className="text-sm text-[var(--text-secondary)]">Tổng người dùng</p>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 text-center">
            <p className="text-2xl font-semibold text-green-600">{MOCK_USERS.filter(u => u.status === 'ACTIVE').length}</p>
            <p className="text-sm text-[var(--text-secondary)]">Đang hoạt động</p>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 text-center">
            <p className="text-2xl font-semibold text-red-600">{MOCK_USERS.filter(u => u.status === 'INACTIVE').length}</p>
            <p className="text-sm text-[var(--text-secondary)]">Bị khóa</p>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 text-center">
            <p className="text-2xl font-semibold text-[var(--text-primary)]">8</p>
            <p className="text-sm text-[var(--text-secondary)]">Vai trò</p>
          </CardContent>
        </Card>
      </div>

      {/* Search */}
      <Card className="card">
        <CardContent className="p-4 flex items-center justify-between">
          <div className="relative w-[300px]">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-[var(--text-tertiary)]" />
            <Input
              placeholder="Tìm người dùng..."
              className="pl-10 input"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
          <Button><Plus className="mr-2 h-4 w-4" />Thêm người dùng</Button>
        </CardContent>
      </Card>

      {/* Table */}
      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead>
              <tr>
                <th>Người dùng</th>
                <th>Vai trò</th>
                <th>Tổ chức</th>
                <th>Đăng nhập cuối</th>
                <th>Trạng thái</th>
                <th>Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {filteredUsers.map(user => (
                <tr key={user.id}>
                  <td>
                    <p className="font-medium text-[var(--text-primary)]">{user.name}</p>
                    <p className="text-xs text-[var(--text-tertiary)]">{user.email}</p>
                  </td>
                  <td><RoleBadge role={user.role} /></td>
                  <td className="text-[var(--text-secondary)]">{user.organization}</td>
                  <td className="text-[var(--text-tertiary)]">{user.lastLogin}</td>
                  <td>
                    <Badge className={user.status === 'ACTIVE' ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'}>
                      {user.status === 'ACTIVE' ? 'Hoạt động' : 'Bị khóa'}
                    </Badge>
                  </td>
                  <td>
                    <div className="flex gap-1">
                      <Button size="sm" variant="ghost"><Eye className="h-4 w-4" /></Button>
                      <Button size="sm" variant="ghost"><Edit className="h-4 w-4" /></Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>
    </div>
  );
}
