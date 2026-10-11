import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Mail, Phone, Lock, User, ShieldCheck, Check } from 'lucide-react';
import { toast } from 'sonner';
import { AuthShell } from './AuthShell';
import { AuthField, GoogleButton, SubmitButton, OrDivider } from './AuthField';
import { authApi } from '../../shared/api/auth.api';
import { fieldErrorsOf, plainMessage, toApiError } from '../../shared/api/api-error';
import { passwordPolicyError } from '../../shared/utils/password';

const PERKS = [
  'Đặt phòng nghỉ dưỡng chỉ trong 30 giây',
  'Xem camera phòng của bé theo thời gian thực',
  'Giảm 10% cho lần đặt phòng đầu tiên',
];

/** Nguong do do manh; chinh sach mat khau nam o passwordPolicyError (BR-TK-03). */
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
/** SDT VN: 0 + 9 so, cho phep khoang trang/gach khi go */
const PHONE_RE = /^0\d{9}$/;

type Field = 'name' | 'email' | 'phone' | 'password' | 'confirmPassword';
type Errors = Partial<Record<Field, string>>;

/** Validate 1 field — dung chung cho onBlur va luc submit. */
function validateField(field: Field, f: Record<Field, string>): string | undefined {
  switch (field) {
    case 'name':
      return f.name.trim() ? undefined : 'Vui lòng nhập họ tên.';
    case 'email':
      if (!f.email.trim()) return 'Vui lòng nhập email.';
      return EMAIL_RE.test(f.email.trim()) ? undefined : 'Email không hợp lệ (ví dụ: ban@email.com).';
    case 'phone':
      if (!f.phone.trim()) return undefined; // khong bat buoc
      return PHONE_RE.test(f.phone.replace(/[\s-]/g, '')) ? undefined : 'Số điện thoại phải gồm 10 số và bắt đầu bằng 0.';
    case 'password':
      if (!f.password) return 'Vui lòng nhập mật khẩu.';
      return passwordPolicyError(f.password) ?? undefined;
    case 'confirmPassword':
      if (!f.confirmPassword) return 'Vui lòng nhập lại mật khẩu.';
      return f.password === f.confirmPassword ? undefined : 'Mật khẩu xác nhận chưa khớp.';
  }
}

/** Truong cua RegisterRequest (identity-v1) -> o nhap tren form. */
const FIELD_OF: Record<string, Field> = { email: 'email', fullName: 'name', phone: 'phone', password: 'password' };
/** Loi rule gan vao o nhap; rule khac hien o cuoi form. */
const RULE_FIELD: Record<string, Field> = { 'BR-TK-01': 'email', 'BR-TK-03': 'password' };
const CONSENT_REQUIRED = 'Bạn cần xác nhận đủ 18 tuổi và đồng ý điều khoản sử dụng';

