const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('playwright');

const origin = process.env.READER_URL || 'http://localhost:8080';
const shots = process.env.PHASE2_SCREENSHOTS || '/tmp/opencode/phase2-screenshots';
const calls = [];
const pageErrors = [];
let browser, server, service;
const narrative = [
  'The fraa remembered the quiet gate. "I thought it was hopeless," she said. Then the light returned.',
  'The avout chuckled at the memory. The night softened around the garden.',
  'They reached the mathic garden. It was calm at last.'
];

function wav(seconds = .9) {
  const count = Math.floor(16000 * seconds), buffer = Buffer.alloc(44 + count * 2);
  buffer.write('RIFF'); buffer.writeUInt32LE(buffer.length - 8, 4); buffer.write('WAVE', 8); buffer.write('fmt ', 12);
  buffer.writeUInt32LE(16, 16); buffer.writeUInt16LE(1, 20); buffer.writeUInt16LE(1, 22); buffer.writeUInt32LE(16000, 24);
  buffer.writeUInt32LE(32000, 28); buffer.writeUInt16LE(2, 32); buffer.writeUInt16LE(16, 34); buffer.write('data', 36); buffer.writeUInt32LE(count * 2, 40);
  for (let index = 0; index < count; index++) buffer.writeInt16LE(Math.sin(index * .09) * 600, 44 + index * 2);
  return buffer;
}

function bookText(long = false, pages = 6) {
  return Array.from({ length: pages }, (_, index) => `# Chapter ${index + 1}\n\n${index === 0 && long ? 'The fraa watched the quiet garden. '.repeat(90) : narrative[index % narrative.length]}\n\nPAGE_MARKER ${index + 1}\n\nPAPER_REFERENCE bibliography record ${index + 1}`).join('\n\n');
}

function cropPdf() {
  const stream = 'BT /F1 16 Tf 150 520 Td (Clear text inside generous blank page margins.) Tj 0 -28 Td (These borders should crop without hiding words.) Tj 0 -28 Td (Fraa walked across the quiet academic garden.) Tj ET';
  const objects = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Count 1 /Kids [3 0 R] >>', '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>', `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`, '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'];
  let output = '%PDF-1.4\n'; const offsets = [];
  objects.forEach((object, index) => { offsets.push(Buffer.byteLength(output)); output += `${index + 1} 0 obj\n${object}\nendobj\n`; });
  const xref = Buffer.byteLength(output); output += `xref\n0 6\n0000000000 65535 f \n${offsets.map((offset) => `${String(offset).padStart(10, '0')} 00000 n \n`).join('')}trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`;
  return Buffer.from(output);
}

async function startServer() {
  server = http.createServer(async (request, response) => {
    response.setHeader('Access-Control-Allow-Origin', origin);
    response.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization, model, xi-api-key, X-Voice-Test');
    response.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
    if (request.method === 'OPTIONS') { response.writeHead(204); response.end(); return; }
    const chunks = []; for await (const chunk of request) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString() || '{}');
    calls.push({ route: request.url, body, headers: request.headers });
    if (request.url.startsWith('/chat')) {
      if (request.url === '/chat-authfail') { response.writeHead(401, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ error: { message: 'Invalid key secret-fixture-auth-token; Bearer secret-fixture-auth-token' } })); return; }
      if (request.url === '/chat-nojson' && body.response_format) { response.writeHead(400, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ error: { message: 'response_format JSON mode is unsupported' } })); return; }
      const instruction = body.messages?.[0]?.content || '', target = body.messages?.at(-1)?.content || '';
      let answer;
      if (instruction.includes('audiobook director')) {
        const selected = target.split('TARGET — only this text may be narrated:\n')[1]?.split('\nEND TARGET')[0] || '';
        const paragraph = narrative.find((text) => selected.includes(text)) || selected.split('\n').find((line) => line.trim() && !line.startsWith('Chapter') && !line.includes('PAGE_MARKER') && !line.includes('PAPER_REFERENCE')) || '';
        const segments = (paragraph.match(/[^.!?]+[.!?]+(?:\s+|$)|[^.!?]+$/g) || [paragraph]).filter((text) => text.trim()).map((text, index) => ({ excerpt: text.trim(), script: `${index === 1 ? '[voice breaking]' : '[calm measured tone]'} ${text.trim()} [pause]` }));
        answer = JSON.stringify({ chunk_tokens: 110, chunk_characters: 180, max_audio_tokens: 1536, segments, skipped: ['Page numbers', 'Bibliography'] });
        if (request.url === '/chat-malformed' && calls.filter((call) => call.route === '/chat-malformed').length === 1) answer = 'I will narrate the meaningful parts of this page.';
        if (request.url === '/chat-tolerant') { segments.forEach((segment) => segment.excerpt = segment.excerpt.normalize('NFKC').replace(/"/g, '“')); answer = JSON.stringify({ chunks: segments, skipped: ['Page numbers'] }); }
        if (request.url === '/chat-invented') answer = JSON.stringify({ segments: [{ excerpt: 'A completely unrelated invented passage.', script: '[happy] A completely unrelated invented passage.' }] });
      } else if (instruction.includes('recapping ONLY')) answer = '• The gate was remembered.\n• The mood was quiet.\n• The reader can resume.';
      else answer = 'A fraa is a member of this book’s scholarly community.';
      await new Promise((resolve) => setTimeout(resolve, 70));
      response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify({ choices: [{ message: { content: answer } }] })); return;
    }
    await new Promise((resolve) => setTimeout(resolve, request.url.includes('slow') ? 220 : 40));
    response.setHeader('Content-Type', 'audio/wav'); response.end(wav(request.url.includes('/fish') ? .28 : .9));
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve)); service = `http://127.0.0.1:${server.address().port}`;
}

