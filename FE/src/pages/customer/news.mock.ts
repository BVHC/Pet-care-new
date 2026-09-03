export interface NewsArticle {
  id: string;
  title: string;
  category: string;
  author: string;
  date: string;
  image: string;
  excerpt: string;
  body: string;
}

// "Tất cả" luôn đứng đầu — dùng làm giá trị mặc định của filter.
export const NEWS_CATEGORIES = ['Tất cả', 'Pet', 'Care', 'Medical', 'Pet Care'];

// Ảnh tái dùng từ public/petpet (chỉ ~9 ảnh hợp chủ đề, xoay vòng cho 15 bài).
const IMG = {
  blog1: '/imgs/blog_post01-Ic8URbXk.jpg',
  blog2: '/imgs/blog_post02-BG3_aPUh.jpg',
  blog3: '/imgs/blog_post03-CuqvOIWv.jpg',
  vet: '/imgs/vet-counter.jpg',
  counter: '/imgs/counter_img-Bz2AlneD.jpg',
  team1: '/imgs/team1.jpg',
  team2: '/imgs/team2.jpg',
  team3: '/imgs/team3.jpg',
  team4: '/imgs/team4.jpg',
  prodKibble: '/imgs/prod1.jpg',
  prodBed: '/imgs/prod2.jpg',
  prodTreats: '/imgs/prod5.jpg',
  prodToy: '/imgs/prod4.jpg',
};

