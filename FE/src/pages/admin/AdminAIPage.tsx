import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import {
  Search,
  Bot,
  FileText,
  UploadCloud,
  CheckCircle2,
  Clock,
  AlertTriangle,
  RotateCcw,
  Sliders,
  MessageSquare,
  Sparkles,
  Trash2,
  Eye,
  ThumbsUp,
  ThumbsDown,
} from 'lucide-react';
import { ConfirmModal, DetailModal, FormModal, InfoRow } from '../../components/ui/modal-templates';
import { toast } from 'sonner';

type DocCategory = 'FIRST_AID' | 'NUTRITION' | 'VACCINE' | 'CLINICAL' | 'GENERAL';
type DocStatus = 'INDEXED' | 'PROCESSING' | 'ERROR';

interface RAGDocument {
  id: string;
  title: string;
  fileName: string;
  category: DocCategory;
  fileSize: string;
  chunksCount: number;
  uploadedBy: string;
  uploadedAt: string;
  status: DocStatus;
  summary: string;
}

interface ChatLog {
  id: string;
  customerName: string;
  question: string;
  answerSummary: string;
  category: string;
  time: string;
  rating: 'GOOD' | 'POOR' | 'UNRATED';
  bookedExam: boolean;
}

const CATEGORY_MAP: Record<DocCategory, { label: string; className: string }> = {
  FIRST_AID: { label: 'Sơ cứu khẩn cấp', className: 'bg-red-100 text-red-700' },
  NUTRITION: { label: 'Dinh dưỡng & Thức ăn', className: 'bg-amber-100 text-amber-700' },
  VACCINE: { label: 'Phác đồ tiêm phòng', className: 'bg-blue-100 text-blue-700' },
  CLINICAL: { label: 'Bệnh lý lâm sàng', className: 'bg-purple-100 text-purple-700' },
  GENERAL: { label: 'Cẩm nang nuôi dưỡng', className: 'bg-emerald-100 text-emerald-700' },
};

const MOCK_DOCS: RAGDocument[] = [
  {
    id: 'doc-1',
    title: 'Phác đồ tiêm chủng chó mèo chuẩn WSAVA 2026',
    fileName: 'WSAVA_Vaccination_Guidelines_2026.pdf',
    category: 'VACCINE',
    fileSize: '2.4 MB',
    chunksCount: 148,
    uploadedBy: 'Dr. Minh (Bác sĩ trưởng)',
    uploadedAt: '2026-09-15 10:20',
    status: 'INDEXED',
    summary: 'Chi tiết lịch tiêm vắc-xin cốt lõi và không cốt lõi cho chó và mèo theo độ tuổi từ sơ sinh đến trưởng thành.',
  },
  {
    id: 'doc-2',
    title: 'Quy trình sơ cứu ngộ độc và hóc dị vật tại nhà',
    fileName: 'Emergency_First_Aid_Companion_Animals.pdf',
    category: 'FIRST_AID',
    fileSize: '3.1 MB',
    chunksCount: 215,
    uploadedBy: 'Dr. Lan (Khoa cấp cứu)',
    uploadedAt: '2026-09-18 14:45',
    status: 'INDEXED',
    summary: 'Kỹ thuật Heimlich cho thú cưng nhỏ, danh mục độc chất thực phẩm (socola, nho, hành tỏi, xylitol) và cách xử lý tức thời.',
  },
  {
    id: 'doc-3',
    title: 'Cẩm nang dinh dưỡng thú cưng sau triệt sản',
    fileName: 'Post_Neutering_Nutrition_Guide.docx',
    category: 'NUTRITION',
    fileSize: '1.2 MB',
    chunksCount: 86,
    uploadedBy: 'Dr. Minh',
    uploadedAt: '2026-09-19 09:10',
    status: 'INDEXED',
    summary: 'Điều chỉnh lượng calo, kiểm soát béo phì và chế độ ăn ngừa sỏi bàng quang cho chó mèo sau phẫu thuật.',
  },
  {
    id: 'doc-4',
    title: 'Chẩn đoán và phân biệt viêm ruột Parvovirus',
    fileName: 'Parvo_FPV_Clinical_Differential.pdf',
    category: 'CLINICAL',
    fileSize: '4.8 MB',
    chunksCount: 320,
    uploadedBy: 'Dr. Lan',
    uploadedAt: '2026-09-21 08:30',
    status: 'PROCESSING',
    summary: 'Dấu hiệu nhận biết sớm Parvo trên chó con và giảm bạch cầu FPV trên mèo, phác đồ bù dịch điện giải.',
  },
];

