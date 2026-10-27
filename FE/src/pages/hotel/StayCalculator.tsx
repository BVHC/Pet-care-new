/**
 * Máy tính tiền lưu trú: loại chuồng + ngày nhận / ngày trả (giờ Việt Nam) → "N đêm × giá đêm = tạm tính".
 * Giá lấy từ loại chuồng công khai. Cùng khoảng ngày với lúc đặt chỗ (từ ngày mai tới +60 ngày, 1 đến 30 đêm) để số tính ra
 * không khác điều đặt chỗ chấp nhận. Trang công khai nên KHÔNG hỏi chỗ trống (endpoint đó cần đăng nhập); xem chỗ trống ở bước đặt chỗ.
 */
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { BedDouble } from 'lucide-react';
import { todayVn } from '@/components/appointments/format';
import { DatePicker } from '@/components/customer/DatePicker';
import { BOARDING_CFG } from '@/shared/types/boarding';
import { nightsBetween, shiftDate, stayAmount } from '@/shared/utils/boarding';
import { nightsLabel, vnd } from '@/pages/customer/boarding/format';
import { bookingDateError, checkInWindow, checkOutWindow, type KennelOption } from '@/pages/customer/boarding/rules';
import styles from './HotelPage.module.css';

export interface StayCalculatorProps {
  types: readonly KennelOption[];
  selectedId: number | null;
  onSelect: (serviceId: number | null) => void;
  loading: boolean;
}

export function StayCalculator({ types, selectedId, onSelect, loading }: StayCalculatorProps) {
  const [checkIn, setCheckIn] = useState<string | null>(null);
  const [checkOut, setCheckOut] = useState<string | null>(null);

  const today = todayVn();
  const inRange = checkInWindow(today);
  const outRange = checkIn ? checkOutWindow(checkIn) : { min: shiftDate(inRange.min, BOARDING_CFG.minNights), max: undefined };
  const dateError = bookingDateError(checkIn, checkOut, today);
  const nights = checkIn && checkOut && !dateError ? nightsBetween(checkIn, checkOut) : 0;
  const type = types.find((t) => t.serviceId === selectedId) ?? null;

  return (
    <section aria-label="Tính tiền lưu trú" className={styles.calc}>
      <div className={styles.calcFields}>
        <div className={styles.field}>
          <label htmlFor="hotel-kennel-type" className={styles.fieldLabel}>
            Loại chuồng
          </label>
          <select
            id="hotel-kennel-type"
            className={styles.select}
            value={selectedId ?? ''}
            disabled={types.length === 0}
            onChange={(e) => onSelect(e.target.value ? Number(e.target.value) : null)}
          >
            <option value="">{loading ? 'Đang tải…' : 'Chọn loại chuồng'}</option>
            {types.map((t) => (
              <option key={t.serviceId} value={t.serviceId}>
                {t.name} ({vnd.format(t.price)}/đêm)
              </option>
            ))}
          </select>
        </div>
        <div className={styles.field}>
          <span className={styles.fieldLabel}>Ngày nhận</span>
          <DatePicker
            id="hotel-check-in"
            value={checkIn}
            onChange={(iso) => setCheckIn(iso || null)}
            minDate={inRange.min}
            maxDate={inRange.max}
            ariaLabel="Ngày nhận"
            placeholder="Chọn ngày nhận"
          />
        </div>
        <div className={styles.field}>
          <span className={styles.fieldLabel}>Ngày trả</span>
          <DatePicker
            id="hotel-check-out"
            value={checkOut}
            onChange={(iso) => setCheckOut(iso || null)}
            minDate={outRange.min}
            maxDate={outRange.max}
            ariaLabel="Ngày trả"
            placeholder="Chọn ngày trả"
          />
        </div>
      </div>

      <div className={styles.calcFoot}>
        <div role="status" className={styles.result}>
          {dateError ? (
            <p className={styles.resultError}>{dateError}</p>
          ) : nights > 0 && type ? (
            <>
              <p className={styles.resultCalc}>
                {nightsLabel(nights)} × {vnd.format(type.price)}
              </p>
              <p className={styles.resultTotal}>{vnd.format(stayAmount(nights, type.price))}</p>
              <p>
                Tạm tính, chưa gồm khám hay tắm. Tiền chốt khi trả thú; giờ trả là {BOARDING_CFG.checkoutTime}, trả sau giờ này tính thêm 1 đêm.
              </p>
            </>
          ) : (
            <p>{types.length === 0 && !loading ? 'Chưa có loại chuồng để tính tiền.' : 'Chọn loại chuồng và ngày để xem tiền tạm tính.'}</p>
          )}
        </div>
        <Link to="/boarding-bookings/new" className={styles.cta}>
          <BedDouble size={16} aria-hidden /> Đặt chỗ lưu trú
        </Link>
      </div>
    </section>
  );
}
