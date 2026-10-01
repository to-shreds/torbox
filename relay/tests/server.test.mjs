import {test} from 'node:test';
import assert from 'node:assert/strict';
import {once} from 'node:events';
import {createServer} from '../server.mjs';
const KEY='fixture-torbox-key-no-real-secret';
async function fixture(fn){const server=createServer({fetchFn:fn,serveWeb:false,local:true});server.listen(0,'127.0.0.1');await once(server,'listening');return{url:`http://127.0.0.1:${server.address().port}`,close:()=>{server.closeAllConnections();server.close();}};}
const ok=data=>new Response(JSON.stringify({success:true,data}),{headers:{'Content-Type':'application/json'}});
const headers={Authorization:'Bearer '+KEY,Origin:'https://to-shreds.github.io'};
test('relay forwards only TorBox API requests and strips cookies/origin',async()=>{
 let seen;const f=await fixture(async(u,o)=>{seen={u,o:{...o,headers:{...o.headers}}};return ok({id:1});});try{const r=await fetch(f.url+'/api/user/me',{headers:{...headers,Cookie:'unrelated=value'}});assert.equal(r.status,200);assert.equal(seen.u.origin,'https://api.torbox.app');assert.equal(seen.o.headers.Authorization,'Bearer '+KEY);assert.equal(seen.o.headers.Cookie,undefined);assert.equal(seen.o.headers.Origin,undefined);assert.equal(seen.o.redirect,'error');assert.equal(r.headers.get('Access-Control-Allow-Origin'),headers.Origin);assert.equal(r.headers.get('Cache-Control'),'no-store, private');}finally{f.close();}
});
test('no auth, unknown path, evil origin, query secrets and wrong methods cannot reach upstream',async()=>{
 let calls=0;const f=await fixture(async()=>{calls++;return ok([]);});try{
 for(const [path,options,status] of [['/api/user/me',{},401],['/api/https://evil.test',{headers},404],['/api/user/me',{headers:{...headers,Origin:'https://evil.test'}},403],['/api/user/me?token='+KEY,{headers},400],['/api/user/me',{method:'POST',headers},405],['/api/user/me?url=https://evil.test',{headers},400]]){const r=await fetch(f.url+path,options);assert.equal(r.status,status);}
 assert.equal(calls,0);}finally{f.close();}
});
test('preflight from GitHub Pages works without needing or sending a key',async()=>{
 let called=false;const f=await fixture(async()=>{called=true;return ok([]);});try{const r=await fetch(f.url+'/api/torrents/mylist',{method:'OPTIONS',headers:{Origin:headers.Origin,'Access-Control-Request-Method':'GET','Access-Control-Request-Headers':'authorization'}});assert.equal(r.status,204);assert.match(r.headers.get('Access-Control-Allow-Headers'),/Authorization/);assert.equal(called,false);}finally{f.close();}
});
test('requestdl key exists only in upstream URL; media is never fetched or relayed',async()=>{
 const seen=[];const f=await fixture(async(u,o)=>{seen.push(u.href);assert.equal(u.searchParams.get('token'),KEY);assert.equal(u.searchParams.get('redirect'),'false');return ok(`https://storage.torbox.app/file?token=${KEY}`);});try{const r=await fetch(f.url+'/api/torrents/requestdl?torrent_id=1&file_id=2&redirect=true',{headers});assert.equal(r.status,200);assert.equal(seen.length,1);assert.ok(seen.every(u=>u.startsWith('https://api.torbox.app/')));assert.equal((await r.json()).data,`https://storage.torbox.app/file?token=${KEY}`);}finally{f.close();}
});
test('errors never echo TorBox credentials or request URLs',async()=>{
 const f=await fixture(async()=>new Response(JSON.stringify({success:false,error:'REJECTED',detail:`Oops ${KEY} https://api.torbox.app/?token=${KEY}`}),{status:400}));try{const r=await fetch(f.url+'/api/user/me',{headers});const t=await r.text();assert.equal(r.status,400);assert.ok(!t.includes(KEY));assert.ok(!t.includes('https://'));}finally{f.close();}
});
test('bulk/all management requests and unsupported operations are blocked',async()=>{
 let calls=0;const f=await fixture(async()=>{calls++;return ok(null);});try{
 for(const body of [{torrent_id:1,operation:'delete',all:true},{operation:'delete',all:false},{torrent_id:1,operation:'clear',all:false}]){const r=await fetch(f.url+'/api/torrents/controltorrent',{method:'POST',headers:{...headers,'Content-Type':'application/json'},body:JSON.stringify(body)});assert.equal(r.status,400);}assert.equal(calls,0);
 const r=await fetch(f.url+'/api/torrents/controltorrent',{method:'POST',headers:{...headers,'Content-Type':'application/json'},body:JSON.stringify({torrent_id:1,operation:'delete',all:false})});assert.equal(r.status,200);assert.equal(calls,1);
 }finally{f.close();}
});
test('rate limits preserve Retry-After and JSON',async()=>{
 const f=await fixture(async()=>new Response(JSON.stringify({success:false}),{status:429,headers:{'Retry-After':'120'}}));try{const r=await fetch(f.url+'/api/user/me',{headers});assert.equal(r.status,429);assert.equal(r.headers.get('Retry-After'),'120');}finally{f.close();}
});
test('concurrency stress preserves isolation for 48 independent keys',async()=>{
 const seen=[];const f=await fixture(async(u,o)=>{const token=o.headers.Authorization.split(' ')[1];seen.push(token);await new Promise(r=>setTimeout(r,10));return ok({id:token});});try{const results=await Promise.all(Array.from({length:48},(_,i)=>fetch(f.url+'/api/user/me',{headers:{Authorization:'Bearer fixture-'+i}}).then(r=>r.json())));assert.deepEqual(results.map(x=>x.data.id),Array.from({length:48},(_,i)=>'fixture-'+i));assert.equal(new Set(seen).size,48);}finally{f.close();}
});
test('no upstream video or HTML response is returned as API JSON',async()=>{
 const f=await fixture(async()=>new Response('<html>not json</html>'));try{const r=await fetch(f.url+'/api/user/me',{headers});assert.equal(r.status,502);}finally{f.close();}
});
test('successful empty mutation is normalized to JSON',async()=>{
 const f=await fixture(async()=>new Response(null,{status:204}));try{const r=await fetch(f.url+'/api/queued/controlqueued',{method:'POST',headers:{...headers,'Content-Type':'application/json'},body:JSON.stringify({queued_id:1,operation:'start',all:false})});assert.equal(r.status,200);assert.deepEqual(await r.json(),{success:true,data:null});}finally{f.close();}
});
