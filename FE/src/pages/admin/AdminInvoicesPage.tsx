import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, Eye, Download, FileText } from 'lucide-react';
import { ConfirmModal, DetailModal, InfoRow } from '../../components/ui/modal-templates';

type InvoiceStatus = 'DRAFT' | 'ISSUED' | 'PAID' | 'VOID' | 'PARTIALLY_REFUNDED' | 'REFUNDED';

interface Invoice {
  id: string; invoiceNumber: string; date: string; customerName: string;
  customerPhone: string; petName: string; subtotal: number; discount: number;
  total: number; status: InvoiceStatus; paymentMethod?: string; items: { name: string; quantity: number; price: number }[];
}

const MOCK_INVOICES: Invoice[] = [
  { id: '1', invoiceNumber: 'INV-2026-001', date: '2026-09-18', customerName: 'Nguyễn Văn A', customerPhone: '0901234567', petName: 'Mèo Whiskas', subtotal: 550000, discount: 0, total: 550000, status: 'PAID', paymentMethod: 'Tiền mặt', items: [{ name: 'Khám tổng quát', quantity: 1, price: 300000 }, { name: 'Thuốc', quantity: 1, price: 250000 }] },
  { id: '2', invoiceNumber: 'INV-2026-002', date: '2026-09-18', customerName: 'Trần Thị B', customerPhone: '0912345678', petName: 'Chó Alaska', subtotal: 1200000, discount: 100000, total: 1100000, status: 'PAID', paymentMethod: 'Thẻ', items: [{ name: 'Tiêm vaccine', quantity: 1, price: 1200000 }] },
  { id: '3', invoiceNumber: 'INV-2026-003', date: '2026-09-18', customerName: 'Lê Văn C', customerPhone: '0923456789', petName: 'Chó Poodle', subtotal: 350000, discount: 0, total: 350000, status: 'ISSUED', items: [{ name: 'Tắm & Grooming', quantity: 1, price: 350000 }] },
];

function StatusBadgeLocal({ status }: { status: InvoiceStatus }) {
  const config: Record<InvoiceStatus, { label: string; className: string }> = {
    DRAFT: { label: 'Nháp', className: 'bg-(--bg-tertiary) text-(--text-secondary)' },
    ISSUED: { label: 'Đã phát hành', className: 'bg-blue-100 text-blue-700' },
    PAID: { label: 'Đã thanh toán', className: 'bg-green-100 text-green-700' },
    VOID: { label: 'Đã hủy', className: 'bg-red-100 text-red-700' },
    PARTIALLY_REFUNDED: { label: 'Hoàn một phần', className: 'bg-amber-100 text-amber-700' },
    REFUNDED: { label: 'Đã hoàn tiền', className: 'bg-purple-100 text-purple-700' },
  };
  return <Badge className={config[status].className}>{config[status].label}</Badge>;
}

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', minimumFractionDigits: 0 }).format(amount);
}

