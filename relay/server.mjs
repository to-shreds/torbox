import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
export const VERSION = '2.1.0';
const UPSTREAM = 'https://api.torbox.app/v1/api/';
const MAX_BODY = 21 * 1024 * 1024, MAX_RESPONSE = 16 * 1024 * 1024;
const routes = new Map([
 ['user/me', ['GET']], ['torrents/mylist', ['GET']], ['webdl/mylist', ['GET']], ['queued/getqueued', ['GET']],
 ['torrents/requestdl', ['GET']], ['webdl/requestdl', ['GET']], ['torrents/createtorrent', ['POST']], ['webdl/createwebdownload', ['POST']],
 ['torrents/controltorrent', ['POST']], ['webdl/controlwebdownload', ['POST']], ['queued/controlqueued', ['POST']],
 ['torrents/edittorrent', ['PUT']], ['webdl/editwebdownload', ['PUT']], ['integration/jobs', ['GET']],
]);
const allowedQuery = new Set(['settings', 'id', 'offset', 'limit', 'bypass_cache', 'type', 'torrent_id', 'web_id', 'file_id', 'zip_link', 'append_name', 'redirect']);
export async function readBounded(stream, limit) {
 let count = 0; const chunks = [];
 for await (const chunk of stream) { count += chunk.length; if (count > limit) throw new Error('LIMIT'); chunks.push(Buffer.from(chunk)); }
 return Buffer.concat(chunks);
}
function json(res, status, data) { if (res.destroyed) return; res.writeHead(status, {'Content-Type':'application/json; charset=utf-8'}); res.end(JSON.stringify(data)); }
function failure(res, status, detail) { json(res, status, {success:false, error:'DROP_REQUEST_FAILED', detail}); }
export function createServer({ fetchFn = fetch, origins = ['https://to-shreds.github.io'], serveWeb = true, local = false } = {}) {
 let inFlight = 0;
 return http.createServer(async (req, res) => {
  res.setHeader('Cache-Control','no-store, private'); res.setHeader('X-Content-Type-Options','nosniff'); res.setHeader('Referrer-Policy','no-referrer');
  const origin = req.headers.origin;
  // file:// HTML is permitted. A TorBox key is still mandatory on every API request.
  const originAllowed = !origin || origin === 'null' || origins.includes(origin) || local && /^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(origin);
  if (!originAllowed) return failure(res,403,'This website is not allowed to use the TorBox Drop API.');
  if (origin) { res.setHeader('Access-Control-Allow-Origin',origin); res.setHeader('Vary','Origin'); }
  res.setHeader('Access-Control-Expose-Headers','Retry-After');
  let incoming; try { incoming = new URL(req.url,'http://drop.invalid'); } catch { return failure(res,400,'Invalid request.'); }
  if (req.method === 'GET' && incoming.pathname === '/healthz') return json(res,200,{ok:true,version:VERSION,storage:'none',mediaProxy:false});
  if (req.method === 'GET' && incoming.pathname === '/' && serveWeb) {
   try { const html = await readFile(new URL('../web/index.html',import.meta.url),'utf8'); res.setHeader('Content-Type','text/html; charset=utf-8'); return res.end(local ? html.replace(/<meta name="torbox-api"[^>]*>/,'<meta name="torbox-api" content="/api/">') : html); }
   catch { return failure(res,503,'The web client has not been built.'); }
  }
  const path = incoming.pathname.slice('/api/'.length);
  if (!incoming.pathname.startsWith('/api/') || !routes.has(path)) return failure(res,404,'Unknown operation.');
  if (req.method === 'OPTIONS') {
   if (!routes.get(path).includes(req.headers['access-control-request-method'])) return failure(res,405,'Method not allowed.');
   res.setHeader('Access-Control-Allow-Methods',routes.get(path).join(', ')); res.setHeader('Access-Control-Allow-Headers','Authorization, Content-Type'); res.setHeader('Access-Control-Max-Age','600'); res.writeHead(204); return res.end();
  }
  if (!routes.get(path).includes(req.method)) return failure(res,405,'Method not allowed.');
  let key = /^Bearer ([^\s]{1,512})$/.exec(req.headers.authorization || '')?.[1];
  if (!key) return json(res,401,{success:false,error:'NO_AUTH',detail:'Enter your TorBox API key.'});
  if (inFlight >= 64) { res.setHeader('Retry-After','5'); return failure(res,429,'The relay is busy. Try again in a few seconds.'); }
  const url = new URL(path,UPSTREAM);
  for (const [name,value] of incoming.searchParams) {
   if (!allowedQuery.has(name) || url.searchParams.has(name) || value.length>100) return failure(res,400,'Invalid request parameter.');
   if (['id','offset','limit','torrent_id','web_id','file_id'].includes(name) && (!/^\d+$/.test(value) || !Number.isSafeInteger(Number(value)))) return failure(res,400,'Invalid numeric parameter.');
   if (name === 'limit' && Number(value)>1000) return failure(res,400,'Page size cannot exceed 1000.');
   if (['settings','bypass_cache','zip_link','append_name','redirect'].includes(name) && !['true','false'].includes(value)) return failure(res,400,'Invalid option.');
   if (name === 'type' && !['torrent','webdl'].includes(value)) return failure(res,400,'Unsupported download type.');
   url.searchParams.set(name,value);
  }
  const headers={Accept:'application/json'};
  if (path.endsWith('/requestdl')) { url.searchParams.set('token',key); url.searchParams.set('redirect','false'); }
  else headers.Authorization='Bearer '+key;
  const controller=new AbortController(), timer=setTimeout(()=>controller.abort(),28000);
  req.on('aborted',()=>controller.abort()); res.on('close',()=>{if(!res.writableEnded)controller.abort();}); inFlight++;
  try {
   let body;
   if (['POST','PUT'].includes(req.method)) {
    const length=Number(req.headers['content-length']); if (Number.isFinite(length)&&length>MAX_BODY) return failure(res,413,'This upload is too large.');
    body=await readBounded(req,MAX_BODY); const type=req.headers['content-type'] || '';
    if (path.endsWith('/createtorrent')) { if(!/^multipart\/form-data;\s*boundary=[^\r\n]{1,200}$/i.test(type)) return failure(res,400,'A torrent or magnet form is required.'); }
    else if(path.endsWith('/createwebdownload')) { if(!type.startsWith('application/x-www-form-urlencoded'))return failure(res,400,'A web download form is required.'); }
    else {
     if (!type.startsWith('application/json') || body.length>128000) return failure(res,400,'A JSON request is required.');
     let parsed;try{parsed=JSON.parse(body);}catch{return failure(res,400,'Unreadable request.');}
     if (!parsed||typeof parsed!=='object'||Array.isArray(parsed))return failure(res,400,'Invalid request body.');
     const idField=path.includes('queued')?'queued_id':path.startsWith('torrents/')?'torrent_id':'webdl_id';
     if(!Number.isSafeInteger(parsed[idField])||parsed[idField]<0)return failure(res,400,'An item ID is required.');
     if (path.includes('/control')) {
      const ops=path.includes('queued')?['start','delete']:path.startsWith('torrents/')?['pause','resume','reannounce','delete']:['delete'];
      if(!ops.includes(parsed.operation)||parsed.all!==false)return failure(res,400,'Only explicit single-item actions are allowed.');
      body=Buffer.from(JSON.stringify({[idField]:parsed[idField],operation:parsed.operation,all:false}));
     } else {
      if(typeof parsed.name!=='string'||parsed.name.length>500||!Array.isArray(parsed.tags)||!Array.isArray(parsed.alternative_hashes)||typeof parsed.airlocked!=='boolean')return failure(res,400,'Complete editable metadata is required.');
      if(parsed.tags.length>100||parsed.alternative_hashes.length>100||[...parsed.tags,...parsed.alternative_hashes].some(x=>typeof x!=='string'||x.length>2000))return failure(res,400,'Invalid metadata.');
      body=Buffer.from(JSON.stringify({[idField]:parsed[idField],name:parsed.name,tags:parsed.tags,alternative_hashes:parsed.alternative_hashes,airlocked:parsed.airlocked}));
     }
    }
    headers['Content-Type']=type;
   }
   // The destination is fixed. No arbitrary URLs, media, cookies, redirects or cache.
   const upstream=await fetchFn(url,{method:req.method,headers,body,redirect:'error',signal:controller.signal});
   if(upstream.headers.get('Retry-After')) res.setHeader('Retry-After',upstream.headers.get('Retry-After').slice(0,100));
   if(upstream.status===204) return json(res,200,{success:true,data:null});
   const data=await readBounded(upstream.body,MAX_RESPONSE);
   let envelope; try{envelope=JSON.parse(data.toString('utf8'));}catch{return failure(res,502,'TorBox returned an unreadable response.');}
   if (!envelope||typeof envelope!=='object'||Array.isArray(envelope)||typeof envelope.success!=='boolean')return failure(res,502,'TorBox returned an unreadable response.');
   if(envelope.success!==true) {
    const error=['BAD_TOKEN','NO_AUTH','ITEM_NOT_FOUND'].includes(envelope.error)?envelope.error:'TORBOX_REJECTED';
    // Provider errors can echo request URLs and credentials. Do not return their raw text.
    return json(res,upstream.status,{success:false,error,detail:error==='ITEM_NOT_FOUND'?'This item is no longer on TorBox.':error==='BAD_TOKEN'||error==='NO_AUTH'?'TorBox rejected the API key.':`TorBox rejected the request (HTTP ${upstream.status}). Refresh before retrying.`});
   }
   json(res,upstream.status,{success:true,data:envelope.data});
  } catch(error) {
   if(error.message==='LIMIT') failure(res,413,'The TorBox request or response exceeded the safe size limit.');
   else failure(res,502,req.method==='GET'?'Could not reach TorBox. Try again.':'No confirmation received from TorBox. Refresh to check whether the action succeeded before retrying.');
  } finally {clearTimeout(timer);inFlight--; key=''; delete headers.Authorization; url.searchParams.delete('token');}
 });
}
if (process.argv[1] && import.meta.url===pathToFileURL(process.argv[1]).href) {
 const local=process.env.DROP_LOCAL==='1';
 const server=createServer({local,origins:(process.env.DROP_ORIGINS||'https://to-shreds.github.io').split(',').map(x=>x.trim()).filter(Boolean)});
 server.requestTimeout=35000; server.headersTimeout=15000;
 server.listen(Number(process.env.PORT)||10000,'0.0.0.0');
 const close=()=>{server.close();setTimeout(()=>process.exit(0),5000).unref();};process.on('SIGTERM',close);process.on('SIGINT',close);
}
