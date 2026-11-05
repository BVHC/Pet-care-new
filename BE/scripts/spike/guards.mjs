// Các lớp lọc / định tuyến thuần (không mạng, không CSDL) theo docs/07-ai-subsystem.md §4.1–4.2.
// Danh sách từ khóa dưới đây sẽ thành tệp tài nguyên của BE ở T77; ở spike để thẳng trong mã cho gọn.

export const norm = (s) => s.normalize('NFC').toLowerCase().replace(/\s+/g, ' ').trim();
export const strip = (s) => s.normalize('NFD').replace(/\p{M}/gu, '').replace(/đ/g, 'd');

const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const bounded = (p, flags = 'u') => new RegExp(`(?<![\\p{L}\\p{N}])${esc(p)}(?![\\p{L}\\p{N}])`, flags);

// Khớp cụm từ có ranh giới từ, ưu tiên dạng có dấu; dạng bỏ dấu chỉ cho cụm từ >= 2 âm tiết (BR-AI-03, 26).
export function hasPhrase(text, phrase) {
  const t = norm(text);
  const p = norm(phrase);
  if (bounded(p).test(t)) return true;
  return p.split(' ').length >= 2 && bounded(strip(p)).test(strip(t));
}
const anyPhrase = (text, list) => list.some((p) => hasPhrase(text, p));

// ---- BR-AI-03: từ khóa cấp cứu ----
const EMERGENCY = ['khó thở', 'thở dốc', 'co giật', 'ngộ độc', 'ăn nhầm thuốc', 'ăn nhầm chocolate', 'ăn nhầm hóa chất',
  'chảy máu không cầm', 'bất tỉnh', 'ngất', 'bị liệt', 'liệt chân', 'liệt nửa người', 'bụng căng cứng', 'xe đâm',
  'rơi từ trên cao', 'không đi tiểu'];
export const isEmergency = (q) => anyPhrase(q, EMERGENCY);

// ---- BR-AI-12: che dữ liệu cá nhân ----
export function maskPii(s) {
  return s
    .replace(/[\w.+-]+@[\w-]+(?:\.[\w-]+)+/g, '[email]')
    .replace(/(?<![\w])(?:\+84|0)(?:[ .-]?\d){8,9}(?!\d)/g, '[sđt]')
    .replace(/(?<!\d)(?:\d{12}|\d{9})(?!\d)/g, '[số]');
}
export function maskPetNames(s, pets) {
  let out = s.normalize('NFC');
  for (const p of pets) out = out.replace(bounded(p.name.normalize('NFC'), 'giu'), '[thú]');
  return out;
}

// ---- BR-AI-26: dấu hiệu sở hữu, ý định cá nhân ----
const STRONG = ['của tôi', 'của mình', 'nhà tôi', 'nhà mình', 'bé nhà', 'con nhà'];
const WEAK = ['sắp tới', 'sắp đến', 'kế tiếp', 'tiếp theo', 'đã đặt', 'đến hạn', 'quá hạn', 'hôm nay', 'ngày mai', 'tuần này'];
const KW_APPOINTMENT = ['lịch hẹn', 'đã đặt lịch', 'lịch khám', 'có lịch', 'hẹn khám'];
const KW_VACCINATION = ['tiêm', 'vaccine', 'vắc xin', 'tái chủng', 'mũi tiêm'];
const PRICE = ['giá bao nhiêu', 'giá tiêm', 'bao nhiêu tiền', 'chi phí', 'mấy tiền'];
const COMMON_NAMES = ['vàng', 'mít', 'đen', 'nâu', 'trắng', 'xám', 'cam', 'bơ', 'đốm'];
const NAME_PREFIX = ['bé', 'con', 'chú', 'em'];

// Tên thú (còn sống) được nhắc trong câu; tên trùng từ thông dụng chỉ tính khi đi sau bé / con / chú / em.
export function mentionedPets(q, pets) {
  return pets.filter((p) => {
    const n = norm(p.name);
    return COMMON_NAMES.includes(n) ? NAME_PREFIX.some((pre) => hasPhrase(q, `${pre} ${n}`)) : hasPhrase(q, n);
  });
}
const strongSign = (q, pets) => anyPhrase(q, STRONG) || mentionedPets(q, pets).length > 0;

// Trả 'APPOINTMENT_UPCOMING' | 'VACCINATION_SCHEDULE' | null. Public: truyền pets = [] (không so khớp tên thú, BR-AI-28a).
export function detectIntent(q, pets = []) {
  if (anyPhrase(q, PRICE)) return null;
  const appt = anyPhrase(q, KW_APPOINTMENT);
  const vacc = anyPhrase(q, KW_VACCINATION);
  const strong = strongSign(q, pets);
  const weak = anyPhrase(q, WEAK);
  if (appt && (strong || weak)) return 'APPOINTMENT_UPCOMING';
  if (vacc && strong) return 'VACCINATION_SCHEDULE';
  return null;
}

