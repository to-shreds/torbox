const { test } = require('node:test');
const assert = require('node:assert/strict');
const C = require('../src/core.js');
const key = 'test-only-api-key-never-real';
const raw = (id, extra={}) => ({ id, name:`File ${id}`, created_at:'2026-09-01T12:30:00Z', download_finished:true, download_present:true, size:4096, ...extra });
const response = (data, status=200, extra={}) => new Response(JSON.stringify({success:true,data,...extra}), {status, headers:{'Content-Type':'application/json'}});
test('UTC dates, fractional timestamps, offsets, seconds, milliseconds and unknown dates', () => {
 assert.equal(C.timestamp('2026-09-01T12:30:00'), C.timestamp('2026-09-01T08:30:00-04:00'));
 assert.equal(C.timestamp('2026-09-01T12:30:00.123456Z'),Date.parse('2026-09-01T12:30:00.123Z'));
 assert.equal(C.timestamp(1790000000),1790000000000); assert.equal(C.timestamp(1790000000000),1790000000000);
 for(const v of [null,undefined,'', 'not a date',Infinity])assert.equal(C.timestamp(v),null);
 assert.equal(C.dateLabel(null).date,'Not reported'); assert.equal(C.dateLabel('2026-09-01T00:00:00Z',Date.parse('2026-09-20T00:00:00Z')).age,'19d ago');
});
test('readiness requires both authoritative fields, zero is not missing',()=>{
 for(const [finished,present] of [[false,false],[true,false],[false,true],[true,true]])assert.equal(C.normalize(raw(1,{download_finished:finished,download_present:present}), 'torrent').ready,finished&&present);
 assert.equal(C.bytes(0),'0 B'); assert.equal(C.normalize(raw(1,{size:0}), 'torrent').size,0);
});
test('names use natural sort and null dates always sort last',()=>{
 const rows=[C.normalize(raw(2,{created_at:null,updated_at:'2030-01-01'}),'torrent'),C.normalize(raw(10),'torrent'),C.normalize(raw(1,{created_at:'2020-01-01T00:00:00Z'}),'torrent')];
 assert.deepEqual(C.sortRows(rows,'name','asc').map(x=>x.id),[1,2,10]);
 for(const direction of ['asc','desc']) assert.equal(C.sortRows(rows,'added',direction).at(-1).id,2);
});
test('folder browse retains paths, directories first, recursive search and exact extensions',()=>{
 const files=C.files([{id:1,name:'root/season 1/ep10.mkv',size:100},{id:2,name:'root/season 1/ep2.mkv',size:20},{id:3,name:'root/info.txt',size:5},{id:4,name:'root/readme',size:0}]);
 const root=C.browse(files,'root');assert.equal(root[0].folder,true);assert.equal(root[0].count,2);assert.equal(root[0].size,120);
 assert.deepEqual(C.browse(files,'root/season 1').map(x=>x.id),[2,1]);assert.equal(C.browse(files,'root','', 'mkv').length,2);
 assert.equal(C.browse(files,'root','ep10').length,1);assert.equal(C.browse(files,'root','', 'mk').length,0);
 assert.equal(C.cleanPath('../root//a\\b'), 'root/a/b');
});
test('duplicate/missing/unsafe file identities fail instead of downloading another file',()=>{
 assert.throws(()=>C.files([{name:'x'}])); assert.throws(()=>C.files([{id:1,name:'x'},{id:1,name:'y'}]));
 assert.throws(()=>C.normalize(raw('9007199254740993'),'torrent'));
});
test('file explorer stress: 50,000 files in 500 folders',()=>{
 const files=C.files(Array.from({length:50000},(_,i)=>({id:i,name:`folder${Math.floor(i/100)}/file${i}.mkv`,size:i})));
 const start=performance.now(); assert.equal(C.browse(files).length,500); assert.equal(C.browse(files,'folder499').length,100);assert.equal(C.browse(files,'','file49999').length,1);
 assert.ok(performance.now()-start<4000);
});
test('safe download supports known TorBox key links without permitting API or hostile destinations',()=>{
 assert.equal(C.safeDownload('https://cdn.example.org/file.mp4',key).credentialBearing,false);
 assert.equal(C.safeDownload(`https://storage-a.torbox.app/file?token=${key}`,key).credentialBearing,true);
 for(const u of [`http://storage.torbox.app/?token=${key}`,`https://storage.torbox.app.evil.test/?token=${key}`,`https://api.torbox.app/?token=${key}`,`https://storage.torbox.app@evil.test/?token=${key}`,`https://storage.torbox.app/?token=${key}&token=${key}`,`https://storage.torbox.app:8443/?token=${key}`,`https://evil.test/${encodeURIComponent(key)}`,'javascript:alert(1)'])assert.throws(()=>C.safeDownload(u,key));
});
test('redaction removes raw and encoded secrets without echoing URLs',()=>{
 for(const secret of [key,encodeURIComponent(key),encodeURIComponent('key +/%')]) assert.ok(!C.redact('Error '+secret,secret===key?key:'key +/%').includes(secret));
 assert.ok(!C.redact('Failed https://example.org/?token=abc',key).includes('https'));
});
test('API requests are no-store, omit cookies, never follow redirects and send bearer only to TorBox', async()=>{
 let capture; const c=new C.Client(key,async(u,o)=>{capture={u,o};return response({id:1});});await c.me();
 assert.equal(new URL(capture.u).origin,'https://api.torbox.app');assert.equal(capture.o.headers.Authorization,'Bearer '+key);assert.ok(!capture.u.includes(key));
 assert.equal(capture.o.cache,'no-store');assert.equal(capture.o.credentials,'omit');assert.equal(capture.o.redirect,'error');
 await assert.rejects(c.request('https://evil.test'),/Invalid/);
});
test('requestdl returns actual TorBox response and never navigates authenticated API endpoint',async()=>{
 let u;const c=new C.Client(key,async(url)=>{u=new URL(url);return response(`https://storage.torbox.app/f?token=${key}`);});
 const link=await c.link(C.normalize(raw(1),'torrent'),{id:4});assert.equal(link.credentialBearing,true);assert.equal(u.searchParams.get('redirect'),'false');assert.equal(u.searchParams.get('file_id'),'4');
 await assert.rejects(c.link(C.normalize(raw(1,{download_present:false}),'torrent'),{id:4}),/not complete/);
});
test('pagination fetches all pages and torrent/web identities never collide',async()=>{
 const offsets=[];const c=new C.Client(key,async(u)=>{const n=+new URL(u).searchParams.get('offset'); offsets.push(n); return response(Array.from({length:n===0?1000:8},(_,i)=>raw(n+i)));});
 assert.equal((await c.list('torrent')).length,1008);assert.deepEqual(offsets,[0,1000]);assert.notEqual(C.normalize(raw(1),'torrent').key,C.normalize(raw(1),'webdl').key);
});
test('repeated pagination is detected rather than looping or silently truncating',async()=>{
 const c=new C.Client(key,async()=>response(Array.from({length:1000},(_,i)=>raw(i))));await assert.rejects(c.list('torrent'),/repeated a page/);
});
test('only explicit non-auth ITEM_NOT_FOUND becomes an empty list',async()=>{
 const c=new C.Client(key,async()=>response(null,404,{success:false,error:'ITEM_NOT_FOUND'}));assert.deepEqual(await c.list('torrent'),[]);
 const forbidden=new C.Client(key,async()=>response(null,403,{success:false,error:'ITEM_NOT_FOUND'}));await assert.rejects(forbidden.list('torrent'));
 const missing=new C.Client(key,async()=>response(null,404,{success:false}));await assert.rejects(missing.list('torrent'));
});
test('rate-limit response enforces cooldown without repeated requests',async()=>{
 let calls=0;const c=new C.Client(key,async()=>{calls++;return new Response(JSON.stringify({success:false}),{status:429,headers:{'Retry-After':'120'}});});
 await assert.rejects(c.me(),/rate limiting/);await assert.rejects(c.me(),/wait/);assert.equal(calls,1);
});
test('invalid key is distinguished from service failure',async()=>{
 const c=new C.Client(key,async()=>response(null,401,{success:false,error:'BAD_TOKEN'}));await assert.rejects(c.me(),e=>e.auth===true);
});
test('sign out aborts in-flight operations and a late response cannot be used',async()=>{
 let resolve,signal;const c=new C.Client(key,async(u,o)=>{signal=o.signal;return new Promise(r=>resolve=r);});const pending=c.me();c.clear();assert.equal(signal.aborted,true);resolve(response({id:1}));await assert.rejects(pending,/Session ended/);await assert.rejects(c.me(),/Sign in/);
});
test('edit fetches current metadata and preserves unrelated fields',async()=>{
 let sent;const c=new C.Client(key,async(u,o)=>{if(o.method==='PUT'){sent=JSON.parse(o.body);return response(null);}return response([raw(3,{tags:['keep'],alternative_hashes:['keep-hash'],airlocked:true})]);});
 await c.edit(C.normalize(raw(3),'torrent'),{name:'New'});assert.deepEqual(sent,{torrent_id:3,name:'New',tags:['keep'],alternative_hashes:['keep-hash'],airlocked:true});
});
test('delete and queue control use isolated IDs and never all=true',async()=>{
 const sent=[];const c=new C.Client(key,async(u,o)=>{sent.push([u,JSON.parse(o.body)]);return response(null);});
 await c.control(C.normalize(raw(2),'webdl'),'delete');await c.control(C.normalize(raw(2),'torrent',true),'start');
 assert.equal(sent[0][1].webdl_id,2);assert.equal(sent[1][1].queued_id,2);assert.ok(sent.every(x=>x[1].all===false));
 await assert.rejects(async()=>c.control(C.normalize(raw(2),'webdl'),'pause'));
});
test('add correctly encodes torrent options and HTTP links',async()=>{
 let sent;const c=new C.Client(key,async(u,o)=>{sent={u,o};return response({id:5});});
 await c.add({value:'magnet:?xt=urn:btih:'+'a'.repeat(40),name:'Test',queued:true,cached:true,seed:1});assert.ok(sent.o.body instanceof FormData);assert.equal(sent.o.body.get('as_queued'),'true');
 await c.add({value:'https://example.org/file',name:''});assert.ok(sent.o.body instanceof URLSearchParams);assert.equal(sent.o.body.get('link'),'https://example.org/file');
 await assert.rejects(async()=>c.add({value:'javascript:alert(1)',name:''}));
});
test('mutation network failures warn to check before retrying and are not auto-retried',async()=>{
 let n=0;const c=new C.Client(key,async()=>{n++;throw new TypeError('fetch failed');});
 await assert.rejects(c.control(C.normalize(raw(1),'torrent'),'delete'),/Refresh to check/);assert.equal(n,1);
});
test('account allowances follow TorBox plan IDs, including Pro=2 and Standard=3',()=>{
 for(const [plan,name,slots,airlock,bandwidth] of [[0,'Free',1,0,5e12],[1,'Essential',3,300e9,10e12],[2,'Pro',10,1e12,30e12],[3,'Standard',5,500e9,20e12]]){
  const a=C.accountInfo({plan,premium_expires_at:'2027-01-01T00:00:00Z'});
  assert.equal(a.plan,name);assert.equal(a.slots,slots);assert.equal(a.airlockLimit,airlock);assert.equal(a.bandwidthBaseline,bandwidth);
 }
 assert.equal(C.accountInfo({plan:'standard',additional_concurrent_slots:'2'}).slots,7);
 assert.equal(C.accountInfo({plan:2,additional_concurrent_slots:100}).slots,10);
 for(const plan of [null,undefined,false,true,'',{},4,-1,'unknown']){
  const a=C.accountInfo({plan});assert.equal(a.plan,'Not reported');assert.equal(a.slots,null);assert.equal(a.airlockLimit,null);assert.equal(a.bandwidthBaseline,null);
 }
 assert.equal(C.accountInfo({plan:1,additional_concurrent_slots:false}).extraSlots,0);
 assert.throws(()=>C.accountInfo([]));
});
test('30-day bandwidth sums only the documented usage buckets, never lifetime totals',()=>{
 const bucket=(date,bytes_downloaded)=>({date,bytes_downloaded});
 const data={general:{total_downloaded:999e12},bandwidth:[bucket('2026-09-05T00:00:00Z',123e9),bucket('2026-09-06T00:00:00Z','77') ]};
 assert.equal(C.bandwidthTotal(data),123e9+77);assert.equal(C.bandwidthTotal({bandwidth:[]}),0);
 for(const d of [{},null,{bandwidth:null},{bandwidth:[bucket('2026-09-01',false)]},{bandwidth:[bucket('2026-09-01',null)]},{bandwidth:[bucket('invalid',1)]},{bandwidth:[bucket('2026-09-01',1),bucket('2026-09-01',2)]},{bandwidth:[bucket('2026-09-01',Number.MAX_SAFE_INTEGER),bucket('2026-09-02',1)]}])assert.equal(C.bandwidthTotal(d),null);
});
test('quota usage includes seeding, excludes cached inactive files, and counts only AirLock bytes',()=>{
 const rows=[C.normalize(raw(1,{active:true,download_state:'uploading',airlocked:true,size:300}),'torrent'),C.normalize(raw(2,{active:false,airlocked:false,size:900e9}),'torrent'),{id:3,active:true,airlocked:true,size:200}];
 assert.deepEqual(C.usageTotals(rows),{active:2,airlock:500});
 assert.deepEqual(C.usageTotals([]),{active:0,airlock:0});assert.deepEqual(C.usageTotals(null),{active:null,airlock:null});
 assert.deepEqual(C.usageTotals([C.normalize(raw(4),'torrent')]),{active:null,airlock:null});
 assert.deepEqual(C.usageTotals([{active:false,airlocked:true,size:null}]),{active:0,airlock:null});
 assert.deepEqual(C.usageTotals([{active:false,airlocked:false,size:null}]),{active:0,airlock:0});
});
test('bandwidth API requests rolling usage with bearer authentication and no general lifetime data',async()=>{
 let seen;const c=new C.Client(key,async(u,o)=>{seen={u:new URL(u),o};return response({bandwidth:[]});});await c.stats();
 assert.equal(seen.u.pathname,'/v1/api/user/stats');assert.equal(seen.u.searchParams.get('general'),'false');assert.equal(seen.u.searchParams.get('bandwidth'),'true');assert.equal(seen.u.searchParams.get('bandwidth_grouping'),'day');assert.equal(seen.o.headers.Authorization,'Bearer '+key);
});
test('read-only Usenet account usage paginates fully and retains unknown flags',async()=>{
 const offsets=[];const c=new C.Client(key,async u=>{const url=new URL(u),n=+url.searchParams.get('offset');offsets.push(n);assert.equal(url.pathname,'/v1/api/usenet/mylist');return response(Array.from({length:n===0?1000:1},(_,i)=>({id:n+i,active:false,airlocked:i===0,size:20})));});
 assert.equal((await c.usenetUsage()).length,1001);assert.deepEqual(offsets,[0,1000]);
 const malformed=new C.Client(key,async()=>response([{id:1,size:0}]));assert.deepEqual(C.usageTotals(await malformed.usenetUsage()),{active:null,airlock:null});
});
