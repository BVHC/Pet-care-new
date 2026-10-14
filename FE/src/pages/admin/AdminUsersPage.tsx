// T22 — UC08: Tài khoản nhân viên (danh sách, tạo, đổi chức vụ, điều chuyển, vô hiệu hóa/kích hoạt,
// gửi lại MK ban đầu, sửa email qua OTP). Phân quyền: ADMIN / SUPER_MANAGER / BRANCH_MANAGER.
import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, Plus, Eye, Edit, Repeat, Mail, ArrowRightLeft, Ban, RotateCcw, UserCheck } from 'lucide-react';
import { toast } from 'sonner';
import { ROLE_LABELS, type AccountStatus, type Role } from '../../shared/types/auth';
import { cn } from '../../lib/utils';
import {
  ConfirmModal,
  FormModal,
  DetailModal,
  InfoRow,
} from '../../components/ui/modal-templates';
import { Checkbox, Field, FormError, NoticeBar, selectClass, type Notice } from '../../components/admin/form-ui';
import { useDebounce } from '../../shared/hooks/useDebounce';
import {
  useChangeRole,
  useConfirmEmailChange,
  useCreateStaff,
  useDisable,
  useReactivate,
  useResendInitialPassword,
  useSendEmailChange,
  useStaff,
  useTransfer,
} from '../../shared/hooks/useAdminIdentity';
import { plainMessage, toApiError } from '../../shared/api/api-error';
import { useBranches } from '../../shared/hooks/useBranch';
import type { ChangeableStaffRole, CreateableStaffRole, StaffResponse } from '../../shared/types/identity';
import { useAccount } from '../../shared/stores/session.store';

/* ================================================================
   Constants.
   ================================================================ */

const STATUS_LABEL: Record<AccountStatus, string> = {
  PENDING: 'Chờ xác thực',
  ACTIVE: 'Đang hoạt động',
  DISABLED: 'Vô hiệu hóa',
};
const STATUS_COLOR: Record<AccountStatus, string> = {
  PENDING: 'bg-yellow-100 text-yellow-700',
  ACTIVE: 'bg-green-100 text-green-700',
  DISABLED: 'bg-red-100 text-red-700',
};
const ROLE_COLOR: Record<Role, string> = {
  ADMIN: 'bg-red-100 text-red-700',
  SUPER_MANAGER: 'bg-purple-100 text-purple-700',
  BRANCH_MANAGER: 'bg-blue-100 text-blue-700',
  RECEPTIONIST: 'bg-cyan-100 text-cyan-700',
  VET: 'bg-green-100 text-green-700',
  CARETAKER: 'bg-pink-100 text-pink-700',
  CUSTOMER: 'bg-gray-100 text-gray-700',
};

const CHANGEABLE_ROLES: ChangeableStaffRole[] = ['BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER'];
const CREATEABLE_ROLES: CreateableStaffRole[] = ['SUPER_MANAGER', 'BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER'];
const STAFF_ROLES_NON_ADMIN: Role[] = ['SUPER_MANAGER', 'BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER'];

/* ================================================================
   Badges.
   ================================================================ */

function RoleBadge({ role }: { role: Role }) {
  return <Badge className={cn('text-xs', ROLE_COLOR[role])}>{ROLE_LABELS[role]}</Badge>;
}

function StatusPill({ status }: { status: AccountStatus }) {
  return <Badge className={cn('text-xs', STATUS_COLOR[status])}>{STATUS_LABEL[status]}</Badge>;
}

/* ================================================================
   Page.
   ================================================================ */

