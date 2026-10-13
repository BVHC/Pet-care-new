import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { KeyRound, Save, UserRound } from 'lucide-react';
import { Button } from '../../components/ui/button';
import { Card, CardContent } from '../../components/ui/card';
import { Input } from '../../components/ui/input';
import { Field, FormError, NoticeBar, type Notice } from '../../components/admin/form-ui';
import { fieldErrorsOf, plainMessage, toApiError } from '../../shared/api/api-error';
import { profileApi } from '../../shared/api/profile.api';
import { ROUTES } from '../../shared/constants/routes';
import { useBranch } from '../../shared/hooks/useBranch';
import { useMe, useProfileMutation } from '../../shared/hooks/useProfile';
import { useSession } from '../../shared/stores/session.store';
import { ROLE_LABELS, type AccountSummary } from '../../shared/types/auth';
import type { StaffProfile } from '../../shared/types/profile';

/** Mặc định của [CFG] `vet.bio_max_length` (BR-TK-20). */
const BIO_MAX = 500;

const textareaClass =
  'w-full rounded-lg border border-(--color-border-default) bg-white px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-accent';

/** UC06 — hồ sơ của nhân viên (A03–A08); VET thêm hồ sơ giới thiệu hiện ở trang "Đội ngũ bác sĩ" (BR-TK-20). */
export function StaffProfilePage() {
  const me = useMe();
  const profile = me.data?.staffProfile;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Hồ sơ cá nhân</h1>
        <p className="text-(--text-secondary)">Họ tên, số điện thoại và ảnh đại diện của bạn. Email do cấp trên quản lý.</p>
      </div>
      {me.isError && <FormError message={plainMessage(toApiError(me.error))} />}
      {me.data && profile && (
        <>
          <PersonalCard account={me.data.account} profile={profile} />
          {me.data.account.role === 'VET' && <VetIntroCard profile={profile} />}
        </>
      )}
    </div>
  );
}

function Avatar({ url, name, size = 'h-16 w-16' }: { url?: string | null; name: string; size?: string }) {
  const initials = name
    .split(' ')
    .slice(-2)
    .map((w) => w[0] ?? '')
    .join('')
    .toUpperCase();
  return url?.trim().startsWith('https://') ? (
    <img src={url.trim()} alt="" className={`${size} shrink-0 rounded-full object-cover`} />
  ) : (
    <span className={`${size} flex shrink-0 items-center justify-center rounded-full bg-accent/10 font-semibold text-accent`}>
      {initials || <UserRound className="h-6 w-6" />}
    </span>
  );
}

function BranchName({ branchId }: { branchId: number }) {
  const branch = useBranch(branchId);
  return <span>{branch.data?.name ?? `Chi nhánh #${branchId}`}</span>;
}

function PersonalCard({ account, profile }: { account: AccountSummary; profile: StaffProfile }) {
  const setSessionName = useSession((s) => s.setFullName);
  const [fullName, setFullName] = useState(profile.fullName);
  const [phone, setPhone] = useState(profile.phone);
  const [avatarUrl, setAvatarUrl] = useState(profile.avatarUrl ?? '');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [notice, setNotice] = useState<Notice | null>(null);
  const save = useProfileMutation(profileApi.updateStaffProfile);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setErrors({});
    setNotice(null);
    try {
      // Chuỗi rỗng ở ảnh đại diện = bỏ ảnh.
      const saved = await save.mutateAsync({ fullName, phone, avatarUrl });
      setSessionName(saved.fullName);
      setNotice({ kind: 'success', text: 'Đã cập nhật hồ sơ' });
    } catch (err) {
      const error = toApiError(err);
      const fields = fieldErrorsOf(error);
      if (Object.keys(fields).length) setErrors(fields);
      else setNotice({ kind: 'error', text: plainMessage(error) });
    }
  };

  return (
    <Card className="card">
      <CardContent className="p-6">
        <form onSubmit={submit} noValidate className="space-y-5">
          <div className="flex items-center gap-4">
            <Avatar url={avatarUrl} name={fullName} />
            <div>
              <p className="font-semibold text-(--text-primary)">{profile.fullName}</p>
              <p className="text-sm text-(--text-secondary)">
                {ROLE_LABELS[account.role]}
                {profile.branchId != null && (
                  <>
                    {' · '}
                    <BranchName branchId={profile.branchId} />
                  </>
                )}
              </p>
            </div>
          </div>

          <div className="grid gap-4 md:grid-cols-2">
            <Field label="Họ tên" error={errors.fullName}>
              {(id) => <Input id={id} value={fullName} maxLength={100} onChange={(e) => setFullName(e.target.value)} />}
            </Field>
            <Field label="Số điện thoại" error={errors.phone}>
              {(id) => <Input id={id} type="tel" value={phone} placeholder="0xxxxxxxxx" onChange={(e) => setPhone(e.target.value)} />}
            </Field>
            <Field label="Ảnh đại diện" hint="Dán đường dẫn ảnh https://…; để trống để bỏ ảnh." error={errors.avatarUrl}>
              {(id) => <Input id={id} value={avatarUrl} placeholder="https://" onChange={(e) => setAvatarUrl(e.target.value)} />}
            </Field>
            <Field label="Email" hint="Không tự sửa được; liên hệ cấp trên để được sửa hộ (BR-TK-15).">
              {(id) => <Input id={id} value={account.email} readOnly className="bg-(--bg-tertiary)!" />}
            </Field>
          </div>

          <NoticeBar notice={notice} />

          <div className="flex flex-wrap items-center justify-between gap-3">
            <Link
              to={ROUTES.changePassword}
              state={{ from: '/admin/profile' }}
              className="inline-flex items-center gap-1.5 text-sm font-medium text-accent hover:underline"
            >
              <KeyRound className="h-4 w-4" />
              Đổi mật khẩu
            </Link>
            <Button type="submit" disabled={save.isPending}>
              <Save className="h-4 w-4" />
              Lưu thông tin
            </Button>
          </div>
        </form>
      </CardContent>
    </Card>
  );
}

