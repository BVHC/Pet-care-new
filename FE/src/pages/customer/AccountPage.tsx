// ===========================================
// AccountPage - T13: Hồ sơ cá nhân + sổ địa chỉ + liên kết hồ sơ
// Gọi T8 (BE-1) endpoints:
//   GET /me                    - thông tin người đang đăng nhập
//   PATCH /me/staff-profile    - sửa hồ sơ nhân viên
// Theo identity-v1.md, BR-TK-15, BR-TK-18, BR-TK-19
// ===========================================
import { useState, useEffect, useCallback } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Camera, Save, Loader2, LogIn } from 'lucide-react';
import { toast } from 'sonner';
import { AccountSidebar } from '@/components/customer/AccountSidebar';
import { AddressBook } from '@/components/customer/AddressBook';
import { VetProfile } from '@/components/customer/VetProfile';
import { ProfileLinking } from '@/components/customer/ProfileLinking';
import { getMe, updateStaffProfile } from '@/shared/api/profile.api';
import type { MeResponse } from '@/shared/models/profile.model';
import { isVetUser } from '@/shared/models/profile.model';
import { useAccount } from '@/shared/stores/session.store';
import { ROLE_LABELS, isStaff, type Role } from '@/shared/types/auth';
import { ROUTES } from '@/shared/constants/routes';
import styles from './AccountPage.module.css';

// Role helpers
function isVet(r: Role): boolean {
  return isVetUser(r);
}

/* ================================================================
   Profile Form
   ================================================================ */

interface ProfileFormProps {
  me: MeResponse;
  onSaved: () => void;
}

function ProfileForm({ me, onSaved }: ProfileFormProps) {
  const { account, staffProfile } = me;

  // Form state
  const [fullName, setFullName] = useState(staffProfile?.fullName ?? '');
  const [phone, setPhone] = useState(staffProfile?.phone ?? '');
  const [avatarUrl, setAvatarUrl] = useState(staffProfile?.avatarUrl ?? '');
  const [saving, setSaving] = useState(false);

  // Sync when me changes
  useEffect(() => {
    setFullName(staffProfile?.fullName ?? '');
    setPhone(staffProfile?.phone ?? '');
    setAvatarUrl(staffProfile?.avatarUrl ?? '');
  }, [staffProfile]);

  const initials = fullName
    .split(' ')
    .slice(-2)
    .map((w) => w[0] ?? '')
    .join('')
    .toUpperCase() || '??';

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    try {
      setSaving(true);
      await updateStaffProfile({
        fullName: fullName.trim() || undefined,
        phone: phone.trim() || undefined,
        avatarUrl: avatarUrl.trim() || undefined,
      });
      toast.success('Cập nhật thông tin thành công');
      onSaved();
    } catch (err) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
      toast.error(msg ?? 'Cập nhật thất bại');
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className={styles.section} id="profile">
      <div className={styles.sectionHeader}>
        <h2 className={styles.sectionTitle}>Thông tin cá nhân</h2>
        <span className={styles.roleBadge}>{ROLE_LABELS[account.role]}</span>
      </div>

      <form onSubmit={handleSubmit} className={styles.profileBody} noValidate>
        {/* Avatar */}
        <div className={styles.avatarRow}>
          <div className={styles.avatarCircle}>
            {avatarUrl ? (
              <img src={avatarUrl} alt="Avatar" className={styles.avatarImg} />
            ) : (
              <span>{initials}</span>
            )}
          </div>
          <div>
            <button type="button" className={styles.avatarBtn} disabled title="Đổi ảnh (sắp ra mắt)">
              <Camera size={14} />
              Đổi ảnh đại diện
            </button>
            <p className={styles.avatarHint}>JPG, PNG · tối đa 2 MB</p>
          </div>
        </div>

        <div className={styles.formGrid}>
          <div className={styles.field}>
            <label htmlFor="fullName" className={styles.fieldLabel}>Họ tên</label>
            <input
              id="fullName"
              type="text"
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              className={styles.fieldInput}
              placeholder="Nhập họ tên"
            />
          </div>

          <div className={styles.field}>
            <label htmlFor="phone" className={styles.fieldLabel}>Số điện thoại</label>
            <input
              id="phone"
              type="tel"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              className={styles.fieldInput}
              placeholder="0xxx xxx xxx"
            />
          </div>

          <div className={styles.field} style={{ gridColumn: '1 / -1' }}>
            <label htmlFor="email" className={styles.fieldLabel}>
              Email <span className={styles.readonly}>(không thể sửa)</span>
            </label>
            <input
              id="email"
              type="email"
              value={account.email}
              className={`${styles.fieldInput} ${styles.readonlyInput}`}
              readOnly
              title="Email không thể tự sửa theo BR-TK-15"
            />
          </div>
        </div>

        <div className={styles.formActions}>
          <button type="submit" className={styles.saveBtn} disabled={saving}>
            {saving ? <Loader2 size={16} className={styles.spinner} /> : <Save size={16} />}
            {saving ? 'Đang lưu...' : 'Lưu thay đổi'}
          </button>
        </div>
      </form>
    </section>
  );
}

/* ================================================================
   Main page.
   ================================================================ */