const MOCK_CHAT_LOGS: ChatLog[] = [
  {
    id: 'chat-1',
    customerName: 'Nguyễn Thu Hà',
    question: 'Mèo bỏ ăn và nôn dịch màu vàng 2 ngày nay thì bị làm sao?',
    answerSummary: 'Phân tích trào ngược dịch mật, tắc búi lông, cảnh báo thoái hóa mỡ gan và hướng dẫn bù điện giải Oresol.',
    category: 'Tiêu hóa',
    time: '15 phút trước',
    rating: 'GOOD',
    bookedExam: true,
  },
  {
    id: 'chat-2',
    customerName: 'Trần Đức Minh',
    question: 'Chó ăn phải thanh socola đen khoảng 50g có sao không?',
    answerSummary: 'Cảnh báo nguy cơ ngộ độc theobromine mức độ cao, hướng dẫn giữ ấm và khẩn cấp đưa đến chi nhánh gần nhất.',
    category: 'Sơ cứu ngộ độc',
    time: '42 phút trước',
    rating: 'GOOD',
    bookedExam: true,
  },
  {
    id: 'chat-3',
    customerName: 'Lê Hoàng Nam',
    question: 'Lịch tiêm phòng mũi 2 cho chó Poodle 10 tuần tuổi?',
    answerSummary: 'Tư vấn tiêm mũi 7 bệnh, điều kiện sức khỏe cần kiểm tra (không sốt, đã tẩy giun) trước khi tiêm.',
    category: 'Tiêm phòng',
    time: '2 giờ trước',
    rating: 'UNRATED',
    bookedExam: false,
  },
];

