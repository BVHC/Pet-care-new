import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ConfirmModal, FormModal, Modal, ModalBody, ModalTitle } from './modal-templates';

// L-04 / L-29: Modal tự viết bằng div (không phải Radix) nên phải tự lo Esc và focus: trước đây Esc không đóng hộp và focus ở lại nút nền (nằm sau lớp phủ).
// Hộp luôn nằm trong DOM kể cả khi đóng (aria-hidden), nên truy vấn hộp đóng phải thêm `hidden: true`.

const dialog = (hidden = false) => screen.getByRole('dialog', { hidden });

const page = (open: boolean, onOpenChange: (open: boolean) => void = () => {}, field?: React.ReactNode) => (
  <>
    <button>Mở</button>
    <FormModal open={open} onOpenChange={onOpenChange} title="Ghi kết quả">
      {field ?? <p>Nội dung</p>}
    </FormModal>
  </>
);

describe('Modal — focus', () => {
  it('mở hộp thì focus chuyển vào hộp (không ở lại nút nền)', () => {
    render(page(true));
    expect(dialog()).toHaveFocus();
  });

  it('hộp đang đóng thì không giành focus', () => {
    render(page(false));
    expect(dialog(true)).not.toHaveFocus();
  });

  it('đóng hộp thì trả focus về nút đã bấm mở', () => {
    const view = render(page(false));
    const opener = screen.getByRole('button', { name: 'Mở' });
    opener.focus();
    view.rerender(page(true));
    expect(dialog()).toHaveFocus();
    view.rerender(page(false));
    expect(opener).toHaveFocus();
  });

  it('ô nhập trong hộp đã tự lấy focus (autoFocus) thì giữ nguyên, không bị giật về khung hộp', () => {
    render(
      <Modal open onOpenChange={() => {}}>
        <ModalTitle>Nhập mã</ModalTitle>
        <ModalBody>
          <input aria-label="Mã" autoFocus />
        </ModalBody>
      </Modal>,
    );
    expect(screen.getByLabelText('Mã')).toHaveFocus();
  });
});

describe('Modal — phím Esc', () => {
  it('Esc đóng hộp (gọi onOpenChange(false) đúng một lần)', async () => {
    const onOpenChange = vi.fn();
    render(page(true, onOpenChange));
    await userEvent.setup().keyboard('{Escape}');
    expect(onOpenChange).toHaveBeenCalledTimes(1);
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('Esc gõ trong ô nhập của hộp cũng đóng hộp', async () => {
    const onOpenChange = vi.fn();
    const user = userEvent.setup();
    render(page(true, onOpenChange, <input aria-label="Ghi chú" />));
    await user.click(screen.getByLabelText('Ghi chú'));
    await user.keyboard('{Escape}');
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('phần tử bên trong đã xử lý Esc (preventDefault, ví dụ bảng chọn ngày) thì hộp không đóng theo', async () => {
    const onOpenChange = vi.fn();
    const user = userEvent.setup();
    render(page(true, onOpenChange, <input aria-label="Ngày" onKeyDown={(e) => e.key === 'Escape' && e.preventDefault()} />));
    await user.click(screen.getByLabelText('Ngày'));
    await user.keyboard('{Escape}');
    expect(onOpenChange).not.toHaveBeenCalled();
  });

  // Modal vẽ tại chỗ (không portal): hộp lồng trong hộp thì Esc nổi bọt từ hộp trong ra hộp ngoài; một lần Esc chỉ được đóng hộp trong.
  it('hộp lồng trong hộp: một lần Esc chỉ đóng hộp trong', async () => {
    const outer = vi.fn();
    const inner = vi.fn();
    render(
      <Modal open onOpenChange={outer}>
        <ModalTitle>Hộp ngoài</ModalTitle>
        <Modal open onOpenChange={inner}>
          <ModalTitle>Hộp trong</ModalTitle>
        </Modal>
      </Modal>,
    );
    await userEvent.setup().keyboard('{Escape}');
    expect(inner).toHaveBeenCalledTimes(1);
    expect(outer).not.toHaveBeenCalled();
  });

  it('hộp đang đóng thì Esc không gọi gì', async () => {
    const onOpenChange = vi.fn();
    render(<ConfirmModal open={false} onOpenChange={onOpenChange} title="Xóa?" onConfirm={() => {}} />);
    dialog(true).focus();
    await userEvent.setup().keyboard('{Escape}');
    expect(onOpenChange).not.toHaveBeenCalled();
  });
});
