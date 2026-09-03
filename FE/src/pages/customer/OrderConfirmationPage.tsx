import { Link, useParams } from 'react-router-dom';
import { CheckCircle, Package, Truck } from 'lucide-react';

export function OrderConfirmationPage() {
  const { id } = useParams<{ id: string }>();
  const orderId = id || 'ORD001';

  return (
    <div className="mx-auto max-w-[720px] px-4 py-12 sm:px-6">
      <div className="text-center">
        <div className="mx-auto mb-6 flex h-20 w-20 items-center justify-center rounded-full bg-green-100">
          <CheckCircle size={40} className="text-green-600" />
        </div>
        <h1 className="font-[var(--font-friendly)] text-3xl font-bold text-gray-900 sm:text-4xl">
          Đặt hàng thành công!
        </h1>
        <p className="mt-3 text-gray-600">
          Cảm ơn bạn đã đặt hàng. Đơn hàng của bạn đang được xử lý.
        </p>
        <div className="mt-4 inline-block rounded-lg bg-gray-100 px-4 py-2">
          <span className="text-sm text-gray-500">Mã đơn hàng:</span>
          <span className="ml-2 font-bold text-gray-900">{orderId}</span>
        </div>
      </div>

      {/* Order Summary */}
      <div className="mt-10 rounded-xl border border-gray-200 bg-white p-6 shadow-sm">
        <h2 className="mb-4 font-[var(--font-friendly)] text-lg font-bold text-gray-900">Chi tiết đơn hàng</h2>
        <div className="space-y-4">
          <div className="flex items-center gap-4">
            <div className="flex h-12 w-12 items-center justify-center rounded-full bg-amber-50">
              <Package size={24} className="text-[#843122]" />
            </div>
            <div>
              <p className="font-semibold text-gray-900">Đang chuẩn bị</p>
              <p className="text-sm text-gray-500">Cửa hàng đang chuẩn bị đơn hàng của bạn</p>
            </div>
          </div>
          <div className="flex items-center gap-4">
            <div className="flex h-12 w-12 items-center justify-center rounded-full bg-blue-50">
              <Truck size={24} className="text-blue-600" />
            </div>
            <div>
              <p className="font-semibold text-gray-900">Giao hàng</p>
              <p className="text-sm text-gray-500">Dự kiến giao trong 2-3 ngày làm việc</p>
            </div>
          </div>
        </div>
      </div>

      {/* Address */}
      <div className="mt-6 rounded-xl border border-gray-200 bg-white p-6 shadow-sm">
        <h2 className="mb-4 font-[var(--font-friendly)] text-lg font-bold text-gray-900">Địa chỉ giao hàng</h2>
        <p className="text-gray-600">
          Nguyễn Văn A<br />
          123 Đường ABC, Phường XYZ<br />
          Quận 1, TP. Hồ Chí Minh<br />
          Điện thoại: 0901 234 567
        </p>
      </div>

      {/* Actions */}
      <div className="mt-8 flex flex-col gap-4 sm:flex-row">
        <Link
          to="/orders"
          className="flex-1 rounded-lg border-2 border-[#843122] py-3 text-center font-bold text-[#843122] hover:bg-amber-50 transition-colors"
        >
          Xem lịch sử đơn hàng
        </Link>
        <Link
          to="/shop"
          className="flex-1 rounded-lg bg-[#843122] py-3 text-center font-bold text-white hover:bg-[#6a2517] transition-colors"
        >
          Tiếp tục mua sắm
        </Link>
      </div>
    </div>
  );
}
