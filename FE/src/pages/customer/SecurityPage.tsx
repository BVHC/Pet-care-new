import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  CheckCircle,
  Database,
  Download,
  Eye,
  Fingerprint,
  KeyRound,
  Lock,
  Mail,
  MapPin,
  Shield,
  Smartphone,
  Trash2,
  X,
} from 'lucide-react'
import { toast } from 'sonner'
import { AccountSidebar } from '@/components/customer/AccountSidebar'
import styles from './SecurityPage.module.css'

/* ================================================================
   Main.
   ================================================================ */

export function SecurityPage() {
  const [twoFa, setTwoFa] = useState(false)
  const [loginAlert, setLoginAlert] = useState(true)
  const [showPwForm, setShowPwForm] = useState(false)
  const [pwForm, setPwForm] = useState({ current: '', next: '', confirm: '' })
  const [pwError, setPwError] = useState('')

  const set = (patch: Partial<typeof pwForm>) =>
    setPwForm((f) => ({ ...f, ...patch }))

  const handleChangePw = (e: React.FormEvent) => {
    e.preventDefault()
    setPwError('')
    if (pwForm.next.length < 8) {
      setPwError('Mật khẩu mới phải có ít nhất 8 ký tự.')
      return
    }
    if (pwForm.next !== pwForm.confirm) {
      setPwError('Mật khẩu xác nhận không khớp.')
      return
    }
    toast.success('Đổi mật khẩu thành công!')
    setPwForm({ current: '', next: '', confirm: '' })
    setShowPwForm(false)
  }

  return (
    <div className="bg-(--color-surface-page) pb-24">
      {/* hero slab */}
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
            <span className="font-semibold text-white/85">Bảo mật</span>
          </nav>
          <h1 className="font-friendly font-extrabold text-[clamp(22px,3vw,36px)] leading-[1.05] text-white">
            Bảo mật tài khoản
          </h1>
        </div>
      </section>

      {/* content */}
      <div className="mx-auto max-w-[1280px] px-5 pt-8 sm:px-8">
        <div className={styles.page}>
          <AccountSidebar active="security" />

          <div className={styles.main}>
            {/* Password */}
            <section className={styles.section}>
              <div className={styles.sectionHeader}>
                <h2 className={styles.sectionTitle}>Mật khẩu</h2>
              </div>
              <div className={styles.sectionBody}>
                {!showPwForm ? (
                  <div className={styles.secItem}>
                    <div className={`${styles.secIcon} ${styles.secIconPass}`}>
                      <KeyRound size={20} />
                    </div>
                    <div className={styles.secContent}>
                      <div className={styles.secTitle}>Đổi mật khẩu</div>
                      <div className={styles.secDesc}>
                        Đặt mật khẩu mạnh để bảo vệ tài khoản.
                      </div>
                    </div>
                    <button
                      type="button"
                      onClick={() => setShowPwForm(true)}
                      className={styles.secBtn}
                    >
                      Đổi mật khẩu
                    </button>
                  </div>
                ) : (
                  <form onSubmit={handleChangePw} className={styles.pwForm}>
                    <div className={styles.field}>
                      <label className={styles.fieldLabel}>Mật khẩu hiện tại</label>
                      <input
                        type="password"
                        value={pwForm.current}
                        onChange={(e) => set({ current: e.target.value })}
                        placeholder="Nhập mật khẩu hiện tại"
                        className={styles.fieldInput}
                      />
                    </div>
                    <div className={styles.field}>
                      <label className={styles.fieldLabel}>Mật khẩu mới</label>
                      <input
                        type="password"
                        value={pwForm.next}
                        onChange={(e) => set({ next: e.target.value })}
                        placeholder="Ít nhất 8 ký tự"
                        className={styles.fieldInput}
                      />
                    </div>
                    <div className={styles.field}>
                      <label className={styles.fieldLabel}>Xác nhận mật khẩu mới</label>
                      <input
                        type="password"
                        value={pwForm.confirm}
                        onChange={(e) => set({ confirm: e.target.value })}
                        placeholder="Nhập lại mật khẩu mới"
                        className={`${styles.fieldInput} ${pwError ? styles.fieldInputError : ''}`}
                      />
                      {pwError && <span className={styles.fieldError}>{pwError}</span>}
                    </div>
                    <div className={styles.formActions}>
                      <button
                        type="button"
                        onClick={() => { setShowPwForm(false); setPwForm({ current: '', next: '', confirm: '' }); setPwError('') }}
                        className={styles.btnCancel}
                      >
                        Hủy
                      </button>
                      <button type="submit" className={styles.btnSave}>
                        Lưu mật khẩu
                      </button>
                    </div>
                  </form>
                )}
              </div>
            </section>

            {/* 2FA */}
            <section className={styles.section}>
              <div className={styles.sectionHeader}>
                <h2 className={styles.sectionTitle}>Xác thực hai yếu tố</h2>
              </div>
              <div className={styles.sectionBody}>
                <div className={styles.secItem}>
                  <div className={`${styles.secIcon} ${styles.secIcon2fa}`}>
                    <Fingerprint size={20} />
                  </div>
                  <div className={styles.secContent}>
                    <div className={styles.secTitle}>Bảo vệ bằng 2FA</div>
                    <div className={styles.secDesc}>
                      Nhận mã OTP qua SMS mỗi khi đăng nhập từ thiết bị mới.
                    </div>
                    {twoFa && <div className={styles.secMeta}><CheckCircle size={13} className="inline mr-1 text-emerald-600 align-[-1px]" />Đã bật</div>}
                  </div>
                  <button
                    type="button"
                    onClick={() => {
                      setTwoFa((v) => !v)
                      toast.success(twoFa ? 'Đã tắt 2FA' : 'Đã bật 2FA — vui lòng xác minh số điện thoại.')
                    }}
                    className={`${styles.secToggle} ${twoFa ? styles.secToggleActive : ''}`}
                    aria-pressed={twoFa}
                  >
                    <span className={styles.secToggleKnob} />
                  </button>
                </div>
              </div>
            </section>

            {/* Email */}
            <section className={styles.section}>
              <div className={styles.sectionHeader}>
                <h2 className={styles.sectionTitle}>Email & SĐT</h2>
              </div>
              <div className={styles.sectionBody}>
                <div className={styles.secItem}>
                  <div className={`${styles.secIcon} ${styles.secIconEmail}`}>
                    <Mail size={20} />
                  </div>
                  <div className={styles.secContent}>
                    <div className={styles.secTitle}>nguyenvana@email.com</div>
                    <div className={styles.secDesc}>Email đã xác minh · Dùng để đăng nhập</div>
                  </div>
                  <button type="button" className={`${styles.secBtn} ${styles.secBtnDanger}`}>
                    Thay đổi
                  </button>
                </div>
                <div className={styles.secItem}>
                  <div className={`${styles.secIcon} ${styles.secIconPhone}`}>
                    <Smartphone size={20} />
                  </div>
                  <div className={styles.secContent}>
                    <div className={styles.secTitle}>0901 234 567</div>
                    <div className={styles.secDesc}>Dùng để nhận mã OTP và thông báo</div>
                    {twoFa && <div className={styles.secMeta}><CheckCircle size={13} className="inline mr-1 text-emerald-600 align-[-1px]" />Đã liên kết 2FA</div>}
                  </div>
                  <button type="button" className={`${styles.secBtn} ${styles.secBtnDanger}`}>
                    Thay đổi
                  </button>
                </div>
              </div>
            </section>

            {/* Alerts */}
            <section className={styles.section}>
              <div className={styles.sectionHeader}>
                <h2 className={styles.sectionTitle}>Cảnh báo đăng nhập</h2>
              </div>
              <div className={styles.sectionBody}>
                <div className={styles.secItem}>
                  <div className={`${styles.secIcon} ${styles.secIconSession}`}>
                    <Lock size={20} />
                  </div>
                  <div className={styles.secContent}>
                    <div className={styles.secTitle}>Thông báo đăng nhập bất thường</div>
                    <div className={styles.secDesc}>
                      Gửi email + SMS khi có đăng nhập từ thiết bị hoặc IP lạ.
                    </div>
                    {loginAlert && <div className={styles.secMeta}><CheckCircle size={13} className="inline mr-1 text-emerald-600 align-[-1px]" />Đã bật</div>}
                  </div>
                  <button
                    type="button"
                    onClick={() => {
                      setLoginAlert((v) => !v)
                      toast.success(loginAlert ? 'Đã tắt thông báo' : 'Đã bật thông báo đăng nhập.')
                    }}
                    className={`${styles.secToggle} ${loginAlert ? styles.secToggleActive : ''}`}
                    aria-pressed={loginAlert}
                  >
                    <span className={styles.secToggleKnob} />
                  </button>
                </div>
              </div>
            </section>

            {/* Sessions */}
            <section className={styles.section}>
              <div className={styles.sectionHeader}>
                <h2 className={styles.sectionTitle}>Phiên đăng nhập</h2>
              </div>
              <div className={styles.sectionBody}>
                <div className={styles.secItem}>
                  <div className={`${styles.secIcon} ${styles.secIconPass}`}>
                    <CheckCircle size={20} />
                  </div>
                  <div className={styles.secContent}>
                    <div className={styles.secTitle}>MacBook Pro · Trình duyệt hiện tại</div>
                    <div className={styles.secDesc}>Hà Nội, Việt Nam · Đang hoạt động</div>
                    <div className={styles.secMeta}>Phiên hiện tại</div>
                  </div>
                </div>
                <div className={styles.secItem}>
                  <div className={`${styles.secIcon} ${styles.secIconSession}`}>
                    <Smartphone size={20} />
                  </div>
                  <div className={styles.secContent}>
                    <div className={styles.secTitle}>iPhone 15 Pro · App Pet Care</div>
                    <div className={styles.secDesc}>TP.HCM, Việt Nam · 2 giờ trước</div>
                  </div>
                  <button
                    type="button"
                    onClick={() => toast.success('Đã đăng xuất thiết bị.')}
                    className={`${styles.secBtn} ${styles.secBtnDanger}`}
                  >
                    <Trash2 size={13} />
                    Đăng xuất
                  </button>
                </div>
              </div>
            </section>

            {/* Privacy & Data */}
            <section className={styles.section}>
              <div className={styles.sectionHeader}>
                <h2 className={styles.sectionTitle}>Quyền riêng tư & Dữ liệu</h2>
              </div>
              <div className={styles.sectionBody}>
                {/* Data export */}
                <div className={styles.secItem}>
                  <div className={`${styles.secIcon} ${styles.secIconPass}`}>
                    <Download size={20} />
                  </div>
                  <div className={styles.secContent}>
                    <div className={styles.secTitle}>Tải về dữ liệu cá nhân</div>
                    <div className={styles.secDesc}>
                      Xuất toàn bộ dữ liệu của bạn (hồ sơ, thú cưng, đơn hàng, bệnh án) dưới dạng file JSON hoặc PDF. Quá trình xuất có thể mất vài phút.
                    </div>
                  </div>
                  <button
                    type="button"
                    onClick={() => toast.success('Yêu cầu xuất dữ liệu đã được gửi. Bạn sẽ nhận email trong 24 giờ.')}
                    className={`${styles.secBtn}`}
                  >
                    <Database size={13} />
                    Yêu cầu xuất
                  </button>
                </div>

                {/* Consent management */}
                <div className={styles.secItem}>
                  <div className={`${styles.secIcon} ${styles.secIcon2fa}`}>
                    <Eye size={20} />
                  </div>
                  <div className={styles.secContent}>
                    <div className={styles.secTitle}>Duyệt quyền xem bệnh án liên chi nhánh</div>
                    <div className={styles.secDesc}>
                      Cho phép chi nhánh khác xem hồ sơ bệnh án của thú cưng trong vòng 24 giờ qua.
                    </div>
                  </div>
                  <button
                    type="button"
                    onClick={() => toast.info('Đang mở danh sách chi nhánh được cấp quyền...')}
                    className={`${styles.secBtn}`}
                  >
                    <Shield size={13} />
                    Quản lý
                  </button>
                </div>

                {/* OTP consent list */}
                <div className={styles.secItem}>
                  <div className={`${styles.secIcon} ${styles.secIconSession}`}>
                    <MapPin size={20} />
                  </div>
                  <div className={styles.secContent}>
                    <div className={styles.secTitle}>Chi nhánh được phép xem hồ sơ</div>
                    <div className={styles.secDesc}>
                      Danh sách chi nhánh đã được bạn cấp quyền xem hồ sơ bệnh án 24h. Thu hồi để chặn quyền truy cập.
                    </div>
                  </div>
                </div>

                {/* Branch consent items */}
                {[
                  { branch: 'PetCare Nguyễn Trãi', address: '123 Nguyễn Trãi, Q.1, TP.HCM', granted: '22/08/2026' },
                  { branch: 'PetCare Quận 7', address: '456 Đường 9, Q.7, TP.HCM', granted: '20/08/2026' },
                ].map((b) => (
                  <div key={b.branch} className={styles.consentItem}>
                    <div className={styles.consentBranch}>
                      <MapPin size={14} className="shrink-0 text-accent" />
                      <div>
                        <div className="text-[13px] font-bold text-(--color-text-primary)">{b.branch}</div>
                        <div className="text-[11.5px] text-(--color-text-secondary)">{b.address}</div>
                        <div className="mt-0.5 text-[11px] text-(--color-text-secondary)">Cấp quyền: {b.granted}</div>
                      </div>
                    </div>
                    <button
                      type="button"
                      onClick={() => toast.success(`Đã thu hồi quyền của ${b.branch}.`)}
                      className={`${styles.secBtn} ${styles.secBtnDanger}`}
                    >
                      <X size={12} />
                      Thu hồi
                    </button>
                  </div>
                ))}

                {/* Delete account */}
                <div className={`${styles.secItem} ${styles.secItemDanger}`}>
                  <div className={`${styles.secIcon} ${styles.secIconDanger}`}>
                    <Trash2 size={20} />
                  </div>
                  <div className={styles.secContent}>
                    <div className={styles.secTitle}>Yêu cầu xóa tài khoản</div>
                    <div className={styles.secDesc}>
                      Xóa vĩnh viễn tài khoản và toàn bộ dữ liệu cá nhân. Thao tác này <strong>không thể hoàn tác</strong>. Tiền trong ví và quyền lợi hội viên sẽ bị mất.
                    </div>
                  </div>
                  <button
                    type="button"
                    onClick={() => {
                      if (confirm('Bạn có chắc muốn xóa tài khoản? Hành động này không thể hoàn tác.')) {
                        toast.success('Yêu cầu xóa tài khoản đã được gửi. Bạn sẽ nhận email xác nhận trong vài phút.')
                      }
                    }}
                    className={`${styles.secBtn} ${styles.secBtnDanger}`}
                  >
                    <Trash2 size={13} />
                    Xóa tài khoản
                  </button>
                </div>
              </div>
            </section>
          </div>
        </div>
      </div>
    </div>
  )
}
