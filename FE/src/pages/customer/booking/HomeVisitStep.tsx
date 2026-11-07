// Bước "khám tại nhà" của /booking (UC43; homevisit-v1 #1, fees-v1 #3): phí của chi nhánh đã chọn, địa chỉ trong sổ địa chỉ của khách và số điện thoại liên hệ.
// Khách chỉ dùng địa chỉ trong sổ của mình (BR-TK-18); chưa có thì dẫn tới sổ địa chỉ để thêm.
import { Link } from 'react-router-dom';
import { Home, MapPin } from 'lucide-react';
import { useAddresses } from '@/shared/hooks/useProfile';
import type { Address } from '@/shared/types/profile';
import type { HomeVisitFee } from '@/shared/types/fees';
import { HOME_VISIT_LIMITS } from '@/shared/types/homevisit';
import styles from '../BookingPage.module.css';

const vnd = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 });
const addressText = (a: Address) => [a.addressLine, a.ward, a.province].filter(Boolean).join(', ');

interface Props {
  fee: HomeVisitFee | undefined;
  addressId: number | null;
  phone: string;
  onAddress: (address: Address) => void;
  onPhone: (phone: string) => void;
}

export function HomeVisitStep({ fee, addressId, phone, onAddress, onPhone }: Props) {
  const addresses = useAddresses();
  const list = addresses.data ?? [];
  return (
    <div className="space-y-3">
      {fee && !fee.enabled && (
        <p role="alert" className={styles.formError}>
          Chi nhánh này chưa nhận khám tại nhà. Chọn chi nhánh khác hoặc đặt khám tại chi nhánh.
        </p>
      )}
      {fee?.enabled && (
        <p className={styles.hint}>
          <Home size={13} className="mr-1 inline" aria-hidden />
          Phí khám tại nhà <strong>{vnd.format(fee.fee)}</strong>, chốt lúc đặt lịch và thu tại chi nhánh sau buổi khám.
        </p>
      )}
      {addresses.isPending && <p className={styles.muted}>Đang tải sổ địa chỉ…</p>}
      {addresses.isSuccess && list.length === 0 && (
        <p className={styles.muted}>
          Bạn chưa có địa chỉ nào trong sổ.{' '}
          <Link to="/account/addresses" className="font-bold text-accent underline">
            Thêm địa chỉ
          </Link>
        </p>
      )}
      <div className={styles.optionGrid} role="group" aria-label="Chọn địa chỉ khám tại nhà">
        {list.map((a) => (
          <button
            key={a.addressId}
            type="button"
            aria-pressed={addressId === a.addressId}
            onClick={() => onAddress(a)}
            className={`${styles.optionCard} ${addressId === a.addressId ? styles.optionCardActive : ''}`}
          >
            <span className={styles.optionTitle}>{a.receiverName}</span>
            <span className={styles.optionMeta}>
              <MapPin size={13} aria-hidden /> {addressText(a)}
            </span>
            <span className={styles.optionMeta}>Điện thoại {a.receiverPhone}</span>
          </button>
        ))}
      </div>
      <div>
        <label htmlFor="home-visit-phone" className="mb-1.5 block text-sm font-bold text-(--color-text-primary)">
          Số điện thoại liên hệ
        </label>
        <input
          id="home-visit-phone"
          type="tel"
          inputMode="tel"
          maxLength={HOME_VISIT_LIMITS.contactPhone}
          value={phone}
          onChange={(e) => onPhone(e.target.value)}
          className={styles.fieldInput}
          placeholder="Số bác sĩ gọi khi đến nơi"
        />
      </div>
    </div>
  );
}
