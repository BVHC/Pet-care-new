// Chữ cái đầu thay ảnh bác sĩ (UC15): bỏ tiền tố học hàm ("BS.", "ThS.", "Bác sĩ"), lấy chữ đầu của họ và của tên cuối.

const TITLE_PREFIX = /^(?:(?:(?:BS|ThS|TS|PGS|GS|Dr)\.?|Bác sĩ)(?:\s+|$))+/iu;

export function doctorInitials(fullName: string): string {
  const words = fullName.normalize('NFC').trim().replace(TITLE_PREFIX, '').split(/\s+/).filter(Boolean);
  if (words.length === 0) return '?';
  const first = Array.from(words[0])[0];
  const last = words.length > 1 ? Array.from(words[words.length - 1])[0] : '';
  return (first + last).toLocaleUpperCase('vi');
}
