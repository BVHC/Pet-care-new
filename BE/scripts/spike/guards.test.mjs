// Kiểm offline (không mạng, không CSDL, không tốn hạn mức): `node --test BE/scripts/spike/guards.test.mjs`
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { chunkDoc } from './chunk.mjs';
import { DOSE_CASES } from './dose-cases.mjs';
import { detectIntent, doseViolation, hasPhrase, isEmergency, maskPetNames, maskPii, preRoute } from './guards.mjs';

const fx = JSON.parse(readFileSync(new URL('./questions.json', import.meta.url), 'utf8'));

test('bộ câu hỏi đúng 43 câu và đúng số câu từng nhóm (§14.1)', () => {
  assert.equal(fx.questions.length, 43);
  const counts = {};
  for (const q of fx.questions) counts[q.group] = (counts[q.group] ?? 0) + 1;
  assert.deepEqual(counts, fx.groups);
  assert.equal(new Set(fx.questions.map((q) => q.id)).size, 43);
});

test('định tuyến trước truy hồi khớp kỳ vọng của cả 43 câu', () => {
  for (const q of fx.questions) {
    const pets = fx.customers[q.customer]?.pets ?? [];
    const r = preRoute(q.question, q.endpoint, pets);
    if (q.expect.embed === false) {
      assert.equal(r.result, q.expect.results[0], `${q.id} "${q.question}"`);
    } else {
      assert.equal(r.result, null, `${q.id} "${q.question}" bị chặn sớm nhầm: ${r.result}`);
    }
    if ('intent' in q.expect) assert.equal(r.intent, q.expect.intent, `${q.id} ý định`);
    else if (q.expect.embed !== false) assert.equal(r.intent, null, `${q.id} không được có ý định cá nhân`);
  }
});

test('ranh giới từ: "liệt kê" không là cấp cứu, "giá" không trùng "già", "tiêm" không trùng "tiệm"', () => {
  assert.equal(isEmergency('Liệt kê giúp tôi các mũi tiêm cho mèo con'), false);
  assert.equal(isEmergency('Chó bị liệt chân sau'), true);
  assert.equal(isEmergency('chó kho tho'), true, 'cụm >= 2 âm tiết nhận dạng bỏ dấu');
  assert.equal(hasPhrase('giá tiêm bao nhiêu', 'già'), false);
  assert.equal(hasPhrase('tiệm thuốc nào gần đây', 'tiêm'), false);
  assert.equal(detectIntent('lịch tiêm của bé già nhà tôi'), 'VACCINATION_SCHEDULE');
  assert.equal(detectIntent('Giá tiêm vaccine của tôi bao nhiêu'), null, 'cụm chỉ giá loại ý định cá nhân');
});

test('dấu hiệu yếu đủ cho lịch hẹn nhưng không đủ cho tiêm chủng', () => {
  assert.equal(detectIntent('Sắp tới có lịch hẹn nào không?'), 'APPOINTMENT_UPCOMING');
  assert.equal(detectIntent('Mũi tiêm tiếp theo là khi nào?'), null);
  assert.equal(detectIntent('Mũi tiêm tiếp theo của tôi là khi nào?'), 'VACCINATION_SCHEDULE');
});

test('tên thú trùng từ thông dụng chỉ tính khi đi sau bé / con / chú / em', () => {
  const pets = [{ id: 1, name: 'Vàng' }];
  assert.equal(detectIntent('giá tiêm vàng', pets), null);
  assert.equal(detectIntent('Vàng tiêm khi nào?', pets), null);
  assert.equal(detectIntent('Bé Vàng tiêm khi nào?', pets), 'VACCINATION_SCHEDULE');
  assert.equal(detectIntent('Mochi tiêm khi nào?', [{ id: 2, name: 'Mochi' }]), 'VACCINATION_SCHEDULE');
});

test('che dữ liệu cá nhân', () => {
  assert.equal(maskPii('Email tôi là a.b@x.vn, gọi 0901 234 567'), 'Email tôi là [email], gọi [sđt]');
  assert.equal(maskPii('CCCD 012345678901 và 123456789'), 'CCCD [số] và [số]');
  assert.equal(maskPii('Chó 10 kg uống 500 ml nước'), 'Chó 10 kg uống 500 ml nước');
  assert.equal(maskPetNames('Mochi và mochi ăn gì? Mochila thì không', [{ name: 'Mochi' }]), '[thú] và [thú] ăn gì? Mochila thì không');
});

test('hậu kiểm liều: bảng ca tổng hợp (in tỷ lệ chặn nhầm / sót)', () => {
  let fp = 0;
  let fn = 0;
  const lines = [];
  for (const c of DOSE_CASES) {
    const blocked = doseViolation(c.s) !== null;
    if (blocked && !c.block) { fp++; lines.push(`  chặn NHẦM: ${c.s}`); }
    if (!blocked && c.block) { fn++; lines.push(`  SÓT: ${c.s}`); }
  }
  console.log(`  [liều] ${DOSE_CASES.length} ca: chặn nhầm ${fp}, sót ${fn}\n${lines.join('\n')}`);
  // Ca "không có edge" là hành vi đặc tả cam kết: phải đúng tuyệt đối. Ca `edge` ghi lại giới hạn đã biết của heuristic.
  for (const c of DOSE_CASES.filter((x) => !x.edge)) assert.equal(doseViolation(c.s) !== null, c.block, c.s);
});

test('cắt đoạn: 300–1 200 ký tự, văn bản nhúng có tiêu đề', () => {
  const para = 'Câu một dài vừa phải để thử cắt đoạn. '.repeat(12);
  const md = `# A\n${para}\n## B\nngắn\n## C\n${para}${para}${para}`;
  const chunks = chunkDoc({ title: 'Tài liệu thử', content: md });
  assert.ok(chunks.length >= 3);
  for (const c of chunks) {
    assert.ok(c.text.length <= 1300, `đoạn ${c.ordinal} dài ${c.text.length}`);
    assert.ok(c.embedText.startsWith('Tài liệu thử'));
  }
  assert.deepEqual(chunks.map((c) => c.ordinal), chunks.map((_, i) => i + 1));
});
