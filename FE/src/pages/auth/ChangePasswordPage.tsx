import { useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { KeyRound, Lock } from 'lucide-react';
import { toast } from 'sonner';
import { AuthShell } from './AuthShell';
import { AuthField, SubmitButton } from './AuthField';
import { authApi } from '../../shared/api/auth.api';
import { toApiError } from '../../shared/api/api-error';
import { ROUTES } from '../../shared/constants/routes';
import { homePathFor } from '../../shared/constants/workspaces';
import { useAccount, useSession } from '../../shared/stores/session.store';

// Màn bắt đổi mật khẩu (BR-TK-17): guard đưa vào đây khi account.mustChangePassword.
export function ChangePasswordPage() {
  const navigate = useNavigate();
  const from = (useLocation().state as { from?: string } | null)?.from;
  const account = useAccount();
  const setMustChangePassword = useSession((s) => s.setMustChangePassword);
  const logout = useSession((s) => s.logout);
  const [form, setForm] = useState({ current: '', next: '', confirm: '' });
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  if (!account) return <Navigate to={ROUTES.login} replace />;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!form.current || !form.next) return setError('Nhập mật khẩu hiện tại và mật khẩu mới.');
    if (form.next !== form.confirm) return setError('Mật khẩu nhập lại không khớp.');
    setError('');
    setLoading(true);
    try {
      await authApi.changePassword({ currentPassword: form.current, newPassword: form.next });
      setMustChangePassword(false);
      toast.success('Đã đổi mật khẩu.');
      navigate(from ?? homePathFor(account.role), { replace: true });
    } catch (err) {
      setError(toApiError(err).message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <AuthShell
      image="/imgs/maw-care-girl-kitten.jpg"
      tag="Bảo mật tài khoản"
      headline={
        <>
          Đặt mật khẩu
          <br />
          của <span className="text-accent-warm">riêng bạn</span>
        </>
      }
      lead="Tài khoản đang dùng mật khẩu tạm. Đổi mật khẩu để tiếp tục làm việc."
      stageFooter={null}
      title="Đổi mật khẩu"
      subtitle={`Đăng nhập với ${account.email}`}
    >
      <form onSubmit={handleSubmit} className="space-y-[18px]">
        <AuthField
          label="Mật khẩu hiện tại"
          icon={<Lock size={19} strokeWidth={1.7} />}
          type="password"
          autoComplete="current-password"
          value={form.current}
          onChange={(v) => setForm({ ...form, current: v })}
          placeholder="Nhập mật khẩu hiện tại"
        />
        <AuthField
          label="Mật khẩu mới"
          icon={<KeyRound size={19} strokeWidth={1.7} />}
          type="password"
          autoComplete="new-password"
          value={form.next}
          onChange={(v) => setForm({ ...form, next: v })}
          placeholder="Ít nhất 8 ký tự, có chữ và số"
        />
        <AuthField
          label="Nhập lại mật khẩu mới"
          icon={<KeyRound size={19} strokeWidth={1.7} />}
          type="password"
          autoComplete="new-password"
          value={form.confirm}
          onChange={(v) => setForm({ ...form, confirm: v })}
          placeholder="Nhập lại mật khẩu mới"
        />

        {error && (
          <p role="alert" className="text-[13px] font-semibold text-[#d32f2f]">
            {error}
          </p>
        )}

        <div className="pt-2">
          <SubmitButton loading={loading}>{loading ? 'Đang lưu...' : 'Đổi mật khẩu'}</SubmitButton>
        </div>
      </form>

      <button
        type="button"
        onClick={async () => {
          await logout();
          navigate(ROUTES.login, { replace: true });
        }}
        className="mt-6 w-full text-center text-[14px] font-bold text-[#7a6a5d] hover:underline"
      >
        Đăng xuất
      </button>
    </AuthShell>
  );
}
