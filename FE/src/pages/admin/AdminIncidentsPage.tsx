import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, Plus, AlertTriangle, Clock, User, Eye, PawPrint, ArrowRight, CheckCircle2, UserCheck } from 'lucide-react';
import { cn } from '../../lib/utils';
import { ConfirmModal, DetailModal, FormModal, InfoRow } from '../../components/ui/modal-templates';

type IncidentStatus = 'REPORTED' | 'INVESTIGATING' | 'RESOLVED' | 'CLOSED';
type IncidentSeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

interface Incident {
  id: string; title: string; description: string; reportedBy: string;
  reportedAt: string; petName?: string; severity: IncidentSeverity;
  status: IncidentStatus; assignedTo?: string; resolvedAt?: string; resolution?: string;
}

const MOCK_INCIDENTS: Incident[] = [
  { id: '1', title: 'Phản ứng sau tiêm vaccine', description: 'Mèo Whiskas có phản ứng dị ứng sau khi tiêm vaccine dại', reportedBy: 'Dr. Minh', reportedAt: '2026-09-18 10:30', petName: 'Mèo Whiskas', severity: 'HIGH', status: 'RESOLVED', assignedTo: 'Dr. Lan', resolvedAt: '2026-09-18 11:00', resolution: 'Đã xử lý phản ứng dị ứng, thú cưng ổn định' },
  { id: '2', title: 'Thú cưng bị thất lạc', description: 'Chó Poodle không có trong khu vực chờ sau khi grooming', reportedBy: 'Hương', reportedAt: '2026-09-18 14:00', petName: 'Chó Poodle', severity: 'CRITICAL', status: 'INVESTIGATING', assignedTo: 'Store Manager' },
  { id: '3', title: 'Sản phẩm hết hạn trong kho', description: 'Phát hiện 5 sản phẩm thức ăn hết hạn trong kho', reportedBy: 'Kho', reportedAt: '2026-09-17 09:00', severity: 'MEDIUM', status: 'CLOSED', resolvedAt: '2026-09-17 10:30', resolution: 'Đã thanh lý sản phẩm hết hạn' },
];

function SeverityBadge({ severity }: { severity: IncidentSeverity }) {
  const config: Record<IncidentSeverity, { label: string; className: string }> = { LOW: { label: 'Thấp', className: 'bg-gray-100 text-gray-700' }, MEDIUM: { label: 'Trung bình', className: 'bg-amber-100 text-amber-700' }, HIGH: { label: 'Cao', className: 'bg-orange-100 text-orange-700' }, CRITICAL: { label: 'Nghiêm trọng', className: 'bg-red-100 text-red-700' } };
  return <Badge className={config[severity].className}>{config[severity].label}</Badge>;
}

function StatusBadge({ status }: { status: IncidentStatus }) {
  const config: Record<IncidentStatus, { label: string; className: string }> = { REPORTED: { label: 'Đã báo cáo', className: 'bg-gray-100 text-gray-700' }, INVESTIGATING: { label: 'Đang điều tra', className: 'bg-blue-100 text-blue-700' }, RESOLVED: { label: 'Đã xử lý', className: 'bg-green-100 text-green-700' }, CLOSED: { label: 'Đã đóng', className: 'bg-purple-100 text-purple-700' } };
  return <Badge className={config[status].className}>{config[status].label}</Badge>;
}

