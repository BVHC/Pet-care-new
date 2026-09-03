import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Minus, Plus, ShoppingCart, Trash2 } from 'lucide-react';

interface CartItem {
  id: number;
  productId: number;
  productName: string;
  quantity: number;
  unitPrice: number;
  subtotal: number;
  image?: string;
}

const MOCK_CART: CartItem[] = [];

function formatVnd(price: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 }).format(price);
}

export function CartPage() {
  const navigate = useNavigate();
  const [cart, setCart] = useState<CartItem[]>(MOCK_CART);

  const subtotal = cart.reduce((sum, item) => sum + item.subtotal, 0);
  const shipping = subtotal > 0 ? (subtotal >= 300000 ? 0 : 30000) : 0;
  const total = subtotal + shipping;

  const updateQty = (id: number, newQty: number) => {
    if (newQty < 1) return;
    setCart(cart.map(item =>
      item.id === id
        ? { ...item, quantity: newQty, subtotal: item.unitPrice * newQty }
        : item
    ));
  };

  const removeItem = (id: number) => {
    setCart(cart.filter(item => item.id !== id));
  };

  return (
    <div className="mx-auto max-w-[1200px] px-4 py-8 sm:px-6">
      <nav className="mb-4 flex flex-wrap items-center gap-2 text-sm text-gray-500">
        <Link to="/" className="hover:text-[#843122]">Trang chủ</Link>
        <span>/</span>
        <Link to="/shop" className="hover:text-[#843122]">Cửa hàng</Link>
        <span>/</span>
        <span className="font-semibold text-gray-900">Giỏ hàng</span>
      </nav>

      <h1 className="mb-8 font-[var(--font-friendly)] text-2xl font-bold text-gray-900 sm:text-3xl">
        Giỏ hàng của bạn
      </h1>

      {cart.length === 0 ? (
        <div className="flex flex-col items-center gap-5 py-20 text-center">
          <div className="flex items-center justify-center rounded-full bg-amber-100" style={{ width: 80, height: 80 }}>
            <ShoppingCart size={36} className="text-[#843122]" />
          </div>
          <div>
            <p className="font-[var(--font-friendly)] text-lg font-bold text-gray-900">
              Giỏ hàng của bạn đang trống
            </p>
            <p className="mt-1 text-sm text-gray-500">
              Hãy thêm sản phẩm vào giỏ để tiếp tục mua sắm.
            </p>
          </div>
          <Link
            to="/shop"
            className="rounded-full bg-[#843122] px-6 py-3 text-sm font-bold text-white hover:bg-[#6a2517] transition-colors"
          >
            Xem sản phẩm
          </Link>
        </div>
      ) : (
        <div className="grid gap-8 lg:grid-cols-[1fr_320px]">
          <div className="rounded-xl border border-gray-200 bg-white shadow-sm">
            {cart.map((item, idx) => (
              <div
                key={item.id}
                className={`flex items-center gap-4 p-4 sm:gap-5 sm:p-5 ${
                  idx < cart.length - 1 ? 'border-b border-gray-100' : ''
                }`}
              >
                <div className="h-20 w-20 shrink-0 overflow-hidden rounded-lg bg-gray-100">
                  {item.image ? (
                    <img src={item.image} alt={item.productName} className="h-full w-full object-cover" />
                  ) : (
                    <div className="flex h-full w-full items-center justify-center opacity-30">
                      <ShoppingCart size={24} />
                    </div>
                  )}
                </div>

                <div className="flex flex-1 flex-col gap-1 min-w-0">
                  <p className="truncate font-[var(--font-friendly)] font-semibold text-gray-900">
                    {item.productName}
                  </p>
                  <p className="text-xs text-gray-500">
                    Đơn giá: {formatVnd(item.unitPrice)}
                  </p>
                </div>

                <div className="flex shrink-0 items-center rounded-lg border border-gray-200">
                  <button
                    onClick={() => updateQty(item.id, item.quantity - 1)}
                    aria-label="Giảm số lượng"
                    className="cursor-pointer p-2 transition-colors hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-40"
                    disabled={item.quantity <= 1}
                  >
                    <Minus size={14} />
                  </button>
                  <span className="w-10 text-center text-sm font-semibold text-gray-900">
                    {item.quantity}
                  </span>
                  <button
                    onClick={() => updateQty(item.id, item.quantity + 1)}
                    aria-label="Tăng số lượng"
                    className="cursor-pointer p-2 transition-colors hover:bg-gray-100"
                  >
                    <Plus size={14} />
                  </button>
                </div>

                <div className="w-28 shrink-0 text-right font-semibold text-gray-900">
                  {formatVnd(item.subtotal)}
                </div>

                <button
                  onClick={() => removeItem(item.id)}
                  aria-label={`Xoá ${item.productName} khỏi giỏ hàng`}
                  className="cursor-pointer rounded-lg p-2 text-gray-400 transition-colors hover:bg-red-50 hover:text-red-500"
                >
                  <Trash2 size={16} />
                </button>
              </div>
            ))}
          </div>

          <div className="h-fit rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
            <h2 className="mb-4 font-[var(--font-friendly)] text-lg font-bold text-gray-900">
              Tóm tắt đơn hàng
            </h2>

            <div className="flex flex-col gap-3 text-sm">
              <div className="flex justify-between">
                <span className="text-gray-500">Tạm tính</span>
                <span className="font-semibold text-gray-900">{formatVnd(subtotal)}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-500">Phí vận chuyển</span>
                <span className={`font-semibold ${shipping === 0 ? 'text-green-600' : 'text-gray-900'}`}>
                  {shipping === 0 ? 'Miễn phí' : formatVnd(shipping)}
                </span>
              </div>
              {subtotal > 0 && subtotal < 300000 && (
                <p className="rounded-lg bg-blue-50 px-3 py-2 text-xs text-blue-600">
                  Thêm {formatVnd(300000 - subtotal)} để được miễn phí vận chuyển!
                </p>
              )}
            </div>

            <div className="my-4 border-t border-gray-100" />

            <div className="mb-5 flex justify-between">
              <span className="font-[var(--font-friendly)] font-bold text-gray-900">Tổng cộng</span>
              <span className="font-[var(--font-friendly)] text-xl font-bold text-gray-900">
                {formatVnd(total)}
              </span>
            </div>

            <button
              onClick={() => navigate('/checkout')}
              className="w-full rounded-lg bg-[#843122] py-3 text-sm font-bold text-white hover:bg-[#6a2517] transition-colors"
            >
              Thanh toán
            </button>

            <div className="mt-3 text-center">
              <Link
                to="/shop"
                className="text-sm font-semibold text-[#843122] hover:underline"
              >
                Tiếp tục mua sắm
              </Link>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
