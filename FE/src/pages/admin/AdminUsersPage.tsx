import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Search, Plus, Eye, Edit, Trash2, Lock, Unlock } from 'lucide-react';
import { ROLE_LABELS, UserRole } from '../../shared/types/admin';
import { cn } from '../../lib/utils';
import {
  ConfirmModal,
  FormModal,
  DetailModal,
  InfoRow,
  StatusBadge,
} from '../../components/ui/modal-templates';

/* ================================================================
   Types.
   ================================================================ */

interface User {
  id: string;
  name: string;
  email: string;
  phone: string;
  role: UserRole;
  organization: string;
  status: 'ACTIVE' | 'INACTIVE';
  lastLogin: string;
  createdAt: string;
  avatar?: string;
}

interface UserFormData {
  name: string;
  email: string;
  phone: string;
  role: UserRole;
  organization: string;
}

/* ================================================================
   Mock Data.
   ================================================================ */

const MOCK_USERS: User[] = [
  { id: '1', name: 'Super Admin', email: 'admin@petcare.vn', phone: '0901234567', role: 'SUPER_ADMIN', organization: 'Pet Care VN', status: 'ACTIVE', lastLogin: '2026-09-18 15:30', createdAt: '2026-01-01' },
  { id: '2', name: 'Nguyễn Văn A', email: 'nva@petcare.vn', phone: '0912345678', role: 'STORE_MANAGER', organization: 'Pet Care VN', status: 'ACTIVE', lastLogin: '2026-09-18 14:00', createdAt: '2026-03-15' },
  { id: '3', name: 'Trần Thị B', email: 'ttb@petcare.vn', phone: '0923456789', role: 'RECEPTIONIST', organization: 'Pet Care VN', status: 'ACTIVE', lastLogin: '2026-09-18 12:30', createdAt: '2026-03-20' },
  { id: '4', name: 'Lê Văn C', email: 'lvc@petcare.vn', phone: '0934567890', role: 'VETERINARIAN', organization: 'Pet Care VN', status: 'ACTIVE', lastLogin: '2026-09-17 18:00', createdAt: '2026-04-01' },
  { id: '5', name: 'Phạm Thị D', email: 'ptd@petcare.vn', phone: '0945678901', role: 'GROOMER', organization: 'Pet Care VN', status: 'ACTIVE', lastLogin: '2026-09-17 17:30', createdAt: '2026-04-15' },
  { id: '6', name: 'Hoàng Văn E', email: 'hve@petcare.vn', phone: '0956789012', role: 'INVENTORY_STAFF', organization: 'Pet Care VN', status: 'INACTIVE', lastLogin: '2026-09-10 10:00', createdAt: '2026-05-01' },
  { id: '7', name: 'Vũ Thị F', email: 'vtf@petcare.vn', phone: '0967890123', role: 'FINANCE_STAFF', organization: 'Pet Care VN', status: 'ACTIVE', lastLogin: '2026-09-18 11:00', createdAt: '2026-05-15' },
];

const ROLES: { value: UserRole; label: string }[] = [
  { value: 'SUPER_ADMIN', label: 'Super Admin' },
  { value: 'ORG_ADMIN', label: 'Org Admin' },
  { value: 'STORE_MANAGER', label: 'Quản lý cửa hàng' },
  { value: 'RECEPTIONIST', label: 'Lễ tân' },
  { value: 'VETERINARIAN', label: 'Bác sĩ thú y' },
  { value: 'GROOMER', label: 'KTV Grooming' },
  { value: 'INVENTORY_STAFF', label: 'Nhân viên kho' },
  { value: 'FINANCE_STAFF', label: 'Nhân viên tài chính' },
];

/* ================================================================
   Components.
   ================================================================ */

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

/* ================================================================
   Main Component.
   ================================================================ */

