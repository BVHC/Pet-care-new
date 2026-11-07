// Dữ liệu mẫu của mock AI (ai.mock.ts): tài liệu tri thức MANUAL và hồ sơ gợi ý. Nội dung là văn bản TỰ SOẠN để chạy demo cơ chế, KHÔNG phải tri thức thú y đã duyệt
// (07 §6.3: "tài liệu chưa ai soạn"): không chép nguồn thật, không có liều thuốc. Dòng đầu mỗi tài liệu nói rõ điều đó; người rà soát ghi "(dữ liệu mẫu)".
import { vnDate } from '../../utils/vn-time';
import type { DocumentSpecies, DocumentStatus, DocumentTopic, IndexStatus, ItemProfile } from '../../types/ai';

/** Một dòng `ai_documents` (05/07 §8) cộng bản đang phục vụ chatbot. */
export interface DocRow {
  documentId: number;
  sourceType: 'MANUAL' | 'CATALOG_PROTOCOL';
  title: string;
  topic: DocumentTopic;
  species: DocumentSpecies;
  content: string;
  sourceNote: string | null;
  reviewerName: string | null;
  reviewedOn: string | null;
  status: DocumentStatus;
  version: number;
  indexedVersion: number;
  indexStatus: IndexStatus;
  indexError: string | null;
  chunkCount: number;
  publishedAt: string | null;
  updatedAt: string;
  /** Đoạn đang phục vụ (BR-AI-16: `doc_version = indexed_version`): tiêu đề và nội dung ở phiên bản ĐÃ NẠP, có thể cũ hơn `content`. null = chưa có đoạn nào. */
  served: { title: string; content: string } | null;
}

const NOTICE = '> Nội dung mẫu minh họa cho demo, chưa được bác sĩ thú y duyệt.';
export const SAMPLE_SOURCE = 'Mẫu minh họa tự soạn cho demo, không trích nguồn thật; thay bằng nguồn WSAVA / AAHA / MSD sau khi bác sĩ rà soát.';
export const SAMPLE_REVIEWER = 'BS. Minh Anh (dữ liệu mẫu)';

type Seed = Pick<DocRow, 'documentId' | 'title' | 'topic' | 'species' | 'status'> & {
  body: string;
  /** Số ngày trước hôm nay của lần sửa cuối. */
  ago: number;
  /** Chỉ PUBLISHED: bản cũ đang phục vụ khi tài liệu đã sửa mà chưa nạp lại (indexStatus FAILED). */
  failed?: { servedBody: string; error: string };
  /** DRAFT / ARCHIVED: có đủ lớp 1 và 2 hay không. */
  noReview?: boolean;
  noSource?: boolean;
};

