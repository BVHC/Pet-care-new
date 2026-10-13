// UC06 (hồ sơ khách, sổ địa chỉ) và UC07 (liên kết hồ sơ tại quầy) của khách A02. Nhân viên có trang riêng
// /admin/profile. customer-v1 #1–7, identity-v1 #8, 11–14.
import { useEffect, useState, type FormEvent } from 'react';
import { Link, NavLink, Navigate, useParams } from 'react-router-dom';
import { AlertTriangle, Loader2, Save } from 'lucide-react';
import { AccountSidebar } from '@/components/customer/AccountSidebar';
import { AddressBook } from '@/components/customer/AddressBook';
import { ProfileLinking } from '@/components/customer/ProfileLinking';
import { Field, FormError, NoticeBar, type Notice } from '@/components/admin/form-ui';
import { cn } from '@/lib/utils';
import { fieldErrorsOf, plainMessage, toApiError } from '@/shared/api/api-error';
import { profileApi } from '@/shared/api/profile.api';
import { useCustomerProfile, useMe, useProfileMutation } from '@/shared/hooks/useProfile';
import { useAccount, useSession } from '@/shared/stores/session.store';
import type { CustomerProfile } from '@/shared/types/profile';
import styles from './AccountPage.module.css';

const SECTIONS = [
  { key: 'profile', path: '/account', label: 'Hồ sơ cá nhân' },
  { key: 'addresses', path: '/account/addresses', label: 'Sổ địa chỉ' },
  { key: 'link', path: '/account/link', label: 'Liên kết hồ sơ' },
] as const;

type Section = (typeof SECTIONS)[number]['key'];

export function AccountPage() {
  const account = useAccount();
  const { section = 'profile' } = useParams();
  if (account && account.role !== 'CUSTOMER') return <Navigate to="/admin/profile" replace />;
  if (!SECTIONS.some((s) => s.key === section)) return <Navigate to="/account" replace />;
  return <CustomerAccount section={section as Section} />;
}

function CustomerAccount({ section }: { section: Section }) {
  const me = useMe();
  const profile = useCustomerProfile();
  const setLinkDecisionPending = useSession((s) => s.setLinkDecisionPending);
  const pending = me.data?.linkDecisionPending ?? false;

  // Cờ chờ liên kết có thể đổi ở nơi khác (lễ tân liên kết hộ): đồng bộ về phiên để chặn đúng các trang (BR-TK-19).
  useEffect(() => {
    if (me.data) setLinkDecisionPending(me.data.linkDecisionPending);
  }, [me.data, setLinkDecisionPending]);

  return (
    <div className="bg-(--color-surface-page) pb-24">
      <section className={styles.slab}>
        <img src="/imgs/hero-dog-clean.png" alt="" className={styles.slabBg} aria-hidden loading="eager" />
        <div className={styles.slabOverlay} aria-hidden />
        <div className={styles.slabNoise} aria-hidden />
        <div className={`${styles.slabContent} mx-auto max-w-[1280px] px-5 sm:px-8`}>
          <nav aria-label="Đường dẫn" className="mb-5 text-[12.5px] text-white/50">
            <Link to="/" className="hover:text-white hover:underline underline-offset-4 transition-colors">
              Trang chủ
            </Link>
            <span className="mx-1.5">/</span>
            <span className="font-semibold text-white/85">Tài khoản</span>
          </nav>
          <h1 className="font-friendly font-extrabold text-[clamp(22px,3vw,36px)] leading-[1.05] text-white">Tài khoản của tôi</h1>
        </div>
      </section>

      <div className="mx-auto max-w-[1280px] px-5 pt-8 sm:px-8">
        <div className={styles.page}>
          <AccountSidebar active="profile" userName={profile.data?.fullName} userEmail={profile.data?.email ?? undefined} />

          <div className={styles.main}>
            <nav aria-label="Mục hồ sơ" className={styles.tabs}>
              {SECTIONS.map((s) => (
                <NavLink key={s.key} to={s.path} end className={({ isActive }) => cn(styles.tab, isActive && styles.tabActive)}>
                  {s.label}
                </NavLink>
              ))}
            </nav>

            {pending && section !== 'link' && <PendingLinkBanner />}

            {profile.isLoading && (
              <div className={styles.loadingState}>
                <Loader2 size={32} className={styles.spinner} />
                <span>Đang tải thông tin...</span>
              </div>
            )}
            {profile.isError && <FormError message={plainMessage(toApiError(profile.error))} />}

            {profile.data && section === 'profile' && <ProfileForm key={profile.data.customerId} profile={profile.data} />}
            {section === 'addresses' && <AddressBook />}
            {profile.data && section === 'link' && <ProfileLinking profile={profile.data} pending={pending} />}
          </div>
        </div>
      </div>
    </div>
  );
}

