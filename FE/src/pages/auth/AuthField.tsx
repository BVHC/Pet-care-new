import { useState, type ReactNode } from 'react';
import { AlertCircle, Eye, EyeOff } from 'lucide-react';

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
  const [reveal, setReveal] = useState(false);
  const isPassword = type === 'password';

  return (
    <div>
      <div className="mb-2 flex items-center justify-between">
        <label className="text-[12px] font-bold uppercase tracking-[0.09em] text-[#7a6a5d]">{label}</label>
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

export function GoogleButton({ label }: { label: string }) {
  return (
    <button
      type="button"
      className="flex w-full items-center justify-center gap-2.5 rounded-full border-[1.5px] border-[#e6d9c8] bg-white px-4 py-[15px] text-[15px] font-bold text-[#191919] transition-[border-color,background-color] duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] hover:border-[#cbb9a3] hover:bg-[#fffdf9]"
    >
      <svg className="h-[18px] w-[18px]" viewBox="0 0 24 24" aria-hidden>
        <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" />
        <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" />
        <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l3.66-2.84z" />
        <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" />
      </svg>
      {label}
    </button>
  );
}

export function SubmitButton({ loading, children }: { loading: boolean; children: ReactNode }) {
  return (
    <button
      type="submit"
      disabled={loading}
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
