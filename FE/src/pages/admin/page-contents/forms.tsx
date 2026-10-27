// Bốn form nội dung trang (PageEditor chọn theo khóa), mỗi khóa một form theo đúng cấu trúc của PAGE_SCHEMAS (không có ô JSON thô). Giới hạn lấy từ PAGE_LIMITS.
import { PAGE_LIMITS as L } from '../../../shared/constants/page-content';
import { Group, ListEditor, TextField } from './fields';

const BREAK_HINT = 'Xuống dòng để ngắt dòng trên banner.';

/** Một số liệu: con số lớn + nhãn nhỏ. */
function StatFields({ path, multiline }: { path: string; multiline?: boolean }) {
  return (
    <div className="grid gap-4 sm:grid-cols-[minmax(0,9rem)_minmax(0,1fr)]">
      <TextField path={`${path}.value`} label="Con số" max={L.statValue} />
      <TextField path={`${path}.label`} label="Nhãn" max={L.statLabel} rows={multiline ? 2 : undefined} />
    </div>
  );
}

/** Dải 4 số liệu (số ô cố định: mỗi ô có biểu tượng riêng theo vị trí nên không thêm / xóa). */
function StatStrip({ hint }: { hint: string }) {
  return (
    <Group title="Dải số liệu" hint={hint}>
      <div className="grid gap-4 lg:grid-cols-2">
        {Array.from({ length: L.statCount }, (_, i) => (
          <Group key={i} inner title={`Số liệu ${i + 1}`}>
            <StatFields path={`stats.${i}`} />
          </Group>
        ))}
      </div>
    </Group>
  );
}

export function HomeBannerForm() {
  return (
    <>
      <Group title="Chữ trên banner" hint="Phần chữ đầu tiên khách thấy ở trang chủ.">
        <TextField path="headline" label="Tiêu đề" max={L.headline} rows={2} hint={BREAK_HINT} />
        <TextField path="subheadline" label="Mô tả ngắn" max={L.subheadline} rows={2} hint={BREAK_HINT} />
        <TextField path="ctaLabel" label="Chữ trên nút" max={L.ctaLabel} />
      </Group>
      <Group title="Số liệu nổi bật trên banner" hint="Thẻ số liệu nhỏ đặt cạnh banner.">
        <StatFields path="heroStat" multiline />
      </Group>
      <StatStrip hint="Bốn số liệu ngay dưới banner, hiện theo đúng thứ tự." />
    </>
  );
}

export function AboutForm() {
  return (
    <>
      <StatStrip hint="Bốn số liệu ở đầu trang giới thiệu, hiện theo đúng thứ tự." />
      <Group title="Giá trị cốt lõi" hint="Bốn thẻ giá trị của PetCare, hiện theo đúng thứ tự.">
        <div className="grid gap-4 lg:grid-cols-2">
          {Array.from({ length: L.valueCount }, (_, i) => (
            <Group key={i} inner title={`Giá trị ${i + 1}`}>
              <TextField path={`values.${i}.title`} label="Tên giá trị" max={L.valueTitle} />
              <TextField path={`values.${i}.description`} label="Mô tả" max={L.valueDescription} rows={3} />
            </Group>
          ))}
        </div>
      </Group>
    </>
  );
}

function PolicyDoc({ path, title, hint }: { path: 'terms' | 'privacy'; title: string; hint: string }) {
  return (
    <Group title={title} hint={hint}>
      <TextField path={`${path}.lead`} label="Đoạn dẫn" max={L.docLead} rows={3} />
      <div className="sm:max-w-xs">
        <TextField path={`${path}.updatedOn`} label="Cập nhật ngày" max={L.docUpdatedOn} hint="Ví dụ: 27/10/2026" />
      </div>
      <ListEditor path={`${path}.sections`} title="Mục" min={1} max={L.docSections} blank={() => ({ heading: '', body: '' })}>
        {(i) => (
          <>
            <TextField path={`${path}.sections.${i}.heading`} label="Tiêu đề" max={L.sectionHeading} />
            <TextField path={`${path}.sections.${i}.body`} label="Nội dung" max={L.sectionBody} rows={6} hint="Mỗi dòng là một đoạn." />
          </>
        )}
      </ListEditor>
    </Group>
  );
}

export function PolicyForm() {
  return (
    <>
      <PolicyDoc path="terms" title="Điều khoản sử dụng" hint="Hiện ở trang Điều khoản sử dụng." />
      <PolicyDoc path="privacy" title="Chính sách bảo mật" hint="Hiện ở trang Chính sách bảo mật." />
    </>
  );
}

export function FaqForm() {
  return (
    <Group title="Danh sách câu hỏi" hint="Hiện ở trang chủ, theo đúng thứ tự dưới đây.">
      <ListEditor path="items" title="Câu hỏi" min={1} max={L.faqItems} blank={() => ({ question: '', answer: '' })}>
        {(i) => (
          <>
            <TextField path={`items.${i}.question`} label="Câu hỏi" max={L.question} />
            <TextField path={`items.${i}.answer`} label="Câu trả lời" max={L.answer} rows={3} />
          </>
        )}
      </ListEditor>
    </Group>
  );
}
