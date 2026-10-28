import fs from 'node:fs/promises'
import http from 'node:http'
import assert from 'node:assert/strict'
import { pathToFileURL } from 'node:url'
// 在仓库根运行。浏览器工具由调用方提供，不引入产品或仓库依赖。
const { chromium } = process.env.PLAYWRIGHT_MODULE
  ? await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE))
  : await import('playwright-core')
const file=await fs.readFile(process.argv[2] || 'build/playlist-research/preview.html')
const server=http.createServer((req,res)=>{res.setHeader('Content-Type','text/html; charset=utf-8');res.end(file)})
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
const browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE
  ? {executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE} : {})})
const checks=[],errors=[],apiRequests=[]
try{
  const page=await browser.newPage({viewport:{width:736,height:1100}})
  page.on('pageerror',error=>errors.push(error.message))
  page.on('request',request=>{if(/music\.163\.com/.test(request.url()))apiRequests.push(request.url())})
  await page.goto(`http://127.0.0.1:${server.address().port}/`,{waitUntil:'load'})
  const frame=page.frameLocator('iframe'),demo=frame.locator('#ncrust-playlist-demo'),ctx=frame.locator('[data-policy=context]')
  await frame.locator('#ncrust-playlist-demo[data-ready=true]').waitFor()
  await demo.evaluate(()=>document.fonts.ready)
  await ctx.locator('[data-song=B]').click()
  const queue=()=>ctx.locator('.np-queue li').allTextContents()
  const current=()=>ctx.locator('.np-queue [aria-current=true]').textContent()
  assert.deepEqual(await queue(),['A','B','C']);assert.equal(await current(),'B');checks.push('playlist click preserves full order and starts at B')
  await ctx.locator('[data-demo=end]').click();assert.equal(await current(),'C')
  await ctx.locator('[data-demo=end]').click();assert.equal(await current(),'A');checks.push('cycle wraps C to A')
  await ctx.locator('[data-mode]').selectOption('single');await ctx.locator('[data-song=B]').click()
  await ctx.locator('[data-demo=end]').click();assert.equal(await current(),'B')
  await ctx.locator('[data-action=next]').click();assert.equal(await current(),'C');checks.push('single repeats naturally but manual next advances')
  await ctx.locator('[data-mode]').selectOption('line');await ctx.locator('[data-song=C]').click()
  await ctx.locator('[data-demo=end]').click();assert.equal(await current(),'C');assert.match(await ctx.locator('.np-footer').textContent(),/已停止/);checks.push('linear ending keeps real final song')
  await ctx.locator('[data-mode]').selectOption('shuffle');await ctx.locator('[data-song=A]').click()
  const heard=[await current()];for(let i=0;i<2;i++){await ctx.locator('[data-demo=end]').click();heard.push(await current())}
  assert.equal(new Set(heard).size,3);const last=heard.at(-1);await ctx.locator('[data-demo=end]').click();assert.notEqual(await current(),last);checks.push('shuffle round has no duplicates and avoids immediate repeat')
  await ctx.locator('[data-mode]').selectOption('infinity');await ctx.locator('[data-song=C]').click();await ctx.locator('[data-demo=end]').click()
  assert.equal(await current(),'C');assert.match(await ctx.locator('.np-status').textContent(),/等待相似歌曲/);checks.push('infinite mode does not fake local wraparound')
  await ctx.locator('[data-mode]').selectOption('cycle');await ctx.locator('[data-song=B]').click();await ctx.locator('[data-menu=B]').click();await ctx.locator('[data-action=picker]').click();await ctx.locator('[data-list="通勤"]').click();await ctx.locator('[data-action=submit]').click()
  assert.match(await ctx.locator('.np-status').textContent(),/已加入/);checks.push('song picker confirms add locally')
  await ctx.locator('[data-menu=B]').click();await ctx.locator('[data-action=picker]').click();await ctx.locator('[data-list="通勤"]').click();await ctx.locator('[data-action=submit]').click();assert.match(await ctx.locator('.np-status').textContent(),/已在/);checks.push('duplicate add does not duplicate the song')
  await ctx.locator('[data-demo=outcome]').selectOption('pending');await ctx.locator('[data-menu=B]').click();await ctx.locator('[data-action=picker]').click();await ctx.locator('[data-list="通勤"]').click();await ctx.locator('[data-action=submit]').click();assert.match(await ctx.locator('.np-status').textContent(),/待确认/);checks.push('readback failure stays unconfirmed')
  await ctx.locator('[data-demo=outcome]').selectOption('limited');await ctx.locator('[data-action=submit]').click();assert.match(await ctx.locator('.np-status').textContent(),/过于频繁/);checks.push('rate limit preserves pending interaction')
  await ctx.locator('[data-action=back]').click();await ctx.locator('[data-demo=outcome]').selectOption('ok');const before=await queue();const beforeCurrent=await current();await ctx.locator('[data-menu=B]').click();await ctx.locator('[data-action=remove]').click();assert.deepEqual(await queue(),before);assert.equal(await current(),beforeCurrent);assert.equal(await ctx.locator('[data-song=B]').count(),0);checks.push('playlist removal leaves active playback snapshot unchanged')
  await ctx.locator('[data-menu=A]').click();await ctx.locator('[data-action=picker]').click();await ctx.locator('[data-action=create]').click();await ctx.getByLabel('歌单名称').fill('收藏示意');await ctx.locator('[data-action=submit]').click();assert.match(await ctx.locator('.np-status').textContent(),/已加入/);assert.deepEqual(await queue(),before);checks.push('create then add preserves existing playback queue')
  await ctx.locator('[data-action=library]').click();await ctx.locator('[data-action=create]').click();await ctx.getByLabel('歌单名称').fill('新歌单');await ctx.getByLabel('歌单隐私').selectOption({label:'公开'});await ctx.locator('[data-action=submit]').click();assert.match(await ctx.locator('h2').textContent(),/新歌单/);assert.match(await ctx.locator('.np-content p').textContent(),/公开 · 0 首/);assert.equal(await ctx.locator('[data-action=playall]').isDisabled(),true);checks.push('standalone creation keeps the new playlist empty and preserves privacy')
  await ctx.locator('[data-action=library]').click();await ctx.locator('[data-list="夜行"]').click()
  for(const width of [320,360,736]){
    await page.setViewportSize({width,height:1200})
    for(const theme of ['light','dark']){
      await page.emulateMedia({colorScheme:theme})
      const overflow=await demo.evaluate(el=>el.scrollWidth>el.clientWidth+1)
      assert.equal(overflow,false,`${width} ${theme} overflow`)
      await demo.screenshot({path:`build/playlist-research/proposal-${width}-${theme}.png`})
    }
  }
  checks.push('320/360/736px layouts fit in both themes')
  const insert=frame.locator('[data-policy=insert]')
  await ctx.evaluate(el=>el.hidden=true);await insert.evaluate(el=>el.hidden=false)
  await insert.locator('[data-song=B]').click();assert.deepEqual(await insert.locator('.np-queue li').allTextContents(),['X','B','Y']);await insert.locator('[data-action=next]').click();assert.equal(await insert.locator('.np-queue [aria-current=true]').textContent(),'Y');checks.push('alternative click inserts B into X,Y without replacing it')
  await insert.locator('[data-action=playall]').click();assert.deepEqual(await insert.locator('.np-queue li').allTextContents(),['A','B','C']);checks.push('explicit play playlist replaces queue in both variants')
  assert.deepEqual(errors,[]);assert.deepEqual(apiRequests,[])
  const result={checksPassed:checks.length,checks,pageErrors:errors,accountApiRequests:apiRequests.length}
  await fs.writeFile('build/playlist-research/interaction-check.json',JSON.stringify(result,null,2)+'\n')
  console.log(JSON.stringify(result,null,2))
}catch(error){console.error(JSON.stringify({pageErrors:errors,accountApiRequests:apiRequests}));throw error}
finally{await browser.close();server.close()}
