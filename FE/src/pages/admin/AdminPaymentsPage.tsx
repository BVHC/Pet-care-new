import { useState } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, DollarSign, CreditCard, Banknote, Download } from 'lucide-react';

type PaymentMethod = 'CASH' | 'CARD' | 'BANK_TRANSFER';
type PaymentStatus = 'PENDING' | 'COMPLETED' | 'FAILED' | 'REFUNDED';

interface Payment {
  id: string; invoiceNumber: string; date: string; time: string;
  customerName: string; amount: number; method: PaymentMethod; status: PaymentStatus; staff: string;
}

const MOCK_PAYMENTS: Payment[] = [
  { id: '1', invoiceNumber: 'INV-2026-001', date: '2026-09-18', time: '09:15', customerName: 'Nguyễn Văn A', amount: 550000, method: 'CASH', status: 'COMPLETED', staff: 'Reception 1' },
  { id: '2', invoiceNumber: 'INV-2026-002', date: '2026-09-18', time: '10:30', customerName: 'Trần Thị B', amount: 1100000, method: 'CARD', status: 'COMPLETED', staff: 'Reception 1' },
  { id: '3', invoiceNumber: 'INV-2026-003', date: '2026-09-18', time: '11:00', customerName: 'Lê Văn C', amount: 350000, method: 'CASH', status: 'PENDING', staff: 'Reception 2' },
];

function MethodBadge({ method }: { method: PaymentMethod }) {
  const config: Record<PaymentMethod, { label: string; className: string }> = {
    CASH: { label: 'Tiền mặt', className: 'bg-green-100 text-green-700' },
    CARD: { label: 'Thẻ', className: 'bg-blue-100 text-blue-700' },
    BANK_TRANSFER: { label: 'Chuyển khoản', className: 'bg-purple-100 text-purple-700' },
  };
  const { label, className } = config[method];
  return <Badge className={className}>{label}</Badge>;
}

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', minimumFractionDigits: 0 }).format(amount);
}

export function AdminPaymentsPage() {
  const [payments] = useState(MOCK_PAYMENTS);
  const [searchQuery, setSearchQuery] = useState('');

  const filteredPayments = payments.filter(p =>
    p.invoiceNumber.toLowerCase().includes(searchQuery.toLowerCase()) ||
    p.customerName.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const totalCash = payments.filter(p => p.method === 'CASH' && p.status === 'COMPLETED').reduce((sum, p) => sum + p.amount, 0);
  const totalCard = payments.filter(p => p.method === 'CARD' && p.status === 'COMPLETED').reduce((sum, p) => sum + p.amount, 0);
  const totalCompleted = payments.filter(p => p.status === 'COMPLETED').reduce((sum, p) => sum + p.amount, 0);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Thanh toán & Đối soát</h1>
        <p className="text-gray-500">Quản lý thanh toán và đối soát cuối ngày</p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-green-100"><Banknote className="h-6 w-6 text-green-600" /></div><div><p className="text-sm text-gray-500">Tiền mặt</p><p className="text-xl font-bold text-green-600">{formatCurrency(totalCash)}</p></div></CardContent></Card>
        <Card><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-blue-100"><CreditCard className="h-6 w-6 text-blue-600" /></div><div><p className="text-sm text-gray-500">Thẻ</p><p className="text-xl font-bold text-blue-600">{formatCurrency(totalCard)}</p></div></CardContent></Card>
        <Card><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-purple-100"><DollarSign className="h-6 w-6 text-purple-600" /></div><div><p className="text-sm text-gray-500">Chuyển khoản</p><p className="text-xl font-bold text-purple-600">{formatCurrency(0)}</p></div></CardContent></Card>
        <Card className="bg-blue-50 border-blue-200"><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-blue-200"><DollarSign className="h-6 w-6 text-blue-700" /></div><div><p className="text-sm text-gray-500">Tổng hoàn tất</p><p className="text-xl font-bold text-blue-700">{formatCurrency(totalCompleted)}</p></div></CardContent></Card>
      </div>

      <Card>
        <CardContent className="p-4 flex items-center justify-between">
          <div className="relative max-w-md">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-gray-400" />
            <Input placeholder="Tìm kiếm..." className="pl-10" value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} />
          </div>
          <Button variant="outline"><Download className="mr-2 h-4 w-4" />Xuất báo cáo</Button>
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>Danh sách thanh toán ({filteredPayments.length})</CardTitle></CardHeader>
        <CardContent className="p-0">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-4 py-3 text-left text-sm font-medium">Mã</th>
                  <th className="px-4 py-3 text-left text-sm font-medium">Thời gian</th>
                  <th className="px-4 py-3 text-left text-sm font-medium">Khách hàng</th>
                  <th className="px-4 py-3 text-left text-sm font-medium">Số tiền</th>
                  <th className="px-4 py-3 text-left text-sm font-medium">Phương thức</th>
                  <th className="px-4 py-3 text-left text-sm font-medium">NV thực hiện</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {filteredPayments.map(pay => (
                  <tr key={pay.id} className="hover:bg-gray-50">
                    <td className="px-4 py-3 font-medium">{pay.invoiceNumber}</td>
                    <td className="px-4 py-3"><p>{pay.date}</p><p className="text-xs text-gray-500">{pay.time}</p></td>
                    <td className="px-4 py-3">{pay.customerName}</td>
                    <td className="px-4 py-3 font-medium">{formatCurrency(pay.amount)}</td>
                    <td className="px-4 py-3"><MethodBadge method={pay.method} /></td>
                    <td className="px-4 py-3">{pay.staff}</td>
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
