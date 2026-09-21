import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Truck, Package, Clock, Check, Plus, Eye } from 'lucide-react';

type Status = 'DRAFT' | 'PENDING' | 'APPROVED' | 'RECEIVED';

interface PurchaseOrder {
  id: string; orderNumber: string; supplierName: string; orderDate: string;
  expectedDate: string; items: number; totalAmount: number; status: Status;
}

const MOCK_ORDERS: PurchaseOrder[] = [
  { id: '1', orderNumber: 'PO-2026-001', supplierName: 'Công ty TNHH Pet Supply VN', orderDate: '2026-09-15', expectedDate: '2026-09-20', items: 5, totalAmount: 5500000, status: 'RECEIVED' },
  { id: '2', orderNumber: 'PO-2026-002', supplierName: 'Nhà phân phối Thú cưng Sài Gòn', orderDate: '2026-09-16', expectedDate: '2026-09-22', items: 3, totalAmount: 3200000, status: 'APPROVED' },
  { id: '3', orderNumber: 'PO-2026-003', supplierName: 'Công ty CP Dược phẩm PetCare', orderDate: '2026-09-17', expectedDate: '2026-09-24', items: 8, totalAmount: 8500000, status: 'PENDING' },
];

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', minimumFractionDigits: 0 }).format(amount);
}

export function AdminPurchasingPage() {
  const pendingCount = MOCK_ORDERS.filter(o => o.status === 'PENDING').length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Mua hàng nhà cung cấp</h1>
        <p className="text-gray-500">Quản lý đơn đặt hàng và nhà cung cấp</p>
      </div>

      <div className="grid grid-cols-4 gap-4">
        <Card><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-blue-100"><Package className="h-6 w-6 text-blue-600" /></div><div><p className="text-2xl font-bold">{MOCK_ORDERS.length}</p><p className="text-sm text-gray-500">Tổng đơn</p></div></CardContent></Card>
        <Card className="border-yellow-500"><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-yellow-100"><Clock className="h-6 w-6 text-yellow-600" /></div><div><p className="text-2xl font-bold text-yellow-600">{pendingCount}</p><p className="text-sm text-gray-500">Chờ duyệt</p></div></CardContent></Card>
        <Card><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-green-100"><Check className="h-6 w-6 text-green-600" /></div><div><p className="text-2xl font-bold">{MOCK_ORDERS.filter(o => o.status === 'RECEIVED').length}</p><p className="text-sm text-gray-500">Đã nhận hàng</p></div></CardContent></Card>
        <Card><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-purple-100"><Truck className="h-6 w-6 text-purple-600" /></div><div><p className="text-2xl font-bold">4</p><p className="text-sm text-gray-500">Nhà cung cấp</p></div></CardContent></Card>
      </div>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle>Danh sách đơn đặt hàng</CardTitle>
          <Button><Plus className="mr-2 h-4 w-4" />Tạo đơn mới</Button>
        </CardHeader>
        <CardContent className="p-0">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-4 py-3 text-left text-sm font-medium">Mã đơn</th>
                  <th className="px-4 py-3 text-left text-sm font-medium">Nhà cung cấp</th>
                  <th className="px-4 py-3 text-left text-sm font-medium">Ngày đặt</th>
                  <th className="px-4 py-3 text-left text-sm font-medium">Ngày dự kiến</th>
                  <th className="px-4 py-3 text-left text-sm font-medium">Tổng tiền</th>
                  <th className="px-4 py-3 text-left text-sm font-medium">Trạng thái</th>
                  <th className="px-4 py-3 text-left text-sm font-medium">Thao tác</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {MOCK_ORDERS.map(order => (
                  <tr key={order.id} className="hover:bg-gray-50">
                    <td className="px-4 py-3 font-medium">{order.orderNumber}</td>
                    <td className="px-4 py-3 font-medium">{order.supplierName}</td>
                    <td className="px-4 py-3">{order.orderDate}</td>
                    <td className="px-4 py-3">{order.expectedDate}</td>
                    <td className="px-4 py-3 font-medium">{formatCurrency(order.totalAmount)}</td>
                    <td className="px-4 py-3">
                      <Badge className={order.status === 'RECEIVED' ? 'bg-green-100 text-green-700' : order.status === 'PENDING' ? 'bg-yellow-100 text-yellow-700' : 'bg-blue-100 text-blue-700'}>
                        {order.status === 'RECEIVED' ? 'Đã nhận' : order.status === 'PENDING' ? 'Chờ duyệt' : 'Đã duyệt'}
                      </Badge>
                    </td>
                    <td className="px-4 py-3"><Button size="sm" variant="ghost"><Eye className="h-4 w-4" /></Button></td>
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
