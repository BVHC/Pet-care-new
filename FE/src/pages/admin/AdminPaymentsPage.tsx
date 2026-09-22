import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, CreditCard, Banknote, CheckCircle, AlertCircle, Eye } from 'lucide-react';
import { ConfirmModal, DetailModal, InfoRow } from '../../components/ui/modal-templates';

type PaymentStatus = 'PENDING' | 'COMPLETED' | 'FAILED' | 'REFUNDED';

interface Payment {
  id: string; transactionId: string; date: string; invoiceNumber: string;
  customerName: string; customerPhone: string; amount: number;
  method: 'CASH' | 'VNPAY' | 'BANK_TRANSFER' | 'MOMO';
  status: PaymentStatus; staff: string; notes?: string;
}

const MOCK_PAYMENTS: Payment[] = [
  { id: '1', transactionId: 'TXN-2026-001', date: '2026-09-18 15:30', invoiceNumber: 'INV-2026-001', customerName: 'Nguyễn Văn A', customerPhone: '0901234567', amount: 550000, method: 'CASH', status: 'COMPLETED', staff: 'Reception 1' },
  { id: '2', transactionId: 'TXN-2026-002', date: '2026-09-18 14:00', invoiceNumber: 'INV-2026-002', customerName: 'Trần Thị B', customerPhone: '0912345678', amount: 1100000, method: 'VNPAY', status: 'COMPLETED', staff: 'Reception 1' },
  { id: '3', transactionId: 'TXN-2026-003', date: '2026-09-18 12:30', invoiceNumber: 'INV-2026-003', customerName: 'Lê Văn C', customerPhone: '0923456789', amount: 350000, method: 'MOMO', status: 'PENDING', staff: 'Reception 2' },
  { id: '4', transactionId: 'TXN-2026-004', date: '2026-09-17 16:00', invoiceNumber: 'INV-2026-004', customerName: 'Phạm Thị D', customerPhone: '0934567890', amount: 750000, method: 'BANK_TRANSFER', status: 'FAILED', staff: 'Reception 1', notes: 'Giao dịch bị từ chối từ ngân hàng' },
];

function StatusBadgeLocal({ status }: { status: PaymentStatus }) {
  const config: Record<PaymentStatus, { label: string; className: string }> = {
    PENDING: { label: 'Chờ thanh toán', className: 'bg-amber-100 text-amber-700' },
    COMPLETED: { label: 'Hoàn tất', className: 'bg-green-100 text-green-700' },
    FAILED: { label: 'Thất bại', className: 'bg-red-100 text-red-700' },
    REFUNDED: { label: 'Đã hoàn', className: 'bg-purple-100 text-purple-700' },
  };
  return <Badge className={config[status].className}>{config[status].label}</Badge>;
}

function MethodBadge({ method }: { method: string }) {
  const icons: Record<string, React.ReactNode> = { CASH: <Banknote className="h-4 w-4" />, VNPAY: <CreditCard className="h-4 w-4" />, BANK_TRANSFER: <CreditCard className="h-4 w-4" />, MOMO: <span className="text-pink-500 font-bold text-xs">M</span> };
  const labels: Record<string, string> = { CASH: 'Tiền mặt', VNPAY: 'VNPAY', BANK_TRANSFER: 'Chuyển khoản', MOMO: 'MoMo' };
  return <Badge variant="secondary" className="bg-(--bg-tertiary) flex items-center gap-1">{icons[method]} {labels[method]}</Badge>;
}

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', minimumFractionDigits: 0 }).format(amount);
}

