import { useState } from 'react';
import { Link } from 'react-router-dom';
import {
  User,
  Package,
  PawPrint,
  Star,
  Settings,
  LogOut,
  ChevronRight,
  Plus,
  Edit2,
  Trash2,
  Award,
  Calendar,
  Sparkles,
  Heart,
  Bell,
  Shield,
  CreditCard,
  HelpCircle,
  Cat
} from 'lucide-react';

// Custom Dog icon (Lucide doesn't have a dog icon)
const DogIcon = ({ size = 16, className = '' }: { size?: number; className?: string }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" className={className}>
    <path d="M4.5 9.5C3.4 9.5 2.5 10.4 2.5 11.5C2.5 12.6 3.4 13.5 4.5 13.5C5.6 13.5 6.5 12.6 6.5 11.5C6.5 10.4 5.6 9.5 4.5 9.5M19.5 9.5C18.4 9.5 17.5 10.4 17.5 11.5C17.5 12.6 18.4 13.5 19.5 13.5C20.6 13.5 21.5 12.6 21.5 11.5C21.5 10.4 20.6 9.5 19.5 9.5M12 4C8 4 5 7 5 11C5 13 5.5 14.5 6.5 16L8 18.5L9 21H10L10.5 18.5H13.5L14 21H15L16 18.5L17.5 16C18.5 14.5 19 13 19 11C19 7 16 4 12 4Z"/>
  </svg>
);

interface Pet {
  id: number;
  name: string;
  type: 'dog' | 'cat';
  breed: string;
  age: string;
  weight: string;
  image: string;
}

const MOCK_PETS: Pet[] = [
  { id: 1, name: 'Milo', type: 'dog', breed: 'Golden Retriever', age: '3 tuổi', weight: '25kg', image: 'https://images.unsplash.com/photo-1552053831-71594a27632a?w=400' },
  { id: 2, name: 'Luna', type: 'cat', breed: 'Maine Coon', age: '2 tuổi', weight: '6kg', image: 'https://images.unsplash.com/photo-1514888286974-6c03e2ca1dba?w=400' },
];

interface MenuItem {
  icon: React.ElementType;
  label: string;
  href?: string;
  badge?: number;
  danger?: boolean;
}

