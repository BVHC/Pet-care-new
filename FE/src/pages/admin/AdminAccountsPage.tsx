// T22 — UC09: Khóa / mở khóa tài khoản. ADMIN chỉ. Lý do bắt buộc, BR-QT-11 không khóa chính mình.
import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Lock, Search, Unlock } from 'lucide-react';
import { toast } from 'sonner';
import { ROLE_LABELS, type AccountStatus, type Role } from '../../shared/types/auth';
import { cn } from '../../lib/utils';
import { FormModal } from '../../components/ui/modal-templates';
import { Field, FormError, NoticeBar, selectClass, type Notice } from '../../components/admin/form-ui';
import { useDebounce } from '../../shared/hooks/useDebounce';
import { useLock, useUnlock, useAccounts } from '../../shared/hooks/useAdminIdentity';
import { plainMessage, toApiError } from '../../shared/api/api-error';
import { useAccount } from '../../shared/stores/session.store';
import { Pager } from '../../components/admin/Pager';

const PAGE_SIZE = 20; // = mặc định của BE, kẹp 1–100

const STATUS_COLOR: Record<AccountStatus, string> = {
  PENDING: 'bg-yellow-100 text-yellow-700',
  ACTIVE: 'bg-green-100 text-green-700',
  DISABLED: 'bg-red-100 text-red-700',
};
const STATUS_LABEL: Record<AccountStatus, string> = {
  PENDING: 'Chờ xác thực',
  ACTIVE: 'Đang hoạt động',
  DISABLED: 'Vô hiệu hóa',
};

