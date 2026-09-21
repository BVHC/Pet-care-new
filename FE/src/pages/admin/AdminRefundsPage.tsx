import { useState } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Clock, AlertTriangle, Check, X } from 'lucide-react';
import { cn } from '../../lib/utils';

type RefundStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'COMPLETED';

interface RefundRequest {
  id: string; invoiceNumber: string; date: string; customerName: string;
  customerPhone: string; reason: string; amount: number; status: RefundStatus; requestedBy: string; reviewedBy?: string;
}

const MOCK_REFUNDS: RefundRequest[] = [
  { id: '1', invoiceNumber: 'INV-2026-004', date: '2026-09-18', customerName: 'Phạm Thị D', customerPhone: '0934567890', reason: 'Dịch vụ không hài lòng', amount: 200000, status: 'PENDING', requestedBy: 'Reception 1' },
  { id: '2', invoiceNumber: 'INV-2026-003', date: '2026-09-17', customerName: 'Lê Văn C', customerPhone: '0923456789', reason: 'Yêu cầu hủy dịch vụ', amount: 150000, status: 'APPROVED', requestedBy: 'Reception 2', reviewedBy: 'Store Manager' },
];

function StatusBadge({ status }: { status: RefundStatus }) {
  const config: Record<RefundStatus, { label: string; className: string }> = {
    PENDING: { label: 'Chờ duyệt', className: 'bg-amber-100 text-amber-700' },
    APPROVED: { label: 'Đã duyệt', className: 'bg-blue-100 text-blue-700' },
    REJECTED: { label: 'Từ chối', className: 'bg-red-100 text-red-700' },
    COMPLETED: { label: 'Hoàn tất', className: 'bg-green-100 text-green-700' },
  };
  const { label, className } = config[status];
  return <Badge className={className}>{label}</Badge>;
}

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', minimumFractionDigits: 0 }).format(amount);
}

export function AdminRefundsPage() {
  const [refunds, setRefunds] = useState(MOCK_REFUNDS);
  const [searchQuery, setSearchQuery] = useState('');

  const filteredRefunds = refunds.filter(r =>
    r.invoiceNumber.toLowerCase().includes(searchQuery.toLowerCase()) ||
    r.customerName.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const pendingCount = refunds.filter(r => r.status === 'PENDING').length;
  const totalPendingAmount = refunds.filter(r => r.status === 'PENDING').reduce((sum, r) => sum + r.amount, 0);

  const approveRefund = (id: string) => {
    setRefunds(prev => prev.map(r => r.id === id ? { ...r, status: 'APPROVED' as RefundStatus, reviewedBy: 'Store Manager' } : r));
  };

  const rejectRefund = (id: string) => {
    setRefunds(prev => prev.map(r => r.id === id ? { ...r, status: 'REJECTED' as RefundStatus, reviewedBy: 'Store Manager' } : r));
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-[var(--text-primary)]">Hoàn tiền (Maker-Checker)</h1>
        <p className="text-[var(--text-secondary)]">Quản lý yêu cầu hoàn tiền với quy trình duyệt</p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-amber-100">
              <Clock className="h-6 w-6 text-amber-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-[var(--text-primary)]">{pendingCount}</p>
              <p className="text-sm text-[var(--text-secondary)]">Chờ duyệt</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi border-amber-200">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-amber-100">
              <AlertTriangle className="h-6 w-6 text-amber-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-amber-600">{formatCurrency(totalPendingAmount)}</p>
              <p className="text-sm text-[var(--text-secondary)]">Số tiền chờ duyệt</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-green-100">
              <Check className="h-6 w-6 text-green-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-green-600">{formatCurrency(refunds.filter(r => r.status === 'COMPLETED').reduce((sum, r) => sum + r.amount, 0))}</p>
              <p className="text-sm text-[var(--text-secondary)]">Đã hoàn</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-red-100">
              <X className="h-6 w-6 text-red-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-red-600">{refunds.filter(r => r.status === 'REJECTED').length}</p>
              <p className="text-sm text-[var(--text-secondary)]">Từ chối</p>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Search */}
      <Card className="card">
        <CardContent className="p-4">
          <Input
            placeholder="Tìm kiếm..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="input max-w-md"
          />
        </CardContent>
      </Card>

      {/* List */}
      <div className="space-y-4">
        {filteredRefunds.map(refund => (
          <Card key={refund.id} className={cn('card', refund.status === 'PENDING' && 'border-amber-200 bg-amber-50/30')}>
            <CardContent className="p-5">
              <div className="flex items-start justify-between">
                <div className="flex-1">
                  <div className="flex items-center gap-3 mb-2">
                    <h3 className="font-semibold text-[var(--text-primary)]">{refund.invoiceNumber}</h3>
                    <StatusBadge status={refund.status} />
                    <span className="text-sm text-[var(--text-tertiary)]">{refund.date}</span>
                  </div>
                  <p className="text-lg font-medium text-[var(--text-primary)]">{refund.customerName}</p>
                  <p className="text-sm text-[var(--text-secondary)]">{refund.customerPhone}</p>
                  <p className="text-sm mt-2 text-[var(--text-secondary)]">
                    <span className="font-medium">Lý do:</span> {refund.reason}
                  </p>
                  <div className="flex items-center gap-4 mt-2 text-sm text-[var(--text-tertiary)]">
                    <span>Yêu cầu bởi: {refund.requestedBy}</span>
                    {refund.reviewedBy && <span>• Duyệt bởi: {refund.reviewedBy}</span>}
                  </div>
                </div>
                <div className="text-right ml-6">
                  <p className="text-2xl font-semibold text-red-600">{formatCurrency(refund.amount)}</p>
                  {refund.status === 'PENDING' && (
                    <div className="flex gap-2 mt-4">
                      <Button size="sm" className="bg-green-600 hover:bg-green-700" onClick={() => approveRefund(refund.id)}>
                        <Check className="mr-1 h-4 w-4" /> Duyệt
                      </Button>
                      <Button size="sm" variant="destructive" onClick={() => rejectRefund(refund.id)}>
                        <X className="mr-1 h-4 w-4" /> Từ chối
                      </Button>
                    </div>
                  )}
                </div>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>
    </div>
  );
}
