import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Mail, Phone, Lock, User, ShieldCheck, Check } from 'lucide-react';
import { toast } from 'sonner';
import { AuthShell } from './AuthShell';
import { AuthField, GoogleButton, SubmitButton, OrDivider } from './AuthField';
import { useAuthStore } from '../../shared/stores/auth.store';
import { apiErrorMessage } from '../../shared/api/auth.api';

const PERKS = [
  'Đặt phòng nghỉ dưỡng chỉ trong 30 giây',
  'Xem camera phòng của bé theo thời gian thực',
  'Giảm 10% cho lần đặt phòng đầu tiên',
];

/** BE tu choi mat khau < 8 ky tu (RULE-01-09) nen FE chan cung nguong nay. */
const PASSWORD_MIN = 8;

/** 0 = trong, 1 yeu, 2 trung binh, 3 manh */
function strengthOf(pw: string) {
  if (!pw) return 0;
  let score = 0;
  if (pw.length >= PASSWORD_MIN) score++;
  if (pw.length >= 12) score++;
  if (/[^a-zA-Z0-9]/.test(pw) || (/[a-zA-Z]/.test(pw) && /[0-9]/.test(pw))) score++;
  return Math.min(score, 3);
}

const STRENGTH_META = [
  { label: '', color: '' },
  { label: 'Yếu', color: 'bg-[#d32f2f]' },
  { label: 'Trung bình', color: 'bg-[#cf5b47]' },
  { label: 'Mạnh', color: 'bg-[#2f8f5b]' },
];

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function RegisterPage() {
  const navigate = useNavigate();
  const register = useAuthStore((s) => s.register);
  const [form, setForm] = useState({ name: '', email: '', phone: '', password: '', confirmPassword: '' });
  const [agreed, setAgreed] = useState(false);
  const [loading, setLoading] = useState(false);

  const strength = strengthOf(form.password);
  const mismatch = form.confirmPassword.length > 0 && form.password !== form.confirmPassword;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!form.name || !form.email || !form.password) {
      toast.error('Vui lòng nhập đầy đủ họ tên, email và mật khẩu!');
      return;
    }
    if (!EMAIL_RE.test(form.email.trim())) {
      toast.error('Email không hợp lệ!');
      return;
    }
    if (form.password.length < PASSWORD_MIN) {
      toast.error(`Mật khẩu phải có ít nhất ${PASSWORD_MIN} ký tự!`);
      return;
    }
    if (form.password !== form.confirmPassword) {
      toast.error('Mật khẩu xác nhận không khớp!');
      return;
    }
    if (!agreed) {
      toast.error('Vui lòng đồng ý với điều khoản sử dụng!');
      return;
    }

    setLoading(true);
    try {
      const email = form.email.trim();
      await register({
        email,
        phone: form.phone.trim() || undefined,
        password: form.password,
        name: form.name.trim(),
      });
      toast.success('Đã gửi mã OTP tới email của bạn!');
      // BE tao account o trang thai PENDING_VERIFICATION -> bat buoc qua buoc OTP.
      navigate('/auth/verify-otp', { state: { email } });
    } catch (error) {
      toast.error(apiErrorMessage(error, 'Đăng ký thất bại, vui lòng thử lại.'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <AuthShell
      image="/imgs/maw-care-girl-orange.jpg"
      tag="Miễn phí · Không mất 1 phút"
      headline={
        <>
          Mở tài khoản
          <br />
          cho <span className="text-accent-warm">bé cưng</span>
          <br />
          của bạn
        </>
      }
      lead="Một hồ sơ duy nhất lưu cân nặng, lịch tiêm và thói quen ăn của bé — mọi lần đặt phòng sau chỉ còn một chạm."
      stageFooter={
        <ul className="space-y-3.5">
          {PERKS.map((p) => (
            <li key={p} className="flex items-start gap-3 text-[13.5px] leading-[1.5] text-[#fdf6ec]/75">
              <Check size={16} strokeWidth={2.4} className="mt-0.5 shrink-0 text-accent-warm" />
              {p}
            </li>
          ))}
        </ul>
      }
      title="Tạo tài khoản mới"
      subtitle="Đã có tài khoản? Đăng nhập chỉ mất vài giây."
    >
      <form onSubmit={handleSubmit} className="space-y-4">
        <AuthField
          label="Họ tên"
          icon={<User size={19} strokeWidth={1.7} />}
          autoComplete="name"
          value={form.name}
          onChange={(v) => setForm({ ...form, name: v })}
          placeholder="Nguyễn Văn A"
        />
        <AuthField
          label="Email"
          icon={<Mail size={19} strokeWidth={1.7} />}
          autoComplete="email"
          value={form.email}
          onChange={(v) => setForm({ ...form, email: v })}
          placeholder="ban@email.com"
        />
        <AuthField
          label="Số điện thoại"
          icon={<Phone size={19} strokeWidth={1.7} />}
          type="tel"
          autoComplete="tel"
          value={form.phone}
          onChange={(v) => setForm({ ...form, phone: v })}
          placeholder="0901 234 567"
          action={<span className="text-[12.5px] font-semibold text-[#a3968a]">Không bắt buộc</span>}
        />

        <div>
          <AuthField
            label="Mật khẩu"
            icon={<Lock size={19} strokeWidth={1.7} />}
            type="password"
            autoComplete="new-password"
            value={form.password}
            onChange={(v) => setForm({ ...form, password: v })}
            placeholder={`Ít nhất ${PASSWORD_MIN} ký tự`}
          />
          {/* Thanh do do manh mat khau */}
          <div className="mt-2.5 flex items-center gap-2.5">
            <div className="flex flex-1 gap-1.5">
              {[1, 2, 3].map((i) => (
                <span
                  key={i}
                  className={`h-[3px] flex-1 rounded-full transition-colors duration-300 ${
                    i <= strength ? STRENGTH_META[strength].color : 'bg-[#e6d9c8]'
                  }`}
                />
              ))}
            </div>
            <span className="w-[74px] text-right text-[11.5px] font-bold text-[#7a6a5d]">
              {STRENGTH_META[strength].label}
            </span>
          </div>
        </div>

        <div>
          <AuthField
            label="Xác nhận mật khẩu"
            icon={<ShieldCheck size={19} strokeWidth={1.7} />}
            type="password"
            autoComplete="new-password"
            value={form.confirmPassword}
            onChange={(v) => setForm({ ...form, confirmPassword: v })}
            placeholder="Nhập lại mật khẩu"
          />
          {mismatch && <p className="mt-2 text-[12.5px] font-semibold text-[#d32f2f]">Mật khẩu xác nhận chưa khớp.</p>}
        </div>

        <label className="flex cursor-pointer items-start gap-2.5 pt-1 pb-5 text-[13.5px] leading-[1.5] text-[#7a6a5d]">
          <input
            type="checkbox"
            checked={agreed}
            onChange={(e) => setAgreed(e.target.checked)}
            className="mt-0.5 h-4 w-4 shrink-0 accent-[#a43324]"
          />
          <span>
            Tôi đồng ý với{' '}
            <Link to="/terms" className="font-semibold text-accent hover:underline">
              Điều khoản sử dụng
            </Link>{' '}
            và{' '}
            <Link to="/privacy" className="font-semibold text-accent hover:underline">
              Chính sách bảo mật
            </Link>
          </span>
        </label>

        <SubmitButton loading={loading}>{loading ? 'Đang tạo tài khoản...' : 'Tạo tài khoản'}</SubmitButton>
      </form>

      <div className="my-6">
        <OrDivider />
      </div>
      <GoogleButton label="Đăng ký với Google" />

      <p className="mt-8 text-center text-[14.5px] text-[#7a6a5d]">
        Đã có tài khoản?{' '}
        <Link to="/auth/login" className="font-extrabold text-accent hover:underline">
          Đăng nhập ngay
        </Link>
      </p>
    </AuthShell>
  );
}
