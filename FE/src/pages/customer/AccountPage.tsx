import { useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { AccountSidebar } from '@/components/customer/AccountSidebar'
import styles from './AccountPage.module.css'

/* ================================================================
   Main page.
   ================================================================ */

export function AccountPage() {
  const [profile, setProfile] = useState({
    name: 'Nguyễn Văn A',
    phone: '0901 234 567',
    email: 'nguyenvana@email.com',
    address: '123 Đường ABC, Phường XYZ, Quận Cầu Giấy, Hà Nội',
  })

  const initials = profile.name
    .split(' ')
    .slice(-2)
    .map((w) => w[0] ?? '')
    .join('')
    .toUpperCase()

  const handleSaveProfile = (e: React.FormEvent) => {
    e.preventDefault()
    toast.success('Cập nhật thông tin thành công!')
  }

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
          <h1 className="font-bayon text-[clamp(22px,3vw,36px)] leading-[1.02] font-normal text-white">
            Tài khoản của tôi
          </h1>
        </div>
      </section>

      {/* Content */}
      <div className="mx-auto max-w-[1280px] px-5 pt-8 sm:px-8">
        <div className={styles.page}>
          {/* Sidebar */}
          <AccountSidebar active="profile" />

          {/* Main — profile only */}
          <div className={styles.main}>
            <section className={styles.section} id="profile">
              <div className={styles.sectionHeader}>
                <h2 className={styles.sectionTitle}>Thông tin cá nhân</h2>
              </div>

              <form onSubmit={handleSaveProfile} className={styles.profileBody} noValidate>
                <div className={styles.avatarRow}>
                  <div className={styles.avatarCircle}>{initials}</div>
                  <div>
                    <button type="button" className={styles.avatarBtn}>
                      Đổi ảnh đại diện
                    </button>
                    <p className={styles.avatarHint}>JPG, PNG · tối đa 2 MB</p>
                  </div>
                </div>

                <div className={styles.formGrid}>
                  <div className={styles.field}>
                    <label htmlFor="name" className={styles.fieldLabel}>Họ tên</label>
                    <input
                      id="name"
                      type="text"
                      value={profile.name}
                      onChange={(e) => setProfile((p) => ({ ...p, name: e.target.value }))}
                      className={styles.fieldInput}
                    />
                  </div>

                  <div className={styles.field}>
                    <label htmlFor="phone" className={styles.fieldLabel}>Số điện thoại</label>
                    <input
                      id="phone"
                      type="tel"
                      value={profile.phone}
                      onChange={(e) => setProfile((p) => ({ ...p, phone: e.target.value }))}
                      className={styles.fieldInput}
                    />
                  </div>

                  <div className={styles.field} style={{ gridColumn: '1 / -1' }}>
                    <label htmlFor="email" className={styles.fieldLabel}>Email</label>
                    <input
                      id="email"
                      type="email"
                      value={profile.email}
                      onChange={(e) => setProfile((p) => ({ ...p, email: e.target.value }))}
                      className={styles.fieldInput}
                    />
                  </div>

                  <div className={styles.field} style={{ gridColumn: '1 / -1' }}>
                    <label htmlFor="address" className={styles.fieldLabel}>Địa chỉ</label>
                    <input
                      id="address"
                      type="text"
                      value={profile.address}
                      onChange={(e) => setProfile((p) => ({ ...p, address: e.target.value }))}
                      className={styles.fieldInput}
                    />
                  </div>
                </div>

                <div className={styles.formActions}>
                  <button type="submit" className={styles.saveBtn}>
                    Lưu thay đổi
                  </button>
                </div>
              </form>
            </section>
          </div>
        </div>
      </div>
    </div>
  )
}
