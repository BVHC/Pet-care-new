import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Eye, EyeOff, Phone } from 'lucide-react';
import { toast } from 'sonner';

export function LoginPage() {
  const navigate = useNavigate();
  const [showPassword, setShowPassword] = useState(false);
  const [form, setForm] = useState({ phone: '', password: '' });
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!form.phone || !form.password) {
      toast.error('Vui lòng nhập đầy đủ thông tin!');
      return;
    }
    setLoading(true);
    // Mock login
    setTimeout(() => {
      setLoading(false);
      toast.success('Đăng nhập thành công!');
      navigate('/');
    }, 1000);
  };

  return (
    <div className="min-h-[calc(100vh-150px)] flex items-center justify-center px-4 py-12">
      <div className="w-full max-w-[420px]">
        <div className="text-center mb-8">
          <Link to="/" className="inline-flex items-center gap-2 font-[var(--font-friendly)] text-2xl font-black text-gray-900">
            <img src="/imgs/DogSticker.svg" alt="PetCare" className="h-8 w-auto" />
            PetCare
          </Link>
          <h1 className="mt-6 font-[var(--font-friendly)] text-3xl font-bold text-gray-900">
            Chào mừng trở lại!
          </h1>
          <p className="mt-2 text-gray-600">Đăng nhập để tiếp tục mua sắm</p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-5">
          {/* Phone */}
          <div>
            <label className="mb-2 block text-sm font-semibold text-gray-700">Số điện thoại</label>
            <div className="relative">
              <Phone size={20} className="absolute left-4 top-1/2 -translate-y-1/2 text-gray-400" />
              <input
                type="tel"
                value={form.phone}
                onChange={(e) => setForm({ ...form, phone: e.target.value })}
                placeholder="0901 234 567"
                className="w-full rounded-xl border border-gray-200 py-3.5 pl-12 pr-4 focus:border-[#843122] focus:outline-none"
              />
            </div>
          </div>

          {/* Password */}
          <div>
            <div className="flex items-center justify-between mb-2">
              <label className="text-sm font-semibold text-gray-700">Mật khẩu</label>
              <Link to="/auth/forgot" className="text-sm font-semibold text-[#843122] hover:underline">
                Quên mật khẩu?
              </Link>
            </div>
            <div className="relative">
              <input
                type={showPassword ? 'text' : 'password'}
                value={form.password}
                onChange={(e) => setForm({ ...form, password: e.target.value })}
                placeholder="Nhập mật khẩu"
                className="w-full rounded-xl border border-gray-200 py-3.5 pl-12 pr-12 focus:border-[#843122] focus:outline-none"
              />
              <button
                type="button"
                onClick={() => setShowPassword(!showPassword)}
                className="absolute right-4 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
              >
                {showPassword ? <EyeOff size={20} /> : <Eye size={20} />}
              </button>
            </div>
          </div>

          {/* Remember */}
          <div className="flex items-center gap-2">
            <input type="checkbox" id="remember" className="h-4 w-4 accent-[#843122]" />
            <label htmlFor="remember" className="text-sm text-gray-600">Ghi nhớ đăng nhập</label>
          </div>

          {/* Submit */}
          <button
            type="submit"
            disabled={loading}
            className="w-full rounded-xl bg-[#843122] py-3.5 font-bold text-white hover:bg-[#6a2517] transition-colors disabled:opacity-60"
          >
            {loading ? 'Đang đăng nhập...' : 'Đăng nhập'}
          </button>
        </form>

        {/* Divider */}
        <div className="my-8 flex items-center gap-4">
          <div className="flex-1 border-t border-gray-200" />
          <span className="text-sm text-gray-400">hoặc</span>
          <div className="flex-1 border-t border-gray-200" />
        </div>

        {/* Social Login */}
        <button className="w-full flex items-center justify-center gap-3 rounded-xl border border-gray-200 py-3.5 font-semibold text-gray-700 hover:bg-gray-50 transition-colors">
          <svg className="h-5 w-5" viewBox="0 0 24 24">
            <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"/>
            <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/>
            <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"/>
            <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"/>
          </svg>
          Đăng nhập với Google
        </button>

        {/* Register Link */}
        <p className="mt-8 text-center text-gray-600">
          Chưa có tài khoản?{' '}
          <Link to="/auth/register" className="font-bold text-[#843122] hover:underline">
            Đăng ký ngay
          </Link>
        </p>
      </div>
    </div>
  );
}
