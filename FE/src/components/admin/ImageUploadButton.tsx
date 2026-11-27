import { useRef, useState, type ChangeEvent } from 'react';
import { Loader2, Upload } from 'lucide-react';
import { isUploadConfigured, uploadImage } from '../../shared/utils/cloudinary';
import { Button } from '../ui/button';

/**
 * Nút chọn tệp ảnh rồi tải lên Cloudinary (ADR-0158); trả các URL qua `onUploaded`, theo thứ tự chọn. Nhận tối đa `limit` tệp,
 * tệp thừa bị bỏ và báo. Tệp lỗi thì dừng, giữ các ảnh đã tải xong trước đó. Form cha dùng `onBusyChange` để chặn lưu khi còn tải dở.
 * Chưa cấu hình thì nút tắt kèm chữ giải thích hiện sẵn (không dùng `title`: nút tắt có pointer-events-none nên tooltip không hiện);
 * vẫn dán URL tay được. Là fragment: thông báo `basis-full` xuống dòng trong hàng `flex-wrap` của cha.
 */
export function ImageUploadButton({
  label,
  multiple = false,
  limit = 1,
  onUploaded,
  onBusyChange,
}: {
  label: string;
  multiple?: boolean;
  limit?: number;
  onUploaded: (urls: string[]) => void;
  onBusyChange?: (busy: boolean) => void;
}) {
  const input = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const configured = isUploadConfigured();

  const pick = async (e: ChangeEvent<HTMLInputElement>) => {
    const chosen = Array.from(e.target.files ?? []);
    e.target.value = ''; // chọn lại đúng tệp đó lần sau vẫn bắn change
    if (!chosen.length) return;
    const files = chosen.slice(0, limit);
    setMessage(chosen.length > files.length ? `Chỉ còn chỗ cho ${limit} ảnh, ${chosen.length - files.length} ảnh sau bị bỏ qua.` : '');
    setBusy(true);
    onBusyChange?.(true);
    const urls: string[] = [];
    try {
      for (const file of files) urls.push(await uploadImage(file));
    } catch (err) {
      setMessage(err instanceof Error ? err.message : 'Tải ảnh lên thất bại');
    } finally {
      if (urls.length) onUploaded(urls);
      setBusy(false);
      onBusyChange?.(false);
    }
  };

  return (
    <>
      <input ref={input} type="file" accept="image/*" multiple={multiple} className="hidden" aria-label={`${label} (chọn tệp)`} onChange={pick} />
      <Button
        type="button"
        size="sm"
        variant="outline"
        disabled={!configured || busy || limit <= 0}
        onClick={() => input.current?.click()}
      >
        {busy ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <Upload className="h-4 w-4" aria-hidden />}
        {busy ? 'Đang tải…' : label}
      </Button>
      {!configured && <p className="basis-full text-xs text-(--text-tertiary)">Chưa cấu hình Cloudinary (xem FE/.env.example).</p>}
      {message && (
        <p role="alert" className="basis-full text-xs text-red-600">
          {message}
        </p>
      )}
    </>
  );
}
