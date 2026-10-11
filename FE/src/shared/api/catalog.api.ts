// Catalog API — docs/api/catalog-v1.md #1–18 (đọc: nhân viên A04–A08; ghi: SUPER_MANAGER).
import { apiClient } from './axios';
import type { PageResponse } from '../types';
import type {
  CreateProductRequest,
  CreateProtocolRequest,
  CreateServiceRequest,
  Product,
  ProductCategory,
  ProductQuery,
  Service,
  ServiceGroup,
  Species,
  UpdateProductRequest,
  UpdateProtocolRequest,
  UpdateServiceRequest,
  VaccinationProtocol,
  VaccineType,
} from '../types/catalog';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body: object): Promise<T> => (await apiClient.post(url, body)).data.data;
const patch = async <T>(url: string, body: object): Promise<T> => (await apiClient.patch(url, body)).data.data;

export const catalogApi = {
  listCategories: (params?: { isActive?: boolean }) => get<ProductCategory[]>('/api/product-categories', params),
  createCategory: (body: { name: string }) => post<ProductCategory>('/api/product-categories', body),
  updateCategory: (categoryId: number, body: { name?: string; isActive?: boolean }) =>
    patch<ProductCategory>(`/api/product-categories/${categoryId}`, body),

  listProducts: (query: ProductQuery) => get<PageResponse<Product>>('/api/products', query),
  createProduct: (body: CreateProductRequest) => post<Product>('/api/products', body),
  updateProduct: (productId: number, body: UpdateProductRequest) => patch<Product>(`/api/products/${productId}`, body),

  listServices: (params?: { group?: ServiceGroup; isActive?: boolean }) => get<Service[]>('/api/services', params),
  createService: (body: CreateServiceRequest) => post<Service>('/api/services', body),
  updateService: (serviceId: number, body: UpdateServiceRequest) => patch<Service>(`/api/services/${serviceId}`, body),

  listVaccineTypes: (params?: { species?: Species; isActive?: boolean }) => get<VaccineType[]>('/api/vaccine-types', params),
  createVaccineType: (body: { name: string; species: Species }) => post<VaccineType>('/api/vaccine-types', body),
  updateVaccineType: (vaccineTypeId: number, body: { name?: string; isActive?: boolean }) =>
    patch<VaccineType>(`/api/vaccine-types/${vaccineTypeId}`, body),
  /** 400 BR-SP-07 nếu đã có phác đồ, sản phẩm hoặc mũi tiêm dùng loại này. */
  deleteVaccineType: async (vaccineTypeId: number): Promise<void> => {
    await apiClient.delete(`/api/vaccine-types/${vaccineTypeId}`);
  },

  listProtocols: (params?: { species?: Species; vaccineTypeId?: number; isActive?: boolean }) =>
    get<VaccinationProtocol[]>('/api/vaccination-protocols', params),
  createProtocol: (body: CreateProtocolRequest) => post<VaccinationProtocol>('/api/vaccination-protocols', body),
  updateProtocol: (protocolId: number, body: UpdateProtocolRequest) =>
    patch<VaccinationProtocol>(`/api/vaccination-protocols/${protocolId}`, body),
};
