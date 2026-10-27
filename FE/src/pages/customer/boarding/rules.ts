// Luật hiển thị cho khách khi đặt chỗ lưu trú (UC58). Hàm thuần: ngày là `YYYY-MM-DD` giờ Việt Nam, "hôm nay" do nơi gọi truyền vào
// (todayVn), không đọc đồng hồ máy. BE vẫn kiểm lại mọi luật (BR-LT-02, 03, 04); đây chỉ để khỏi gợi ý điều chắc chắn bị từ chối.
import { formatShortDate } from '@/components/appointments/format';
import { BOARDING_CFG, type NightAvailability } from '@/shared/types/boarding';
import type { KennelTypeSpec, PublicService } from '@/shared/types/catalog';
import { nightsBetween, shiftDate } from '@/shared/utils/boarding';

/** Loại chuồng = dịch vụ nhóm BOARDING có thông số loài / cân nặng tối đa (BR-SP-04), bật ở `branchIds`. */
export type KennelOption = PublicService & { kennelType: KennelTypeSpec };

/** Phần của hồ sơ thú cần để lọc loại chuồng. */
export interface PetFit {
  species: string;
  currentWeightKg?: number | null;
}

export const kennelOptions = (services: readonly PublicService[]): KennelOption[] =>
  services.filter((s): s is KennelOption => s.group === 'BOARDING' && !!s.kennelType);

/** BR-LT-02: đúng loài và cân nặng hiện tại không vượt tối đa; thú chưa cân thì cho qua (BE kiểm lại khi nhận). */
export const kennelFitsPet = (kennel: KennelOption, pet: PetFit): boolean =>
  kennel.kennelType.species === pet.species && (pet.currentWeightKg == null || pet.currentWeightKg <= kennel.kennelType.maxWeightKg);

/** Chỉ loại bật tại chi nhánh và phù hợp thú: loại không phù hợp không bao giờ hiện cho khách chọn. */
export const kennelsFor = (options: readonly KennelOption[], branchId: number, pet: PetFit): KennelOption[] =>
  options.filter((k) => k.branchIds.includes(branchId) && kennelFitsPet(k, pet));

export const branchIdsWithBoarding = (options: readonly KennelOption[]): number[] =>
  [...new Set(options.flatMap((k) => k.branchIds))].sort((a, b) => a - b);

/** BR-LT-04: khách đặt trước ngày nhận tối thiểu 1 ngày và xa nhất 60 ngày (tính từ hôm nay giờ Việt Nam). */
export const checkInWindow = (today: string) => ({
  min: shiftDate(today, BOARDING_CFG.minLeadDays),
  max: shiftDate(today, BOARDING_CFG.maxAdvanceDays),
});

/** BR-LT-02: 1 đến 30 đêm, tính từ ngày nhận. */
export const checkOutWindow = (checkIn: string) => ({
  min: shiftDate(checkIn, BOARDING_CFG.minNights),
  max: shiftDate(checkIn, BOARDING_CFG.maxNights),
});

/**
 * Lỗi ngày cần báo ngay trên form, hoặc null. Bộ chọn ngày có nút "Hôm nay" bỏ qua giới hạn nên không thể chỉ dựa vào min / max của nó.
 * Chưa chọn ngày nhận thì chưa báo gì; chưa chọn ngày trả thì chỉ kiểm ngày nhận.
 */
export function bookingDateError(checkIn: string | null, checkOut: string | null, today: string): string | null {
  if (!checkIn) return null;
  const window = checkInWindow(today);
  if (checkIn < window.min) return `Ngày nhận phải từ ngày mai (${formatShortDate(window.min)}): khách đặt online trước ngày nhận ít nhất ${BOARDING_CFG.minLeadDays} ngày.`;
  if (checkIn > window.max) return `Chỉ đặt trước tối đa ${BOARDING_CFG.maxAdvanceDays} ngày, ngày nhận muộn nhất là ${formatShortDate(window.max)}.`;
  if (!checkOut) return null;
  const nights = nightsBetween(checkIn, checkOut);
  if (nights < BOARDING_CFG.minNights) return 'Ngày trả phải sau ngày nhận.';
  if (nights > BOARDING_CFG.maxNights) return `Lưu trú tối đa ${BOARDING_CFG.maxNights} đêm.`;
  return null;
}

/** Các đêm không còn chỗ (`remaining` = 0). */
export const soldOutNights = (nights: readonly NightAvailability[]): string[] => nights.filter((n) => n.remaining <= 0).map((n) => n.date);

const ddMM = (date: string) => `${date.slice(8, 10)}/${date.slice(5, 7)}`;

/** Cùng văn phong BR-LT-03 của BE: "Hết chỗ các đêm: 26/10, 27/10". */
export const soldOutMessage = (dates: readonly string[]): string => `Hết chỗ các đêm: ${dates.map(ddMM).join(', ')}`;
