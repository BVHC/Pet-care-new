import { useState } from 'react';
import { Link } from 'react-router-dom';
import { PawPrint, ShoppingBag, Star, } from 'lucide-react';

const DOG_PRODUCTS = [
  { id: 1, name: 'Royal Canin Adult', desc: 'Thức ăn hạt dinh dưỡng cao cấp cho chó trưởng thành', price: '450.000đ', rating: 4.8, image: '/imgs/prod1.jpg' },
  { id: 2, name: 'Pedigree Chicken', desc: 'Thức ăn chó vị gà, giàu protein', price: '280.000đ', rating: 4.5, image: '/imgs/prod2.jpg' },
  { id: 4, name: 'Interactive Ball', desc: 'Bóng tương tác phát sáng cho chó', price: '89.000đ', rating: 4.3, image: '/imgs/prod4.jpg' },
  { id: 8, name: 'Dental Chew', desc: 'Xương làm sạch răng cho chó', price: '95.000đ', rating: 4.4, image: '/imgs/prod8.jpg' },
];

const CAT_PRODUCTS = [
  { id: 3, name: 'Whiskas Salmon', desc: 'Thức ăn mèo vị cá hồi', price: '120.000đ', rating: 4.7, image: '/imgs/prod3.jpg' },
  { id: 5, name: 'Catnip Toy Mouse', desc: 'Chuột đồ chơi có bạc hà mèo', price: '45.000đ', rating: 4.6, image: '/imgs/prod5.jpg' },
  { id: 7, name: 'Salmon Treats', desc: 'Snack cá hồi cho mèo', price: '150.000đ', rating: 4.8, image: '/imgs/prod7.jpg' },
  { id: 10, name: 'Cozy Cat Bed', desc: 'Ổ nằm êm ái cho mèo', price: '420.000đ', rating: 4.7, image: '/imgs/prod2.jpg' },
];

export function RecommendPage() {
  const [petType, setPetType] = useState<'dog' | 'cat'>('dog');
  const products = petType === 'dog' ? DOG_PRODUCTS : CAT_PRODUCTS;

  return (
    <div className="mx-auto max-w-[1200px] px-4 py-8 sm:px-6">
      {/* Hero */}
      <div className="mb-12 text-center">
        <div className="mb-4 inline-flex h-16 w-16 items-center justify-center rounded-full bg-[#843122]/10">
          <PawPrint size={32} className="text-[#843122]" />
        </div>
        <h1 className="font-[var(--font-friendly)] text-4xl font-extrabold text-gray-900">
          Gợi ý cho thú cưng của bạn
        </h1>
        <p className="mt-4 text-lg text-gray-600">
          Chúng tôi chọn lọc những sản phẩm tốt nhất phù hợp với {petType === 'dog' ? 'chó' : 'mèo'} của bạn
        </p>
      </div>

      {/* Pet Type Filter */}
      <div className="mb-8 flex justify-center gap-4">
        <button
          onClick={() => setPetType('dog')}
          className={`flex items-center gap-2 rounded-full px-8 py-3 font-bold transition-colors ${
            petType === 'dog'
              ? 'bg-[#843122] text-white'
              : 'border-2 border-[#843122] text-[#843122] hover:bg-[#843122] hover:text-white'
          }`}
        >
          🐕 Cho Chó
        </button>
        <button
          onClick={() => setPetType('cat')}
          className={`flex items-center gap-2 rounded-full px-8 py-3 font-bold transition-colors ${
            petType === 'cat'
              ? 'bg-[#843122] text-white'
              : 'border-2 border-[#843122] text-[#843122] hover:bg-[#843122] hover:text-white'
          }`}
        >
          🐱 Cho Mèo
        </button>
      </div>

      {/* Products Grid */}
      <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
        {products.map((product) => (
          <div key={product.id} className="overflow-hidden rounded-xl border border-gray-200 bg-white shadow-sm hover:shadow-lg transition-shadow">
            <Link to={`/shop/${product.id}`}>
              <div className="aspect-[4/3] bg-gray-100">
                <img src={product.image} alt={product.name} className="h-full w-full object-cover" />
              </div>
            </Link>
            <div className="p-4">
              <div className="flex items-center gap-1 text-sm text-gray-500 mb-1">
                <Star size={14} className="fill-amber-400 text-amber-400" />
                {product.rating}
              </div>
              <h3 className="font-bold text-gray-900">{product.name}</h3>
              <p className="mt-1 text-sm text-gray-500 line-clamp-2">{product.desc}</p>
              <div className="mt-3 flex items-center justify-between">
                <span className="font-bold text-[#843122]">{product.price}</span>
                <button className="rounded-lg bg-[#843122] px-3 py-1.5 text-sm font-bold text-white hover:bg-[#6a2517] transition-colors">
                  Thêm vào giỏ
                </button>
              </div>
            </div>
          </div>
        ))}
      </div>

      {/* CTA */}
      <div className="mt-16 rounded-2xl bg-gradient-to-r from-[#843122] to-[#6a2517] p-8 text-center text-white">
        <h2 className="font-[var(--font-friendly)] text-2xl font-bold">
          Bạn muốn xem thêm sản phẩm?
        </h2>
        <p className="mt-2 opacity-90">Khám phá toàn bộ danh mục sản phẩm dành cho {petType === 'dog' ? 'chó' : 'mèo'}</p>
        <Link
          to={`/shop?petType=${petType}`}
          className="mt-6 inline-flex items-center gap-2 rounded-full bg-white px-8 py-3 font-bold text-[#843122] hover:bg-gray-100 transition-colors"
        >
          <ShoppingBag size={20} /> Xem tất cả sản phẩm
        </Link>
      </div>
    </div>
  );
}
