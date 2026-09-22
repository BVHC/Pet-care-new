import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  Bell,
  ChevronRight,
  Globe,
  Globe2,
  Info,
  Mail,
  Moon,
  Smartphone,
  Trash2,
} from 'lucide-react'
import { toast } from 'sonner'
import { AccountSidebar } from '@/components/customer/AccountSidebar'
import styles from './SettingsPage.module.css'

/* ================================================================
   Main.
   ================================================================ */

export function SettingsPage() {
  const [notif, setNotif] = useState(true)
  const [dark, setDark] = useState(false)
  const [emailNotif, setEmailNotif] = useState(true)
  const [smsNotif, setSmsNotif] = useState(false)

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
            <span className="font-semibold text-white/85">Cài đặt</span>
          </nav>
          <h1 className="font-bayon text-[clamp(22px,3vw,36px)] leading-[1.02] font-normal text-white">
            Cài đặt
          </h1>
        </div>
      </section>

      {/* Content */}
      <div className="mx-auto max-w-[1280px] px-5 pt-8 sm:px-8">
        <div className={styles.page}>
          <AccountSidebar />

          <div className={styles.main}>
            {/* Notifications */}
            <section className={styles.section}>
              <div className={styles.sectionHeader}>
                <h2 className={styles.sectionTitle}>Thông báo</h2>
              </div>
              <div className={styles.sectionBody}>
                <div className={styles.settingRow}>
                  <div className={`${styles.settingIcon} ${styles.settingIconBlue}`}>
                    <Bell size={20} />
                  </div>
                  <div className={styles.settingContent}>
                    <div className={styles.settingTitle}>Thông báo đẩy</div>
                    <div className={styles.settingDesc}>Nhận thông báo từ ứng dụng</div>
                  </div>
                  <button
                    type="button"
                    onClick={() => setNotif((v) => !v)}
                    className={`${styles.toggle} ${notif ? styles.toggleActive : ''}`}
                    aria-pressed={notif}
                  >
                    <span className={styles.toggleKnob} />
                  </button>
                </div>

                <div className={styles.settingRow}>
                  <div className={`${styles.settingIcon} ${styles.settingIconBlue}`}>
                    <Mail size={20} />
                  </div>
                  <div className={styles.settingContent}>
                    <div className={styles.settingTitle}>Thông báo qua email</div>
                    <div className={styles.settingDesc}>Nhận cập nhật đơn hàng, khuyến mãi qua email</div>
                  </div>
                  <button
                    type="button"
                    onClick={() => setEmailNotif((v) => !v)}
                    className={`${styles.toggle} ${emailNotif ? styles.toggleActive : ''}`}
                    aria-pressed={emailNotif}
                  >
                    <span className={styles.toggleKnob} />
                  </button>
                </div>

                <div className={styles.settingRow}>
                  <div className={`${styles.settingIcon} ${styles.settingIconBlue}`}>
                    <Smartphone size={20} />
                  </div>
                  <div className={styles.settingContent}>
                    <div className={styles.settingTitle}>Thông báo SMS</div>
                    <div className={styles.settingDesc}>Nhận tin nhắn về trạng thái đơn hàng</div>
                  </div>
                  <button
                    type="button"
                    onClick={() => setSmsNotif((v) => !v)}
                    className={`${styles.toggle} ${smsNotif ? styles.toggleActive : ''}`}
                    aria-pressed={smsNotif}
                  >
                    <span className={styles.toggleKnob} />
                  </button>
                </div>
              </div>
            </section>

            {/* Display */}
            <section className={styles.section}>
              <div className={styles.sectionHeader}>
                <h2 className={styles.sectionTitle}>Hiển thị</h2>
              </div>
              <div className={styles.sectionBody}>
                <div className={styles.settingRow}>
                  <div className={`${styles.settingIcon} ${styles.settingIconPurple}`}>
                    <Moon size={20} />
                  </div>
                  <div className={styles.settingContent}>
                    <div className={styles.settingTitle}>Chế độ tối</div>
                    <div className={styles.settingDesc}>Sử dụng giao diện tối</div>
                  </div>
                  <button
                    type="button"
                    onClick={() => {
                      setDark((v) => !v)
                      toast.success('Chế độ tối đang phát triển.')
                    }}
                    className={`${styles.toggle} ${dark ? styles.toggleActive : ''}`}
                    aria-pressed={dark}
                  >
                    <span className={styles.toggleKnob} />
                  </button>
                </div>

                <div className={styles.settingRow}>
                  <div className={`${styles.settingIcon} ${styles.settingIconGreen}`}>
                    <Globe size={20} />
                  </div>
                  <div className={styles.settingContent}>
                    <div className={styles.settingTitle}>Ngôn ngữ</div>
                    <div className={styles.settingDesc}>Tiếng Việt</div>
                  </div>
                  <ChevronRight size={18} className={styles.settingChevron} />
                </div>

                <div className={styles.settingRow}>
                  <div className={`${styles.settingIcon} ${styles.settingIconGreen}`}>
                    <Globe2 size={20} />
                  </div>
                  <div className={styles.settingContent}>
                    <div className={styles.settingTitle}>Đơn vị đo lường</div>
                    <div className={styles.settingDesc}>Kilogram (kg) · Centimet (cm)</div>
                  </div>
                  <ChevronRight size={18} className={styles.settingChevron} />
                </div>
              </div>
            </section>

            {/* Language & Region */}
            <section className={styles.section}>
              <div className={styles.sectionHeader}>
                <h2 className={styles.sectionTitle}>Ngôn ngữ &amp; Khu vực</h2>
              </div>
              <div className={styles.sectionBody}>
                <div className={styles.settingRow}>
                  <div className={`${styles.settingIcon} ${styles.settingIconYellow}`}>
                    <Globe size={20} />
                  </div>
                  <div className={styles.settingContent}>
                    <div className={styles.settingTitle}>Ngôn ngữ ứng dụng</div>
                    <div className={styles.settingDesc}>Tiếng Việt</div>
                  </div>
                  <ChevronRight size={18} className={styles.settingChevron} />
                </div>

                <div className={styles.settingRow}>
                  <div className={`${styles.settingIcon} ${styles.settingIconYellow}`}>
                    <Globe2 size={20} />
                  </div>
                  <div className={styles.settingContent}>
                    <div className={styles.settingTitle}>Múi giờ</div>
                    <div className={styles.settingDesc}>Asia/Ho_Chi_Minh (UTC+7)</div>
                  </div>
                  <ChevronRight size={18} className={styles.settingChevron} />
                </div>

                <div className={styles.settingRow}>
                  <div className={`${styles.settingIcon} ${styles.settingIconYellow}`}>
                    <Globe size={20} />
                  </div>
                  <div className={styles.settingContent}>
                    <div className={styles.settingTitle}>Định dạng ngày &amp; số</div>
                    <div className={styles.settingDesc}>Việt Nam</div>
                  </div>
                  <ChevronRight size={18} className={styles.settingChevron} />
                </div>
              </div>
            </section>

            {/* Data */}
            <section className={styles.section}>
              <div className={styles.sectionHeader}>
                <h2 className={styles.sectionTitle}>Dữ liệu &amp; Quyền riêng tư</h2>
              </div>
              <div className={styles.sectionBody}>
                <div className={styles.settingRow}>
                  <div className={`${styles.settingIcon} ${styles.settingIconRed}`}>
                    <Trash2 size={20} />
                  </div>
                  <div className={styles.settingContent}>
                    <div className={styles.settingTitle}>Xóa dữ liệu cá nhân</div>
                    <div className={styles.settingDesc}>Yêu cầu xóa toàn bộ dữ liệu của bạn</div>
                  </div>
                  <ChevronRight size={18} className={styles.settingChevron} />
                </div>

                <div className={styles.settingRow}>
                  <div className={`${styles.settingIcon} ${styles.settingIconGray}`}>
                    <Info size={20} />
                  </div>
                  <div className={styles.settingContent}>
                    <div className={styles.settingTitle}>Chính sách quyền riêng tư</div>
                    <div className={styles.settingDesc}>Xem cách chúng tôi bảo vệ dữ liệu</div>
                  </div>
                  <ChevronRight size={18} className={styles.settingChevron} />
                </div>
              </div>
            </section>

            {/* Version */}
            <div className={styles.versionBadge}>
              <span>Pet Care</span>
              <span>·</span>
              <span>Phiên bản 2.0.0</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
