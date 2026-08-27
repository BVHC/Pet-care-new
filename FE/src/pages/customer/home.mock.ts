import {
  ShoppingBag,
  HeartHandshake,
  CalendarCheck,
  Sparkles,
  House,
  Truck,
  Stethoscope,
  BadgePercent,
  ShieldCheck,
  PawPrint,
  Users,
  Clock,
  Smile,
  Search,
  Heart,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'

// Ảnh local từ public/imgs
const PHOTOS = {
  heroFullBleed: '/imgs/hero-fullbleed.png',
  heroDog: '/imgs/hero-dog.png',
  shapePaw: '/imgs/shape-paw.png',
  shapeBone: '/imgs/shape-bone.png',
  shapePaw2: '/imgs/shape-paw2.png',
  shapeCat: '/imgs/shape-cat.png',
  vetCounter: '/imgs/vet-counter.jpg',
  catShop: '/imgs/prod3.jpg',
  catVet: '/imgs/vet-counter.jpg',
  catSpa: '/imgs/team1.jpg',
  catBoarding: '/imgs/team4.jpg',
  prodKibble: '/imgs/prod1.jpg',
  prodBed: '/imgs/prod2.jpg',
  prodTreats: '/imgs/prod5.jpg',
  prodToy: '/imgs/prod4.jpg',
  ctaBanner: '/imgs/cta-banner.jpg',
  whyArt: '/imgs/team2.jpg',
  doodleBone: '/imgs/download3.png',
  doodlePaw: '/imgs/download11.png',
  doodleYarn: '/imgs/download1.png',
  doodleCollar: '/imgs/download5.png',
}

// Popup nhỏ bo tròn xung quanh ảnh hero (sản phẩm + thú cưng nổi).
export const HERO_POPS: { image: string; label: string; sub: string; pos: string }[] = [
  { image: PHOTOS.prodKibble, label: 'Hạt hữu cơ', sub: '199K', pos: 'p1' },
  { image: PHOTOS.prodTreats, label: 'Snack thưởng', sub: '45K', pos: 'p2' },
  { image: PHOTOS.prodToy, label: 'Pate mèo', sub: '25K', pos: 'p3' },
]

export interface Category {
  icon: LucideIcon
  image?: string
  photo: string
  name: string
  desc: string
  page: string
}

export const CATEGORIES: Category[] = [
  {
    icon: ShoppingBag,
    photo: PHOTOS.catShop,
    name: 'Cửa hàng',
    desc: 'Thức ăn, đồ chơi & sức khỏe',
    page: 'listing',
  },
  {
    icon: HeartHandshake,
    image: '/imgs/PetIcon.svg',
    photo: PHOTOS.prodToy,
    name: 'Dành cho thú cưng của bạn',
    desc: 'Gợi ý theo loài & độ tuổi',
    page: 'recommend',
  },
  {
    icon: CalendarCheck,
    image: '/imgs/ThuY.svg',
    photo: PHOTOS.catVet,
    name: 'Đặt lịch khám thú y',
    desc: 'Khám tổng quát & tiêm phòng',
    page: 'booking',
  },
  {
    icon: Sparkles,
    photo: PHOTOS.catSpa,
    name: 'Spa & cắt tỉa',
    desc: 'Tắm, cắt tỉa, cắt móng',
    page: 'booking',
  },
  {
    icon: House,
    image: '/imgs/SpaHotel.svg',
    photo: PHOTOS.catBoarding,
    name: 'Lưu trú',
    desc: 'Trông thú cưng qua đêm',
    page: 'hotel',
  },
]

export interface FeaturedProduct {
  id: string;
  name: string;
  category: string;
  price: string;
  rating: string;
  badge?: string;
  image: string;
  petType: 'dog' | 'cat';
}

export const FEATURED: FeaturedProduct[] = [
  {
    id: 'p1',
    name: 'Grain-Free Kibble',
    category: 'Dog Food',
    price: '$34.99',
    rating: '4.8',
    badge: 'Best seller',
    image: PHOTOS.prodKibble,
    petType: 'dog',
  },
  {
    id: 'p2',
    name: 'Orthopedic Bed',
    category: 'Bedding',
    price: '$59.00',
    rating: '4.9',
    image: PHOTOS.prodBed,
    petType: 'dog',
  },
  {
    id: 'p3',
    name: 'Salmon Treats',
    category: 'Treats',
    price: '$12.50',
    rating: '4.7',
    badge: 'New',
    image: PHOTOS.prodTreats,
    petType: 'cat',
  },
  {
    id: 'p4',
    name: 'Interactive Puzzle',
    category: 'Dog Toys',
    price: '$18.99',
    rating: '4.5',
    image: PHOTOS.prodToy,
    petType: 'dog',
  },
]

// Lọc sản phẩm nổi bật theo loài — hàm thuần, dùng cho tab ALL/CHÓ/MÈO ở HomePage.
export function filterFeaturedByPetType(
  items: FeaturedProduct[],
  filter: 'all' | 'dog' | 'cat'
): FeaturedProduct[] {
  if (filter === 'all') return items
  return items.filter((p) => p.petType === filter)
}

export const WHY: { icon: LucideIcon; title: string; desc: string }[] = [
  {
    icon: Truck,
    title: 'Giao hàng nhanh',
    desc: 'Hầu hết đơn hàng được giao trong 24 giờ, tận cửa nhà bạn.',
  },
  {
    icon: Stethoscope,
    title: 'Bác sĩ uy tín',
    desc: 'Mọi phòng khám đối tác đều có giấy phép và được kiểm chứng.',
  },
  { icon: BadgePercent, title: 'Giá tốt', desc: 'Giá ưu đãi mỗi ngày, không cần thẻ thành viên.' },
  {
    icon: ShieldCheck,
    title: 'Bảo hành uy tín',
    desc: 'Cam kết đổi trả và hoàn tiền rõ ràng cho mọi đơn hàng.',
  },
]

export const TESTIMONIALS = [
  {
    name: 'Ngọc Anh',
    pet: 'Chủ của Bơ',
    quote:
      'Đặt lịch khám cho Bơ chỉ với hai cú chạm và bác sĩ rất tận tâm. Đây mới đúng là cách chăm sóc thú cưng.',
    avatar: '/imgs/author1.png',
  },
  {
    name: 'Minh Đức',
    pet: 'Chủ của Mực',
    quote: 'Thức ăn giao ngay sáng hôm sau và lịch hẹn cắt tỉa của Mực cũng dễ dàng dời lịch.',
    avatar: '/imgs/author2.png',
  },
  {
    name: 'Thu Hà',
    pet: 'Chủ của Sữa',
    quote: 'Dịch vụ lưu trú giúp tôi hoàn toàn yên tâm khi đi xa — có cập nhật ảnh mỗi ngày.',
    avatar: '/imgs/author3.png',
  },
]

export const TRUST_ITEMS: { icon: LucideIcon; label: string }[] = [
  { icon: Stethoscope, label: 'Bác sĩ có giấy phép' },
  { icon: Truck, label: 'Giao hàng nhanh' },
  { icon: ShieldCheck, label: 'Uy tín & an toàn' },
  { icon: BadgePercent, label: 'Giá tốt' },
]

export const STATS: { icon: LucideIcon; image?: string; value: string; label: string }[] = [
  { icon: PawPrint, image: '/imgs/100.svg', value: '10K+', label: 'Thú cưng được chăm sóc' },
  { icon: Users, image: '/imgs/ThuY.svg', value: '500+', label: 'Bác sĩ & chuyên gia' },
  { icon: Clock, image: '/imgs/ship.svg', value: '24h', label: 'Giao hàng tận nơi' },
  { icon: Smile, image: '/imgs/support.svg', value: '4.9/5', label: 'Đánh giá hài lòng' },
]

export const STEPS: { icon: LucideIcon; step: string; title: string; desc: string }[] = [
  {
    icon: Search,
    step: '01',
    title: 'Chọn dịch vụ',
    desc: 'Duyệt cửa hàng, phòng khám, spa hoặc lưu trú phù hợp với thú cưng.',
  },
  {
    icon: CalendarCheck,
    step: '02',
    title: 'Đặt lịch',
    desc: 'Chọn khung giờ và xác nhận chỉ với vài cú chạm, thanh toán linh hoạt.',
  },
  {
    icon: Heart,
    step: '03',
    title: 'Tận hưởng chăm sóc',
    desc: 'Nhận hàng tận cửa hoặc đưa thú cưng tới — phần còn lại để chúng tôi lo.',
  },
]

// Logo brand thật (public/imgs/brands).
export const PARTNERS: { name: string; logo: string }[] = [
  { name: 'Royal Canin', logo: '/imgs/brands/Royal_20Canin.jpg' },
  { name: 'Pedigree', logo: '/imgs/brands/Pedigree.jpg' },
  { name: 'Whiskas', logo: '/imgs/brands/Whiskas.jpg' },
  { name: 'SmartHeart', logo: '/imgs/brands/SmartHeart.jpg' },
  { name: 'Nekko', logo: '/imgs/brands/Nekko.jpg' },
  { name: 'Me-O', logo: '/imgs/brands/Me-O.jpg' },
  { name: 'KitCat', logo: '/imgs/brands/KitCat.jpg' },
  { name: 'Monge', logo: '/imgs/brands/Monge.jpg' },
]

export const FAQS: { q: string; a: string }[] = [
  {
    q: 'PetCare hoạt động ở khu vực nào?',
    a: 'Hiện chúng tôi phục vụ tại các thành phố lớn và đang mở rộng liên tục. Nhập địa chỉ khi đặt lịch để kiểm tra khả năng phục vụ.',
  },
  {
    q: 'Tôi có thể đổi hoặc hủy lịch hẹn không?',
    a: 'Có. Bạn có thể dời hoặc hủy lịch miễn phí trước 24 giờ, ngay trong mục Lịch hẹn của tài khoản.',
  },
  {
    q: 'Các bác sĩ thú y có được cấp phép không?',
    a: 'Mọi phòng khám và bác sĩ đối tác đều được kiểm chứng giấy phép hành nghề trước khi tham gia hệ thống.',
  },
  {
    q: 'Chi phí giao hàng như thế nào?',
    a: 'Miễn phí giao hàng cho đơn từ một mức tối thiểu; phần lớn đơn được giao trong vòng 24 giờ.',
  },
]

export const DOCTORS = [
  { name: 'Daria Andaloro', role: 'Veterinary Technician', photo: '/imgs/team1.jpg' },
  { name: 'Michael Brian', role: 'Medicine Specialist', photo: '/imgs/team2.jpg' },
  { name: 'Kenroly Gajon', role: 'Food Technician', photo: '/imgs/team3.jpg' },
  { name: 'Lizay Arianya', role: 'Veterinary Technician', photo: '/imgs/team4.jpg' },
]

export { PHOTOS }
