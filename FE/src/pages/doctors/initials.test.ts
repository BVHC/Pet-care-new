import { describe, expect, it } from 'vitest';
import { doctorInitials } from './initials';

describe('doctorInitials', () => {
  it('bỏ tiền tố học hàm, lấy chữ cái đầu của họ và tên cuối', () => {
    expect(doctorInitials('BS. Minh Anh')).toBe('MA');
    expect(doctorInitials('ThS. BS. Lê Thị Hoa')).toBe('LH');
    expect(doctorInitials('Bác sĩ Nguyễn Văn Minh')).toBe('NM');
  });

  it('một từ thì lấy một chữ, giữ nguyên dấu tiếng Việt và viết hoa', () => {
    expect(doctorInitials('BS. đạt')).toBe('Đ');
    expect(doctorInitials('Ánh')).toBe('Á');
  });

  it('chuỗi rỗng hoặc chỉ có tiền tố thì trả dấu hỏi thay vì rỗng', () => {
    expect(doctorInitials('')).toBe('?');
    expect(doctorInitials('   ')).toBe('?');
    expect(doctorInitials('BS.')).toBe('?');
  });
});
