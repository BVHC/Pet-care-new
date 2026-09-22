import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { CreditCard, Truck, Wallet } from 'lucide-react';

const PAYMENT_METHODS = [
  { id: 'cod', label: 'Thanh toán khi nhận hàng (COD)', desc: 'Thanh toán khi đơn hàng đến nơi', icon: Truck },
  { id: 'vnpay', label: 'VNPAY', desc: 'Thanh toán bằng thẻ ngân hàng hoặc QR', icon: CreditCard },
  { id: 'momo', label: 'Ví MoMo', desc: 'Thanh toán bằng ví điện tử MoMo', icon: Wallet },
];

function formatVnd(price: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 }).format(price);
}

const MOCK_CART = [
  { id: 1, name: 'Royal Canin Adult', price: 450000, qty: 1, image: '/imgs/prod1.jpg' },
  { id: 2, name: 'Interactive Ball', price: 89000, qty: 2, image: '/imgs/prod4.jpg' },
];

export function CheckoutPage() {
  const navigate = useNavigate();
  const [method, setMethod] = useState('cod');
  const [form, setForm] = useState({
    fullName: '',
    phone: '',
    address: '',
    city: '',
    note: '',
  });

  const subtotal = MOCK_CART.reduce((sum, item) => sum + item.price * item.qty, 0);
  const shipping = subtotal >= 300000 ? 0 : 30000;
  const total = subtotal + shipping;

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!form.fullName || !form.phone || !form.address || !form.city) {
      alert('Vui lòng điền đầy đủ thông tin!');
      return;
    }
    navigate('/order/ORD001');
  };

  if (MOCK_CART.length === 0) {
    return (
      <div className="mx-auto max-w-[1200px] px-4 py-24 text-center">
        <p className="font-semibold text-gray-900">Giỏ hàng của bạn đang trống.</p>
        <Link to="/cart" className="mt-3 inline-block text-sm font-semibold text-[#843122] hover:underline">
          Quay lại giỏ hàng
        </Link>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-[1200px] px-4 py-8 sm:px-6">
      <nav className="mb-4 flex flex-wrap items-center gap-2 text-sm text-gray-500">
        <Link to="/" className="hover:text-[#843122]">Trang chủ</Link>
        <span>/</span>
        <Link to="/cart" className="hover:text-[#843122]">Giỏ hàng</Link>
        <span>/</span>
        <span className="font-semibold text-gray-900">Thanh toán</span>
      </nav>

      <h1 className="mb-8 font-friendly text-2xl font-bold text-gray-900 sm:text-3xl">
        Thanh toán
      </h1>

      <form onSubmit={handleSubmit}>
        <div className="grid gap-8 lg:grid-cols-[1fr_320px]">
          <div className="flex flex-col gap-6">
            {/* Thông tin giao hàng */}
            <section className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
              <h2 className="mb-5 font-friendly text-lg font-bold text-gray-900">
                Thông tin giao hàng
              </h2>

              <div className="flex flex-col gap-4">
                <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                  <div>
                    <label className="mb-1.5 block text-sm font-semibold text-gray-900">
                      Họ tên <span className="text-red-500">*</span>
                    </label>
                    <input
                      type="text"
                      value={form.fullName}
                      onChange={(e) => setForm({ ...form, fullName: e.target.value })}
                      placeholder="Nguyễn Văn A"
                      className="w-full rounded-lg border border-gray-200 bg-white px-4 py-3 text-gray-900 focus:border-[#843122] focus:outline-none"
                    />
                  </div>
                  <div>
                    <label className="mb-1.5 block text-sm font-semibold text-gray-900">
                      Số điện thoại <span className="text-red-500">*</span>
                    </label>
                    <input
                      type="tel"
                      value={form.phone}
                      onChange={(e) => setForm({ ...form, phone: e.target.value })}
                      placeholder="0901 234 567"
                      className="w-full rounded-lg border border-gray-200 bg-white px-4 py-3 text-gray-900 focus:border-[#843122] focus:outline-none"
                    />
                  </div>
                </div>

                <div>
                  <label className="mb-1.5 block text-sm font-semibold text-gray-900">
                    Địa chỉ <span className="text-red-500">*</span>
                  </label>
                  <input
                    type="text"
                    value={form.address}
                    onChange={(e) => setForm({ ...form, address: e.target.value })}
                    placeholder="123 Đường ABC, Phường XYZ"
                    className="w-full rounded-lg border border-gray-200 bg-white px-4 py-3 text-gray-900 focus:border-[#843122] focus:outline-none"
                  />
                </div>

                <div>
                  <label className="mb-1.5 block text-sm font-semibold text-gray-900">
                    Thành phố / Tỉnh <span className="text-red-500">*</span>
                  </label>
                  <input
                    type="text"
                    value={form.city}
                    onChange={(e) => setForm({ ...form, city: e.target.value })}
                    placeholder="Hà Nội"
                    className="w-full rounded-lg border border-gray-200 bg-white px-4 py-3 text-gray-900 focus:border-[#843122] focus:outline-none"
                  />
                </div>

                <div>
                  <label className="mb-1.5 block text-sm font-semibold text-gray-900">Ghi chú</label>
                  <textarea
                    value={form.note}
                    onChange={(e) => setForm({ ...form, note: e.target.value })}
                    placeholder="Ghi chú cho đơn hàng (không bắt buộc)"
                    rows={3}
                    className="w-full rounded-lg border border-gray-200 bg-white px-4 py-3 text-gray-900 focus:border-[#843122] focus:outline-none resize-none"
                  />
                </div>
              </div>
            </section>

            {/* Phương thức thanh toán */}
            <section className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
              <h2 className="mb-4 font-friendly text-lg font-bold text-gray-900">
                Phương thức thanh toán
              </h2>

              <div className="flex flex-col gap-3">
                {PAYMENT_METHODS.map((m) => (
                  <button
                    key={m.id}
                    type="button"
                    onClick={() => setMethod(m.id)}
                    className={`flex w-full cursor-pointer items-center gap-4 rounded-xl border-2 p-4 text-left transition-all ${
                      method === m.id
                        ? 'border-[#843122] bg-amber-50'
                        : 'border-gray-200 bg-white hover:border-gray-300'
                    }`}
                  >
                    <div className={`flex h-11 w-11 items-center justify-center rounded-full ${
                      method === m.id ? 'bg-[#843122] text-white' : 'bg-gray-100 text-gray-600'
                    }`}>
                      <m.icon size={22} />
                    </div>
                    <div className="flex-1">
                      <p className="font-semibold text-gray-900">{m.label}</p>
                      <p className="text-sm text-gray-500">{m.desc}</p>
                    </div>
                    <div className={`flex h-5 w-5 items-center justify-center rounded-full border-2 ${
                      method === m.id ? 'border-[#843122]' : 'border-gray-300'
                    }`}>
                      {method === m.id && <div className="h-2.5 w-2.5 rounded-full bg-[#843122]" />}
                    </div>
                  </button>
                ))}
              </div>
            </section>
          </div>

          {/* Tóm tắt đơn hàng */}
          <div className="h-fit rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
            <h2 className="mb-4 font-friendly text-lg font-bold text-gray-900">
              Tóm tắt đơn hàng
            </h2>

            <div className="flex flex-col gap-2 text-sm">
              {MOCK_CART.map((item) => (
                <div key={item.id} className="flex items-center justify-between gap-2">
                  <span className="flex-1 truncate text-gray-600">
                    {item.name}
                    <span className="ml-1 text-gray-400">×{item.qty}</span>
                  </span>
                  <span className="shrink-0 font-semibold text-gray-900">
                    {formatVnd(item.price * item.qty)}
                  </span>
                </div>
              ))}
            </div>

            <div className="my-4 border-t border-gray-100" />

            <div className="flex flex-col gap-2 text-sm">
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
            </div>

            <div className="my-4 border-t border-gray-100" />

            <div className="mb-5 flex justify-between">
              <span className="font-friendly font-bold text-gray-900">Tổng cộng</span>
              <span className="font-friendly text-xl font-bold text-gray-900">
                {formatVnd(total)}
              </span>
            </div>

            <button
              type="submit"
              className="w-full rounded-lg bg-[#843122] py-3 text-sm font-bold text-white hover:bg-[#6a2517] transition-colors"
            >
              Đặt hàng
            </button>
          </div>
        </div>
      </form>
    </div>
  );
}
