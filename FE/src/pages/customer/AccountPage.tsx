import { useState } from 'react';
import { Link } from 'react-router-dom';
import { User, Package, PawPrint, Star, Settings, LogOut } from 'lucide-react';

export function AccountPage() {
  const [activeTab, setActiveTab] = useState('profile');
  const [profile, setProfile] = useState({
    name: 'Nguyễn Văn A',
    phone: '0901 234 567',
    email: 'nguyenvana@email.com',
    address: '123 Đường ABC, Phường XYZ, Quận 1, TP.HCM',
  });

  const handleSave = (e: React.FormEvent) => {
    e.preventDefault();
    alert('Cập nhật thông tin thành công!');
  };

  return (
    <div className="mx-auto max-w-[1000px] px-4 py-8 sm:px-6">
      <h1 className="mb-8 font-[var(--font-friendly)] text-3xl font-bold text-gray-900">
        Tài khoản của tôi
      </h1>

      <div className="grid gap-6 lg:grid-cols-[240px_1fr]">
        {/* Sidebar */}
        <aside className="space-y-1">
          <button
            onClick={() => setActiveTab('profile')}
            className={`flex w-full items-center gap-3 rounded-lg px-4 py-3 text-left transition-colors ${
              activeTab === 'profile' ? 'bg-[#843122] text-white' : 'hover:bg-gray-100'
            }`}
          >
            <User size={20} /> Hồ sơ
          </button>
          <Link
            to="/orders"
            className={`flex items-center gap-3 rounded-lg px-4 py-3 transition-colors ${
              activeTab === 'orders' ? 'bg-[#843122] text-white' : 'hover:bg-gray-100'
            }`}
          >
            <Package size={20} /> Đơn hàng
          </Link>
          <Link
            to="/pets"
            className="flex items-center gap-3 rounded-lg px-4 py-3 hover:bg-gray-100 transition-colors"
          >
            <PawPrint size={20} /> Thú cưng
          </Link>
          <button className="flex w-full items-center gap-3 rounded-lg px-4 py-3 hover:bg-gray-100 transition-colors">
            <Star size={20} /> Đánh giá
          </button>
          <button className="flex w-full items-center gap-3 rounded-lg px-4 py-3 hover:bg-gray-100 transition-colors">
            <Settings size={20} /> Cài đặt
          </button>
          <button className="flex w-full items-center gap-3 rounded-lg px-4 py-3 text-red-500 hover:bg-red-50 transition-colors">
            <LogOut size={20} /> Đăng xuất
          </button>
        </aside>

        {/* Content */}
        <div className="rounded-xl border border-gray-200 bg-white p-6 shadow-sm">
          {activeTab === 'profile' && (
            <form onSubmit={handleSave} className="space-y-6">
              <h2 className="font-[var(--font-friendly)] text-xl font-bold text-gray-900">
                Thông tin cá nhân
              </h2>

              <div className="flex items-center gap-6 pb-6 border-b border-gray-100">
                <div className="h-20 w-20 rounded-full bg-amber-100 flex items-center justify-center">
                  <User size={32} className="text-[#843122]" />
                </div>
                <button type="button" className="text-sm font-semibold text-[#843122] hover:underline">
                  Đổi ảnh đại diện
                </button>
              </div>

              <div className="grid gap-4 sm:grid-cols-2">
                <div>
                  <label className="mb-2 block text-sm font-semibold text-gray-700">Họ tên</label>
                  <input
                    type="text"
                    value={profile.name}
                    onChange={(e) => setProfile({ ...profile, name: e.target.value })}
                    className="w-full rounded-lg border border-gray-200 px-4 py-3 focus:border-[#843122] focus:outline-none"
                  />
                </div>
                <div>
                  <label className="mb-2 block text-sm font-semibold text-gray-700">Số điện thoại</label>
                  <input
                    type="tel"
                    value={profile.phone}
                    onChange={(e) => setProfile({ ...profile, phone: e.target.value })}
                    className="w-full rounded-lg border border-gray-200 px-4 py-3 focus:border-[#843122] focus:outline-none"
                  />
                </div>
                <div className="sm:col-span-2">
                  <label className="mb-2 block text-sm font-semibold text-gray-700">Email</label>
                  <input
                    type="email"
                    value={profile.email}
                    onChange={(e) => setProfile({ ...profile, email: e.target.value })}
                    className="w-full rounded-lg border border-gray-200 px-4 py-3 focus:border-[#843122] focus:outline-none"
                  />
                </div>
                <div className="sm:col-span-2">
                  <label className="mb-2 block text-sm font-semibold text-gray-700">Địa chỉ</label>
                  <input
                    type="text"
                    value={profile.address}
                    onChange={(e) => setProfile({ ...profile, address: e.target.value })}
                    className="w-full rounded-lg border border-gray-200 px-4 py-3 focus:border-[#843122] focus:outline-none"
                  />
                </div>
              </div>

              <button
                type="submit"
                className="rounded-lg bg-[#843122] px-6 py-3 font-bold text-white hover:bg-[#6a2517] transition-colors"
              >
                Lưu thay đổi
              </button>
            </form>
          )}
        </div>
      </div>
    </div>
  );
}
