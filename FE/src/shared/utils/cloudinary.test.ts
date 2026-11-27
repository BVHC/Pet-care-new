import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CLOUDINARY_MAX_BYTES, isUploadConfigured, uploadImage } from './cloudinary';

// Tải ảnh thẳng lên Cloudinary bằng unsigned preset (ADR-0158): không đi qua BE, không có secret ở FE.
const URL_OK = 'https://res.cloudinary.com/demo/image/upload/v1/petcare/anh.jpg';
const png = (name = 'anh.png') => new File(['x'], name, { type: 'image/png' });

const reply = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });

beforeEach(() => {
  vi.stubEnv('VITE_CLOUDINARY_CLOUD_NAME', 'demo');
  vi.stubEnv('VITE_CLOUDINARY_UPLOAD_PRESET', 'petcare_unsigned');
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

describe('isUploadConfigured', () => {
  it('đủ cloud name và preset thì true', () => {
    expect(isUploadConfigured()).toBe(true);
  });

  it.each([
    ['cloud name', 'VITE_CLOUDINARY_CLOUD_NAME'],
    ['preset', 'VITE_CLOUDINARY_UPLOAD_PRESET'],
  ])('thiếu %s thì false', (_name, key) => {
    vi.stubEnv(key, '');
    expect(isUploadConfigured()).toBe(false);
  });
});

describe('uploadImage', () => {
  it('gửi POST tới đúng cloud, kèm tệp + upload_preset, trả secure_url', async () => {
    const fetchMock = vi.fn().mockResolvedValue(reply(200, { secure_url: URL_OK }));
    vi.stubGlobal('fetch', fetchMock);
    const file = png();

    await expect(uploadImage(file)).resolves.toBe(URL_OK);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('https://api.cloudinary.com/v1_1/demo/image/upload');
    expect(init.method).toBe('POST');
    const form = init.body as FormData;
    expect(form.get('file')).toBe(file);
    expect(form.get('upload_preset')).toBe('petcare_unsigned');
    // Không đi qua apiClient: không được kèm JWT của người dùng sang bên thứ ba.
    expect(init.headers).toBeUndefined();
  });

  it('chưa cấu hình: báo lỗi nói rõ biến cần đặt, không gọi mạng', async () => {
    vi.stubEnv('VITE_CLOUDINARY_UPLOAD_PRESET', '');
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(uploadImage(png())).rejects.toThrow(/VITE_CLOUDINARY_CLOUD_NAME.*VITE_CLOUDINARY_UPLOAD_PRESET/);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('tệp không phải ảnh: từ chối, không gọi mạng', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(uploadImage(new File(['x'], 'ghi-chu.txt', { type: 'text/plain' }))).rejects.toThrow('Chỉ nhận tệp ảnh');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('ảnh vượt giới hạn dung lượng: từ chối, không gọi mạng', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const big = new File([new Uint8Array(CLOUDINARY_MAX_BYTES + 1)], 'to.png', { type: 'image/png' });

    await expect(uploadImage(big)).rejects.toThrow(/tối đa 5 MB/);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('ảnh đúng bằng giới hạn vẫn được tải', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply(200, { secure_url: URL_OK })));
    const edge = new File([new Uint8Array(CLOUDINARY_MAX_BYTES)], 'vua.png', { type: 'image/png' });

    await expect(uploadImage(edge)).resolves.toBe(URL_OK);
  });

  it('Cloudinary trả lỗi: nêu lý do của Cloudinary', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply(400, { error: { message: 'Upload preset not found' } })));

    await expect(uploadImage(png())).rejects.toThrow('Upload preset not found');
  });

  it('Cloudinary trả lỗi không có thân JSON: vẫn báo lỗi tiếng Việt', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>502</html>', { status: 502 })));

    await expect(uploadImage(png())).rejects.toThrow('Tải ảnh lên thất bại');
  });

  it('mất mạng: báo không kết nối được', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));

    await expect(uploadImage(png())).rejects.toThrow('Không kết nối được tới Cloudinary');
  });

  it('phản hồi 200 mà không có secure_url: không trả undefined để lưu vào DB', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply(200, {})));

    await expect(uploadImage(png())).rejects.toThrow('Tải ảnh lên thất bại');
  });
});