export const NEWS_ARTICLES: NewsArticle[] = [
  {
    id: 'n1',
    title: 'Royal Canin vs Pedigree: nên chọn hãng nào cho chó cưng?',
    category: 'Pet',
    author: 'Dr. Minh Thu',
    date: '28th Jul, 2026',
    image: IMG.prodKibble,
    excerpt: 'Cả hai đều là thương hiệu lớn, nhưng công thức và nhu cầu của từng bé chó sẽ quyết định lựa chọn phù hợp nhất.',
    body: `Nhiều chủ nuôi vẫn đang phân vân giữa Royal Canin và Pedigree khi chọn thức ăn cho "boss". Thực tế, cả hai đều có ưu và nhược điểm riêng.

**Royal Canin** nổi tiếng với dòng sản phẩm được nghiên cứu kỹ theo từng giống (Golden Retriever, Poodle, Shih Tzu...) và từng giai đoạn sống. Nếu chó bạn có vấn đề tiêu hóa, da lông hay cân nặng, đây là lựa chọn đáng cân nhắc. Tuy nhiên, giá thành cao hơn đáng kể.

**Pedigree** có giá phải chăng hơn, phù hợp với những gia đình cho nhiều bé ăn cùng lúc. Dinh dưỡng cơ bản đảm bảo, nhưng công thức ít được tùy chỉnh theo giống loài cụ thể.

Lời khuyên: Nếu ngân sách cho phép và chó có nhu cầu đặc biệt, Royal Canin là lựa chọn tốt hơn. Với chó khỏe mạnh, ăn Pedigree chất lượng vẫn hoàn toàn ổn — quan trọng hơn là duy trì lịch ăn đều đặn và theo dõi cân nặng.`,
  },
  {
    id: 'n2',
    title: 'Mèo bỏ ăn 3 ngày liên tiếp — khi nào cần đưa đi cấp cứu?',
    category: 'Medical',
    author: 'Dr. Lan Hương',
    date: '25th Jul, 2026',
    image: IMG.vet,
    excerpt: 'Mèo không ăn sau 48 giờ có thể gây tổn thương gan nghiêm trọng. Đây là những dấu hiệu cần nhận biết sớm.',
    body: `Trái với suy nghĩ phổ biến, mèo không ăn trong thời gian dài có thể gây ra bệnh gan nhiễm mỡ — đặc biệt nguy hiểm với mèo béo phì. Đây là tình trạng có thể gây suy gan và tử vong nếu không được xử lý kịp thời.

**Khi nào cần đưa đi cấp cứu ngay:**
- Mèo không ăn uống gì từ 24 giờ trở lên và có dấu hiệu lừ đừ, yếu ớt
- Nôn ói kèm theo không ăn uống
- Bụng căng trướng, vàng da hoặc vàng niêm mạc
- Mèo cố ăn nhưng không nuốt được

**Những nguyên nhân phổ biến khiến mèo bỏ ăn:**
- Stress thay đổi môi trường (nhà mới, có thú cưng mới)
- Vấn đề răng miệng (viêm lợi, sâu răng)
- Bệnh đường tiết niệu
- Tắc nghẽn lông (hairball)

Đừng chờ đến ngày thứ 3. Nếu mèo bạn từ chối thức ăn quá 24 giờ, hãy gọi phòng khám thú y để được tư vấn và đặt lịch khám.`,
  },
  {
    id: 'n3',
    title: 'Cách tôi dạy bé cún 4 tháng tuổi đi vệ sinh đúng chỗ trong 2 tuần',
    category: 'Pet',
    author: 'Thu Trang',
    date: '22nd Jul, 2026',
    image: IMG.blog3,
    excerpt: 'Không phải "thả rông" để chó tự học — đây là phương pháp có kế hoạch giúp bé Bông của mình không còn "tai nạn" sau 14 ngày.',
    body: `Lần đầu nuôi chó con, mình đã sai lầm khi nghĩ cứ thả cho Bông tự do trong nhà, dọn dẹp sau mỗi "tai nạn" là được. Kết quả: 3 tháng trời nhà luôn có mùi và Bông chẳng hiểu vì sao bị mắng.

Sau khi tìm hiểu và áp dụng phương pháp từ huấn luyện viên, đây là những gì mình làm:

**Tuần 1: Thiết lập thói quen**
- Đưa Bông ra chỗ cần đi vệ sinh mỗi 2 giờ, ngay sau khi ngủ dậy và sau ăn 15 phút
- Khen ngay khi Bông đi đúng chỗ, thậm chí dùng giọng cao hơn bình thường
- Không bao giờ mắng khi bắt gặp "tai nạn" — chỉ dọn dẹp im lặng

**Tuần 2: Mở rộng không gian dần**
- Khi Bông đi đúng 80% thời gian, cho phép tự do thêm 1-2 giờ
- Dùng enzyme cleaner để khử mùi hoàn toàn những chỗ từng có "tai nạn"

Kết quả: Tuần thứ 3, Bông hoàn toàn đi vệ sinh ngoài sân. Quan trọng nhất là kiên nhẫn — không có chó nào học được trong vài ngày.`,
  },
  {
    id: 'n4',
    title: 'Vaccine 5 bệnh cho chó con: lịch tiêm và những điều cần biết',
    category: 'Medical',
    author: 'Dr. Minh Thu',
    date: '18th Jul, 2026',
    image: IMG.counter,
    excerpt: 'Vaccine là "lá chắn" quan trọng nhất cho chó con. Nhiều chủ nuôi bỏ lỡ mũi tiêm nhắc lại vì không biết lịch trình cụ thể.',
    body: `Vaccine 5 bệnh (hay còn gọi vaccine 5 trong 1) là mũi tiêm cơ bản nhất mà mọi chú chó đều cần, phòng các bệnh nguy hiểm: Care (Distemper), Parvovirus, Viêm gan truyền nhiễm, Leptospirosis và Adenovirus type 2.

**Lịch tiêm chuẩn cho chó con:**
- **Mũi 1:** 6-8 tuần tuổi
- **Mũi 2:** 9-11 tuần tuổi (cách mũi 1 tối thiểu 3 tuần)
- **Mũi 3:** 12-16 tuần tuổi (đây là mũi quan trọng nhất, tạo miễn dịch cơ bản)
- **Mũi 4 (tùy chọn):** 16-20 tuần, tùy khuyến cáo bác sĩ

**Lưu ý quan trọng:**
- Chó con chưa tiêm đủ 3 mũi KHÔNG nên ra ngoài tiếp xúc với chó lạ
- Sau mũi tiêm, có thể có phản ứng nhẹ: mệt mỏi, sốt nhẹ trong 24-48 giờ — bình thường
- Nếu sốt cao, nôn ói, sưng mặt hoặc khó thở → đưa đi cấp cứu ngay (dị ứng vaccine rất hiếm nhưng nguy hiểm)

Giữ sổ tiêm phòng cẩn thận, đây là giấy tờ bắt buộc khi muốn đưa chó đi xã giao, khách sạn thú cưng hay ra nước ngoài.`,
  },
  {
    id: 'n5',
    title: 'Đệm memory foam cho chó có thực sự cần thiết? Kinh nghiệm dùng 6 tháng',
    category: 'Care',
    author: 'Minh Tuấn',
    date: '15th Jul, 2026',
    image: IMG.prodBed,
    excerpt: 'Bỏ ra 800K cho đệm chó — nghe có vẻ phí, nhưng sau 6 tháng mình hiểu tại sao bé Mập (giờ là bé Mập Mập) ngủ ngon đến thế.',
    body: `Trước khi mua đệm memory foam cho Mập (Shiba Inu 4 tuổi), mình đã dùng qua: đệm nỉ thường (rách sau 3 tháng), đệm gel làm mát (Mập tỉnh giấc vì nằm không đúng chỗ), và một cái đệm "orthopedic" giá rẻ (cứng như nằm sàn).

**Sau 6 tháng dùng đệm memory foam chính hãng, đây là review thật:**

Ưu điểm:
- Mập ngủ sâu hơn rõ rệt — trước hay trằn trọc, giờ nằm một chỗ cả tiếng
- Khớp xương của Mập đỡ kêu hơn khi đứng dậy (mình để ý thấy rõ vì Mập bắt đầu lớn tuổi)
- Đệm không bị biến dạng dù Mập hay xoay ngang xoay dọc

Nhược điểm:
- Giá cao: 700K-1.5 triệu tùy kích thước
- Nặng và cần vỏ chống thấm nếu chó hay rơi nước miếng
- Khó giặt hơn đệm thường

**Kết luận:** Nếu chó bạn trên 5 tuổi hoặc có vấn đề khớp, đây là khoản đầu tư xứng đáng. Với chó trẻ, khỏe mạnh, đệm nỉ chất lượng tốt (300-500K) là đủ.`,
  },
  {
    id: 'n6',
    title: 'Mèo cào sofa: 5 cách ngăn chặn mà không cần "bỏ rơi" cả căn phòng',
    category: 'Pet',
    author: 'Phương Linh',
    date: '12th Jul, 2026',
    image: IMG.team4,
    excerpt: 'Mèo cào không phải vì "hư" hay "điên" — đây là bản năng đánh dấu lãnh thổ và kéo móng. Và bạn hoàn toàn có thể kiểm soát được.',
    body: `Suốt 2 năm đầu nuôi mèo, mình đã thay 3 cái ghế sofa vì cào tan. Sau đó mình mới hiểu: cào là nhu cầu sinh lý bình thường của mèo, không phải hành vi phá phách.

**Tại sao mèo cào:**
- Kéo móng: Loại bỏ lớp móng ngoài cùng, giữ móng sắc
- Đánh dấu lãnh thổ: Cả mùi từ chân mèo lẫn vết cào đều là "tín hiệu" với các con mèo khác
- Giãn cơ: Cào là cách mèo "khởi động" sau giấc ngủ

**5 giải pháp đã thử và hiệu quả:**

1. **Cào thay thế:** Đặt cào bằng vải sisal gần sofa. Cào có gắn catnip để thu hút mèo.

2. **Dán băng dính hai mặt:** Dán lớp băng trong suốt lên chỗ mèo hay cào. Cảm giác dính khiến mèo tránh xa.

3. **Phủ vải lông xù:** Trải thêm vải len hoặc thảm lên sofa — mèo ít cào vải mềm.

4. **Cắt móng định kỳ:** Mỗi 2-3 tuần, cắt móng cho mèo giảm 70% thiệt hại.

5. **Xịt phân phối pheromone:** Feliway giúp mèo bình tĩnh, giảm hành vi cào vì stress.

Không cách nào hiệu quả 100% nhưng kết hợp 2-3 cách trên, mình đã cứu được chiếc sofa thứ 4.`,
  },
  {
    id: 'n7',
    title: 'Dinh dưỡng cho chó mắc bệnh tiểu đường: kinh nghiệm chăm sóc bé Gấu 2 năm',
    category: 'Care',
    author: 'Hoàng Nam',
    date: '8th Jul, 2026',
    image: IMG.prodTreats,
    excerpt: 'Chó mắc tiểu đường không thể ăn tự do như trước. Chế độ ăn kiểm soát nghiêm ngặt giúp Gấu sống khỏe mạnh đến giờ.',
    body: `Gấu (Corgi, 8 tuổi) được chẩn đoán tiểu đường vào năm ngoái. Ban đầu cả nhà rất hoang mang, nhưng sau 2 năm kiểm soát, Gấu vẫn chạy nhảy bình thường. Đây là những gì mình học được:

**Về chế độ ăn:**
- Cho ăn đúng giờ, đúng lượng mỗi ngày — không để thức ăn tự do
- Thức ăn khuyến nghị: công thức dành cho chó tiểu đường (Royal Canin Diabetic, Hill's m/d)
- Tránh xúc xích, bánh snack, hoặc thức ăn có đường hoàn toàn
- Mình cân Gấu mỗi tuần để theo dõi cân nặng ổn định

**Về insulin:**
- Tiêm insulin 2 lần/ngày theo chỉ định bác sĩ — đúng giờ, đúng liều
- Đo đường huyết tại nhà bằng máy đo glucose (mua ở hiệu thuốc, kèm kim lấy máu cho mèo/chó)
- Sổ theo dõi đường huyết hàng ngày để bác sĩ điều chỉnh liều lượng

**Dấu hiệu cảnh báo cần gặp bác sĩ ngay:**
- Uống nước quá nhiều hoặc đột ngột bỏ ăn
- Mắt đục hoặc lờ đờ bất thường
- Cân nặng giảm nhanh dù ăn đủ

Tiểu đường ở chó không chữa khỏi hoàn toàn, nhưng kiểm soát tốt hoàn toàn giúp chó sống bình thường. Quan trọng nhất: tuân thủ lịch tiêm insulin và chế độ ăn.`,
  },
  {
    id: 'n8',
    title: 'Review 3 loại snack thưởng cho mèo: Pate, dried chicken và cat grass',
    category: 'Pet',
    author: 'Ngọc Mai',
    date: '5th Jul, 2026',
    image: IMG.prodTreats,
    excerpt: 'Snack thưởng không chỉ là "trái cây sau bữa ăn" — đúng loại còn giúp mèo bổ sung dinh dưỡng và hỗ trợ tiêu hóa.',
    body: `Mình đã thử qua hàng chục loại snack cho bé Sữa (Munchkin 3 tuổi). Đây là review trung thực 3 loại mình dùng thường xuyên nhất:

**1. Pate (Whiskas, Me-O, Royal Canin) — Đánh giá: 8/10**
- Ưu: Mùi thơm hấp dẫn, dễ tiêu, nhiều vị để chọn
- Nhược: Đa số có chất bảo quản, không nên cho quá 10% khẩu phần ăn
- Giá: 15-35K/hộp nhỏ
- Tip: Cho vào tủ lạnh trước 10 phút, vị lạnh dai hơn, Sữa phát cuồng

**2. Dried chicken strips (KitCat, Orijen) — Đánh giá: 9/10**
- Ưu: Protein cao, ít carb, mèo rất thích, dùng làm phần thưởng huấn luyện tốt
- Nhược: Khẩu phần nhỏ, dễ bị mốc nếu để ẩm
- Giá: 80-150K/túi
- Tip: Mình chia nhỏ vào hộp kín, để ngăn mát tủ lạnh

**3. Cat grass (trồng tại nhà) — Đánh giá: 7/10**
- Ưu: Giúp mèo nôn khi nuốt lông, tự nhiên và an toàn
- Nhược: Cần trồng và duy trì, một số mèo không mấy quan tâm
- Giá: 50-100K/gói hạt giống hoặc 120K/chậu có sẵn
- Tip: Mình trồng 2 chậu luân phiên, để gần cửa sổ nhiều nắng

Kết luận: Pate cho bữa ăn kèm, dried chicken làm phần thưởng huấn luyện, cat grass hỗ trợ tiêu hóa. 3 loại kết hợp là combo hoàn hảo cho Sữa.`,
  },
  {
    id: 'n9',
    title: 'Chó sợ pháo hoa: 4 bước giúp bé bình tĩnh qua đêm Giao thừa',
    category: 'Pet',
    author: 'Dr. Lan Hương',
    date: '1st Jul, 2026',
    image: IMG.team2,
    excerpt: 'Âm thanh nổ lớn khiến nhiều chú chó hoảng loạn, thậm chí bỏ chạy khỏi nhà. Đây là cách chuẩn bị an toàn cho thú cưng.',
    body: `Mỗi dịp Tết Nguyên Đán, các phòng khám thú y đều ghi nhận số ca chó bị thương do hoảng loạn tăng đột biến. Giao thừa với pháo hoa không chỉ là tiếng ồn — với chó, đó là "thảm họa" cảm giác.

**4 bước chuẩn bị trước Tết:**

**Bước 1: Tập thích nghi âm thanh (bắt đầu 2-4 tuần trước)**
- Phát video tiếng pháo hoa từ YouTube ở âm lượng nhỏ
- Tăng dần âm lượng qua nhiều ngày, khen và thưởng khi chó bình tĩnh
- Kết thúc mỗi buổi bằng thức ăn ngon và vuốt ve

**Bước 2: Tạo không gian an toàn**
- Chuẩn bị phòng nhỏ, đóng cửa, kéo rèm giảm âm thanh
- Để sẵn đệm, đồ chơi quen thuộc, áo thun mới mặc có mùi chủ
- Bật TV hoặc nhạc nền để "che" tiếng nổ bên ngoài

**Bước 3: Sử dụng thuốc hỗ trợ**
- Thuốc an thần thảo dược (Zylkene, Sileo) nếu chó hoảng loạn nặng
- Tham khảo bác sĩ thú y về liều lượng phù hợp
- T-Thorazine chỉ dùng khi được kê đơn

**Bước 4: Không để chó một mình ngoài trời**
- Trong đêm Giao thừa, có người ở nhà với chó
- Đóng cổng cửa chắc chắn, tránh chó bỏ chạy
- Thắt dây xích cẩn thận nếu chó phải ra ngoài

Điều quan trọng nhất: Bình tĩnh của chủ nuôi truyền sang cho chó. Nếu bạn lo lắng, chó sẽ cảm nhận được và sợ hơn.`,
  },
  {
    id: 'n10',
    title: 'Nấm da ở chó: nguyên nhân, nhận biết và cách điều trị tại nhà',
    category: 'Medical',
    author: 'Dr. Minh Thu',
    date: '27th Jun, 2026',
    image: IMG.team3,
    excerpt: 'Nấm da tuy không nguy hiểm tính mạng nhưng lây lan nhanh và rất khó điều trị dứt điểm nếu không phát hiện sớm.',
    body: `Nấm da ( Dermatophytosis) là bệnh nhiễm nấm trên bề mặt da và lông, khá phổ biến ở chó — đặc biệt trong mùa nóng ẩm Việt Nam. Nhiều chủ nuôi nhầm lẫn với ghẻ hoặc dị ứng thức ăn.

**Dấu hiệu nhận biết nấm da:**
- Vòng tròn lông rụng, viền đỏ, giữa có vảy
- Thường xuất hiện ở tai, chân, đuôi và lưng
- Ngứa nhẹ đến trung bình (không ngứa dữ dội như ghẻ)
- Móng vuốt có thể bị biến dạng nếu nấm lan đến

**Cách xác định tại nhà:**
- Chiếu đèn Wood (UV) lên vùng da nghi ngờ — nấm Microsporum canis sẽ phát sáng màu xanh lá
- Mua đèn Wood ở các cửa hàng y tế, giá khoảng 150-300K
- Tuy nhiên, chỉ 50% trường hợp phát sáng — âm tính không có nghĩa là không có nấm

**Điều trị:**
- Kem chống nấm (Clotrimazole, Miconazole) bôi tại chỗ 2 lần/ngày trong 2-4 tuần
- Tắm dung dịch chống nấm (Ketoconazole shampoo) 2 lần/tuần
- Nếu lan rộng: cần thuốc uống Itraconazole hoặc Terbinafine theo kê đơn bác sĩ
- Dùng găng tay khi tiếp xúc vùng da bệnh, giặt đồ giặt giũ riêng

**Phòng ngừa:**
- Giữ lông chó khô ráo, đặc biệt sau khi tắm hoặc trời mưa
- Vệ sinh môi trường sống, khử trùng đồ chơi và chuồng
- Kiểm tra da lông định kỳ, nhất là mùa nắng nóng`,
  },
  {
    id: 'n11',
    title: 'Mèo già có nhu cầu dinh dưỡng khác mèo trẻ không? Chế độ ăn cho "cụ mèo" 10 tuổi',
    category: 'Care',
    author: 'Ngọc Mai',
    date: '23rd Jun, 2026',
    image: IMG.prodKibble,
    excerpt: 'Mèo trên 7 tuổi bước vào "senior years" — thận hoạt động kém hơn, cơ bắp giảm dần, cần chế độ ăn riêng biệt.',
    body: `Bé Muội (mèo ta, 10 tuổi) đã qua 3 lần chuyển đổi chế độ ăn trong đời. Mỗi lần mình đều học được điều mới về nhu cầu dinh dưỡng của mèo già.

**Thay đổi sinh lý ở mèo senior (7+ tuổi):**
- Thận suy giảm: ~30% mèo trên 10 tuổi có vấn đề thận
- Cơ bắp mất dần: Dù ăn đủ, mèo già vẫn sụt cân do khó hấp thụ
- Thị lực và thính giác giảm: Ăn uống chậm hơn, cần không gian yên tĩnh
- Hoạt động ít hơn: Nhu cầu calo giảm 20-30%

**Chế độ ăn khuyến nghị cho mèo 10 tuổi trở lên:**
- Thức ăn "senior" công thức thận: ít protein nhưng protein chất lượng cao (tránh gánh nặng cho thận)
- Bổ sung EPA/DHA (omega-3): hỗ trợ não bộ và giảm viêm khớp
- Thức ăn ướt nhiều hơn: Mèo già dễ mất nước, thức ăn ướt giúp bổ sung độ ẩm
- Khẩu phần nhỏ hơn, cho ăn nhiều bữa hơn (4-5 bữa nhỏ thay vì 2 bữa lớn)

**Điều cần theo dõi:**
- Cân nặng: Cân Muội mỗi tuần, báo bác sĩ nếu sụt >5% trong 1 tháng
- Lượng nước uống: Đặt nhiều chén nước quanh nhà, có thể thêm máy lọc nước cho mèo
- Phân và nước tiểu: Bất thường về màu sắc, mùi là dấu hiệu cần khám

Mỗi mèo già có tình trạng sức khỏe riêng — nên khám định kỳ 6 tháng/lần và tham khảo bác sĩ về chế độ ăn cụ thể.`,
  },
  {
    id: 'n12',
    title: '5 loại đồ chơi kích thích trí não cho chó — giảm hành vi phá phách',
    category: 'Pet',
    author: 'Thu Trang',
    date: '19th Jun, 2026',
    image: IMG.prodToy,
    excerpt: 'Chó nghĉ mũi 4 tiếng/ngày nếu không được kích thích tinh thần. Đồ chơi giải đố là "cứu cánh" cho những ngày mưa.',
    body: `Corgi của mình, Bean, nổi tiếng là "vua phá phách" khi ở nhà một mình. Sau khi tìm hiểu, mình hiểu ra: Bean không phá phách vì "hư" — mà vì nhàm chán. Não chó cần được "tập thể dục" cũng như cơ thể.

**Top 5 đồ chơi giải đố mình đã thử:**

**1. Kong Classic (nhồi thức ăn đông lạnh) — 9/10**
- Nhồi pate hoặc thức ăn ướt vào Kong, để đông lạnh qua đêm
- Bean mất 30-45 phút để "giải cứu" snack bên trong
- Độ bền tốt, dễ vệ sinh, giá 200-350K

**2. Lickimat (mat liếm) — 8/10**
- Phết pate, cream cheese hoặc peanut butter lên rãnh, cho mèo/chó liếm
- Giúp thư giãn, giảm stress hiệu quả
- Giá 120-180K, rất dễ rửa

**3. Snuffle Mat (thảm tìm thức ăn) — 8/10**
- Giấu thức ăn trong các sợi vải, chó phải "đào bới" để tìm
- Mô phỏng hành vi tìm kiếm thức ăn tự nhiên
- Giá 250-400K, giặt máy được

**4. Outward Hound Puzzle (bánh xe) — 7/10**
- Chó đẩy khay xoay để tìm snack
- Độ khó có thể điều chỉnh
- Giá 400-600K, khó vệ sinh hơn

**5. Đồ chơi tự lăn phân phối thức ăn — 6/10**
- Chó đẩy để thức ăn rơi ra ngoài
- Tốt cho chó năng động nhưng Bean nhanh chán
- Giá 300-500K

Kinh nghiệm: Mua 2-3 loại khác nhau, luân phiên mỗi ngày để tránh nhàm chán. Đặc biệt hiệu quả khi để chó một mình ở nhà.`,
  },
  {
    id: 'n13',
    title: 'Tắm cho mèo đúng cách: hướng dẫn từ A-Z cho người mới nuôi',
    category: 'Care',
    author: 'Phương Linh',
    date: '15th Jun, 2026',
    image: IMG.team1,
    excerpt: 'Mèo tự liếm sạch lông và thường không cần tắm thường xuyên. Nhưng khi cần tắm (dính dầu, bệnh da), đây là cách làm đúng.',
    body: `Thú thật: 3 năm nuôi mèo, mình chỉ tắm cho cả 2 bé đúng 4 lần. Không phải mình bẩn thỉu — mèo thực sự rất sạch sẽ và tự chăm sóc lông bằng cách liếm. Nhưng có những trường hợp bắt buộc phải tắm.

**Khi nào mèo CẦN tắm:**
- Dính dầu nhớt, hóa chất hoặc chất độc hại lên lông
- Bị bệnh da cần tắm dung dịch trị liệu theo chỉ định bác sĩ
- Lông bết dính phân (mèo già, bệnh)
- Nhảy vào nơi không thể vệ sinh bằng cách khác

**Khi nào KHÔNG CẦN tắm:**
- Mèo bình thường, khỏe mạnh — chỉ cần chải lông định kỳ
- Vì mèo có mùi "giống mèo" — đây là mùi tự nhiên, không phải "bẩn"

**Cách tắm đúng (nếu bắt buộc):**
1. Chuẩn bị: nước ấm (38-40°C), shampoo mèo chuyên dụng, khăn Cotton, bông gòn để che tai
2. Làm ướt lông từ cổ trở xuống, tránh đầu và tai
3. Xoa nhẹ nhàng, không chà xát mạnh
4. Xả sạch hoàn toàn, xà bông sót lại gây kích ứng da
5. Sấy khô bằng khăn, tránh máy sấy ồn làm mèo sợ
6. Giữ ấm cho mèo cho đến khi lông khô hoàn toàn

**Lưu ý:** Không bao giờ tắm mèo dưới 8 tuần tuổi. Nếu mèo quá hung dữ, hãy nhờ grooming service thay vì tự làm — nguy cơ bị cào và mèo bị stress rất cao.`,
  },
  {
    id: 'n14',
    title: 'Cách chọn khách sạn thú cưng uy tín: checklist 15 câu hỏi cần hỏi trước khi đặt',
    category: 'Pet Care',
    author: 'Hoàng Nam',
    date: '10th Jun, 2026',
    image: IMG.blog2,
    excerpt: 'Gửi thú cưng cho người lạ là quyết định khó khăn. 15 câu hỏi này giúp bạn đánh giá khách sạn thú cưng trước khi giao "con" cho họ.',
    body: `Trước khi gửi Gấu lần đầu, mình đã tham quan 5 khách sạn thú cưng, gọi điện hỏi han và cuối cùng chọn được nơi mà cả mình và Gấu đều hài lòng. Đây là checklist mình dùng:

**Về cơ sở vật chất:**
1. Khu vực lưu trú có camera giám sát 24/7 không? Tôi có thể xem qua điện thoại không?
2. Chó được nhốt riêng hay ở chung? Nếu riêng, kích thước chuồng/room như thế nào?
3. Có khu vực cách ly riêng cho thú ốm không?
4. Hệ thống lọc không khí và vệ sinh môi trường như thế nào?
5. Có sân vận động/diện tích cho chó chạy nhảy không?

**Về nhân viên:**
6. Nhân viên có được đào tạo về sơ cấp cứu thú cưng không?
7. Có bác sĩ thú y trực tại chỗ hay liên kết phòng khám gần đó?
8. Tỷ lệ nhân viên/thú cưng là bao nhiêu?
9. Có cho phép tôi tham quan cơ sở trước khi đặt không?

**Về chế độ sinh hoạt:**
10. Lịch cho ăn, vận động, đi dạo như thế nào?
11. Tôi có nên mang thức ăn từ nhà hay các bữa ăn có sẵn?
12. Nếu Gấu cần thuốc, nhân viên có cho uống đúng giờ không?
13. Chính sách xử lý khẩn cấp (bệnh, thương tích) là gì?
14. Tôi có thể gọi video thăm Gấu không?

**Về chính sách:**
15. Chính sách hủy và hoàn tiền nếu tôi đến muộn hoặc cần về sớm?

**Red flags cần tránh:**
- Không cho xem cơ sở trước, chỉ gửi ảnh stock
- Không hỏi về tình trạng sức khỏe và tính cách chó trước khi nhận
- Giá quá rẻ so với thị trường (dưới 150K/đêm cho chó lớn)
- Không có hợp đồng gửi thú cưng rõ ràng`,
  },
  {
    id: 'n15',
    title: 'Triệt sản cho chó mèo: nên làm ở tuổi nào và những lo ngại thường gặp',
    category: 'Medical',
    author: 'Dr. Lan Hương',
    date: '5th Jun, 2026',
    image: IMG.vet,
    excerpt: 'Triệt sản là phẫu thuật phổ biến nhất ở thú cưng, nhưng nhiều chủ nuôi vẫn băn khoăn về thời điểm và tác dụng phụ.',
    body: `Triệt sản (spay/neuter) là quyết định quan trọng trong việc chăm sóc thú cưng. Thông tin dưới đây dựa trên hướng dẫn của Hiệp hội Thú y Hoa Kỳ (AVMA) và kinh nghiệm lâm sàng tại Việt Nam.

**Lợi ích của triệt sản:**
- Giảm nguy cơ ung thư tuyến vú (giảm 50% nếu trước chu kỳ đầu tiên ở cái)
- Loại bỏ nguy cơ viêm tử cung (pyometra) ở cái
- Giảm hành vi đánh dấu lãnh thổ, bỏ chạy tìm bạn ở đực
- Giảm số lượng chó mèo hoang không mong muốn

**Thời điểm phù hợp:**
- **Chó cái:** 6-12 tháng tuổi, trước chu kỳ động dục đầu tiên (thường 6-9 tháng)
- **Chó đực:** 6-12 tháng, có thể muộn hơn đến 18 tháng với giống lớn
- **Mèo cái:** 4-6 tháng (mèo có thể mang thai từ 4 tháng tuổi)
- **Mèo đực:** 5-7 tháng, trước khi bắt đầu phun tỏa

**Những lo ngại thường gặp và sự thật:**

*"Triệt sản khiến chó mèo béo phì"*
→ Đúng là cảm giác thèm ăn tăng, nhưng quản lý khẩu phần và vận động sẽ kiểm soát được cân nặng.

*"Con cái nên sinh một lứa trước"*
→ Không có bằng chứng khoa học. Sinh một lứa trước thậm chí có thể tăng nguy cơ ung thư.

*"Phẫu thuật nguy hiểm"*
→ Đây là phẫu thuật rất phổ biến, tỷ lệ biến chứng dưới 5% ở thú khỏe mạnh.

**Chuẩn bị trước phẫu thuật:**
- Nhịn ăn 8-12 giờ trước giờ mổ (đúng theo hướng dẫn bác sĩ)
- Khám sức khỏe và xét nghiệm máu trước nếu thú cưng trên 5 tuổi
- Chuẩn bị áo phẫu thuật hoặc e-collar (vòng cổ bảo vệ) để ngăn liếm vết mổ

**Sau phẫu thuật:**
- Cho nghỉ ngơi 24-48 giờ, hạn chế vận động mạnh
- Theo dõi vết mổ: sưng nhẹ bình thường, chảy dịch hoặc đỏ hơn cần báo bác sĩ
- Thuốc giảm đau thường được kê, không tự ý dừng
- Không tắm trong 10-14 ngày`,
  },
];

// Lọc bài viết theo category — hàm thuần, dùng cho tab filter ở NewsPage.
export function filterNewsByCategory(items: NewsArticle[], category: string): NewsArticle[] {
  if (category === 'Tất cả') return items;
  return items.filter((a) => a.category === category);
}

// Bài viết liên quan: cùng category, loại bài hiện tại, giới hạn `limit`.
export function getRelatedNews(all: NewsArticle[], current: NewsArticle, limit = 4): NewsArticle[] {
  return all.filter((a) => a.category === current.category && a.id !== current.id).slice(0, limit);
}
