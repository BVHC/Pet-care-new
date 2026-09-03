import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import {
  PawPrint,
  ShoppingBag,
  Star,
  Sparkles,
  ChevronRight,
  ShoppingCart,
  Package,
  ArrowLeft,
  Filter,
  Heart,
  Cat
} from 'lucide-react';

// Mock products data
const DOG_PRODUCTS = [
  { id: 1, name: 'Royal Canin Adult', desc: 'Thức ăn hạt dinh dưỡng cao cấp cho chó trưởng thành', price: '450.000đ', rating: 4.8, image: 'https://images.unsplash.com/photo-1589924691995-400dc9ecc119?w=400', badge: 'Bán chạy', category: 'food' },
  { id: 2, name: 'Pedigree Chicken', desc: 'Thức ăn chó vị gà, giàu protein', price: '280.000đ', rating: 4.5, image: 'https://images.unsplash.com/photo-1568640347023-a616a30bc3bd?w=400', category: 'food' },
  { id: 4, name: 'Interactive Ball', desc: 'Bóng tương tác phát sáng cho chó', price: '89.000đ', rating: 4.3, image: 'https://images.unsplash.com/photo-1601758124510-52d02ddb7cbd?w=400', badge: 'Mới', category: 'toy' },
  { id: 8, name: 'Dental Chew', desc: 'Xương làm sạch răng cho chó', price: '95.000đ', rating: 4.4, image: 'https://images.unsplash.com/photo-1583337130417-3346a1be7dee?w=400', category: 'health' },
  { id: 11, name: 'Cozy Dog Bed', desc: 'Ổ nằm êm ái cho chó', price: '520.000đ', rating: 4.9, image: 'https://images.unsplash.com/photo-1591946614720-90a587da4a36?w=400', badge: 'Hot', category: 'accessory' },
  { id: 12, name: 'Retractable Leash', desc: 'Dây dắt chó có khóa tự động', price: '180.000đ', rating: 4.6, image: 'https://images.unsplash.com/photo-1587300003388-59208cc962cb?w=400', category: 'accessory' },
];

const CAT_PRODUCTS = [
  { id: 3, name: 'Whiskas Salmon', desc: 'Thức ăn mèo vị cá hồi', price: '120.000đ', rating: 4.7, image: 'https://images.unsplash.com/photo-1574158622682-e40e69881006?w=400', badge: 'Mới', category: 'food' },
  { id: 5, name: 'Catnip Toy Mouse', desc: 'Chuột đồ chơi có bạc hà mèo', price: '45.000đ', rating: 4.6, image: 'https://images.unsplash.com/photo-1545249390-6bdfa286032f?w=400', category: 'toy' },
  { id: 7, name: 'Salmon Treats', desc: 'Snack cá hồi cho mèo', price: '150.000đ', rating: 4.8, image: 'https://images.unsplash.com/photo-1518791841217-8f162f1e1131?w=400', category: 'food' },
  { id: 10, name: 'Cozy Cat Bed', desc: 'Ổ nằm êm ái cho mèo', price: '420.000đ', rating: 4.7, image: 'https://images.unsplash.com/photo-1541781774459-bb2af2f05b55?w=400', badge: 'Hot', category: 'accessory' },
  { id: 13, name: 'Scratching Post', desc: 'Cây cào móng cho mèo', price: '350.000đ', rating: 4.5, image: 'https://images.unsplash.com/photo-1592194996308-7b43878e84a6?w=400', category: 'accessory' },
  { id: 14, name: 'Cat Litter Premium', desc: 'Cát vệ sinh hạt hấp thụ', price: '220.000đ', rating: 4.4, image: 'https://images.unsplash.com/photo-1573865526739-10659fec78a5?w=400', category: 'health' },
];

// Pet types with Lucide icons (Dog and Cat alternatives)
const DogIcon = ({ size = 20, className = '' }: { size?: number; className?: string }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" className={className}>
    <path d="M4.5 9.5C3.4 9.5 2.5 10.4 2.5 11.5C2.5 12.6 3.4 13.5 4.5 13.5C5.6 13.5 6.5 12.6 6.5 11.5C6.5 10.4 5.6 9.5 4.5 9.5M19.5 9.5C18.4 9.5 17.5 10.4 17.5 11.5C17.5 12.6 18.4 13.5 19.5 13.5C20.6 13.5 21.5 12.6 21.5 11.5C21.5 10.4 20.6 9.5 19.5 9.5M12 4C8 4 5 7 5 11C5 13 5.5 14.5 6.5 16L8 18.5L9 21H10L10.5 18.5H13.5L14 21H15L16 18.5L17.5 16C18.5 14.5 19 13 19 11C19 7 16 4 12 4Z"/>
  </svg>
);