async function context(viewport = { width: 1100, height: 850 }, clock = false) {
  const ctx = await browser.newContext({ viewport, hasTouch: true, acceptDownloads: true, timezoneId: 'UTC' });
  await ctx.addInitScript(() => {
    window.__speech = []; window.__contexts = []; window.__audios = [];
    const OriginalAudio = window.Audio;
    window.Audio = function(...args) { const audio = new OriginalAudio(...args); window.__audios.push(audio); return audio; };
    const OriginalContext = window.AudioContext;
    window.AudioContext = class extends OriginalContext { constructor(...args) { super(...args); window.__contexts.push(this); } };
    let timeout, utterance, remaining = 350, start;
    const synthesis = {
      speaking: false, paused: false, addEventListener() {}, getVoices: () => [{ name: 'Offline test voice', voiceURI: 'offline-test', lang: 'en-US' }],
      cancel() { clearTimeout(timeout); utterance = null; this.speaking = false; this.paused = false; },
      speak(value) { this.cancel(); utterance = value; this.speaking = true; remaining = 350; start = Date.now(); window.__speech.push(value.text); setTimeout(() => { if (utterance === value) value.onstart?.(); }, 0); timeout = setTimeout(() => { this.speaking = false; if (utterance === value) value.onend?.(); }, remaining); },
      pause() { clearTimeout(timeout); remaining -= Date.now() - start; this.paused = true; },
      resume() { this.paused = false; start = Date.now(); const value = utterance; timeout = setTimeout(() => { this.speaking = false; if (utterance === value) value.onend?.(); }, Math.max(1, remaining)); }
    };
    Object.defineProperty(window, 'speechSynthesis', { value: synthesis }); window.SpeechSynthesisUtterance = function(text) { this.text = text; };
  });
  const page = await ctx.newPage(); page.on('pageerror', (error) => pageErrors.push(error.message));
  if (clock) await page.clock.install({ time: new Date('2026-11-24T12:00:00Z') });
  await page.goto(origin, { waitUntil: 'networkidle' }); return { ctx, page };
}

async function upload(page, text = bookText(), name = 'Dense reader.md') {
  await page.locator('#pdf-file-input').setInputFiles({ name, mimeType: 'text/markdown', buffer: Buffer.from(text) });
  await page.locator('#text-reflow-content .reader-token').first().waitFor({ state: 'visible' });
}

async function configureAI(page, route = '/chat', key = '') {
  await page.locator('#reader-settings-btn').click(); await page.locator('#open-ai-manager-btn').click();
  await page.locator('[data-preset="openai"]').click(); await page.locator('#ai-name-input').fill('Saved reader director');
  await page.locator('#ai-model-input').fill('fixture-director'); await page.locator('#ai-endpoint-input').fill(`${service}${route}`); await page.locator('#ai-key-input').fill(key);
  if (route !== '/chat' && (await page.locator('#ai-fallback-toggle-btn').innerText()).trim() === 'ON') await page.locator('#ai-fallback-toggle-btn').click();
  await page.locator('#save-ai-profile-btn').click(); await page.locator('#done-ai-config-btn').click();
}