export function AccountPage() {
  const [activeTab, setActiveTab] = useState('profile');
  const [pets, setPets] = useState<Pet[]>(MOCK_PETS);
  const [showPetForm, setShowPetForm] = useState(false);
  const [editingPet, setEditingPet] = useState<Pet | null>(null);
  const [petForm, setPetForm] = useState({ name: '', type: 'dog' as 'dog' | 'cat', breed: '', age: '', weight: '' });
  const [profile, setProfile] = useState({
    name: 'Nguyễn Văn A',
    phone: '0901 234 567',
    email: 'nguyenvana@email.com',
    address: '123 Đường ABC, Phường XYZ, Quận 1, TP.HCM',
  });

  // Sidebar menu items
  const menuItems: MenuItem[] = [
    { icon: User, label: 'Hồ sơ', href: '#profile' },
    { icon: PawPrint, label: 'Thú cưng', href: '#pets', badge: pets.length },
    { icon: Package, label: 'Đơn hàng', href: '/orders' },
    { icon: Sparkles, label: 'Gợi ý sản phẩm', href: '/recommend' },
    { icon: Heart, label: 'Yêu thích' },
    { icon: Bell, label: 'Thông báo' },
    { icon: CreditCard, label: 'Thanh toán' },
    { icon: Shield, label: 'Bảo mật' },
    { icon: HelpCircle, label: 'Trợ giúp' },
  ];

  const settingsItems: MenuItem[] = [
    { icon: Settings, label: 'Cài đặt' },
  ];

  const handleSaveProfile = (e: React.FormEvent) => {
    e.preventDefault();
    alert('Cập nhật thông tin thành công!');
  };

  const handleAddPet = (e: React.FormEvent) => {
    e.preventDefault();
    if (editingPet) {
      setPets(pets.map(p => p.id === editingPet.id ? { ...editingPet, ...petForm } : p));
    } else {
      setPets([...pets, { id: Date.now(), ...petForm, image: 'https://images.unsplash.com/photo-1543466835-00a7907e9de1?w=400' }]);
    }
    setShowPetForm(false);
    setEditingPet(null);
    setPetForm({ name: '', type: 'dog', breed: '', age: '', weight: '' });
  };

  const handleEditPet = (pet: Pet) => {
    setEditingPet(pet);
    setPetForm({ name: pet.name, type: pet.type, breed: pet.breed, age: pet.age, weight: pet.weight });
    setShowPetForm(true);
  };

  const handleDeletePet = (id: number) => {
    if (confirm('Xóa thú cưng này?')) {
      setPets(pets.filter(p => p.id !== id));
    }
  };

  return (
    <div className="mx-auto max-w-[1200px] px-4 py-8 sm:px-6">
      {/* Page Header */}
      <div className="mb-8">
        <h1 className="font-[var(--font-friendly)] text-3xl font-bold text-[var(--color-text-primary)]">
          Tài khoản của tôi
        </h1>
        <p className="mt-1 text-[var(--color-text-secondary)]">
          Quản lý hồ sơ, thú cưng và cài đặt tài khoản
        </p>
      </div>

      <div className="grid gap-6 lg:grid-cols-[280px_1fr]">
        {/* Sidebar */}
        <aside className="space-y-6">
          {/* User Card */}
          <div className="overflow-hidden rounded-xl bg-white shadow-[var(--shadow-1)]">
            <div className="bg-gradient-to-br from-[var(--color-brand-secondary)] to-[var(--color-brand-primary)] p-6 text-white">
              <div className="flex items-center gap-4">
                <div className="flex h-14 w-14 items-center justify-center rounded-full bg-white/20 backdrop-blur">
                  <User size={28} />
                </div>
                <div>
                  <h3 className="font-bold text-lg">{profile.name}</h3>
                  <p className="text-sm text-white/80">{profile.email}</p>
                </div>
              </div>
            </div>
          </div>

          {/* Navigation Menu */}
          <div className="overflow-hidden rounded-xl bg-white shadow-[var(--shadow-1)]">
            <div className="p-2">
              {menuItems.map((item) => {
                const Icon = item.icon;
                return (
                  <Link
                    key={item.label}
                    to={item.href || '#'}
                    onClick={() => item.href?.startsWith('#') ? setActiveTab(item.href.slice(1)) : undefined}
                    className="flex items-center gap-3 rounded-lg px-4 py-3 text-[var(--color-text-primary)] transition-all hover:bg-[var(--color-surface-sunken)] group"
                  >
                    <Icon size={20} className="text-[var(--color-brand-secondary)]" />
                    <span className="flex-1 font-medium">{item.label}</span>
                    {item.badge && (
                      <span className="flex h-6 min-w-[24px] items-center justify-center rounded-full bg-[var(--color-brand-tertiary)] px-2 text-xs font-bold text-[var(--color-text-on-brand)]">
                        {item.badge}
                      </span>
                    )}
                    <ChevronRight size={16} className="text-gray-300 group-hover:translate-x-1 transition-transform" />
                  </Link>
                );
              })}
            </div>
          </div>

          {/* Settings & Logout */}
          <div className="overflow-hidden rounded-xl bg-white shadow-[var(--shadow-1)]">
            <div className="p-2">
              {settingsItems.map((item) => {
                const Icon = item.icon;
                return (
                  <Link
                    key={item.label}
                    to="#"
                    className="flex items-center gap-3 rounded-lg px-4 py-3 text-[var(--color-text-primary)] transition-all hover:bg-[var(--color-surface-sunken)]"
                  >
                    <Icon size={20} className="text-gray-400" />
                    <span className="font-medium">{item.label}</span>
                  </Link>
                );
              })}
              <button className="flex w-full items-center gap-3 rounded-lg px-4 py-3 text-red-500 transition-all hover:bg-red-50">
                <LogOut size={20} />
                <span className="font-medium">Đăng xuất</span>
              </button>
            </div>
          </div>
        </aside>

        {/* Main Content */}
        <div className="space-y-6">
          {/* Profile Section */}
          <div id="profile" className="overflow-hidden rounded-xl bg-white shadow-[var(--shadow-1)]">
            <div className="border-b border-[var(--color-border-default)] px-6 py-4">
              <h2 className="font-[var(--font-friendly)] text-xl font-bold text-[var(--color-text-primary)]">
                Thông tin cá nhân
              </h2>
            </div>
            <form onSubmit={handleSaveProfile} className="p-6">
              <div className="flex items-center gap-6 pb-6 mb-6 border-b border-[var(--color-border-default)]">
                <div className="h-20 w-20 rounded-full bg-[var(--color-brand-tertiary)] flex items-center justify-center">
                  <User size={32} className="text-[var(--color-brand-secondary)]" />
                </div>
                <div>
                  <button type="button" className="rounded-lg border border-[var(--color-border-default)] px-4 py-2 text-sm font-semibold text-[var(--color-text-primary)] hover:bg-[var(--color-surface-sunken)] transition-colors">
                    Đổi ảnh đại diện
                  </button>
                  <p className="mt-1 text-xs text-[var(--color-text-secondary)]">JPG, PNG hoặc GIF. Kích thước tối đa 2MB</p>
                </div>
              </div>

              <div className="grid gap-5 sm:grid-cols-2">
                <div>
                  <label className="mb-2 block text-sm font-semibold text-[var(--color-text-primary)]">Họ tên</label>
                  <input
                    type="text"
                    value={profile.name}
                    onChange={(e) => setProfile({ ...profile, name: e.target.value })}
                    className="w-full rounded-lg border border-[var(--color-border-default)] bg-white px-4 py-3 text-[var(--color-text-primary)] focus:border-[var(--color-brand-secondary)] focus:outline-none focus:ring-2 focus:ring-[var(--color-brand-tertiary)]/20 transition-all"
                  />
                </div>
                <div>
                  <label className="mb-2 block text-sm font-semibold text-[var(--color-text-primary)]">Số điện thoại</label>
                  <input
                    type="tel"
                    value={profile.phone}
                    onChange={(e) => setProfile({ ...profile, phone: e.target.value })}
                    className="w-full rounded-lg border border-[var(--color-border-default)] bg-white px-4 py-3 text-[var(--color-text-primary)] focus:border-[var(--color-brand-secondary)] focus:outline-none focus:ring-2 focus:ring-[var(--color-brand-tertiary)]/20 transition-all"
                  />
                </div>
                <div className="sm:col-span-2">
                  <label className="mb-2 block text-sm font-semibold text-[var(--color-text-primary)]">Email</label>
                  <input
                    type="email"
                    value={profile.email}
                    onChange={(e) => setProfile({ ...profile, email: e.target.value })}
                    className="w-full rounded-lg border border-[var(--color-border-default)] bg-white px-4 py-3 text-[var(--color-text-primary)] focus:border-[var(--color-brand-secondary)] focus:outline-none focus:ring-2 focus:ring-[var(--color-brand-tertiary)]/20 transition-all"
                  />
                </div>
                <div className="sm:col-span-2">
                  <label className="mb-2 block text-sm font-semibold text-[var(--color-text-primary)]">Địa chỉ</label>
                  <input
                    type="text"
                    value={profile.address}
                    onChange={(e) => setProfile({ ...profile, address: e.target.value })}
                    className="w-full rounded-lg border border-[var(--color-border-default)] bg-white px-4 py-3 text-[var(--color-text-primary)] focus:border-[var(--color-brand-secondary)] focus:outline-none focus:ring-2 focus:ring-[var(--color-brand-tertiary)]/20 transition-all"
                  />
                </div>
              </div>

              <button
                type="submit"
                className="mt-6 rounded-lg bg-[var(--color-brand-secondary)] px-6 py-3 font-bold text-white transition-all hover:bg-[var(--color-brand-tertiary)] active:scale-[0.98]"
              >
                Lưu thay đổi
              </button>
            </form>
          </div>

          {/* Pets Section */}
          <div id="pets" className="overflow-hidden rounded-xl bg-white shadow-[var(--shadow-1)]">
            <div className="flex items-center justify-between border-b border-[var(--color-border-default)] px-6 py-4">
              <h2 className="font-[var(--font-friendly)] text-xl font-bold text-[var(--color-text-primary)]">
                Thú cưng của tôi
              </h2>
              <button
                onClick={() => { setShowPetForm(true); setEditingPet(null); setPetForm({ name: '', type: 'dog', breed: '', age: '', weight: '' }); }}
                className="flex items-center gap-2 rounded-lg bg-[var(--color-brand-secondary)] px-4 py-2 text-sm font-bold text-white transition-all hover:bg-[var(--color-brand-tertiary)]"
              >
                <Plus size={16} /> Thêm thú cưng
              </button>
            </div>
            <div className="p-6">
              {pets.length === 0 ? (
                <div className="text-center py-12">
                  <PawPrint size={48} className="mx-auto mb-4 text-gray-300" />
                  <p className="text-[var(--color-text-secondary)]">Chưa có thú cưng nào</p>
                  <button
                    onClick={() => setShowPetForm(true)}
                    className="mt-4 font-semibold text-[var(--color-brand-secondary)] hover:underline"
                  >
                    Thêm thú cưng đầu tiên
                  </button>
                </div>
              ) : (
                <div className="grid gap-5 sm:grid-cols-2">
                  {pets.map((pet) => (
                    <div key={pet.id} className="overflow-hidden rounded-xl border border-[var(--color-border-default)] transition-all hover:shadow-[var(--shadow-2)]">
                      <div className="aspect-[16/9] bg-[var(--color-surface-sunken)]">
                        <img src={pet.image} alt={pet.name} className="h-full w-full object-cover" />
                      </div>
                      <div className="p-4">
                        <div className="flex items-center justify-between mb-3">
                          <div>
                            <h3 className="font-bold text-[var(--color-text-primary)] text-lg">{pet.name}</h3>
                            <span className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-bold ${
                              pet.type === 'dog'
                                ? 'bg-blue-100 text-blue-600'
                                : 'bg-purple-100 text-purple-600'
                            }`}>
                              {pet.type === 'dog' ? (
                                <DogIcon size={12} className="text-blue-600" />
                              ) : (
                                <Cat size={12} className="text-purple-600" />
                              )}
                              {pet.type === 'dog' ? 'Chó' : 'Mèo'}
                            </span>
                          </div>
                          <div className="flex gap-1">
                            <button
                              onClick={() => handleEditPet(pet)}
                              className="p-2 text-gray-400 hover:text-[var(--color-brand-secondary)] hover:bg-[var(--color-surface-sunken)] rounded-lg transition-all"
                            >
                              <Edit2 size={16} />
                            </button>
                            <button
                              onClick={() => handleDeletePet(pet.id)}
                              className="p-2 text-gray-400 hover:text-red-500 hover:bg-red-50 rounded-lg transition-all"
                            >
                              <Trash2 size={16} />
                            </button>
                          </div>
                        </div>
                        <div className="space-y-1.5 text-sm text-[var(--color-text-secondary)]">
                          <div className="flex items-center gap-2">
                            <Award size={14} className="text-gray-400" /> {pet.breed}
                          </div>
                          <div className="flex items-center gap-2">
                            <Calendar size={14} className="text-gray-400" /> {pet.age}
                          </div>
                          <div className="flex items-center gap-2">
                            <PawPrint size={14} className="text-gray-400" /> {pet.weight}
                          </div>
                        </div>
                      </div>
                    </div>
                  ))}

                  {/* Quick Link to Recommend */}
                  <Link
                    to="/recommend"
                    className="flex min-h-[200px] flex-col items-center justify-center rounded-xl border-2 border-dashed border-[var(--color-border-default)] p-6 text-center transition-all hover:border-[var(--color-brand-secondary)] hover:bg-[var(--color-surface-sunken)]/50"
                  >
                    <Sparkles size={40} className="mb-3 text-[var(--color-brand-secondary)]" />
                    <h4 className="font-bold text-[var(--color-text-primary)]">Xem gợi ý sản phẩm</h4>
                    <p className="mt-1 text-sm text-[var(--color-text-secondary)]">
                      Dựa trên {pets.length} thú cưng của bạn
                    </p>
                  </Link>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Pet Form Modal */}
      {showPetForm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
          <div className="w-full max-w-md rounded-xl bg-white shadow-xl">
            <div className="border-b border-[var(--color-border-default)] px-6 py-4">
              <h2 className="font-[var(--font-friendly)] text-xl font-bold text-[var(--color-text-primary)]">
                {editingPet ? 'Sửa thông tin thú cưng' : 'Thêm thú cưng mới'}
              </h2>
            </div>
            <form onSubmit={handleAddPet} className="p-6 space-y-4">
              <div>
                <label className="mb-2 block text-sm font-semibold text-[var(--color-text-primary)]">Tên thú cưng</label>
                <input
                  type="text"
                  value={petForm.name}
                  onChange={(e) => setPetForm({ ...petForm, name: e.target.value })}
                  required
                  className="w-full rounded-lg border border-[var(--color-border-default)] px-4 py-2.5 focus:border-[var(--color-brand-secondary)] focus:outline-none focus:ring-2 focus:ring-[var(--color-brand-tertiary)]/20 transition-all"
                  placeholder="VD: Milo, Luna"
                />
              </div>
              <div>
                <label className="mb-2 block text-sm font-semibold text-[var(--color-text-primary)]">Loại</label>
                <select
                  value={petForm.type}
                  onChange={(e) => setPetForm({ ...petForm, type: e.target.value as 'dog' | 'cat' })}
                  className="w-full rounded-lg border border-[var(--color-border-default)] px-4 py-2.5 focus:border-[var(--color-brand-secondary)] focus:outline-none focus:ring-2 focus:ring-[var(--color-brand-tertiary)]/20 transition-all"
                >
                  <option value="dog">Chó</option>
                  <option value="cat">Mèo</option>
                </select>
              </div>
              <div>
                <label className="mb-2 block text-sm font-semibold text-[var(--color-text-primary)]">Giống</label>
                <input
                  type="text"
                  value={petForm.breed}
                  onChange={(e) => setPetForm({ ...petForm, breed: e.target.value })}
                  className="w-full rounded-lg border border-[var(--color-border-default)] px-4 py-2.5 focus:border-[var(--color-brand-secondary)] focus:outline-none focus:ring-2 focus:ring-[var(--color-brand-tertiary)]/20 transition-all"
                  placeholder="VD: Golden Retriever"
                />
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="mb-2 block text-sm font-semibold text-[var(--color-text-primary)]">Tuổi</label>
                  <input
                    type="text"
                    value={petForm.age}
                    onChange={(e) => setPetForm({ ...petForm, age: e.target.value })}
                    className="w-full rounded-lg border border-[var(--color-border-default)] px-4 py-2.5 focus:border-[var(--color-brand-secondary)] focus:outline-none focus:ring-2 focus:ring-[var(--color-brand-tertiary)]/20 transition-all"
                    placeholder="VD: 2 tuổi"
                  />
                </div>
                <div>
                  <label className="mb-2 block text-sm font-semibold text-[var(--color-text-primary)]">Cân nặng</label>
                  <input
                    type="text"
                    value={petForm.weight}
                    onChange={(e) => setPetForm({ ...petForm, weight: e.target.value })}
                    className="w-full rounded-lg border border-[var(--color-border-default)] px-4 py-2.5 focus:border-[var(--color-brand-secondary)] focus:outline-none focus:ring-2 focus:ring-[var(--color-brand-tertiary)]/20 transition-all"
                    placeholder="VD: 5kg"
                  />
                </div>
              </div>
              <div className="flex gap-3 pt-2">
                <button
                  type="button"
                  onClick={() => { setShowPetForm(false); setEditingPet(null); }}
                  className="flex-1 rounded-lg border border-[var(--color-border-default)] py-2.5 font-semibold text-[var(--color-text-primary)] hover:bg-[var(--color-surface-sunken)] transition-colors"
                >
                  Hủy
                </button>
                <button
                  type="submit"
                  className="flex-1 rounded-lg bg-[var(--color-brand-secondary)] py-2.5 font-semibold text-white hover:bg-[var(--color-brand-tertiary)] transition-colors"
                >
                  {editingPet ? 'Lưu' : 'Thêm'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
