import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Clock, AlertTriangle, Check, X, Eye } from 'lucide-react';
import { ConfirmModal, DetailModal, InfoRow } from '../../components/ui/modal-templates';

type RefundStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'PROCESSING' | 'COMPLETED';

interface RefundRequest {
  id: string; invoiceNumber: string; date: string; customerName: string;
  customerPhone: string; reason: string; amount: number; status: RefundStatus;
  requestedBy: string; reviewedBy?: string; reviewedAt?: string; images?: string[];
}

const MOCK_REFUNDS: RefundRequest[] = [
  { id: '1', invoiceNumber: 'INV-2026-004', date: '2026-09-18', customerName: 'Phạm Thị D', customerPhone: '0934567890', reason: 'Dịch vụ không hài lòng với kết quả cắt tỉa lông', amount: 200000, status: 'PENDING', requestedBy: 'Reception 1', images: ['https://picsum.photos/seed/1/200/200'] },
  { id: '2', invoiceNumber: 'INV-2026-003', date: '2026-09-17', customerName: 'Lê Văn C', customerPhone: '0923456789', reason: 'Yêu cầu hủy dịch vụ do lịch hẹn thay đổi', amount: 150000, status: 'APPROVED', requestedBy: 'Reception 2', reviewedBy: 'Store Manager', reviewedAt: '2026-09-17 15:30' },
  { id: '3', invoiceNumber: 'INV-2026-005', date: '2026-09-16', customerName: 'Hoàng Văn E', customerPhone: '0945678901', reason: 'Sản phẩm bị lỗi từ nhà cung cấp', amount: 350000, status: 'PROCESSING', requestedBy: 'Reception 1', reviewedBy: 'Store Manager', reviewedAt: '2026-09-16 10:00' },
];

function StatusBadgeLocal({ status }: { status: RefundStatus }) {
  const config: Record<RefundStatus, { label: string; className: string }> = {
    PENDING: { label: 'Chờ duyệt', className: 'bg-amber-100 text-amber-700' },
    APPROVED: { label: 'Đã duyệt', className: 'bg-blue-100 text-blue-700' },
    REJECTED: { label: 'Từ chối', className: 'bg-red-100 text-red-700' },
    PROCESSING: { label: 'Đang xử lý', className: 'bg-purple-100 text-purple-700' },
    COMPLETED: { label: 'Hoàn tất', className: 'bg-green-100 text-green-700' },
  };
  return <Badge className={config[status].className}>{config[status].label}</Badge>;
}

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', minimumFractionDigits: 0 }).format(amount);
}

