import type { DocumentSource, DocumentSpecies, DocumentStatus, DocumentTopic, IndexStatus, ItemType, ProfileSpecies, RecoGroup } from '../../../shared/types/ai';

export const TOPIC_LABELS: Record<DocumentTopic, string> = {
  CARE: 'Chăm sóc',
  NUTRITION: 'Dinh dưỡng',
  BEHAVIOR: 'Hành vi',
  HYGIENE: 'Vệ sinh',
  VACCINATION: 'Tiêm phòng',
  CLINIC_INFO: 'Thông tin phòng khám',
};
export const SPECIES_LABELS: Record<DocumentSpecies, string> = { DOG: 'Chó', CAT: 'Mèo', OTHER: 'Loài khác', ALL: 'Mọi loài' };
export const PROFILE_SPECIES_LABELS: Record<ProfileSpecies, string> = { DOG: 'Chó', CAT: 'Mèo', OTHER: 'Loài khác' };
export const STATUS_LABELS: Record<DocumentStatus, string> = { DRAFT: 'Nháp', PUBLISHED: 'Đã xuất bản', ARCHIVED: 'Đã lưu trữ' };
export const STATUS_VARIANT = { DRAFT: 'neutral', PUBLISHED: 'success', ARCHIVED: 'warning' } as const;
export const SOURCE_LABELS: Record<DocumentSource, string> = { MANUAL: 'Soạn tay', CATALOG_PROTOCOL: 'Phác đồ tiêm (hệ thống)' };
export const INDEX_LABELS: Record<IndexStatus, string> = { PENDING: 'Đang chờ nạp', INDEXED: 'Đã nạp', FAILED: 'Nạp lỗi' };
export const INDEX_VARIANT = { PENDING: 'info', INDEXED: 'success', FAILED: 'danger' } as const;
export const ITEM_TYPE_LABELS: Record<ItemType, string> = { SERVICE: 'Dịch vụ', PRODUCT: 'Sản phẩm' };
export const GROUP_LABELS: Record<RecoGroup, string> = { GROOMING: 'Thẩm mỹ', BOARDING: 'Lưu trú', PRODUCT: 'Bán lẻ' };
