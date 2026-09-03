import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { SlidersHorizontal, Search, X, ShoppingCart, Star } from 'lucide-react';
import { toast } from 'sonner';

const CATEGORY_LABELS: Record<string, string> = {
  food: 'Thức ăn',
  toys: 'Đồ chơi',
  treats: 'Bánh thưởng',
  health: 'Sức khỏe',
  bedding: 'Ổ nằm',
  grooming: 'Chăm sóc',
};

const PET_TYPE_LABELS: Record<string, string> = {
  dog: 'Cho Chó',
  cat: 'Cho Mèo',
};

const PRICE_BANDS = [
  { id: 'all', label: 'Tất cả giá' },
  { id: 'u150', label: 'Dưới 150K' },
  { id: '150-350', label: '150K - 350K' },
  { id: 'o350', label: 'Trên 350K' },
];

const SORT_OPTIONS = [
  { id: 'popular', label: 'Phổ biến nhất' },
  { id: 'newest', label: 'Mới nhất' },
  { id: 'price_asc', label: 'Giá: Thấp → Cao' },
  { id: 'price_desc', label: 'Giá: Cao → Thấp' },
];

const BRANDS = ['Royal Canin', 'Pedigree', 'Whiskas', 'SmartHeart', 'Nekko', 'Me-O', 'KitCat', 'Monge'];

const MOCK_PRODUCTS = [
  { id: 1, name: 'Royal Canin Adult', category: 'food', petType: 'dog', price: 450000, rating: 4.8, badge: 'Bán chạy', image: '/imgs/prod1.jpg' },
  { id: 2, name: 'Pedigree Chicken', category: 'food', petType: 'dog', price: 280000, rating: 4.5, image: '/imgs/prod2.jpg' },
  { id: 3, name: 'Whiskas Salmon', category: 'food', petType: 'cat', price: 120000, rating: 4.7, badge: 'Mới', image: '/imgs/prod3.jpg' },
  { id: 4, name: 'Interactive Ball', category: 'toys', petType: 'dog', price: 89000, rating: 4.3, image: '/imgs/prod4.jpg' },
  { id: 5, name: 'Catnip Toy Mouse', category: 'toys', petType: 'cat', price: 45000, rating: 4.6, image: '/imgs/prod5.jpg' },
  { id: 6, name: 'Orthopedic Bed', category: 'bedding', petType: 'dog', price: 890000, rating: 4.9, image: '/imgs/prod6.jpg' },
  { id: 7, name: 'Salmon Treats', category: 'treats', petType: 'cat', price: 150000, rating: 4.8, image: '/imgs/prod7.jpg' },
  { id: 8, name: 'Dental Chew', category: 'health', petType: 'dog', price: 95000, rating: 4.4, image: '/imgs/prod8.jpg' },
  { id: 9, name: 'Grooming Kit', category: 'grooming', petType: 'dog', price: 350000, rating: 4.6, image: '/imgs/prod1.jpg' },
  { id: 10, name: 'Cozy Cat Bed', category: 'bedding', petType: 'cat', price: 420000, rating: 4.7, image: '/imgs/prod2.jpg' },
  { id: 11, name: 'SmartHeart Gold', category: 'food', petType: 'dog', price: 180000, rating: 4.2, image: '/imgs/prod3.jpg' },
  { id: 12, name: 'Nekko Premium', category: 'food', petType: 'cat', price: 220000, rating: 4.5, image: '/imgs/prod4.jpg' },
];

const toggle = <T,>(arr: T[], val: T) =>
  arr.includes(val) ? arr.filter((v) => v !== val) : [...arr, val];

function formatVnd(price: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 }).format(price);
}

