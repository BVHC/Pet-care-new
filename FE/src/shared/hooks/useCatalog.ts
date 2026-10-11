// React Query cho danh mục (T21). Mọi thao tác ghi làm mới cả nhánh ['catalog'] vì các bảng tham chiếu
// nhau (sản phẩm hiện tên danh mục, phác đồ hiện tên loại vaccine…).
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { catalogApi } from '../api/catalog.api';
import type { ProductQuery, ServiceGroup, Species } from '../types/catalog';

export const useCategories = () =>
  useQuery({ queryKey: ['catalog', 'categories'], queryFn: () => catalogApi.listCategories() });

export const useProducts = (query: ProductQuery) =>
  useQuery({
    queryKey: ['catalog', 'products', query],
    queryFn: () => catalogApi.listProducts(query),
    placeholderData: keepPreviousData,
  });

export const useServices = (group?: ServiceGroup) =>
  useQuery({ queryKey: ['catalog', 'services', group], queryFn: () => catalogApi.listServices({ group }) });

export const useVaccineTypes = (species?: Species) =>
  useQuery({ queryKey: ['catalog', 'vaccine-types', species], queryFn: () => catalogApi.listVaccineTypes({ species }) });

export const useProtocols = (filter: { species?: Species; vaccineTypeId?: number }) =>
  useQuery({ queryKey: ['catalog', 'protocols', filter], queryFn: () => catalogApi.listProtocols(filter) });

export function useCatalogMutation<A, R>(fn: (args: A) => Promise<R>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['catalog'] }),
  });
}
