import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Calendar, Dog, Cat, Home, Wifi, Car, Coffee, Shield, Star, Check } from 'lucide-react';
import { toast } from 'sonner';
import { StayPriceCalculator } from '@/components/customer/StayPriceCalculator';

const ROOMS = [
  { id: 1, name: 'Phòng Standard', desc: 'Phòng nhỏ cho thú cưng đơn lẻ', price: 150000, icon: Home, image: 'https://images.unsplash.com/photo-1548199973-03cce0bbc87b?w=600', features: ['Giường êm', 'Bát ăn uống', 'TV'] },
  { id: 2, name: 'Phòng Deluxe', desc: 'Phòng rộng với không gian chơi', price: 250000, icon: Home, popular: true, image: 'https://images.unsplash.com/photo-1587300003388-59208cc962cb?w=600', features: ['Giường đôi', 'Khu vực chơi', 'Camera 24/7', 'Bữa ăn sáng'] },
  { id: 3, name: 'Phòng VIP', desc: 'Suite cao cấp với dịch vụ đặc biệt', price: 400000, icon: Star, image: 'https://images.unsplash.com/photo-1601758228041-f3b2795255f1?w=600', features: ['Phòng riêng lớn', 'Spa miễn phí', 'Camera 24/7', 'Bữa ăn cao cấp', 'Dịch vụ dắt đi dạo'] },
];

const SERVICES = [
  { name: 'Dắt đi dạo', price: '30.000đ/lần', icon: Dog },
  { name: 'Spa & tắm', price: '100.000đ', icon: Coffee },
  { name: 'Chăm sóc lông', price: '80.000đ', icon: Cat },
  { name: 'Khám sức khỏe', price: '150.000đ', icon: Shield },
];