/** BR-TK-19: còn cờ thì nhắc liên kết, các chức năng cần hồ sơ đang bị ẩn. */
function PendingLinkBanner() {
  return (
    <div className="flex gap-3 rounded-2xl border border-amber-200 bg-amber-50 p-4 text-amber-900">
      <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0" />
      <div className="space-y-1 text-sm">
        <p className="font-semibold">Bạn đã từng đến phòng khám Pet Care?</p>
        <p>
          Số điện thoại bạn khai trùng với hồ sơ tại phòng khám. Hãy liên kết, hoặc chọn "Không phải tôi", trước khi quản lý thú cưng,
          đặt lịch hay gửi góp ý.
        </p>
        <Link to="/account/link" className="inline-block font-semibold text-accent hover:underline">
          Liên kết hồ sơ ngay
        </Link>
      </div>
    </div>
  );
}

function ProfileForm({ profile }: { profile: CustomerProfile }) {
  const setSessionName = useSession((s) => s.setFullName);
  const [fullName, setFullName] = useState(profile.fullName);
  const [phone, setPhone] = useState(profile.phone ?? '');
  const [avatarUrl, setAvatarUrl] = useState(profile.avatarUrl ?? '');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [notice, setNotice] = useState<Notice | null>(null);
  const save = useProfileMutation(profileApi.updateCustomerProfile);
  const counter = profile.createdChannel === 'COUNTER';

  const initials =
    fullName
      .split(' ')
      .slice(-2)
      .map((w) => w[0] ?? '')
      .join('')
      .toUpperCase() || '?';

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setErrors({});
    setNotice(null);
    try {
      // Chuỗi rỗng = xóa SĐT (chỉ hồ sơ online) / bỏ ảnh.
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
    <section className={styles.section}>
      <div className={styles.sectionHeader}>
        <h2 className={styles.sectionTitle}>Thông tin cá nhân</h2>
        <span className={styles.roleBadge}>{counter ? 'Hồ sơ tại phòng khám' : 'Hồ sơ online'}</span>
      </div>

      <form onSubmit={submit} className={styles.profileBody} noValidate>
        <div className={styles.avatarRow}>
          <div className={styles.avatarCircle}>
            {avatarUrl.trim().startsWith('https://') ? <img src={avatarUrl.trim()} alt="" className={styles.avatarImg} /> : <span>{initials}</span>}
          </div>
          <p className={styles.avatarHint}>Dán đường dẫn ảnh https://… vào ô "Ảnh đại diện"; để trống để bỏ ảnh.</p>
        </div>

        <div className={styles.formGrid}>
          <Field label="Họ tên" error={errors.fullName}>
            {(id) => <input id={id} className={styles.fieldInput} value={fullName} maxLength={100} onChange={(e) => setFullName(e.target.value)} />}
          </Field>
          <Field
            label="Số điện thoại"
            error={errors.phone}
            hint={counter ? 'Hồ sơ tại phòng khám bắt buộc có số điện thoại.' : 'Để trống nếu không muốn lưu số điện thoại.'}
          >
            {(id) => (
              <input id={id} type="tel" className={styles.fieldInput} value={phone} placeholder="0xxxxxxxxx" onChange={(e) => setPhone(e.target.value)} />
            )}
          </Field>
          <Field label="Ảnh đại diện" error={errors.avatarUrl}>
            {(id) => <input id={id} className={styles.fieldInput} value={avatarUrl} placeholder="https://" onChange={(e) => setAvatarUrl(e.target.value)} />}
          </Field>
          <Field label="Email" hint="Không tự sửa được; liên hệ lễ tân để được sửa hộ (BR-TK-15).">
            {(id) => <input id={id} className={`${styles.fieldInput} ${styles.readonlyInput}`} value={profile.email ?? ''} readOnly />}
          </Field>
        </div>

        <NoticeBar notice={notice} />

        <div className={styles.formActions}>
          <button type="submit" className={styles.saveBtn} disabled={save.isPending}>
            {save.isPending ? <Loader2 size={16} className={styles.spinner} /> : <Save size={16} />}
            Lưu thay đổi
          </button>
        </div>
      </form>
    </section>
  );
}