const CatIcon = ({ size = 20, className = '' }: { size?: number; className?: string }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" className={className}>
    <path d="M12 8L10.5 6.5L7 4L8 8L4.5 9.5L3 13L5.5 14.5L7 18L8 21L9.5 19L10.5 17.5L12 17.5L13.5 17.5L14.5 19L16 21L17 18L18.5 14.5L21 13L19.5 9.5L16 8L14.5 6.5L13 8H12M9.5 11.5C9.5 12.05 9.05 12.5 8.5 12.5C7.95 12.5 7.5 12.05 7.5 11.5C7.5 10.95 7.95 10.5 8.5 10.5C9.05 10.5 9.5 10.95 9.5 11.5M16.5 11.5C16.5 12.05 16.05 12.5 15.5 12.5C14.95 12.5 14.5 12.05 14.5 11.5C14.5 10.95 14.95 10.5 15.5 10.5C16.05 10.5 16.5 10.95 16.5 11.5M12 14.5C10.83 14.5 9.5 13.83 9.5 13H14.5C14.5 13.83 13.17 14.5 12 14.5Z"/>
  </svg>
);

const PET_TYPES = [
  { id: 'dog', label: 'Cho Chó', color: 'blue' },
  { id: 'cat', label: 'Cho Mèo', color: 'purple' },
];

interface Pet {
  id: number;
  name: string;
  type: 'dog' | 'cat';
  breed: string;
  age: string;
  weight: string;
  image: string;
}

// Load pets from localStorage or use mock
const loadPets = (): Pet[] => {
  const stored = localStorage.getItem('myPets');
  if (stored) {
    try {
      return JSON.parse(stored);
    } catch {
      return [];
    }
  }
  // Default pets
  return [
    { id: 1, name: 'Milo', type: 'dog', breed: 'Golden Retriever', age: '3 tuổi', weight: '25kg', image: 'https://images.unsplash.com/photo-1552053831-71594a27632a?w=400' },
    { id: 2, name: 'Luna', type: 'cat', breed: 'Maine Coon', age: '2 tuổi', weight: '6kg', image: 'https://images.unsplash.com/photo-1514888286974-6c03e2ca1dba?w=400' },
  ];
};

