// Kết nối Postgres giữ sống: một tiến trình `docker exec -i <container> psql` chạy suốt, gửi câu lệnh qua stdin.
// Chọn cách này vì CLAUDE.md cấm `npm i` ngoài FE/ và FE không có `pg`; mỗi lệnh `docker exec` riêng tốn ~150 ms
// (07 §13 đã ghi là không đại diện), còn một tiến trình giữ sống chỉ tốn vài ms qua ống.
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';

const MARK = '__END__';

export function openDb({ container = process.env.SPIKE_PG_CONTAINER ?? 'petcare-spike-pgvector', db = 'spike' } = {}) {
  const proc = spawn('docker', ['exec', '-i', container, 'psql', '-U', 'postgres', '-d', db, '-X', '-A', '-t', '-q'], {
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  let out = '';
  let err = '';
  let pending = null;
  proc.stdout.setEncoding('utf8');
  proc.stderr.setEncoding('utf8');
  const settle = () => {
    if (!pending || !out.includes(`${MARK}\n`) || !err.includes(`${MARK}\n`)) return;
    const result = out.slice(0, out.indexOf(`${MARK}\n`)).trim();
    const error = err.slice(0, err.indexOf(`${MARK}\n`));
    out = err = '';
    const { resolve, reject } = pending;
    pending = null;
    /ERROR:|FATAL:/.test(error) ? reject(new Error(error.trim())) : resolve(result);
  };
  proc.stdout.on('data', (d) => { out += d; settle(); });
  proc.stderr.on('data', (d) => { err += d; settle(); });

  let chain = Promise.resolve();
  const query = (sql) => {
    const run = () => new Promise((resolve, reject) => {
      pending = { resolve, reject };
      proc.stdin.write(`${sql.trim().replace(/;?$/, ';')}\n\\echo ${MARK}\n\\warn ${MARK}\n`);
    });
    chain = chain.then(run, run);
    return chain;
  };
  return {
    query,
    json: async (sql) => JSON.parse(await query(sql)),
    close: () => { proc.stdin.end('\\q\n'); },
  };
}

// Chuỗi hằng dạng dollar-quote, an toàn với mọi nội dung (không cần escape dấu nháy).
export function lit(value) {
  if (value === null || value === undefined) return 'NULL';
  const s = String(value);
  let tag;
  do tag = `$q${randomBytes(3).toString('hex')}$`; while (s.includes(tag));
  return `${tag}${s}${tag}`;
}

export const vec = (arr) => `'[${arr.join(',')}]'::vector`;
