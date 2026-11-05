// Client Gemini tối giản (fetch, không thư viện) cho spike T56: nhúng `gemini-embedding-001` 768 chiều và sinh
// `gemini-2.5-flash` tắt suy nghĩ, JSON theo lược đồ {inScope, answer}.
// Khóa chỉ đọc từ biến môi trường GEMINI_API_KEY và chỉ đi trong header; không ghi ra file hay log.
//
// Gói free có giới hạn tốc độ: có giãn nhịp giữa các lệnh và thử lại có giới hạn khi 429.
// Theo BR-AI-10 BE thật KHÔNG thử lại trong cùng request, nên lỗi ở lần thử đầu được ghi vào `firstFail`
// (= "sẽ là UNAVAILABLE"); thử lại chỉ để thu đủ số liệu, và thời gian chờ không tính vào độ trễ.
const BASE = 'https://generativelanguage.googleapis.com/v1beta/models/';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export class ProviderError extends Error {
  constructor(kind, message, fails = []) { super(message); this.kind = kind; this.fails = fails; }
}

const SCHEMA = {
  type: 'OBJECT',
  properties: { inScope: { type: 'BOOLEAN' }, answer: { type: 'STRING' } },
  required: ['inScope', 'answer'],
};

export function createGemini({
  key = process.env.GEMINI_API_KEY,
  llmModel = 'gemini-2.5-flash',
  embModel = 'gemini-embedding-001',
  dim = 768,
  gapMs = { embed: 800, llm: 6500 },
  maxRetries = 3,
  timeoutMs = 15000,
} = {}) {
  if (!key) throw new ProviderError('NO_KEY', 'Thiếu GEMINI_API_KEY (chỉ đặt qua biến môi trường)');
  const blank = () => ({ calls: 0, first429: 0, firstTimeout: 0, firstError: 0, retries: 0 });
  const stats = { embed: blank(), llm: blank() };
  const nextAt = { embed: 0, llm: 0 };

  async function pace(kind) {
    const wait = nextAt[kind] - Date.now();
    if (wait > 0) await sleep(wait);
    nextAt[kind] = Date.now() + gapMs[kind];
  }

  // Trả { json, ms (chỉ lần thử thành công), firstFail, fails } (fails = mã lỗi của MỌI lần thử hỏng, ví dụ ['429', '503']).
  async function request(kind, model, method, body) {
    stats[kind].calls++;
    let firstFail = null;
    const fails = [];
    for (let attempt = 0; ; attempt++) {
      await pace(kind);
      const t0 = performance.now();
      let kindFail;
      let delayMs = 3000;
      try {
        const res = await fetch(`${BASE}${model}:${method}`, {
          method: 'POST',
          headers: { 'content-type': 'application/json', 'x-goog-api-key': key },
          body: JSON.stringify(body),
          signal: AbortSignal.timeout(timeoutMs),
        });
        const text = await res.text();
        const ms = performance.now() - t0;
        if (res.ok) return { json: JSON.parse(text), ms, firstFail, fails };
        let err = {};
        try { err = JSON.parse(text).error ?? {}; } catch { /* thân không phải JSON */ }
        if (res.status === 429) {
          if (/perday|per day/i.test(text)) {
            const limit = /limit: (\d+)/.exec(err.message ?? '')?.[1];
            throw new ProviderError('DAILY_QUOTA', `Hết hạn mức theo NGÀY (${model}, giới hạn ${limit ?? '?'} lệnh/ngày)`, [...fails, '429D']);
          }
          fails.push('429');
          const retry = (err.details ?? []).find((d) => d.retryDelay)?.retryDelay;
          delayMs = retry ? Math.ceil(parseFloat(retry) * 1000) + 1000 : 30000;
          kindFail = 'PROVIDER_RATE_LIMIT';
        } else if (res.status >= 500) {
          fails.push(String(res.status));
          delayMs = 4000;
          kindFail = 'PROVIDER_ERROR';
        } else {
          throw new ProviderError(res.status === 403 || res.status === 401 ? 'NO_KEY' : 'PROVIDER_ERROR', `HTTP ${res.status}: ${(err.message ?? text).slice(0, 300)}`, [...fails, String(res.status)]);
        }
      } catch (e) {
        if (e instanceof ProviderError) throw e;
        kindFail = e.name === 'TimeoutError' ? 'TIMEOUT' : 'PROVIDER_ERROR';
        fails.push(e.name === 'TimeoutError' ? 'timeout' : 'network');
      }
      if (!firstFail) {
        firstFail = kindFail;
        if (kindFail === 'PROVIDER_RATE_LIMIT') stats[kind].first429++;
        else if (kindFail === 'TIMEOUT') stats[kind].firstTimeout++;
        else stats[kind].firstError++;
      }
      if (attempt >= maxRetries) throw new ProviderError(kindFail, `${kind} thất bại sau ${attempt + 1} lần thử (${kindFail})`, fails);
      stats[kind].retries++;
      await sleep(Math.min(delayMs, 90000));
    }
  }

  const check = (values) => {
    if (values?.length !== dim) throw new ProviderError('PROVIDER_ERROR', `Số chiều nhúng ${values?.length}, cần ${dim}`);
    return values;
  };
  const embedBody = (text, taskType) => ({ model: `models/${embModel}`, content: { parts: [{ text }] }, taskType, outputDimensionality: dim });

  return {
    stats,
    llmModel,
    embModel,
    // Một câu hỏi (loại truy vấn), đo độ trễ thật.
    async embedQuery(text) {
      const { json, ms, firstFail, fails } = await request('embed', embModel, 'embedContent', embedBody(text, 'RETRIEVAL_QUERY'));
      return { vector: check(json.embedding?.values), ms, firstFail, fails };
    },
    // Nhiều đoạn tài liệu (loại tài liệu), theo lô nhỏ để khỏi vượt hạn mức token mỗi phút.
    async embedDocuments(texts, batch = 16) {
      const vectors = [];
      for (let i = 0; i < texts.length; i += batch) {
        const requests = texts.slice(i, i + batch).map((t) => embedBody(t, 'RETRIEVAL_DOCUMENT'));
        const { json } = await request('embed', embModel, 'batchEmbedContents', { requests });
        vectors.push(...json.embeddings.map((e) => check(e.values)));
      }
      return vectors;
    },
    // system = lời nhắc §5.4, user = khối tài liệu + câu hỏi. Trả { inScope, answer, usage, ms, firstFail }.
    async generate(system, user) {
      const body = {
        systemInstruction: { parts: [{ text: system }] },
        contents: [{ role: 'user', parts: [{ text: user }] }],
        generationConfig: {
          temperature: 0.2,
          maxOutputTokens: 600,
          thinkingConfig: { thinkingBudget: 0 },
          responseMimeType: 'application/json',
          responseSchema: SCHEMA,
        },
      };
      const { json, ms, firstFail, fails } = await request('llm', llmModel, 'generateContent', body);
      const cand = json.candidates?.[0];
      let parsed;
      try { parsed = JSON.parse(cand?.content?.parts?.[0]?.text ?? ''); } catch { /* xử lý bên dưới */ }
      if (typeof parsed?.inScope !== 'boolean' || typeof parsed?.answer !== 'string') {
        throw new ProviderError('PROVIDER_ERROR', `JSON không hợp lệ (finishReason=${cand?.finishReason ?? 'không có'})`);
      }
      const u = json.usageMetadata ?? {};
      return {
        ...parsed,
        usage: { input: u.promptTokenCount ?? null, output: u.candidatesTokenCount ?? null, thought: u.thoughtsTokenCount ?? 0 },
        ms,
        firstFail,
        fails,
      };
    },
  };
}
