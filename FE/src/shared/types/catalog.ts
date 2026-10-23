// Danh mục sản phẩm / dịch vụ — theo docs/api/catalog-v1.md (mục Schema). Trường `?` có thể vắng hoặc null.

export type Species = 'DOG' | 'CAT' | 'OTHER';
/** Khám/Tiêm, Thẩm mỹ, Lưu trú (05 §0). */
export type ServiceGroup = 'MEDICAL' | 'GROOMING' | 'BOARDING';
/** Chỉ nhóm MEDICAL (BR-SP-06). */
export type MedicalType = 'EXAM' | 'VACCINE';
export type ProductType = 'GOODS' | 'DRUG' | 'VACCINE';

export const SPECIES_LABELS: Record<Species, string> = { DOG: 'Chó', CAT: 'Mèo', OTHER: 'Khác' };
export const SERVICE_GROUP_LABELS: Record<ServiceGroup, string> = {
  MEDICAL: 'Khám/Tiêm',
  GROOMING: 'Thẩm mỹ',
  BOARDING: 'Lưu trú (loại chuồng)',
};
export const MEDICAL_TYPE_LABELS: Record<MedicalType, string> = { EXAM: 'Khám', VACCINE: 'Tiêm' };
export const PRODUCT_TYPE_LABELS: Record<ProductType, string> = { GOODS: 'Hàng hóa', DRUG: 'Thuốc', VACCINE: 'Vaccine' };

export interface ProductCategory {
  categoryId: number;
  name: string;
  isActive: boolean;
}

export interface Product {
  productId: number;
  categoryId: number;
  sku: string;
  name: string;
  productType: ProductType;
  isPrescription: boolean;
  tracksExpiry: boolean;
  vaccineTypeId?: number | null;
  unit: string;
  /** VND */
  price: number;
  description?: string | null;
  imageUrl?: string | null;
  isActive: boolean;
}

export interface CreateProductRequest {
  categoryId: number;
  sku: string;
  name: string;
  productType: ProductType;
  isPrescription: boolean;
  tracksExpiry: boolean;
  vaccineTypeId?: number;
  unit: string;
  price: number;
  description?: string;
  imageUrl?: string;
}

/** `productType` và `sku` không đổi sau khi tạo (catalog-v1 A1). */
export type UpdateProductRequest = Partial<Omit<CreateProductRequest, 'productType' | 'sku'>> & { isActive?: boolean };

export interface ProductQuery {
  q?: string;
  categoryId?: number;
  productType?: ProductType;
  vaccineTypeId?: number;
  isActive?: boolean;
  retailOnly?: boolean;
  page?: number;
  size?: number;
}

/** Chỉ dịch vụ nhóm BOARDING (BR-SP-04). */
export interface KennelTypeSpec {
  species: Species;
  maxWeightKg: number;
}

export interface Service {
  serviceId: number;
  name: string;
  group: ServiceGroup;
  medicalType?: MedicalType | null;
  /** VND; với loại chuồng là giá theo đêm. */
  price: number;
  /** Hiện "Từ X đ" (BR-CK-02). */
  priceIsFrom: boolean;
  description?: string | null;
  isActive: boolean;
  kennelType?: KennelTypeSpec | null;
}

export interface CreateServiceRequest {
  name: string;
  group: ServiceGroup;
  medicalType?: MedicalType;
  price: number;
  priceIsFrom?: boolean;
  description?: string;
  kennelType?: KennelTypeSpec;
}

/** `group` không đổi sau khi tạo (catalog-v1 A1). */
export type UpdateServiceRequest = Partial<Omit<CreateServiceRequest, 'group'>> & { isActive?: boolean };

export interface VaccineType {
  vaccineTypeId: number;
  name: string;
  species: Species;
  isActive: boolean;
}

export interface VaccinationProtocol {
  protocolId: number;
  species: Species;
  vaccineTypeId: number;
  doseNumber: number;
  intervalDays: number;
  minAgeWeeks: number;
  requiredForBoarding: boolean;
  isActive: boolean;
}

export type CreateProtocolRequest = Omit<VaccinationProtocol, 'protocolId' | 'isActive'>;

/** Loài, loại vaccine, mũi thứ không đổi; sửa chỉ áp dụng cho mũi tiêm sau thời điểm sửa (BR-SP-03). */
export type UpdateProtocolRequest = Partial<Pick<VaccinationProtocol, 'intervalDays' | 'minAgeWeeks' | 'requiredForBoarding' | 'isActive'>>;

/** catalog-v1 #19: dịch vụ đang kinh doanh và bật ở ≥ 1 chi nhánh ACTIVE, kèm các chi nhánh đó (BR-CK-01). */
export interface PublicService {
  serviceId: number;
  name: string;
  group: ServiceGroup;
  price: number;
  /** Hiện "Từ X đ" (BR-CK-02). */
  priceIsFrom: boolean;
  description?: string | null;
  kennelType?: KennelTypeSpec | null;
  branchIds: number[];
}
