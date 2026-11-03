import { render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Sheet, SheetClose, SheetContent, SheetFooter, SheetTitle } from './sheet';

// Bảng bên dùng chung (shadcn/Radix; menu nhân viên và ngăn chi tiết thú cưng). Hai lỗi đã gặp: nút X mang tên truy cập tiếng Anh "Close", và React báo
// "Function components cannot be given refs" ở SheetOverlay vì Radix Portal (asChild) gán ref cho con trực tiếp mà SheetOverlay là function component.

let consoleError: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
});
afterEach(() => {
  consoleError.mockRestore();
});

function Sample() {
  return (
    <Sheet open>
      <SheetContent>
        <SheetTitle>Tiêu đề mẫu</SheetTitle>
        <p>Nội dung thường</p>
        <SheetFooter>
          <SheetClose>Đóng</SheetClose>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  );
}

describe('Sheet dùng chung', () => {
  // Phải chạy ĐẦU TIÊN trong file: React chỉ cảnh báo "cannot be given refs" một lần cho mỗi tên component trong một lần nạp module.
  it('mở bảng bên không phát cảnh báo React "Function components cannot be given refs"', () => {
    render(<Sample />);

    expect(screen.getByRole('dialog', { name: 'Tiêu đề mẫu' })).toBeInTheDocument();
    const refWarnings = consoleError.mock.calls.filter((args) => args.some((arg) => String(arg).includes('cannot be given refs')));
    expect(refWarnings).toEqual([]);
  });

  it('nút X có tên truy cập tiếng Việt "Đóng bảng bên", không còn nút "Close", và không trùng nút "Đóng" ở chân bảng', () => {
    render(<Sample />);

    expect(screen.getByRole('button', { name: 'Đóng bảng bên' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Close' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /close/i })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Đóng' })).toHaveTextContent('Đóng');
  });
});
