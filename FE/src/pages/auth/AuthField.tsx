import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { AlertCircle, Eye, EyeOff } from 'lucide-react';
import {
  ensureGoogleInitialized,
  googleClientId,
  loadGoogleIdentity,
  setGoogleCredentialHandler,
} from '../../shared/hooks/useGoogleIdentity';

type Props = {
  label: string;
  /** link nho ben phai label, vd "Quen mat khau?" */
  action?: ReactNode;
  icon: ReactNode;
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  type?: 'text' | 'tel' | 'password';
  autoComplete?: string;
  /** thong bao loi hien ngay duoi o nhap; co loi thi vien chuyen do */
  error?: string;
  onBlur?: () => void;
};

export function AuthField({
  label,
  action,
  icon,
  value,
  onChange,
  placeholder,
  type = 'text',
  autoComplete,
  error,
  onBlur,
}: Props) {
  const id = useId();
  const [reveal, setReveal] = useState(false);
  const isPassword = type === 'password';

  return (
    <div>
      <div className="mb-2 flex items-center justify-between">
        <label htmlFor={id} className="text-[12px] font-bold uppercase tracking-[0.09em] text-[#7a6a5d]">{label}</label>
        {action}
      </div>
      <div
        className={`flex items-center gap-3 rounded-2xl border-[1.5px] bg-white px-[18px] py-[15px] transition-[border-color,box-shadow] duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] ${
          error
            ? 'border-[#d32f2f] shadow-[0_0_0_4px_rgba(211,47,47,0.10)]'
            : 'border-[#e6d9c8] focus-within:border-accent focus-within:shadow-[0_0_0_4px_rgba(164,51,36,0.10)]'
        }`}
      >
        <span className="shrink-0 text-[#b3a495]">{icon}</span>
        <input
          id={id}
          type={isPassword && !reveal ? 'password' : type === 'password' ? 'text' : type}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onBlur={onBlur}
          placeholder={placeholder}
          autoComplete={autoComplete}
          aria-invalid={!!error}
          className="w-full bg-transparent text-[15px] text-[#191919] outline-none placeholder:text-[#b3a495]"
        />
        {isPassword && (
          <button
            type="button"
            onClick={() => setReveal(!reveal)}
            aria-label={reveal ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'}
            className="shrink-0 text-[#b3a495] transition-colors hover:text-[#7a6a5d]"
          >
            {reveal ? <EyeOff size={19} strokeWidth={1.7} /> : <Eye size={19} strokeWidth={1.7} />}
          </button>
        )}
      </div>
      {error && (
        <p role="alert" className="mt-2 flex items-start gap-1.5 text-[12.5px] font-semibold text-[#d32f2f]">
          <AlertCircle size={14} strokeWidth={2.4} className="mt-[1px] shrink-0" />
          {error}
        </p>
      )}
    </div>
  );
}

/** Nút Google do GIS vẽ (renderButton). Không có client ID hoặc không nạp được script thì không render gì: trang vẫn đăng nhập bằng mật khẩu. */
export function GoogleButton({
  text,
  onCredential,
}: {
  text: 'continue_with' | 'signup_with';
  onCredential: (credential: string) => void;
}) {
  const boxRef = useRef<HTMLDivElement>(null);
  const latest = useRef(onCredential);
  const [failed, setFailed] = useState(false);
  const enabled = googleClientId() !== '';

  useEffect(() => {
    latest.current = onCredential;
  });

  useEffect(() => {
    const box = boxRef.current;
    if (!enabled || !box) return;
    let cancelled = false;
    loadGoogleIdentity()
      .then(() => {
        if (cancelled) return;
        ensureGoogleInitialized();
        setGoogleCredentialHandler((credential) => latest.current(credential));
        box.innerHTML = ''; // StrictMode chạy effect hai lần: không được thành hai nút
        window.google?.accounts.id.renderButton(box, {
          theme: 'outline',
          size: 'large',
          shape: 'pill',
          text,
          locale: 'vi',
          width: Math.min(box.offsetWidth || 400, 400), // GIS giới hạn 400 px
        });
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
      setGoogleCredentialHandler(null);
    };
  }, [enabled, text]);

  if (!enabled || failed) return null;
  return <div ref={boxRef} className="flex justify-center" />;
}

export function SubmitButton({ loading, disabled = false, children }: { loading: boolean; disabled?: boolean; children: ReactNode }) {
  return (
    <button
      type="submit"
      disabled={loading || disabled}
      className="w-full rounded-full bg-accent px-4 py-[17px] text-[15px] font-extrabold text-white transition-[background-color,transform] duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] hover:bg-accent-hover active:scale-[0.985] disabled:opacity-60"
    >
      {children}
    </button>
  );
}

export function OrDivider() {
  return (
    <div className="flex items-center gap-4 text-[13px] font-semibold text-[#b3a495]">
      <span className="h-px flex-1 bg-[#e6d9c8]" />
      hoặc
      <span className="h-px flex-1 bg-[#e6d9c8]" />
    </div>
  );
}
