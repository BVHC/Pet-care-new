import { Link } from 'react-router-dom'
import styles from './CommonPageHero.module.css'

/* ================================================================
   Types.
   ================================================================ */

export interface CommonPageHeroAction {
  label: string
  href: string
  variant?: 'primary' | 'ghost'
}

export interface CommonPageHeroProps {
  eyebrow?: string
  title: string
  subtitle?: string
  /** Background image. Defaults to a generic pet photo. */
  backgroundImage?: string
  /** Breadcrumb path. Last item is rendered active (no link). */
  breadcrumbs?: { label: string; href?: string }[]
  actions?: CommonPageHeroAction[]
}

/* ================================================================
   Main.
   ================================================================ */

export function CommonPageHero({
  eyebrow,
  title,
  subtitle,
  backgroundImage = '/imgs/hero-dog-clean.png',
  breadcrumbs,
  actions,
}: CommonPageHeroProps) {
  return (
    <section className={styles.slab}>
      <img src={backgroundImage} alt="" className={styles.slabBg} aria-hidden loading="eager" />
      <div className={styles.slabOverlay} aria-hidden />
      <div className={styles.slabNoise} aria-hidden />

      <div className={`${styles.slabContent} mx-auto max-w-[1280px] px-5 sm:px-8`}>
        {breadcrumbs && breadcrumbs.length > 0 && (
          <nav aria-label="Đường dẫn" className="mb-5 text-[12.5px] text-white/50">
            <Link to="/" className="hover:text-white hover:underline underline-offset-4 transition-colors">
              Trang chủ
            </Link>
            {breadcrumbs.map((bc, i) => (
              <span key={`${bc.label}-${i}`}>
                <span className="mx-1.5">/</span>
                {bc.href ? (
                  <Link to={bc.href} className="hover:text-white hover:underline underline-offset-4 transition-colors">
                    {bc.label}
                  </Link>
                ) : (
                  <span className="font-semibold text-white/85">{bc.label}</span>
                )}
              </span>
            ))}
          </nav>
        )}

        {eyebrow && <div className={styles.eyebrow}>{eyebrow}</div>}

        <h1 className={styles.title}>{title}</h1>

        {subtitle && <p className={styles.subtitle}>{subtitle}</p>}

        {actions && actions.length > 0 && (
          <div className={styles.actions}>
            {actions.map((a) => (
              <Link
                key={a.label}
                to={a.href}
                className={a.variant === 'ghost' ? styles.btnGhost : styles.btnPrimary}
              >
                {a.label}
              </Link>
            ))}
          </div>
        )}
      </div>
    </section>
  )
}
