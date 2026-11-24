const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('playwright');

const origin = process.env.READER_URL || 'http://localhost:8080';
const screenshotDir = process.env.READER_SCREENSHOTS || '/tmp/opencode/reader-screenshots';
const requests = [];
const errors = [];
let browser, server, service;

function wave(seconds = .9) {
  const samples = Math.floor(16000 * seconds), data = Buffer.alloc(44 + samples * 2);
  data.write('RIFF'); data.writeUInt32LE(data.length - 8, 4); data.write('WAVE', 8);
  data.write('fmt ', 12); data.writeUInt32LE(16, 16); data.writeUInt16LE(1, 20);
  data.writeUInt16LE(1, 22); data.writeUInt32LE(16000, 24); data.writeUInt32LE(32000, 28);
  data.writeUInt16LE(2, 32); data.writeUInt16LE(16, 34); data.write('data', 36); data.writeUInt32LE(samples * 2, 40);
  for (let index = 0; index < samples; index++) data.writeInt16LE(Math.round(Math.sin(index * 2 * Math.PI * 220 / 16000) * 500), 44 + index * 2);
  return data;
}

function pdfFixture() {
  const lines = [
    'A quiet reader selects words in a continuous line of text.',
    'Adjacent words should form a single smooth translucent band.',
    'The toolbar stays clear of the entire selected passage.',
    'Drag either handle to refine the beginning or the ending.',
    'Turn a page smoothly and keep reading without a blank flash.'
  ];
  const stream = (second) => `BT /F1 16 Tf 40 730 Td ${lines.map((line, index) => `${index ? '0 -28 Td ' : ''}(${second ? 'Next page: ' : ''}${line}) Tj`).join('\n')} ET`;
  const first = stream(false), second = stream(true);
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Count 2 /Kids [3 0 R 5 0 R] >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 7 0 R >> >> /Contents 4 0 R >>',
    `<< /Length ${Buffer.byteLength(first)} >>\nstream\n${first}\nendstream`,
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 7 0 R >> >> /Contents 6 0 R >>',
    `<< /Length ${Buffer.byteLength(second)} >>\nstream\n${second}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'
  ];
  let content = '%PDF-1.4\n'; const offsets = [0];
  objects.forEach((object, index) => { offsets.push(Buffer.byteLength(content)); content += `${index + 1} 0 obj\n${object}\nendobj\n`; });
  const xref = Buffer.byteLength(content);
  content += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  offsets.slice(1).forEach((offset) => { content += `${String(offset).padStart(10, '0')} 00000 n \n`; });
  content += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(content);
}

async function startService() {
  server = http.createServer(async (request, response) => {
    response.setHeader('Access-Control-Allow-Origin', origin);
    response.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
    response.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization, xi-api-key, X-Voice-Test');
    if (request.method === 'OPTIONS') { response.writeHead(204); response.end(); return; }
    const chunks = []; for await (const chunk of request) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString() || '{}');
    requests.push({ path: request.url, body, headers: request.headers, at: Date.now() });
    if (request.url.startsWith('/fail')) { response.writeHead(503); response.end('Unavailable'); return; }
    if (request.url.startsWith('/chat')) {
      await new Promise((resolve) => setTimeout(resolve, 300));
      response.setHeader('Content-Type', 'application/json');
      response.end(JSON.stringify({ choices: [{ message: { content: 'A clear answer based on the supplied passage.' } }] })); return;
    }
    await new Promise((resolve) => setTimeout(resolve, 40));
    const audio = wave(request.url.includes('fast') ? .1 : .9);
    if (request.url.startsWith('/custom')) {
      response.setHeader('Content-Type', 'application/json');
      response.end(JSON.stringify({ audio_base64: audio.toString('base64'), mime_type: 'audio/wav' }));
    } else { response.setHeader('Content-Type', 'audio/wav'); response.end(audio); }
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  service = `http://127.0.0.1:${server.address().port}`;
}

