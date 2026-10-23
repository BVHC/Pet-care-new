import { useState } from 'react'
import { Link } from 'react-router-dom'
import { AlertTriangle, CalendarClock, ShieldCheck, Stethoscope, Syringe, type LucideIcon } from 'lucide-react'
import { toast } from 'sonner'
import { AccountSidebar } from '@/components/customer/AccountSidebar'
import { plainMessage, toApiError } from '@/shared/api/api-error'
import { useNotificationSettings, useSaveNotificationSettings } from '@/shared/hooks/useNotifications'
import {
  CHANNELS,
  CHANNEL_LABELS,
  REMINDER_KINDS,
  REMINDER_LABELS,
  type ChannelToggle,
  type NotificationSettings,
  type ReminderKind,
} from '@/shared/types/care'
import styles from './SettingsPage.module.css'

/* ================================================================
   T34 — Cài đặt nhận thông báo của khách (UC88, chỉ A02; care-v1 #9, #10).
   Nội dung theo care-v1 A1 (bật / tắt 3 loại nhắc × 2 kênh; email bảo mật luôn gửi), còn chờ PO chốt (Q1).
   Bỏ các mục của bản cũ không có use case ở v16: thông báo đẩy / SMS, chế độ tối, ngôn ngữ, đơn vị đo, múi giờ,
   định dạng ngày, xóa dữ liệu cá nhân, phiên bản ứng dụng.
   ================================================================ */

const ICONS: Record<ReminderKind, LucideIcon> = {
  appointmentReminder: CalendarClock,
  vaccineReminder: Syringe,
  followUpReminder: Stethoscope,
}

const same = (a: NotificationSettings, b: NotificationSettings) =>
  REMINDER_KINDS.every((k) => CHANNELS.every((ch) => a[k][ch] === b[k][ch]))

export function SettingsPage() {
  const settings = useNotificationSettings()
  const save = useSaveNotificationSettings()
  /** Bản đang sửa; null = đang hiện đúng dữ liệu đã lưu. */
  const [draft, setDraft] = useState<NotificationSettings | null>(null)
  const saved = settings.data
  const current = draft ?? saved
  const dirty = !!draft && !!saved && !same(draft, saved)

  const toggle = (kind: ReminderKind, channel: keyof ChannelToggle) => {
    if (!current) return
    setDraft({ ...current, [kind]: { ...current[kind], [channel]: !current[kind][channel] } })
  }

  const submit = () => {
    if (!draft) return
    save.mutate(draft, {
      onSuccess: () => {
        setDraft(null)
        toast.success('Đã lưu cài đặt nhận thông báo')
      },
      onError: (error) => toast.error(plainMessage(toApiError(error))),
    })
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
            <span className="font-semibold text-white/85">Cài đặt</span>
          </nav>
          <h1 className="font-friendly font-extrabold text-[clamp(22px,3vw,36px)] leading-[1.05] text-white">
            Cài đặt
          </h1>
        </div>
      </section>

      {/* Content */}
      <div className="mx-auto max-w-[1280px] px-5 pt-8 sm:px-8">
        <div className={styles.page}>
          <AccountSidebar active="settings" />

          <div className={styles.main}>
            <section className={styles.section} aria-labelledby="notif-settings-title">
              <div className={styles.sectionHeader}>
                <h2 id="notif-settings-title" className={styles.sectionTitle}>
                  Nhận thông báo nhắc
                </h2>
                <p className={styles.sectionDesc}>Chọn kênh nhận từng loại nhắc. Thay đổi có hiệu lực từ lần nhắc tiếp theo.</p>
              </div>

              {settings.isPending ? (
                <div role="status" aria-label="Đang tải" className={styles.sectionBody}>
                  {REMINDER_KINDS.map((k) => (
                    <div key={k} className={styles.skeleton} />
                  ))}
                </div>
              ) : settings.isError || !current ? (
                <div role="alert" className={styles.errorBox}>
                  <AlertTriangle size={20} aria-hidden />
                  <span>Không tải được cài đặt. {settings.error ? plainMessage(toApiError(settings.error)) : ''}</span>
                  <button type="button" className={styles.secondaryBtn} onClick={() => settings.refetch()}>
                    Thử lại
                  </button>
                </div>
              ) : (
                <div className={styles.sectionBody}>
                  <div className={styles.gridHead} aria-hidden>
                    <span />
                    {CHANNELS.map((ch) => (
                      <span key={ch}>{CHANNEL_LABELS[ch]}</span>
                    ))}
                  </div>
                  <ul className={styles.rows}>
                    {REMINDER_KINDS.map((kind) => {
                      const Icon = ICONS[kind]
                      const { title, description } = REMINDER_LABELS[kind]
                      const off = CHANNELS.every((ch) => !current[kind][ch])
                      return (
                        <li key={kind} className={styles.gridRow}>
                          <div className={styles.rowLabel}>
                            <span className={styles.settingIcon}>
                              <Icon size={20} aria-hidden />
                            </span>
                            <span className={styles.settingContent}>
                              <span className={styles.settingTitle}>{title}</span>
                              <span className={styles.settingDesc}>{description}</span>
                              {off && <span className={styles.offHint}>Đã tắt cả hai kênh — bạn sẽ không nhận nhắc này.</span>}
                            </span>
                          </div>
                          {CHANNELS.map((ch) => {
                            const on = current[kind][ch]
                            return (
                              <div key={ch} className={styles.cell}>
                                <span className={styles.cellLabel} aria-hidden>
                                  {CHANNEL_LABELS[ch]}
                                </span>
                                <button
                                  type="button"
                                  role="switch"
                                  aria-checked={on}
                                  aria-label={`${title} ${ch === 'email' ? 'qua email' : 'trong ứng dụng'}`}
                                  onClick={() => toggle(kind, ch)}
                                  disabled={save.isPending}
                                  className={`${styles.toggle} ${on ? styles.toggleActive : ''}`}
                                >
                                  <span className={styles.toggleKnob} />
                                </button>
                              </div>
                            )
                          })}
                        </li>
                      )
                    })}
                  </ul>

                  <div className={styles.actions}>
                    {dirty && (
                      <span role="status" className={styles.dirtyNote}>
                        Có thay đổi chưa lưu
                      </span>
                    )}
                    <button type="button" className={styles.secondaryBtn} onClick={() => setDraft(null)} disabled={!dirty || save.isPending}>
                      Hủy thay đổi
                    </button>
                    <button type="button" className={styles.primaryBtn} onClick={submit} disabled={!dirty || save.isPending}>
                      {save.isPending ? 'Đang lưu…' : 'Lưu thay đổi'}
                    </button>
                  </div>
                </div>
              )}
            </section>

            {/* care-v1 A1: email bảo mật không nằm trong cài đặt. */}
            <aside className={styles.note}>
              <ShieldCheck size={20} aria-hidden className={styles.noteIcon} />
              <p>
                <strong>Email bảo mật luôn được gửi</strong> — mã OTP, thông báo đổi mật khẩu và đổi email — để bảo vệ tài khoản
                của bạn, kể cả khi bạn tắt mọi loại nhắc ở trên. Đổi mật khẩu tại{' '}
                <Link to="/security" className={styles.noteLink}>
                  Bảo mật
                </Link>
                .
              </p>
            </aside>
          </div>
        </div>
      </div>
    </div>
  )
}
