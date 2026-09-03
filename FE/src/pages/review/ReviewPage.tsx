import { useState } from 'react';
import { Star, ThumbsUp, } from 'lucide-react';

const REVIEWS = [
  { id: 1, author: 'Nguyễn Thị Bích', avatar: 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=100', product: 'Royal Canin Adult', rating: 5, date: '25/08/2026', content: 'Sản phẩm rất tốt! Chó nhà tôi rất thích ăn và có bộ lông bóng mượt hơn sau 1 tháng sử dụng. Sẽ tiếp tục mua.', helpful: 24, images: ['/imgs/prod1.jpg'] },
  { id: 2, author: 'Trần Văn Minh', avatar: 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=100', product: 'Whiskas Salmon', rating: 4, date: '23/08/2026', content: 'Mèo mình ăn rất ngon miệng. Giao hàng nhanh, đóng gói cẩn thận. Giá cả hợp lý.', helpful: 15 },
  { id: 3, author: 'Lê Hoàng Mai', avatar: 'https://images.unsplash.com/photo-1438761681033-6461ffad8d80?w=100', product: 'Interactive Ball', rating: 5, date: '20/08/2026', content: 'Bóng phát sáng rất đẹp, chó mình chơi cả ngày không chán. Chất lượng tốt, bền.', helpful: 32 },
  { id: 4, author: 'Phạm Đức Anh', avatar: 'https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=100', product: 'Cozy Cat Bed', rating: 4, date: '18/08/2026', content: 'Ổ nằm rất êm, mèo mình nằm ngủ cả ngày. Màu sắc đẹp như hình. Giao hàng nhanh.', helpful: 8 },
  { id: 5, author: 'Đặng Thu Hà', avatar: 'https://images.unsplash.com/photo-1544005313-94ddf0286df2?w=100', product: 'Dental Chew', rating: 5, date: '15/08/2026', content: 'Răng chó mình trắng hơn rõ rệt sau khi dùng. Mùi thơm, chó thích. Sẽ mua lại.', helpful: 19 },
];

export function ReviewPage() {
  const [reviews] = useState(REVIEWS);
  const [filterRating, setFilterRating] = useState<number | null>(null);

  const filteredReviews = filterRating
    ? reviews.filter(r => r.rating === filterRating)
    : reviews;

  const avgRating = (reviews.reduce((sum, r) => sum + r.rating, 0) / reviews.length).toFixed(1);

  return (
    <div className="mx-auto max-w-[1000px] px-4 py-8 sm:px-6">
      <h1 className="mb-8 font-[var(--font-friendly)] text-3xl font-bold text-gray-900">
        Đánh giá sản phẩm
      </h1>

      {/* Rating Summary */}
      <div className="mb-8 flex flex-wrap items-center gap-8 rounded-xl border border-gray-200 bg-white p-6">
        <div className="text-center">
          <div className="text-5xl font-bold text-gray-900">{avgRating}</div>
          <div className="mt-2 flex gap-0.5 justify-center">
            {[1,2,3,4,5].map(i => (
              <Star key={i} size={20} className={i <= Math.round(Number(avgRating)) ? 'fill-amber-400 text-amber-400' : 'text-gray-300'} />
            ))}
          </div>
          <div className="mt-1 text-sm text-gray-500">{reviews.length} đánh giá</div>
        </div>
        <div className="flex-1">
          <div className="space-y-2">
            {[5,4,3,2,1].map(rating => {
              const count = reviews.filter(r => r.rating === rating).length;
              const percent = (count / reviews.length) * 100;
              return (
                <div key={rating} className="flex items-center gap-3">
                  <span className="flex items-center gap-1 text-sm w-12">
                    {rating} <Star size={14} className="fill-amber-400 text-amber-400" />
                  </span>
                  <div className="flex-1 h-2 bg-gray-100 rounded-full overflow-hidden">
                    <div className="h-full bg-amber-400 rounded-full" style={{ width: `${percent}%` }} />
                  </div>
                  <span className="text-sm text-gray-500 w-8">{count}</span>
                </div>
              );
            })}
          </div>
        </div>
      </div>

      {/* Filter */}
      <div className="mb-6 flex flex-wrap gap-2">
        <button
          onClick={() => setFilterRating(null)}
          className={`rounded-full px-4 py-2 text-sm font-semibold transition-colors ${
            filterRating === null ? 'bg-[#843122] text-white' : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
          }`}
        >
          Tất cả
        </button>
        {[5,4,3,2,1].map(rating => (
          <button
            key={rating}
            onClick={() => setFilterRating(rating)}
            className={`rounded-full px-4 py-2 text-sm font-semibold transition-colors ${
              filterRating === rating ? 'bg-[#843122] text-white' : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
            }`}
          >
            {rating} ★ ({reviews.filter(r => r.rating === rating).length})
          </button>
        ))}
      </div>

      {/* Reviews List */}
      <div className="space-y-4">
        {filteredReviews.map(review => (
          <div key={review.id} className="rounded-xl border border-gray-200 bg-white p-6">
            <div className="flex items-start gap-4">
              <img src={review.avatar} alt={review.author} className="h-12 w-12 rounded-full object-cover" />
              <div className="flex-1">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div>
                    <div className="font-semibold text-gray-900">{review.author}</div>
                    <div className="flex items-center gap-2 mt-1">
                      <div className="flex gap-0.5">
                        {[1,2,3,4,5].map(i => (
                          <Star key={i} size={14} className={i <= review.rating ? 'fill-amber-400 text-amber-400' : 'text-gray-300'} />
                        ))}
                      </div>
                      <span className="text-sm text-gray-500">· {review.date}</span>
                    </div>
                  </div>
                  <span className="rounded-full bg-gray-100 px-3 py-1 text-xs font-semibold text-gray-600">
                    {review.product}
                  </span>
                </div>
                <p className="mt-3 text-gray-600">{review.content}</p>
                {review.images && (
                  <div className="mt-3 flex gap-2">
                    {review.images.map((img, i) => (
                      <img key={i} src={img} alt="" className="h-16 w-16 rounded-lg object-cover" />
                    ))}
                  </div>
                )}
                <div className="mt-4 flex items-center gap-4">
                  <button className="flex items-center gap-1 text-sm text-gray-500 hover:text-[#843122] transition-colors">
                    <ThumbsUp size={14} /> Hữu ích ({review.helpful})
                  </button>
                </div>
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