export function AdminIncidentsPage() {
  const [incidents, setIncidents] = useState(MOCK_INCIDENTS);
  const [searchQuery, setSearchQuery] = useState('');
  const [viewIncident, setViewIncident] = useState<Incident | null>(null);
  const [resolveIncident, setResolveIncident] = useState<Incident | null>(null);
  const [closeIncident, setCloseIncident] = useState<Incident | null>(null);
  const [addIncidentOpen, setAddIncidentOpen] = useState(false);

  const filteredIncidents = incidents.filter(i => i.title.toLowerCase().includes(searchQuery.toLowerCase()) || i.description.toLowerCase().includes(searchQuery.toLowerCase()));
  const openCount = incidents.filter(i => i.status === 'REPORTED' || i.status === 'INVESTIGATING').length;

  const handleResolve = () => {
    if (!resolveIncident) return;
    setIncidents(prev => prev.map(i => i.id === resolveIncident.id ? { ...i, status: 'RESOLVED' as IncidentStatus, resolvedAt: new Date().toLocaleString('vi-VN') } : i));
    setResolveIncident(null);
  };

  const handleClose = () => {
    if (!closeIncident) return;
    setIncidents(prev => prev.map(i => i.id === closeIncident.id ? { ...i, status: 'CLOSED' as IncidentStatus } : i));
    setCloseIncident(null);
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Sự cố & Báo cáo</h1>
        <p className="text-(--text-secondary)">Quản lý các sự cố và khiếu nại</p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi border-red-200"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-red-100"><AlertTriangle className="h-6 w-6 text-red-600" /></div><div><p className="text-2xl font-semibold text-red-600">{openCount}</p><p className="text-sm text-(--text-secondary)">Sự cố đang mở</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-blue-100"><Clock className="h-6 w-6 text-blue-600" /></div><div><p className="text-2xl font-semibold text-blue-600">{incidents.filter(i => i.status === 'INVESTIGATING').length}</p><p className="text-sm text-(--text-secondary)">Đang điều tra</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-green-100"><AlertTriangle className="h-6 w-6 text-green-600" /></div><div><p className="text-2xl font-semibold text-green-600">{incidents.filter(i => i.status === 'RESOLVED').length}</p><p className="text-sm text-(--text-secondary)">Đã xử lý</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-purple-100"><AlertTriangle className="h-6 w-6 text-purple-600" /></div><div><p className="text-2xl font-semibold text-purple-600">{incidents.length}</p><p className="text-sm text-(--text-secondary)">Tổng sự cố</p></div></CardContent></Card>
      </div>

      <Card className="card"><CardContent className="p-4 flex items-center justify-between">
        <div className="relative max-w-md"><Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-(--text-tertiary)" /><Input placeholder="Tìm sự cố..." className="pl-10 input" value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} /></div>
        <Button onClick={() => setAddIncidentOpen(true)}><Plus className="mr-2 h-4 w-4" />Báo cáo sự cố</Button>
      </CardContent></Card>

      <Card className="card"><CardContent className="p-0">
        <div className="divide-y divide-(--border-subtle)">
          {filteredIncidents.map(incident => (
            <div key={incident.id} className={cn('p-5', incident.status === 'INVESTIGATING' && 'bg-red-50/30 border-l-4 border-l-red-500', incident.severity === 'CRITICAL' && incident.status !== 'CLOSED' && 'bg-red-50/50')}>
              <div className="flex items-start justify-between">
                <div className="flex-1">
                  <div className="flex items-center gap-3 mb-2"><h3 className="font-semibold text-(--text-primary)">{incident.title}</h3><SeverityBadge severity={incident.severity} /><StatusBadge status={incident.status} /></div>
                  <p className="text-sm text-(--text-secondary) mb-3">{incident.description}</p>
                  <div className="flex items-center gap-6 text-sm text-(--text-tertiary)">
                    <span className="flex items-center gap-1"><User className="h-4 w-4" /> {incident.reportedBy}</span>
                    <span className="flex items-center gap-1"><Clock className="h-4 w-4" /> {incident.reportedAt}</span>
                    {incident.petName && <span className="flex items-center gap-1"><PawPrint className="h-3.5 w-3.5" /> {incident.petName}</span>}
                    {incident.assignedTo && <span className="flex items-center gap-1"><ArrowRight className="h-3.5 w-3.5" /> {incident.assignedTo}</span>}
                    {incident.resolvedAt && <span className="flex items-center gap-1 text-emerald-600 font-medium"><CheckCircle2 className="h-3.5 w-3.5" /> Đã xử lý: {incident.resolvedAt}</span>}
                  </div>
                </div>
                <div className="flex gap-2 ml-4">
                  <Button size="sm" variant="outline" onClick={() => setViewIncident(incident)}><Eye className="mr-1 h-4 w-4" /> Chi tiết</Button>
                  {incident.status === 'INVESTIGATING' && <Button size="sm" className="bg-green-600 hover:bg-green-700" onClick={() => setResolveIncident(incident)}><CheckCircle2 className="mr-1 h-4 w-4" /> Xử lý xong</Button>}
                  {incident.status === 'RESOLVED' && <Button size="sm" variant="outline" onClick={() => setCloseIncident(incident)}><UserCheck className="mr-1 h-4 w-4" /> Đóng sự cố</Button>}
                </div>
              </div>
            </div>
          ))}
        </div>
      </CardContent></Card>

      {/* MODALS */}
      <DetailModal open={!!viewIncident} onOpenChange={(o) => !o && setViewIncident(null)} title="Chi tiết sự cố" size="md">
        {viewIncident && (
          <div className="space-y-4">
            <div className="flex items-center justify-between pb-4 border-b border-(--color-border-light)">
              <div><h3 className="text-lg font-semibold">{viewIncident.title}</h3><p className="text-sm text-(--text-secondary)">{viewIncident.reportedAt}</p></div>
              <div className="flex gap-2"><SeverityBadge severity={viewIncident.severity} /><StatusBadge status={viewIncident.status} /></div>
            </div>
            <div className="space-y-1">
              <InfoRow label="Người báo cáo" value={viewIncident.reportedBy} />
              {viewIncident.petName && <InfoRow label="Thú cưng" value={viewIncident.petName} />}
              {viewIncident.assignedTo && <InfoRow label="Người phụ trách" value={viewIncident.assignedTo} />}
            </div>
            <div className="border-t border-(--color-border-light) pt-4">
              <p className="text-sm font-medium text-(--text-secondary) mb-2">Mô tả</p>
              <p className="text-sm text-(--text-primary)">{viewIncident.description}</p>
            </div>
            {viewIncident.resolution && (
              <div className="border-t border-(--color-border-light) pt-4">
                <p className="text-sm font-medium text-(--text-secondary) mb-2">Kết quả xử lý</p>
                <p className="text-sm text-(--text-primary) bg-green-50 p-3 rounded-lg">{viewIncident.resolution}</p>
              </div>
            )}
          </div>
        )}
      </DetailModal>

      <ConfirmModal open={!!resolveIncident} onOpenChange={(o) => !o && setResolveIncident(null)} type="success" title="Xác nhận đã xử lý?" description={`Đánh dấu sự cố "${resolveIncident?.title}" là đã xử lý?`} confirmText="Xác nhận xử lý" onConfirm={handleResolve} />

      <ConfirmModal open={!!closeIncident} onOpenChange={(o) => !o && setCloseIncident(null)} type="info" title="Đóng sự cố?" description={`Đóng sự cố "${closeIncident?.title}"? Sự cố sẽ được lưu trữ.`} confirmText="Đóng sự cố" onConfirm={handleClose} />

      <FormModal open={addIncidentOpen} onOpenChange={setAddIncidentOpen} title="Báo cáo sự cố mới" description="Mô tả chi tiết sự cố để theo dõi và xử lý" onSubmit={() => setAddIncidentOpen(false)} submitText="Báo cáo sự cố" size="lg">
        <div className="space-y-4">
          <div><label className="block text-sm font-medium mb-1.5">Tiêu đề *</label><Input placeholder="VD: Phản ứng sau tiêm vaccine" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Mức độ nghiêm trọng</label><select className="w-full h-10 px-3 rounded-lg border border-(--color-border-default) bg-white text-sm"><option value="LOW">Thấp</option><option value="MEDIUM">Trung bình</option><option value="HIGH">Cao</option><option value="CRITICAL">Nghiêm trọng</option></select></div>
          <div><label className="block text-sm font-medium mb-1.5">Mô tả chi tiết *</label><textarea className="w-full h-24 px-3 py-2 rounded-lg border border-(--color-border-default) bg-white text-sm resize-none" placeholder="Mô tả chi tiết sự cố..." /></div>
          <div><label className="block text-sm font-medium mb-1.5">Người phụ trách xử lý</label><select className="w-full h-10 px-3 rounded-lg border border-(--color-border-default) bg-white text-sm"><option>Store Manager</option><option>Dr. Minh</option><option>Dr. Lan</option></select></div>
        </div>
      </FormModal>
    </div>
  );
}