export function AdminAIPage() {
  const [activeTab, setActiveTab] = useState<'KNOWLEDGE' | 'CONFIG' | 'LOGS'>('KNOWLEDGE');
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCategory, setSelectedCategory] = useState<string>('ALL');
  const [docs, setDocs] = useState<RAGDocument[]>(MOCK_DOCS);

  // Modals state
  const [uploadModalOpen, setUploadModalOpen] = useState(false);
  const [viewDoc, setViewDoc] = useState<RAGDocument | null>(null);
  const [deleteDoc, setDeleteDoc] = useState<RAGDocument | null>(null);

  // Config state
  const [systemPrompt, setSystemPrompt] = useState(
    'Bạn là Bác sĩ Thú y AI của hệ thống PetCare Clinic & Spa. Nhiệm vụ của bạn là tư vấn chăm sóc sức khỏe, sơ cứu ban đầu, tiêm phòng và dinh dưỡng cho thú cưng dựa trên tài liệu chuyên môn được cung cấp (RAG). Luôn nhấn mạnh khuyến cáo mang thú cưng đi khám nếu tình trạng kéo dài hoặc nguy kịch. Không tự ý kê các loại thuốc kháng sinh mạnh hoặc thuốc độc hại.'
  );
  const [selectedModel, setSelectedModel] = useState('gemini-1.5-flash');
  const [temperature, setTemperature] = useState('0.3');
  const [topK, setTopK] = useState('5');

  // Filtered docs
  const filteredDocs = docs.filter((d) => {
    const matchSearch = d.title.toLowerCase().includes(searchQuery.toLowerCase()) || d.fileName.toLowerCase().includes(searchQuery.toLowerCase());
    const matchCat = selectedCategory === 'ALL' || d.category === selectedCategory;
    return matchSearch && matchCat;
  });

  const totalChunks = docs.reduce((acc, d) => acc + d.chunksCount, 0);

  const handleDeleteDoc = () => {
    if (!deleteDoc) return;
    setDocs((prev) => prev.filter((d) => d.id !== deleteDoc.id));
    toast.success(`Đã xóa tài liệu "${deleteDoc.title}" khỏi kho tri thức RAG.`);
    setDeleteDoc(null);
  };

  const handleSaveConfig = (e: React.FormEvent) => {
    e.preventDefault();
    toast.success('Đã lưu cấu hình Bác sĩ AI PetCare thành công!');
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <div className="flex items-center gap-2 mb-1">
            <h1 className="text-2xl font-semibold text-(--text-primary)">Quản lý Trí tuệ nhân tạo (AI & RAG)</h1>
            <Badge className="bg-amber-100 text-amber-800 border-amber-200 flex items-center gap-1">
              <Sparkles className="w-3 h-3 text-amber-600" /> Bác Sĩ AI RAG
            </Badge>
          </div>
          <p className="text-(--text-secondary)">Quản lý kho tri thức thú y, vector hóa tài liệu RAG và cấu hình trợ lý AI</p>
        </div>

        {/* Tab Buttons */}
        <div className="flex bg-(--bg-secondary) p-1 rounded-xl border border-(--border-color)">
          <button
            onClick={() => setActiveTab('KNOWLEDGE')}
            className={`px-3.5 py-1.5 rounded-lg text-xs font-semibold transition-colors flex items-center gap-1.5 ${
              activeTab === 'KNOWLEDGE' ? 'bg-white text-(--color-primary) shadow-xs' : 'text-(--text-secondary) hover:text-(--text-primary)'
            }`}
          >
            <FileText className="w-3.5 h-3.5" />
            Kho tri thức ({docs.length})
          </button>
          <button
            onClick={() => setActiveTab('CONFIG')}
            className={`px-3.5 py-1.5 rounded-lg text-xs font-semibold transition-colors flex items-center gap-1.5 ${
              activeTab === 'CONFIG' ? 'bg-white text-(--color-primary) shadow-xs' : 'text-(--text-secondary) hover:text-(--text-primary)'
            }`}
          >
            <Sliders className="w-3.5 h-3.5" />
            Cấu hình Prompt
          </button>
          <button
            onClick={() => setActiveTab('LOGS')}
            className={`px-3.5 py-1.5 rounded-lg text-xs font-semibold transition-colors flex items-center gap-1.5 ${
              activeTab === 'LOGS' ? 'bg-white text-(--color-primary) shadow-xs' : 'text-(--text-secondary) hover:text-(--text-primary)'
            }`}
          >
            <MessageSquare className="w-3.5 h-3.5" />
            Nhật ký hội thoại
          </button>
        </div>
      </div>

      {/* KPI Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-purple-100 text-purple-600">
              <FileText className="h-6 w-6" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-(--text-primary)">{docs.length}</p>
              <p className="text-sm text-(--text-secondary)">Tài liệu RAG</p>
            </div>
          </CardContent>
        </Card>

        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-blue-100 text-blue-600">
              <Bot className="h-6 w-6" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-blue-600">{totalChunks.toLocaleString()}</p>
              <p className="text-sm text-(--text-secondary)">Vector Chunks</p>
            </div>
          </CardContent>
        </Card>

        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-green-100 text-green-600">
              <CheckCircle2 className="h-6 w-6" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-green-600">96.4%</p>
              <p className="text-sm text-(--text-secondary)">Độ chính xác truy vấn</p>
            </div>
          </CardContent>
        </Card>

        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-amber-100 text-amber-600">
              <Sparkles className="h-6 w-6" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-amber-600">68%</p>
              <p className="text-sm text-(--text-secondary)">Chuyển đổi đặt khám</p>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* ================================================================
          TAB 1: KHO TRI THỨC (KNOWLEDGE BASE)
          ================================================================ */}
      {activeTab === 'KNOWLEDGE' && (
        <div className="space-y-4">
          <Card className="card">
            <CardContent className="p-4 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
              <div className="flex flex-wrap items-center gap-3">
                <div className="relative w-72">
                  <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-(--text-tertiary)" />
                  <Input
                    placeholder="Tìm tài liệu, tên file..."
                    className="pl-9 input"
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                  />
                </div>
                <select
                  value={selectedCategory}
                  onChange={(e) => setSelectedCategory(e.target.value)}
                  className="h-10 px-3 rounded-xl border border-(--color-border-default) bg-white text-xs font-medium focus:outline-none focus:ring-2 focus:ring-(--color-primary)"
                >
                  <option value="ALL">Tất cả chuyên mục</option>
                  <option value="FIRST_AID">Sơ cứu khẩn cấp</option>
                  <option value="NUTRITION">Dinh dưỡng & Thức ăn</option>
                  <option value="VACCINE">Phác đồ tiêm phòng</option>
                  <option value="CLINICAL">Bệnh lý lâm sàng</option>
                  <option value="GENERAL">Cẩm nang nuôi dưỡng</option>
                </select>
              </div>

              <Button onClick={() => setUploadModalOpen(true)} className="bg-(--color-primary) hover:bg-accent-hover text-white">
                <UploadCloud className="mr-2 h-4 w-4" />
                Tải lên tài liệu mới
              </Button>
            </CardContent>
          </Card>

          {/* Table */}
          <Card className="card">
            <CardContent className="p-0 overflow-hidden">
              <table className="table text-xs">
                <thead>
                  <tr>
                    <th>Tên tài liệu / File</th>
                    <th>Chuyên mục</th>
                    <th>Dung lượng</th>
                    <th>Số Chunks</th>
                    <th>Người đăng tải</th>
                    <th>Trạng thái</th>
                    <th className="text-right">Thao tác</th>
                  </tr>
                </thead>
                <tbody>
                  {filteredDocs.map((doc) => (
                    <tr key={doc.id}>
                      <td>
                        <div className="font-semibold text-(--text-primary) text-sm">{doc.title}</div>
                        <div className="text-[11px] text-(--text-tertiary) flex items-center gap-1 font-mono">
                          <FileText className="w-3 h-3 text-red-500" />
                          {doc.fileName}
                        </div>
                      </td>
                      <td>
                        <Badge className={CATEGORY_MAP[doc.category].className}>{CATEGORY_MAP[doc.category].label}</Badge>
                      </td>
                      <td className="text-(--text-secondary) font-mono">{doc.fileSize}</td>
                      <td>
                        <span className="font-bold text-blue-600 bg-blue-50 px-2 py-0.5 rounded-md font-mono">{doc.chunksCount}</span>
                      </td>
                      <td>
                        <div className="text-(--text-primary)">{doc.uploadedBy}</div>
                        <div className="text-[11px] text-(--text-tertiary)">{doc.uploadedAt}</div>
                      </td>
                      <td>
                        {doc.status === 'INDEXED' && (
                          <Badge className="bg-green-100 text-green-700 flex items-center gap-1 w-fit">
                            <CheckCircle2 className="w-3 h-3" /> Đã Vector hóa
                          </Badge>
                        )}
                        {doc.status === 'PROCESSING' && (
                          <Badge className="bg-amber-100 text-amber-700 flex items-center gap-1 w-fit">
                            <Clock className="w-3 h-3 animate-spin" /> Đang nhúng...
                          </Badge>
                        )}
                        {doc.status === 'ERROR' && (
                          <Badge className="bg-red-100 text-red-700 flex items-center gap-1 w-fit">
                            <AlertTriangle className="w-3 h-3" /> Lỗi Index
                          </Badge>
                        )}
                      </td>
                      <td className="text-right">
                        <div className="flex items-center justify-end gap-1">
                          <Button size="sm" variant="ghost" onClick={() => setViewDoc(doc)} title="Xem chi tiết">
                            <Eye className="w-4 h-4" />
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() => toast.success(`Đang re-index lại tài liệu "${doc.fileName}"...`)}
                            title="Chạy lại Vector Indexing"
                          >
                            <RotateCcw className="w-4 h-4 text-blue-600" />
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() => setDeleteDoc(doc)}
                            className="text-red-500 hover:text-red-600 hover:bg-red-50"
                            title="Xóa tài liệu"
                          >
                            <Trash2 className="w-4 h-4" />
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </CardContent>
          </Card>
        </div>
      )}

      {/* ================================================================
          TAB 2: CẤU HÌNH PROMPT & MODEL
          ================================================================ */}
      {activeTab === 'CONFIG' && (
        <Card className="card">
          <CardContent className="p-6">
            <form onSubmit={handleSaveConfig} className="space-y-6 max-w-3xl">
              <div>
                <h3 className="text-base font-semibold text-(--text-primary) mb-1">Cấu hình Hệ thống & System Prompt</h3>
                <p className="text-xs text-(--text-secondary) mb-3">
                  Quy định thái độ, cách thức phản hồi và ranh giới an toàn chuyên môn cho Bác sĩ AI PetCare.
                </p>
                <textarea
                  rows={6}
                  value={systemPrompt}
                  onChange={(e) => setSystemPrompt(e.target.value)}
                  className="w-full rounded-xl border border-(--color-border-default) p-3 text-xs leading-relaxed text-(--text-primary) focus:outline-none focus:ring-2 focus:ring-(--color-primary) font-mono"
                />
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
                <div>
                  <label className="block text-xs font-semibold text-(--text-primary) mb-1.5">Model AI nền tảng</label>
                  <select
                    value={selectedModel}
                    onChange={(e) => setSelectedModel(e.target.value)}
                    className="w-full h-10 px-3 rounded-xl border border-(--color-border-default) bg-white text-xs focus:outline-none focus:ring-2 focus:ring-(--color-primary)"
                  >
                    <option value="gemini-1.5-flash">Google Gemini 1.5 Flash (Khuyên dùng)</option>
                    <option value="gemini-1.5-pro">Google Gemini 1.5 Pro</option>
                    <option value="gpt-4o">OpenAI GPT-4o</option>
                    <option value="local-med-llama">Local Med-Llama-3 8B (On-premise)</option>
                  </select>
                </div>

                <div>
                  <label className="block text-xs font-semibold text-(--text-primary) mb-1.5">Temperature (Độ sáng tạo)</label>
                  <Input
                    type="number"
                    step="0.1"
                    min="0"
                    max="1"
                    value={temperature}
                    onChange={(e) => setTemperature(e.target.value)}
                    className="input h-10 text-xs"
                  />
                  <span className="text-[10px] text-(--text-tertiary)">Nên để 0.2 - 0.4 cho y khoa chính xác.</span>
                </div>

                <div>
                  <label className="block text-xs font-semibold text-(--text-primary) mb-1.5">Top-K Chunks truy xuất RAG</label>
                  <Input
                    type="number"
                    min="1"
                    max="15"
                    value={topK}
                    onChange={(e) => setTopK(e.target.value)}
                    className="input h-10 text-xs"
                  />
                  <span className="text-[10px] text-(--text-tertiary)">Số đoạn tri thức trích xuất mỗi câu hỏi.</span>
                </div>
              </div>

              <div className="p-4 rounded-xl bg-amber-50/80 border border-amber-200 text-xs text-amber-800 space-y-1">
                <div className="font-bold flex items-center gap-1.5">
                  <AlertTriangle className="w-4 h-4 text-amber-600" /> Ranh giới an toàn y tế bắt buộc
                </div>
                <p>• AI tự động kích hoạt cờ CẢNH BÁO NGUY HIỂM khi phát hiện các triệu chứng: khó thở, sùi bọt mép, nôn ra máu, co giật.</p>
                <p>• Khi phát hiện nguy cấp, AI lập tức hiển thị nút Đặt lịch khám và số hotline phòng cấp cứu 24/7 của trạm thú y.</p>
              </div>

              <div className="flex justify-end gap-3 pt-2">
                <Button type="submit" className="bg-(--color-primary) hover:bg-accent-hover text-white">
                  Lưu cấu hình AI
                </Button>
              </div>
            </form>
          </CardContent>
        </Card>
      )}

      {/* ================================================================
          TAB 3: NHẬT KÝ HỘI THOẠI & PHẢN HỒI
          ================================================================ */}
      {activeTab === 'LOGS' && (
        <Card className="card">
          <CardContent className="p-0 overflow-hidden">
            <table className="table text-xs">
              <thead>
                <tr>
                  <th>Khách hàng</th>
                  <th>Câu hỏi khách đặt</th>
                  <th>Tóm tắt phản hồi AI</th>
                  <th>Phân loại</th>
                  <th>Thời gian</th>
                  <th>Đánh giá</th>
                  <th>Đặt khám</th>
                </tr>
              </thead>
              <tbody>
                {MOCK_CHAT_LOGS.map((log) => (
                  <tr key={log.id}>
                    <td className="font-semibold text-(--text-primary)">{log.customerName}</td>
                    <td className="max-w-xs font-medium text-(--text-primary)">{log.question}</td>
                    <td className="max-w-md text-(--text-secondary)">{log.answerSummary}</td>
                    <td>
                      <Badge variant="secondary">{log.category}</Badge>
                    </td>
                    <td className="text-(--text-tertiary)">{log.time}</td>
                    <td>
                      {log.rating === 'GOOD' && (
                        <span className="inline-flex items-center gap-1 text-green-600 bg-green-50 px-2 py-0.5 rounded-full font-bold">
                          <ThumbsUp className="w-3 h-3" /> Hài lòng
                        </span>
                      )}
                      {log.rating === 'POOR' && (
                        <span className="inline-flex items-center gap-1 text-red-600 bg-red-50 px-2 py-0.5 rounded-full font-bold">
                          <ThumbsDown className="w-3 h-3" /> Chưa đủ
                        </span>
                      )}
                      {log.rating === 'UNRATED' && <span className="text-(--text-tertiary)">-</span>}
                    </td>
                    <td>
                      {log.bookedExam ? (
                        <Badge className="bg-emerald-100 text-emerald-800">Đã đặt khám</Badge>
                      ) : (
                        <span className="text-(--text-tertiary)">Chưa</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </CardContent>
        </Card>
      )}

      {/* ================================================================
          MODALS
          ================================================================ */}

      {/* Upload Document Modal */}
      <FormModal
        open={uploadModalOpen}
        onOpenChange={setUploadModalOpen}
        title="Tải lên tài liệu tri thức RAG"
        description="File sẽ được phân tích, cắt đoạn (Chunking) và tạo Vector Embeddings vào cơ sở dữ liệu tri thức."
        onSubmit={() => {
          setDocs((prev) => [
            {
              id: `doc-${Date.now()}`,
              title: 'Tài liệu hướng dẫn điều trị bổ sung',
              fileName: 'Huong_Dan_Dieu_Tri_Moi.pdf',
              category: 'CLINICAL',
              fileSize: '1.8 MB',
              chunksCount: 95,
              uploadedBy: 'Ban Quản Trị',
              uploadedAt: 'Vừa xong',
              status: 'INDEXED',
              summary: 'Tài liệu mới bổ sung vào kho tri thức vector cho Bác sĩ AI.',
            },
            ...prev,
          ]);
          setUploadModalOpen(false);
          toast.success('Đã tải lên và hoàn tất vector hóa tài liệu vào hệ thống RAG!');
        }}
        submitText="Bắt đầu nhúng Vector"
        size="md"
      >
        <div className="space-y-4 text-xs">
          <div>
            <label className="block font-semibold mb-1.5 text-(--text-primary)">Tiêu đề tài liệu *</label>
            <Input placeholder="VD: Hướng dẫn chăm sóc da liễu & nấm mèo" required />
          </div>

          <div>
            <label className="block font-semibold mb-1.5 text-(--text-primary)">Chuyên mục y khoa *</label>
            <select className="w-full h-10 px-3 rounded-xl border border-(--color-border-default) bg-white text-xs focus:outline-none focus:ring-2 focus:ring-(--color-primary)">
              <option value="FIRST_AID">Sơ cứu khẩn cấp</option>
              <option value="NUTRITION">Dinh dưỡng & Thức ăn</option>
              <option value="VACCINE">Phác đồ tiêm phòng</option>
              <option value="CLINICAL">Bệnh lý lâm sàng</option>
              <option value="GENERAL">Cẩm nang nuôi dưỡng</option>
            </select>
          </div>

          <div>
            <label className="block font-semibold mb-1.5 text-(--text-primary)">Tập tin tài liệu (.pdf, .docx, .txt)</label>
            <div className="border-2 border-dashed border-(--color-border-default) rounded-2xl p-6 text-center hover:border-(--color-primary) transition-colors cursor-pointer bg-stone-50/50">
              <UploadCloud className="w-8 h-8 text-(--color-primary) mx-auto mb-2" />
              <p className="font-semibold text-(--text-primary)">Kéo thả file vào đây hoặc bấm để chọn</p>
              <p className="text-[11px] text-(--text-tertiary) mt-1">Hỗ trợ PDF, DOCX, TXT tối đa 25MB</p>
            </div>
          </div>
        </div>
      </FormModal>

      {/* Detail Document Modal */}
      <DetailModal
        open={!!viewDoc}
        onOpenChange={(o) => !o && setViewDoc(null)}
        title="Chi tiết tài liệu tri thức RAG"
        size="md"
      >
        {viewDoc && (
          <div className="space-y-3 text-xs">
            <div className="p-3 bg-stone-50 rounded-xl space-y-1">
              <h4 className="font-bold text-sm text-(--text-primary)">{viewDoc.title}</h4>
              <p className="font-mono text-stone-500">{viewDoc.fileName}</p>
            </div>
            <InfoRow label="Chuyên mục" value={<Badge className={CATEGORY_MAP[viewDoc.category].className}>{CATEGORY_MAP[viewDoc.category].label}</Badge>} />
            <InfoRow label="Dung lượng" value={viewDoc.fileSize} />
            <InfoRow label="Số lượng Chunks" value={<span className="font-bold text-blue-600">{viewDoc.chunksCount} chunks</span>} />
            <InfoRow label="Người đăng tải" value={viewDoc.uploadedBy} />
            <InfoRow label="Ngày cập nhật" value={viewDoc.uploadedAt} />
            <InfoRow
              label="Trạng thái"
              value={
                viewDoc.status === 'INDEXED' ? (
                  <span className="text-green-600 font-semibold flex items-center gap-1">
                    <CheckCircle2 className="w-3.5 h-3.5" /> Đã Vector hóa
                  </span>
                ) : (
                  viewDoc.status
                )
              }
            />
            <div className="pt-2 border-t border-(--color-border-light)">
              <p className="font-semibold text-(--text-secondary) mb-1">Tóm tắt nội dung tri thức:</p>
              <p className="text-(--text-primary) leading-relaxed bg-white p-2.5 rounded-lg border border-stone-200">
                {viewDoc.summary}
              </p>
            </div>
          </div>
        )}
      </DetailModal>

      {/* Delete Confirmation */}
      <ConfirmModal
        open={!!deleteDoc}
        onOpenChange={(o) => !o && setDeleteDoc(null)}
        type="danger"
        title="Xóa tài liệu khỏi kho RAG?"
        description={`Bạn có chắc muốn xóa "${deleteDoc?.title}"? Hệ thống AI sẽ không còn sử dụng các đoạn vector trích xuất từ tài liệu này để trả lời.`}
        confirmText="Xác nhận xóa"
        onConfirm={handleDeleteDoc}
      />
    </div>
  );
}