export function AdminUsersPage() {
  const account = useAccount();
  const role = account?.role;
  const canChangeRole = role === 'SUPER_MANAGER';
  const canTransfer = role === 'SUPER_MANAGER';
  const canDisable = role === 'SUPER_MANAGER' || role === 'BRANCH_MANAGER';
  const canResend = role === 'SUPER_MANAGER' || role === 'BRANCH_MANAGER';
  const canChangeEmail = role === 'SUPER_MANAGER' || role === 'BRANCH_MANAGER';

  const [q, setQ] = useState('');
  const search = useDebounce(q.trim());
  const [roleFilter, setRoleFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [branchFilter, setBranchFilter] = useState('');

  const params = {
    q: search || undefined,
    role: (roleFilter || undefined) as Role | undefined,
    status: (statusFilter || undefined) as AccountStatus | undefined,
    branchId: branchFilter ? Number(branchFilter) : undefined,
  };
  const { data, isPending } = useStaff(params);
  const branches = useBranches();
  const myBranch = branches.data?.find((b) => b.branchId === account?.branchId);

  const [detail, setDetail] = useState<StaffResponse | null>(null);
  const [creating, setCreating] = useState(false);
  const [changeRole, setChangeRole] = useState<StaffResponse | null>(null);
  const [transfer, setTransfer] = useState<StaffResponse | null>(null);
  const [disable, setDisable] = useState<StaffResponse | null>(null);
  const [reactivate, setReactivate] = useState<StaffResponse | null>(null);
  const [resend, setResend] = useState<StaffResponse | null>(null);
  const [changeEmail, setChangeEmail] = useState<StaffResponse | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);

  const list = data?.content ?? [];

  const handleError = (e: unknown) => {
    const msg = plainMessage(toApiError(e));
    toast.error(msg);
    setNotice({ kind: 'error', text: msg });
  };

  return (
    <div className="space-y-6">
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-(--text-primary)">Người dùng & Vai trò</h1>
          <p className="text-(--text-secondary)">
            Quản lý tài khoản nhân viên theo phân cấp (BR-QT-01).
            {role === 'BRANCH_MANAGER' && myBranch ? <> Chỉ thấy nhân viên thuộc <strong>{myBranch.name}</strong>.</> : null}
          </p>
        </div>
        <Button onClick={() => setCreating(true)}>
          <Plus className="mr-2 h-4 w-4" />
          Thêm người dùng
        </Button>
      </div>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="p-4 grid grid-cols-1 md:grid-cols-4 gap-3">
          <div className="relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-(--text-tertiary)" />
            <Input
              aria-label="Tìm theo tên hoặc email"
              placeholder="Tìm theo tên hoặc email…"
              className="pl-10"
              value={q}
              onChange={(e) => setQ(e.target.value)}
            />
          </div>
          <select aria-label="Lọc vai trò" className={selectClass} value={roleFilter} onChange={(e) => setRoleFilter(e.target.value)}>
            <option value="">Mọi vai trò</option>
            {STAFF_ROLES_NON_ADMIN.map((r) => (
              <option key={r} value={r}>{ROLE_LABELS[r]}</option>
            ))}
          </select>
          <select aria-label="Lọc trạng thái" className={selectClass} value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
            <option value="">Mọi trạng thái</option>
            <option value="ACTIVE">Đang hoạt động</option>
            <option value="DISABLED">Vô hiệu hóa</option>
            <option value="PENDING">Chờ xác thực</option>
          </select>
          {branches.data && branches.data.length > 0 ? (
            <select
              aria-label="Lọc chi nhánh"
              className={selectClass}
              value={branchFilter}
              onChange={(e) => setBranchFilter(e.target.value)}
              disabled={role === 'BRANCH_MANAGER'}
            >
              <option value="">Mọi chi nhánh</option>
              {branches.data.map((b) => (
                <option key={b.branchId} value={String(b.branchId)}>{b.name}</option>
              ))}
            </select>
          ) : (
            <div />
          )}
        </CardContent>
      </Card>

      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead>
              <tr>
                <th>Nhân viên</th>
                <th>Vai trò</th>
                <th>Chi nhánh</th>
                <th>Trạng thái</th>
                <th>Đăng nhập cuối</th>
                <th className="text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {isPending && (
                <tr><td colSpan={6} className="text-center text-(--text-tertiary) py-8">Đang tải…</td></tr>
              )}
              {!isPending && list.length === 0 && (
                <tr><td colSpan={6} className="text-center text-(--text-tertiary) py-8">Không có nhân viên nào.</td></tr>
              )}
              {list.map((u) => {
                const isMe = u.accountId === account?.id;
                const branch = u.branchId ? branches.data?.find((b) => b.branchId === u.branchId) : null;
                return (
                  <tr key={u.accountId}>
                    <td>
                      <div className="flex items-center gap-3">
                        <div className="h-10 w-10 rounded-full bg-linear-to-br from-accent to-accent-hover flex items-center justify-center text-white font-medium">
                          {u.fullName.charAt(0)}
                        </div>
                        <div>
                          <p className="font-medium text-(--text-primary)">
                            {u.fullName}
                            {isMe && <span className="ml-2 text-xs text-(--text-tertiary)">(bạn)</span>}
                          </p>
                          <p className="text-xs text-(--text-tertiary)">{u.email}</p>
                        </div>
                      </div>
                    </td>
                    <td><RoleBadge role={u.role} /></td>
                    <td className="text-(--text-secondary)">{branch?.name ?? '—'}</td>
                    <td>
                      <div className="flex flex-col gap-1">
                        <StatusPill status={u.status} />
                        {u.isLocked && (
                          <span className="inline-flex items-center gap-1 text-xs text-red-600"><Ban className="h-3 w-3" />Đang bị khóa</span>
                        )}
                        {u.mustChangePassword && (
                          <span className="text-xs text-orange-600">Chưa đổi MK lần đầu</span>
                        )}
                      </div>
                    </td>
                    <td className="text-(--text-tertiary)">{u.online ? <span className="text-green-600">● Online</span> : 'Offline'}</td>
                    <td>
                      <div className="flex justify-end gap-1 flex-wrap">
                        <Button size="sm" variant="ghost" onClick={() => setDetail(u)} title="Xem chi tiết" aria-label={`Xem chi tiết ${u.fullName}`}>
                          <Eye className="h-4 w-4" />
                        </Button>
                        <Button size="sm" variant="ghost" onClick={() => toast.info('Sửa hồ sơ: dùng nút "Sửa email" hoặc trang Hồ sơ nhân viên.')} title="Sửa hồ sơ" aria-label={`Sửa ${u.fullName}`}>
                          <Edit className="h-4 w-4" />
                        </Button>
                        {canChangeEmail && !isMe && (
                          <Button size="sm" variant="ghost" onClick={() => setChangeEmail(u)} title="Sửa email" aria-label={`Sửa email ${u.fullName}`}>
                            <Mail className="h-4 w-4" />
                          </Button>
                        )}
                        {canResend && !isMe && u.mustChangePassword && (
                          <Button size="sm" variant="ghost" onClick={() => setResend(u)} title="Gửi lại MK ban đầu" aria-label={`Gửi lại MK cho ${u.fullName}`}>
                            <Repeat className="h-4 w-4" />
                          </Button>
                        )}
                        {canChangeRole && !isMe && (
                          <Button size="sm" variant="ghost" onClick={() => setChangeRole(u)} title="Đổi chức vụ" aria-label={`Đổi chức vụ ${u.fullName}`}>
                            <UserCheck className="h-4 w-4" />
                          </Button>
                        )}
                        {canTransfer && !isMe && u.role !== 'SUPER_MANAGER' && (
                          <Button size="sm" variant="ghost" onClick={() => setTransfer(u)} title="Điều chuyển" aria-label={`Điều chuyển ${u.fullName}`}>
                            <ArrowRightLeft className="h-4 w-4" />
                          </Button>
                        )}
                        {canDisable && !isMe && u.status !== 'DISABLED' && (
                          <Button size="sm" variant="ghost" onClick={() => setDisable(u)} title="Vô hiệu hóa" aria-label={`Vô hiệu hóa ${u.fullName}`}>
                            <Ban className="h-4 w-4" />
                          </Button>
                        )}
                        {canDisable && !isMe && u.status === 'DISABLED' && (
                          <Button size="sm" variant="ghost" onClick={() => setReactivate(u)} title="Kích hoạt lại" aria-label={`Kích hoạt lại ${u.fullName}`}>
                            <RotateCcw className="h-4 w-4" />
                          </Button>
                        )}
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </CardContent>
      </Card>

      {/* ─── Detail modal ─── */}
      <DetailModal open={!!detail} onOpenChange={(o) => !o && setDetail(null)} title="Chi tiết nhân viên" size="md">
        {detail && (
          <div className="space-y-4">
            <div className="flex items-center gap-4 pb-4 border-b border-(--color-border-light)">
              <div className="h-14 w-14 rounded-full bg-linear-to-br from-accent to-accent-hover flex items-center justify-center text-white text-2xl font-bold">
                {detail.fullName.charAt(0)}
              </div>
              <div className="flex-1">
                <h3 className="text-lg font-semibold text-(--text-primary)">{detail.fullName}</h3>
                <p className="text-sm text-(--text-secondary)">{detail.email}</p>
                <div className="mt-1 flex gap-2">
                  <RoleBadge role={detail.role} />
                  <StatusPill status={detail.status} />
                  {detail.isLocked && <span className="text-xs text-red-600">● Bị khóa</span>}
                </div>
              </div>
            </div>
            <div className="space-y-1">
              <InfoRow label="Mã tài khoản" value={`#${detail.accountId}`} />
              <InfoRow label="Số điện thoại" value={detail.phone} />
              <InfoRow label="Chi nhánh" value={detail.branchId ? branches.data?.find((b) => b.branchId === detail.branchId)?.name ?? `#${detail.branchId}` : '—'} />
              <InfoRow label="Đăng nhập" value={detail.online ? '● Online' : 'Offline'} />
              {detail.role === 'VET' && (
                <>
                  <InfoRow label="Chuyên môn" value={detail.specialty ?? '—'} />
                  <InfoRow label="Mô tả ngắn" value={detail.bio ?? '—'} />
                </>
              )}
              {detail.mustChangePassword && <InfoRow label="Bắt buộc đổi MK" value="Có" />}
            </div>
          </div>
        )}
      </DetailModal>

      <CreateForm
        open={creating}
        onOpenChange={setCreating}
        branches={branches.data ?? []}
        myBranchId={myBranch?.branchId ?? null}
        actorRole={role ?? null}
        onSaved={() => { setCreating(false); toast.success('Đã tạo nhân viên'); }}
        onError={handleError}
      />

      <ChangeRoleDialog
        target={changeRole}
        onOpenChange={(o) => !o && setChangeRole(null)}
        onError={handleError}
      />

      <TransferDialog
        target={transfer}
        branches={branches.data ?? []}
        onOpenChange={(o) => !o && setTransfer(null)}
        onError={handleError}
      />

      <ConfirmModal
        open={!!disable}
        onOpenChange={(o) => !o && setDisable(null)}
        type="warning"
        title="Vô hiệu hóa tài khoản?"
        description={disable ? `Tài khoản "${disable.fullName}" sẽ bị vô hiệu hóa. Mọi phiên đăng nhập sẽ bị hủy, nhân viên chuyển offline ngay. Bạn có thể kích hoạt lại sau.` : ''}
        confirmText="Vô hiệu hóa"
        loading={false}
        onConfirm={async () => {
          if (!disable) return;
          try {
            const mut = useDisable();
            await mut.mutateAsync(disable.accountId);
            toast.success(`Đã vô hiệu hóa ${disable.fullName}`);
            setDisable(null);
          } catch (e) { handleError(e); }
        }}
      />

      <ReactivateDialog
        target={reactivate}
        branches={branches.data ?? []}
        onOpenChange={(o) => !o && setReactivate(null)}
        onError={handleError}
      />

      <ConfirmModal
        open={!!resend}
        onOpenChange={(o) => !o && setResend(null)}
        type="info"
        title="Gửi lại mật khẩu ban đầu?"
        description={resend ? `Mật khẩu tạm mới sẽ được gửi tới email của "${resend.fullName}". Phiên đăng nhập sẽ bị hủy.` : ''}
        confirmText="Gửi lại"
        loading={false}
        onConfirm={async () => {
          if (!resend) return;
          try {
            const mut = useResendInitialPassword();
            await mut.mutateAsync(resend.accountId);
            toast.success('Đã gửi mật khẩu tạm mới qua email');
            setResend(null);
          } catch (e) { handleError(e); }
        }}
      />

      <ChangeEmailDialog
        target={changeEmail}
        onOpenChange={(o) => !o && setChangeEmail(null)}
        onError={handleError}
      />
    </div>
  );
}

/* ================================================================
   Dialogs.
   ================================================================ */

function CreateForm({
  open,
  onOpenChange,
  branches,
  myBranchId,
  actorRole,
  onSaved,
  onError,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  branches: { branchId: number; name: string }[];
  myBranchId: number | null;
  actorRole: Role | null;
  onSaved: () => void;
  onError: (e: unknown) => void;
}) {
  const [email, setEmail] = useState('');
  const [phone, setPhone] = useState('');
  const [fullName, setFullName] = useState('');
  const [role, setRole] = useState<CreateableStaffRole>('RECEPTIONIST');
  const [branchId, setBranchId] = useState<string>(myBranchId ? String(myBranchId) : '');

  const create = useCreateStaff();
  const isBRANCH_MANAGER = actorRole === 'BRANCH_MANAGER';
  const isSUPER_MANAGER = actorRole === 'SUPER_MANAGER';
  const allowedRoles: CreateableStaffRole[] = isBRANCH_MANAGER
    ? ['RECEPTIONIST', 'VET', 'CARETAKER']
    : isSUPER_MANAGER
      ? CREATEABLE_ROLES
      : ['RECEPTIONIST', 'VET', 'CARETAKER'];
  const roleNeedsBranch = role !== 'SUPER_MANAGER';
  const branchRequired = roleNeedsBranch && !isBRANCH_MANAGER;

  // Reset khi mở
  if (open && !email && !phone && !fullName) {
    // chỉ chạy lần đầu
  }

  const handleSubmit = async () => {
    try {
      await create.mutateAsync({
        email: email.trim(),
        phone: phone.trim(),
        fullName: fullName.trim(),
        role,
        ...(roleNeedsBranch ? { branchId: Number(branchId) } : {}),
      } as Parameters<typeof create.mutateAsync>[0]);
      onSaved();
      setEmail(''); setPhone(''); setFullName(''); setBranchId(myBranchId ? String(myBranchId) : '');
    } catch (e) { onError(e); }
  };

  return (
    <FormModal
      open={open}
      onOpenChange={onOpenChange}
      title="Thêm người dùng mới"
      description="Điền thông tin để tạo tài khoản nhân viên. Mật khẩu tạm sẽ gửi qua email; nhân viên phải đổi ở lần đăng nhập đầu."
      onSubmit={handleSubmit}
      submitText="Tạo tài khoản"
      loading={create.isPending}
      size="md"
    >
      <div className="space-y-4">
        <Field label="Họ tên *">
          {(id) => <Input id={id} value={fullName} onChange={(e) => setFullName(e.target.value)} maxLength={100} required />}
        </Field>
        <Field label="Email *">
          {(id) => <Input id={id} type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />}
        </Field>
        <Field label="Số điện thoại *" hint="Dạng 0xxxxxxxxx (10 chữ số)">
          {(id) => <Input id={id} value={phone} onChange={(e) => setPhone(e.target.value)} pattern="^0\d{9}$" required />}
        </Field>
        <Field label="Vai trò *">
          {(id) => (
            <select id={id} className={selectClass} value={role} onChange={(e) => setRole(e.target.value as CreateableStaffRole)}>
              {allowedRoles.map((r) => <option key={r} value={r}>{ROLE_LABELS[r]}</option>)}
            </select>
          )}
        </Field>
        {roleNeedsBranch && (
          <Field label={branchRequired ? 'Chi nhánh *' : 'Chi nhánh'} hint={isBRANCH_MANAGER ? 'Mặc định chi nhánh của bạn.' : undefined}>
            {(id) => (
              <select id={id} className={selectClass} value={branchId} onChange={(e) => setBranchId(e.target.value)} disabled={isBRANCH_MANAGER}>
                <option value="">— Chọn chi nhánh —</option>
                {branches.map((b) => <option key={b.branchId} value={b.branchId}>{b.name}</option>)}
              </select>
            )}
          </Field>
        )}
        {create.isError && <FormError message={plainMessage(toApiError(create.error))} />}
      </div>
    </FormModal>
  );
}

function ChangeRoleDialog({
  target,
  onOpenChange,
  onError,
}: {
  target: StaffResponse | null;
  onOpenChange: (open: boolean) => void;
  onError: (e: unknown) => void;
}) {
  const [role, setRole] = useState<ChangeableStaffRole>('BRANCH_MANAGER');
  const changeRole = useChangeRole();

  const handleConfirm = async () => {
    if (!target) return;
    try {
      await changeRole.mutateAsync({ id: target.accountId, args: { role } });
      toast.success(`Đã đổi chức vụ ${target.fullName} sang ${ROLE_LABELS[role]}`);
      onOpenChange(false);
    } catch (e) { onError(e); }
  };

  return (
    <FormModal
      open={!!target}
      onOpenChange={onOpenChange}
      title={`Đổi chức vụ ${target?.fullName ?? ''}`}
      description="Đổi chức vụ sẽ thay đổi quyền truy cập. Nếu là VET → RECEPTIONIST/CARETAKER, chuyên môn và mô tả ngắn sẽ bị xóa (BR-QT-05)."
      onSubmit={handleConfirm}
      submitText="Đổi chức vụ"
      loading={changeRole.isPending}
      size="sm"
    >
      <Field label="Vai trò mới *">
        {(id) => (
          <select id={id} className={selectClass} value={role} onChange={(e) => setRole(e.target.value as ChangeableStaffRole)}>
            {CHANGEABLE_ROLES.map((r) => <option key={r} value={r}>{ROLE_LABELS[r]}</option>)}
          </select>
        )}
      </Field>
      {target && target.role === 'VET' && role !== 'VET' && (
        <NoticeBar notice={{ kind: 'success', text: `Chuyên môn "${target.specialty ?? '(trống)'}" sẽ bị xóa.` }} />
      )}
      {changeRole.isError && <FormError message={plainMessage(toApiError(changeRole.error))} />}
    </FormModal>
  );
}

function TransferDialog({
  target,
  branches,
  onOpenChange,
  onError,
}: {
  target: StaffResponse | null;
  branches: { branchId: number; name: string }[];
  onOpenChange: (open: boolean) => void;
  onError: (e: unknown) => void;
}) {
  const [branchId, setBranchId] = useState('');
  const transfer = useTransfer();

  const handleSubmit = async () => {
    if (!target) return;
    try {
      await transfer.mutateAsync({ id: target.accountId, args: { branchId: Number(branchId) } });
      toast.success(`Đã điều chuyển ${target.fullName}`);
      onOpenChange(false);
    } catch (e) { onError(e); }
  };

  return (
    <FormModal
      open={!!target}
      onOpenChange={onOpenChange}
      title={`Điều chuyển ${target?.fullName ?? ''}`}
      description="Chọn chi nhánh mới. Nhân viên sẽ chuyển chi nhánh ngay; quyền truy cập theo chi nhánh cập nhật ở yêu cầu kế tiếp."
      onSubmit={handleSubmit}
      submitText="Điều chuyển"
      loading={transfer.isPending}
      size="sm"
    >
      <Field label="Chi nhánh mới *">
        {(id) => (
          <select id={id} className={selectClass} value={branchId} onChange={(e) => setBranchId(e.target.value)}>
            <option value="">— Chọn chi nhánh —</option>
            {branches.map((b) => <option key={b.branchId} value={b.branchId}>{b.name}</option>)}
          </select>
        )}
      </Field>
      {transfer.isError && <FormError message={plainMessage(toApiError(transfer.error))} />}
    </FormModal>
  );
}

function ReactivateDialog({
  target,
  branches,
  onOpenChange,
  onError,
}: {
  target: StaffResponse | null;
  branches: { branchId: number; name: string }[];
  onOpenChange: (open: boolean) => void;
  onError: (e: unknown) => void;
}) {
  const [branchId, setBranchId] = useState('');
  const [changeBranch, setChangeBranch] = useState(false);
  const reactivate = useReactivate();

  const handleSubmit = async () => {
    if (!target) return;
    try {
      await reactivate.mutateAsync({
        id: target.accountId,
        args: { branchId: changeBranch && branchId ? Number(branchId) : null },
      });
      toast.success(`Đã kích hoạt lại ${target.fullName}, mật khẩu tạm mới đã gửi email`);
      onOpenChange(false);
    } catch (e) { onError(e); }
  };

  return (
    <FormModal
      open={!!target}
      onOpenChange={onOpenChange}
      title={`Kích hoạt lại ${target?.fullName ?? ''}`}
      description="Mật khẩu tạm mới sẽ được gửi tới email nhân viên. Nhân viên sẽ phải đổi mật khẩu ở lần đăng nhập kế tiếp."
      onSubmit={handleSubmit}
      submitText="Kích hoạt lại"
      loading={reactivate.isPending}
      size="sm"
    >
      <Checkbox
        label="Đổi chi nhánh"
        checked={changeBranch}
        onChange={setChangeBranch}
      />
      {changeBranch && (
        <Field label="Chi nhánh mới *">
          {(id) => (
            <select id={id} className={selectClass} value={branchId} onChange={(e) => setBranchId(e.target.value)}>
              <option value="">— Chọn chi nhánh —</option>
              {branches.map((b) => <option key={b.branchId} value={b.branchId}>{b.name}</option>)}
            </select>
          )}
        </Field>
      )}
      {reactivate.isError && <FormError message={plainMessage(toApiError(reactivate.error))} />}
    </FormModal>
  );
}

function ChangeEmailDialog({
  target,
  onOpenChange,
  onError,
}: {
  target: StaffResponse | null;
  onOpenChange: (open: boolean) => void;
  onError: (e: unknown) => void;
}) {
  const [step, setStep] = useState<'email' | 'otp'>('email');
  const [newEmail, setNewEmail] = useState('');
  const [code, setCode] = useState('');
  const [masked, setMasked] = useState('');
  const [resendAt, setResendAt] = useState<number>(0);
  const send = useSendEmailChange();
  const confirm = useConfirmEmailChange();

  const reset = () => {
    setStep('email');
    setNewEmail('');
    setCode('');
    setMasked('');
    setResendAt(0);
  };

  const close = () => { onOpenChange(false); setTimeout(reset, 200); };

  const handleSend = async () => {
    if (!target) return;
    try {
      const res = await send.mutateAsync({ id: target.accountId, newEmail: newEmail.trim() });
      setMasked(res.maskedEmail ?? '');
      setResendAt(new Date(res.resendAvailableAt).getTime());
      setStep('otp');
      toast.success(`Đã gửi mã OTP tới ${res.maskedEmail}`);
    } catch (e) { onError(e); }
  };

  const handleResend = async () => {
    if (!target) return;
    if (Date.now() < resendAt) {
      toast.error(`Vui lòng chờ ${Math.ceil((resendAt - Date.now()) / 1000)}s`);
      return;
    }
    try {
      const res = await send.mutateAsync({ id: target.accountId, newEmail: newEmail.trim() });
      setResendAt(new Date(res.resendAvailableAt).getTime());
      toast.success('Đã gửi lại mã');
    } catch (e) { onError(e); }
  };

  const handleConfirm = async () => {
    if (!target) return;
    try {
      await confirm.mutateAsync({ id: target.accountId, code: code.trim() });
      toast.success(`Đã đổi email thành ${newEmail.trim()}`);
      close();
    } catch (e) { onError(e); }
  };

  return (
    <FormModal
      open={!!target}
      onOpenChange={(o) => !o && close()}
      title={step === 'email' ? `Sửa email — ${target?.fullName ?? ''}` : `Nhập mã OTP — ${target?.fullName ?? ''}`}
      onSubmit={step === 'email' ? handleSend : handleConfirm}
      submitText={step === 'email' ? 'Gửi mã OTP' : 'Xác nhận'}
      loading={send.isPending || confirm.isPending}
      size="sm"
    >
      {step === 'email' ? (
        <Field label="Email mới *" hint="Mã OTP sẽ được gửi tới email mới này.">
          {(id) => <Input id={id} type="email" value={newEmail} onChange={(e) => setNewEmail(e.target.value)} required />}
        </Field>
      ) : (
        <div className="space-y-3">
          <NoticeBar notice={{ kind: 'success', text: `Mã đã gửi tới ${masked}. Mã mock: 123456.` }} />
          <Field label="Mã OTP *">
            {(id) => <Input id={id} value={code} onChange={(e) => setCode(e.target.value)} inputMode="numeric" maxLength={8} autoFocus />}
          </Field>
          <Button type="button" variant="outline" onClick={handleResend} disabled={Date.now() < resendAt || send.isPending}>
            <Repeat className="mr-2 h-4 w-4" />
            Gửi lại mã
          </Button>
        </div>
      )}
      {(send.isError || confirm.isError) && <FormError message={plainMessage(toApiError(send.error || confirm.error))} />}
    </FormModal>
  );
}
