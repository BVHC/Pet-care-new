import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { Calendar, ChevronLeft, ChevronRight } from 'lucide-react'
import styles from './DatePicker.module.css'

/* ================================================================
   Helpers.
   ================================================================ */

const MONTH_NAMES = [
  'tháng 1', 'tháng 2', 'tháng 3', 'tháng 4', 'tháng 5', 'tháng 6',
  'tháng 7', 'tháng 8', 'tháng 9', 'tháng 10', 'tháng 11', 'tháng 12',
]

const DOW_SHORT = ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su']

function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n)
}

function toIso(d: Date): string {
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
}

function parseIso(iso: string | null): Date | null {
  if (!iso) return null
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso)
  if (!m) return null
  const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]))
  return Number.isNaN(d.getTime()) ? null : d
}

function sameDay(a: Date, b: Date): boolean {
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  )
}

function stripTime(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate())
}

function startOfDay(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate())
}

const DISPLAY_FMT = new Intl.DateTimeFormat('vi-VN', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
})

function formatDisplay(iso: string | null): string {
  const d = parseIso(iso)
  return d ? DISPLAY_FMT.format(d) : ''
}

/* ================================================================
   Component.
   ================================================================ */

export interface DatePickerProps {
  value: string | null
  onChange: (iso: string) => void
  /** ISO date — ngày sớm nhất có thể chọn. */
  minDate?: string | null
  /** ISO date — ngày muộn nhất có thể chọn. */
  maxDate?: string | null
  placeholder?: string
  ariaLabel?: string
  id?: string
}

/**
 * Compact date picker. Click vào input → mở lịch dưới.
 * Value là ISO `yyyy-mm-dd` (cùng dạng với `<input type="date">`).
 */
