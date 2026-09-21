import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Activity, Download } from 'lucide-react';

type Action = 'CREATE' | 'UPDATE' | 'DELETE' | 'LOGIN' | 'APPROVE';

interface AuditLog {
  id: string; timestamp: string; user: string; action: Action; description: string; ipAddress: string;
}

const MOCK_AUDIT: AuditLog[] = [
  { id: '1', timestamp: '2026-09-18 15:30:45', user: 'Nguyễn Văn A', action: 'APPROVE', description: 'Duyệt hoàn tiền #REF-001', ipAddress: '192.168.1.100' },
  { id: '2', timestamp: '2026-09-18 15:25:30', user: 'Trần Thị B', action: 'CREATE', description: 'Tạo hóa đơn #INV-2026-005', ipAddress: '192.168.1.101' },
  { id: '3', timestamp: '2026-09-18 15:20:15', user: 'Lê Văn C', action: 'UPDATE', description: 'Cập nhật hồ sơ bệnh án', ipAddress: '192.168.1.102' },
  { id: '4', timestamp: '2026-09-18 15:15:00', user: 'Nguyễn Văn A', action: 'LOGIN', description: 'Đăng nhập hệ thống', ipAddress: '192.168.1.100' },
];

export function AdminAuditPage() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Nhật ký kiểm toán</h1>
        <p className="text-gray-500">Theo dõi hoạt động trong hệ thống</p>
      </div>

      <Card>
        <CardContent className="p-4 flex items-center justify-between">
          <div className="relative w-[300px]">
            <Input placeholder="Tìm kiếm..." />
          </div>
          <Button variant="outline"><Download className="mr-2 h-4 w-4" />Xuất log</Button>
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>Nhật ký hoạt động ({MOCK_AUDIT.length})</CardTitle></CardHeader>
        <CardContent className="p-0">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-4 py-3 text-left text-sm font-medium">Thời gian</th>
                  <th className="px-4 py-3 text-left text-sm font-medium">Người dùng</th>
                  <th className="px-4 py-3 text-left text-sm font-medium">Hành động</th>
                  <th className="px-4 py-3 text-left text-sm font-medium">Mô tả</th>
                  <th className="px-4 py-3 text-left text-sm font-medium">IP</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {MOCK_AUDIT.map(log => (
                  <tr key={log.id} className="hover:bg-gray-50">
                    <td className="px-4 py-3 text-sm">
                      <p>{log.timestamp.split(' ')[0]}</p>
                      <p className="text-xs text-gray-500">{log.timestamp.split(' ')[1]}</p>
                    </td>
                    <td className="px-4 py-3"><p className="font-medium">{log.user}</p></td>
                    <td className="px-4 py-3">
                      <Badge className={log.action === 'CREATE' ? 'bg-green-100 text-green-700' : log.action === 'UPDATE' ? 'bg-blue-100 text-blue-700' : log.action === 'DELETE' ? 'bg-red-100 text-red-700' : 'bg-gray-100 text-gray-700'}>
                        {log.action === 'CREATE' ? 'Tạo mới' : log.action === 'UPDATE' ? 'Cập nhật' : log.action === 'DELETE' ? 'Xóa' : log.action === 'LOGIN' ? 'Đăng nhập' : 'Duyệt'}
                      </Badge>
                    </td>
                    <td className="px-4 py-3 text-sm">{log.description}</td>
                    <td className="px-4 py-3 text-sm font-mono text-gray-500">{log.ipAddress}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
