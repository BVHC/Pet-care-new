import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, Eye, Download } from 'lucide-react';

type InvoiceStatus = 'DRAFT' | 'ISSUED' | 'PAID' | 'VOID' | 'PARTIALLY_REFUNDED' | 'REFUNDED';

interface Invoice {
  id: string; invoiceNumber: string; date: string; customerName: string;
  customerPhone: string; petName: string; subtotal: number; discount: number;
  total: number; status: InvoiceStatus; paymentMethod?: string;
}

const MOCK_INVOICES: Invoice[] = [
  { id: '1', invoiceNumber: 'INV-2026-001', date: '2026-09-18', customerName: 'Nguyễn Văn A', customerPhone: '0901234567', petName: 'Mèo Whiskas', subtotal: 550000, discount: 0, total: 550000, status: 'PAID', paymentMethod: 'Tiền mặt' },
  { id: '2', invoiceNumber: 'INV-2026-002', date: '2026-09-18', customerName: 'Trần Thị B', customerPhone: '0912345678', petName: 'Chó Alaska', subtotal: 1200000, discount: 100000, total: 1100000, status: 'PAID', paymentMethod: 'Thẻ' },
  { id: '3', invoiceNumber: 'INV-2026-003', date: '2026-09-18', customerName: 'Lê Văn C', customerPhone: '0923456789', petName: 'Chó Poodle', subtotal: 350000, discount: 0, total: 350000, status: 'ISSUED' },
];

function StatusBadge({ status }: { status: InvoiceStatus }) {
  const config: Record<InvoiceStatus, { label: string; className: string }> = {
    DRAFT: { label: 'Nháp', className: 'bg-[var(--bg-tertiary)] text-[var(--text-secondary)]' },
    ISSUED: { label: 'Đã phát hành', className: 'bg-blue-100 text-blue-700' },
    PAID: { label: 'Đã thanh toán', className: 'bg-green-100 text-green-700' },
    VOID: { label: 'Đã hủy', className: 'bg-red-100 text-red-700' },
    PARTIALLY_REFUNDED: { label: 'Hoàn một phần', className: 'bg-amber-100 text-amber-700' },
    REFUNDED: { label: 'Đã hoàn tiền', className: 'bg-purple-100 text-purple-700' },
  };
  const { label, className } = config[status];
  return <Badge className={className}>{label}</Badge>;
}

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', minimumFractionDigits: 0 }).format(amount);
}

export function AdminInvoicesPage() {
  const [invoices] = useState(MOCK_INVOICES);
  const [searchQuery, setSearchQuery] = useState('');

  const filteredInvoices = invoices.filter(inv =>
    inv.invoiceNumber.toLowerCase().includes(searchQuery.toLowerCase()) ||
    inv.customerName.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const totalRevenue = invoices.filter(i => i.status === 'PAID').reduce((sum, i) => sum + i.total, 0);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-[var(--text-primary)]">Hóa đơn</h1>
        <p className="text-[var(--text-secondary)]">Quản lý hóa đơn và thanh toán</p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <Card className="card-kpi">
          <CardContent className="p-5">
            <p className="text-sm text-[var(--text-secondary)]">Tổng hóa đơn</p>
            <p className="text-2xl font-semibold text-[var(--text-primary)] mt-1">{invoices.length}</p>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5">
            <p className="text-sm text-[var(--text-secondary)]">Đã thanh toán</p>
            <p className="text-2xl font-semibold text-green-600 mt-1">{formatCurrency(totalRevenue)}</p>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5">
            <p className="text-sm text-[var(--text-secondary)]">Chờ thanh toán</p>
            <p className="text-2xl font-semibold text-amber-600 mt-1">{invoices.filter(i => i.status === 'ISSUED').length}</p>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5">
            <p className="text-sm text-[var(--text-secondary)]">Đã hủy</p>
            <p className="text-2xl font-semibold text-red-600 mt-1">{invoices.filter(i => i.status === 'VOID').length}</p>
          </CardContent>
        </Card>
      </div>

      {/* Search */}
      <Card className="card">
        <CardContent className="p-4">
          <div className="relative max-w-md">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-[var(--text-tertiary)]" />
            <Input
              placeholder="Tìm kiếm hóa đơn..."
              className="pl-10 input"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
        </CardContent>
      </Card>

      {/* Table */}
      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <div className="table-container rounded-none border-0 shadow-none">
            <table className="table">
              <thead>
                <tr>
                  <th className="text-[var(--text-secondary)]">Số hóa đơn</th>
                  <th className="text-[var(--text-secondary)]">Ngày</th>
                  <th className="text-[var(--text-secondary)]">Khách hàng</th>
                  <th className="text-[var(--text-secondary)]">Tổng tiền</th>
                  <th className="text-[var(--text-secondary)]">Trạng thái</th>
                  <th className="text-[var(--text-secondary)]">Thao tác</th>
                </tr>
              </thead>
              <tbody>
                {filteredInvoices.map(inv => (
                  <tr key={inv.id}>
                    <td className="font-medium text-[var(--text-primary)]">{inv.invoiceNumber}</td>
                    <td className="text-[var(--text-secondary)]">{inv.date}</td>
                    <td>
                      <p className="font-medium text-[var(--text-primary)]">{inv.customerName}</p>
                      <p className="text-xs text-[var(--text-tertiary)]">{inv.customerPhone}</p>
                    </td>
                    <td className="font-semibold text-[var(--text-primary)]">{formatCurrency(inv.total)}</td>
                    <td><StatusBadge status={inv.status} /></td>
                    <td>
                      <div className="flex gap-1">
                        <Button size="sm" variant="ghost"><Eye className="h-4 w-4" /></Button>
                        <Button size="sm" variant="ghost"><Download className="h-4 w-4" /></Button>
                      </div>
                    </td>
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