export function AdminAccountsPage() {
  const account = useAccount();
  const [q, setQ] = useState('');
  const search = useDebounce(q.trim());
  const [role, setRole] = useState('');
  const [isLocked, setIsLocked] = useState('');
  // Đổi bộ lọc thì về trang đầu: trang nhớ kèm bộ lọc lúc chọn, lệch bộ lọc thì coi như trang 0.
  const filterKey = [search, role, isLocked].join('|');
  const [paging, setPaging] = useState({ key: filterKey, page: 0 });
  if (paging.key !== filterKey) setPaging({ key: filterKey, page: 0 });
  const page = paging.key === filterKey ? paging.page : 0;
  const [notice, setNotice] = useState<Notice | null>(null);
  const [lockTarget, setLockTarget] = useState<{ id: number; name: string } | null>(null);
  const [unlockTarget, setUnlockTarget] = useState<{ id: number; name: string } | null>(null);
  const [reason, setReason] = useState('');

  const params = {
    q: search || undefined,
    role: (role || undefined) as Role | undefined,
    isLocked: isLocked ? isLocked === 'true' : undefined,
    page,
    size: PAGE_SIZE,
  };
  const { data, isPending } = useAccounts(params);
  const lock = useLock();
  const unlock = useUnlock();

  const list = data?.content ?? [];

  const handleError = (e: unknown) => {
    const msg = plainMessage(toApiError(e));
    toast.error(msg);
    setNotice({ kind: 'error', text: msg });
  };

  if (account?.role !== 'ADMIN') {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-semibold text-(--text-primary)">Khóa tài khoản</h1>
        <NoticeBar notice={{ kind: 'error', text: 'Chỉ quản trị viên mới có thể khóa / mở khóa tài khoản.' }} />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Khóa tài khoản</h1>
        <p className="text-(--text-secondary)">Khóa khẩn cấp tài khoản nhân viên hoặc khách — mọi phiên đăng nhập bị hủy ngay. Lý do khóa bắt buộc (BR-QT-11).</p>
      </div>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="p-4 grid grid-cols-1 md:grid-cols-3 gap-3">
          <div className="relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-(--text-tertiary)" />
            <Input
              aria-label="Tìm theo email"
              placeholder="Tìm theo email…"
              className="pl-10"
              value={q}
              onChange={(e) => setQ(e.target.value)}
            />
          </div>
          <select aria-label="Lọc vai trò" className={selectClass} value={role} onChange={(e) => setRole(e.target.value)}>
            <option value="">Mọi vai trò</option>
            {(['ADMIN', 'SUPER_MANAGER', 'BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER', 'CUSTOMER'] as const).map((r) => (
              <option key={r} value={r}>
                {ROLE_LABELS[r]}
              </option>
            ))}
          </select>
          <select aria-label="Lọc trạng thái khóa" className={selectClass} value={isLocked} onChange={(e) => setIsLocked(e.target.value)}>
            <option value="">Mọi trạng thái khóa</option>
            <option value="true">Đang bị khóa</option>
            <option value="false">Đang mở</option>
          </select>
        </CardContent>
      </Card>

      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead>
              <tr>
                <th>Tài khoản</th>
                <th>Vai trò</th>
                <th>Trạng thái</th>
                <th>Bị khóa</th>
                <th>Đổi MK lần đầu</th>
                <th className="text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {isPending && <tr><td colSpan={6} className="text-center text-(--text-tertiary) py-8">Đang tải…</td></tr>}
              {!isPending && list.length === 0 && (
                <tr><td colSpan={6} className="text-center text-(--text-tertiary) py-8">Không có tài khoản nào.</td></tr>
              )}
              {list.map((a) => {
                const isMe = a.id === account?.id;
                return (
                  <tr key={a.id}>
                    <td>
                      <div className="flex items-center gap-3">
                        <div className="h-9 w-9 rounded-full bg-linear-to-br from-accent to-accent-hover flex items-center justify-center text-white font-medium">
                          #{a.id}
                        </div>
                        <div>
                          <p className="font-medium text-(--text-primary)">#{a.id}{isMe && <span className="ml-2 text-xs text-(--text-tertiary)">(bạn)</span>}</p>
                          <p className="text-xs text-(--text-tertiary)">{a.email}</p>
                        </div>
                      </div>
                    </td>
                    <td><Badge>{ROLE_LABELS[a.role]}</Badge></td>
                    <td><Badge className={cn('text-xs', STATUS_COLOR[a.status])}>{STATUS_LABEL[a.status]}</Badge></td>
                    <td>{a.isLocked ? <span className="inline-flex items-center gap-1 text-xs text-red-600"><Lock className="h-3 w-3" />Đang bị khóa</span> : '—'}</td>
                    <td>{a.mustChangePassword ? <span className="text-xs text-orange-600">Có</span> : '—'}</td>
                    <td>
                      <div className="flex justify-end gap-1">
                        {!a.isLocked && !isMe && (
                          <Button size="sm" variant="ghost" onClick={() => { setLockTarget({ id: a.id, name: a.email }); setReason(''); }} title="Khóa" aria-label={`Khóa ${a.email}`}>
                            <Lock className="h-4 w-4" />
                          </Button>
                        )}
                        {a.isLocked && (
                          <Button size="sm" variant="ghost" onClick={() => { setUnlockTarget({ id: a.id, name: a.email }); setReason(''); }} title="Mở khóa" aria-label={`Mở khóa ${a.email}`}>
                            <Unlock className="h-4 w-4" />
                          </Button>
                        )}
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {data && <Pager data={data} unit="tài khoản" onPage={(p) => setPaging({ key: filterKey, page: p })} />}
        </CardContent>
      </Card>

      <FormModal
        open={!!lockTarget}
        onOpenChange={(o) => !o && setLockTarget(null)}
        title={`Khóa tài khoản ${lockTarget?.name ?? ''}?`}
        onSubmit={async () => {
          if (!lockTarget) return;
          try {
            await lock.mutateAsync({ id: lockTarget.id, reason });
            toast.success(`Đã khóa ${lockTarget.name}`);
            setLockTarget(null);
          } catch (e) { handleError(e); }
        }}
        submitText="Khóa"
        loading={lock.isPending}
        size="sm"
      >
        <NoticeBar notice={{ kind: 'success', text: 'Mọi phiên đăng nhập sẽ bị hủy ngay. Nhân viên / khách phải dùng lại khi được mở khóa.' }} />
        <Field label="Lý do khóa *" hint="Lưu vào nhật ký kiểm toán (UC11).">
          {(id) => <Input id={id} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="VD: Nhân viên vi phạm BR-CS-01" required />}
        </Field>
        {lock.isError && <FormError message={plainMessage(toApiError(lock.error))} />}
      </FormModal>

      <FormModal
        open={!!unlockTarget}
        onOpenChange={(o) => !o && setUnlockTarget(null)}
        title={`Mở khóa tài khoản ${unlockTarget?.name ?? ''}?`}
        onSubmit={async () => {
          if (!unlockTarget) return;
          try {
            await unlock.mutateAsync({ id: unlockTarget.id, reason });
            toast.success(`Đã mở khóa ${unlockTarget.name}`);
            setUnlockTarget(null);
          } catch (e) { handleError(e); }
        }}
        submitText="Mở khóa"
        loading={unlock.isPending}
        size="sm"
      >
        <NoticeBar notice={{ kind: 'success', text: 'Tài khoản có thể đăng nhập lại ngay ở lần yêu cầu kế tiếp.' }} />
        <Field label="Lý do mở khóa *" hint="Lưu vào nhật ký kiểm toán (UC11).">
          {(id) => <Input id={id} value={reason} onChange={(e) => setReason(e.target.value)} required />}
        </Field>
        {unlock.isError && <FormError message={plainMessage(toApiError(unlock.error))} />}
      </FormModal>
    </div>
  );
}
