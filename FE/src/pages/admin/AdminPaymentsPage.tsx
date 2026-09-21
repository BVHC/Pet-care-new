import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, CreditCard, Banknote, CheckCircle, AlertCircle } from 'lucide-react';
import { cn } from '../../lib/utils';

type PaymentStatus = 'PENDING' | 'COMPLETED' | 'FAILED' | 'REFUNDED';

interface Payment {
  id: string;
  transactionId: string;
  date: string;
  invoiceNumber: string;
  customerName: string;
  customerPhone: string;
  amount: number;
  method: 'CASH' | 'VNPAY' | 'BANK_TRANSFER' | 'MOMO';
  status: PaymentStatus;
  staff: string;
  notes?: string;
}

const MOCK_PAYMENTS: Payment[] = [
  { id: '1', transactionId: 'TXN-2026-001', date: '2026-09-18 15:30', invoiceNumber: 'INV-2026-001', customerName: 'Nguyễn Văn A', customerPhone: '0901234567', amount: 550000, method: 'CASH', status: 'COMPLETED', staff: 'Reception 1' },
  { id: '2', transactionId: 'TXN-2026-002', date: '2026-09-18 14:00', invoiceNumber: 'INV-2026-002', customerName: 'Trần Thị B', customerPhone: '0912345678', amount: 1100000, method: 'VNPAY', status: 'COMPLETED', staff: 'Reception 1' },
  { id: '3', transactionId: 'TXN-2026-003', date: '2026-09-18 12:30', invoiceNumber: 'INV-2026-003', customerName: 'Lê Văn C', customerPhone: '0923456789', amount: 350000, method: 'MOMO', status: 'PENDING', staff: 'Reception 2' },
];

function StatusBadge({ status }: { status: PaymentStatus }) {
  const config: Record<PaymentStatus, { label: string; className: string }> = {
    PENDING: { label: 'Chờ thanh toán', className: 'bg-amber-100 text-amber-700' },
    COMPLETED: { label: 'Hoàn tất', className: 'bg-green-100 text-green-700' },
    FAILED: { label: 'Thất bại', className: 'bg-red-100 text-red-700' },
    REFUNDED: { label: 'Đã hoàn', className: 'bg-purple-100 text-purple-700' },
  };
  return <Badge className={config[status].className}>{config[status].label}</Badge>;
}

function MethodBadge({ method }: { method: string }) {
  const icons: Record<string, React.ReactNode> = {
    CASH: <Banknote className="h-4 w-4" />,
    VNPAY: <CreditCard className="h-4 w-4" />,
    BANK_TRANSFER: <CreditCard className="h-4 w-4" />,
    MOMO: <span className="text-pink-500 font-bold text-xs">M</span>,
  };
  return (
    <Badge variant="secondary" className="bg-[var(--bg-tertiary)] flex items-center gap-1">
      {icons[method]} {method}
    </Badge>
  );
}

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', minimumFractionDigits: 0 }).format(amount);
}

export function AdminPaymentsPage() {
  const [payments] = useState(MOCK_PAYMENTS);
  const [searchQuery, setSearchQuery] = useState('');

  const filteredPayments = payments.filter(p =>
    p.transactionId.toLowerCase().includes(searchQuery.toLowerCase()) ||
    p.customerName.toLowerCase().includes(searchQuery.toLowerCase()) ||
    p.invoiceNumber.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const totalCollected = payments.filter(p => p.status === 'COMPLETED').reduce((sum, p) => sum + p.amount, 0);
  const pendingCount = payments.filter(p => p.status === 'PENDING').length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-[var(--text-primary)]">Thanh toán</h1>
        <p className="text-[var(--text-secondary)]">Quản lý thanh toán và đối soát</p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi border-green-200">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-green-100">
              <CheckCircle className="h-6 w-6 text-green-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-green-600">{formatCurrency(totalCollected)}</p>
              <p className="text-sm text-[var(--text-secondary)]">Đã thu hôm nay</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-amber-100">
              <AlertCircle className="h-6 w-6 text-amber-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-amber-600">{pendingCount}</p>
              <p className="text-sm text-[var(--text-secondary)]">Chờ thanh toán</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-blue-100">
              <CreditCard className="h-6 w-6 text-blue-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-blue-600">{payments.filter(p => p.method === 'VNPAY').length}</p>
              <p className="text-sm text-[var(--text-secondary)]">VNPAY</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-green-100">
              <Banknote className="h-6 w-6 text-green-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-green-600">{payments.filter(p => p.method === 'CASH').length}</p>
              <p className="text-sm text-[var(--text-secondary)]">Tiền mặt</p>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Search */}
      <Card className="card">
        <CardContent className="p-4">
          <div className="relative max-w-md">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-[var(--text-tertiary)]" />
            <Input
              placeholder="Tìm giao dịch..."
              className="pl-10 input"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
        </CardContent>
      </Card>

      {/* Payments Table */}
      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead>
              <tr>
                <th>Mã GD</th>
                <th>Thời gian</th>
                <th>Khách hàng</th>
                <th>Hóa đơn</th>
                <th>Phương thức</th>
                <th>Số tiền</th>
                <th>Trạng thái</th>
                <th>Thu ngân</th>
              </tr>
            </thead>
            <tbody>
              {filteredPayments.map(payment => (
                <tr key={payment.id}>
                  <td className="font-mono text-sm text-[var(--color-primary)]">{payment.transactionId}</td>
                  <td className="text-[var(--text-secondary)]">{payment.date}</td>
                  <td>
                    <p className="font-medium text-[var(--text-primary)]">{payment.customerName}</p>
                    <p className="text-xs text-[var(--text-tertiary)]">{payment.customerPhone}</p>
                  </td>
                  <td className="text-[var(--text-secondary)]">{payment.invoiceNumber}</td>
                  <td><MethodBadge method={payment.method} /></td>
                  <td className="font-semibold text-[var(--text-primary)]">{formatCurrency(payment.amount)}</td>
                  <td><StatusBadge status={payment.status} /></td>
                  <td className="text-[var(--text-secondary)]">{payment.staff}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>
    </div>
  );
}
