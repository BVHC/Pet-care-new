// Product domain model for Pet-care-new FE

export type PetType = 'dog' | 'cat';
export type Category = 'food' | 'toys' | 'treats' | 'health' | 'bedding';
export type Brand = 'Royal Canin' | 'Pedigree' | 'Whiskas' | 'SmartHeart' | 'Me-O' | 'KitCat';

export interface Product {
  id: number;
  name: string;
  category: Category;
  brand: Brand;
  petType: PetType;
  price: number;
  finalPrice: number;
  rating: number;
  image: string;
  images: string[];
  description: string;
  stock: number;
  badge?: string;
}

export interface ProductFilter {
  petTypes: PetType[];
  categories: Category[];
  brands: Brand[];
  priceBand: string;
  sort: string;
}

// Display labels (Vietnamese)
export const PET_TYPE_LABELS: Record<PetType, string> = { dog: 'Chó', cat: 'Mèo' };
export const CATEGORY_LABELS: Record<Category, string> = {
  food: 'Thức ăn',
  toys: 'Đồ chơi',
  treats: 'Bánh thưởng',
  health: 'Sức khỏe',
  bedding: 'Ổ nằm',
};
export const BRANDS: Brand[] = ['Royal Canin', 'Pedigree', 'Whiskas', 'SmartHeart', 'Me-O', 'KitCat'];

export const PRICE_BANDS: { id: string; label: string; test: (p: number) => boolean }[] = [
  { id: 'all', label: 'Mọi mức giá', test: () => true },
  { id: 'u150', label: 'Dưới 150K', test: (p) => p < 150_000 },
  { id: '150-350', label: '150K – 350K', test: (p) => p >= 150_000 && p <= 350_000 },
  { id: 'o350', label: 'Trên 350K', test: (p) => p > 350_000 },
];

export const SORT_OPTIONS: { id: string; label: string }[] = [
  { id: 'popular', label: 'Phổ biến' },
  { id: 'price-asc', label: 'Giá thấp → cao' },
  { id: 'price-desc', label: 'Giá cao → thấp' },
  { id: 'rating', label: 'Đánh giá cao' },
];