export function AdminPaymentsPage() {
  const [payments] = useState<Payment[]>(MOCK_PAYMENTS);
  const [searchQuery, setSearchQuery] = useState('');
  const [viewPayment, setViewPayment] = useState<Payment | null>(null);
  const [retryPayment, setRetryPayment] = useState<Payment | null>(null);

  const filteredPayments = payments.filter(p => p.transactionId.toLowerCase().includes(searchQuery.toLowerCase()) || p.customerName.toLowerCase().includes(searchQuery.toLowerCase()) || p.invoiceNumber.toLowerCase().includes(searchQuery.toLowerCase()));
  const totalCollected = payments.filter(p => p.status === 'COMPLETED').reduce((sum, p) => sum + p.amount, 0);
  const pendingCount = payments.filter(p => p.status === 'PENDING').length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Thanh toán</h1>
        <p className="text-(--text-secondary)">Quản lý thanh toán và đối soát</p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi border-green-200"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-green-100"><CheckCircle className="h-6 w-6 text-green-600" /></div><div><p className="text-2xl font-semibold text-green-600">{formatCurrency(totalCollected)}</p><p className="text-sm text-(--text-secondary)">Đã thu hôm nay</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-amber-100"><AlertCircle className="h-6 w-6 text-amber-600" /></div><div><p className="text-2xl font-semibold text-amber-600">{pendingCount}</p><p className="text-sm text-(--text-secondary)">Chờ thanh toán</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-blue-100"><CreditCard className="h-6 w-6 text-blue-600" /></div><div><p className="text-2xl font-semibold text-blue-600">{payments.filter(p => p.method === 'VNPAY').length}</p><p className="text-sm text-(--text-secondary)">VNPAY</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-green-100"><Banknote className="h-6 w-6 text-green-600" /></div><div><p className="text-2xl font-semibold text-green-600">{payments.filter(p => p.method === 'CASH').length}</p><p className="text-sm text-(--text-secondary)">Tiền mặt</p></div></CardContent></Card>
      </div>

      <Card className="card"><CardContent className="p-4"><div className="relative max-w-md"><Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-(--text-tertiary)" /><Input placeholder="Tìm giao dịch..." className="pl-10 input" value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} /></div></CardContent></Card>

      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead><tr><th>Mã GD</th><th>Thời gian</th><th>Khách hàng</th><th>Hóa đơn</th><th>Phương thức</th><th>Số tiền</th><th>Trạng thái</th><th>Thu ngân</th><th></th></tr></thead>
            <tbody>
              {filteredPayments.map(payment => (
                <tr key={payment.id}>
                  <td className="font-mono text-sm text-(--text-primary)">{payment.transactionId}</td>
                  <td className="text-(--text-secondary)">{payment.date}</td>
                  <td><p className="font-medium text-(--text-primary)">{payment.customerName}</p><p className="text-xs text-(--text-tertiary)">{payment.customerPhone}</p></td>
                  <td className="text-(--text-secondary)">{payment.invoiceNumber}</td>
                  <td><MethodBadge method={payment.method} /></td>
                  <td className="font-semibold text-(--text-primary)">{formatCurrency(payment.amount)}</td>
                  <td><StatusBadgeLocal status={payment.status} /></td>
                  <td className="text-(--text-secondary)">{payment.staff}</td>
                  <td><Button size="sm" variant="ghost" onClick={() => setViewPayment(payment)}><Eye className="h-4 w-4" /></Button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>

      {/* MODALS */}

      {/* Detail Modal */}
      <DetailModal open={!!viewPayment} onOpenChange={(o) => !o && setViewPayment(null)} title="Chi tiết giao dịch" size="md">
        {viewPayment && (
          <div className="space-y-4">
            <div className="flex items-center justify-between pb-4 border-b border-(--color-border-light)">
              <div><h3 className="text-lg font-semibold">{viewPayment.transactionId}</h3><p className="text-sm text-(--text-secondary)">{viewPayment.date}</p></div>
              <StatusBadgeLocal status={viewPayment.status} />
            </div>
            <div className="space-y-1">
              <InfoRow label="Khách hàng" value={viewPayment.customerName} />
              <InfoRow label="SĐT" value={viewPayment.customerPhone} />
              <InfoRow label="Hóa đơn" value={viewPayment.invoiceNumber} />
              <InfoRow label="Phương thức" value={<MethodBadge method={viewPayment.method} />} />
              <InfoRow label="Số tiền" value={<span className="font-bold text-accent">{formatCurrency(viewPayment.amount)}</span>} />
              <InfoRow label="Thu ngân" value={viewPayment.staff} />
              {viewPayment.notes && <InfoRow label="Ghi chú" value={viewPayment.notes} />}
            </div>
          </div>
        )}
      </DetailModal>

      {/* Retry Confirmation */}
      <ConfirmModal open={!!retryPayment} onOpenChange={(o) => !o && setRetryPayment(null)} type="warning" title="Thử lại giao dịch?" description={`Bạn có chắc muốn thử lại giao dịch "${retryPayment?.transactionId}"?`} confirmText="Thử lại" onConfirm={() => setRetryPayment(null)} />
    </div>
  );
}