export function HotelPage() {
  const [checkIn, setCheckIn] = useState('');
  const [checkOut, setCheckOut] = useState('');
  const [petType, setPetType] = useState<'dog' | 'cat'>('dog');
  const [selectedRoom, setSelectedRoom] = useState<number | null>(null);

  const handleBook = () => {
    if (!checkIn || !checkOut || !selectedRoom) {
      toast.error('Vui lòng chọn đầy đủ thông tin!');
      return;
    }
    toast.success('Đặt phòng thành công! Chúng tôi sẽ liên hệ xác nhận.');
  };

  return (
    <div>
      {/* Hero */}
      <section className="relative bg-gradient-to-br from-[#843122] to-[#5c2116] px-6 py-20 text-white">
        <div className="mx-auto max-w-[1000px] text-center">
          <div className="mb-4 inline-flex items-center gap-2 rounded-full bg-white/20 px-4 py-2 text-sm font-bold">
            <Home size={16} /> Khách sạn thú cưng
          </div>
          <h1 className="font-[var(--font-friendly)] text-4xl font-extrabold sm:text-5xl">
            Khách sạn 5 sao<br />cho thú cưng của bạn
          </h1>
          <p className="mt-6 text-lg opacity-90">
            Để thú cưng của bạn tận hưởng kỳ nghỉ tuyệt vời khi bạn vắng nhà.
            Dịch vụ cao cấp, đội ngũ chăm sóc tận tâm 24/7.
          </p>
          <Link
            to="/booking"
            className="mt-8 inline-flex items-center gap-2 rounded-full bg-white px-8 py-3 font-bold text-[#843122] hover:bg-gray-100 transition-colors"
          >
            <Calendar size={20} /> Đặt phòng ngay
          </Link>
        </div>
      </section>

      {/* Booking Form */}
      <section className="mx-auto max-w-[1000px] -mt-8 px-4 sm:px-6">
        <div className="rounded-2xl border border-gray-200 bg-white p-6 shadow-xl">
          <h2 className="mb-4 font-[var(--font-friendly)] text-xl font-bold text-gray-900">Đặt phòng</h2>
          <div className="grid gap-4 sm:grid-cols-4">
            <div>
              <label className="mb-2 block text-sm font-semibold text-gray-700">Loại thú cưng</label>
              <div className="flex gap-2">
                <button
                  onClick={() => setPetType('dog')}
                  className={`flex-1 flex items-center justify-center gap-2 rounded-lg border py-3 font-semibold transition-colors ${
                    petType === 'dog' ? 'border-[#843122] bg-[#843122]/10 text-[#843122]' : 'border-gray-200'
                  }`}
                >
                  <Dog size={20} /> Chó
                </button>
                <button
                  onClick={() => setPetType('cat')}
                  className={`flex-1 flex items-center justify-center gap-2 rounded-lg border py-3 font-semibold transition-colors ${
                    petType === 'cat' ? 'border-[#843122] bg-[#843122]/10 text-[#843122]' : 'border-gray-200'
                  }`}
                >
                  <Cat size={20} /> Mèo
                </button>
              </div>
            </div>
            <div>
              <label className="mb-2 block text-sm font-semibold text-gray-700">Ngày nhận phòng</label>
              <input
                type="date"
                value={checkIn}
                onChange={(e) => setCheckIn(e.target.value)}
                min={new Date().toISOString().split('T')[0]}
                className="w-full rounded-lg border border-gray-200 px-4 py-3 focus:border-[#843122] focus:outline-none"
              />
            </div>
            <div>
              <label className="mb-2 block text-sm font-semibold text-gray-700">Ngày trả phòng</label>
              <input
                type="date"
                value={checkOut}
                onChange={(e) => setCheckOut(e.target.value)}
                min={checkIn || new Date().toISOString().split('T')[0]}
                className="w-full rounded-lg border border-gray-200 px-4 py-3 focus:border-[#843122] focus:outline-none"
              />
            </div>
            <div className="flex items-end">
              <button
                onClick={handleBook}
                className="w-full rounded-lg bg-[#843122] py-3 font-bold text-white hover:bg-[#6a2517] transition-colors"
              >
                Tìm phòng
              </button>
            </div>
          </div>
        </div>
      </section>

      {/* Interactive Stay Price Calculator */}
      <section className="mx-auto max-w-[1000px] px-4 pt-14 sm:px-6">
        <StayPriceCalculator />
      </section>

      {/* Rooms */}
      <section className="mx-auto max-w-[1000px] px-4 py-12 sm:px-6">
        <h2 className="mb-8 text-center font-[var(--font-friendly)] text-3xl font-bold text-gray-900">Loại phòng</h2>
        <div className="grid gap-6 lg:grid-cols-3">
          {ROOMS.map((room) => (
            <div
              key={room.id}
              className={`overflow-hidden rounded-2xl border-2 bg-white shadow-sm transition-all ${
                selectedRoom === room.id ? 'border-[#843122] shadow-lg' : 'border-transparent'
              }`}
              onClick={() => setSelectedRoom(room.id === selectedRoom ? null : room.id)}
            >
              {room.popular && (
                <div className="bg-[#843122] px-4 py-1.5 text-center text-xs font-bold text-white flex items-center justify-center gap-1.5">
                  <Star size={13} className="fill-amber-300 text-amber-300" />
                  <span>Phổ biến nhất</span>
                </div>
              )}
              <div className="aspect-[4/3] bg-gray-100">
                <img src={room.image} alt={room.name} className="h-full w-full object-cover" />
              </div>
              <div className="p-6">
                <div className="flex items-center gap-2">
                  <room.icon size={20} className="text-[#843122]" />
                  <h3 className="font-bold text-gray-900">{room.name}</h3>
                </div>
                <p className="mt-2 text-sm text-gray-600">{room.desc}</p>
                <div className="mt-4 flex flex-wrap gap-2">
                  {room.features.map((f) => (
                    <span key={f} className="inline-flex items-center gap-1 rounded-full bg-gray-100 px-2 py-1 text-xs text-gray-600">
                      <Check size={10} /> {f}
                    </span>
                  ))}
                </div>
                <div className="mt-4 flex items-center justify-between">
                  <div>
                    <span className="font-bold text-[#843122]">{room.price.toLocaleString('vi-VN')}đ</span>
                    <span className="text-sm text-gray-500">/đêm</span>
                  </div>
                  <button
                    onClick={(e) => { e.stopPropagation(); setSelectedRoom(room.id); toast.info(`Đã chọn ${room.name}`); }}
                    className="rounded-lg bg-[#843122] px-4 py-2 text-sm font-bold text-white hover:bg-[#6a2517] transition-colors"
                  >
                    Chọn
                  </button>
                </div>
              </div>
            </div>
          ))}
        </div>
      </section>

      {/* Services */}
      <section className="bg-gray-50 px-4 py-12 sm:px-6">
        <div className="mx-auto max-w-[1000px]">
          <h2 className="mb-8 text-center font-[var(--font-friendly)] text-3xl font-bold text-gray-900">Dịch vụ thêm</h2>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {SERVICES.map((service) => (
              <div key={service.name} className="flex items-center gap-4 rounded-xl border border-gray-200 bg-white p-4">
                <div className="flex h-12 w-12 items-center justify-center rounded-full bg-[#843122]/10">
                  <service.icon size={24} className="text-[#843122]" />
                </div>
                <div>
                  <div className="font-semibold text-gray-900">{service.name}</div>
                  <div className="text-sm text-[#843122]">{service.price}</div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* Amenities */}
      <section className="mx-auto max-w-[1000px] px-4 py-12 sm:px-6">
        <h2 className="mb-8 text-center font-[var(--font-friendly)] text-3xl font-bold text-gray-900">Tiện nghi</h2>
        <div className="flex flex-wrap justify-center gap-6">
          {[
            { icon: Wifi, label: 'Wifi miễn phí' },
            { icon: Car, label: 'Bãi đỗ xe' },
            { icon: Coffee, label: 'Khu vực chờ' },
            { icon: Shield, label: 'Camera 24/7' },
          ].map((item) => (
            <div key={item.label} className="flex items-center gap-3 text-gray-600">
              <item.icon size={24} className="text-[#843122]" />
              <span className="font-semibold">{item.label}</span>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}
