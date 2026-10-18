const {chromium} = require('playwright');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const path = require('node:path');

(async () => {
  const browser = await chromium.launch({
    executablePath: process.env.CHIMAHON_CHROMIUM_PATH || undefined,
    headless: true,
    args: ['--no-sandbox'],
  });
  const context = await browser.newContext({viewport: {width: 412, height: 600}, isMobile: true, hasTouch: true});
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const navigations = [];
  const cdp = await context.newCDPSession(page);
  await cdp.send('Page.enable');
  cdp.on('Page.frameRequestedNavigation', event => { if (event.url.startsWith('chima:')) navigations.push(event.url); });
  const root = path.resolve(__dirname, '../..');
  await page.setContent('<meta name="viewport" content="width=device-width, initial-scale=1"><style>' + fs.readFileSync(path.join(root, 'chimahon/src/main/assets/dictionary/base.css'), 'utf8') + '</style><main id="entries"></main>');
  await page.evaluate(() => {
    window.exports = [];
    window.AnkiBridge = {
      addToAnki(...args) { window.exports.push({action: 'add', selection: args[3]}); },
      openInAnki(...args) { window.exports.push({action: 'open', selection: args[3]}); },
    };
    window.touch = (type, x, y) => {
      const target = document.querySelector('.gloss-content');
      const touch = new Touch({identifier: 1, target, clientX: x, clientY: y});
      const event = new TouchEvent(type, {bubbles: true, cancelable: true,
        touches: type === 'touchend' || type === 'touchcancel' ? [] : [touch], changedTouches: [touch]});
      target.dispatchEvent(event);
      return event.defaultPrevented;
    };
  });
  await page.addScriptTag({content: fs.readFileSync(path.join(root, 'chimahon/src/main/assets/dictionary/renderer.js'), 'utf8')});
  async function fixture(paginated = false, recursive = false) {
    await page.evaluate(({paginated, recursive}) => {
      getSelection().removeAllRanges();
      window.exports = [];
      document.documentElement.dataset.chimaPaginatedScrolling = String(paginated);
      DictionaryRenderer.render({results: [{index: 0, matched: 'complete', process: [], term: {
        expression: 'complete', reading: '', glossaries: [{dictName: 'Test Dictionary', glossary: 'Selectable definition for precise Anki export.', definitionTags: '', termTags: ''}], frequencies: [], pitches: []
      }}], styles: [], existingExpressions: ['complete'], ankiEnabled: true, ankiDupAction: 'allow', wordAudioEnabled: false, dictionaryCollapseMode: 'expand_all', showNavigationButtons: false});
      const filler = document.createElement('div');
      filler.style.height = '1600px';
      document.getElementById('entries').appendChild(filler);
      DictionaryRenderer.setRecursiveLookupEnabled(recursive);
      window.scrollTo(0, 0);
    }, {paginated, recursive});
    navigations.length = 0;
  }
  async function selectByMouse(selector = '.gloss-content') {
    const points = await page.locator(selector).first().evaluate(el => {
      const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
      const text = walker.nextNode();
      const point = offset => {
        const range = document.createRange();
        range.setStart(text, offset); range.setEnd(text, offset + 1);
        const r = range.getBoundingClientRect();
        return {x: r.left + 0.2, y: r.top + r.height / 2};
      };
      return [point(0), point('Selectable definition'.length)];
    });
    await page.mouse.move(points[0].x, points[0].y);
    await page.mouse.down();
    await page.mouse.move(points[1].x, points[1].y, {steps: 12});
    await page.mouse.up();
    await page.waitForTimeout(25);
    assert.equal(await page.evaluate(() => getSelection().toString()), 'Selectable definition');
  }
  const failures = [];
  async function test(name, run) {
    try { await run(); console.log('PASS:', name); }
    catch (e) { failures.push(name); console.error('FAIL:', name, e.message); }
  }
  try {
    await test('native browser selection reaches Add to Anki as only the selected substring', async () => {
      await fixture(); await selectByMouse();
      await page.locator('.anki-add-btn').click();
      assert.deepEqual(await page.evaluate(() => window.exports), [{action: 'add', selection: 'Selectable definition'}]);
    });
    await test('the Open in Anki button preserves the real text selection', async () => {
      await fixture(); await selectByMouse();
      await page.locator('.anki-open-btn').click();
      assert.deepEqual(await page.evaluate(() => window.exports), [{action: 'open', selection: 'Selectable definition'}]);
    });
    await test('AI explanation text can be selected by dragging and exported as a substring', async () => {
      await fixture(false, true);
      await page.evaluate(() => DictionaryRenderer.updateAiExplanation({key: 'selection', text: 'Selectable definition from the AI explanation.', loading: false, initiallyCollapsed: false}));
      await selectByMouse('.ai-explanation-text');
      await page.locator('.anki-add-btn').click();
      assert.deepEqual(await page.evaluate(() => window.exports), [{action: 'add', selection: 'Selectable definition'}]);
      assert.equal(navigations.length, 0);
      await page.evaluate(() => DictionaryRenderer.updateAiExplanation(null));
    });
    await test('selected text in a kanji-tappable span does not start a recursive lookup', async () => {
      await fixture(false, true);
      await page.locator('.gloss-content').evaluate(el => {
        const span = document.createElement('span');
        span.className = 'kanji-tappable';
        span.textContent = el.textContent;
        el.replaceChildren(span);
      });
      await selectByMouse('.kanji-tappable');
      assert.equal(navigations.length, 0);
    });
    await test('an installed recursive listener respects disabling recursive lookup', async () => {
      await fixture(false, true);
      await page.evaluate(() => DictionaryRenderer.setRecursiveLookupEnabled(false));
      await page.locator('.gloss-content').click({position: {x: 40, y: 8}});
      await page.waitForTimeout(50);
      assert.equal(navigations.length, 0);
    });
    await test('long touch does not become a recursive lookup on release', async () => {
      await fixture(false, true);
      const r = await page.locator('.gloss-content').boundingBox();
      await cdp.send('Input.dispatchTouchEvent', {type: 'touchStart', touchPoints: [{x: r.x + 35, y: r.y + 8}]});
      await page.waitForTimeout(700);
      await cdp.send('Input.dispatchTouchEvent', {type: 'touchEnd', touchPoints: []});
      await page.waitForTimeout(100);
      assert.equal(navigations.length, 0);
    });
    await test('paginated scrolling leaves selection handle gestures untouched', async () => {
      await fixture(true); await selectByMouse();
      const prevented = await page.evaluate(() => {
        touch('touchstart', 70, 90);
        const prevented = touch('touchmove', 70, 210);
        touch('touchend', 70, 210);
        return prevented;
      });
      assert.equal(prevented, false);
      assert.equal(await page.evaluate(() => window.scrollY), 0);
      assert.equal(await page.evaluate(() => getSelection().toString()), 'Selectable definition');
    });
    await test('finger jitter and long presses remain available for native selection in paginated mode', async () => {
      await fixture(true);
      await page.evaluate(() => touch('touchstart', 70, 90));
      assert.equal(await page.evaluate(() => touch('touchmove', 72, 92)), false);
      await page.waitForTimeout(550);
      assert.equal(await page.evaluate(() => touch('touchmove', 70, 160)), false);
      await page.evaluate(() => touch('touchend', 70, 160));
      assert.equal(await page.evaluate(() => window.scrollY), 0);
    });
    await test('short vertical swipes still scroll by one page', async () => {
      await fixture(true);
      const prevented = await page.evaluate(() => {
        touch('touchstart', 70, 200);
        const prevented = touch('touchmove', 70, 100);
        touch('touchend', 70, 100);
        return prevented;
      });
      assert.equal(prevented, true);
      assert.equal(await page.evaluate(() => window.scrollY), 540);
    });
    await test('slow swipes remain paging gestures after movement has started', async () => {
      await fixture(true);
      await page.evaluate(() => { touch('touchstart', 70, 200); touch('touchmove', 70, 150); });
      await page.waitForTimeout(550);
      await page.evaluate(() => { touch('touchmove', 70, 100); touch('touchend', 70, 100); });
      assert.equal(await page.evaluate(() => window.scrollY), 540);
    });
    await test('canceled touches cannot scroll or navigate', async () => {
      await fixture(true, true);
      await page.evaluate(() => { touch('touchstart', 70, 200); touch('touchmove', 70, 100); touch('touchcancel', 70, 100); touch('touchend', 70, 100); });
      assert.equal(await page.evaluate(() => window.scrollY), 0);
    });
    await test('normal short taps still perform recursive lookup', async () => {
      await fixture(false, true);
      await page.waitForTimeout(650);
      await page.locator('.gloss-content').click({position: {x: 40, y: 8}});
      await page.waitForTimeout(50);
      assert.equal(navigations.length, 1);
    });
    assert.deepEqual(errors, []);
    assert.deepEqual(failures, []);
  } finally { await browser.close(); }
})().catch(error => {console.error(error);process.exit(1);});
