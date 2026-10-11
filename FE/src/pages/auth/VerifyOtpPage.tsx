import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { MailCheck, ShieldCheck, Timer } from 'lucide-react';
import { toast } from 'sonner';
import { AuthShell } from './AuthShell';
import { SubmitButton } from './AuthField';
import { authApi } from '../../shared/api/auth.api';
import { plainMessage, toApiError } from '../../shared/api/api-error';

const OTP_LENGTH = 6;

/** So giay con lai toi `resendAvailableAt` cua BE (BR-TK-07, khoang cach la [CFG]). */
const secondsUntil = (iso?: string) => (iso ? Math.max(0, Math.ceil((Date.parse(iso) - Date.now()) / 1000)) : 0);

const STEPS = [
  { icon: MailCheck, text: 'Mã gồm 6 chữ số vừa được gửi tới email của bạn' },
  { icon: Timer, text: 'Mã có hiệu lực trong 5 phút, hết hạn thì gửi lại' },
  { icon: ShieldCheck, text: 'Xác thực xong là đăng nhập được ngay' },
];

export function VerifyOtpPage() {
  const navigate = useNavigate();
  const location = useLocation();

  // Email do RegisterPage/LoginPage chuyen sang qua router state; fallback
  // ?email= de link van mo lai duoc sau khi F5. Tu LoginPage (BR-TK-08) khong co resendAvailableAt.
  const [searchParams] = useSearchParams();
  const state = location.state as { email?: string; resendAvailableAt?: string } | null;
  const email = state?.email ?? searchParams.get('email') ?? '';

  const [digits, setDigits] = useState<string[]>(Array(OTP_LENGTH).fill(''));
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [cooldown, setCooldown] = useState(() => secondsUntil(state?.resendAvailableAt));
  const inputs = useRef<Array<HTMLInputElement | null>>([]);

  // Khong co email thi khong xac thuc duoc -> quay ve dang ky.
  useEffect(() => {
    if (!email) {
      toast.error('Thiếu email để xác thực, vui lòng đăng ký lại.');
      navigate('/auth/register', { replace: true });
      return;
    }
    inputs.current[0]?.focus();
  }, [email, navigate]);

  useEffect(() => {
    if (cooldown <= 0) return;
    const id = window.setTimeout(() => setCooldown((c) => c - 1), 1000);
    return () => window.clearTimeout(id);
  }, [cooldown]);

  const writeDigits = (next: string[]) => {
    setDigits(next);
    const firstEmpty = next.findIndex((d) => !d);
    inputs.current[firstEmpty === -1 ? OTP_LENGTH - 1 : firstEmpty]?.focus();
  };

  const handleChange = (index: number, raw: string) => {
    const clean = raw.replace(/\D/g, '');
    if (!clean) {
      const next = [...digits];
      next[index] = '';
      setDigits(next);
      return;
    }
    // Go tung so hoac dan ca day 6 so deu chay qua day.
    const next = [...digits];
    clean.split('').forEach((ch, offset) => {
      if (index + offset < OTP_LENGTH) next[index + offset] = ch;
    });
    writeDigits(next);
  };

  const handleKeyDown = (index: number, e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Backspace' && !digits[index] && index > 0) {
      inputs.current[index - 1]?.focus();
    }
    if (e.key === 'ArrowLeft' && index > 0) inputs.current[index - 1]?.focus();
    if (e.key === 'ArrowRight' && index < OTP_LENGTH - 1) inputs.current[index + 1]?.focus();
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const code = digits.join('');
    if (code.length < OTP_LENGTH) {
      toast.error(`Vui lòng nhập đủ ${OTP_LENGTH} chữ số!`);
      return;
    }
    setLoading(true);
    setError('');
    try {
      await authApi.verifyRegistration(email, code);
      toast.success('Xác thực thành công! Mời bạn đăng nhập.');
      navigate('/auth/login', { replace: true });
    } catch (err) {
      // BR-TK-05 sai / het han, BR-TK-06 sai qua 5 lan (phai gui lai ma), 404 khong co tai khoan cho xac thuc.
      setError(plainMessage(toApiError(err)));
      writeDigits(Array(OTP_LENGTH).fill(''));
    } finally {
      setLoading(false);
    }
  };

  const handleResend = async () => {
    if (cooldown > 0) return;
    setError('');
    try {
      const res = await authApi.resendRegistrationOtp(email);
      setCooldown(secondsUntil(res.resendAvailableAt));
      toast.success('Đã gửi lại mã OTP.');
    } catch (err) {
      setError(plainMessage(toApiError(err)));
    }
  };

  return (
    <AuthShell
      image="/imgs/vet-counter.jpg"
      tag="Bước cuối · Xác thực email"
      headline={
        <>
          Một mã
          <br />
          <span className="text-accent-warm">sáu số</span>
          <br />
          là xong
        </>
      }
      lead="Xác thực email giúp chúng tôi gửi đúng nhật ký ảnh và thông báo lịch nghỉ dưỡng của bé tới bạn."
      stageFooter={
        <ul className="space-y-3.5">
          {STEPS.map(({ icon: Icon, text }) => (
            <li key={text} className="flex items-start gap-3 text-[13.5px] leading-[1.5] text-[#fdf6ec]/75">
              <Icon size={16} strokeWidth={2.2} className="mt-0.5 shrink-0 text-accent-warm" />
              {text}
            </li>
          ))}
        </ul>
      }
      title="Nhập mã xác thực"
      subtitle={email ? `Chúng tôi vừa gửi mã tới ${email}` : 'Đang kiểm tra email...'}
    >
      <form onSubmit={handleSubmit}>
        <div className="flex justify-between gap-2.5">
          {digits.map((digit, i) => (
            <input
              key={i}
              ref={(el) => {
                inputs.current[i] = el;
              }}
              inputMode="numeric"
              autoComplete={i === 0 ? 'one-time-code' : 'off'}
              maxLength={OTP_LENGTH}
              value={digit}
              onChange={(e) => handleChange(i, e.target.value)}
              onKeyDown={(e) => handleKeyDown(i, e)}
              className="font-friendly font-bold h-[62px] w-full rounded-2xl border-[1.5px] border-[#e6d9c8] bg-white text-center text-[26px] text-[#191919] transition-[border-color,box-shadow] duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] focus:border-accent focus:shadow-[0_0_0_4px_rgba(164,51,36,0.10)] focus:outline-none"
            />
          ))}
        </div>

        {error && (
          <p role="alert" className="mt-4 text-[13px] font-semibold text-[#d32f2f]">
            {error}
          </p>
        )}

        <div className="mt-5 mb-7 flex items-center justify-between text-[13.5px]">
          <span className="text-[#7a6a5d]">Không nhận được mã?</span>
          <button
            type="button"
            onClick={handleResend}
            disabled={cooldown > 0}
            className="font-bold text-accent transition-colors hover:underline disabled:text-[#a3968a] disabled:no-underline"
          >
            {cooldown > 0 ? `Gửi lại sau ${cooldown}s` : 'Gửi lại mã'}
          </button>
        </div>

        <SubmitButton loading={loading}>{loading ? 'Đang xác thực...' : 'Xác thực'}</SubmitButton>
      </form>

      <p className="mt-8 text-center text-[14.5px] text-[#7a6a5d]">
        Nhập sai email?{' '}
        <Link to="/auth/register" className="font-extrabold text-accent hover:underline">
          Đăng ký lại
        </Link>
      </p>
    </AuthShell>
  );
}