export function DatePicker({
  value,
  onChange,
  minDate,
  maxDate,
  placeholder = 'dd/mm/yyyy',
  ariaLabel,
  id,
}: DatePickerProps) {
  const generatedId = useId()
  const inputId = id ?? generatedId
  const wrapRef = useRef<HTMLDivElement>(null)

  // Lịch đang hiện (mặc định: tháng của value hoặc hôm nay).
  const [viewMonth, setViewMonth] = useState<Date>(() => {
    const v = parseIso(value)
    if (v) return new Date(v.getFullYear(), v.getMonth(), 1)
    const t = new Date()
    return new Date(t.getFullYear(), t.getMonth(), 1)
  })

  const [open, setOpen] = useState(false)

  const today = useMemo(() => stripTime(new Date()), [])
  const minD = useMemo(() => (minDate ? parseIso(minDate) : null), [minDate])
  const maxD = useMemo(() => (maxDate ? parseIso(maxDate) : null), [maxDate])

  // Khi value đổi từ ngoài, đồng bộ lịch về tháng đó.
  useEffect(() => {
    const v = parseIso(value)
    if (v) setViewMonth(new Date(v.getFullYear(), v.getMonth(), 1))
  }, [value])

  // Click ngoài / Escape để đóng.
  useEffect(() => {
    if (!open) return
    function onDoc(e: MouseEvent) {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDoc)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  // Lưới ngày trong tháng `viewMonth` (Mon → Sun).
  const days = useMemo(() => {
    const first = new Date(viewMonth.getFullYear(), viewMonth.getMonth(), 1)
    // Tính offset Mon-first: Mon=0, Sun=6.
    const dow = (first.getDay() + 6) % 7
    const grid: { date: Date; inMonth: boolean }[] = []
    // Leading days từ tháng trước.
    for (let i = dow; i > 0; i--) {
      grid.push({
        date: new Date(first.getFullYear(), first.getMonth(), 1 - i),
        inMonth: false,
      })
    }
    // Ngày trong tháng.
    const lastDay = new Date(viewMonth.getFullYear(), viewMonth.getMonth() + 1, 0).getDate()
    for (let i = 1; i <= lastDay; i++) {
      grid.push({
        date: new Date(viewMonth.getFullYear(), viewMonth.getMonth(), i),
        inMonth: true,
      })
    }
    // Trailing để đủ 42 ô (6 hàng × 7).
    const trailing = 42 - grid.length
    for (let i = 1; i <= trailing; i++) {
      grid.push({
        date: new Date(viewMonth.getFullYear(), viewMonth.getMonth() + 1, i),
        inMonth: false,
      })
    }
    return grid
  }, [viewMonth])

  const selected = useMemo(() => (value ? parseIso(value) : null), [value])

  const monthYearLabel = `${MONTH_NAMES[viewMonth.getMonth()]} ${viewMonth.getFullYear()}`

  const canGoPrev = useMemo(() => {
    if (!minD) return true
    const prevMonthLast = new Date(viewMonth.getFullYear(), viewMonth.getMonth(), 0)
    return prevMonthLast >= startOfDay(minD)
  }, [minD, viewMonth])

  const canGoNext = useMemo(() => {
    if (!maxD) return true
    const nextMonthFirst = new Date(viewMonth.getFullYear(), viewMonth.getMonth() + 1, 1)
    return nextMonthFirst <= startOfDay(maxD)
  }, [maxD, viewMonth])

  const handlePick = (d: Date) => {
    onChange(toIso(d))
    setOpen(false)
  }

  return (
    <div ref={wrapRef} className={styles.wrap}>
      <button
        type="button"
        id={inputId}
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={ariaLabel ?? placeholder}
        className={`${styles.field} ${open ? styles.fieldOpen : ''}`}
      >
        <Calendar size={15} className="text-[var(--color-text-secondary)]" />
        <span className={value ? '' : styles.fieldPlaceholder}>
          {value ? formatDisplay(value) : placeholder}
        </span>
      </button>

      {open && (
        <div role="dialog" aria-label="Chọn ngày" className={styles.popover}>
          {/* Month nav */}
          <div className={styles.header}>
            <button
              type="button"
              onClick={() =>
                setViewMonth(new Date(viewMonth.getFullYear(), viewMonth.getMonth() - 1, 1))
              }
              disabled={!canGoPrev}
              className={styles.navBtn}
              aria-label="Tháng trước"
            >
              <ChevronLeft size={16} />
            </button>
            <span className={styles.monthLabel}>{monthYearLabel}</span>
            <button
              type="button"
              onClick={() =>
                setViewMonth(new Date(viewMonth.getFullYear(), viewMonth.getMonth() + 1, 1))
              }
              disabled={!canGoNext}
              className={styles.navBtn}
              aria-label="Tháng sau"
            >
              <ChevronRight size={16} />
            </button>
          </div>

          {/* DOW header */}
          <div className={styles.dowRow}>
            {DOW_SHORT.map((d, i) => (
              <div key={d} className={`${styles.dowCell} ${i === 6 ? styles.sun : ''}`}>
                {d}
              </div>
            ))}
          </div>

          {/* Day grid */}
          <div className={styles.grid}>
            {days.map(({ date, inMonth }, i) => {
              const isToday = sameDay(date, today)
              const isSelected = selected && sameDay(date, selected)
              const dow = (date.getDay() + 6) % 7
              const isSunday = dow === 6
              const tooEarly = minD && startOfDay(date) < startOfDay(minD)
              const tooLate = maxD && startOfDay(date) > startOfDay(maxD)
              const disabled = !!tooEarly || !!tooLate

              const classNames = [
                styles.day,
                !inMonth && styles.dayOutside,
                isToday && !isSelected && styles.dayToday,
                isSelected && styles.daySelected,
                disabled && styles.dayDisabled,
                isSunday && !isSelected && !disabled && styles.daySun,
              ]
                .filter(Boolean)
                .join(' ')

              return (
                <button
                  key={i}
                  type="button"
                  onClick={() => !disabled && handlePick(date)}
                  disabled={disabled}
                  className={classNames}
                  aria-label={DISPLAY_FMT.format(date)}
                >
                  {date.getDate()}
                </button>
              )
            })}
          </div>

          {/* Footer */}
          <div className={styles.footer}>
            <button
              type="button"
              onClick={() => {
                onChange('')
                setOpen(false)
              }}
              className={styles.footerBtn}
            >
              Xoá
            </button>
            <button
              type="button"
              onClick={() => {
                handlePick(today)
              }}
              className={styles.footerBtn}
            >
              Hôm nay
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
