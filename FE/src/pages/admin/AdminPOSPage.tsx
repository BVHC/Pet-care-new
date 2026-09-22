import { useState } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Badge } from '../../components/ui/badge';
import { ScrollArea } from '../../components/ui/scroll-area';
import { Separator } from '../../components/ui/separator';
import { Search, ShoppingCart, Trash2, CreditCard, Banknote, Minus, Plus, User } from 'lucide-react';
import { ConfirmModal, DetailModal, FormModal } from '../../components/ui/modal-templates';

interface CartItem { id: string; name: string; price: number; quantity: number; }

const MOCK_PRODUCTS = [
  { id: '1', name: 'Thức ăn Royal Canin (mèo)', price: 450000, category: 'Thức ăn', stock: 25 },
  { id: '2', name: 'Thức ăn Pedigree (chó)', price: 380000, category: 'Thức ăn', stock: 30 },
  { id: '3', name: 'Sữa tắm diệt ve rận', price: 120000, category: 'Chăm sóc', stock: 15 },
  { id: '4', name: 'Vitamin tổng hợp', price: 280000, category: 'Dinh dưỡng', stock: 20 },
];

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', minimumFractionDigits: 0 }).format(amount);
}

export function AdminPOSPage() {
  const [cart, setCart] = useState<CartItem[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [paymentMethod, setPaymentMethod] = useState<'CASH' | 'CARD' | null>(null);
  const [paymentOpen, setPaymentOpen] = useState(false);
  const [receiptOpen, setReceiptOpen] = useState(false);
  const [customerOpen, setCustomerOpen] = useState(false);
  const [customerName, setCustomerName] = useState('');
  const products = MOCK_PRODUCTS.filter(p => p.name.toLowerCase().includes(searchQuery.toLowerCase()));

  const addToCart = (product: typeof MOCK_PRODUCTS[0]) => {
    setCart(prev => {
      const existing = prev.find(item => item.id === product.id);
      if (existing) return prev.map(item => item.id === product.id ? { ...item, quantity: item.quantity + 1 } : item);
      return [...prev, { id: product.id, name: product.name, price: product.price, quantity: 1 }];
    });
  };

  const updateQuantity = (id: string, delta: number) => {
    setCart(prev => prev.map(item => {
      if (item.id === id) {
        const newQty = item.quantity + delta;
        return newQty > 0 ? { ...item, quantity: newQty } : item;
      }
      return item;
    }).filter(item => item.quantity > 0));
  };

  const removeFromCart = (id: string) => setCart(prev => prev.filter(item => item.id !== id));
  const clearCart = () => setCart([]);
  const total = cart.reduce((sum, item) => sum + item.price * item.quantity, 0);
  const itemCount = cart.reduce((sum, item) => sum + item.quantity, 0);

  const handlePayment = () => { setPaymentMethod(paymentMethod || 'CASH'); setPaymentOpen(false); setReceiptOpen(true); };

  return (
    <div className="flex h-[calc(100vh-8rem)] gap-6">
      <div className="flex-1 flex flex-col min-w-0">
        <div className="mb-6">
          <h1 className="text-2xl font-semibold text-(--text-primary)">Quầy POS</h1>
          <p className="text-(--text-secondary)">Bán lẻ và thanh toán tại quầy</p>
        </div>

        <div className="mb-4">
          <div className="relative"><Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-(--text-tertiary)" /><Input placeholder="Tìm sản phẩm..." className="pl-10 input" value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} /></div>
        </div>

        <ScrollArea className="flex-1 pr-4">
          <div className="grid grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4 pb-4">
            {products.map((product) => (
              <Card key={product.id} className="card cursor-pointer hover:border-(--color-primary) transition-all" onClick={() => addToCart(product)}>
                <CardContent className="p-4">
                  <div className="flex justify-between items-start mb-3"><Badge variant="secondary" className="text-xs bg-(--bg-tertiary)">{product.category}</Badge><span className="text-xs text-(--text-tertiary)">Còn {product.stock}</span></div>
                  <p className="font-medium text-sm text-(--text-primary) mb-2 line-clamp-2">{product.name}</p>
                  <p className="text-lg font-semibold text-(--color-primary)">{formatCurrency(product.price)}</p>
                </CardContent>
              </Card>
            ))}
          </div>
        </ScrollArea>
      </div>

      <Card className="card w-96 flex flex-col shrink-0">
        <CardHeader className="pb-4">
          <CardTitle className="flex items-center justify-between text-base">
            <span className="flex items-center gap-2"><ShoppingCart className="h-5 w-5" /> Giỏ hàng</span>
            <Badge variant="secondary" className="bg-(--bg-tertiary)">{itemCount} sản phẩm</Badge>
          </CardTitle>
        </CardHeader>
        <CardContent className="flex-1 flex flex-col min-h-0">
          <ScrollArea className="flex-1 mb-4">
            {cart.length === 0 ? (
              <div className="flex flex-col items-center justify-center h-40 text-(--text-tertiary)"><ShoppingCart className="h-12 w-12 mb-3 opacity-50" /><p>Giỏ hàng trống</p></div>
            ) : (
              <div className="space-y-3">
                {cart.map((item) => (
                  <div key={item.id} className="flex items-center justify-between py-3 border-b border-(--border-subtle)">
                    <div className="flex-1 min-w-0 pr-3"><p className="font-medium text-sm text-(--text-primary) truncate">{item.name}</p><p className="text-xs text-(--text-secondary)">{formatCurrency(item.price)}</p></div>
                    <div className="flex items-center gap-1.5">
                      <Button variant="outline" size="icon" className="h-7 w-7" onClick={() => updateQuantity(item.id, -1)}><Minus className="h-3 w-3" /></Button>
                      <span className="w-6 text-center text-sm font-medium">{item.quantity}</span>
                      <Button variant="outline" size="icon" className="h-7 w-7" onClick={() => updateQuantity(item.id, 1)}><Plus className="h-3 w-3" /></Button>
                      <Button variant="ghost" size="icon" className="h-7 w-7 text-red-500 hover:text-red-600 hover:bg-red-50" onClick={() => removeFromCart(item.id)}><Trash2 className="h-4 w-4" /></Button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </ScrollArea>

          <Separator className="my-4" />

          <div className="space-y-2 mb-4">
            <div className="flex justify-between text-lg font-semibold"><span className="text-(--text-primary)">Tổng cộng</span><span className="text-(--color-primary)">{formatCurrency(total)}</span></div>
          </div>

          <div className="grid grid-cols-2 gap-3 mb-3">
            <Button size="lg" className="bg-green-600 hover:bg-green-700 text-white" onClick={() => { if (cart.length > 0) setPaymentOpen(true); }}><Banknote className="mr-2 h-4 w-4" /> Tiền mặt</Button>
            <Button size="lg" variant="outline" className="border-(--border-color)" onClick={() => { if (cart.length > 0) { setPaymentMethod('CARD'); setReceiptOpen(true); } }}><CreditCard className="mr-2 h-4 w-4" /> Thẻ</Button>
          </div>
          <Button size="lg" variant="outline" className="w-full" onClick={() => setCustomerOpen(true)}><User className="mr-2 h-4 w-4" /> Khách hàng</Button>
          {cart.length > 0 && <Button size="sm" variant="ghost" className="w-full mt-2 text-red-500" onClick={clearCart}>Xóa giỏ hàng</Button>}
        </CardContent>
      </Card>

      {/* MODALS */}
      <DetailModal open={receiptOpen} onOpenChange={setReceiptOpen} title="Hóa đơn" size="sm">
        <div className="space-y-4">
          <div className="text-center pb-4 border-b border-(--color-border-light)">
            <h3 className="text-lg font-semibold">Pet Care Clinic</h3>
            <p className="text-sm text-(--text-secondary)">Hóa đơn thanh toán</p>
          </div>
          <div className="space-y-2">
            {cart.map(item => <div key={item.id} className="flex justify-between text-sm"><span>{item.name} x{item.quantity}</span><span className="font-medium">{formatCurrency(item.price * item.quantity)}</span></div>)}
          </div>
          <Separator />
          <div className="flex justify-between font-semibold"><span>Tổng cộng</span><span className="text-(--color-primary)">{formatCurrency(total)}</span></div>
          <div className="text-sm text-(--text-secondary)"><span>Thanh toán: </span><Badge variant="secondary">{paymentMethod === 'CASH' ? 'Tiền mặt' : paymentMethod === 'CARD' ? 'Thẻ' : '-'}</Badge></div>
          {customerName && <div className="text-sm text-(--text-secondary)">Khách hàng: {customerName}</div>}
        </div>
      </DetailModal>

      <ConfirmModal open={paymentOpen} onOpenChange={setPaymentOpen} type="success" title="Xác nhận thanh toán?" description={`Thanh toán ${formatCurrency(total)} bằng tiền mặt?`} confirmText="Thanh toán" onConfirm={handlePayment} />

      <FormModal open={customerOpen} onOpenChange={setCustomerOpen} title="Thông tin khách hàng" description="Nhập thông tin khách hàng (tùy chọn)" onSubmit={() => setCustomerOpen(false)} submitText="Lưu" size="sm">
        <div><label className="block text-sm font-medium mb-1.5">Tên khách hàng</label><Input placeholder="Nhập tên..." value={customerName} onChange={(e) => setCustomerName(e.target.value)} /></div>
      </FormModal>
    </div>
  );
}