async function context(viewport = { width: 1100, height: 850 }) {
  const ctx = await browser.newContext({ viewport, hasTouch: true, acceptDownloads: true });
  await ctx.addInitScript(() => {
    window.__speechEvents = [];
    window.__touchListeners = {};
    const add = EventTarget.prototype.addEventListener;
    EventTarget.prototype.addEventListener = function(type, ...args) {
      if (this.id === 'reading-stage' && ['touchmove', 'touchend', 'touchcancel'].includes(type)) window.__touchListeners[type] = (window.__touchListeners[type] || 0) + 1;
      return add.call(this, type, ...args);
    };
    let timer, current, remaining = 180, started;
    const synth = {
      speaking: false, paused: false,
      getVoices: () => [{ name: 'Offline fixture voice', lang: 'en-US', voiceURI: 'fixture-offline', localService: true }],
      addEventListener() {},
      cancel() { clearTimeout(timer); this.speaking = false; this.paused = false; current = null; },
      speak(utterance) {
        this.cancel(); current = utterance; this.speaking = true; remaining = 180; started = Date.now();
        window.__speechEvents.push(utterance.text);
        setTimeout(() => { if (current === utterance) utterance.onstart?.(); }, 0);
        timer = setTimeout(() => { this.speaking = false; if (current === utterance) utterance.onend?.(); }, remaining);
      },
      pause() { clearTimeout(timer); remaining = Math.max(1, remaining - (Date.now() - started)); this.paused = true; },
      resume() { this.paused = false; started = Date.now(); const utterance = current; timer = setTimeout(() => { this.speaking = false; if (current === utterance) utterance.onend?.(); }, remaining); }
    };
    Object.defineProperty(window, 'speechSynthesis', { value: synth, configurable: true });
    window.SpeechSynthesisUtterance = function(text) { this.text = text; this.rate = 1; };
  });
  const page = await ctx.newPage();
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto(origin, { waitUntil: 'networkidle', timeout: 60000 });
  return { ctx, page };
}

async function sample(page) {
  await page.locator('#load-demo-btn').click();
  await page.locator('#text-reflow-content .reader-token').first().waitFor({ state: 'visible' });
  await page.waitForTimeout(100);
}

async function select(page, first = 3, last = 15, root = '#text-reflow-content') {
  const start = await page.locator(`${root} .reader-token`).nth(first).boundingBox();
  const end = await page.locator(`${root} .reader-token`).nth(last).boundingBox();
  await page.mouse.move(start.x + start.width / 2, start.y + start.height / 2);
  await page.mouse.down(); await page.waitForTimeout(280);
  await page.mouse.move(end.x + end.width / 2, end.y + end.height / 2, { steps: 12 });
  await page.mouse.up(); await page.waitForTimeout(80);
}

async function assertSelection(page) {
  const result = await page.evaluate(() => {
    const bar = document.getElementById('selection-action-bar').getBoundingClientRect();
    const tokens = Array.from(document.querySelectorAll('#reading-stage .reader-token.is-selected')).filter((token) => token.getBoundingClientRect().width);
    const rects = tokens.map((token) => token.getBoundingClientRect());
    return {
      tokenCount: tokens.length,
      bands: document.querySelectorAll('.reader-highlight-band[data-kind="selection"]').length,
      cleanTokens: tokens.every((token) => getComputedStyle(token).boxShadow === 'none' && getComputedStyle(token).backgroundColor === 'rgba(0, 0, 0, 0)'),
      singleRow: getComputedStyle(document.querySelector('.selection-toolbar-row')).flexWrap === 'nowrap',
      clear: bar.bottom <= Math.min(...rects.map((r) => r.top)) - 10 || bar.top >= Math.max(...rects.map((r) => r.bottom)) + 10,
      withinWidth: bar.left >= 0 && bar.right <= innerWidth,
      handles: !document.getElementById('selection-end-handle').classList.contains('hidden')
    };
  });
  assert(result.tokenCount > 3);
  assert(result.bands > 0 && result.bands < result.tokenCount);
  assert(result.cleanTokens && result.singleRow && result.clear && result.withinWidth && result.handles, JSON.stringify(result));
}

