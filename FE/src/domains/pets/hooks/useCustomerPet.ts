// React Query cho module KH (UC22..26) và các hàm kiểm tra phía FE trước khi gửi.
// Như shared/hooks/useAdminIdentity.ts: hook chỉ gọi API và làm mới cache; màn hình tự báo
// thành công / lỗi (toast, toApiError, fieldErrorsOf). BE vẫn kiểm lại mọi rule.

import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { identityApi } from '../../../shared/api/identity.api';
import { INVALIDATE, QUERY_KEYS } from '../../../shared/constants/queryKeys';
import { retryDelay, shouldRetry } from '../../../shared/constants/realtime';
import { customerPetApi } from '../customer-pet.api';
import type {
  AddWeightRequest,
  CreatePetRequest,
  CustomerSearchQuery,
  MarkDeceasedRequest,
  TransferOwnerRequest,
  UpdateCustomerRequest,
  UpdatePetRequest,
} from '../types';

/** Lỗi 4xx không đổi khi thử lại: báo ngay, không chờ 3 lần retry. */
const retry = { retry: shouldRetry, retryDelay } as const;

const CRITERIA = ['phone', 'name', 'email', 'petName'] as const;

// ───── Đọc ─────

/** UC23: bỏ điều kiện rỗng; chưa có điều kiện nào thì không gọi (BE trả 400). */
export function useCustomerLookup(query: CustomerSearchQuery) {
  const filters: Record<string, string> = {};
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && String(value).trim() !== '') filters[key] = String(value).trim();
  }
  return useQuery({
    queryKey: QUERY_KEYS.counterCustomers.search(filters),
    queryFn: () => customerPetApi.searchCustomers(filters as CustomerSearchQuery),
    enabled: CRITERIA.some((key) => key in filters),
    placeholderData: keepPreviousData,
    ...retry,
  });
}

export const useCustomerDetail = (customerId: number | null) =>
  useQuery({
    queryKey: QUERY_KEYS.counterCustomers.detail(customerId ?? 0),
    queryFn: () => customerPetApi.getCustomer(customerId!),
    enabled: customerId != null,
    ...retry,
  });

export const useMyPets = () => useQuery({ queryKey: QUERY_KEYS.pets.mine(), queryFn: customerPetApi.listMyPets, ...retry });

export const usePetDetail = (petId: number | null) =>
  useQuery({
    queryKey: QUERY_KEYS.pets.detail(petId ?? 0),
    queryFn: () => customerPetApi.getPet(petId!),
    enabled: petId != null,
    ...retry,
  });

export const usePetWeights = (petId: number | null) =>
  useQuery({
    queryKey: QUERY_KEYS.pets.weights(petId ?? 0),
    queryFn: () => customerPetApi.listWeights(petId!),
    enabled: petId != null,
    ...retry,
  });

export const useHealthRecord = (petId: number | null) =>
  useQuery({
    queryKey: QUERY_KEYS.pets.health(petId ?? 0),
    queryFn: () => customerPetApi.getHealthRecord(petId!),
    enabled: petId != null,
    ...retry,
  });

// ───── Ghi ─────

function usePetMutation<A, R>(fn: (args: A) => Promise<R>, refresh: readonly (readonly unknown[])[]) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => refresh.forEach((queryKey) => queryClient.invalidateQueries({ queryKey })),
  });
}

export const useCreateCounterCustomer = () =>
  usePetMutation(customerPetApi.createCounterCustomer, INVALIDATE.createCounterCustomer);

export const useUpdateCustomer = (customerId: number) =>
  usePetMutation((body: UpdateCustomerRequest) => customerPetApi.updateCustomer(customerId, body), INVALIDATE.updateCounterCustomer);

export const useAddPetForCustomer = (customerId: number) =>
  usePetMutation((body: CreatePetRequest) => customerPetApi.addPetForCustomer(customerId, body), INVALIDATE.addPetForCustomer);

export const useAddMyPet = () => usePetMutation(customerPetApi.addMyPet, INVALIDATE.createMyPet);

export const useUpdatePet = (petId: number) =>
  usePetMutation((body: UpdatePetRequest) => customerPetApi.updatePet(petId, body), INVALIDATE.updatePet);

export const useMarkDeceased = (petId: number) =>
  usePetMutation((body: MarkDeceasedRequest) => customerPetApi.markDeceased(petId, body), INVALIDATE.markDeceased);

/** Truyền id thú khi gọi `mutate(petId)`: một hook dùng được cho cả danh sách thú. */
export const useDeletePet = () => usePetMutation((petId: number) => customerPetApi.deletePet(petId), INVALIDATE.deletePet);

export const useTransferOwner = (petId: number) =>
  usePetMutation((body: TransferOwnerRequest) => customerPetApi.transferOwner(petId, body), INVALIDATE.transferOwner);

export const useAddWeight = (petId: number) =>
  usePetMutation((body: AddWeightRequest) => customerPetApi.addWeight(petId, body), INVALIDATE.addWeight);

// Tài khoản của hồ sơ khách tại quầy (UC22, identity-v1 #25–27). Lễ tân đã đối chiếu CCCD tại quầy (BR-TK-16, A6).

/** #25: gửi mã tới email mới; chưa xác nhận thì email cũ giữ nguyên. Không làm mới cache. */
export const useSendCustomerEmailChange = (customerId: number) =>
  useMutation({
    mutationFn: (newEmail: string) =>
      identityApi.sendCustomerEmailChange(customerId, { newEmail, verificationMethod: 'ID_CARD_IN_PERSON' }),
  });

