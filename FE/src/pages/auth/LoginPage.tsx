import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Mail, Lock } from 'lucide-react';
import { toast } from 'sonner';
import { AuthShell } from './AuthShell';
import { AuthField, GoogleButton, SubmitButton, OrDivider } from './AuthField';
import { useAuthStore } from '../../shared/stores/auth.store';
import { apiErrorMessage } from '../../shared/api/auth.api';

const STATS = [
  { value: '2.500+', label: 'Chủ nuôi' },
  { value: '4.9/5', label: 'Đánh giá' },
  { value: '24/7', label: 'Bác sĩ trực' },
];

export function LoginPage() {
  const navigate = useNavigate();
  const login = useAuthStore((s) => s.login);
  const [form, setForm] = useState({ email: '', password: '' });
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!form.email || !form.password) {
      toast.error('Vui lòng nhập đầy đủ thông tin!');
      return;
    }
    setLoading(true);
    try {
      await login({ email: form.email.trim(), password: form.password });
      toast.success('Đăng nhập thành công!');
      navigate('/');
    } catch (error) {
      const message = apiErrorMessage(error, 'Đăng nhập thất bại, vui lòng thử lại.');
      toast.error(message);
      // Account PENDING_VERIFICATION -> dua thang sang buoc nhap OTP.
      if (message.includes('xác thực')) {
        navigate('/auth/verify-otp', { state: { email: form.email.trim() } });
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <AuthShell
      image="/imgs/maw-care-girl-kitten.jpg"
      tag="Khách sạn thú cưng · Hà Nội"
      headline={
        <>
          Bé cưng
          <br />
          được <span className="text-accent-warm">chăm</span>
          <br />
          như ở nhà
        </>
      }
      lead="Đăng nhập để theo dõi lịch nghỉ dưỡng, xem camera phòng 24/7 và nhận nhật ký ảnh mỗi ngày."
      stageFooter={
        <div className="flex gap-9">
          {STATS.map((s) => (
            <div key={s.label}>
              <b className="font-friendly font-extrabold block text-[26px] text-surface-1 sm:text-[30px]">{s.value}</b>
              <span className="text-[12px] font-semibold uppercase tracking-[0.06em] text-[#fdf6ec]/55">{s.label}</span>
            </div>
          ))}
        </div>
      }
      title="Chào mừng trở lại"
      subtitle="Đăng nhập để tiếp tục chăm sóc bé cưng."
    >
      <form onSubmit={handleSubmit} className="space-y-[18px]">
        <AuthField
          label="Email"
          icon={<Mail size={19} strokeWidth={1.7} />}
          type="text"
          autoComplete="email"
          value={form.email}
          onChange={(v) => setForm({ ...form, email: v })}
          placeholder="ban@email.com"
        />
        <AuthField
          label="Mật khẩu"
          icon={<Lock size={19} strokeWidth={1.7} />}
          type="password"
          autoComplete="current-password"
          value={form.password}
          onChange={(v) => setForm({ ...form, password: v })}
          placeholder="Nhập mật khẩu"
          action={
            <Link to="/auth/forgot" className="text-[13px] font-bold text-accent hover:underline">
              Quên mật khẩu?
            </Link>
          }
        />

        <label className="flex cursor-pointer items-center gap-2 pt-1 pb-6 text-[13.5px] text-[#7a6a5d]">
          <input type="checkbox" className="h-4 w-4 accent-[#a43324]" />
          Ghi nhớ đăng nhập
        </label>

        <SubmitButton loading={loading}>{loading ? 'Đang đăng nhập...' : 'Đăng nhập'}</SubmitButton>
      </form>

      <div className="my-6">
        <OrDivider />
      </div>
      <GoogleButton label="Tiếp tục với Google" />

      <p className="mt-8 text-center text-[14.5px] text-[#7a6a5d]">
        Chưa có tài khoản?{' '}
        <Link to="/auth/register" className="font-extrabold text-accent hover:underline">
          Đăng ký ngay
        </Link>
      </p>
    </AuthShell>
  );
}