const SEEDS: Seed[] = [
  {
    documentId: 1, title: 'Chăm sóc chó con trong những tuần đầu', topic: 'CARE', species: 'DOG', status: 'PUBLISHED', ago: 20,
    body: `## Môi trường sống
Chó con cần chỗ nằm ấm, khô ráo và yên tĩnh. Hạn chế cho bé tiếp xúc với chó lạ cho tới khi bác sĩ cho phép.

## Bữa ăn
Chia nhỏ khẩu phần thành nhiều bữa trong ngày và dùng thức ăn dành riêng cho chó con. Luôn để sẵn nước sạch.

## Làm quen và vận động
Cho bé đi dạo ngắn, làm quen dần với tiếng động và người lạ. Tránh vận động mạnh khi cơ thể chưa phát triển đủ.`,
  },
  {
    documentId: 2, title: 'Chế độ ăn cho mèo trưởng thành', topic: 'NUTRITION', species: 'CAT', status: 'PUBLISHED', ago: 18,
    body: `## Nguyên tắc chung
Mèo trưởng thành nên ăn theo giờ cố định, chia thành vài bữa nhỏ mỗi ngày. Chọn thức ăn đủ đạm, phù hợp độ tuổi và thể trạng.

## Nước uống
Mèo hay uống ít nước. Đặt nhiều bát nước sạch ở các góc nhà, thay nước hằng ngày; thức ăn ướt cũng giúp bé nạp thêm nước.

## Theo dõi cân nặng
Cân bé định kỳ. Nếu thấy bé tăng hoặc sụt cân rõ rệt, hãy đặt lịch để bác sĩ tư vấn.`,
  },
  {
    documentId: 3, title: 'Nước uống và thức ăn hằng ngày của chó', topic: 'NUTRITION', species: 'DOG', status: 'PUBLISHED', ago: 17,
    body: `## Nước uống
Luôn để sẵn nước sạch cho chó, nhiều hơn khi trời nóng hoặc sau khi vận động. Rửa bát nước mỗi ngày.

## Thức ăn
Chia bữa theo độ tuổi: chó con ăn nhiều bữa nhỏ, chó trưởng thành ăn hai bữa. Đổi thức ăn từ từ trong nhiều ngày để tránh rối loạn tiêu hóa.

## Đồ ăn của người
Không cho chó ăn thức ăn có nhiều muối, đồ chiên rán hoặc xương nhỏ dễ gãy.`,
  },
  {
    documentId: 4, title: 'Thực phẩm độc hại với chó mèo', topic: 'CARE', species: 'ALL', status: 'PUBLISHED', ago: 16,
    body: `## Những thứ không dành cho thú cưng
Chocolate, hành, tỏi, nho khô và kẹo có chất tạo ngọt xylitol không dành cho chó mèo. Hãy cất kỹ ngoài tầm với của bé.

## Khi bé lỡ ăn
Nếu nghi bé đã ăn phải, hãy giữ bình tĩnh, mang theo bao bì nếu còn và đưa bé tới phòng khám sớm nhất.`,
  },
  {
    documentId: 5, title: 'Tắm và chải lông đúng cách', topic: 'HYGIENE', species: 'ALL', status: 'PUBLISHED', ago: 14,
    body: `## Tắm
Dùng sữa tắm dành riêng cho thú cưng, nước ấm vừa phải và sấy khô kỹ sau khi tắm. Không tắm quá dày để tránh khô da.

## Chải lông
Chải lông đều đặn giúp giảm rụng và rối lông, đồng thời là dịp quan sát da của bé. Giống lông dài cần chải thường xuyên hơn.

## Cắt móng
Cắt móng định kỳ, tránh cắt sát phần hồng bên trong móng.`,
  },
  {
    documentId: 6, title: 'Vệ sinh tai và răng miệng', topic: 'HYGIENE', species: 'ALL', status: 'PUBLISHED', ago: 12,
    body: `## Vệ sinh tai
Lau nhẹ phần tai ngoài bằng gạc ẩm hoặc dung dịch vệ sinh tai dành cho thú cưng. Không dùng vật cứng đưa sâu vào ống tai.

## Răng miệng
Đánh răng cho bé bằng kem đánh răng dành cho thú cưng. Nếu hơi thở có mùi nặng hoặc nướu đỏ, hãy đặt lịch khám.`,
  },
  {
    documentId: 7, title: 'Chăm sóc thú cưng sau khi tiêm phòng', topic: 'VACCINATION', species: 'ALL', status: 'PUBLISHED', ago: 10,
    body: `## Sau mũi tiêm
Cho bé nghỉ ngơi, giữ ấm và yên tĩnh trong ngày đầu. Chưa tắm hoặc đưa đi chơi nơi đông thú lạ ngay sau tiêm.

## Theo dõi
Bé có thể kém ăn hoặc lười vận động nhẹ. Nếu thấy dấu hiệu bất thường kéo dài, hãy liên hệ phòng khám.`,
  },
  {
    documentId: 8, title: 'Chuẩn bị đưa thú cưng đi lưu trú', topic: 'CARE', species: 'ALL', status: 'PUBLISHED', ago: 3,
    body: `## Trước khi gửi
Mang theo sổ tiêm, thức ăn quen thuộc và món đồ có mùi quen của bé. Báo trước tình trạng sức khỏe và thói quen ăn ngủ.

## Trong thời gian lưu trú
Bé được ghi nhật ký chăm sóc mỗi ngày. Hãy giữ liên lạc với chi nhánh để cập nhật tình hình.

## Khi đón về
Cho bé nghỉ ngơi và theo dõi ăn uống trong vài ngày đầu để bé làm quen lại với nhà.`,
    failed: {
      servedBody: `## Trước khi gửi
Mang theo sổ tiêm và thức ăn quen thuộc của bé.

## Khi đón về
Cho bé nghỉ ngơi và theo dõi ăn uống trong vài ngày đầu.`,
      error: 'Nhà cung cấp embedding trả 429 (hết hạn mức); bản cũ vẫn đang phục vụ',
    },
  },
  {
    documentId: 9, title: 'Chó sủa nhiều và lo âu khi xa chủ', topic: 'BEHAVIOR', species: 'DOG', status: 'DRAFT', ago: 2, noReview: true, noSource: true,
    body: `## Dấu hiệu
Chó sủa liên tục, cào cửa hoặc phá đồ khi ở một mình có thể là lo âu chia ly.

## Hướng xử lý (bản nháp)
Tập cho bé quen dần với việc ở một mình, không làm lớn chuyện khi ra về hoặc về nhà.`,
  },
  {
    documentId: 10, title: 'Thông tin phòng khám: đặt lịch và cấp cứu ngoài giờ', topic: 'CLINIC_INFO', species: 'ALL', status: 'PUBLISHED', ago: 6,
    body: `## Đặt lịch
Pet Care nhận đặt lịch khám, tiêm phòng, thẩm mỹ và lưu trú trực tuyến; khách cũng có thể đặt trực tiếp tại quầy.

## Cấp cứu ngoài giờ
Một số chi nhánh nhận cấp cứu ngoài giờ. Hãy gọi trước khi đưa bé tới để phòng khám chuẩn bị.`,
  },
  {
    documentId: 11, title: 'Chăm sóc mèo lớn tuổi', topic: 'CARE', species: 'CAT', status: 'ARCHIVED', ago: 25,
    body: `## Sinh hoạt
Mèo lớn tuổi ngủ nhiều hơn và vận động chậm hơn. Giữ chỗ nằm êm, dễ lên xuống.

## Theo dõi
Khám sức khỏe định kỳ để phát hiện sớm thay đổi về ăn uống, cân nặng và hành vi.`,
  },
  {
    documentId: 12, title: 'Dinh dưỡng cho chó lớn tuổi', topic: 'NUTRITION', species: 'DOG', status: 'DRAFT', ago: 1, noReview: true,
    body: `## Nguyên tắc
Chó lớn tuổi cần khẩu phần dễ tiêu, kiểm soát cân nặng và theo dõi răng miệng. (Bản nháp, cần bổ sung.)`,
  },
];