function VetIntroCard({ profile }: { profile: StaffProfile }) {
  const [specialty, setSpecialty] = useState(profile.specialty ?? '');
  const [bio, setBio] = useState(profile.bio ?? '');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState<Notice | null>(null);
  const save = useProfileMutation(profileApi.updateStaffProfile);
  // BE đếm sau khi bỏ khoảng trắng hai đầu, theo ký tự.
  const bioLength = [...bio.trim()].length;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setNotice(null);
    if (bioLength > BIO_MAX) return setError(`Mô tả ngắn tối đa ${BIO_MAX} ký tự`);
    setError('');
    try {
      // Chuỗi rỗng = xóa chuyên môn / mô tả.
      await save.mutateAsync({ specialty, bio });
      setNotice({ kind: 'success', text: 'Đã lưu hồ sơ giới thiệu' });
    } catch (err) {
      setError(plainMessage(toApiError(err)));
    }
  };

  return (
    <Card className="card">
      <CardContent className="p-6">
        <section className="space-y-5">
          <div>
            <h2 className="text-lg font-semibold text-(--text-primary)">Hồ sơ giới thiệu công khai</h2>
            <p className="text-sm text-(--text-secondary)">
              Hiện ở trang "Đội ngũ bác sĩ" khi tài khoản đang hoạt động, cùng ảnh đại diện và chi nhánh của bạn.
            </p>
          </div>
          <div className="grid gap-6 lg:grid-cols-[1fr_320px]">
            <form onSubmit={submit} noValidate className="space-y-4">
              <Field label="Chuyên môn" hint="Để trống để bỏ chuyên môn.">
                {(id) => (
                  <Input
                    id={id}
                    value={specialty}
                    maxLength={200}
                    placeholder="Ví dụ: Nội khoa, da liễu thú y"
                    onChange={(e) => setSpecialty(e.target.value)}
                  />
                )}
              </Field>
              <Field label="Mô tả ngắn" hint={`${bioLength}/${BIO_MAX}`}>
                {(id) => (
                  <textarea
                    id={id}
                    rows={5}
                    value={bio}
                    placeholder="Kinh nghiệm, thế mạnh chuyên môn…"
                    onChange={(e) => setBio(e.target.value)}
                    className={textareaClass}
                  />
                )}
              </Field>
              <FormError message={error} />
              <NoticeBar notice={notice} />
              <div className="flex justify-end">
                <Button type="submit" disabled={save.isPending}>
                  <Save className="h-4 w-4" />
                  Lưu hồ sơ giới thiệu
                </Button>
              </div>
            </form>

            <article aria-label="Xem trước" className="rounded-2xl border border-(--border-color) bg-(--bg-secondary) p-5">
              <p className="mb-3 text-xs font-semibold uppercase tracking-wide text-(--text-tertiary)">Xem trước</p>
              <div className="flex items-center gap-3">
                <Avatar url={profile.avatarUrl} name={profile.fullName} size="h-14 w-14" />
                <div>
                  <p className="font-semibold text-(--text-primary)">{profile.fullName}</p>
                  {specialty.trim() && <p className="text-sm text-accent">{specialty.trim()}</p>}
                </div>
              </div>
              {bio.trim() && <p className="mt-3 whitespace-pre-line text-sm text-(--text-secondary)">{bio.trim()}</p>}
              {profile.branchId != null && (
                <p className="mt-3 text-xs text-(--text-tertiary)">
                  <BranchName branchId={profile.branchId} />
                </p>
              )}
            </article>
          </div>
        </section>
      </CardContent>
    </Card>
  );
}
