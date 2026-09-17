// Dữ liệu danh mục sản phẩm. Thay bằng API `/products` khi BE sẵn sàng.

export interface ShopProduct {
  id: number
  name: string
  brand: string
  category: CategoryId
  petType: 'dog' | 'cat'
  price: number
  oldPrice?: number
  rating: number
  sold: number
  badge?: string
  image: string
}

export type CategoryId = 'food' | 'toys' | 'treats' | 'health' | 'bedding' | 'grooming'

// Line-art đã tách khỏi nền đen gốc (public/imgs/cat/*.png).
export const CATEGORIES: { id: CategoryId; label: string; photo: string }[] = [
  { id: 'food', label: 'Thức ăn', photo: '/imgs/cat/food.png' },
  { id: 'toys', label: 'Đồ chơi', photo: '/imgs/cat/toys.png' },
  { id: 'treats', label: 'Bánh thưởng', photo: '/imgs/cat/treats.png' },
  { id: 'health', label: 'Sức khoẻ', photo: '/imgs/cat/health.png' },
  { id: 'bedding', label: 'Ổ nằm', photo: '/imgs/cat/bedding.png' },
  { id: 'grooming', label: 'Vệ sinh', photo: '/imgs/cat/grooming.png' },
]

export const CATEGORY_LABELS = Object.fromEntries(
  CATEGORIES.map((c) => [c.id, c.label]),
) as Record<CategoryId, string>

export const PET_TYPES = [
  { id: 'dog', label: 'Cho chó' },
  { id: 'cat', label: 'Cho mèo' },
] as const

export const PRICE_BANDS = [
  { id: 'u150', label: 'Dưới 150K', min: 0, max: 150_000 },
  { id: '150-350', label: '150K – 350K', min: 150_000, max: 350_000 },
  { id: 'o350', label: 'Trên 350K', min: 350_000, max: Infinity },
] as const

export const SORT_OPTIONS = [
  { id: 'popular', label: 'Bán chạy' },
  { id: 'newest', label: 'Mới nhất' },
  { id: 'price_asc', label: 'Giá thấp → cao' },
  { id: 'price_desc', label: 'Giá cao → thấp' },
  { id: 'rating', label: 'Đánh giá cao' },
] as const

// Logo lấy từ public/imgs/brands — tên file giữ nguyên dạng đã encode.
export const BRANDS: { id: string; label: string; logo: string }[] = [
  { id: 'royal-canin', label: 'Royal Canin', logo: '/imgs/brands/Royal_20Canin.jpg' },
  { id: 'pedigree', label: 'Pedigree', logo: '/imgs/brands/Pedigree.jpg' },
  { id: 'monge', label: 'Monge', logo: '/imgs/brands/Monge.jpg' },
  { id: 'nekko', label: 'Nekko', logo: '/imgs/brands/Nekko.jpg' },
  { id: 'me-o', label: 'Me-O', logo: '/imgs/brands/Me-O.jpg' },
  { id: 'kitcat', label: 'KitCat', logo: '/imgs/brands/KitCat.jpg' },
  { id: 'anf', label: 'ANF', logo: '/imgs/brands/ANF.jpg' },
  { id: 'cattyman', label: 'Cattyman', logo: '/imgs/brands/Cattyman.jpg' },
]

export const BRAND_BY_ID = Object.fromEntries(BRANDS.map((b) => [b.id, b]))

// [tên, brand, danh mục, loài, giá, sao, đã bán, giá cũ?, nhãn?]
type Row = [string, string, CategoryId, 'dog' | 'cat', number, number, number, number?, string?]