async function voice(page, fish = false, slow = false) {
  await page.locator('#reader-settings-btn').click(); await page.locator('#open-tts-settings-btn').click();
  await page.locator(`[data-tts-preset="${fish ? 'fish' : 'openai'}"]`).click();
  await page.locator('#tts-endpoint-input').fill(`${service}/${fish ? 'fish' : 'speech'}${slow ? '-slow' : ''}`);
  await page.locator('#tts-voice-input').fill(fish ? 'reference-clear-reader' : 'alloy');
  await page.locator('#tts-format-input').selectOption('wav'); await page.locator('#tts-key-input').fill('fixture-key');
  await page.locator('#tts-profile-save-btn').click(); await page.locator('#tts-settings-modal').waitFor({ state: 'hidden' }); await page.locator('#done-settings-btn').click();
}

async function tools(page, id) { await page.locator('#reader-tools-btn').click(); await page.locator(`#${id}`).click(); }
async function jump(page, number) { await page.locator('#jump-page-btn').click(); await page.locator('#jump-page-input').fill(String(number)); await page.locator('#jump-page-form').evaluate((form) => form.requestSubmit()); await page.waitForFunction((pageNumber) => document.getElementById('reader-page-subtitle').textContent.includes(`Page ${pageNumber} of`), number); await page.waitForSelector('.page-turn-overlay', { state: 'detached' }); }
async function selectWord(page, word) { const box = await page.locator(`#text-reflow-content .reader-token[data-word="${word}"]`).first().boundingBox(); await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2); await page.mouse.down(); await page.waitForTimeout(300); await page.mouse.up(); }
async function test(name, action) { if (process.env.PHASE2_TEST && !name.includes(process.env.PHASE2_TEST)) return; await action(); console.log(`PASS ${name}`); }