export function RegisterPage() {
  const navigate = useNavigate();
  const [form, setForm] = useState({ name: '', email: '', phone: '', password: '', confirmPassword: '' });
  const [errors, setErrors] = useState<Errors>({});
  const [isAdult, setIsAdult] = useState(false);
  const [agreed, setAgreed] = useState(false);
  const [formError, setFormError] = useState('');
  const [loading, setLoading] = useState(false);

  /** Go chu thi xoa loi cua chinh o do — khong bat nguoi dung nhin loi cu. */
  const setField = (field: Field, value: string) => {
    setForm((prev) => ({ ...prev, [field]: value }));
    setErrors((prev) => (prev[field] ? { ...prev, [field]: undefined } : prev));
  };

  const blurField = (field: Field) =>
    setErrors((prev) => ({ ...prev, [field]: validateField(field, { ...form }) }));

  const strength = strengthOf(form.password);
  const mismatch = form.confirmPassword.length > 0 && form.password !== form.confirmPassword;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    const found: Errors = {};
    (['name', 'email', 'phone', 'password', 'confirmPassword'] as Field[]).forEach((f) => {
      const msg = validateField(f, form);
      if (msg) found[f] = msg;
    });
    setErrors(found);
    setFormError('');
    if (Object.keys(found).length > 0) return;

    // BR-TK-02: bat buoc tick ca hai o.
    if (!isAdult || !agreed) {
      setFormError(CONSENT_REQUIRED);
      return;
    }

    setLoading(true);
    try {
      const email = form.email.trim();
      const res = await authApi.register({
        email,
        password: form.password,
        fullName: form.name.trim(),
        phone: form.phone.replace(/[\s-]/g, '') || undefined,
        isAdult,
        termsAccepted: agreed,
      });
      toast.success('Đã gửi mã OTP tới email của bạn!');
      // Tai khoan PENDING -> bat buoc qua buoc OTP; dem nguoc gui lai theo BE (BR-TK-07).
      navigate('/auth/verify-otp', { state: { email, resendAvailableAt: res.otpResendAvailableAt } });
    } catch (error) {
      const err = toApiError(error);
      const byField: Errors = {};
      for (const [name, message] of Object.entries(fieldErrorsOf(err))) {
        if (FIELD_OF[name]) byField[FIELD_OF[name]] = message;
      }
      const ruleField = err.ruleId ? RULE_FIELD[err.ruleId] : undefined;
      if (ruleField) byField[ruleField] = plainMessage(err);
      // Loi thuoc ve mot o cu the -> gan ngay duoi o do; con lai hien o cuoi form.
      if (Object.keys(byField).length > 0) setErrors(byField);
      else setFormError(plainMessage(err));
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
          onChange={(v) => setField('name', v)}
          onBlur={() => blurField('name')}
          error={errors.name}
          placeholder="Nguyễn Văn A"
        />
        <AuthField
          label="Email"
          icon={<Mail size={19} strokeWidth={1.7} />}
          autoComplete="email"
          value={form.email}
          onChange={(v) => setField('email', v)}
          onBlur={() => blurField('email')}
          error={errors.email}
          placeholder="ban@email.com"
        />
        <AuthField
          label="Số điện thoại"
          icon={<Phone size={19} strokeWidth={1.7} />}
          type="tel"
          autoComplete="tel"
          value={form.phone}
          onChange={(v) => setField('phone', v)}
          onBlur={() => blurField('phone')}
          error={errors.phone}
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
            onChange={(v) => setField('password', v)}
            onBlur={() => blurField('password')}
            error={errors.password}
            placeholder={`Ít nhất ${PASSWORD_MIN} ký tự, có chữ và số`}
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
            onChange={(v) => setField('confirmPassword', v)}
            onBlur={() => blurField('confirmPassword')}
            error={errors.confirmPassword ?? (mismatch ? 'Mật khẩu xác nhận chưa khớp.' : undefined)}
            placeholder="Nhập lại mật khẩu"
          />
        </div>

        <label className="flex cursor-pointer items-start gap-2.5 pt-1 text-[13.5px] leading-[1.5] text-[#7a6a5d]">
          <input
            type="checkbox"
            checked={isAdult}
            onChange={(e) => {
              setIsAdult(e.target.checked);
              setFormError('');
            }}
            className="mt-0.5 h-4 w-4 shrink-0 accent-[#a43324]"
          />
          <span>Tôi xác nhận đã đủ 18 tuổi</span>
        </label>

        <label className="flex cursor-pointer items-start gap-2.5 pb-5 text-[13.5px] leading-[1.5] text-[#7a6a5d]">
          <input
            type="checkbox"
            checked={agreed}
            onChange={(e) => {
              setAgreed(e.target.checked);
              setFormError('');
            }}
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

        {formError && (
          <p role="alert" className="-mt-2 pb-2 text-[13px] font-semibold text-[#d32f2f]">
            {formError}
          </p>
        )}

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
