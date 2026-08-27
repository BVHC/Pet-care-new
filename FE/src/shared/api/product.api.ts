// Product API hooks - using React Query + axios client
import { useQuery } from '@tanstack/react-query';
import { apiClient } from './axios';
import { PRICE_BANDS, type Product, type Category, type Brand, type PetType } from '../models/product.model';

interface BackendProduct {
  id: number;
  name: string;
  category?: string;
  brand?: string;
  petType?: string;
  price: number;
  finalPrice: number;
  stockQuantity?: number;
  rating?: number;
  imageUrl?: string;
  images?: string[];
  description?: string;
  discountPercent?: number;
  badge?: string;
}

interface PaginatedResponse<T> {
  data: T[];
  page: number;
  pageSize: number;
  total: number;
}

const CATEGORY_NAME_TO_SLUG: Record<string, Category> = {
  'Thức ăn': 'food',
  'Đồ chơi': 'toys',
  'Bánh thưởng': 'treats',
  'Sức khỏe': 'health',
  'Ổ nằm': 'bedding',
};

function mapToProduct(p: BackendProduct): Product {
  return {
    id: p.id,
    name: p.name,
    category: CATEGORY_NAME_TO_SLUG[p.category ?? ''] ?? (p.category as Category) ?? 'food',
    brand: (p.brand ?? 'Royal Canin') as Brand,
    petType: (p.petType ?? 'dog') as PetType,
    price: p.price,
    finalPrice: p.finalPrice ?? p.price,
    rating: p.rating ?? 0,
    image: p.imageUrl ?? '/imgs/prod1.jpg',
    images: p.images?.length ? p.images : [p.imageUrl ?? '/imgs/prod1.jpg'],
    description: p.description ?? '',
    stock: p.stockQuantity ?? 0,
    badge: p.badge,
  };
}

export function useProducts(filter?: {
  petTypes?: string[];
  categories?: string[];
  brands?: string[];
  priceBand?: string;
  sort?: string;
}) {
  return useQuery({
    queryKey: ['products', filter],
    queryFn: async () => {
      const params: Record<string, string | number> = { page: 1, pageSize: 12 };
      if (filter?.petTypes?.length) params.petType = filter.petTypes[0];
      if (filter?.categories?.length) params.category = filter.categories[0];
      if (filter?.brands?.length) params.brand = filter.brands[0];
      if (filter?.priceBand && filter.priceBand !== 'all') {
        const band = PRICE_BANDS.find((b) => b.id === filter.priceBand);
        if (band) {
          if (filter.priceBand === 'u150') params.priceMax = 149_999;
          else if (filter.priceBand === '150-350') { params.priceMin = 150_000; params.priceMax = 350_000; }
          else if (filter.priceBand === 'o350') params.priceMin = 350_001;
        }
      }
      if (filter?.sort && filter.sort !== 'popular') params.sort = filter.sort;

      const { data } = await apiClient.get<PaginatedResponse<BackendProduct>>('/products', { params });
      return data.data.map(mapToProduct);
    },
  });
}

export function useProduct(id: number | string) {
  return useQuery({
    queryKey: ['product', id],
    queryFn: async () => {
      const { data } = await apiClient.get<BackendProduct>(`/products/${id}`);
      return mapToProduct(data);
    },
    enabled: !!id,
  });
}