const ROWS: Row[] = [
  // ---- thức ăn cho chó ----
  ['Hạt Royal Canin Medium Adult 4kg', 'royal-canin', 'food', 'dog', 452_000, 4.8, 1284, 520_000, 'Bán chạy'],
  ['Pedigree vị gà & rau củ 3kg', 'pedigree', 'food', 'dog', 278_000, 4.5, 863],
  ['Hạt ANF 6Free vị cừu 2kg', 'anf', 'food', 'dog', 186_000, 4.2, 507, undefined, 'Mới'],
  ['Hạt Monge All Breed Adult 3kg', 'monge', 'food', 'dog', 395_000, 4.6, 412],
  ['Pate Pedigree bò hầm 400g (lốc 6)', 'pedigree', 'food', 'dog', 162_000, 4.4, 731],
  ['Hạt Royal Canin Mini Puppy 2kg', 'royal-canin', 'food', 'dog', 338_000, 4.9, 654],
  ['Hạt ANF Salmon & Potato 1kg', 'anf', 'food', 'dog', 132_000, 4.3, 389],
  ['Súp thưởng gà xé cho chó 40g (lốc 10)', 'me-o', 'food', 'dog', 98_000, 4.2, 542],
  ['Hạt Monge Puppy & Junior 2.5kg', 'monge', 'food', 'dog', 352_000, 4.7, 233, 399_000, 'Giảm 12%'],
  ['Pate Pedigree gan & rau củ 400g', 'pedigree', 'food', 'dog', 29_000, 4.1, 1904],

  // ---- thức ăn cho mèo ----
  ['Pate Nekko cá hồi 70g (lốc 12)', 'nekko', 'food', 'cat', 118_000, 4.7, 2047, 144_000, 'Giảm 18%'],
  ['Hạt Me-O Persian 1.1kg', 'me-o', 'food', 'cat', 128_000, 4.5, 1392],
  ['Hạt Royal Canin Kitten 2kg', 'royal-canin', 'food', 'cat', 385_000, 4.9, 728, undefined, 'Bán chạy'],
  ['Pate Nekko gà & phô mai 70g (lốc 12)', 'nekko', 'food', 'cat', 122_000, 4.6, 1183],
  ['Hạt KitCat Grain Free 1.5kg', 'kitcat', 'food', 'cat', 265_000, 4.5, 406],
  ['Hạt Me-O Tuna 1.2kg', 'me-o', 'food', 'cat', 115_000, 4.3, 1755],
  ['Súp thưởng Cattyman cá ngừ 14g (lốc 20)', 'cattyman', 'food', 'cat', 92_000, 4.6, 2318],
  ['Hạt Monge Indoor Cat 1.5kg', 'monge', 'food', 'cat', 298_000, 4.6, 318],
  ['Pate KitCat cá mòi 80g (lốc 12)', 'kitcat', 'food', 'cat', 134_000, 4.4, 872],
  ['Hạt ANF Cat Adult 1.5kg', 'anf', 'food', 'cat', 168_000, 4.2, 445, 189_000, 'Giảm 11%'],

  // ---- đồ chơi cho chó ----
  ['Bóng cao su nhồi thức ăn size M', 'kitcat', 'toys', 'dog', 89_000, 4.3, 412],
  ['Dây thừng kéo co 2 nút 35cm', 'cattyman', 'toys', 'dog', 62_000, 4.4, 758],
  ['Xương gặm cao su gai massage', 'kitcat', 'toys', 'dog', 74_000, 4.2, 531],
  ['Đĩa bay silicon mềm 22cm', 'monge', 'toys', 'dog', 96_000, 4.5, 287],
  ['Bóng phát tiếng cút kít (bộ 3)', 'cattyman', 'toys', 'dog', 58_000, 4.1, 1129],
  ['Thú nhồi bông hình vịt 28cm', 'pedigree', 'toys', 'dog', 118_000, 4.6, 402],

  // ---- đồ chơi cho mèo ----
  ['Chuột bông catnip (bộ 3 con)', 'cattyman', 'toys', 'cat', 47_000, 4.6, 1536],
  ['Cần câu mèo lông vũ tự nhiên', 'cattyman', 'toys', 'cat', 38_000, 4.4, 2215],
  ['Đèn laser trêu mèo sạc USB', 'kitcat', 'toys', 'cat', 85_000, 4.3, 968],
  ['Bóng chuông nhựa (bộ 6)', 'me-o', 'toys', 'cat', 34_000, 4.2, 1874],
  ['Đường hầm vải gấp gọn 3 ngả', 'kitcat', 'toys', 'cat', 245_000, 4.7, 336],
  ['Trụ cào móng sisal 45cm', 'cattyman', 'toys', 'cat', 189_000, 4.5, 624, 215_000, 'Giảm 12%'],

  // ---- bánh thưởng cho chó ----
  ['Bánh thưởng huấn luyện vị bò 200g', 'pedigree', 'treats', 'dog', 74_000, 4.5, 1863],
  ['Snack gà xé sợi sấy khô 150g', 'me-o', 'treats', 'dog', 84_000, 4.7, 1047],
  ['Xúc xích cho chó 15g (lốc 20)', 'pedigree', 'treats', 'dog', 96_000, 4.3, 1428],
  ['Cá hồi sấy lạnh cho chó 100g', 'anf', 'treats', 'dog', 138_000, 4.6, 593],
  ['Bánh quy sữa dê 250g', 'monge', 'treats', 'dog', 112_000, 4.4, 476],
  ['Gân bò sấy giòn 120g', 'anf', 'treats', 'dog', 156_000, 4.8, 812, undefined, 'Bán chạy'],

  // ---- bánh thưởng cho mèo ----
  ['Snack cá hồi sấy lạnh 100g', 'me-o', 'treats', 'cat', 152_000, 4.8, 974],
  ['Bánh thưởng Cattyman vị sò 60g', 'cattyman', 'treats', 'cat', 58_000, 4.4, 1362],
  ['Cỏ mèo sấy khô catnip 30g', 'kitcat', 'treats', 'cat', 42_000, 4.5, 2087],
  ['Snack cá ngừ dạng que 15g (lốc 12)', 'nekko', 'treats', 'cat', 88_000, 4.6, 1541],
  ['Viên thưởng bổ sung taurine 80g', 'anf', 'treats', 'cat', 124_000, 4.3, 388],
  ['Ức gà sấy cho mèo 100g', 'me-o', 'treats', 'cat', 132_000, 4.7, 705],

  // ---- sức khoẻ cho chó ----
  ['Xương gặm sạch răng bạc hà 12 thanh', 'pedigree', 'health', 'dog', 96_000, 4.4, 1108],
  ['Men tiêu hoá cho chó 60 viên', 'anf', 'health', 'dog', 248_000, 4.5, 342],
  ['Canxi & vitamin tổng hợp 100 viên', 'monge', 'health', 'dog', 289_000, 4.6, 267],
  ['Nhỏ gáy phòng ve rận 3 ống', 'royal-canin', 'health', 'dog', 215_000, 4.3, 831],
  ['Dung dịch vệ sinh tai 100ml', 'cattyman', 'health', 'dog', 78_000, 4.4, 662],
  ['Dầu cá hồi Omega-3 250ml', 'anf', 'health', 'dog', 192_000, 4.7, 418, 219_000, 'Giảm 12%'],

  // ---- sức khoẻ cho mèo ----
  ['Men tiêu hoá & lợi khuẩn 60 viên', 'anf', 'health', 'cat', 268_000, 4.6, 294],
  ['Gel dinh dưỡng cho mèo 100g', 'kitcat', 'health', 'cat', 156_000, 4.5, 537],
  ['Thuốc xổ giun cho mèo 4 viên', 'royal-canin', 'health', 'cat', 89_000, 4.2, 1046],
  ['Viên hỗ trợ tiêu búi lông 60 viên', 'me-o', 'health', 'cat', 134_000, 4.4, 712],
  ['Dung dịch nhỏ mắt cho mèo 15ml', 'cattyman', 'health', 'cat', 68_000, 4.3, 489],
  ['Sữa bột cho mèo con 300g', 'monge', 'health', 'cat', 226_000, 4.7, 351],

  // ---- ổ nằm cho chó ----
  ['Nệm nằm memory foam 80×60cm', 'monge', 'bedding', 'dog', 889_000, 4.9, 187, 1_050_000, 'Cao cấp'],
  ['Đệm vải oxford chống nước 70cm', 'kitcat', 'bedding', 'dog', 385_000, 4.4, 264],
  ['Giường khung sắt nâng cao 90cm', 'monge', 'bedding', 'dog', 1_180_000, 4.6, 92],
  ['Chăn lông cừu cho chó 100×70cm', 'pedigree', 'bedding', 'dog', 215_000, 4.5, 438],
  ['Balo vận chuyển mặt trong suốt', 'cattyman', 'bedding', 'dog', 465_000, 4.3, 176],
  ['Chuồng quây gấp gọn 8 tấm', 'kitcat', 'bedding', 'dog', 725_000, 4.5, 118],

  // ---- ổ nằm cho mèo ----
  ['Ổ tròn lông cừu cho mèo Ø50cm', 'kitcat', 'bedding', 'cat', 419_000, 4.7, 265],
  ['Nhà đệm vải canvas gấp gọn', 'monge', 'bedding', 'cat', 536_000, 4.5, 141],
  ['Võng mèo gắn cửa sổ giác hút', 'cattyman', 'bedding', 'cat', 298_000, 4.4, 382],
  ['Túi vận chuyển mèo có bánh xe', 'kitcat', 'bedding', 'cat', 812_000, 4.6, 87],
  ['Đệm sưởi cho mèo mùa lạnh 45cm', 'me-o', 'bedding', 'cat', 345_000, 4.5, 203],
  ['Kệ leo trèo gắn tường 3 tầng', 'cattyman', 'bedding', 'cat', 968_000, 4.8, 64, undefined, 'Cao cấp'],

  // ---- vệ sinh & tắm chải ----
  ['Bộ chải lông & cắt móng 5 món', 'cattyman', 'grooming', 'dog', 347_000, 4.6, 328],
  ['Sữa tắm trị ve rận 450ml', 'monge', 'grooming', 'dog', 215_000, 4.4, 641, 249_000, 'Giảm 14%'],
  ['Kìm cắt móng chuyên dụng', 'cattyman', 'grooming', 'dog', 96_000, 4.4, 728],
  ['Bàn chải đánh răng & kem 100g', 'kitcat', 'grooming', 'dog', 128_000, 4.5, 596],
  ['Máy cạo lông mini không dây', 'monge', 'grooming', 'dog', 458_000, 4.6, 214],
  ['Lược chải rụng lông hai mặt', 'kitcat', 'grooming', 'cat', 112_000, 4.3, 883],
  ['Sữa tắm khô không cần xả 200ml', 'me-o', 'grooming', 'cat', 145_000, 4.5, 1194],
  ['Khăn lau vệ sinh cho mèo 100 tờ', 'me-o', 'grooming', 'cat', 65_000, 4.3, 1783],
]

export const PRODUCTS: ShopProduct[] = ROWS.map(
  ([name, brand, category, petType, price, rating, sold, oldPrice, badge], i) => ({
    id: i + 1,
    name,
    brand,
    category,
    petType,
    price,
    rating,
    sold,
    ...(oldPrice ? { oldPrice } : {}),
    ...(badge ? { badge } : {}),
    image: `/imgs/prod${(i % 8) + 1}.jpg`,
  }),
)
