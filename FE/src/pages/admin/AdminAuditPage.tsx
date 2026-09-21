import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, FileText, Clock, User, Filter } from 'lucide-react';

type AuditAction = 'CREATE' | 'UPDATE' | 'DELETE' | 'LOGIN' | 'LOGOUT' | 'APPROVE' | 'REJECT';

interface AuditLog {
  id: string;
  timestamp: string;
  user: string;
  role: string;
  action: AuditAction;
  module: string;
  description: string;
  ipAddress: string;
  details?: string;
}

const MOCK_AUDIT: AuditLog[] = [
  { id: '1', timestamp: '2026-09-18 15:30:25', user: 'admin', role: 'SUPER_ADMIN', action: 'LOGIN', module: 'Auth', description: 'Đăng nhập hệ thống', ipAddress: '192.168.1.100' },
  { id: '2', timestamp: '2026-09-18 15:32:10', user: 'admin', role: 'SUPER_ADMIN', action: 'CREATE', module: 'Invoice', description: 'Tạo hóa đơn INV-2026-001', ipAddress: '192.168.1.100', details: 'Số tiền: 550,000 VNĐ' },
  { id: '3', timestamp: '2026-09-18 14:45:00', user: 'nva', role: 'STORE_MANAGER', action: 'APPROVE', module: 'Refund', description: 'Duyệt hoàn tiền REF-2026-001', ipAddress: '192.168.1.105', details: 'Số tiền: 200,000 VNĐ' },
  { id: '4', timestamp: '2026-09-18 14:30:15', user: 'ttb', role: 'RECEPTIONIST', action: 'CREATE', module: 'Appointment', description: 'Tạo lịch hẹn mới', ipAddress: '192.168.1.110', details: 'Khách hàng: Nguyễn Văn A' },
  { id: '5', timestamp: '2026-09-18 12:00:00', user: 'nva', role: 'STORE_MANAGER', action: 'UPDATE', module: 'Inventory', description: 'Cập nhật tồn kho', ipAddress: '192.168.1.105', details: 'SKU: THUCAN-001, Số lượng: +50' },
];

function ActionBadge({ action }: { action: AuditAction }) {
  const config: Record<AuditAction, { label: string; className: string }> = {
    CREATE: { label: 'Tạo mới', className: 'bg-green-100 text-green-700' },
    UPDATE: { label: 'Cập nhật', className: 'bg-blue-100 text-blue-700' },
    DELETE: { label: 'Xóa', className: 'bg-red-100 text-red-700' },
    LOGIN: { label: 'Đăng nhập', className: 'bg-purple-100 text-purple-700' },
    LOGOUT: { label: 'Đăng xuất', className: 'bg-gray-100 text-gray-700' },
    APPROVE: { label: 'Duyệt', className: 'bg-green-100 text-green-700' },
    REJECT: { label: 'Từ chối', className: 'bg-red-100 text-red-700' },
  };
  return <Badge className={config[action].className}>{config[action].label}</Badge>;
}

export function AdminAuditPage() {
  const [audit] = useState(MOCK_AUDIT);
  const [searchQuery, setSearchQuery] = useState('');

  const filteredAudit = audit.filter(a =>
    a.user.toLowerCase().includes(searchQuery.toLowerCase()) ||
    a.description.toLowerCase().includes(searchQuery.toLowerCase()) ||
    a.module.toLowerCase().includes(searchQuery.toLowerCase())
  );

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-[var(--text-primary)]">Nhật ký kiểm toán</h1>
        <p className="text-[var(--text-secondary)]">Theo dõi tất cả hoạt động trong hệ thống</p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-purple-100">
              <FileText className="h-6 w-6 text-purple-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-[var(--text-primary)]">{audit.length}</p>
              <p className="text-sm text-[var(--text-secondary)]">Hoạt động hôm nay</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-blue-100">
              <User className="h-6 w-6 text-blue-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-blue-600">{[...new Set(audit.map(a => a.user))].length}</p>
              <p className="text-sm text-[var(--text-secondary)]">Người dùng hoạt động</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-green-100">
              <FileText className="h-6 w-6 text-green-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-green-600">{audit.filter(a => a.action === 'CREATE' || a.action === 'APPROVE').length}</p>
              <p className="text-sm text-[var(--text-secondary)]">Tạo mới / Duyệt</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-red-100">
              <FileText className="h-6 w-6 text-red-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-red-600">{audit.filter(a => a.action === 'DELETE' || a.action === 'REJECT').length}</p>
              <p className="text-sm text-[var(--text-secondary)]">Xóa / Từ chối</p>
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
              placeholder="Tìm nhật ký..."
              className="pl-10 input"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
          <Button variant="outline"><Filter className="mr-2 h-4 w-4" />Bộ lọc nâng cao</Button>
        </CardContent>
      </Card>

      {/* Audit Log Table */}
      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead>
              <tr>
                <th>Thời gian</th>
                <th>Người dùng</th>
                <th>Hành động</th>
                <th>Module</th>
                <th>Mô tả</th>
                <th>IP</th>
              </tr>
            </thead>
            <tbody>
              {filteredAudit.map(log => (
                <tr key={log.id} className="hover:bg-[var(--bg-secondary)]">
                  <td className="text-[var(--text-tertiary)] font-mono text-xs">{log.timestamp}</td>
                  <td>
                    <p className="font-medium text-[var(--text-primary)]">{log.user}</p>
                    <p className="text-xs text-[var(--text-tertiary)]">{log.role}</p>
                  </td>
                  <td><ActionBadge action={log.action} /></td>
                  <td className="text-[var(--text-secondary)]">{log.module}</td>
                  <td>
                    <p className="text-[var(--text-primary)]">{log.description}</p>
                    {log.details && <p className="text-xs text-[var(--text-tertiary)]">{log.details}</p>}
                  </td>
                  <td className="text-[var(--text-tertiary)] font-mono text-xs">{log.ipAddress}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>
    </div>
  );
}