export function RecommendPage() {
  const [petType, setPetType] = useState<'dog' | 'cat'>('dog');
  const [pets, setPets] = useState<Pet[]>(loadPets);
  const [selectedPetId, setSelectedPetId] = useState<number | null>(pets[0]?.id || null);
  const [addedToCart, setAddedToCart] = useState<number[]>([]);

  const products = petType === 'dog' ? DOG_PRODUCTS : CAT_PRODUCTS;
  const selectedPet = pets.find(p => p.id === selectedPetId);

  // Filter products by selected pet type
  const filteredProducts = products.filter(p => p.category !== undefined);

  const handleAddToCart = (productId: number, productName: string) => {
    setAddedToCart(prev => [...prev, productId]);
    alert(`Đã thêm "${productName}" vào giỏ hàng!`);

    // Reset button after 2 seconds
    setTimeout(() => {
      setAddedToCart(prev => prev.filter(id => id !== productId));
    }, 2000);
  };

  const colorClasses = {
    blue: { bg: 'bg-blue-100', text: 'text-blue-600', ring: 'ring-blue-200' },
    purple: { bg: 'bg-purple-100', text: 'text-purple-600', ring: 'ring-purple-200' },
  };

  return (
    <div className="mx-auto max-w-[1200px] px-4 py-8 sm:px-6">
      {/* Breadcrumb */}
      <nav className="mb-6 flex items-center gap-2 text-sm">
        <Link to="/" className="text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)] transition-colors">Trang chủ</Link>
        <ChevronRight size={14} className="text-gray-300" />
        <span className="font-medium text-[var(--color-text-primary)]">Gợi ý sản phẩm</span>
      </nav>

      {/* Hero Section */}
      <div className="mb-8 text-center">
        <div className="mb-5 inline-flex h-16 w-16 items-center justify-center rounded-full bg-[var(--color-brand-tertiary)]/30">
          <Sparkles size={32} className="text-[var(--color-brand-secondary)]" />
        </div>
        <h1 className="font-[var(--font-friendly)] text-3xl font-extrabold text-[var(--color-text-primary)] sm:text-4xl">
          Gợi ý cho thú cưng của bạn
        </h1>
        <p className="mt-3 text-base text-[var(--color-text-secondary)] max-w-xl mx-auto">
          Chúng tôi chọn lọc những sản phẩm tốt nhất dựa trên loài và nhu cầu dinh dưỡng của thú cưng
        </p>
      </div>

      {/* My Pets Quick View */}
      {pets.length > 0 && (
        <div className="mb-6 rounded-xl bg-[var(--color-surface-sunken)] p-4">
          <div className="flex items-center gap-3 mb-3">
            <PawPrint size={18} className="text-[var(--color-brand-secondary)]" />
            <span className="font-semibold text-[var(--color-text-primary)]">Thú cưng của bạn</span>
            <Link to="/account#pets" className="ml-auto text-sm text-[var(--color-brand-secondary)] hover:underline flex items-center gap-1">
              Quản lý <ChevronRight size={14} />
            </Link>
          </div>
          <div className="flex gap-2 overflow-x-auto pb-1">
            {pets.map((pet) => (
              <button
                key={pet.id}
                onClick={() => {
                  setSelectedPetId(pet.id);
                  setPetType(pet.type);
                }}
                className={`flex items-center gap-2 rounded-full px-4 py-2 transition-all whitespace-nowrap ${
                  selectedPetId === pet.id
                    ? 'bg-[var(--color-brand-secondary)] text-white'
                    : 'bg-white text-[var(--color-text-primary)] hover:bg-[var(--color-brand-tertiary)]/20'
                }`}
              >
                <img src={pet.image} alt={pet.name} className="h-6 w-6 rounded-full object-cover" />
                <span className="text-sm font-medium">{pet.name}</span>
                {pet.type === 'dog' ? (
                  <DogIcon size={14} className={selectedPetId === pet.id ? 'text-white' : 'text-[var(--color-brand-secondary)]'} />
                ) : (
                  <CatIcon size={14} className={selectedPetId === pet.id ? 'text-white' : 'text-[var(--color-brand-secondary)]'} />
                )}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Pet Type Filter Tabs */}
      <div className="mb-8 flex justify-center gap-3">
        {PET_TYPES.map((type) => (
          <button
            key={type.id}
            onClick={() => setPetType(type.id as 'dog' | 'cat')}
            className={`group relative flex items-center gap-3 rounded-full px-8 py-3.5 font-bold transition-all duration-300 ${
              petType === type.id
                ? 'bg-[var(--color-brand-secondary)] text-white shadow-[var(--shadow-2)]'
                : 'bg-white text-[var(--color-text-primary)] border-2 border-[var(--color-border-default)] hover:border-[var(--color-brand-secondary)]'
            }`}
          >
            {type.id === 'dog' ? (
              <DogIcon size={20} className={petType === type.id ? 'text-white' : 'text-[var(--color-brand-secondary)]'} />
            ) : (
              <CatIcon size={20} className={petType === type.id ? 'text-white' : 'text-[var(--color-brand-secondary)]'} />
            )}
            <span>{type.label}</span>
            {petType === type.id && (
              <span className="absolute -bottom-1 left-1/2 -translate-x-1/2 w-0 h-0 border-l-[6px] border-l-transparent border-r-[6px] border-r-transparent border-t-[6px] border-t-[var(--color-brand-secondary)]" />
            )}
          </button>
        ))}
      </div>

      {/* Selected Pet Info */}
      {selectedPet && petType === selectedPet.type && (
        <div className="mb-6 flex items-center gap-4 rounded-xl bg-white p-4 shadow-[var(--shadow-1)]">
          <img src={selectedPet.image} alt={selectedPet.name} className="h-14 w-14 rounded-full object-cover" />
          <div>
            <div className="font-bold text-[var(--color-text-primary)]">{selectedPet.name}</div>
            <div className="flex flex-wrap gap-2 mt-1">
              <span className="rounded-full bg-[var(--color-surface-sunken)] px-2 py-0.5 text-xs font-medium text-[var(--color-text-secondary)]">
                {selectedPet.breed}
              </span>
              <span className="rounded-full bg-[var(--color-surface-sunken)] px-2 py-0.5 text-xs font-medium text-[var(--color-text-secondary)]">
                {selectedPet.age}
              </span>
              <span className="rounded-full bg-[var(--color-surface-sunken)] px-2 py-0.5 text-xs font-medium text-[var(--color-text-secondary)]">
                {selectedPet.weight}
              </span>
            </div>
          </div>
          <div className="ml-auto text-right">
            <div className="text-xs text-[var(--color-text-secondary)]">Sản phẩm gợi ý</div>
            <div className="font-bold text-[var(--color-brand-secondary)]">{filteredProducts.length} sản phẩm</div>
          </div>
        </div>
      )}

      {/* Products Grid */}
      <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
        {filteredProducts.map((product, index) => (
          <div
            key={product.id}
            className="group overflow-hidden rounded-xl bg-white shadow-[var(--shadow-1)] transition-all duration-300 hover:shadow-[var(--shadow-2)] hover:-translate-y-1"
            style={{ animationDelay: `${index * 50}ms` }}
          >
            <Link to={`/shop/${product.id}`} className="block">
              <div className="relative aspect-[4/3] overflow-hidden bg-[var(--color-surface-sunken)]">
                <img
                  src={product.image}
                  alt={product.name}
                  className="h-full w-full object-cover transition-transform duration-500 group-hover:scale-110"
                />
                {product.badge && (
                  <span className="absolute top-3 left-3 rounded-full bg-[var(--color-brand-secondary)] px-3 py-1 text-[11px] font-bold text-white shadow">
                    {product.badge}
                  </span>
                )}
                <button
                  onClick={(e) => {
                    e.preventDefault();
                    e.stopPropagation();
                  }}
                  className="absolute top-3 right-3 flex h-9 w-9 items-center justify-center rounded-full bg-white/90 text-gray-400 opacity-0 transition-all hover:text-red-500 group-hover:opacity-100"
                >
                  <Heart size={18} />
                </button>
              </div>
            </Link>
            <div className="p-4">
              <div className="mb-2 flex items-center gap-1">
                <Star size={14} className="fill-[var(--color-brand-primary)] text-[var(--color-brand-primary)]" />
                <span className="text-sm font-semibold text-[var(--color-text-secondary)]">{product.rating}</span>
              </div>
              <h3 className="font-bold text-[var(--color-text-primary)] text-[15px] leading-tight mb-1 group-hover:text-[var(--color-brand-secondary)] transition-colors">
                {product.name}
              </h3>
              <p className="text-sm text-[var(--color-text-secondary)] line-clamp-2 mb-3">{product.desc}</p>
              <div className="flex items-center justify-between">
                <span className="text-lg font-bold text-[var(--color-text-primary)]">{product.price}</span>
                <button
                  onClick={() => handleAddToCart(product.id, product.name)}
                  className={`flex items-center gap-1.5 rounded-lg px-3 py-2 text-[13px] font-bold transition-all active:scale-95 ${
                    addedToCart.includes(product.id)
                      ? 'bg-green-500 text-white'
                      : 'bg-[var(--color-brand-primary)] text-[var(--color-text-on-brand)] hover:bg-[var(--color-brand-primary-hover)]'
                  }`}
                >
                  <ShoppingCart size={14} />
                  {addedToCart.includes(product.id) ? 'Đã thêm!' : 'Thêm vào giỏ'}
                </button>
              </div>
            </div>
          </div>
        ))}
      </div>

      {/* Why These Products Section */}
      <div className="mt-12 rounded-2xl bg-gradient-to-r from-[var(--color-brand-tertiary)]/20 to-[var(--color-brand-primary)]/10 p-6 sm:p-8">
        <div className="flex flex-col sm:flex-row items-start sm:items-center gap-4">
          <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-[var(--color-brand-secondary)] text-white">
            <Package size={24} />
          </div>
          <div>
            <h3 className="font-bold text-[var(--color-text-primary)] text-lg mb-1">
              Tại sao chúng tôi gợi ý những sản phẩm này?
            </h3>
            <p className="text-[var(--color-text-secondary)] text-sm">
              Dựa trên loài và độ tuổi của thú cưng, chúng tôi chọn sản phẩm có thành phần dinh dưỡng và tính năng phù hợp nhất.
            </p>
          </div>
        </div>
      </div>

      {/* CTA Section */}
      <div className="mt-12 overflow-hidden rounded-2xl bg-gradient-to-br from-[var(--color-dark-gray,#3b2a1e)] to-[#2a1a10] p-8 text-center text-white">
        <h2 className="font-[var(--font-friendly)] text-2xl font-bold mb-2">
          Khám phá thêm sản phẩm
        </h2>
        <p className="mb-6 text-white/80 max-w-md mx-auto">
          Xem toàn bộ danh mục sản phẩm với hơn 500+ sản phẩm chất lượng cho thú cưng
        </p>
        <Link
          to={`/shop?petType=${petType}`}
          className="inline-flex items-center gap-2 rounded-full bg-[var(--color-brand-secondary)] px-8 py-3.5 font-bold text-white transition-all hover:bg-[var(--color-brand-tertiary)] hover:scale-105 active:scale-95"
        >
          <ShoppingBag size={20} />
          Xem tất cả sản phẩm
        </Link>
      </div>
    </div>
  );
}
