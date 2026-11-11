import fs from 'node:fs';
import path from 'node:path';

const directory = path.resolve('docs/tien');
const titles = ['Ranh giới và điểm phối hợp', 'Sequence: enqueue và rollback',
  'Sequence: worker và retry', 'Sequence: API báo cáo', 'Outbox theo ERD 4c gốc'];
const cards = titles.map((title, index) => {
  const filename = `architecture-rendered-${index + 1}.svg`;
  fs.accessSync(path.join(directory, filename));
  return `<section><h2>${title}</h2><a href="${filename}">Mở SVG</a><div class="diagram"><img src="${filename}" alt="${title}"></div></section>`;
}).join('\n');
fs.writeFileSync(path.join(directory, 'architecture.html'), `<!doctype html><html lang="vi"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Vingo — Phần Tiến trên repo Java</title>
<style>*{box-sizing:border-box}body{margin:0;background:#eef2f7;color:#172238;font:16px/1.6 system-ui,sans-serif}
header,main{max-width:1600px;margin:auto;padding:24px}h1{font-size:32px}h2{font-size:22px;margin:0}a{color:#2454a6}
section{padding:28px;margin-bottom:24px;background:white;border:1px solid #d8e1ee;border-radius:16px}
.diagram{overflow:auto;padding:12px}img{width:100%;height:auto;min-width:720px}p{max-width:1000px}
@media print{section{break-before:page}.diagram{overflow:visible}img{min-width:0}}</style>
<header><p>VINGO · PHẦN TIẾN · LOCAL · 11/11/2026</p><h1>Notifications & Reporting trên repo Java chung</h1>
<p>Chỉ mô tả phần Tiến. Nét đứt là điểm cần chủ module tích hợp, không khẳng định Auth/Ticketing đã thực hiện.
JWT, lỗi, request_id, Clock và ShedLock giữ theo hạ tầng team.</p>
<p><a href="architecture-rendered.md">Luồng và API chi tiết</a> · <a href="contract-pending.md">Các điểm chờ Đức/chủ module duyệt</a></p>
</header><main>${cards}</main></html>`, 'utf8');
