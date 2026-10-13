import { Link, Outlet } from 'react-router-dom';
import { Link2 } from 'lucide-react';
import { useSession } from '../../stores/session.store';

/**
 * BR-TK-19: khi hồ sơ online còn cờ chờ quyết định liên kết, ẩn quản lý thú cưng, đặt lịch / lưu trú,
 * hồ sơ sức khỏe và feedback; thay bằng lời đề nghị liên kết.
 */
export function RequireLinkDecision() {
  const pending = useSession((s) => s.linkDecisionPending);
  if (!pending) return <Outlet />;
  return (
    <section className="mx-auto flex max-w-md flex-col items-center gap-4 px-6 py-24 text-center">
      <span className="flex h-14 w-14 items-center justify-center rounded-full bg-accent/10 text-accent">
        <Link2 className="h-7 w-7" />
      </span>
      <h1 className="text-xl font-bold text-(--text-primary)">Hoàn tất liên kết hồ sơ</h1>
      <p className="text-sm text-(--text-secondary)">
        Số điện thoại bạn khai trùng với hồ sơ tại phòng khám. Hãy liên kết với hồ sơ đó, hoặc chọn "Không phải tôi", trước khi quản lý
        thú cưng, đặt lịch hay gửi góp ý.
      </p>
      <Link to="/account/link" className="rounded-full bg-accent px-5 py-2.5 text-sm font-bold text-white hover:bg-accent-hover">
        Đến bước liên kết hồ sơ
      </Link>
    </section>
  );
}
