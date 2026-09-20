// Dữ liệu mock cho /recommend — pet + gợi ý sản phẩm.
// Thay bằng API /pets + /recommendations khi BE sẵn sàng.

export interface Pet {
  id: number
  name: string
  type: 'dog' | 'cat'
  breed: string
  age: string
  weight: string
  image: string
  healthNote?: string
}

export interface RecProduct {
  id: number
  name: string
  brand: string
  category: string
  petType: 'dog' | 'cat'
  price: number
  oldPrice?: number
  rating: number
  sold: number
  badge?: string
  image: string
  reason: string // tại sao gợi ý cho pet này
}

// Ảnh pet dùng ảnh thật từ kho.
const PET_PHOTOS: Record<string, string> = {
  milo:    '/imgs/hero-dog-clean.png',   // golden retriever
  luna:    '/imgs/prod3.jpg',             // mèo cam
  max:     '/imgs/hero-dog.png',          // poodle
  mochi:   '/imgs/cat-vet.png',           // british shorthair
  buddy:   '/imgs/hero-dog-clean.png',    // chó nâu lông ngắn
  nala:    '/imgs/cat-food.png',         // mèo đen trắng
}

export const MOCK_PETS: Pet[] = [
  {
    id: 1,
    name: 'Milo',
    type: 'dog',
    breed: 'Golden Retriever',
    age: '3 tuổi',
    weight: '25 kg',
    image: PET_PHOTOS.milo,
    healthNote: 'Cần bổ sung glucosamine',
  },
  {
    id: 2,
    name: 'Luna',
    type: 'cat',
    breed: 'Maine Coon',
    age: '2 tuổi',
    weight: '6.2 kg',
    image: PET_PHOTOS.luna,
  },
  {
    id: 3,
    name: 'Max',
    type: 'dog',
    breed: 'Poodle',
    age: '5 tuổi',
    weight: '12 kg',
    image: PET_PHOTOS.max,
    healthNote: 'Da nhạy cảm',
  },
  {
    id: 4,
    name: 'Mochi',
    type: 'cat',
    breed: 'British Shorthair',
    age: '1 tuổi',
    weight: '4.8 kg',
    image: PET_PHOTOS.mochi,
  },
]

// Ảnh sản phẩm từ kho local — 8 packshot nền trắng.
const PROD = (n: number) => `/imgs/prod${n}.jpg`