export function AccountPage() {
  const navigate = useNavigate();
  const isAuthenticated = !!useAccount();
  const [me, setMe] = useState<MeResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [activeSection, setActiveSection] = useState<'profile' | 'addresses' | 'vet' | 'linking'>('profile');

  // Load me data
  async function loadMe() {
    try {
      setLoading(true);
      setError(null);
      const data = await getMe();
      setMe(data);
    } catch (err) {
      const status = (err as { response?: { status?: number } })?.response?.status;
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
      if (status === 401) {
        setError('Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.');
      } else {
        setError(msg ?? 'Không tải được thông tin tài khoản');
      }
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (isAuthenticated) {
      loadMe();
    } else {
      setLoading(false);
    }
  }, [isAuthenticated]);

  const handleSaved = useCallback(() => {
    loadMe();
  }, []);

  function handleLoginRedirect() {
    navigate(ROUTES.login, { state: { from: '/account' } });
  }

  // Chưa đăng nhập → yêu cầu đăng nhập
  if (!isAuthenticated) {
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
            <h1 className="font-friendly font-extrabold text-[clamp(22px,3vw,36px)] leading-[1.05] text-white">
              Tài khoản của tôi
            </h1>
          </div>
        </section>

        <div className="mx-auto max-w-[1280px] px-5 pt-8 sm:px-8">
          <div className={styles.notAuthBox}>
            <div className={styles.notAuthIcon}>
              <LogIn size={40} />
            </div>
            <h2 className={styles.notAuthTitle}>Bạn chưa đăng nhập</h2>
            <p className={styles.notAuthText}>
              Vui lòng đăng nhập để xem và quản lý thông tin tài khoản, sổ địa chỉ, hồ sơ giới thiệu và liên kết hồ sơ.
            </p>
            <button type="button" className={styles.notAuthBtn} onClick={handleLoginRedirect}>
              <LogIn size={16} />
              Đăng nhập ngay
            </button>
          </div>
        </div>
      </div>
    );
  }

  // Role checks
  const role = me?.account.role ?? 'CUSTOMER';
  const showVetProfile = isVet(role);
  const showLinking = me?.linkDecisionPending ?? false;

  return (
    <div className="bg-(--color-surface-page) pb-24">
      {/* Hero slab */}
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
          <h1 className="font-friendly font-extrabold text-[clamp(22px,3vw,36px)] leading-[1.05] text-white">
            Tài khoản của tôi
          </h1>
        </div>
      </section>

      {/* Content */}
      <div className="mx-auto max-w-[1280px] px-5 pt-8 sm:px-8">
        {loading && !me ? (
          <div className={styles.loadingState}>
            <Loader2 size={32} className={styles.spinner} />
            <span>Đang tải thông tin...</span>
          </div>
        ) : error && !me ? (
          <div className={styles.errorState}>
            <p>{error}</p>
            <button type="button" className={styles.retryBtn} onClick={handleLoginRedirect}>
              Đăng nhập lại
            </button>
          </div>
        ) : me ? (
          <div className={styles.page}>
            {/* Sidebar */}
            <AccountSidebar
              active="profile"
              userName={me.staffProfile?.fullName}
              userEmail={me.account.email}
            />

            {/* Main */}
            <div className={styles.main}>
              {/* Tabs for staff users */}
              {isStaff(role) && (
                <div className={styles.tabs}>
                  <button
                    type="button"
                    className={`${styles.tab} ${activeSection === 'profile' ? styles.tabActive : ''}`}
                    onClick={() => setActiveSection('profile')}
                  >
                    Hồ sơ cá nhân
                  </button>
                  <button
                    type="button"
                    className={`${styles.tab} ${activeSection === 'addresses' ? styles.tabActive : ''}`}
                    onClick={() => setActiveSection('addresses')}
                  >
                    Sổ địa chỉ
                  </button>
                  {showVetProfile && (
                    <button
                      type="button"
                      className={`${styles.tab} ${activeSection === 'vet' ? styles.tabActive : ''}`}
                      onClick={() => setActiveSection('vet')}
                    >
                      Hồ sơ giới thiệu
                    </button>
                  )}
                  {showLinking && (
                    <button
                      type="button"
                      className={`${styles.tab} ${activeSection === 'linking' ? styles.tabActive : ''}`}
                      onClick={() => setActiveSection('linking')}
                    >
                      Liên kết hồ sơ
                    </button>
                  )}
                </div>
              )}

              {/* Profile Form */}
              {activeSection === 'profile' && (
                <ProfileForm me={me} onSaved={handleSaved} />
              )}

              {/* Address Book */}
              {activeSection === 'addresses' && (
                <AddressBook onAddressChange={handleSaved} />
              )}

              {/* Vet Profile */}
              {activeSection === 'vet' && showVetProfile && (
                <VetProfile
                  staffProfile={me.staffProfile}
                  onSaved={handleSaved}
                />
              )}

              {/* Profile Linking */}
              {activeSection === 'linking' && showLinking && (
                <ProfileLinking
                  linkDecisionPending={me.linkDecisionPending}
                  onLinked={handleSaved}
                  onDeclined={handleSaved}
                />
              )}

              {/* Non-staff: show simplified profile */}
              {!isStaff(role) && (
                <>
                  <ProfileForm me={me} onSaved={handleSaved} />
                  <AddressBook onAddressChange={handleSaved} />
                  {showLinking && (
                    <ProfileLinking
                      linkDecisionPending={me.linkDecisionPending}
                      onLinked={handleSaved}
                      onDeclined={handleSaved}
                    />
                  )}
                </>
              )}
            </div>
          </div>
        ) : (
          <div className={styles.errorState}>
            <p>Không thể tải thông tin tài khoản.</p>
            <button type="button" className={styles.retryBtn} onClick={loadMe}>
              Thử lại
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
