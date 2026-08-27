import { Link } from 'react-router-dom';
import { Heart, Award, Clock, MapPin, Phone, Mail, Shield, Star } from 'lucide-react';

const TEAM = [
  { name: 'Dr. Nguyễn Văn Minh', role: 'Bác sĩ Thú y chuyên nghiệp', image: 'https://images.unsplash.com/photo-1612349317150-e413f6a5b16d?w=400', exp: '15 năm kinh nghiệm' },
  { name: 'Dr. Trần Thị Lan', role: 'Chuyên gia Dinh dưỡng', image: 'https://images.unsplash.com/photo-1559839734-2b71ea197ec2?w=400', exp: '10 năm kinh nghiệm' },
  { name: 'Ngô Thị Hương', role: 'Chuyên gia Chăm sóc & Spa', image: 'https://images.unsplash.com/photo-1594824476967-48c8b964273f?w=400', exp: '8 năm kinh nghiệm' },
  { name: 'Lê Minh Tuấn', role: 'Bác sĩ Phẫu thuật', image: 'https://images.unsplash.com/photo-1537368910025-700350fe46c7?w=400', exp: '12 năm kinh nghiệm' },
];

const VALUES = [
  { icon: Heart, title: 'Yêu thương', desc: 'Mỗi thú cưng đều xứng đáng được yêu thương và chăm sóc tốt nhất.' },
  { icon: Shield, title: 'An toàn', desc: 'Tiêu chuẩn vệ sinh, an toàn và chất lượng dịch vụ hàng đầu.' },
  { icon: Award, title: 'Chuyên nghiệp', desc: 'Đội ngũ bác sĩ và nhân viên được đào tạo bài bản, giàu kinh nghiệm.' },
  { icon: Star, title: 'Chất lượng', desc: 'Sản phẩm và dịch vụ luôn đạt tiêu chuẩn cao nhất.' },
];