async function voice(page, options = {}) {
  await page.locator('#reader-settings-btn').click();
  await page.locator('#open-tts-settings-btn').click();
  await page.locator('[data-tts-preset="openai"]').click();
  await page.locator('#tts-name-input').fill(options.name || 'Local fixture');
  await page.locator('#tts-engine-input').selectOption(options.engine || 'openai');
  await page.locator('#tts-endpoint-input').fill(options.endpoint || `${service}/v1/audio/speech`);
  await page.locator('#tts-model-input').fill('fixture-model');
  await page.locator('#tts-voice-input').fill(options.voice || 'alloy');
  await page.locator('#tts-format-input').selectOption('wav');
  await page.locator('#tts-key-input').fill('fixture-key');
  if (options.engine === 'custom') {
    await page.locator('#tts-header-input').fill('X-Voice-Test');
    await page.locator('#tts-header-value-input').fill('fixture-header');
    await page.locator('#tts-payload-input').fill('{"utterance":"Read {{text}}","speaker":"{{voice}}","model":"{{model}}","speed":{{speed}}}');
  }
  await page.locator('#tts-profile-save-btn').click();
  await page.locator('#tts-settings-modal').waitFor({ state: 'hidden' });
  await page.locator('#done-settings-btn').click();
}

async function test(name, work) {
  await work(); console.log(`PASS ${name}`);
}

