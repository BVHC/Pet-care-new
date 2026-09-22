import { useState } from 'react'
import { ArrowRight, Calendar, User } from 'lucide-react'
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero'

/* ================================================================
   Data.
   ================================================================ */

interface Article {
  id: number
  title: string
  category: string
  author: string
  date: string
  image: string
  excerpt: string
}

const ARTICLES: Article[] = [
  {
    id: 1,
    title: 'Cách chăm sóc chó con cho người mới bắt đầu',
    category: 'Chăm sóc',
    author: 'Dr. Minh',
    date: '25/08/2026',
    image: 'https://images.unsplash.com/photo-1587300003388-59208cc962cb?w=600',
    excerpt: 'Nuôi chó con cần chú ý gì? Từ dinh dưỡng đến huấn luyện cơ bản dành cho những ai mới có thú cưng.',
  },
  {
    id: 2,
    title: '5 loại thực phẩm tốt cho mèo bạn nên biết',
    category: 'Dinh dưỡng',
    author: 'Dr. Lan',
    date: '23/08/2026',
    image: 'https://images.unsplash.com/photo-1514888286974-6c03e2ca1dba?w=600',
    excerpt: 'Khám phá những loại thực phẩm an toàn và bổ dưỡng cho mèo mà bạn có thể bổ sung vào chế độ ăn hàng ngày.',
  },
  {
    id: 3,
    title: 'Dấu hiệu nhận biết bệnh ở thú cưng sớm nhất',
    category: 'Sức khỏe',
    author: 'Dr. Tuấn',
    date: '20/08/2026',
    image: 'https://images.unsplash.com/photo-1543466835-00a7907e9de1?w=600',
    excerpt: 'Làm sao để nhận biết thú cưng đang bị bệnh? Những dấu hiệu cảnh báo mà chủ nuôi không nên bỏ qua.',
  },
  {
    id: 4,
    title: 'Hướng dẫn tắm cho mèo đúng cách',
    category: 'Chăm sóc',
    author: 'Hương',
    date: '18/08/2026',
    image: 'https://images.unsplash.com/photo-1573865526739-10659fec78a5?w=600',
    excerpt: 'Tắm cho mèo không phải lúc nào cũng dễ dàng. Bài viết này sẽ hướng dẫn bạn cách tắm mèo an toàn và hiệu quả.',
  },
  {
    id: 5,
    title: 'Tại sao thú cưng cần tiêm phòng đầy đủ?',
    category: 'Sức khỏe',
    author: 'Dr. Minh',
    date: '15/08/2026',
    image: 'https://images.unsplash.com/photo-1548199973-03cce0bbc87b?w=600',
    excerpt: 'Tiêm phòng là biện pháp quan trọng để bảo vệ thú cưng khỏi các bệnh nguy hiểm. Tìm hiểu lịch tiêm phòng cơ bản.',
  },
  {
    id: 6,
    title: 'Cách chọn thức ăn phù hợp cho chó theo độ tuổi',
    category: 'Dinh dưỡng',
    author: 'Dr. Lan',
    date: '12/08/2026',
    image: 'https://images.unsplash.com/photo-1587300003388-59208cc962cb?w=600',
    excerpt: 'Chó ở mỗi giai đoạn tuổi có nhu cầu dinh dưỡng khác nhau. Hãy cùng chúng tôi tìm hiểu cách chọn thức ăn phù hợp.',
  },
]

const CATEGORIES = ['Tất cả', 'Chăm sóc', 'Dinh dưỡng', 'Sức khỏe', 'Huấn luyện']

/* ================================================================
   Main.
   ================================================================ */