async function main() {
  await startServer(); await fs.mkdir(shots, { recursive: true });
  browser = await chromium.launch({ headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage', '--autoplay-policy=no-user-gesture-required'] });
  await test('personal glossary persists and opens future-page definitions offline without AI', async () => {
    const { ctx, page } = await context();
    try {
      await upload(page); await configureAI(page); await selectWord(page, 'fraa'); await page.locator('#sel-define-btn').click();
      await page.waitForFunction(() => !document.getElementById('define-save-glossary-btn').disabled);
      await page.locator('#define-save-glossary-btn').click(); await page.locator('#define-close-btn').click();
      await page.waitForSelector('.glossary-word');
      await jump(page, 4); const before = calls.filter((call) => call.route === '/chat').length;
      await ctx.setOffline(true); await page.locator('.glossary-word').first().click();
      assert.match(await page.locator('#define-body').innerText(), /scholarly community/); assert.match(await page.locator('#define-source').innerText(), /Offline/);
      assert.equal(calls.filter((call) => call.route === '/chat').length, before);
      await page.screenshot({ path: path.join(shots, 'offline-glossary.png') });
    } finally { await ctx.close(); }
  });
  await test('return pin restores exact scroll and natural page pace feeds a thirty-day heatmap', async () => {
    const { ctx, page } = await context({ width: 1100, height: 850 }, true);
    try {
      await upload(page, bookText(true)); await page.evaluate(() => document.getElementById('reading-stage').scrollTop = 290);
      const original = await page.locator('#reading-stage').evaluate((stage) => stage.scrollTop);
      await jump(page, 6); assert(await page.locator('#return-page-pill').isVisible());
      await page.locator('#return-page-btn').click(); await page.waitForFunction(() => document.getElementById('reader-page-subtitle').textContent.includes('Page 1 of'));
      await page.waitForSelector('.page-turn-overlay', { state: 'detached' }); await page.waitForTimeout(100);
      assert(Math.abs(await page.locator('#reading-stage').evaluate((stage) => stage.scrollTop) - original) < 3);
      await page.clock.runFor(12000); await page.locator('#next-page-btn').click(); await page.waitForSelector('.page-turn-overlay', { state: 'detached' });
      assert.match(await page.locator('#reader-page-subtitle').innerText(), /~/);
      await tools(page, 'reader-ritual-btn'); assert.equal(await page.locator('.ritual-day').count(), 30);
      assert(await page.locator('.ritual-day[data-level="1"]').count() > 0);
    } finally { await ctx.close(); }
  });
  await test('recap sends only text before the saved End Line', async () => {
    const { ctx, page } = await context();
    try {
      await upload(page, '# Start\n\nThe fraa remembered the gate. The safe memory remained. FUTURE_SPOILER must stay unread.\n\n# Next\n\nAnother page.');
      await configureAI(page); await page.locator('.reader-token[data-word="safe"]').click(); await page.locator('#sel-endline-btn').click();
      await tools(page, 'reader-recap-btn'); await page.waitForFunction(() => document.getElementById('recap-content').textContent.includes('•'));
      const recap = calls.findLast((call) => call.route === '/chat' && call.body.messages[0].content.includes('recapping ONLY'));
      assert(recap); assert(!recap.body.messages.at(-1).content.includes('FUTURE_SPOILER'));
      assert(recap.body.messages.at(-1).content.includes('safe memory'));
    } finally { await ctx.close(); }
  });
  await test('metadata title, custom cover and favorite survive reload', async () => {
    const { ctx, page } = await context();
    try {
      await upload(page); await tools(page, 'reader-edit-btn'); await page.locator('#book-title-input').fill('Anathem notes'); await page.locator('#book-favorite-input').check();
      const png = await page.evaluate(() => { const canvas = document.createElement('canvas'); canvas.width = 40; canvas.height = 60; const c = canvas.getContext('2d'); c.fillStyle = '#6378aa'; c.fillRect(0, 0, 40, 60); return canvas.toDataURL('image/png').split(',')[1]; });
      await page.locator('#book-cover-input').setInputFiles({ name: 'cover.png', mimeType: 'image/png', buffer: Buffer.from(png, 'base64') });
      await page.waitForFunction(() => document.getElementById('book-cover-preview').src.startsWith('data:image/jpeg'));
      await page.locator('#book-editor-save').click(); await page.locator('#back-to-lib-btn').click(); await page.reload({ waitUntil: 'networkidle' });
      await page.locator('[data-cat="favorites"]').click(); await page.waitForFunction(() => document.getElementById('books-grid').textContent.includes('Anathem notes'));
      assert(await page.locator('#books-grid img[src^="data:image/jpeg"]').count() > 0);
    } finally { await ctx.close(); }
  });
  await test('PDF margin crop enlarges text and preserves overlay alignment', async () => {
    const { ctx, page } = await context({ width: 390, height: 844 });
    try {
      await page.locator('#pdf-file-input').setInputFiles({ name: 'Wide margins.pdf', mimeType: 'application/pdf', buffer: cropPdf() });
      await page.locator('#pdf-text-overlay .reader-token').first().waitFor({ state: 'visible' });
      const before = await page.locator('#pdf-text-overlay .reader-token').first().boundingBox();
      await tools(page, 'crop-margins-btn'); await page.waitForFunction((width) => document.querySelector('#pdf-text-overlay .reader-token').getBoundingClientRect().width > width * 1.3, before.width);
      const result = await page.evaluate(() => { const canvas = document.getElementById('pdf-canvas').getBoundingClientRect(); return Array.from(document.querySelectorAll('#pdf-text-overlay .reader-token')).every((token) => { const rect = token.getBoundingClientRect(); return rect.left >= canvas.left - 3 && rect.right <= canvas.right + 3 && rect.top >= canvas.top - 3 && rect.bottom <= canvas.bottom + 3; }); });
      assert(result); await page.screenshot({ path: path.join(shots, 'phone-cropped-pdf.png') });
    } finally { await ctx.close(); }
  });
  await test('sunset scheduler changes phases and eye coach changes micro-prompts', async () => {
    const { ctx, page } = await context({ width: 1100, height: 850 }, true);
    try {
      await upload(page); await page.locator('#reader-settings-btn').click(); await page.locator('#eye-schedule-toggle').click();
      assert(await page.locator('body').evaluate((body) => body.classList.contains('theme-sepia')));
      await page.clock.setSystemTime(new Date('2026-11-24T20:00:00Z')); await page.clock.runFor(61000);
      assert(await page.locator('body').evaluate((body) => body.classList.contains('theme-dark')));
      await page.clock.setSystemTime(new Date('2026-11-24T23:00:00Z')); await page.clock.runFor(61000);
      assert(await page.locator('body').evaluate((body) => body.classList.contains('theme-midnight')));
      await page.locator('[data-theme="sepia"]').click(); assert.equal(await page.locator('#eye-schedule-toggle').getAttribute('aria-pressed'), 'false');
      await page.locator('#done-settings-btn').click(); await page.locator('#timer-pill-btn').click(); await page.locator('#test-rest-btn').click();
      await page.clock.runFor(8000); assert.match(await page.locator('#rest-title').innerText(), /Slow blink/);
      await page.clock.runFor(7000); assert.match(await page.locator('#rest-title').innerText(), /Relax/);
      await page.clock.runFor(6000); assert(await page.locator('#continue-reading-btn').isEnabled());
    } finally { await ctx.close(); }
  });
  await test('procedural soundscapes run offline and suspend or stop cleanly', async () => {
    const { ctx, page } = await context();
    try {
      await upload(page); await tools(page, 'reader-ambient-btn'); await ctx.setOffline(true);
      await page.locator('[data-ambient-scene="rain"]').click(); await page.waitForFunction(() => window.__contexts.some((context) => context.state === 'running'));
      await page.locator('#ambient-play-btn').click(); await page.waitForFunction(() => window.__contexts.at(-1).state === 'suspended');
      await page.locator('[data-ambient-scene="brown"]').click(); await page.waitForFunction(() => window.__contexts.at(-1).state === 'running');
      await page.locator('[data-ambient-scene="fire"]').click(); await page.waitForFunction(() => window.__contexts.at(-1).state === 'running');
      await page.locator('[data-ambient-scene="library"]').click(); await page.waitForFunction(() => window.__contexts.at(-1).state === 'running');
      await page.locator('#ambient-stop-btn').click(); await page.waitForFunction(() => window.__contexts.every((context) => context.state === 'closed'));
    } finally { await ctx.close(); }
  });
  await test('local music rotates its record, controls playback and persists songs', async () => {
    const { ctx, page } = await context({ width: 390, height: 844 });
    try {
      await page.locator('#lib-music-btn').click();
      await page.locator('#music-file-input').setInputFiles([{ name: 'Artist - Blue Hour.wav', mimeType: 'audio/wav', buffer: wav(3) }, { name: 'Artist - Dawn.wav', mimeType: 'audio/wav', buffer: wav(1) }]);
      await page.waitForFunction(() => document.querySelectorAll('.music-track').length === 2);
      await page.locator('#music-play').click(); await page.waitForFunction(() => document.body.classList.contains('music-is-playing'));
      assert.equal(await page.locator('#music-modal .vinyl-record').evaluate((record) => getComputedStyle(record).animationPlayState), 'running');
      await page.screenshot({ path: path.join(shots, 'phone-music-player.png') });
      await page.locator('#music-play').click(); assert(!await page.locator('body').evaluate((body) => body.classList.contains('music-is-playing')));
      await page.locator('#music-next').click(); await page.waitForFunction(() => document.getElementById('music-title').textContent === 'Dawn');
      await page.reload({ waitUntil: 'networkidle' }); await page.locator('#lib-music-btn').click(); await page.waitForFunction(() => document.querySelectorAll('.music-track').length === 2);
      await ctx.setOffline(true); await page.locator('#music-play').click(); await page.waitForFunction(() => document.body.classList.contains('music-is-playing'));
    } finally { await ctx.close(); }
  });
  await test('saved speech replays after reload and offline without new TTS calls', async () => {
    const { ctx, page } = await context();
    try {
      await upload(page, '# Audio\n\nThe quiet garden waits. The gate opens softly.'); await voice(page); const before = calls.filter((call) => call.route === '/speech').length;
      await page.locator('#tts-btn').click(); await page.waitForSelector('.reader-highlight-band[data-kind="tts"]'); await page.waitForTimeout(80);
      assert(calls.filter((call) => call.route === '/speech').length > before); await page.locator('#tts-stop-bar-btn').click();
      await page.reload({ waitUntil: 'networkidle' }); await page.locator('.open-book-trigger').first().click(); await page.locator('#text-reflow-content .reader-token').first().waitFor({ state: 'visible' });
      const count = calls.filter((call) => call.route === '/speech').length; await ctx.setOffline(true); await page.locator('#tts-btn').click();
      await page.waitForSelector('.reader-highlight-band[data-kind="tts"]'); assert.equal(calls.filter((call) => call.route === '/speech').length, count);
      assert.equal(await page.evaluate(() => window.__speech.length), 0);
    } finally { await ctx.close(); }
  });
  await test('AI-directed S2 conversion excludes non-narrative text and reuses page/range/book audio', async () => {
    const { ctx, page } = await context();
    try {
      await upload(page, bookText(false, 3)); await configureAI(page); await voice(page, true);
      const liveBefore = calls.length; await page.locator('#tts-btn').click(); await page.waitForSelector('.reader-highlight-band[data-kind="tts"]');
      const live = calls.slice(liveBefore).filter((call) => call.route === '/fish'); assert(live.length > 0);
      assert(live.every((call) => call.body.text.includes('[') && !call.body.text.includes('PAGE_MARKER') && !call.body.text.includes('PAPER_REFERENCE')));
      await page.locator('#tts-stop-bar-btn').click(); await jump(page, 2);
      await tools(page, 'reader-audiobook-btn'); const before = calls.length; await page.locator('#audiobook-convert').click();
      await page.waitForFunction(() => document.getElementById('audiobook-conversion-label').textContent.startsWith('Saved'), null, { timeout: 30000 });
      const fish = calls.slice(before).filter((call) => call.route === '/fish'); assert(fish.length >= 2);
      fish.forEach((call) => { assert.equal(call.headers.model, 's2.1-pro-free'); assert.equal(call.body.reference_id, 'reference-clear-reader'); assert.equal(call.body.max_new_tokens, 1536); assert.equal(call.body.chunk_length, 180); assert(call.body.text.includes('[')); assert(!call.body.text.includes('PAGE_MARKER') && !call.body.text.includes('PAPER_REFERENCE')); });
      const guide = calls.slice(before).find((call) => call.route === '/chat'); assert(guide.body.messages.at(-1).content.includes('COMPLETE CURRENT PAGE'));
      const count = calls.length; await page.locator('#audiobook-convert').click(); await page.waitForTimeout(150); assert.equal(calls.length, count);
      await page.locator('#audiobook-play').click();
      const downloadPromise = page.waitForEvent('download'); await page.locator('.audiobook-job').first().getByText('Download', { exact: true }).click(); const download = await downloadPromise;
      const audio = await fs.readFile(await download.path()); assert.equal(audio.toString('ascii', 0, 4), 'RIFF'); assert(audio.length > 44);
      await page.locator('#audiobook-scope').selectOption('book'); await page.locator('#audiobook-convert').click(); await page.waitForFunction(() => document.getElementById('audiobook-conversion-label').textContent.startsWith('Saved'), null, { timeout: 30000 });
      const fullCount = calls.length; await page.locator('#audiobook-scope').selectOption('range'); await page.locator('#audiobook-start-page').fill('2'); await page.locator('#audiobook-end-page').fill('3'); await page.locator('#audiobook-convert').click();
      await page.waitForFunction(() => document.getElementById('audiobook-conversion-label').textContent.startsWith('Saved'), null, { timeout: 30000 }); assert.equal(calls.length, fullCount);
      await page.screenshot({ path: path.join(shots, 'saved-audiobooks.png') });
      await page.reload({ waitUntil: 'networkidle' }); await page.locator('#lib-audiobook-btn').click(); await page.waitForFunction(() => document.querySelectorAll('.audiobook-job').length >= 3);
      const storedCount = calls.length; await ctx.setOffline(true); await page.locator('.audiobook-job').first().getByText('Play', { exact: true }).click();
      await page.waitForFunction(() => window.__audios.some((audio) => !audio.paused && audio.src.startsWith('blob:'))); assert.equal(calls.length, storedCount);
    } finally { await ctx.close(); }
  });
  await test('paused audiobook conversion saves the current passage and resumes without regenerating it', async () => {
    const { ctx, page } = await context();
    try {
      await upload(page, bookText(false, 3)); await configureAI(page); await voice(page, true, true);
      await tools(page, 'reader-audiobook-btn'); await page.locator('#audiobook-scope').selectOption('book'); await page.locator('#audiobook-convert').click();
      await page.waitForFunction(() => document.getElementById('audiobook-conversion-label').textContent.includes('1 saved passages'));
      await page.locator('#audiobook-pause-convert').click();
      await page.waitForFunction(() => document.getElementById('audiobook-conversion-label').textContent.startsWith('Paused'), null, { timeout: 10000 });
      const recorded = calls.filter((call) => call.route === '/fish-slow').map((call) => call.body.text);
      assert(recorded.length > 0);
      await page.reload({ waitUntil: 'networkidle' }); await page.locator('#lib-audiobook-btn').click();
      await page.waitForSelector('.audiobook-job'); await page.locator('.audiobook-job').first().getByText('Resume', { exact: true }).click();
      await page.waitForFunction(() => document.getElementById('audiobook-conversion-label').textContent.startsWith('Saved'), null, { timeout: 30000 });
      for (const text of recorded) assert.equal(calls.filter((call) => call.route === '/fish-slow' && call.body.text === text).length, 1);
    } finally { await ctx.close(); }
  });
  await test('narration planning reports authentication errors without exposing keys', async () => {
    const { ctx, page } = await context();
    try {
      await upload(page, bookText(false, 1)); await configureAI(page, '/chat-authfail', 'secret-fixture-auth-token'); await voice(page, true);
      const count = calls.filter((call) => call.route === '/fish').length;
      await tools(page, 'reader-audiobook-btn'); await page.locator('#audiobook-convert').click();
      await page.waitForFunction(() => document.getElementById('audiobook-conversion-label').textContent.includes('HTTP 401'), null, { timeout: 8000 }).catch(async (error) => {
        console.log('Planning diagnostic:', await page.locator('#audiobook-conversion-label').innerText());
        console.log('Request routes:', calls.map((call) => call.route)); console.log('Browser errors:', pageErrors); throw error;
      });
      const error = await page.locator('#audiobook-conversion-label').innerText(); assert(!error.includes('secret-fixture-auth-token')); assert(error.includes('API key'));
      assert.equal(calls.filter((call) => call.route === '/fish').length, count);
    } finally { await ctx.close(); }
  });
  await test('narration planning repairs malformed JSON and handles unsupported JSON mode', async () => {
    for (const route of ['/chat-malformed', '/chat-nojson']) {
      const { ctx, page } = await context();
      try {
        await upload(page, bookText(false, 1)); await configureAI(page, route); await voice(page, true);
        await tools(page, 'reader-audiobook-btn'); await page.locator('#audiobook-convert').click();
        await page.waitForFunction(() => document.getElementById('audiobook-conversion-label').textContent.startsWith('Saved'), null, { timeout: 15000 });
        const requests = calls.filter((call) => call.route === route); assert.equal(requests.length, 2); assert(requests[0].body.response_format);
        if (route === '/chat-nojson') assert(!requests[1].body.response_format);
      } finally { await ctx.close(); }
    }
  });
  await test('narration planning tolerates typography changes but rejects invented excerpts', async () => {
    for (const route of ['/chat-tolerant', '/chat-invented']) {
      const { ctx, page } = await context();
      try {
        await upload(page, bookText(false, 1)); await configureAI(page, route); await voice(page, true);
        const count = calls.filter((call) => call.route === '/fish').length;
        await tools(page, 'reader-audiobook-btn'); await page.locator('#audiobook-convert').click();
        if (route === '/chat-tolerant') await page.waitForFunction(() => document.getElementById('audiobook-conversion-label').textContent.startsWith('Saved'), null, { timeout: 15000 });
        else { await page.waitForFunction(() => document.getElementById('audiobook-conversion-label').textContent.includes('outside the supplied page')); assert.equal(calls.filter((call) => call.route === '/fish').length, count); }
      } finally { await ctx.close(); }
    }
  });
  assert.deepEqual(pageErrors, []); console.log(`Phase 2 verification complete. Screenshots: ${shots}`);
}

main().catch((error) => { console.error(error); process.exitCode = 1; }).finally(async () => { if (browser) await browser.close(); if (server) await new Promise((resolve) => server.close(resolve)); });
