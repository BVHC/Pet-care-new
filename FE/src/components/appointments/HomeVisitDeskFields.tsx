// Phần "khám tại nhà" của hộp thoại đặt hộ (UC43; homevisit-v1 #1, fees-v1 #3). Lễ tân không đọc được sổ địa chỉ của khách (hồ sơ tại quầy không có sổ; hợp đồng không có endpoint),
// nên nhập địa chỉ tay (≤ 300 ký tự) và số liên hệ; phí của chi nhánh hiện để báo khách trước khi đặt.
import { Field, FormError, selectClass } from '@/components/admin/form-ui';
import { formatCurrency } from '@/lib/utils';
import type { HomeVisitFee } from '@/shared/types/fees';
import { HOME_VISIT_LIMITS } from '@/shared/types/homevisit';

const inputClass = `${selectClass} placeholder:text-(--text-tertiary)`;

export function HomeVisitDeskFields({
  fee,
  address,
  phone,
  onAddress,
  onPhone,
}: {
  fee: HomeVisitFee | undefined;
  address: string;
  phone: string;
  onAddress: (value: string) => void;
  onPhone: (value: string) => void;
}) {
  return (
    <div className="space-y-3 rounded-lg border border-(--color-border-default) p-3">
      {fee && !fee.enabled && <FormError message="Chi nhánh này chưa bật khám tại nhà nên chưa đặt được." />}
      {fee?.enabled && (
        <p className="text-sm text-(--text-secondary)">
          Phí khám tại nhà <strong>{formatCurrency(fee.fee)}</strong>, chốt lúc đặt và thu tại chi nhánh sau buổi khám.
        </p>
      )}
      <Field label="Địa chỉ khám tại nhà" hint={`${address.length}/${HOME_VISIT_LIMITS.address}`}>
        {(id) => (
          <textarea
            id={id}
            rows={2}
            maxLength={HOME_VISIT_LIMITS.address}
            value={address}
            onChange={(e) => onAddress(e.target.value)}
            className="w-full rounded-lg border border-(--color-border-default) bg-white px-3 py-2 text-sm"
            placeholder="Số nhà, đường, phường, tỉnh"
          />
        )}
      </Field>
      <Field label="Số điện thoại liên hệ">
        {(id) => <input id={id} type="tel" inputMode="tel" maxLength={HOME_VISIT_LIMITS.contactPhone} value={phone} onChange={(e) => onPhone(e.target.value)} className={inputClass} />}
      </Field>
    </div>
  );
}
