import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Eye, EyeOff, Phone, User } from 'lucide-react';
import { toast } from 'sonner';

export function RegisterPage() {
  const navigate = useNavigate();
  const [showPassword, setShowPassword] = useState(false);
  const [form, setForm] = useState({ name: '', phone: '', password: '', confirmPassword: '' });
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!form.name || !form.phone || !form.password) {
      toast.error('Vui lòng nhập đầy đủ thông tin!');
      return;
    }
    if (form.password !== form.confirmPassword) {
      toast.error('Mật khẩu xác nhận không khớp!');
      return;
    }
    if (form.password.length < 6) {
      toast.error('Mật khẩu phải có ít nhất 6 ký tự!');
      return;
    }
    setLoading(true);
    setTimeout(() => {
      setLoading(false);
      toast.success('Đăng ký thành công!');
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
            Tạo tài khoản mới
          </h1>
          <p className="mt-2 text-gray-600">Đăng ký để bắt đầu mua sắm</p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          {/* Name */}
          <div>
            <label className="mb-2 block text-sm font-semibold text-gray-700">Họ tên</label>
            <div className="relative">
              <User size={20} className="absolute left-4 top-1/2 -translate-y-1/2 text-gray-400" />
              <input
                type="text"
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
                placeholder="Nguyễn Văn A"
                className="w-full rounded-xl border border-gray-200 py-3.5 pl-12 pr-4 focus:border-[#843122] focus:outline-none"
              />
            </div>
          </div>

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
            <label className="mb-2 block text-sm font-semibold text-gray-700">Mật khẩu</label>
            <div className="relative">
              <input
                type={showPassword ? 'text' : 'password'}
                value={form.password}
                onChange={(e) => setForm({ ...form, password: e.target.value })}
                placeholder="Ít nhất 6 ký tự"
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

          {/* Confirm Password */}
          <div>
            <label className="mb-2 block text-sm font-semibold text-gray-700">Xác nhận mật khẩu</label>
            <input
              type={showPassword ? 'text' : 'password'}
              value={form.confirmPassword}
              onChange={(e) => setForm({ ...form, confirmPassword: e.target.value })}
              placeholder="Nhập lại mật khẩu"
              className="w-full rounded-xl border border-gray-200 py-3.5 pl-12 pr-4 focus:border-[#843122] focus:outline-none"
            />
          </div>

          {/* Terms */}
          <div className="flex items-start gap-2">
            <input type="checkbox" id="terms" required className="mt-1 h-4 w-4 accent-[#843122]" />
            <label htmlFor="terms" className="text-sm text-gray-600">
              Tôi đồng ý với{' '}
              <Link to="/terms" className="text-[#843122] hover:underline">Điều khoản sử dụng</Link>
              {' '}và{' '}
              <Link to="/privacy" className="text-[#843122] hover:underline">Chính sách bảo mật</Link>
            </label>
          </div>

          {/* Submit */}
          <button
            type="submit"
            disabled={loading}
            className="w-full rounded-xl bg-[#843122] py-3.5 font-bold text-white hover:bg-[#6a2517] transition-colors disabled:opacity-60"
          >
            {loading ? 'Đang đăng ký...' : 'Tạo tài khoản'}
          </button>
        </form>

        {/* Login Link */}
        <p className="mt-6 text-center text-gray-600">
          Đã có tài khoản?{' '}
          <Link to="/auth/login" className="font-bold text-[#843122] hover:underline">
            Đăng nhập ngay
          </Link>
        </p>
      </div>
    </div>
  );
}
