import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, Plus, AlertTriangle, Clock, User, Eye } from 'lucide-react';
import { cn } from '../../lib/utils';

type IncidentStatus = 'REPORTED' | 'INVESTIGATING' | 'RESOLVED' | 'CLOSED';
type IncidentSeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

interface Incident {
  id: string;
  title: string;
  description: string;
  reportedBy: string;
  reportedAt: string;
  petName?: string;
  severity: IncidentSeverity;
  status: IncidentStatus;
  assignedTo?: string;
  resolvedAt?: string;
}

const MOCK_INCIDENTS: Incident[] = [
  { id: '1', title: 'Phản ứng sau tiêm vaccine', description: 'Mèo Whiskas có phản ứng dị ứng sau khi tiêm vaccine dại', reportedBy: 'Dr. Minh', reportedAt: '2026-09-18 10:30', petName: 'Mèo Whiskas', severity: 'HIGH', status: 'RESOLVED', assignedTo: 'Dr. Lan', resolvedAt: '2026-09-18 11:00' },
  { id: '2', title: 'Thú cưng bị thất lạc', description: 'Chó Poodle không có trong khu vực chờ sau khi grooming', reportedBy: 'Hương', reportedAt: '2026-09-18 14:00', petName: 'Chó Poodle', severity: 'CRITICAL', status: 'INVESTIGATING', assignedTo: 'Store Manager' },
  { id: '3', title: 'Sản phẩm hết hạn trong kho', description: 'Phát hiện 5 sản phẩm thức ăn hết hạn trong kho', reportedBy: 'Kho', reportedAt: '2026-09-17 09:00', severity: 'MEDIUM', status: 'CLOSED', resolvedAt: '2026-09-17 10:30' },
];

function SeverityBadge({ severity }: { severity: IncidentSeverity }) {
  const config: Record<IncidentSeverity, { label: string; className: string }> = {
    LOW: { label: 'Thấp', className: 'bg-gray-100 text-gray-700' },
    MEDIUM: { label: 'Trung bình', className: 'bg-amber-100 text-amber-700' },
    HIGH: { label: 'Cao', className: 'bg-orange-100 text-orange-700' },
    CRITICAL: { label: 'Nghiêm trọng', className: 'bg-red-100 text-red-700' },
  };
  return <Badge className={config[severity].className}>{config[severity].label}</Badge>;
}

function StatusBadge({ status }: { status: IncidentStatus }) {
  const config: Record<IncidentStatus, { label: string; className: string }> = {
    REPORTED: { label: 'Đã báo cáo', className: 'bg-gray-100 text-gray-700' },
    INVESTIGATING: { label: 'Đang điều tra', className: 'bg-blue-100 text-blue-700' },
    RESOLVED: { label: 'Đã xử lý', className: 'bg-green-100 text-green-700' },
    CLOSED: { label: 'Đã đóng', className: 'bg-purple-100 text-purple-700' },
  };
  return <Badge className={config[status].className}>{config[status].label}</Badge>;
}

export function AdminIncidentsPage() {
  const [incidents] = useState(MOCK_INCIDENTS);
  const [searchQuery, setSearchQuery] = useState('');

  const filteredIncidents = incidents.filter(i =>
    i.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
    i.description.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const openCount = incidents.filter(i => i.status === 'REPORTED' || i.status === 'INVESTIGATING').length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-[var(--text-primary)]">Sự cố & Báo cáo</h1>
        <p className="text-[var(--text-secondary)]">Quản lý các sự cố và khiếu nại</p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi border-red-200">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-red-100">
              <AlertTriangle className="h-6 w-6 text-red-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-red-600">{openCount}</p>
              <p className="text-sm text-[var(--text-secondary)]">Sự cố đang mở</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-blue-100">
              <Clock className="h-6 w-6 text-blue-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-blue-600">{incidents.filter(i => i.status === 'INVESTIGATING').length}</p>
              <p className="text-sm text-[var(--text-secondary)]">Đang điều tra</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-green-100">
              <AlertTriangle className="h-6 w-6 text-green-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-green-600">{incidents.filter(i => i.status === 'RESOLVED').length}</p>
              <p className="text-sm text-[var(--text-secondary)]">Đã xử lý</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-purple-100">
              <AlertTriangle className="h-6 w-6 text-purple-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-purple-600">{incidents.length}</p>
              <p className="text-sm text-[var(--text-secondary)]">Tổng sự cố</p>
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
              placeholder="Tìm sự cố..."
              className="pl-10 input"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
          <Button><Plus className="mr-2 h-4 w-4" />Báo cáo sự cố</Button>
        </CardContent>
      </Card>

      {/* Incidents List */}
      <Card className="card">
        <CardContent className="p-0">
          <div className="divide-y divide-[var(--border-subtle)]">
            {filteredIncidents.map(incident => (
              <div key={incident.id} className={cn(
                'p-5',
                incident.status === 'INVESTIGATING' && 'bg-red-50/30 border-l-4 border-l-red-500',
                incident.severity === 'CRITICAL' && incident.status !== 'CLOSED' && 'bg-red-50/50'
              )}>
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <div className="flex items-center gap-3 mb-2">
                      <h3 className="font-semibold text-[var(--text-primary)]">{incident.title}</h3>
                      <SeverityBadge severity={incident.severity} />
                      <StatusBadge status={incident.status} />
                    </div>
                    <p className="text-sm text-[var(--text-secondary)] mb-3">{incident.description}</p>
                    <div className="flex items-center gap-6 text-sm text-[var(--text-tertiary)]">
                      <span className="flex items-center gap-1">
                        <User className="h-4 w-4" /> {incident.reportedBy}
                      </span>
                      <span className="flex items-center gap-1">
                        <Clock className="h-4 w-4" /> {incident.reportedAt}
                      </span>
                      {incident.petName && <span>🐾 {incident.petName}</span>}
                      {incident.assignedTo && <span>→ {incident.assignedTo}</span>}
                      {incident.resolvedAt && <span>✓ Đã xử lý: {incident.resolvedAt}</span>}
                    </div>
                  </div>
                  <div className="ml-4">
                    <Button size="sm" variant="outline">
                      <Eye className="mr-1 h-4 w-4" /> Chi tiết
                    </Button>
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
