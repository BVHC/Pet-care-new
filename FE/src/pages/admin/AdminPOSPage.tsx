import { useState } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Badge } from '../../components/ui/badge';
import { ScrollArea } from '../../components/ui/scroll-area';
import { Separator } from '../../components/ui/separator';
import { Search, ShoppingCart, Trash2, CreditCard, Banknote, Minus, Plus } from 'lucide-react';

interface CartItem { id: string; name: string; price: number; quantity: number; }

const MOCK_PRODUCTS = [
  { id: '1', name: 'Thức ăn Royal Canin (mèo)', price: 450000, category: 'Thức ăn', stock: 25 },
  { id: '2', name: 'Thức ăn Pedigree (chó)', price: 380000, category: 'Thức ăn', stock: 30 },
  { id: '3', name: 'Sữa tắm diệt ve rận', price: 120000, category: 'Chăm sóc', stock: 15 },
  { id: '4', name: 'Vitamin tổng hợp', price: 280000, category: 'Dinh dưỡng', stock: 20 },
  { id: '5', name: 'Thuốc nhỏ mắt', price: 95000, category: 'Y tế', stock: 12 },
  { id: '6', name: 'Bàn chải lông', price: 65000, category: 'Chăm sóc', stock: 40 },
  { id: '7', name: 'Xương gặm sạch răng', price: 85000, category: 'Phụ kiện', stock: 35 },
  { id: '8', name: 'Bình nước mini', price: 75000, category: 'Phụ kiện', stock: 18 },
  { id: '9', name: 'Đồ chơi bóng', price: 45000, category: 'Phụ kiện', stock: 50 },
  { id: '10', name: 'Sữa uống cho mèo', price: 180000, category: 'Dinh dưỡng', stock: 22 },
];

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', minimumFractionDigits: 0 }).format(amount);
}

export function AdminPOSPage() {
  const [cart, setCart] = useState<CartItem[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
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
  const total = cart.reduce((sum, item) => sum + item.price * item.quantity, 0);
  const itemCount = cart.reduce((sum, item) => sum + item.quantity, 0);

  return (
    <div className="flex h-[calc(100vh-8rem)] gap-6">
      {/* Products */}
      <div className="flex-1 flex flex-col min-w-0">
        <div className="mb-6">
          <h1 className="text-2xl font-semibold text-[var(--text-primary)]">Quầy POS</h1>
          <p className="text-[var(--text-secondary)]">Bán lẻ và thanh toán tại quầy</p>
        </div>

        <div className="mb-4">
          <div className="relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-[var(--text-tertiary)]" />
            <Input
              placeholder="Tìm sản phẩm..."
              className="pl-10 input"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
        </div>

        <ScrollArea className="flex-1 pr-4">
          <div className="grid grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4 pb-4">
            {products.map((product) => (
              <Card
                key={product.id}
                className="card cursor-pointer hover:border-[var(--color-primary)] transition-all"
                onClick={() => addToCart(product)}
              >
                <CardContent className="p-4">
                  <div className="flex justify-between items-start mb-3">
                    <Badge variant="secondary" className="text-xs bg-[var(--bg-tertiary)]">{product.category}</Badge>
                    <span className="text-xs text-[var(--text-tertiary)]">Còn {product.stock}</span>
                  </div>
                  <p className="font-medium text-sm text-[var(--text-primary)] mb-2 line-clamp-2">{product.name}</p>
                  <p className="text-lg font-semibold text-[var(--color-primary)]">{formatCurrency(product.price)}</p>
                </CardContent>
              </Card>
            ))}
          </div>
        </ScrollArea>
      </div>

      {/* Cart */}
      <Card className="card w-96 flex flex-col flex-shrink-0">
        <CardHeader className="pb-4">
          <CardTitle className="flex items-center justify-between text-base">
            <span className="flex items-center gap-2">
              <ShoppingCart className="h-5 w-5" />
              Giỏ hàng
            </span>
            <Badge variant="secondary" className="bg-[var(--bg-tertiary)]">{itemCount} sản phẩm</Badge>
          </CardTitle>
        </CardHeader>
        <CardContent className="flex-1 flex flex-col min-h-0">
          <ScrollArea className="flex-1 mb-4">
            {cart.length === 0 ? (
              <div className="flex flex-col items-center justify-center h-40 text-[var(--text-tertiary)]">
                <ShoppingCart className="h-12 w-12 mb-3 opacity-50" />
                <p>Giỏ hàng trống</p>
              </div>
            ) : (
              <div className="space-y-3">
                {cart.map((item) => (
                  <div key={item.id} className="flex items-center justify-between py-3 border-b border-[var(--border-subtle)]">
                    <div className="flex-1 min-w-0 pr-3">
                      <p className="font-medium text-sm text-[var(--text-primary)] truncate">{item.name}</p>
                      <p className="text-xs text-[var(--text-secondary)]">{formatCurrency(item.price)}</p>
                    </div>
                    <div className="flex items-center gap-1.5">
                      <Button variant="outline" size="icon" className="h-7 w-7" onClick={() => updateQuantity(item.id, -1)}>
                        <Minus className="h-3 w-3" />
                      </Button>
                      <span className="w-6 text-center text-sm font-medium">{item.quantity}</span>
                      <Button variant="outline" size="icon" className="h-7 w-7" onClick={() => updateQuantity(item.id, 1)}>
                        <Plus className="h-3 w-3" />
                      </Button>
                      <Button variant="ghost" size="icon" className="h-7 w-7 text-red-500 hover:text-red-600 hover:bg-red-50" onClick={() => removeFromCart(item.id)}>
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </ScrollArea>

          <Separator className="my-4" />

          <div className="space-y-2 mb-4">
            <div className="flex justify-between text-lg font-semibold">
              <span className="text-[var(--text-primary)]">Tổng cộng</span>
              <span className="text-[var(--color-primary)]">{formatCurrency(total)}</span>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <Button size="lg" className="bg-green-600 hover:bg-green-700 text-white">
              <Banknote className="mr-2 h-4 w-4" /> Tiền mặt
            </Button>
            <Button size="lg" variant="outline" className="border-[var(--border-color)]">
              <CreditCard className="mr-2 h-4 w-4" /> Thẻ
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