const RECS: RecProduct[] = [
  // ---- gợi ý cho Milo (Golden Retriever, 3 tuổi, cần glucosamine) ----
  { id: 101, name: 'Hạt Royal Canin Medium Adult 4kg', brand: 'Royal Canin', category: 'Thức ăn', petType: 'dog', price: 452_000, oldPrice: 520_000, rating: 4.8, sold: 1284, badge: 'Bán chạy', image: PROD(1), reason: 'Hạt cho chó trưởng thành cỡ trung, khớp với cân nặng của Milo' },
  { id: 102, name: 'Viên glucosamine & chondroitin 60 viên', brand: 'ANF', category: 'Sức khoẻ', petType: 'dog', price: 268_000, rating: 4.7, sold: 441, badge: 'Cần thiết', image: PROD(7), reason: 'Hỗ trợ khớp cho Golden Retriever sau 3 tuổi' },
  { id: 103, name: 'Bánh thưởng huấn luyện vị bò 200g', brand: 'Pedigree', category: 'Bánh thưởng', petType: 'dog', price: 74_000, rating: 4.5, sold: 1863, image: PROD(6), reason: 'Huấn luyện với reward tự nhiên' },
  { id: 104, name: 'Dầu cá hồi Omega-3 250ml', brand: 'ANF', category: 'Sức khoẻ', petType: 'dog', price: 192_000, rating: 4.7, sold: 418, image: PROD(5), reason: 'Bổ sung Omega cho lông bóng và da khoẻ' },
  { id: 105, name: 'Đệm vải oxford chống nước 70cm', brand: 'KitCat', category: 'Ổ nằm', petType: 'dog', price: 385_000, rating: 4.4, sold: 264, image: PROD(2), reason: 'Kích thước phù hợp cho Milo nằm thoải mái' },
  { id: 106, name: 'Xương gặm cao su massage nướu', brand: 'KitCat', category: 'Đồ chơi', petType: 'dog', price: 96_000, rating: 4.5, sold: 631, image: PROD(4), reason: 'Giảm stress khi vắng chủ, sạch răng' },

  // ---- gợi ý cho Luna (Maine Coon, 2 tuổi) ----
  { id: 201, name: 'Hạt Royal Canin Kitten 2kg', brand: 'Royal Canin', category: 'Thức ăn', petType: 'cat', price: 385_000, rating: 4.9, sold: 728, badge: 'Bán chạy', image: PROD(1), reason: 'Dinh dưỡng cho mèo đang phát triển tối ưu' },
  { id: 202, name: 'Pate Nekko cá hồi 70g (lốc 12)', brand: 'Nekko', category: 'Thức ăn', petType: 'cat', price: 118_000, oldPrice: 144_000, rating: 4.7, sold: 2047, badge: 'Giảm 18%', image: PROD(3), reason: 'Protein cao cho Maine Coon lông dài' },
  { id: 203, name: 'Trụ cào móng sisal 45cm', brand: 'Cattyman', category: 'Đồ chơi', petType: 'cat', price: 189_000, rating: 4.5, sold: 624, image: PROD(4), reason: 'Bảo vệ sofa, đồ đạc khỏi móng Luna' },
  { id: 204, name: 'Gel dinh dưỡng cho mèo 100g', brand: 'KitCat', category: 'Sức khoẻ', petType: 'cat', price: 156_000, rating: 4.5, sold: 537, image: PROD(5), reason: 'Bổ sung khi Luna lười ăn' },
  { id: 205, name: 'Ổ tròn lông cừu cho mèo Ø50cm', brand: 'KitCat', category: 'Ổ nằm', petType: 'cat', price: 419_000, rating: 4.7, sold: 265, image: PROD(2), reason: 'Ổ tròn giữ ấm — Luna thích cuộn' },
  { id: 206, name: 'Cỏ mèo sấy khô catnip 30g', brand: 'KitCat', category: 'Bánh thưởng', petType: 'cat', price: 42_000, rating: 4.5, sold: 2087, image: PROD(7), reason: 'Thưởng tự nhiên, giúp Luna thư giãn' },

  // ---- gợi ý cho Max (Poodle, 5 tuổi, da nhạy cảm) ----
  { id: 301, name: 'Hạt Monge Sensitive Skin 3kg', brand: 'Monge', category: 'Thức ăn', petType: 'dog', price: 418_000, rating: 4.6, sold: 302, badge: 'Nhạy cảm', image: PROD(3), reason: 'Không gluten, tốt cho da nhạy cảm của Max' },
  { id: 302, name: 'Sữa tắm dịu nhạy cảm 450ml', brand: 'Monge', category: 'Vệ sinh', petType: 'dog', price: 198_000, rating: 4.6, sold: 487, image: PROD(5), reason: 'Không hương liệu, phù hợp da Max' },
  { id: 303, name: 'Xương gặm sạch răng bạc hà 12 thanh', brand: 'Pedigree', category: 'Sức khoẻ', petType: 'dog', price: 96_000, rating: 4.4, sold: 1108, image: PROD(8), reason: 'Vệ sinh răng miệng hàng ngày cho Poodle' },
  { id: 304, name: 'Canxi & vitamin tổng hợp 100 viên', brand: 'Monge', category: 'Sức khoẻ', petType: 'dog', price: 289_000, rating: 4.6, sold: 267, image: PROD(7), reason: 'Bổ sung canxi cho xương sau 5 tuổi' },
  { id: 305, name: 'Bóng cao su phát sáng size M', brand: 'KitCat', category: 'Đồ chơi', petType: 'dog', price: 89_000, rating: 4.3, sold: 412, image: PROD(4), reason: 'Poodle thích đuổi bóng —运动 tại nhà' },
  { id: 306, name: 'Chăn lông cừu mùa lạnh 100×70cm', brand: 'Pedigree', category: 'Ổ nằm', petType: 'dog', price: 215_000, rating: 4.5, sold: 438, image: PROD(6), reason: 'Giữ ấm cho Max vào ban đêm' },

  // ---- gợi ý cho Mochi (British Shorthair, 1 tuổi) ----
  { id: 401, name: 'Hạt ANF Cat Adult 1.5kg', brand: 'ANF', category: 'Thức ăn', petType: 'cat', price: 168_000, oldPrice: 189_000, rating: 4.2, sold: 445, badge: 'Giảm 11%', image: PROD(3), reason: 'Bắt đầu chuyển từ kitten sang adult' },
  { id: 402, name: 'Sữa tắm không cần xả 200ml', brand: 'Me-O', category: 'Vệ sinh', petType: 'cat', price: 145_000, rating: 4.5, sold: 1194, image: PROD(5), reason: 'Wipe-down cho British Shorthair lười tắm' },
  { id: 403, name: 'Lược chải rụng lông hai mặt', brand: 'KitCat', category: 'Vệ sinh', petType: 'cat', price: 112_000, rating: 4.3, sold: 883, image: PROD(2), reason: 'Lông ngắn nhưng rụng nhiều — cần chải đều' },
  { id: 404, name: 'Đường hầm vải gấp gọn 3 ngả', brand: 'KitCat', category: 'Đồ chơi', petType: 'cat', price: 245_000, rating: 4.7, sold: 336, image: PROD(4), reason: 'Mochi thích ẩn núp — đường hầm là tổ lý tưởng' },
  { id: 405, name: 'Khăn lau vệ sinh cho mèo 100 tờ', brand: 'Me-O', category: 'Vệ sinh', petType: 'cat', price: 65_000, rating: 4.3, sold: 1783, image: PROD(6), reason: 'Lau chân, tai, mắt nhanh hàng ngày' },
  { id: 406, name: 'Snack cá ngừ dạng que 15g (lốc 12)', brand: 'Nekko', category: 'Bánh thưởng', petType: 'cat', price: 88_000, rating: 4.6, sold: 1541, image: PROD(7), reason: 'Thưởng giàu protein, ít calorie' },
]

export function getRecommendations(petId: number): RecProduct[] {
  return RECS.filter((r) => {
    const pet = MOCK_PETS.find((p) => p.id === petId)
    if (!pet) return false
    return r.petType === pet.type
  })
}

export function getRecommendationCount(petId: number): number {
  return getRecommendations(petId).length
}