export function AdminUsersPage() {
  const [searchQuery, setSearchQuery] = useState('');
  const [users, setUsers] = useState<User[]>(MOCK_USERS);

  // Modal states
  const [detailUser, setDetailUser] = useState<User | null>(null);
  const [editUser, setEditUser] = useState<User | null>(null);
  const [addUserOpen, setAddUserOpen] = useState(false);
  const [lockUser, setLockUser] = useState<User | null>(null);
  const [deleteUser, setDeleteUser] = useState<User | null>(null);

  // Form state for Add/Edit
  const [formData, setFormData] = useState<UserFormData>({
    name: '',
    email: '',
    phone: '',
    role: 'RECEPTIONIST',
    organization: 'Pet Care VN',
  });

  const filteredUsers = users.filter(u =>
    u.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
    u.email.toLowerCase().includes(searchQuery.toLowerCase())
  );

  // Handlers
  const handleAddUser = () => {
    setFormData({ name: '', email: '', phone: '', role: 'RECEPTIONIST', organization: 'Pet Care VN' });
    setAddUserOpen(true);
  };

  const handleEditUser = (user: User) => {
    setEditUser(user);
    setFormData({
      name: user.name,
      email: user.email,
      phone: user.phone,
      role: user.role,
      organization: user.organization,
    });
  };

  const handleSaveUser = () => {
    if (editUser) {
      // Update existing
      setUsers(prev => prev.map(u => u.id === editUser.id ? { ...u, ...formData } : u));
      setEditUser(null);
    } else {
      // Add new
      const newUser: User = {
        id: String(Date.now()),
        ...formData,
        status: 'ACTIVE',
        lastLogin: '-',
        createdAt: new Date().toISOString().split('T')[0],
      };
      setUsers(prev => [...prev, newUser]);
      setAddUserOpen(false);
    }
  };

  const handleLockUser = () => {
    if (lockUser) {
      setUsers(prev => prev.map(u =>
        u.id === lockUser.id
          ? { ...u, status: u.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE' }
          : u
      ));
      setLockUser(null);
    }
  };

  const handleDeleteUser = () => {
    if (deleteUser) {
      setUsers(prev => prev.filter(u => u.id !== deleteUser.id));
      setDeleteUser(null);
    }
  };

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Người dùng & Vai trò</h1>
        <p className="text-(--text-secondary)">Quản lý tài khoản và phân quyền người dùng</p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <Card className="card-kpi">
          <CardContent className="p-5 text-center">
            <p className="text-2xl font-semibold text-(--text-primary)">{users.length}</p>
            <p className="text-sm text-(--text-secondary)">Tổng người dùng</p>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 text-center">
            <p className="text-2xl font-semibold text-green-600">{users.filter(u => u.status === 'ACTIVE').length}</p>
            <p className="text-sm text-(--text-secondary)">Đang hoạt động</p>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 text-center">
            <p className="text-2xl font-semibold text-red-600">{users.filter(u => u.status === 'INACTIVE').length}</p>
            <p className="text-sm text-(--text-secondary)">Bị khóa</p>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 text-center">
            <p className="text-2xl font-semibold text-(--text-primary)">8</p>
            <p className="text-sm text-(--text-secondary)">Vai trò</p>
          </CardContent>
        </Card>
      </div>

      {/* Search & Actions */}
      <Card className="card">
        <CardContent className="p-4 flex items-center justify-between gap-4">
          <div className="relative flex-1 max-w-[300px]">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-(--text-tertiary)" />
            <Input
              placeholder="Tìm người dùng..."
              className="pl-10 input"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
          <Button onClick={handleAddUser}>
            <Plus className="mr-2 h-4 w-4" />
            Thêm người dùng
          </Button>
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
                <th className="text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {filteredUsers.map(user => (
                <tr key={user.id}>
                  <td>
                    <div className="flex items-center gap-3">
                      <div className="h-10 w-10 rounded-full bg-linear-to-br from-accent to-accent-hover flex items-center justify-center text-white font-medium">
                        {user.name.charAt(0)}
                      </div>
                      <div>
                        <p className="font-medium text-(--text-primary)">{user.name}</p>
                        <p className="text-xs text-(--text-tertiary)">{user.email}</p>
                      </div>
                    </div>
                  </td>
                  <td><RoleBadge role={user.role} /></td>
                  <td className="text-(--text-secondary)">{user.organization}</td>
                  <td className="text-(--text-tertiary)">{user.lastLogin}</td>
                  <td>
                    <StatusBadge
                      status={user.status === 'ACTIVE' ? 'Hoạt động' : 'Bị khóa'}
                      variant={user.status === 'ACTIVE' ? 'success' : 'danger'}
                    />
                  </td>
                  <td>
                    <div className="flex justify-end gap-1">
                      <Button size="sm" variant="ghost" onClick={() => setDetailUser(user)} title="Xem chi tiết">
                        <Eye className="h-4 w-4" />
                      </Button>
                      <Button size="sm" variant="ghost" onClick={() => handleEditUser(user)} title="Sửa">
                        <Edit className="h-4 w-4" />
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => setLockUser(user)}
                        title={user.status === 'ACTIVE' ? 'Khóa tài khoản' : 'Mở khóa'}
                      >
                        {user.status === 'ACTIVE' ? <Lock className="h-4 w-4" /> : <Unlock className="h-4 w-4" />}
                      </Button>
                      <Button size="sm" variant="ghost" onClick={() => setDeleteUser(user)} className="text-red-500 hover:text-red-600" title="Xóa">
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>

      {/* ================================================================
         MODALS
         ================================================================ */}

      {/* 1. Detail Modal - Xem chi tiết user */}
      <DetailModal
        open={!!detailUser}
        onOpenChange={(open) => !open && setDetailUser(null)}
        title="Chi tiết người dùng"
        size="md"
        onEdit={() => {
          if (detailUser) handleEditUser(detailUser);
        }}
        onDelete={() => {
          if (detailUser) setDeleteUser(detailUser);
        }}
        editText="Sửa"
        deleteText="Xóa"
      >
        {detailUser && (
          <div className="space-y-4">
            {/* Avatar & Basic Info */}
            <div className="flex items-center gap-4 pb-4 border-b border-(--color-border-light)">
              <div className="h-16 w-16 rounded-full bg-linear-to-br from-accent to-accent-hover flex items-center justify-center text-white text-2xl font-bold">
                {detailUser.name.charAt(0)}
              </div>
              <div>
                <h3 className="text-lg font-semibold text-(--text-primary)">{detailUser.name}</h3>
                <p className="text-sm text-(--text-secondary)">{detailUser.email}</p>
                <StatusBadge
                  status={detailUser.status === 'ACTIVE' ? 'Hoạt động' : 'Bị khóa'}
                  variant={detailUser.status === 'ACTIVE' ? 'success' : 'danger'}
                />
              </div>
            </div>

            {/* Info Rows */}
            <div className="space-y-1">
              <InfoRow label="Mã người dùng" value={detailUser.id} />
              <InfoRow label="Số điện thoại" value={detailUser.phone} />
              <InfoRow label="Vai trò" value={<RoleBadge role={detailUser.role} />} />
              <InfoRow label="Tổ chức" value={detailUser.organization} />
              <InfoRow label="Đăng nhập cuối" value={detailUser.lastLogin} />
              <InfoRow label="Ngày tạo" value={detailUser.createdAt} />
            </div>
          </div>
        )}
      </DetailModal>

      {/* 2. Add/Edit Modal - Form thêm/sửa user */}
      <FormModal
        open={addUserOpen || !!editUser}
        onOpenChange={(open) => {
          if (!open) {
            setAddUserOpen(false);
            setEditUser(null);
          }
        }}
        title={editUser ? 'Sửa người dùng' : 'Thêm người dùng mới'}
        description={editUser ? 'Cập nhật thông tin người dùng' : 'Điền thông tin để tạo tài khoản mới'}
        onSubmit={handleSaveUser}
        submitText={editUser ? 'Lưu thay đổi' : 'Thêm người dùng'}
        size="md"
      >
        <div className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-(--text-primary) mb-1.5">Họ tên *</label>
            <Input
              value={formData.name}
              onChange={(e) => setFormData({ ...formData, name: e.target.value })}
              placeholder="Nguyễn Văn A"
              required
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-(--text-primary) mb-1.5">Email *</label>
            <Input
              type="email"
              value={formData.email}
              onChange={(e) => setFormData({ ...formData, email: e.target.value })}
              placeholder="email@petcare.vn"
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

          <div>
            <label className="block text-sm font-medium text-(--text-primary) mb-1.5">Vai trò *</label>
            <select
              className="w-full h-10 px-3 rounded-lg border border-(--color-border-default) bg-white text-sm focus:outline-none focus:ring-2 focus:ring-accent"
              value={formData.role}
              onChange={(e) => setFormData({ ...formData, role: e.target.value as UserRole })}
            >
              {ROLES.map(r => (
                <option key={r.value} value={r.value}>{r.label}</option>
              ))}
            </select>
          </div>

          <div>
            <label className="block text-sm font-medium text-(--text-primary) mb-1.5">Tổ chức</label>
            <Input
              value={formData.organization}
              onChange={(e) => setFormData({ ...formData, organization: e.target.value })}
              placeholder="Pet Care VN"
            />
          </div>
        </div>
      </FormModal>

      {/* 3. Lock/Unlock Confirmation */}
      <ConfirmModal
        open={!!lockUser}
        onOpenChange={(open) => !open && setLockUser(null)}
        type={lockUser?.status === 'ACTIVE' ? 'warning' : 'info'}
        title={lockUser?.status === 'ACTIVE' ? 'Khóa tài khoản?' : 'Mở khóa tài khoản?'}
        description={
          lockUser?.status === 'ACTIVE'
            ? `Bạn có chắc muốn khóa tài khoản của "${lockUser?.name}"? Người dùng sẽ không thể đăng nhập.`
            : `Bạn có chắc muốn mở khóa tài khoản của "${lockUser?.name}"? Người dùng sẽ có thể đăng nhập lại.`
        }
        confirmText={lockUser?.status === 'ACTIVE' ? 'Khóa tài khoản' : 'Mở khóa'}
        cancelText="Hủy"
        onConfirm={handleLockUser}
      />

      {/* 4. Delete Confirmation */}
      <ConfirmModal
        open={!!deleteUser}
        onOpenChange={(open) => !open && setDeleteUser(null)}
        type="danger"
        title="Xóa người dùng?"
        description={`Bạn có chắc muốn xóa tài khoản của "${deleteUser?.name}"? Hành động này không thể hoàn tác.`}
        confirmText="Xóa người dùng"
        cancelText="Hủy"
        onConfirm={handleDeleteUser}
      />
    </div>
  );
}
