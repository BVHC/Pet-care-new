import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Minus, Plus, ShoppingCart, Star, Truck, ShieldCheck, RotateCcw } from 'lucide-react';
import { toast } from 'sonner';

const MOCK_PRODUCTS: Record<number, {
  name: string;
  category: string;
  price: number;
  originalPrice?: number;
  rating: number;
  reviews: number;
  stock: number;
  image: string;
  images: string[];
  description: string;
  petType: string;
}> = {
  1: { name: 'Royal Canin Adult', category: 'Thức ăn', price: 450000, originalPrice: 520000, rating: 4.8, reviews: 234, stock: 50, image: '/imgs/prod1.jpg', images: ['/imgs/prod1.jpg', '/imgs/prod2.jpg'], description: 'Thức ăn hạt cho chó trưởng thành, cung cấp đầy đủ dinh dưỡng cần thiết.', petType: 'dog' },
  2: { name: 'Pedigree Chicken', category: 'Thức ăn', price: 280000, rating: 4.5, reviews: 156, stock: 30, image: '/imgs/prod2.jpg', images: ['/imgs/prod2.jpg'], description: 'Thức ăn chó vị gà, giàu protein và vitamin.', petType: 'dog' },
  3: { name: 'Whiskas Salmon', category: 'Thức ăn', price: 120000, rating: 4.7, reviews: 189, stock: 45, image: '/imgs/prod3.jpg', images: ['/imgs/prod3.jpg'], description: 'Thức ăn mèo vị cá hồi, giúp lông mượt.', petType: 'cat' },
  4: { name: 'Interactive Ball', category: 'Đồ chơi', price: 89000, rating: 4.3, reviews: 98, stock: 20, image: '/imgs/prod4.jpg', images: ['/imgs/prod4.jpg'], description: 'Bóng tương tác cho chó, phát sáng và phát nhạc.', petType: 'dog' },
  5: { name: 'Catnip Toy Mouse', category: 'Đồ chơi', price: 45000, rating: 4.6, reviews: 145, stock: 60, image: '/imgs/prod5.jpg', images: ['/imgs/prod5.jpg'], description: 'Chuột đồ chơi có bạc hà mèo, an toàn cho mèo.', petType: 'cat' },
};

function formatVnd(price: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 }).format(price);
}

export function ProductDetailPage() {
  const { id } = useParams<{ id: string }>();
  const productId = id ? parseInt(id) : 1;
  const product = MOCK_PRODUCTS[productId] || MOCK_PRODUCTS[1];
  const [qty, setQty] = useState(1);
  const [activeImg, setActiveImg] = useState(0);

  const discount = product.originalPrice ? Math.round((1 - product.price / product.originalPrice) * 100) : 0;

  const handleAddToCart = () => {
    toast.success(`Đã thêm ${product.name} vào giỏ hàng`);
  };

  return (
    <div className="mx-auto max-w-[1200px] px-4 py-8 sm:px-6">
      <nav className="mb-4 flex flex-wrap items-center gap-2 text-sm text-gray-500">
        <Link to="/" className="hover:text-[#843122]">Trang chủ</Link>
        <span>/</span>
        <Link to="/shop" className="hover:text-[#843122]">Danh mục sản phẩm</Link>
        <span>/</span>
        <span>{product.category}</span>
        <span>/</span>
        <span className="font-semibold text-gray-900">{product.name}</span>
      </nav>

      <div className="grid gap-8 lg:grid-cols-2">
        {/* Hình ảnh */}
        <div>
          <div className="relative aspect-square overflow-hidden rounded-xl bg-gray-100">
            <img src={product.images[activeImg] || product.image} alt={product.name} className="h-full w-full object-cover" />
            {discount > 0 && (
              <span className="absolute top-4 left-4 rounded-full bg-red-500 px-3 py-1 text-sm font-bold text-white">
                -{discount}%
              </span>
            )}
          </div>
          <div className="mt-4 flex gap-3">
            {product.images.map((img, i) => (
              <button
                key={i}
                onClick={() => setActiveImg(i)}
                className={`h-20 w-20 overflow-hidden rounded-lg border-2 ${activeImg === i ? 'border-[#843122]' : 'border-transparent'}`}
              >
                <img src={img} alt="" className="h-full w-full object-cover" />
              </button>
            ))}
          </div>
        </div>

        {/* Thông tin */}
        <div>
          <div className="text-sm text-gray-500">{product.category} · Cho {product.petType === 'dog' ? 'Chó' : 'Mèo'}</div>
          <h1 className="mt-2 font-[var(--font-friendly)] text-3xl font-bold text-gray-900">{product.name}</h1>

          <div className="mt-3 flex items-center gap-2">
            <div className="flex gap-0.5">
              {[1,2,3,4,5].map(i => (
                <Star key={i} size={16} className={i <= Math.round(product.rating) ? 'fill-amber-400 text-amber-400' : 'text-gray-300'} />
              ))}
            </div>
            <span className="text-sm text-gray-600">{product.rating} ({product.reviews} đánh giá)</span>
          </div>

          <div className="mt-6 flex items-end gap-3">
            <span className="font-[var(--font-friendly)] text-3xl font-bold text-[#843122]">{formatVnd(product.price)}</span>
            {product.originalPrice && (
              <span className="text-lg text-gray-400 line-through">{formatVnd(product.originalPrice)}</span>
            )}
          </div>

          <p className="mt-6 text-gray-600 leading-relaxed">{product.description}</p>

          {/* Số lượng */}
          <div className="mt-6">
            <span className="text-sm font-semibold text-gray-900">Số lượng:</span>
            <div className="mt-2 flex items-center gap-4">
              <div className="flex items-center rounded-lg border border-gray-200">
                <button onClick={() => setQty(Math.max(1, qty - 1))} className="p-3 hover:bg-gray-100"><Minus size={16} /></button>
                <span className="w-12 text-center font-semibold">{qty}</span>
                <button onClick={() => setQty(Math.min(product.stock, qty + 1))} className="p-3 hover:bg-gray-100"><Plus size={16} /></button>
              </div>
              <span className="text-sm text-gray-500">{product.stock} sản phẩm có sẵn</span>
            </div>
          </div>

          {/* Nút */}
          <div className="mt-6 flex gap-3">
            <button
              onClick={handleAddToCart}
              className="flex-1 flex items-center justify-center gap-2 rounded-lg bg-[#843122] py-3 font-bold text-white hover:bg-[#6a2517] transition-colors"
            >
              <ShoppingCart size={20} /> Thêm vào giỏ
            </button>
            <button className="flex-1 rounded-lg border-2 border-[#843122] py-3 font-bold text-[#843122] hover:bg-amber-50 transition-colors">
              Mua ngay
            </button>
          </div>

          {/* Ưu đãi */}
          <div className="mt-8 space-y-3">
            <div className="flex items-center gap-3 text-sm text-gray-600">
              <Truck size={20} className="text-green-600" /> Miễn phí vận chuyển cho đơn từ 300K
            </div>
            <div className="flex items-center gap-3 text-sm text-gray-600">
              <ShieldCheck size={20} className="text-blue-600" /> Bảo hành chính hãng 12 tháng
            </div>
            <div className="flex items-center gap-3 text-sm text-gray-600">
              <RotateCcw size={20} className="text-purple-600" /> Đổi trả trong 7 ngày
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
