import { useState } from 'react';
import { Calendar, User, Phone, Stethoscope, Scissors, Bath, Heart } from 'lucide-react';
import { toast } from 'sonner';

const SERVICES = [
  { id: 'checkup', name: 'Khám tổng quát', icon: Stethoscope, desc: 'Kiểm tra sức khỏe toàn diện', price: '150.000đ' },
  { id: 'vaccine', name: 'Tiêm phòng', icon: Heart, desc: 'Vaccine phòng bệnh', price: '200.000đ' },
  { id: 'grooming', name: 'Cắt tỉa lông', icon: Scissors, desc: 'Làm đẹp cho thú cưng', price: '250.000đ' },
  { id: 'spa', name: 'Spa & tắm', icon: Bath, desc: 'Tắm, dưỡng lông', price: '300.000đ' },
];

const TIME_SLOTS = ['08:00', '09:00', '10:00', '11:00', '14:00', '15:00', '16:00', '17:00'];

export function BookingPage() {
  const [selectedService, setSelectedService] = useState<string | null>(null);
  const [selectedDate, setSelectedDate] = useState('');
  const [selectedTime, setSelectedTime] = useState<string | null>(null);
  const [form, setForm] = useState({
    petName: '',
    petType: 'dog',
    ownerName: '',
    phone: '',
    note: '',
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedService || !selectedDate || !selectedTime || !form.petName || !form.ownerName || !form.phone) {
      toast.error('Vui lòng điền đầy đủ thông tin!');
      return;
    }
    toast.success('Đặt lịch thành công! Chúng tôi sẽ liên hệ xác nhận.');
    setSelectedService(null);
    setSelectedDate('');
    setSelectedTime(null);
    setForm({ petName: '', petType: 'dog', ownerName: '', phone: '', note: '' });
  };

  return (
    <div className="mx-auto max-w-[1000px] px-4 py-8 sm:px-6">
      <div className="text-center mb-10">
        <h1 className="font-[var(--font-friendly)] text-3xl font-bold text-gray-900 sm:text-4xl">
          Đặt lịch khám & dịch vụ
        </h1>
        <p className="mt-3 text-gray-600">Đặt lịch nhanh chóng, chuyên nghiệp cho thú cưng của bạn</p>
      </div>

      <form onSubmit={handleSubmit} className="space-y-8">
        {/* Chọn dịch vụ */}
        <section>
          <h2 className="mb-4 flex items-center gap-2 font-[var(--font-friendly)] text-xl font-bold text-gray-900">
            <Stethoscope size={24} className="text-[#843122]" /> Chọn dịch vụ
          </h2>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            {SERVICES.map((service) => (
              <button
                key={service.id}
                type="button"
                onClick={() => setSelectedService(service.id)}
                className={`flex items-start gap-4 rounded-xl border-2 p-4 text-left transition-all ${
                  selectedService === service.id
                    ? 'border-[#843122] bg-amber-50'
                    : 'border-gray-200 bg-white hover:border-gray-300'
                }`}
              >
                <div className={`flex h-12 w-12 shrink-0 items-center justify-center rounded-xl ${
                  selectedService === service.id ? 'bg-[#843122] text-white' : 'bg-gray-100 text-gray-600'
                }`}>
                  <service.icon size={24} />
                </div>
                <div>
                  <p className="font-bold text-gray-900">{service.name}</p>
                  <p className="text-sm text-gray-500">{service.desc}</p>
                  <p className="mt-1 text-sm font-semibold text-[#843122]">{service.price}</p>
                </div>
              </button>
            ))}
          </div>
        </section>

        {/* Thông tin thú cưng */}
        <section>
          <h2 className="mb-4 flex items-center gap-2 font-[var(--font-friendly)] text-xl font-bold text-gray-900">
            <User size={24} className="text-[#843122]" /> Thông tin thú cưng
          </h2>
          <div className="rounded-xl border border-gray-200 bg-white p-6">
            <div className="grid gap-4 sm:grid-cols-2">
              <div>
                <label className="mb-2 block text-sm font-semibold text-gray-700">Tên thú cưng</label>
                <input
                  type="text"
                  value={form.petName}
                  onChange={(e) => setForm({ ...form, petName: e.target.value })}
                  placeholder="VD: Milo, Luna"
                  className="w-full rounded-lg border border-gray-200 px-4 py-3 focus:border-[#843122] focus:outline-none"
                />
              </div>
              <div>
                <label className="mb-2 block text-sm font-semibold text-gray-700">Loại thú cưng</label>
                <select
                  value={form.petType}
                  onChange={(e) => setForm({ ...form, petType: e.target.value })}
                  className="w-full rounded-lg border border-gray-200 px-4 py-3 focus:border-[#843122] focus:outline-none"
                >
                  <option value="dog">Chó</option>
                  <option value="cat">Mèo</option>
                  <option value="other">Khác</option>
                </select>
              </div>
            </div>
          </div>
        </section>

        {/* Chọn ngày & giờ */}
        <section>
          <h2 className="mb-4 flex items-center gap-2 font-[var(--font-friendly)] text-xl font-bold text-gray-900">
            <Calendar size={24} className="text-[#843122]" /> Chọn ngày & giờ
          </h2>
          <div className="grid gap-6 sm:grid-cols-2">
            <div className="rounded-xl border border-gray-200 bg-white p-6">
              <label className="mb-2 block text-sm font-semibold text-gray-700">Ngày đặt lịch</label>
              <input
                type="date"
                value={selectedDate}
                onChange={(e) => setSelectedDate(e.target.value)}
                min={new Date().toISOString().split('T')[0]}
                className="w-full rounded-lg border border-gray-200 px-4 py-3 focus:border-[#843122] focus:outline-none"
              />
            </div>
            <div className="rounded-xl border border-gray-200 bg-white p-6">
              <label className="mb-2 block text-sm font-semibold text-gray-700">Giờ hẹn</label>
              <div className="grid grid-cols-4 gap-2">
                {TIME_SLOTS.map((time) => (
                  <button
                    key={time}
                    type="button"
                    onClick={() => setSelectedTime(time)}
                    className={`rounded-lg border py-2 text-sm font-medium transition-colors ${
                      selectedTime === time
                        ? 'border-[#843122] bg-[#843122] text-white'
                        : 'border-gray-200 hover:border-gray-300'
                    }`}
                  >
                    {time}
                  </button>
                ))}
              </div>
            </div>
          </div>
        </section>

        {/* Thông tin liên hệ */}
        <section>
          <h2 className="mb-4 flex items-center gap-2 font-[var(--font-friendly)] text-xl font-bold text-gray-900">
            <Phone size={24} className="text-[#843122]" /> Thông tin liên hệ
          </h2>
          <div className="rounded-xl border border-gray-200 bg-white p-6">
            <div className="grid gap-4 sm:grid-cols-2">
              <div>
                <label className="mb-2 block text-sm font-semibold text-gray-700">Họ tên chủ nuôi <span className="text-red-500">*</span></label>
                <input
                  type="text"
                  value={form.ownerName}
                  onChange={(e) => setForm({ ...form, ownerName: e.target.value })}
                  placeholder="Nguyễn Văn A"
                  className="w-full rounded-lg border border-gray-200 px-4 py-3 focus:border-[#843122] focus:outline-none"
                />
              </div>
              <div>
                <label className="mb-2 block text-sm font-semibold text-gray-700">Số điện thoại <span className="text-red-500">*</span></label>
                <input
                  type="tel"
                  value={form.phone}
                  onChange={(e) => setForm({ ...form, phone: e.target.value })}
                  placeholder="0901 234 567"
                  className="w-full rounded-lg border border-gray-200 px-4 py-3 focus:border-[#843122] focus:outline-none"
                />
              </div>
            </div>
            <div className="mt-4">
              <label className="mb-2 block text-sm font-semibold text-gray-700">Ghi chú</label>
              <textarea
                value={form.note}
                onChange={(e) => setForm({ ...form, note: e.target.value })}
                placeholder="Triệu chứng, yêu cầu đặc biệt..."
                rows={3}
                className="w-full rounded-lg border border-gray-200 px-4 py-3 focus:border-[#843122] focus:outline-none resize-none"
              />
            </div>
          </div>
        </section>

        {/* Submit */}
        <div className="text-center">
          <button
            type="submit"
            className="rounded-full bg-[#843122] px-12 py-4 text-lg font-bold text-white hover:bg-[#6a2517] transition-colors"
          >
            Xác nhận đặt lịch
          </button>
        </div>
      </form>
    </div>
  );
}
