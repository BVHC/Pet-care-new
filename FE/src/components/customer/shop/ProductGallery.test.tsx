import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { ProductGallery } from './ProductGallery';

// Ảnh sản phẩm ở trang chi tiết: ảnh lớn + dải ảnh nhỏ. Danh sách vào là [imageUrl, ...galleryUrls]; component tự bỏ rỗng / trùng.
// jsdom không tải ảnh, nên ảnh lỗi giả lập bằng sự kiện `error`.

const NAME = 'Hạt Royal Canin Mini Adult 2kg';
const A = '/imgs/prod1.jpg';
const B = '/imgs/prod2.jpg';
const C = '/imgs/prod3.jpg';

const main = () => screen.getByRole('img', { name: NAME });
const thumbs = () => screen.queryAllByRole('button', { name: /^Xem ảnh \d+ trên \d+$/ });
const thumbImg = (button: HTMLElement) => button.querySelector('img')!;

function setup(urls: (string | null | undefined)[]) {
  render(<ProductGallery name={NAME} urls={urls} />);
  return userEvent.setup();
}

describe('ProductGallery', () => {
  it('một ảnh: chỉ ảnh lớn, không có dải ảnh nhỏ, không có nút Trước / Sau', () => {
    setup([A]);
    expect(main()).toHaveAttribute('src', A);
    expect(thumbs()).toHaveLength(0);
    expect(screen.queryByRole('button', { name: 'Ảnh trước' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Ảnh sau' })).not.toBeInTheDocument();
  });

  it('nhiều ảnh: một nút cho mỗi ảnh, nhãn "Xem ảnh i trên n", ảnh đầu đang chọn (aria-current) và là ảnh lớn', () => {
    setup([A, B, C]);
    const buttons = thumbs();
    expect(buttons.map((b) => b.getAttribute('aria-label'))).toEqual(['Xem ảnh 1 trên 3', 'Xem ảnh 2 trên 3', 'Xem ảnh 3 trên 3']);
    expect(buttons.map((b) => b.getAttribute('aria-current'))).toEqual(['true', null, null]);
    expect(buttons.map((b) => thumbImg(b).getAttribute('src'))).toEqual([A, B, C]);
    expect(main()).toHaveAttribute('src', A);
  });

  it('ảnh nhỏ có alt rỗng nên tên ảnh sản phẩm chỉ gắn với đúng một ảnh lớn', () => {
    setup([A, B]);
    expect(screen.getAllByRole('img', { name: NAME })).toHaveLength(1);
  });

  it('bỏ giá trị rỗng, null, chỉ khoảng trắng và URL trùng (kể cả trùng sau khi cắt khoảng trắng)', () => {
    setup([A, '', '   ', null, undefined, B, A, ` ${B} `]);
    expect(thumbs().map((b) => b.getAttribute('aria-label'))).toEqual(['Xem ảnh 1 trên 2', 'Xem ảnh 2 trên 2']);
  });

  it('bấm ảnh nhỏ thì ảnh lớn đổi và aria-current chuyển theo', async () => {
    const user = setup([A, B, C]);
    await user.click(thumbs()[2]);
    expect(main()).toHaveAttribute('src', C);
    expect(thumbs().map((b) => b.getAttribute('aria-current'))).toEqual([null, null, 'true']);
  });

  it('phím mũi tên phải / trái trên dải ảnh nhỏ đổi ảnh, đưa tiêu điểm theo, vòng lại ở hai đầu', async () => {
    const user = setup([A, B, C]);
    thumbs()[0].focus();
    await user.keyboard('{ArrowRight}');
    expect(main()).toHaveAttribute('src', B);
    expect(thumbs()[1]).toHaveFocus();
    await user.keyboard('{ArrowRight}{ArrowRight}');
    expect(main()).toHaveAttribute('src', A); // C rồi vòng về A
    expect(thumbs()[0]).toHaveFocus();
    await user.keyboard('{ArrowLeft}');
    expect(main()).toHaveAttribute('src', C); // vòng ngược về ảnh cuối
    expect(thumbs()[2]).toHaveFocus();
  });

  it('nút "Ảnh sau" / "Ảnh trước" trên ảnh lớn đổi ảnh, vòng lại ở hai đầu', async () => {
    const user = setup([A, B, C]);
    await user.click(screen.getByRole('button', { name: 'Ảnh sau' }));
    expect(main()).toHaveAttribute('src', B);
    await user.click(screen.getByRole('button', { name: 'Ảnh trước' }));
    await user.click(screen.getByRole('button', { name: 'Ảnh trước' }));
    expect(main()).toHaveAttribute('src', C);
    await user.click(screen.getByRole('button', { name: 'Ảnh sau' }));
    expect(main()).toHaveAttribute('src', A);
    expect(thumbs()[0]).toHaveAttribute('aria-current', 'true');
  });

  it('ảnh nhỏ tải lỗi: ẩn khỏi dải, đánh số lại, và nút Sau bỏ qua nó', async () => {
    const user = setup([A, B, C]);
    fireEvent.error(thumbImg(thumbs()[1]));
    expect(thumbs().map((b) => b.getAttribute('aria-label'))).toEqual(['Xem ảnh 1 trên 2', 'Xem ảnh 2 trên 2']);
    expect(thumbs().map((b) => thumbImg(b).getAttribute('src'))).toEqual([A, C]);
    await user.click(screen.getByRole('button', { name: 'Ảnh sau' }));
    expect(main()).toHaveAttribute('src', C);
  });

  it('ảnh lớn đang xem tải lỗi: chuyển sang ảnh tốt kế tiếp, ảnh lỗi biến khỏi dải', () => {
    setup([A, B, C]);
    fireEvent.error(main());
    expect(main()).toHaveAttribute('src', B);
    expect(thumbs().map((b) => b.getAttribute('aria-label'))).toEqual(['Xem ảnh 1 trên 2', 'Xem ảnh 2 trên 2']);
    expect(thumbs()[0]).toHaveAttribute('aria-current', 'true');
  });

  it('ảnh cuối đang xem tải lỗi: lùi về ảnh tốt liền trước', async () => {
    const user = setup([A, B, C]);
    await user.click(thumbs()[2]);
    fireEvent.error(main());
    expect(main()).toHaveAttribute('src', B);
  });

  it('hai ảnh, một ảnh lỗi: còn một ảnh tốt nên dải và nút Trước / Sau cùng biến mất', () => {
    setup([A, B]);
    expect(screen.getByRole('button', { name: 'Ảnh sau' })).toBeInTheDocument();
    fireEvent.error(thumbImg(thumbs()[1]));
    expect(thumbs()).toHaveLength(0);
    expect(screen.queryByRole('button', { name: 'Ảnh trước' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Ảnh sau' })).not.toBeInTheDocument();
    expect(main()).toHaveAttribute('src', A);
  });

  it('đổi sang danh sách ảnh của sản phẩm khác: ảnh đang chọn không dính sang, hiện ảnh đầu của danh sách mới', async () => {
    const user = userEvent.setup();
    const { rerender } = render(<ProductGallery name={NAME} urls={[A, B, C]} />);
    await user.click(thumbs()[2]);
    expect(main()).toHaveAttribute('src', C);
    rerender(<ProductGallery name={NAME} urls={['/imgs/prod4.jpg', '/imgs/prod5.jpg']} />);
    expect(main()).toHaveAttribute('src', '/imgs/prod4.jpg');
    expect(thumbs()[0]).toHaveAttribute('aria-current', 'true');
  });

  it('hết ảnh tốt (hoặc không có ảnh): khung dự phòng, không có thẻ ảnh vỡ', () => {
    setup([A, B]);
    fireEvent.error(main());
    fireEvent.error(main());
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
    expect(thumbs()).toHaveLength(0);
  });

  it('không có URL nào: khung dự phòng ngay', () => {
    setup([null, '', '  ']);
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Ảnh sản phẩm' })).not.toBeInTheDocument();
  });

  it('dải ảnh nhỏ nằm trong nhóm "Ảnh sản phẩm"', () => {
    setup([A, B]);
    expect(within(screen.getByRole('group', { name: 'Ảnh sản phẩm' })).getAllByRole('button')).toHaveLength(2);
  });
});