export function ShopPage() {
  const [searchParams] = useSearchParams();
  const petTypeParam = searchParams.get('petType');
  const categoryParam = searchParams.get('category');

  const [petTypes, setPetTypes] = useState<string[]>(petTypeParam ? [petTypeParam] : []);
  const [categories, setCategories] = useState<string[]>(categoryParam ? [categoryParam] : []);
  const [brands, setBrands] = useState<string[]>([]);
  const [priceBand, setPriceBand] = useState('all');
  const [sort, setSort] = useState('popular');
  const [openMobile, setOpenMobile] = useState(false);

  const products = useMemo(() => {
    let filtered = [...MOCK_PRODUCTS];

    if (petTypes.length > 0) {
      filtered = filtered.filter(p => petTypes.includes(p.petType));
    }
    if (categories.length > 0) {
      filtered = filtered.filter(p => categories.includes(p.category));
    }
    if (brands.length > 0) {
      filtered = filtered.filter(p => BRANDS.slice(0, 4).some(b => p.name.toLowerCase().includes(b.toLowerCase())));
    }
    if (priceBand === 'u150') {
      filtered = filtered.filter(p => p.price < 150000);
    } else if (priceBand === '150-350') {
      filtered = filtered.filter(p => p.price >= 150000 && p.price <= 350000);
    } else if (priceBand === 'o350') {
      filtered = filtered.filter(p => p.price > 350000);
    }

    if (sort === 'price_asc') {
      filtered.sort((a, b) => a.price - b.price);
    } else if (sort === 'price_desc') {
      filtered.sort((a, b) => b.price - a.price);
    }

    return filtered;
  }, [petTypes, categories, brands, priceBand, sort]);

  const activeCount = petTypes.length + categories.length + brands.length + (priceBand !== 'all' ? 1 : 0);
  const clearAll = () => { setPetTypes([]); setCategories([]); setBrands([]); setPriceBand('all'); };

  const handleAddToCart = (_id: number, name: string) => {
    toast.success(`Đã thêm ${name} vào giỏ hàng`);
  };

  const sidebar = (
    <div className="flex flex-col gap-6">
      <FilterGroup title="Loại thú cưng">
        {Object.entries(PET_TYPE_LABELS).map(([key, label]) => (
          <CheckRow key={key} label={label} checked={petTypes.includes(key)}
            onChange={() => setPetTypes((a) => toggle(a, key))} />
        ))}
      </FilterGroup>

      <FilterGroup title="Danh mục">
        {Object.entries(CATEGORY_LABELS).map(([key, label]) => (
          <CheckRow key={key} label={label} checked={categories.includes(key)}
            onChange={() => setCategories((a) => toggle(a, key))} />
        ))}
      </FilterGroup>

      <FilterGroup title="Giá">
        {PRICE_BANDS.map((b) => (
          <RadioRow key={b.id} name="price" label={b.label} checked={priceBand === b.id}
            onChange={() => setPriceBand(b.id)} />
        ))}
      </FilterGroup>

      <FilterGroup title="Thương hiệu">
        {BRANDS.slice(0, 4).map((b) => (
          <CheckRow key={b} label={b} checked={brands.includes(b)}
            onChange={() => setBrands((a) => toggle(a, b))} />
        ))}
      </FilterGroup>
    </div>
  );

  return (
    <div className="mx-auto max-w-[1200px] px-4 py-8 sm:px-6">
      <nav className="mb-4 text-sm text-gray-500">
        <Link to="/" className="hover:text-[#843122]">Trang chủ</Link>
        <span className="mx-2">/</span>
        <span className="font-semibold text-gray-900">Danh mục sản phẩm</span>
      </nav>

      <div className="mb-6 flex items-end justify-between gap-4">
        <div>
          <h1 className="font-[var(--font-friendly)] text-2xl font-bold text-gray-900 sm:text-3xl">
            Danh mục sản phẩm
          </h1>
          <p className="mt-1 text-sm text-gray-500">
            {products.length} sản phẩm cho thú cưng của bạn
          </p>
        </div>
        <label className="flex items-center gap-2 text-sm text-gray-500">
          <span className="hidden sm:inline">Sắp xếp:</span>
          <select value={sort} onChange={(e) => setSort(e.target.value)}
            className="cursor-pointer rounded-lg border border-gray-200 bg-white px-3 py-2 text-gray-900 focus:border-[#843122] focus:outline-none">
            {SORT_OPTIONS.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
          </select>
        </label>
      </div>

      <button onClick={() => setOpenMobile(true)}
        className="mb-4 flex items-center gap-2 rounded-lg border border-gray-200 bg-white px-4 py-2 text-sm font-semibold text-gray-900 lg:hidden">
        <SlidersHorizontal size={16} />
        Bộ lọc {activeCount > 0 && `(${activeCount})`}
      </button>

      <div className="flex gap-8">
        <aside className="hidden w-60 shrink-0 lg:block">
          <div className="sticky top-24 rounded-xl border border-gray-200 bg-white p-5">
            <div className="mb-4 flex items-center justify-between">
              <span className="font-[var(--font-friendly)] font-bold text-gray-900">Bộ lọc</span>
              {activeCount > 0 && (
                <button onClick={clearAll} className="text-xs font-semibold text-[#843122] hover:underline">
                  Xoá tất cả
                </button>
              )}
            </div>
            {sidebar}
          </div>
        </aside>

        <div className="min-w-0 flex-1">
          {products.length === 0 ? (
            <div className="flex flex-col items-center justify-center rounded-xl border-2 border-dashed border-gray-200 py-24 text-center">
              <Search size={32} className="mb-3 text-gray-300" />
              <p className="font-semibold text-gray-900">Không có sản phẩm phù hợp</p>
              <p className="mt-1 text-sm text-gray-500">Hãy thử bỏ bớt bộ lọc để xem thêm kết quả.</p>
              <button onClick={clearAll} className="mt-4 text-sm font-semibold text-[#843122] hover:underline">
                Xoá bộ lọc
              </button>
            </div>
          ) : (
            <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 xl:grid-cols-4">
              {products.map((p) => (
                <div key={p.id} className="overflow-hidden rounded-xl bg-white shadow-sm hover:shadow-md transition-shadow">
                  <Link to={`/shop/${p.id}`}>
                    <div className="relative aspect-[4/3] bg-gray-100">
                      <img src={p.image} alt={p.name} className="h-full w-full object-cover" />
                      {p.badge && (
                        <span className="absolute top-2 left-2 rounded-full bg-amber-400 px-2 py-0.5 text-[11px] font-bold text-white">
                          {p.badge}
                        </span>
                      )}
                    </div>
                    <div className="p-3">
                      <div className="text-[11px] tracking-wide text-gray-400 uppercase">{CATEGORY_LABELS[p.category]}</div>
                      <div className="my-0.5 mb-1.5 text-[15px] font-bold text-gray-900 line-clamp-1">{p.name}</div>
                      <div className="flex items-center justify-between">
                        <span className="text-base font-bold text-gray-900">{formatVnd(p.price)}</span>
                        <span className="flex items-center gap-1 text-sm text-gray-500">
                          <Star size={13} className="fill-amber-400 text-amber-400" /> {p.rating}
                        </span>
                      </div>
                    </div>
                  </Link>
                  <div className="px-3 pb-3">
                    <button
                      onClick={() => handleAddToCart(p.id, p.name)}
                      className="mt-2 flex w-full items-center justify-center gap-1.5 rounded-lg border border-[#843122] bg-[#843122] px-3 py-2 text-[13px] font-semibold text-white hover:bg-[#6a2517] transition-colors"
                    >
                      <ShoppingCart size={14} />
                      Thêm vào giỏ
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {openMobile && (
        <div className="fixed inset-0 z-50 lg:hidden">
          <div className="absolute inset-0 bg-black/40" onClick={() => setOpenMobile(false)} />
          <div className="absolute top-0 left-0 h-full w-[85%] max-w-sm overflow-y-auto bg-white p-5 shadow-xl">
            <div className="mb-4 flex items-center justify-between">
              <span className="font-[var(--font-friendly)] font-bold text-gray-900">Bộ lọc</span>
              <button onClick={() => setOpenMobile(false)} aria-label="Đóng"><X size={20} /></button>
            </div>
            {sidebar}
            <div className="mt-6 flex gap-3">
              <button onClick={clearAll}
                className="flex-1 rounded-lg border border-gray-200 py-2 text-sm font-semibold text-gray-900">
                Xoá
              </button>
              <button onClick={() => setOpenMobile(false)}
                className="flex-1 rounded-lg bg-[#843122] py-2 text-sm font-semibold text-white">
                Xem {products.length} sản phẩm
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function FilterGroup({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <div className="mb-2 text-sm font-bold text-gray-900">{title}</div>
      <div className="flex flex-col gap-1.5">{children}</div>
    </div>
  );
}

function CheckRow({ label, checked, onChange }: { label: string; checked: boolean; onChange: () => void }) {
  return (
    <label className="flex cursor-pointer items-center gap-2.5 text-sm text-gray-600 hover:text-gray-900">
      <input type="checkbox" checked={checked} onChange={onChange}
        className="h-4 w-4 accent-[#843122]" />
      {label}
    </label>
  );
}

function RadioRow({ name, label, checked, onChange }: { name: string; label: string; checked: boolean; onChange: () => void }) {
  return (
    <label className="flex cursor-pointer items-center gap-2.5 text-sm text-gray-600 hover:text-gray-900">
      <input type="radio" name={name} checked={checked} onChange={onChange}
        className="h-4 w-4 accent-[#843122]" />
      {label}
    </label>
  );
}