// Kết quả cá nhân theo số thú của khách (BR-AI-27). Chỉ phân loại; nội dung lấy CSDL là việc của T77.
export function personalResult(intent, q, pets) {
  if (intent === 'APPOINTMENT_UPCOMING') return { result: 'PERSONAL' };
  if (mentionedPets(q, pets).length > 0 || pets.length <= 1) return { result: 'PERSONAL' };
  return { result: 'NEED_PET_CHOICE', pets: pets.map((p) => ({ petId: p.id, name: p.name })) };
}

// ---- BR-AI-28b: hồ sơ bệnh án / đơn thuốc của thú cụ thể ----
const RECORD = ['bệnh án', 'đơn thuốc', 'toa thuốc', 'kết quả xét nghiệm', 'hồ sơ bệnh án'];
export const isMedicalRecordQuestion = (q, pets = []) => anyPhrase(q, RECORD) && strongSign(q, pets);

// ---- BR-AI-06: hậu kiểm liều thuốc theo ngữ cảnh ----
const NUM = String.raw`\d+(?:[.,]\d+)?(?:\s*(?:-|–|đến)\s*\d+(?:[.,]\d+)?)?`;
const RE_STRONG = new RegExp(String.raw`${NUM}\s*(?:mg|mcg|µg|μg|iu|ui|viên|ống)(?![\p{L}\p{N}])`, 'iu');
const RE_WEAK = new RegExp(String.raw`${NUM}\s*(?:ml|giọt)(?![\p{L}\p{N}])`, 'iu');
const DRUG_WORDS = ['thuốc', 'kháng sinh', 'siro', 'vaccine', 'vắc xin', 'tẩy giun', 'xổ giun', 'paracetamol', 'ibuprofen',
  'aspirin', 'amoxicillin', 'doxycycline', 'metronidazole', 'ivermectin', 'praziquantel', 'prednisolone', 'meloxicam'];

// Trả null nếu sạch, hoặc { rule: 'strong' | 'weak', sentence } khi phải thay bằng câu mẫu MEDICAL_REFUSAL.
export function doseViolation(answer) {
  for (const sentence of answer.split(/(?<=[.!?])\s+|\n+/)) {
    if (RE_STRONG.test(sentence)) return { rule: 'strong', sentence };
    if (RE_WEAK.test(sentence) && anyPhrase(sentence, DRUG_WORDS)) return { rule: 'weak', sentence };
  }
  return null;
}

// Các lớp trước truy hồi theo thứ tự §5.2: cấp cứu → bệnh án → ý định cá nhân. `result: null` nghĩa là đi tiếp truy hồi.
// Public không biết khách là ai nên không so khớp tên thú (BR-AI-28a).
export function preRoute(q, endpoint, pets) {
  const known = endpoint === 'me' ? pets : [];
  if (isEmergency(q)) return { result: 'EMERGENCY', intent: null };
  if (isMedicalRecordQuestion(q, known)) return { result: 'MEDICAL_REFUSAL', intent: null, via: 'MEDICAL_RECORD' };
  const intent = detectIntent(q, known);
  if (!intent) return { result: null, intent: null };
  return endpoint === 'public' ? { result: 'NEED_LOGIN', intent } : { ...personalResult(intent, q, pets), intent };
}

// Dấu vết lộ lời nhắc hệ thống (chỉ dùng để chấm bộ câu chèn chỉ thị).
export const leaksPrompt = (answer) => /Quy tắc bắt buộc|inScope|Đầu ra: JSON|Bạn là trợ lý tư vấn chăm sóc thú cưng/i.test(answer);

// Câu mẫu cố định (BR-AI-02, 07, 28)
export const CANNED = {
  OUT_OF_SCOPE: 'Mình chưa có thông tin cho câu hỏi này. Bạn có thể đặt lịch để bác sĩ tư vấn trực tiếp.',
  MEDICAL_REFUSAL: 'Mình không thể chẩn đoán, kê đơn hay nêu liều thuốc. Bạn hãy đặt lịch khám để bác sĩ tư vấn trực tiếp.',
  MEDICAL_RECORD: 'Mình không xem được hồ sơ bệnh án. Bạn xem ở Hồ sơ sức khỏe của thú (UC25) hoặc hỏi bác sĩ.',
  NEED_LOGIN: 'Vui lòng đăng nhập để xem lịch hẹn / lịch tiêm của bạn.',
};