async function main() {
  await startService(); await fs.mkdir(screenshotDir, { recursive: true });
  browser = await chromium.launch({ headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage', '--autoplay-policy=no-user-gesture-required'] });
  await test('tablet continuous selection, handles, saved highlights and toolbar', async () => {
    const { ctx, page } = await context();
    try {
      await sample(page); await select(page); await assertSelection(page);
      const handle = await page.locator('#selection-end-handle').boundingBox();
      const old = await page.locator('#text-reflow-content .reader-token').nth(15).boundingBox();
      const target = await page.locator('#text-reflow-content .reader-token').nth(25).boundingBox();
      const offset = handle.y + 15 - (old.y + old.height / 2);
      await page.mouse.move(handle.x + handle.width / 2, handle.y + 15); await page.mouse.down();
      await page.mouse.move(target.x + target.width / 2, target.y + target.height / 2 + offset, { steps: 12 }); await page.mouse.up();
      await page.waitForTimeout(100); await assertSelection(page);
      assert(await page.locator('#text-reflow-content .reader-token.is-selected').count() >= 20);
      await page.screenshot({ path: path.join(screenshotDir, 'tablet-selection.png') });
      await page.locator('#sel-hl-toggle-btn').click(); await page.locator('.hl-color-pick[data-color="amber"]').click();
      await page.waitForFunction(() => document.querySelectorAll('.reader-highlight-band[data-kind="saved"]').length > 0);
      assert.equal(await page.locator('.pdf-hl-rect').count(), 0);
      assert(await page.locator('.reader-highlight-band[data-kind="saved"]').count() < 10);
      assert.deepEqual(await page.evaluate(() => window.__touchListeners), { touchmove: 1, touchend: 1, touchcancel: 1 });
    } finally { await ctx.close(); }
  });
  await test('phone selection uses one scrollable row and avoids the text', async () => {
    const { ctx, page } = await context({ width: 390, height: 844 });
    try {
      await sample(page); await select(page, 1, 12); await assertSelection(page);
      const next = await page.locator('#next-page-btn').boundingBox(); assert(next.x + next.width <= 390);
      await page.screenshot({ path: path.join(screenshotDir, 'phone-selection.png') });
    }
    finally { await ctx.close(); }
  });
  await test('3D turns, swipe progress, cancellation, and persisted effect choices', async () => {
    const { ctx, page } = await context();
    try {
      await sample(page);
      await page.locator('#next-page-btn').click();
      await page.waitForSelector('.page-turn-overlay');
      assert(await page.locator('.page-turn-leaf').evaluate((leaf) => leaf.getAnimations().length > 0));
      await page.waitForSelector('.page-turn-overlay', { state: 'detached' });
      assert.match(await page.locator('#reader-page-subtitle').innerText(), /Page 2/);
      await page.keyboard.press('ArrowLeft'); await page.waitForSelector('.page-turn-overlay', { state: 'detached' });
      assert.match(await page.locator('#reader-page-subtitle').innerText(), /Page 1/);
      await page.evaluate(() => {
        const stage = document.getElementById('reading-stage');
        const touch = (x) => new Touch({ identifier: 1, target: stage, clientX: x, clientY: 400 });
        stage.dispatchEvent(new TouchEvent('touchstart', { bubbles: true, touches: [touch(850)], changedTouches: [touch(850)] }));
        stage.dispatchEvent(new TouchEvent('touchmove', { bubbles: true, cancelable: true, touches: [touch(570)], changedTouches: [touch(570)] }));
      });
      assert.match(await page.locator('.page-turn-leaf').evaluate((leaf) => leaf.style.transform), /rotateY/);
      await page.screenshot({ path: path.join(screenshotDir, 'page-swipe-3d.png') });
      await page.evaluate(() => {
        const stage = document.getElementById('reading-stage'), touch = new Touch({ identifier: 1, target: stage, clientX: 570, clientY: 400 });
        stage.dispatchEvent(new TouchEvent('touchend', { bubbles: true, touches: [], changedTouches: [touch] }));
      });
      await page.waitForSelector('.page-turn-overlay', { state: 'detached' });
      assert.match(await page.locator('#reader-page-subtitle').innerText(), /Page 2/);
      for (const effect of ['slide', 'fade', 'instant', 'rotate']) {
        if (effect === 'slide') {
          await page.evaluate(() => {
            const stage = document.getElementById('reading-stage');
            const touch = (x) => new Touch({ identifier: 2, target: stage, clientX: x, clientY: 400 });
            stage.dispatchEvent(new TouchEvent('touchstart', { bubbles: true, touches: [touch(750)], changedTouches: [touch(750)] }));
            stage.dispatchEvent(new TouchEvent('touchmove', { bubbles: true, cancelable: true, touches: [touch(715)], changedTouches: [touch(715)] }));
          });
          await page.waitForTimeout(220);
          await page.evaluate(() => {
            const stage = document.getElementById('reading-stage'), touch = new Touch({ identifier: 2, target: stage, clientX: 715, clientY: 400 });
            stage.dispatchEvent(new TouchEvent('touchend', { bubbles: true, touches: [], changedTouches: [touch] }));
          });
          await page.waitForSelector('.page-turn-overlay', { state: 'detached' });
          assert.match(await page.locator('#reader-page-subtitle').innerText(), /Page 2/);
        }
        await page.locator('#reader-settings-btn').click(); await page.locator('#page-turn-effect-select').selectOption(effect); await page.locator('#done-settings-btn').click();
        await page.locator('#prev-page-btn').click();
        if (effect !== 'instant') await page.waitForSelector('.page-turn-overlay', { state: 'detached' });
        assert.match(await page.locator('#reader-page-subtitle').innerText(), /Page 1/);
        await page.locator('#next-page-btn').click(); await page.waitForTimeout(480);
        assert.match(await page.locator('#reader-page-subtitle').innerText(), /Page 2/);
      }
      assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('eyesafe_prefs_v2')).pageTurnEffect), 'rotate');
    } finally { await ctx.close(); }
  });
  await test('reduced-motion preference bypasses the 3D animation', async () => {
    const { ctx, page } = await context();
    try {
      await page.emulateMedia({ reducedMotion: 'reduce' }); await sample(page);
      await page.evaluate(() => {
        window.__flipCount = 0;
        new MutationObserver((records) => records.forEach((record) => record.addedNodes.forEach((node) => { if (node.classList?.contains('page-turn-overlay')) window.__flipCount++; })))
          .observe(document.getElementById('reader-view'), { childList: true });
      });
      await page.locator('#next-page-btn').click(); await page.waitForTimeout(100);
      assert.match(await page.locator('#reader-page-subtitle').innerText(), /Page 2/);
      assert.equal(await page.evaluate(() => window.__flipCount), 0);
    } finally { await ctx.close(); }
  });
  await test('PDF strips and outgoing canvas snapshots retain rendered pixels', async () => {
    const { ctx, page } = await context();
    try {
      await page.waitForFunction(() => !!window.pdfjsLib);
      await page.locator('#pdf-file-input').setInputFiles({ name: 'Selection fixture.pdf', mimeType: 'application/pdf', buffer: pdfFixture() });
      await page.locator('#pdf-text-overlay .reader-token').first().waitFor({ state: 'visible' });
      await select(page, 1, 17, '#pdf-text-overlay'); await assertSelection(page);
      await page.screenshot({ path: path.join(screenshotDir, 'pdf-selection.png') });
      await page.locator('#sel-close-btn').click(); await page.locator('#next-page-btn').click();
      await page.waitForSelector('.page-turn-leaf canvas');
      assert(await page.locator('.page-turn-leaf canvas').evaluate((canvas) => canvas.width > 0 && canvas.height > 0 && canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data.some((value, index) => index % 4 !== 3 && value < 100)));
      await page.waitForSelector('.page-turn-overlay', { state: 'detached' });
      assert.match(await page.locator('#reader-page-subtitle').innerText(), /Page 2/);
    } finally { await ctx.close(); }
  });
  await test('local OpenAI-compatible audio plays, prefetches, and reuses cached clips', async () => {
    const { ctx, page } = await context();
    try {
      await sample(page); await voice(page);
      const before = requests.length; await page.locator('#tts-btn').click();
      await page.waitForSelector('.reader-highlight-band[data-kind="tts"]');
      assert(requests.length >= before + 3);
      const first = requests[before]; assert(first.body.input && first.body.voice === 'alloy'); assert.equal(first.headers.authorization, 'Bearer fixture-key');
      await page.locator('#tts-bar-play-btn').click(); const pausedRequests = requests.length;
      await page.waitForTimeout(200); assert.equal(requests.length, pausedRequests);
      await page.locator('#tts-bar-play-btn').click(); await page.waitForTimeout(80);
      assert.equal(requests.filter((request) => request.body.input === first.body.input).length, 1);
      await page.locator('#tts-stop-bar-btn').click(); await page.locator('#tts-btn').click();
      await page.waitForSelector('.reader-highlight-band[data-kind="tts"]');
      assert.equal(requests.filter((request) => request.body.input === first.body.input).length, 1);
      await page.screenshot({ path: path.join(screenshotDir, 'ai-tts-player.png') });
    } finally { await ctx.close(); }
  });
  await test('custom JSON/base64 and ElevenLabs headers work through local HTTP', async () => {
    const { ctx, page } = await context();
    try {
      await sample(page); await voice(page, { engine: 'custom', endpoint: `${service}/custom`, name: 'Custom base64', voice: 'fixture-voice' });
      await page.locator('#tts-btn').click(); await page.waitForSelector('.reader-highlight-band[data-kind="tts"]');
      const custom = requests.findLast((request) => request.path === '/custom');
      assert(custom.body.utterance.startsWith('Read ')); assert.equal(custom.body.speed, 1); assert.equal(custom.headers['x-voice-test'], 'fixture-header');
      await page.locator('#tts-stop-bar-btn').click();
      await voice(page, { engine: 'elevenlabs', endpoint: `${service}/eleven/{voice_id}`, name: 'ElevenLabs fixture', voice: 'voice-123' });
      await page.locator('#tts-btn').click(); await page.waitForSelector('.reader-highlight-band[data-kind="tts"]');
      const eleven = requests.findLast((request) => request.path === '/eleven/voice-123');
      assert.equal(eleven.headers['xi-api-key'], 'fixture-key'); assert.equal(eleven.body.model_id, 'fixture-model');
    } finally { await ctx.close(); }
  });
  await test('system voice fallback and automatic spoken page advancement', async () => {
    const { ctx, page } = await context();
    try {
      await sample(page); await page.locator('#tts-btn').click();
      await page.waitForFunction(() => window.__speechEvents.length > 0);
      await page.locator('#tts-stop-bar-btn').click();
      await voice(page, { endpoint: `${service}/fail`, name: 'Unavailable fixture' });
      const previous = await page.evaluate(() => window.__speechEvents.length);
      await page.locator('#tts-btn').click(); await page.waitForFunction((count) => window.__speechEvents.length > count, previous);
      assert.match(await page.locator('#toast').innerText(), /offline system voice/);
      await page.locator('#tts-stop-bar-btn').click();
      await voice(page, { endpoint: `${service}/v1/audio/speech?fast=1`, name: 'Fast page voice' });
      await page.locator('#tts-btn').click();
      await page.waitForFunction(() => document.getElementById('reader-page-subtitle').textContent.includes('Page 2'), null, { timeout: 12000 });
      await page.locator('#tts-stop-bar-btn').click();
    } finally { await ctx.close(); }
  });
  await test('offline fallback, selected-passage listening, and backup key exclusion', async () => {
    const { ctx, page } = await context();
    try {
      await sample(page); await voice(page, { name: 'Offline fallback fixture' });
      await ctx.setOffline(true); await select(page, 0, 5); await page.locator('#sel-listen-btn').click();
      await page.waitForFunction(() => window.__speechEvents.length > 0);
      assert.match(await page.locator('#toast').innerText(), /offline/);
      await page.waitForSelector('#tts-player-bar', { state: 'hidden' });
      assert.match(await page.locator('#reader-page-subtitle').innerText(), /Page 1/);
      await ctx.setOffline(false);
      await page.locator('#back-to-lib-btn').click(); await page.locator('#lib-backup-btn').click();
      const downloadPromise = page.waitForEvent('download'); await page.locator('#export-backup-btn').click();
      const download = await downloadPromise;
      const backup = JSON.parse(await fs.readFile(await download.path(), 'utf8'));
      assert(backup.prefs.ttsProfiles.every((profile) => !profile.apiKey && !profile.headerValue));
    } finally { await ctx.close(); }
  });
  await test('library controls, goal, backup dialogs and AI pending-message guard', async () => {
    const { ctx, page } = await context();
    try {
      await page.locator('#lib-deck-btn').click(); assert(await page.locator('#deck-modal').isVisible()); await page.locator('#close-deck-btn').click();
      await page.locator('#lib-backup-btn').click(); assert(await page.locator('#backup-modal').isVisible()); await page.locator('#close-backup-btn').click();
      await page.locator('#view-grid-btn').click(); await page.locator('[data-cat="study"]').click();
      const goal = await page.locator('#goal-cycle-btn').innerText(); await page.locator('#goal-cycle-btn').click(); assert.notEqual(await page.locator('#goal-cycle-btn').innerText(), goal);
      await sample(page); await page.locator('#reader-settings-btn').click(); await page.locator('#open-ai-manager-btn').click();
      await page.locator('[data-preset="openai"]').click(); await page.locator('#ai-endpoint-input').fill(`${service}/chat`);
      await page.locator('#ai-model-input').fill('fixture-model'); await page.locator('#save-ai-profile-btn').click(); await page.locator('#done-ai-config-btn').click();
      await page.locator('#ai-chat-trigger-btn').click(); await page.locator('#ai-chat-input').fill('Explain this passage');
      await page.locator('#ai-send-btn').click(); assert(await page.locator('#ai-send-btn').isDisabled());
      assert.match(await page.locator('#ai-chat-messages').innerText(), /Explain this passage/);
      assert.match(await page.locator('#ai-chat-messages').innerText(), /Thinking/);
      await page.locator('#ai-chat-form').evaluate((form) => form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })));
      await page.waitForFunction(() => !document.getElementById('ai-send-btn').disabled);
      assert.equal(requests.filter((request) => request.path === '/chat').length, 1);
      assert.match(await page.locator('#ai-chat-messages').innerText(), /A clear answer/);
    } finally { await ctx.close(); }
  });
  assert.deepEqual(errors, []);
  console.log(`Browser verification complete. Screenshots: ${screenshotDir}`);
}

main().catch((error) => { console.error(error); process.exitCode = 1; }).finally(async () => {
  if (browser) await browser.close();
  if (server) await new Promise((resolve) => server.close(resolve));
});
