import { render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Dialog, DialogClose, DialogContent, DialogFooter, DialogTitle } from './dialog';

// Hộp thoại dùng chung (shadcn/Radix). Hai lỗi đã gặp: nút X mang tên truy cập tiếng Anh "Close" (đứng cạnh nút "Đóng" ở chân hộp), và React báo
// "Function components cannot be given refs" ở DialogOverlay vì Radix Portal (asChild) gán ref cho con trực tiếp mà DialogOverlay là function component.

let consoleError: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
});
afterEach(() => {
  consoleError.mockRestore();
});

function Sample({ showCloseButton }: { showCloseButton?: boolean }) {
  return (
    <Dialog open>
      <DialogContent showCloseButton={showCloseButton}>
        <DialogTitle>Tiêu đề mẫu</DialogTitle>
        <p>Nội dung thường</p>
        <DialogFooter>
          <DialogClose>Đóng</DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

describe('Dialog dùng chung', () => {
  // Phải chạy ĐẦU TIÊN trong file: React chỉ cảnh báo "cannot be given refs" một lần cho mỗi tên component trong một lần nạp module.
  it('mở hộp thoại không phát cảnh báo React "Function components cannot be given refs"', () => {
    render(<Sample />);

    expect(screen.getByRole('dialog', { name: 'Tiêu đề mẫu' })).toBeInTheDocument();
    const refWarnings = consoleError.mock.calls.filter((args) => args.some((arg) => String(arg).includes('cannot be given refs')));
    expect(refWarnings).toEqual([]);
  });

  it('nút X có tên truy cập tiếng Việt "Đóng hộp thoại", không còn nút "Close", và không trùng nút "Đóng" ở chân hộp', () => {
    render(<Sample />);

    expect(screen.getByRole('button', { name: 'Đóng hộp thoại' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Close' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /close/i })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Đóng' })).toHaveTextContent('Đóng');
  });

  it('showCloseButton={false} bỏ nút X nhưng giữ nút "Đóng" ở chân hộp', () => {
    render(<Sample showCloseButton={false} />);

    expect(screen.queryByRole('button', { name: 'Đóng hộp thoại' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Đóng' })).toBeInTheDocument();
  });
});