export const useConfirmCustomerEmailChange = (customerId: number) =>
  usePetMutation((code: string) => identityApi.confirmCustomerEmailChange(customerId, { code }), INVALIDATE.updateCounterCustomer);

export const useLinkCounterAccount = (customerId: number) =>
  usePetMutation(
    (accountEmail: string) => identityApi.linkCounterAccount(customerId, { accountEmail, verificationMethod: 'ID_CARD_IN_PERSON' }),
    INVALIDATE.updateCounterCustomer,
  );

// ───── Kiểm tra phía FE (lỗi theo trường, cùng dạng với fieldErrorsOf) ─────

export type FieldErrors = Partial<Record<string, string>>;

export const hasErrors = (errors: FieldErrors) => Object.keys(errors).length > 0;

/** SĐT 10 số bắt đầu bằng 0 (như SĐT hồ sơ ở UC06). */
export const PHONE_PATTERN = /^0\d{9}$/;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** Ngày nghiệp vụ hôm nay theo giờ Việt Nam, dạng yyyy-MM-dd (so sánh chuỗi được). */
export function todayVn(now: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Ho_Chi_Minh' }).format(now);
}

/** BR-KH-01: họ tên bắt buộc; SĐT bắt buộc với hồ sơ tại quầy. Độ dài theo 05-erd `customers`. */
export function customerErrors(
  body: { fullName?: string; phone?: string; email?: string },
  phoneRequired: boolean,
): FieldErrors {
  const errors: FieldErrors = {};
  const fullName = body.fullName?.trim() ?? '';
  const phone = body.phone?.trim() ?? '';
  const email = body.email?.trim() ?? '';
  if (!fullName) errors.fullName = 'Vui lòng nhập họ tên';
  else if (fullName.length > 100) errors.fullName = 'Họ tên tối đa 100 ký tự';
  if (!phone) {
    if (phoneRequired) errors.phone = 'Hồ sơ tại quầy bắt buộc có số điện thoại';
  } else if (!PHONE_PATTERN.test(phone)) {
    errors.phone = 'Số điện thoại phải có dạng 0xxxxxxxxx';
  }
  if (email && (email.length > 255 || !EMAIL_PATTERN.test(email))) errors.email = 'Email không hợp lệ';
  return errors;
}

/** BR-KH-02: tên, loài, giới tính bắt buộc; ngày sinh không ở tương lai. Độ dài theo 05-erd `pets`. */
export function petErrors(body: CreatePetRequest, today: string = todayVn()): FieldErrors {
  const errors: FieldErrors = {};
  if (!body.name?.trim()) errors.name = 'Vui lòng nhập tên thú cưng';
  if (!body.species) errors.species = 'Vui lòng chọn loài';
  if (!body.sex) errors.sex = 'Vui lòng chọn giới tính';
  if (body.birthDate && body.birthDate > today) errors.birthDate = 'Ngày sinh không được ở tương lai';
  if ((body.breed?.trim().length ?? 0) > 100) errors.breed = 'Giống tối đa 100 ký tự';
  if ((body.color?.trim().length ?? 0) > 50) errors.color = 'Màu lông tối đa 50 ký tự';
  if ((body.photoUrl?.trim().length ?? 0) > 500) errors.photoUrl = 'Đường dẫn ảnh tối đa 500 ký tự';
  return errors;
}

/** BR-KH-05: chọn ngày mất và xác nhận (xác nhận 2 bước ở giao diện, A3). */
export function deceasedErrors(body: MarkDeceasedRequest, today: string = todayVn()): FieldErrors {
  const errors: FieldErrors = {};
  if (!body.deceasedOn) errors.deceasedOn = 'Vui lòng chọn ngày mất';
  else if (body.deceasedOn > today) errors.deceasedOn = 'Ngày mất không được ở tương lai';
  if (!body.confirmed) errors.confirmed = 'Cần xác nhận trước khi đánh dấu đã mất';
  return errors;
}

/** BR-KH-08: chủ mới đã có hồ sơ, khác chủ hiện tại; lễ tân xác nhận chủ cũ (A4). */
export function transferErrors(
  body: { newCustomerId: number | null; previousOwnerConfirmed: boolean },
  currentCustomerId: number,
): FieldErrors {
  const errors: FieldErrors = {};
  if (body.newCustomerId == null) errors.newCustomerId = 'Vui lòng chọn chủ mới';
  else if (body.newCustomerId === currentCustomerId) errors.newCustomerId = 'Chủ mới phải khác chủ hiện tại';
  if (!body.previousOwnerConfirmed) errors.previousOwnerConfirmed = 'Cần xác nhận chủ cũ có mặt hoặc đã đồng ý';
  return errors;
}

/** Email bắt buộc, đúng định dạng, ≤ 255 ký tự; trả '' nếu hợp lệ. */
export function emailError(email: string): string {
  const value = email.trim();
  if (!value) return 'Vui lòng nhập email';
  if (value.length > 255 || !EMAIL_PATTERN.test(value)) return 'Email không hợp lệ';
  return '';
}

/** Mã OTP 6 chữ số (BR-TK-04). */
export const otpError = (code: string) => (/^\d{6}$/.test(code.trim()) ? '' : 'Mã OTP gồm 6 chữ số');

/** BR-KH-04: cân nặng > 0; cột NUMERIC(6,2) nên tối đa 9999.99. */
export function weightErrors(weightKg: number | null): FieldErrors {
  if (weightKg == null || Number.isNaN(weightKg)) return { weightKg: 'Vui lòng nhập cân nặng' };
  if (weightKg <= 0) return { weightKg: 'Cân nặng phải lớn hơn 0' };
  if (weightKg > 9999.99) return { weightKg: 'Cân nặng tối đa 9999.99 kg' };
  return {};
}
