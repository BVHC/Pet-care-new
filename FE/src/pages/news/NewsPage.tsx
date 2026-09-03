import { useState } from 'react';
import { Calendar, User, ArrowRight } from 'lucide-react';

const ARTICLES = [
  { id: 1, title: 'Cách chăm sóc chó con cho người mới bắt đầu', category: 'Chăm sóc', author: 'Dr. Minh', date: '25/08/2026', image: 'https://images.unsplash.com/photo-1587300003388-59208cc962cb?w=600', excerpt: 'Nuôi chó con cần chú ý gì? Từ dinh dưỡng đến huấn luyện cơ bản dành cho những ai mới có thú cưng.' },
  { id: 2, title: '5 loại thực phẩm tốt cho mèo bạn nên biết', category: 'Dinh dưỡng', author: 'Dr. Lan', date: '23/08/2026', image: 'https://images.unsplash.com/photo-1514888286974-6c03e2ca1dba?w=600', excerpt: 'Khám phá những loại thực phẩm an toàn và bổ dưỡng cho mèo mà bạn có thể bổ sung vào chế độ ăn hàng ngày.' },
  { id: 3, title: 'Dấu hiệu nhận biết bệnh ở thú cưng sớm nhất', category: 'Sức khỏe', author: 'Dr. Tuấn', date: '20/08/2026', image: 'https://images.unsplash.com/photo-1543466835-00a7907e9de1?w=600', excerpt: 'Làm sao để nhận biết thú cưng đang bị bệnh? Những dấu hiệu cảnh báo mà chủ nuôi không nên bỏ qua.' },
  { id: 4, title: 'Hướng dẫn tắm cho mèo đúng cách', category: 'Chăm sóc', author: 'Hương', date: '18/08/2026', image: 'https://images.unsplash.com/photo-1573865526739-10659fec78a5?w=600', excerpt: 'Tắm cho mèo không phải lúc nào cũng dễ dàng. Bài viết này sẽ hướng dẫn bạn cách tắm mèo an toàn và hiệu quả.' },
  { id: 5, title: 'Tại sao thú cưng cần tiêm phòng đầy đủ?', category: 'Sức khỏe', author: 'Dr. Minh', date: '15/08/2026', image: 'https://images.unsplash.com/photo-1548199973-03cce0bbc87b?w=600', excerpt: 'Tiêm phòng là biện pháp quan trọng để bảo vệ thú cưng khỏi các bệnh nguy hiểm. Tìm hiểu lịch tiêm phòng cơ bản.' },
  { id: 6, title: 'Cách chọn thức ăn phù hợp cho chó theo độ tuổi', category: 'Dinh dưỡng', author: 'Dr. Lan', date: '12/08/2026', image: 'https://images.unsplash.com/photo-1587300003388-59208cc962cb?w=600', excerpt: 'Chó ở mỗi giai đoạn tuổi có nhu cầu dinh dưỡng khác nhau. Hãy cùng chúng tôi tìm hiểu cách chọn thức ăn phù hợp.' },
];

const CATEGORIES = ['Tất cả', 'Chăm sóc', 'Dinh dưỡng', 'Sức khỏe', 'Huấn luyện'];

export function NewsPage() {
  const [selectedCategory, setSelectedCategory] = useState('Tất cả');

  const filteredArticles = selectedCategory === 'Tất cả'
    ? ARTICLES
    : ARTICLES.filter(a => a.category === selectedCategory);

  return (
    <div className="mx-auto max-w-[1200px] px-4 py-8 sm:px-6">
      {/* Hero */}
      <div className="mb-12 text-center">
        <h1 className="font-[var(--font-friendly)] text-4xl font-extrabold text-gray-900">
          Tin tức & Bài viết
        </h1>
        <p className="mt-4 text-lg text-gray-600">
          Cập nhật những kiến thức hữu ích về chăm sóc thú cưng
        </p>
      </div>

      {/* Categories */}
      <div className="mb-8 flex flex-wrap justify-center gap-2">
        {CATEGORIES.map((cat) => (
          <button
            key={cat}
            onClick={() => setSelectedCategory(cat)}
            className={`rounded-full px-5 py-2 text-sm font-semibold transition-colors ${
              selectedCategory === cat
                ? 'bg-[#843122] text-white'
                : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
            }`}
          >
            {cat}
          </button>
        ))}
      </div>

      {/* Featured Article */}
      <div className="mb-12 overflow-hidden rounded-2xl border border-gray-200 bg-white shadow-sm">
        <div className="grid lg:grid-cols-2">
          <div className="aspect-[4/3] bg-gray-100 lg:aspect-auto">
            <img src={ARTICLES[0].image} alt="" className="h-full w-full object-cover" />
          </div>
          <div className="p-8 flex flex-col justify-center">
            <span className="inline-block rounded-full bg-[#843122]/10 px-3 py-1 text-xs font-bold text-[#843122]">
              {ARTICLES[0].category}
            </span>
            <h2 className="mt-4 font-[var(--font-friendly)] text-2xl font-bold text-gray-900">
              {ARTICLES[0].title}
            </h2>
            <p className="mt-3 text-gray-600">{ARTICLES[0].excerpt}</p>
            <div className="mt-4 flex items-center gap-4 text-sm text-gray-500">
              <span className="flex items-center gap-1"><User size={14} /> {ARTICLES[0].author}</span>
              <span className="flex items-center gap-1"><Calendar size={14} /> {ARTICLES[0].date}</span>
            </div>
            <button className="mt-6 flex items-center gap-2 font-bold text-[#843122] hover:underline">
              Đọc tiếp <ArrowRight size={18} />
            </button>
          </div>
        </div>
      </div>

      {/* Articles Grid */}
      <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
        {filteredArticles.slice(1).map((article) => (
          <article key={article.id} className="overflow-hidden rounded-xl border border-gray-200 bg-white shadow-sm hover:shadow-lg transition-shadow">
            <div className="aspect-[4/3] bg-gray-100">
              <img src={article.image} alt="" className="h-full w-full object-cover" />
            </div>
            <div className="p-5">
              <span className="inline-block rounded-full bg-[#843122]/10 px-2 py-0.5 text-xs font-bold text-[#843122]">
                {article.category}
              </span>
              <h3 className="mt-3 font-bold text-gray-900 line-clamp-2">{article.title}</h3>
              <p className="mt-2 text-sm text-gray-600 line-clamp-2">{article.excerpt}</p>
              <div className="mt-4 flex items-center justify-between text-sm text-gray-500">
                <span className="flex items-center gap-1"><User size={12} /> {article.author}</span>
                <span className="flex items-center gap-1"><Calendar size={12} /> {article.date}</span>
              </div>
            </div>
          </article>
        ))}
      </div>
    </div>
  );
}
