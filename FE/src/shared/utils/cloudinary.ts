// Tải ảnh thẳng từ trình duyệt lên Cloudinary bằng unsigned preset (ADR-0158). Không đi qua BE và không dùng `apiClient`
// (interceptor sẽ gắn JWT và Content-Type JSON vào request tới bên thứ ba). FE chỉ giữ cloud name và tên preset, không có secret.
export const CLOUDINARY_MAX_BYTES = 5 * 1024 * 1024;

const config = () => ({
  cloud: import.meta.env.VITE_CLOUDINARY_CLOUD_NAME?.trim(),
  preset: import.meta.env.VITE_CLOUDINARY_UPLOAD_PRESET?.trim(),
});

export const isUploadConfigured = (): boolean => {
  const { cloud, preset } = config();
  return Boolean(cloud && preset);
};

/** Trả `secure_url` (https) của ảnh vừa tải lên; mọi lỗi ném `Error` có câu tiếng Việt để hiện thẳng cho người dùng. */
export async function uploadImage(file: File): Promise<string> {
  const { cloud, preset } = config();
  if (!cloud || !preset) {
    throw new Error('Chưa cấu hình Cloudinary: đặt VITE_CLOUDINARY_CLOUD_NAME và VITE_CLOUDINARY_UPLOAD_PRESET trong FE/.env');
  }
  if (!file.type.startsWith('image/')) throw new Error('Chỉ nhận tệp ảnh (JPG, PNG, WebP…)');
  if (file.size > CLOUDINARY_MAX_BYTES) throw new Error('Ảnh quá lớn, tối đa 5 MB');

  const body = new FormData();
  body.append('file', file);
  body.append('upload_preset', preset);

  let res: Response;
  try {
    res = await fetch(`https://api.cloudinary.com/v1_1/${cloud}/image/upload`, { method: 'POST', body });
  } catch {
    throw new Error('Không kết nối được tới Cloudinary');
  }
  const data = (await res.json().catch(() => null)) as { secure_url?: unknown; error?: { message?: string } } | null;
  if (!res.ok || typeof data?.secure_url !== 'string') {
    throw new Error(data?.error?.message ? `Tải ảnh lên thất bại: ${data.error.message}` : 'Tải ảnh lên thất bại');
  }
  return data.secure_url;
}