export function AdminRefundsPage() {
  const [refunds, setRefunds] = useState<RefundRequest[]>(MOCK_REFUNDS);
  const [searchQuery, setSearchQuery] = useState('');
  const [viewRefund, setViewRefund] = useState<RefundRequest | null>(null);
  const [approveRefund, setApproveRefund] = useState<RefundRequest | null>(null);
  const [rejectRefund, setRejectRefund] = useState<RefundRequest | null>(null);

  const filteredRefunds = refunds.filter(r => r.invoiceNumber.toLowerCase().includes(searchQuery.toLowerCase()) || r.customerName.toLowerCase().includes(searchQuery.toLowerCase()));
  const pendingCount = refunds.filter(r => r.status === 'PENDING').length;
  const totalPendingAmount = refunds.filter(r => r.status === 'PENDING').reduce((sum, r) => sum + r.amount, 0);

  const handleApprove = () => {
    if (!approveRefund) return;
    setRefunds(prev => prev.map(r => r.id === approveRefund.id ? { ...r, status: 'APPROVED' as RefundStatus, reviewedBy: 'Store Manager', reviewedAt: new Date().toISOString() } : r));
    setApproveRefund(null);
  };

  const handleReject = () => {
    if (!rejectRefund) return;
    setRefunds(prev => prev.map(r => r.id === rejectRefund.id ? { ...r, status: 'REJECTED' as RefundStatus, reviewedBy: 'Store Manager', reviewedAt: new Date().toISOString() } : r));
    setRejectRefund(null);
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Hoàn tiền (Maker-Checker)</h1>
        <p className="text-(--text-secondary)">Quản lý yêu cầu hoàn tiền với quy trình duyệt</p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-amber-100"><Clock className="h-6 w-6 text-amber-600" /></div><div><p className="text-2xl font-semibold">{pendingCount}</p><p className="text-sm text-(--text-secondary)">Chờ duyệt</p></div></CardContent></Card>
        <Card className="card-kpi border-amber-200"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-amber-100"><AlertTriangle className="h-6 w-6 text-amber-600" /></div><div><p className="text-2xl font-semibold text-amber-600">{formatCurrency(totalPendingAmount)}</p><p className="text-sm text-(--text-secondary)">Số tiền chờ duyệt</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-green-100"><Check className="h-6 w-6 text-green-600" /></div><div><p className="text-2xl font-semibold text-green-600">{formatCurrency(refunds.filter(r => r.status === 'COMPLETED').reduce((sum, r) => sum + r.amount, 0))}</p><p className="text-sm text-(--text-secondary)">Đã hoàn</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-red-100"><X className="h-6 w-6 text-red-600" /></div><div><p className="text-2xl font-semibold text-red-600">{refunds.filter(r => r.status === 'REJECTED').length}</p><p className="text-sm text-(--text-secondary)">Từ chối</p></div></CardContent></Card>
      </div>

      <Card className="card"><CardContent className="p-4"><Input placeholder="Tìm kiếm..." value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} className="input max-w-md" /></CardContent></Card>

      <div className="space-y-4">
        {filteredRefunds.map(refund => (
          <Card key={refund.id} className={refund.status === 'PENDING' ? 'border-amber-200 bg-amber-50/30' : ''}>
            <CardContent className="p-5">
              <div className="flex items-start justify-between">
                <div className="flex-1">
                  <div className="flex items-center gap-3 mb-2">
                    <span className="font-semibold text-(--text-primary)">{refund.invoiceNumber}</span>
                    <StatusBadgeLocal status={refund.status} />
                    <span className="text-sm text-(--text-tertiary)">{refund.date}</span>
                  </div>
                  <p className="text-lg font-medium text-(--text-primary)">{refund.customerName}</p>
                  <p className="text-sm text-(--text-secondary)">{refund.customerPhone}</p>
                  <p className="text-sm mt-2 text-(--text-secondary)"><span className="font-medium">Lý do:</span> {refund.reason}</p>
                  <div className="flex items-center gap-4 mt-2 text-sm text-(--text-tertiary)">
                    <span>Yêu cầu bởi: {refund.requestedBy}</span>
                    {refund.reviewedBy && <span>• Duyệt bởi: {refund.reviewedBy}</span>}
                  </div>
                </div>
                <div className="text-right ml-6">
                  <p className="text-2xl font-semibold text-red-600">{formatCurrency(refund.amount)}</p>
                  <div className="flex gap-2 mt-4 justify-end">
                    <Button size="sm" variant="outline" onClick={() => setViewRefund(refund)}><Eye className="mr-1 h-4 w-4" /> Chi tiết</Button>
                    {refund.status === 'PENDING' && (
                      <>
                        <Button size="sm" className="bg-green-600 hover:bg-green-700" onClick={() => setApproveRefund(refund)}><Check className="mr-1 h-4 w-4" /> Duyệt</Button>
                        <Button size="sm" variant="outline" className="text-red-500 border-red-200 hover:bg-red-50" onClick={() => setRejectRefund(refund)}><X className="mr-1 h-4 w-4" /> Từ chối</Button>
                      </>
                    )}
                  </div>
                </div>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      {/* MODALS */}

      <DetailModal open={!!viewRefund} onOpenChange={(o) => !o && setViewRefund(null)} title="Chi tiết yêu cầu hoàn tiền" size="md">
        {viewRefund && (
          <div className="space-y-4">
            <div className="flex items-center justify-between pb-4 border-b border-(--color-border-light)">
              <div><h3 className="text-lg font-semibold">{viewRefund.invoiceNumber}</h3><p className="text-sm text-(--text-secondary)">{viewRefund.date}</p></div>
              <StatusBadgeLocal status={viewRefund.status} />
            </div>
            <div className="space-y-1">
              <InfoRow label="Khách hàng" value={viewRefund.customerName} />
              <InfoRow label="SĐT" value={viewRefund.customerPhone} />
              <InfoRow label="Số tiền hoàn" value={<span className="font-bold text-red-600">{formatCurrency(viewRefund.amount)}</span>} />
              <InfoRow label="Yêu cầu bởi" value={viewRefund.requestedBy} />
              {viewRefund.reviewedBy && <InfoRow label="Duyệt bởi" value={viewRefund.reviewedBy} />}
              {viewRefund.reviewedAt && <InfoRow label="Thời gian duyệt" value={viewRefund.reviewedAt} />}
            </div>
            <div className="border-t border-(--color-border-light) pt-4">
              <p className="text-sm font-medium text-(--text-secondary) mb-2">Lý do hoàn tiền</p>
              <p className="text-sm text-(--text-primary)">{viewRefund.reason}</p>
            </div>
            {viewRefund.images && viewRefund.images.length > 0 && (
              <div className="border-t border-(--color-border-light) pt-4">
                <p className="text-sm font-medium text-(--text-secondary) mb-2">Hình ảnh đính kèm</p>
                <div className="flex gap-2">{viewRefund.images.map((img, idx) => <img key={idx} src={img} alt="" className="h-16 w-16 rounded-lg object-cover" />)}</div>
              </div>
            )}
          </div>
        )}
      </DetailModal>

      <ConfirmModal open={!!approveRefund} onOpenChange={(o) => !o && setApproveRefund(null)} type="success" title="Duyệt hoàn tiền?" description={`Bạn có chắc duyệt yêu cầu hoàn tiền "${approveRefund?.invoiceNumber}" cho "${approveRefund?.customerName}" với số tiền ${approveRefund?.amount ? formatCurrency(approveRefund.amount) : ''}?`} confirmText="Duyệt hoàn tiền" onConfirm={handleApprove} />

      <ConfirmModal open={!!rejectRefund} onOpenChange={(o) => !o && setRejectRefund(null)} type="danger" title="Từ chối hoàn tiền?" description={`Bạn có chắc từ chối yêu cầu hoàn tiền "${rejectRefund?.invoiceNumber}" cho "${rejectRefund?.customerName}"? Khách hàng sẽ được thông báo qua email.`} confirmText="Từ chối" onConfirm={handleReject} />
    </div>
  );
}
