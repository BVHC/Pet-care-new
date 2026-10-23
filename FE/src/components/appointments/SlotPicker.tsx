/**
 * Chọn ngày → khung giờ trống (appointment-v1 #1), dùng chung cho đặt lịch, đặt hộ và đổi giờ.
 * Khung FULL không chọn được và ghi "Hết chỗ" (BR-LH-03); mảng rỗng = ngày nghỉ / ngoài giờ / ngoài khoảng đặt được (A1).
 * Khung đang chọn mà thành hết chỗ sau khi tải lại (vd sau lỗi BR-LH-03) thì tự bỏ chọn.
 */
import { useEffect } from 'react';
import { DatePicker } from '@/components/customer/DatePicker';
import { cn } from '@/lib/utils';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { useAvailableSlots } from '@/shared/hooks/useAppointments';
import styles from './SlotPicker.module.css';

export interface SlotPickerProps {
  branchId: number | null;
  serviceId: number | null;
  date: string | null;
  onDateChange: (date: string | null) => void;
  value: string | null;
  onChange: (slotStart: string | null) => void;
  minDate: string;
  maxDate?: string;
  /** Đổi giờ: khung hiện tại của lịch, hiện "Giờ hiện tại" và không chọn lại. */
  current?: { slotDate: string; slotStart: string } | null;
  /** Ghi chú dưới ô ngày (vd khoảng đặt được của khách). */
  hint?: string;
}

export function SlotPicker({ branchId, serviceId, date, onDateChange, value, onChange, minDate, maxDate, current, hint }: SlotPickerProps) {
  const slots = useAvailableSlots(branchId, serviceId, date);
  const ready = branchId != null && serviceId != null;

  useEffect(() => {
    if (value && slots.data && !slots.data.some((s) => s.slotStart === value && s.status === 'AVAILABLE')) onChange(null);
  }, [slots.data, value, onChange]);

  return (
    <div className={styles.root}>
      <div className={styles.field}>
        <span className={styles.label}>Ngày hẹn</span>
        <DatePicker
          value={date}
          onChange={(iso) => {
            onDateChange(iso || null);
            onChange(null);
          }}
          minDate={minDate}
          maxDate={maxDate}
          ariaLabel="Ngày hẹn"
        />
        {hint && <p className={styles.hint}>{hint}</p>}
      </div>

      <div className={styles.field}>
        <span className={styles.label}>Giờ hẹn</span>
        {!ready && <p className={styles.muted}>Chọn chi nhánh và dịch vụ trước.</p>}
        {ready && !date && <p className={styles.muted}>Chọn ngày để xem khung giờ còn trống.</p>}
        {ready && date && slots.isPending && <p className={styles.muted}>Đang tải khung giờ…</p>}
        {slots.isError && (
          <p role="alert" className={styles.error}>
            {plainMessage(toApiError(slots.error))}
          </p>
        )}
        {slots.data && slots.data.length === 0 && <p className={styles.empty}>Chi nhánh không nhận lịch ngày này</p>}
        {slots.data && slots.data.length > 0 && (
          <div role="group" aria-label="Khung giờ" className={styles.grid}>
            {slots.data.map((slot) => {
              const full = slot.status === 'FULL';
              const isCurrent = current?.slotDate === date && current?.slotStart === slot.slotStart;
              const selected = value === slot.slotStart;
              return (
                <button
                  key={slot.slotStart}
                  type="button"
                  disabled={full || isCurrent}
                  aria-pressed={selected}
                  onClick={() => onChange(slot.slotStart)}
                  className={cn(styles.slot, selected && styles.slotActive, (full || isCurrent) && styles.slotDisabled)}
                >
                  <span className={styles.time}>{slot.slotStart}</span>
                  {full && !isCurrent && <span className={styles.tag}>Hết chỗ</span>}
                  {isCurrent && <span className={styles.tag}>Giờ hiện tại</span>}
                </button>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