export function AboutPage() {
  return (
    <div>
      {/* Hero */}
      <section className="relative overflow-hidden bg-gradient-to-br from-[#FDF6EC] to-amber-50 px-6 py-20 text-center">
        <div className="mx-auto max-w-[800px]">
          <div className="mb-4 inline-flex items-center gap-2 rounded-full bg-[#843122]/10 px-4 py-2 text-sm font-bold text-[#843122]">
            <Heart size={16} className="fill-red-500 text-red-500" />
            Về chúng tôi
          </div>
          <h1 className="font-[var(--font-friendly)] text-4xl font-extrabold text-gray-900 sm:text-5xl">
            PetCare - Người bạn đồng hành<br />đáng tin cậy của thú cưng
          </h1>
          <p className="mt-6 text-lg text-gray-600 leading-relaxed">
            PetCare được thành lập với sứ mệnh mang đến dịch vụ chăm sóc thú cưng toàn diện,
            từ y tế, dinh dưỡng đến spa và lưu trú. Chúng tôi tin rằng mỗi thú cưng đều
            xứng đáng được yêu thương và có cuộc sống khỏe mạnh nhất.
          </p>
          <div className="mt-8 flex justify-center gap-4">
            <Link to="/booking" className="rounded-full bg-[#843122] px-8 py-3 font-bold text-white hover:bg-[#6a2517] transition-colors">
              Đặt lịch ngay
            </Link>
            <Link to="/shop" className="rounded-full border-2 border-[#843122] px-8 py-3 font-bold text-[#843122] hover:bg-[#843122] hover:text-white transition-colors">
              Mua sắm ngay
            </Link>
          </div>
        </div>
      </section>

      {/* Stats */}
      <section className="bg-[#843122] px-6 py-12">
        <div className="mx-auto max-w-[1000px] grid grid-cols-2 gap-8 text-center text-white sm:grid-cols-4">
          <div>
            <div className="text-4xl font-extrabold">10K+</div>
            <div className="mt-1 text-white/80">Khách hàng tin tưởng</div>
          </div>
          <div>
            <div className="text-4xl font-extrabold">15+</div>
            <div className="mt-1 text-white/80">Năm kinh nghiệm</div>
          </div>
          <div>
            <div className="text-4xl font-extrabold">50+</div>
            <div className="mt-1 text-white/80">Bác sĩ chuyên nghiệp</div>
          </div>
          <div>
            <div className="text-4xl font-extrabold">4.9★</div>
            <div className="mt-1 text-white/80">Đánh giá khách hàng</div>
          </div>
        </div>
      </section>

      {/* Values */}
      <section className="px-6 py-16">
        <div className="mx-auto max-w-[1000px]">
          <div className="text-center mb-12">
            <h2 className="font-[var(--font-friendly)] text-3xl font-extrabold text-gray-900">
              Giá trị cốt lõi
            </h2>
            <p className="mt-3 text-gray-600">Những gì chúng tôi luôn theo đuổi</p>
          </div>
          <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
            {VALUES.map((v) => (
              <div key={v.title} className="rounded-xl border border-gray-100 bg-white p-6 text-center shadow-sm hover:shadow-md transition-shadow">
                <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-[#843122]/10">
                  <v.icon size={28} className="text-[#843122]" />
                </div>
                <h3 className="font-bold text-gray-900">{v.title}</h3>
                <p className="mt-2 text-sm text-gray-600">{v.desc}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* Team */}
      <section className="bg-gray-50 px-6 py-16">
        <div className="mx-auto max-w-[1000px]">
          <div className="text-center mb-12">
            <h2 className="font-[var(--font-friendly)] text-3xl font-extrabold text-gray-900">
              Đội ngũ chuyên gia
            </h2>
            <p className="mt-3 text-gray-600">Những người bạn đồng hành của thú cưng</p>
          </div>
          <div className="grid gap-8 sm:grid-cols-2 lg:grid-cols-4">
            {TEAM.map((member) => (
              <div key={member.name} className="text-center">
                <div className="mx-auto mb-4 h-32 w-32 overflow-hidden rounded-full border-4 border-white shadow-lg">
                  <img src={member.image} alt={member.name} className="h-full w-full object-cover" />
                </div>
                <h3 className="font-bold text-gray-900">{member.name}</h3>
                <p className="text-sm text-[#843122]">{member.role}</p>
                <p className="mt-1 flex items-center justify-center gap-1 text-xs text-gray-500">
                  <Clock size={12} /> {member.exp}
                </p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* Contact */}
      <section className="px-6 py-16">
        <div className="mx-auto max-w-[1000px]">
          <div className="text-center mb-12">
            <h2 className="font-[var(--font-friendly)] text-3xl font-extrabold text-gray-900">
              Liên hệ với chúng tôi
            </h2>
          </div>
          <div className="grid gap-6 sm:grid-cols-3">
            <div className="flex items-start gap-4 rounded-xl border border-gray-100 bg-white p-6 shadow-sm">
              <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-[#843122]/10">
                <MapPin size={24} className="text-[#843122]" />
              </div>
              <div>
                <h3 className="font-bold text-gray-900">Địa chỉ</h3>
                <p className="mt-1 text-sm text-gray-600">123 Đường Nguyễn Trãi, Quận 1, TP.HCM</p>
              </div>
            </div>
            <div className="flex items-start gap-4 rounded-xl border border-gray-100 bg-white p-6 shadow-sm">
              <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-[#843122]/10">
                <Phone size={24} className="text-[#843122]" />
              </div>
              <div>
                <h3 className="font-bold text-gray-900">Hotline</h3>
                <p className="mt-1 text-sm text-gray-600">0901 234 567</p>
                <p className="text-sm text-gray-500">8:00 - 20:00, Thứ 2 - CN</p>
              </div>
            </div>
            <div className="flex items-start gap-4 rounded-xl border border-gray-100 bg-white p-6 shadow-sm">
              <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-[#843122]/10">
                <Mail size={24} className="text-[#843122]" />
              </div>
              <div>
                <h3 className="font-bold text-gray-900">Email</h3>
                <p className="mt-1 text-sm text-gray-600">contact@petcare.vn</p>
                <p className="text-sm text-gray-500">Phản hồi trong 24h</p>
              </div>
            </div>
          </div>
        </div>
      </section>
    </div>
  );
}
