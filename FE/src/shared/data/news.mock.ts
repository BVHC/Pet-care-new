export interface NewsArticle {
  id: string;
  title: string;
  category: string;
  author: string;
  date: string;
  image: string;
  excerpt: string;
}

export const NEWS_ARTICLES: NewsArticle[] = [
  { id: 'n1', title: 'Royal Canin vs Pedigree: nên chọn hãng nào cho chó cưng?', category: 'Pet', author: 'Dr. Minh Thu', date: '28th Jul, 2026', image: '/imgs/prod1.jpg', excerpt: 'Cả hai đều là thương hiệu lớn, nhưng công thức và nhu cầu của từng bé chó sẽ quyết định lựa chọn phù hợp nhất.' },
  { id: 'n2', title: 'Mèo bỏ ăn 3 ngày liên tiếp — khi nào cần đưa đi cấp cứu?', category: 'Medical', author: 'Dr. Lan Hương', date: '25th Jul, 2026', image: '/imgs/vet-counter.jpg', excerpt: 'Mèo không ăn sau 48 giờ có thể gây tổn thương gan nghiêm trọng. Đây là những dấu hiệu cần nhận biết sớm.' },
  { id: 'n3', title: 'Cách tôi dạy bé cún đi vệ sinh đúng chỗ trong 2 tuần', category: 'Pet', author: 'Thu Trang', date: '22nd Jul, 2026', image: '/imgs/blog_post03-CuqvOIWv.jpg', excerpt: 'Không phải "thả rông" để chó tự học — đây là phương pháp có kế hoạch giúp bé Bông không còn "tai nạn" sau 14 ngày.' },
  { id: 'n4', title: 'Vaccine 5 bệnh cho chó con: lịch tiêm và những điều cần biết', category: 'Medical', author: 'Dr. Minh Thu', date: '18th Jul, 2026', image: '/imgs/counter_img-Bz2AlneD.jpg', excerpt: 'Vaccine là "lá chắn" quan trọng nhất cho chó con. Nhiều chủ nuôi bỏ lỡ mũi tiêm nhắc lại vì không biết lịch trình cụ thể.' },
  { id: 'n5', title: 'Đệm memory foam cho chó có thực sự cần thiết?', category: 'Care', author: 'Minh Tuấn', date: '15th Jul, 2026', image: '/imgs/prod2.jpg', excerpt: 'Bỏ ra 800K cho đệm chó — nghe có vẻ phí, nhưng sau 6 tháng mình hiểu tại sao bé Mập ngủ ngon đến thế.' },
  { id: 'n6', title: 'Mèo cào sofa: 5 cách ngăn chặn mà không cần "bỏ rơi" cả căn phòng', category: 'Pet', author: 'Phương Linh', date: '12th Jul, 2026', image: '/imgs/team4.jpg', excerpt: 'Mèo cào không phải vì "hư" hay "điên" — đây là bản năng đánh dấu lãnh thổ và kéo móng.' },
];

export function filterNewsByCategory(items: NewsArticle[], category: string): NewsArticle[] {
  if (category === 'Tất cả') return items;
  return items.filter((a) => a.category === category);
}
