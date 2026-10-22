// Module KH (Khách hàng & Thú cưng) — UC22..26.
// Hợp đồng: docs/api/customer-v1.md (mục Schema) và docs/api/visit-v1.md #21 (HealthRecord và các schema con).
// CustomerProfile dùng chung với hồ sơ khách của tôi (UC06): shared/types/profile.ts.

import type { CreatedChannel, CustomerProfile } from '../../shared/types/profile';

export type Species = 'DOG' | 'CAT' | 'OTHER';
export type Sex = 'MALE' | 'FEMALE' | 'UNKNOWN';
export type WeightSource = 'VET' | 'INTAKE' | 'OWNER';
export type ServiceGroup = 'MEDICAL' | 'GROOMING';

export const SPECIES_LABEL: Record<Species, string> = { DOG: 'Chó', CAT: 'Mèo', OTHER: 'Khác' };
export const SPECIES_EMOJI: Record<Species, string> = { DOG: '🐶', CAT: '🐱', OTHER: '🐾' };
export const SEX_LABEL: Record<Sex, string> = { MALE: 'Đực', FEMALE: 'Cái', UNKNOWN: 'Chưa rõ' };
export const WEIGHT_SOURCE_LABEL: Record<WeightSource, string> = {
  VET: 'Bác sĩ đo khi khám',
  INTAKE: 'Đo khi nhận lưu trú',
  OWNER: 'Chủ tự khai',
};
export const CHANNEL_LABEL: Record<CreatedChannel, string> = { ONLINE: 'Đăng ký online', COUNTER: 'Lập tại quầy' };
export const SERVICE_GROUP_LABEL: Record<ServiceGroup, string> = { MEDICAL: 'Khám bệnh', GROOMING: 'Thẩm mỹ' };

// ───── Thú cưng (UC24, UC26) ─────

export interface PetBrief {
  petId: number;
  name: string;
  species: Species;
  deceased: boolean;
}

export interface PetResponse {
  petId: number;
  customerId: number;
  name: string;
  species: Species;
  sex: Sex;
  breed?: string | null;
  birthDate?: string | null;
  birthDateEstimated: boolean;
  color?: string | null;
  isNeutered?: boolean | null;
  photoUrl?: string | null;
  /** Khác null = đã mất, hồ sơ chỉ đọc (BR-KH-05). */
  deceasedOn?: string | null;
  /** Đã có bệnh án hoặc mũi tiêm: không đổi loài (BR-KH-03). */
  speciesLocked: boolean;
  currentWeightKg?: number | null;
  /** Chưa phát sinh lịch hẹn, đặt chỗ, Visit hay Order: được xóa (BR-KH-06). */
  deletable: boolean;
}

export interface CreatePetRequest {
  name: string;
  species: Species;
  sex: Sex;
  breed?: string;
  birthDate?: string;
  birthDateEstimated?: boolean;
  color?: string;
  isNeutered?: boolean;
  photoUrl?: string;
}

/** null / không gửi = giữ nguyên; chuỗi rỗng = xóa. */
export type UpdatePetRequest = Partial<CreatePetRequest>;

export interface PetWriteResult {
  pet: PetResponse;
  /** Chủ đã có thú cùng tên, cùng loài: cảnh báo, không chặn (BR-KH-02). */
  duplicateNameWarning: boolean;
}

export interface MarkDeceasedRequest {
  deceasedOn: string;
  confirmed: boolean;
}

export interface TransferOwnerRequest {
  newCustomerId: number;
  previousOwnerConfirmed: boolean;
}

// ───── Cân nặng (BR-KH-04) ─────

export interface WeightRecord {
  weightRecordId: number;
  weightKg: number;
  source: WeightSource;
  visitId?: number | null;
  boardingBookingId?: number | null;
  measuredAt: string;
}

export interface AddWeightRequest {
  weightKg: number;
  measuredAt?: string;
}

// ───── Hồ sơ khách tại quầy, tra cứu (UC22, UC23) ─────

/** Một SĐT có thể ra nhiều hồ sơ: nhân viên chọn theo họ tên, email đã che và thú cưng (BR-KH-10). */
export interface CustomerSearchItem {
  customerId: number;
  fullName: string;
  phone?: string | null;
  maskedEmail?: string | null;
  createdChannel: CreatedChannel;
  hasAccount: boolean;
  pets: PetBrief[];
}

/** Cần ít nhất một điều kiện tìm. */
export interface CustomerSearchQuery {
  phone?: string;
  name?: string;
  email?: string;
  petName?: string;
  page?: number;
  size?: number;
}

export interface CreateCounterCustomerRequest {
  fullName: string;
  phone: string;
  email?: string;
}

/** null / không gửi = giữ nguyên. Email chỉ sửa được khi hồ sơ chưa gắn tài khoản (A5). */
export interface UpdateCustomerRequest {
  fullName?: string;
  phone?: string;
  email?: string;
}

export interface CustomerWriteResult {
  customer: CustomerProfile;
  /** SĐT trùng hồ sơ khác: cảnh báo nghi trùng, không chặn (BR-KH-10). */
  duplicatePhoneProfiles: CustomerSearchItem[];
}

export interface CustomerDetail {
  customer: CustomerProfile;
  pets: PetResponse[];
}

// ───── Hồ sơ sức khỏe (UC25) — docs/api/visit-v1.md ─────

export interface PrescriptionItem {
  itemId: number;
  productId: number;
  productName: string;
  quantity: number;
  dosageInstructions: string;
  isExternalPurchase: boolean;
  externalReason?: 'OUT_OF_STOCK_AT_PRESCRIBE' | 'OUT_OF_STOCK_AT_PAYMENT' | null;
  orderLineId?: number | null;
}

export interface Addendum {
  addendumId: number;
  content: string;
  isInternal: boolean;
  createdBy: number;
  createdByName: string;
  createdAt: string;
}

export interface HealthVisitEntry {
  visitId: number;
  visitDate: string;
  branchName: string;
  serviceGroup: ServiceGroup;
  examination?: string | null;
  diagnosis?: string | null;
  treatmentPlan?: string | null;
  /** Khách không nhận trường này (BR-KH-07, BR-KB-01). */
  internalNote?: string | null;
  followUpDate?: string | null;
  prescriptions: PrescriptionItem[];
  /** Khách chỉ nhận bản bổ sung không nội bộ. */
  addenda: Addendum[];
}

export interface VaccinationRecord {
  vaccinationId: number;
  visitId: number;
  branchId: number;
  vaccineTypeId: number;
  vaccineTypeName: string;
  protocolId: number;
  doseNumber: number;
  productId: number;
  productName: string;
  lotNumber?: string | null;
  expiryDate?: string | null;
  administeredOn: string;
  nextDueDate: string;
  superseded: boolean;
  recordedBy: number;
  orderLineId: number;
}

export interface UpcomingVaccination {
  vaccineTypeId: number;
  vaccineTypeName: string;
  nextDueDate: string;
}

export interface HealthRecord {
  petId: number;
  visits: HealthVisitEntry[];
  vaccinations: VaccinationRecord[];
  upcomingVaccinations: UpcomingVaccination[];
  upcomingFollowUpDate?: string | null;
}