export function NewsPage() {
  const [selectedCategory, setSelectedCategory] = useState('Tất cả')

  const filteredArticles =
    selectedCategory === 'Tất cả'
      ? ARTICLES
      : ARTICLES.filter((a) => a.category === selectedCategory)

  const featured = ARTICLES[0]
  const rest = filteredArticles.slice(1)

  return (
    <div className="bg-(--color-surface-page) pb-24">
      <CommonPageHero
        eyebrow="Tin tức & Bài viết"
        title="Kiến thức chăm sóc thú cưng"
        subtitle="Cập nhật những kiến thức hữu ích về chăm sóc, dinh dưỡng, sức khỏe và huấn luyện thú cưng từ đội ngũ bác sĩ và chuyên gia của PetCare."
        breadcrumbs={[{ label: 'Tin tức' }]}
      />

      {/* Categories */}
      <section className="mx-auto max-w-[1200px] px-5 pt-12 sm:px-8">
        <div className="mb-8 flex flex-wrap justify-center gap-2">
          {CATEGORIES.map((cat) => (
            <button
              key={cat}
              onClick={() => setSelectedCategory(cat)}
              className={`rounded-full px-5 py-2 text-[13.5px] font-semibold transition-all duration-200 ${
                selectedCategory === cat
                  ? 'bg-accent text-white shadow-[0_4px_12px_-6px_rgba(164,51,36,0.7)]'
                  : 'bg-(--color-surface-card) text-(--color-text-primary) hover:bg-(--color-accent-soft) hover:text-accent'
              }`}
            >
              {cat}
            </button>
          ))}
        </div>

        {/* Featured */}
        <article className="mb-10 grid overflow-hidden rounded-3xl border border-(--color-border-default) bg-(--color-surface-card) shadow-[0_1px_2px_rgba(56,36,23,0.06),0_8px_18px_-14px_rgba(56,36,23,0.4)] lg:grid-cols-2">
          <div className="aspect-4/3 bg-(--color-surface-1) lg:aspect-auto">
            <img src={featured.image} alt="" className="h-full w-full object-cover" />
          </div>
          <div className="flex flex-col justify-center p-8">
            <span className="inline-block w-fit rounded-full bg-(--color-accent-soft) px-3 py-1 text-[11.5px] font-bold uppercase tracking-wide text-accent">
              {featured.category} · Nổi bật
            </span>
            <h2 className="mt-4 font-friendly text-[26px] font-extrabold leading-tight text-(--color-text-primary)">
              {featured.title}
            </h2>
            <p className="mt-3 text-[14px] leading-relaxed text-(--color-text-secondary)">
              {featured.excerpt}
            </p>
            <div className="mt-4 flex items-center gap-4 text-[12.5px] text-(--color-text-secondary)">
              <span className="flex items-center gap-1">
                <User size={13} /> {featured.author}
              </span>
              <span className="flex items-center gap-1">
                <Calendar size={13} /> {featured.date}
              </span>
            </div>
            <button className="mt-6 inline-flex w-fit items-center gap-2 text-[13.5px] font-bold text-accent transition-colors hover:text-(--color-accent-hover)">
              Đọc tiếp <ArrowRight size={16} />
            </button>
          </div>
        </article>

        {/* Articles grid */}
        <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
          {rest.map((article) => (
            <article
              key={article.id}
              className="group overflow-hidden rounded-2xl border border-(--color-border-default) bg-(--color-surface-card) shadow-[0_1px_2px_rgba(56,36,23,0.06),0_4px_12px_-8px_rgba(56,36,23,0.4)] transition-all duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] hover:-translate-y-0.5 hover:shadow-[0_2px_4px_rgba(56,36,23,0.07),0_12px_28px_-16px_rgba(164,51,36,0.4)]"
            >
              <div className="aspect-4/3 overflow-hidden bg-(--color-surface-1)">
                <img
                  src={article.image}
                  alt=""
                  className="h-full w-full object-cover transition-transform duration-500 ease-[cubic-bezier(0.32,0.72,0,1)] group-hover:scale-105"
                />
              </div>
              <div className="p-5">
                <span className="inline-block rounded-full bg-(--color-accent-soft) px-2.5 py-0.5 text-[11px] font-bold text-accent">
                  {article.category}
                </span>
                <h3 className="mt-3 line-clamp-2 text-[15px] font-bold text-(--color-text-primary) transition-colors group-hover:text-accent">
                  {article.title}
                </h3>
                <p className="mt-2 line-clamp-2 text-[13px] text-(--color-text-secondary)">
                  {article.excerpt}
                </p>
                <div className="mt-4 flex items-center justify-between text-[12px] text-(--color-text-secondary)">
                  <span className="flex items-center gap-1">
                    <User size={12} /> {article.author}
                  </span>
                  <span className="flex items-center gap-1">
                    <Calendar size={12} /> {article.date}
                  </span>
                </div>
              </div>
            </article>
          ))}
        </div>

        {rest.length === 0 && (
          <div className="rounded-2xl border border-dashed border-(--color-border-default) bg-(--color-surface-card) p-12 text-center text-(--color-text-secondary)">
            Chưa có bài viết trong chủ đề này.
          </div>
        )}
      </section>
    </div>
  )
}