export function AdminInvoicesPage() {
  const [invoices, setInvoices] = useState<Invoice[]>(MOCK_INVOICES);
  const [searchQuery, setSearchQuery] = useState('');
  const [viewInvoice, setViewInvoice] = useState<Invoice | null>(null);
  const [voidInvoice, setVoidInvoice] = useState<Invoice | null>(null);

  const filteredInvoices = invoices.filter(inv => inv.invoiceNumber.toLowerCase().includes(searchQuery.toLowerCase()) || inv.customerName.toLowerCase().includes(searchQuery.toLowerCase()));
  const totalRevenue = invoices.filter(i => i.status === 'PAID').reduce((sum, i) => sum + i.total, 0);

  const handleVoidInvoice = () => {
    if (!voidInvoice) return;
    setInvoices(prev => prev.map(i => i.id === voidInvoice.id ? { ...i, status: 'VOID' as InvoiceStatus } : i));
    setVoidInvoice(null);
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Hóa đơn</h1>
        <p className="text-(--text-secondary)">Quản lý hóa đơn và thanh toán</p>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <Card className="card-kpi"><CardContent className="p-5"><p className="text-sm text-(--text-secondary)">Tổng hóa đơn</p><p className="text-2xl font-semibold mt-1">{invoices.length}</p></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5"><p className="text-sm text-(--text-secondary)">Đã thanh toán</p><p className="text-2xl font-semibold text-green-600 mt-1">{formatCurrency(totalRevenue)}</p></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5"><p className="text-sm text-(--text-secondary)">Chờ thanh toán</p><p className="text-2xl font-semibold text-amber-600 mt-1">{invoices.filter(i => i.status === 'ISSUED').length}</p></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5"><p className="text-sm text-(--text-secondary)">Đã hủy</p><p className="text-2xl font-semibold text-red-600 mt-1">{invoices.filter(i => i.status === 'VOID').length}</p></CardContent></Card>
      </div>

      <Card className="card"><CardContent className="p-4"><div className="relative max-w-md"><Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-(--text-tertiary)" /><Input placeholder="Tìm kiếm hóa đơn..." className="pl-10 input" value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} /></div></CardContent></Card>

      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead><tr><th>Số hóa đơn</th><th>Ngày</th><th>Khách hàng</th><th>Tổng tiền</th><th>Trạng thái</th><th>Thao tác</th></tr></thead>
            <tbody>
              {filteredInvoices.map(inv => (
                <tr key={inv.id}>
                  <td className="font-medium text-(--text-primary)">{inv.invoiceNumber}</td>
                  <td className="text-(--text-secondary)">{inv.date}</td>
                  <td><p className="font-medium text-(--text-primary)">{inv.customerName}</p><p className="text-xs text-(--text-tertiary)">{inv.customerPhone}</p></td>
                  <td className="font-semibold text-(--text-primary)">{formatCurrency(inv.total)}</td>
                  <td><StatusBadgeLocal status={inv.status} /></td>
                  <td>
                    <div className="flex gap-1">
                      <Button size="sm" variant="ghost" onClick={() => setViewInvoice(inv)} title="Xem chi tiết"><Eye className="h-4 w-4" /></Button>
                      <Button size="sm" variant="ghost" title="Tải xuống"><Download className="h-4 w-4" /></Button>
                      {inv.status === 'ISSUED' && <Button size="sm" variant="ghost" className="text-red-500 hover:text-red-600" onClick={() => setVoidInvoice(inv)} title="Hủy hóa đơn"><FileText className="h-4 w-4" /></Button>}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>

      {/* MODALS */}

      {/* Detail Modal */}
      <DetailModal open={!!viewInvoice} onOpenChange={(o) => !o && setViewInvoice(null)} title="Chi tiết hóa đơn" size="lg">
        {viewInvoice && (
          <div className="space-y-4">
            <div className="flex items-center justify-between pb-4 border-b border-(--color-border-light)">
              <div><h3 className="text-lg font-semibold">{viewInvoice.invoiceNumber}</h3><p className="text-sm text-(--text-secondary)">{viewInvoice.date}</p></div>
              <StatusBadgeLocal status={viewInvoice.status} />
            </div>
            <div className="space-y-1"><InfoRow label="Khách hàng" value={viewInvoice.customerName} /><InfoRow label="SĐT" value={viewInvoice.customerPhone} /><InfoRow label="Thú cưng" value={viewInvoice.petName} /><InfoRow label="Thanh toán" value={viewInvoice.paymentMethod || '-'} /></div>
            <div className="border-t border-(--color-border-light) pt-4"><h4 className="font-medium mb-2">Dịch vụ/Sản phẩm</h4>{viewInvoice.items.map((item, idx) => <div key={idx} className="flex justify-between py-2 border-b border-(--color-border-light) last:border-0"><span>{item.quantity}x {item.name}</span><span className="font-medium">{formatCurrency(item.price * item.quantity)}</span></div>)}</div>
            <div className="border-t border-(--color-border-light) pt-4 space-y-1"><InfoRow label="Tạm tính" value={formatCurrency(viewInvoice.subtotal)} /><InfoRow label="Giảm giá" value={formatCurrency(viewInvoice.discount)} /><InfoRow label="Tổng cộng" value={<span className="font-bold text-accent">{formatCurrency(viewInvoice.total)}</span>} /></div>
          </div>
        )}
      </DetailModal>

      {/* Void Confirmation */}
      <ConfirmModal open={!!voidInvoice} onOpenChange={(o) => !o && setVoidInvoice(null)} type="danger" title="Hủy hóa đơn?" description={`Bạn có chắc muốn hủy hóa đơn "${voidInvoice?.invoiceNumber}"? Hành động này không thể hoàn tác.`} confirmText="Hủy hóa đơn" onConfirm={handleVoidInvoice} />
    </div>
  );
}