/** Số đoạn ước tính (BR-AI-15: cắt theo tiêu đề Markdown, mỗi đoạn tối đa ~1 200 ký tự); nội dung rỗng = 0. */
export function chunkCountOf(content: string): number {
  return content
    .split(/\n(?=#{1,6}\s)/)
    .map((section) => section.trim())
    .filter(Boolean)
    .reduce((sum, section) => sum + Math.max(1, Math.ceil(section.length / 1100)), 0);
}

const DAY = 86_400_000;

/** Tài liệu MANUAL mẫu; thời điểm tính ngược từ `now`. Bản FAILED: v2 chờ nạp, bản v1 cũ vẫn phục vụ (BR-AI-16). */
export function seedManualDocuments(now: number): DocRow[] {
  return SEEDS.map((seed): DocRow => {
    const content = `${NOTICE}\n\n${seed.body}`;
    const updatedAt = new Date(now - seed.ago * DAY).toISOString();
    const published = seed.status === 'PUBLISHED';
    const archived = seed.status === 'ARCHIVED';
    const reviewed = !seed.noReview;
    return {
      documentId: seed.documentId,
      sourceType: 'MANUAL',
      title: seed.title,
      topic: seed.topic,
      species: seed.species,
      content,
      sourceNote: seed.noSource ? null : SAMPLE_SOURCE,
      reviewerName: reviewed ? SAMPLE_REVIEWER : null,
      reviewedOn: reviewed ? vnDate(now - (seed.ago + 1) * DAY) : null,
      status: seed.status,
      version: seed.failed ? 2 : 1,
      indexedVersion: published || archived ? 1 : 0,
      indexStatus: seed.failed ? 'FAILED' : published || archived ? 'INDEXED' : 'PENDING',
      indexError: seed.failed?.error ?? null,
      chunkCount: published || archived ? chunkCountOf(seed.failed ? `${NOTICE}\n\n${seed.failed.servedBody}` : content) : 0,
      publishedAt: published || archived ? updatedAt : null,
      updatedAt,
      served: published || archived ? { title: seed.title, content: seed.failed ? `${NOTICE}\n\n${seed.failed.servedBody}` : content } : null,
    };
  });
}

/** Hồ sơ gợi ý mẫu, khóa `SERVICE:<id>` / `PRODUCT:<id>` (khớp danh mục mẫu của catalog.mock). Dịch vụ 3 (Tắm & sấy) cố ý chưa có hồ sơ để thử `missingOnly`. */
export const SEED_PROFILES: Record<string, ItemProfile> = {
  'SERVICE:4': { species: 'DOG', minAgeWeeks: 12, minWeightKg: 5, breedKeywords: ['poodle', 'phoc soc', 'shih tzu'], repeatIntervalDays: 45 },
  'PRODUCT:1': { species: 'DOG', minAgeWeeks: 52, maxWeightKg: 10, breedKeywords: ['poodle', 'chihuahua'], repeatIntervalDays: 45 },
  'PRODUCT:2': { species: 'CAT', minAgeWeeks: 8, breedKeywords: [], repeatIntervalDays: 14 },
};

/** Số lượt dùng 90 ngày gần nhất TOÀN CHUỖI theo nhóm (c của công thức P, 07 §7.3): mock đơn giản hóa, không tách theo (loài, nhóm tuổi). Khóa `<RecoGroup>:<id>`. */
export const SEED_POPULARITY: Record<string, number> = {
  'GROOMING:3': 18,
  'GROOMING:4': 9,
  'BOARDING:5': 12,
  'BOARDING:6': 7,
  'PRODUCT:1': 24,
  'PRODUCT:2': 31,
};
