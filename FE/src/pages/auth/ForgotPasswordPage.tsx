import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Mail, Lock, ShieldCheck, KeyRound, Timer, LifeBuoy } from 'lucide-react';
import { toast } from 'sonner';
import { AuthShell } from './AuthShell';
import { AuthField, SubmitButton } from './AuthField';
import { authApi } from '../../shared/api/auth.api';
import { plainMessage, toApiError } from '../../shared/api/api-error';
import { useSession } from '../../shared/stores/session.store';
import { passwordPolicyError } from '../../shared/utils/password';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** So giay con lai toi `resendAvailableAt` cua BE (BR-TK-07). */
const secondsUntil = (iso: string) => Math.max(0, Math.ceil((Date.parse(iso) - Date.now()) / 1000));

const NOTES = [
  { icon: Mail, text: 'Mã 6 số được gửi tới đúng email đã đăng ký' },
  { icon: Timer, text: 'Mã sống 5 phút, quá hạn thì bấm gửi lại' },
  { icon: LifeBuoy, text: 'Đặt lại mật khẩu cũng mở khoá tài khoản bị khoá tạm' },
];

export function ForgotPasswordPage() {
  const navigate = useNavigate();
  const clearSession = useSession((s) => s.clear);

  const [step, setStep] = useState<'email' | 'reset'>('email');
  const [email, setEmail] = useState('');
  const [otpCode, setOtpCode] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [cooldown, setCooldown] = useState(0);

  useEffect(() => {
    if (cooldown <= 0) return;
    const id = window.setTimeout(() => setCooldown((c) => c - 1), 1000);
    return () => window.clearTimeout(id);
  }, [cooldown]);

  const mismatch = confirm.length > 0 && password !== confirm;

  // BE luon tra 202 cung mot noi dung du email co ton tai hay khong (BR-TK-10).
  const sendCode = async () => {
    const value = email.trim();
    if (!EMAIL_RE.test(value)) {
      setError('Email không hợp lệ.');
      return;
    }
    setLoading(true);
    setError('');
    try {
      const res = await authApi.forgotPassword(value);
      setEmail(value);
      setStep('reset');
      setCooldown(secondsUntil(res.resendAvailableAt));
      toast.success('Nếu email đã đăng ký, mã đặt lại vừa được gửi tới hộp thư.');
    } catch (err) {
      setError(plainMessage(toApiError(err)));
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (step === 'email') {
      await sendCode();
      return;
    }
    const invalid =
      (otpCode.trim().length !== 6 && 'Mã xác thực gồm 6 chữ số.') ||
      passwordPolicyError(password) ||
      (password !== confirm && 'Mật khẩu xác nhận không khớp.');
    if (invalid) {
      setError(invalid);
      return;
    }
    setLoading(true);
    setError('');
    try {
      await authApi.resetPassword({ email, code: otpCode.trim(), newPassword: password });
      // BE huy moi phien cua tai khoan: bo luon phien dang luu o may nay.
      clearSession();
      toast.success('Đổi mật khẩu thành công! Mời bạn đăng nhập lại.');
      navigate('/auth/login', { replace: true });
    } catch (err) {
      // BR-TK-05 ma sai / het han, BR-TK-06 sai qua 5 lan, BR-TK-03 mat khau moi khong hop le.
      setError(plainMessage(toApiError(err)));
    } finally {
      setLoading(false);
    }
  };

  return (
    <AuthShell
      image="/imgs/maw-care-girl-kitten.jpg"
      tag="Khôi phục · Chỉ mất 2 bước"
      headline={
        <>
          Quên mật khẩu
          <br />
          <span className="text-accent-warm">không sao cả</span>
        </>
      }
      lead="Chúng tôi gửi một mã sáu số tới email của bạn. Nhập mã, đặt mật khẩu mới, quay lại với bé cưng ngay."
      stageFooter={
        <ul className="space-y-3.5">
          {NOTES.map(({ icon: Icon, text }) => (
            <li key={text} className="flex items-start gap-3 text-[13.5px] leading-[1.5] text-[#fdf6ec]/75">
              <Icon size={16} strokeWidth={2.2} className="mt-0.5 shrink-0 text-accent-warm" />
              {text}
            </li>
          ))}
        </ul>
      }
      title={step === 'email' ? 'Đặt lại mật khẩu' : 'Nhập mã và mật khẩu mới'}
      subtitle={
        step === 'email'
          ? 'Nhập email đã đăng ký, chúng tôi sẽ gửi mã xác thực.'
          : `Mã xác thực vừa gửi tới ${email}`
      }
    >
      <form onSubmit={handleSubmit} className="space-y-4">
        <AuthField
          label="Email"
          icon={<Mail size={19} strokeWidth={1.7} />}
          autoComplete="email"
          value={email}
          onChange={setEmail}
          placeholder="ban@email.com"
          action={
            step === 'reset' ? (
              <button
                type="button"
                onClick={() => setStep('email')}
                className="text-[13px] font-bold text-accent hover:underline"
              >
                Đổi email
              </button>
            ) : undefined
          }
        />

        {step === 'reset' && (
          <>
            <AuthField
              label="Mã xác thực"
              icon={<KeyRound size={19} strokeWidth={1.7} />}
              autoComplete="one-time-code"
              value={otpCode}
              onChange={(v) => setOtpCode(v.replace(/\D/g, '').slice(0, 6))}
              placeholder="6 chữ số"
              action={
                <button
                  type="button"
                  onClick={sendCode}
                  disabled={cooldown > 0 || loading}
                  className="text-[13px] font-bold text-accent transition-colors hover:underline disabled:text-[#a3968a] disabled:no-underline"
                >
                  {cooldown > 0 ? `Gửi lại sau ${cooldown}s` : 'Gửi lại mã'}
                </button>
              }
            />
            <AuthField
              label="Mật khẩu mới"
              icon={<Lock size={19} strokeWidth={1.7} />}
              type="password"
              autoComplete="new-password"
              value={password}
              onChange={setPassword}
              placeholder="Ít nhất 8 ký tự, có chữ và số"
            />
            <div>
              <AuthField
                label="Xác nhận mật khẩu"
                icon={<ShieldCheck size={19} strokeWidth={1.7} />}
                type="password"
                autoComplete="new-password"
                value={confirm}
                onChange={setConfirm}
                placeholder="Nhập lại mật khẩu mới"
              />
              {mismatch && (
                <p className="mt-2 text-[12.5px] font-semibold text-[#d32f2f]">Mật khẩu xác nhận chưa khớp.</p>
              )}
            </div>
          </>
        )}

        {error && (
          <p role="alert" className="text-[13px] font-semibold text-[#d32f2f]">
            {error}
          </p>
        )}

        <div className="pt-4">
          <SubmitButton loading={loading}>
            {loading ? 'Đang xử lý...' : step === 'email' ? 'Gửi mã xác thực' : 'Đổi mật khẩu'}
          </SubmitButton>
        </div>
      </form>

      <p className="mt-8 text-center text-[14.5px] text-[#7a6a5d]">
        Nhớ ra mật khẩu rồi?{' '}
        <Link to="/auth/login" className="font-extrabold text-accent hover:underline">
          Đăng nhập ngay
        </Link>
      </p>
    </AuthShell>
  );
}
