// Диагностика публичного поиска. Не передаёт ключи или данные приложения.
const { chromium } = require('playwright');
const fs = require('node:fs/promises');
const CODE = '4601985011102';
const URL = 'https://countrychecker.com/ru/?q=' + CODE;
const OUT = 'countrychecker-report';
async function main() {
  await fs.mkdir(OUT, { recursive: true });
  const browser = await chromium.launch({headless: true});
  const page = await browser.newPage({locale:'ru-RU', viewport:{width:1280,height:1000}});
  const network = [], pending = [], errors = [];
  page.on('response', response => {
    const request = response.request();
    if (!['fetch','xhr'].includes(request.resourceType())) return;
    const url = new globalThis.URL(response.url());
    const row = {method:request.method(), origin:url.origin, path:url.pathname,
      queryKeys:[...url.searchParams.keys()], barcodeInQuery:[...url.searchParams.values()].includes(CODE),
      status:response.status(), contentType:response.headers()['content-type'] || ''};
    network.push(row);
    pending.push((async () => {
      if (!row.contentType.includes('json')) return;
      try {
        const json=await response.json();
        row.jsonKeys=Object.keys(json).slice(0,60);
        // Сохраняем только факт совпадения, а не токены или полные ответы.
        row.expectedProductMentioned=/на паях|перу|peru/i.test(JSON.stringify(json));
      } catch (_) {}
    })());
  });
  page.on('pageerror', error => errors.push(error.name));
  let navigationStatus=null, submitted=false, before='', after='';
  try {
    const response=await page.goto(URL,{waitUntil:'domcontentloaded',timeout:35000});
    navigationStatus=response?.status() ?? null;
    await page.waitForTimeout(8000);
    before=await page.locator('body').innerText({timeout:10000});
    const field=page.locator('input').filter({visible:true}).first();
    const button=page.getByRole('button',{name:/сканировать штрих-код|scan barcode/i}).first();
    if (navigationStatus===200 && !/на паях|перу/i.test(before) && network.length===0 &&
        await field.count() && await button.count()) {
      await field.fill(CODE);
      await button.click({timeout:5000});
      submitted=true;
      await page.waitForTimeout(12000);
    }
    after=await page.locator('body').innerText({timeout:10000});
    await page.screenshot({path:OUT+'/page.png',fullPage:true,timeout:15000});
  } catch(error) {
    errors.push(error.name);
  } finally {
    await Promise.race([Promise.allSettled(pending),new Promise(resolve=>setTimeout(resolve,5000))]);
    await fs.writeFile(OUT+'/report.json',JSON.stringify({url:URL,barcode:CODE,navigationStatus,submitted,
      expectedProductMentioned:/на паях|перу/i.test(after),network,errors},null,2));
    await fs.writeFile(OUT+'/visible-text.txt',after||before);
    await browser.close();
  }
  console.log(JSON.stringify({navigationStatus,submitted,network,errors},null,2));
}
main().catch(error=>{console.error(error.name);process.exitCode=1;});
