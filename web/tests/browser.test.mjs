import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import { once } from 'node:events';
import { readFile, mkdir } from 'node:fs/promises';
import { createServer } from '../../relay/server.mjs';
let browser;
const key='fixture-key-only-not-a-real-account';
before(async()=>{browser=await chromium.launch({headless:true,executablePath:process.env.DROP_CHROME || undefined,args:['--no-sandbox']});});
after(async()=>{await browser?.close();});
const base=(id,extra={})=>({id,name:`Collection ${id}`,created_at:'2026-09-01T12:30:00Z',cached_at:'2026-08-25T09:00:00Z',size:4096,download_finished:true,download_present:true,allow_zipped:true,tags:[],files:[{id:1,name:'Pack/Season 1/Episode 2.mp4',size:16,mimetype:'video/mp4'},{id:2,name:'Pack/Season 1/Episode 10.mp4',size:16},{id:3,name:'Pack/readme.txt',size:16}],...extra});
async function fixture(t,options={}) {
 const requests=[], state={items:[base(1),base(2,{name:'Older item',created_at:'2026-07-04T10:00:00Z'}),base(3,{name:'Currently downloading',download_finished:false,download_present:false,progress:.23})],queue:[{id:5,name:'Waiting torrent',created_at:'2026-09-20T12:00:00Z'}],...options};
 const ok=d=>new Response(JSON.stringify({success:true,data:d}),{headers:{'Content-Type':'application/json'}});
 const server=createServer({local:true,fetchFn:async(u,o)=>{
  requests.push({url:u.href,method:o.method,body:o.body?.toString(),headers:{...o.headers}});
  if(state.override){const r=await state.override(u,o);if(r)return r;}
  const path=u.pathname;
  if(path.endsWith('/user/me'))return ok({id:1,email:'Test account'});
  if(path.endsWith('/mylist')){if(path.includes('/webdl/'))return ok([]);if(u.searchParams.has('id'))return ok(state.items.filter(r=>r.id===Number(u.searchParams.get('id'))));const offset=Number(u.searchParams.get('offset')||0),limit=Number(u.searchParams.get('limit')||1000);return ok(state.items.slice(offset,offset+limit));}
  if(path.endsWith('/getqueued'))return ok(u.searchParams.get('type')==='torrent'?state.queue:[]);
  if(path.endsWith('/requestdl'))return ok(state.link || 'https://cdn.example.test/file');
  if(path.includes('/control')){const data=JSON.parse(o.body);if(state.rejectDelete&&data.operation==='delete')return new Response(JSON.stringify({success:false,detail:'Denied'}),{status:400}); if(data.operation==='delete')state.items=state.items.filter(x=>x.id!==data.torrent_id);return ok(null);}
  if(path.includes('/edit')){const data=JSON.parse(o.body);state.items=state.items.map(i=>i.id===data.torrent_id?{...i,...data}:i);return ok(null);}
  if(path.includes('/create'))return ok({torrent_id:10});
  throw new Error('Unhandled fixture route '+path);
 }});
 server.listen(0,'127.0.0.1');await once(server,'listening');
 const context=await browser.newContext({locale:'en-US',timezoneId:'UTC',viewport:options.viewport||{width:1440,height:960},acceptDownloads:true});
 const page=await context.newPage(), errors=[];page.setDefaultTimeout(10000);page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{window.showSaveFilePicker=undefined;});
 await context.route('https://cdn.example.test/**',route=>route.fulfill({status:200,contentType:'application/octet-stream',headers:{'Content-Disposition':'attachment; filename=test-file.txt','Access-Control-Allow-Origin':'*'},body:'fixture-download'}));
 const url=`http://127.0.0.1:${server.address().port}`;await page.goto(url);
 t.after(async()=>{await context.close();server.closeAllConnections();server.close();assert.deepEqual(errors,[]);});
 return {page,state,requests,url,context,login:async()=>{await page.locator('#api-key').fill(key);await page.locator('#connect').click();await page.locator('#app').waitFor({state:'visible'});await page.waitForFunction(()=>!document.querySelector('#refresh').disabled);}};
}
function nextDownload(context) {
 return new Promise((resolve,reject)=>{
  const watched=new Set(),timer=setTimeout(()=>{cleanup();reject(Error('No browser download arrived.'));},10000);
  const done=download=>{cleanup();resolve(download);};
  const watch=page=>{watched.add(page);page.on('download',done);};
  const cleanup=()=>{clearTimeout(timer);context.off('page',watch);for(const page of watched)page.off('download',done);};
  context.pages().forEach(watch);context.on('page',watch);
 });
}
async function browse(page){await page.getByRole('button',{name:'Collection 1',exact:true}).click();await page.getByRole('button',{name:'Pack',exact:true}).click();await page.getByRole('button',{name:'Season 1',exact:true}).click();}
test('login gates all data; password not persisted; sign out and reload erase account',async t=>{const f=await fixture(t);assert.equal(f.requests.length,0);await f.login();assert.equal(await f.page.locator('#api-key').inputValue(),'');assert.equal(await f.page.evaluate(()=>localStorage.length+sessionStorage.length),0);assert.ok(!await f.page.content().then(h=>h.includes(key)));await f.page.getByRole('button',{name:'Sign out',exact:true}).click();assert.equal(await f.page.locator('#rows tr').count(),0);await f.page.reload();assert.equal(await f.page.locator('#login').isVisible(),true);});
test('desktop explorer shows actual added/cache dates, natural sorting, folders and search',async t=>{const f=await fixture(t);await f.login();assert.ok((await f.page.locator('#rows').innerText()).includes('Sep 1, 2026'));await mkdir('web/verification',{recursive:true});await f.page.screenshot({path:'web/verification/desktop-library.png',fullPage:true});await f.page.getByRole('button',{name:'Cached',exact:true}).click();assert.equal(await f.page.locator('#sort').inputValue(),'cachedAt:asc');await f.page.getByRole('button',{name:'Cached ↑',exact:true}).click();assert.equal(await f.page.locator('#sort').inputValue(),'cachedAt:desc');await f.page.locator('#sort').selectOption('added:asc');assert.match(await f.page.locator('#rows tr').first().innerText(),/Older item/);await browse(f.page);assert.equal(await f.page.locator('#rows .name-button').first().innerText(),'Episode 2.mp4');await f.page.locator('#search').fill('Episode 10');assert.equal(await f.page.locator('#rows tr').count(),1);await f.page.locator('#search').fill('');await f.page.keyboard.press('Tab');await f.page.locator('#view-title').click();await f.page.keyboard.press('Backspace');assert.equal(await f.page.getByRole('button',{name:'Season 1',exact:true}).count(),1);await mkdir('web/verification',{recursive:true});await f.page.screenshot({path:'web/verification/desktop.png',fullPage:true});});
test('mobile and tablet retain aligned date columns with contained horizontal scrolling',async t=>{
 const f=await fixture(t,{viewport:{width:390,height:844}});await f.login();
 assert.equal(await f.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
 assert.deepEqual(await f.page.locator('#table-head th').allTextContents(),['Name','Added ↓','Cached','Size','Status','Actions']);
 const first=f.page.locator('#rows tr').first();
 assert.doesNotMatch(await first.locator('.name-cell').innerText(),/Sep 1|Aug 25|ago/);
 assert.match(await first.locator('.date-column').first().innerText(),/Sep 1, 2026/);
 const name=await first.locator('.name-cell').boundingBox();
 const added=await first.locator('.date-column').first().boundingBox();
 assert.ok(added.x>=name.x+name.width-1 && added.x+added.width<=378);
 const checkAlignment=async()=>{
  for(let n=0;n<2;n++){
   const head=await f.page.locator('#table-head .date-column').nth(n).boundingBox();
   const cell=await first.locator('.date-column').nth(n).boundingBox();
   assert.ok(Math.abs(head.x-cell.x)<1 && Math.abs(head.width-cell.width)<1);
  }
 };
 await checkAlignment();await f.page.screenshot({path:'web/verification/mobile-library.png',fullPage:true});
 await f.page.locator('.table-wrap').evaluate(e=>e.scrollLeft=144);
 const pinned=await first.locator('.name-cell').boundingBox();
 const cached=await first.locator('.date-column').nth(1).boundingBox();
 assert.equal(pinned.x,name.x);assert.ok(cached.x>=pinned.x+pinned.width-1 && cached.x+cached.width<=378);
 await checkAlignment();await f.page.screenshot({path:'web/verification/mobile-dates-scrolled.png',fullPage:true});
 await f.page.getByRole('button',{name:'Cached',exact:true}).click();assert.equal(await f.page.locator('#sort').inputValue(),'cachedAt:asc');
 await f.page.locator('.table-wrap').evaluate(e=>e.scrollLeft=0);
 await browse(f.page);assert.equal(await f.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
 const box=await f.page.getByRole('button',{name:'Download',exact:true}).first().boundingBox();assert.ok(box.x+box.width<=378);
 await f.page.screenshot({path:'web/verification/mobile.png',fullPage:true});
 await f.page.getByRole('button',{name:/All files/}).click();
 for(const width of [320,740,1024]){
  await f.page.setViewportSize({width,height:900});
  assert.equal(await f.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  for(const cell of await f.page.locator('#table-head .date-column').all())assert.equal(await cell.isVisible(),true);
  await checkAlignment();
 }
 await f.page.screenshot({path:'web/verification/tablet-library.png',fullPage:true});
});
test('Download produces an actual browser download with exact bytes',async t=>{const f=await fixture(t);await f.login();await browse(f.page);const downloadPromise=nextDownload(f.context);await f.page.getByRole('button',{name:'Download',exact:true}).first().click();const d=await downloadPromise;assert.equal((await readFile(await d.path())).toString(),'fixture-download');assert.match(await f.page.locator('#jobs').innerText(),/Sent to your browser/);const request=f.requests.find(x=>x.url.includes('/requestdl'));assert.ok(request.url.includes('file_id=1'));assert.equal(new URL(request.url).searchParams.get('redirect'),'false');});
test('direct save streams bytes without blob buffering and records completion',async t=>{const f=await fixture(t);await f.page.evaluate(()=>{window.__saved=[];window.showSaveFilePicker=async()=>({createWritable:async()=>({write:async chunk=>window.__saved.push(...chunk),close:async()=>{window.__closed=true;},abort:async()=>{window.__aborted=true;}})});});await f.login();await browse(f.page);await f.page.getByRole('button',{name:'Download',exact:true}).first().click();await f.page.waitForFunction(()=>window.__closed===true);assert.equal(await f.page.evaluate(()=>new TextDecoder().decode(new Uint8Array(window.__saved))),'fixture-download');assert.match(await f.page.locator('#jobs').innerText(),/Saved to your device/);});
test('failed direct save aborts partial writer and exposes working browser fallback',async t=>{const f=await fixture(t);await f.page.evaluate(()=>{window.showSaveFilePicker=async()=>({createWritable:async()=>({write:async()=>{throw Error('disk full');},close:async()=>{},abort:async()=>{window.__aborted=true;}})});});await f.login();await browse(f.page);await f.page.getByRole('button',{name:'Download',exact:true}).first().click();await f.page.getByRole('button',{name:'Browser download',exact:true}).waitFor();assert.equal(await f.page.evaluate(()=>window.__aborted),true);const d=nextDownload(f.context);await f.page.getByRole('button',{name:'Browser download',exact:true}).click();await d;});
test('credential-bearing browser links require disclosure and never go to clipboard',async t=>{const f=await fixture(t,{link:`https://storage.torbox.app/file?token=${key}`});await f.login();await browse(f.page);await f.page.getByRole('button',{name:'More',exact:true}).first().click();await f.page.getByRole('button',{name:'Copy link',exact:true}).click();await f.page.getByText('TorBox included your API key in this link.',{exact:false}).waitFor();await f.page.getByRole('button',{name:'Close dialog'}).click();await f.page.getByRole('button',{name:'Download',exact:true}).first().click();await f.page.getByRole('button',{name:'Continue with browser download'}).waitFor();assert.ok(!await f.page.content().then(x=>x.includes(key)));});
test('delete rejection remains visible and preserves the item; success removes it',async t=>{const f=await fixture(t,{rejectDelete:true});await f.login();await f.page.getByRole('button',{name:'Details',exact:true}).first().click();await f.page.getByRole('button',{name:'Delete',exact:true}).click();await f.page.getByRole('button',{name:'Delete',exact:true}).click();await f.page.locator('#dialog .notice.error').waitFor({state:'visible'});assert.equal(await f.page.locator('#rows tr').count(),3);f.state.rejectDelete=false;await f.page.getByRole('button',{name:'Delete',exact:true}).click();await f.page.waitForFunction(()=>document.querySelectorAll('#rows tr').length===2);});
test('rename preserves tags, alternative hashes and AirLock',async t=>{const f=await fixture(t,{items:[base(1,{tags:['keep'],alternative_hashes:['hash'],airlocked:true})]});await f.login();await f.page.getByRole('button',{name:'Details',exact:true}).click();await f.page.getByRole('button',{name:'Rename / tags'}).click();await f.page.getByLabel('Name',{exact:true}).fill('New name');await f.page.getByRole('button',{name:'Save',exact:true}).click();await f.page.getByRole('button',{name:'New name',exact:true}).waitFor();const body=JSON.parse(f.requests.find(r=>r.method==='PUT').body);assert.deepEqual(body.alternative_hashes,['hash']);assert.deepEqual(body.tags,['keep']);assert.equal(body.airlocked,true);});
test('add explicit magnet action submits only once and options reach TorBox',async t=>{const f=await fixture(t);await f.login();await f.page.locator('#add').click();await f.page.getByLabel('Magnet or download link').fill('magnet:?xt=urn:btih:'+'a'.repeat(40));await f.page.getByLabel('Add to queue',{exact:true}).check();await f.page.getByLabel('Only add if cached',{exact:true}).check();await f.page.getByRole('button',{name:'Add to TorBox',exact:true}).click();await f.page.locator('#dialog').waitFor({state:'hidden'});const calls=f.requests.filter(r=>r.url.includes('/createtorrent'));assert.equal(calls.length,1);assert.match(calls[0].body,/name="as_queued"\r\n\r\ntrue/);assert.match(calls[0].body,/name="add_only_if_cached"\r\n\r\ntrue/);});
test('multi-file selection prepares separate links without automatic download storms',async t=>{const f=await fixture(t);await f.login();await browse(f.page);await f.page.getByLabel('Select visible files').check();await f.page.getByRole('button',{name:'Download selected',exact:true}).click();await f.page.getByRole('link',{name:'Save file',exact:true}).first().waitFor();assert.equal(await f.page.getByRole('link',{name:'Save file',exact:true}).count(),2);assert.equal(f.requests.filter(r=>r.url.includes('/requestdl')).length,2);});
test('large account loads every API page while DOM stays bounded',async t=>{const items=Array.from({length:2500},(_,i)=>base(i,{files:[]}));const f=await fixture(t,{items});await f.login();assert.match(await f.page.locator('#list-summary').innerText(),/2,500/);assert.equal(await f.page.locator('#rows tr').count(),100);await f.page.locator('#search').fill('Collection 2499');assert.equal(await f.page.locator('#rows tr').count(),1);});
test('HTML-like filenames render as text rather than executing',async t=>{const f=await fixture(t,{items:[base(1,{name:'<img src=x onerror="window.hacked=true">'})]});await f.login();assert.equal(await f.page.locator('#rows img').count(),0);assert.equal(await f.page.evaluate(()=>window.hacked),undefined);});
test('partial service failures retain successful lists with a visible explanation',async t=>{const f=await fixture(t,{override:async u=>u.pathname.includes('/webdl/mylist')?new Response(JSON.stringify({success:false}),{status:503}):null});await f.login();assert.equal(await f.page.locator('#rows tr').count(),3);assert.match(await f.page.locator('#notice').innerText(),/Some lists could not be refreshed/);});
test('sign out during late detail response cannot repopulate private data',async t=>{let release;const f=await fixture(t,{override:async u=>u.pathname.endsWith('/mylist')&&u.searchParams.has('id')?await new Promise(r=>release=()=>r(new Response(JSON.stringify({success:true,data:[base(1)]})))):null});await f.login();await f.page.getByRole('button',{name:'Collection 1',exact:true}).click();await f.page.getByRole('button',{name:'Sign out',exact:true}).click();release?.();await new Promise(r=>setTimeout(r,100));assert.equal(await f.page.locator('#app').isVisible(),false);assert.equal(await f.page.locator('#rows tr').count(),0);});
test('expired API key returns to sign-in and clears rows',async t=>{const f=await fixture(t);await f.login();f.state.override=async()=>new Response(JSON.stringify({success:false,error:'BAD_TOKEN'}),{status:401});await f.page.locator('#refresh').click();await f.page.locator('#login').waitFor({state:'visible'});assert.equal(await f.page.locator('#rows tr').count(),0);assert.match(await f.page.locator('#login-error').innerText(),/rejected/);});

test('unconfigured publication blocks API-key entry until the relay is activated',async t=>{const f=await fixture(t);const html=(await readFile('web/index.html','utf8')).replace(/<meta name="torbox-api"[^>]*>/,'<meta name="torbox-api" content="">');await f.page.route(f.url+'/',route=>route.fulfill({contentType:'text/html',body:html}));await f.page.reload();assert.equal(await f.page.locator('#api-key').isDisabled(),true);assert.equal(await f.page.locator('#connect').isDisabled(),true);assert.match(await f.page.locator('#login-error').innerText(),/activation/);assert.equal(f.requests.length,0);});
