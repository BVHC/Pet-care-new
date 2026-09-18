import { Link, useNavigate } from 'react-router-dom'
import {
  Bell,
  CalendarClock,
  ChevronRight,
  CreditCard,
  Gift,
  Heart,
  HelpCircle,
  LogOut,
  Package,
  PawPrint,
  Settings,
  Shield,
  Sparkles,
  Star,
  User,
} from 'lucide-react'
import { toast } from 'sonner'
import { useAuthStore } from '../../shared/stores/auth.store'
import styles from './AccountSidebar.module.css'

/* ================================================================
   Nav item.
   ================================================================ */

function NavItem({
  icon: Icon,
  label,
  active,
  badge,
  href,
  onClick,
  danger,
}: {
  icon: React.ElementType
  label: string
  active?: boolean
  badge?: number
  href?: string
  onClick?: () => void
  danger?: boolean
}) {
  const cls = [
    styles.navItem,
    active && styles.navItemActive,
    danger && styles.navDanger,
  ]
    .filter(Boolean)
    .join(' ')

  // Ưu tiên onClick (parent dùng state để switch section), fallback href (route riêng).
  if (onClick) {
    return (
      <button type="button" onClick={onClick} className={cls}>
        <Icon size={17} className={styles.navIcon} />
        <span className={styles.navLabel}>{label}</span>
        {badge !== undefined && badge > 0 && (
          <span className={styles.navBadge}>{badge}</span>
        )}
        <ChevronRight size={15} className={styles.navChevron} />
      </button>
    )
  }

  if (href) {
    return (
      <Link to={href} className={cls}>
        <Icon size={17} className={styles.navIcon} />
        <span className={styles.navLabel}>{label}</span>
        {badge !== undefined && badge > 0 && (
          <span className={styles.navBadge}>{badge}</span>
        )}
        <ChevronRight size={15} className={styles.navChevron} />
      </Link>
    )
  }

  return (
    <button type="button" className={cls}>
      <Icon size={17} className={styles.navIcon} />
      <span className={styles.navLabel}>{label}</span>
      {badge !== undefined && badge > 0 && (
        <span className={styles.navBadge}>{badge}</span>
      )}
      <ChevronRight size={15} className={styles.navChevron} />
    </button>
  )
}

/* ================================================================
   Props.
   ================================================================ */

export interface AccountSidebarProps {
  /** Tab đang active. */
  active?: 'profile' | 'pets' | 'orders' | 'recommend' | 'favorites' | 'notifications' | 'payment' | 'security' | 'settings' | 'help' | 'membership' | 'vouchers' | 'caregivers' | 'appointments' | 'packages'
  /** Số thú cưng (hiện badge). */
  petCount?: number
  /** User display name. */
  userName?: string
  /** User email. */
  userEmail?: string
}

/* ================================================================
   Main component.
   ================================================================ */

export function AccountSidebar({
  active = 'profile',
  petCount = 0,
  userName,
  userEmail,
}: AccountSidebarProps) {
  const navigate = useNavigate()
  const logout = useAuthStore((s) => s.logout)
  const authName = useAuthStore((s) => s.user?.name) ?? 'Khách'
  const authEmail = useAuthStore((s) => s.user?.email) ?? ''

  const displayName = userName ?? authName
  const displayEmail = userEmail ?? authEmail

  const initials = displayName
    .split(' ')
    .slice(-2)
    .map((w) => w[0] ?? '')
    .join('')
    .toUpperCase()

  const handleLogout = async () => {
    await logout()
    toast.success('Đã đăng xuất. Hẹn gặp lại!')
    navigate('/', { replace: true })
  }

  return (
    <aside className={styles.sidebar}>
      {/* User card */}
      <div className={styles.userCard}>
        <div className={styles.userAvatar}>{initials}</div>
        <div>
          <div className={styles.userName}>{displayName}</div>
          {displayEmail && <div className={styles.userEmail}>{displayEmail}</div>}
        </div>
      </div>

      {/* Nav */}
      <nav className={styles.navList} aria-label="Tài khoản">
        <NavItem
          icon={User}
          label="Hồ sơ"
          active={active === 'profile'}
          href="/account"
        />
        <NavItem
          icon={PawPrint}
          label="Thú cưng"
          badge={petCount}
          active={active === 'pets'}
          href="/pets"
        />
        <NavItem
          icon={Package}
          label="Đơn hàng"
          active={active === 'orders'}
          href="/orders"
        />
        <NavItem
          icon={CalendarClock}
          label="Lịch hẹn"
          active={active === 'appointments'}
          href="/appointments"
        />
        <NavItem
          icon={Gift}
          label="Gói dịch vụ"
          active={active === 'packages'}
          href="/packages"
        />
        <NavItem
          icon={Sparkles}
          label="Gợi ý sản phẩm"
          active={active === 'recommend'}
          href="/recommend"
        />

        <div className={styles.navDivider} />

        <NavItem
          icon={Heart}
          label="Yêu thích"
          active={active === 'favorites'}
          href="/favorites"
        />
        <NavItem
          icon={Bell}
          label="Thông báo"
          active={active === 'notifications'}
          href="/notifications"
        />
        <NavItem
          icon={CreditCard}
          label="Thanh toán"
          active={active === 'payment'}
          href="/payment"
        />
        <NavItem
          icon={Shield}
          label="Bảo mật"
          active={active === 'security'}
          href="/security"
        />
        <NavItem
          icon={Sparkles}
          label="Hội viên"
          active={active === 'membership'}
          href="/membership"
        />
        <NavItem
          icon={Star}
          label="Voucher"
          active={active === 'vouchers'}
          href="/vouchers"
        />
        <NavItem
          icon={Heart}
          label="Người chăm sóc"
          active={active === 'caregivers'}
          href="/caregivers"
        />

        <div className={styles.navDivider} />

        <NavItem
          icon={Settings}
          label="Cài đặt"
          href="/settings"
        />
        <NavItem
          icon={HelpCircle}
          label="Trợ giúp"
          href="/help"
        />

        <div className={styles.navDivider} />

        <NavItem
          icon={LogOut}
          label="Đăng xuất"
          onClick={handleLogout}
          danger
        />
      </nav>
    </aside>
  )
}
