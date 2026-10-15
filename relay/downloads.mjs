// TorBox Drop secure, stateless download handoff.
// CDN links (which may contain a master API token) are encrypted in short-lived
// download tickets. Neither the master token nor the CDN URL is returned to the browser.
import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';

const UPSTREAM = 'https://api.torbox.app/v1/api/';
const secret = randomBytes(32); // Process-local; resets on restart. Never persisted.
const TTL_MS = 2 * 60 * 1000;
const MAX_TICKET = 12000;
const MAX_FILENAME = 180;
const MAX_JSON = 4096;

function validCdn(raw) {
  const url = new URL(raw);
  const host = url.hostname.toLowerCase().replace(/\.$/, '');
  const trusted = host === 'storage.torbox.app' ||
    /^storage-[a-z0-9-]+\.torbox\.app$/.test(host) ||
    host === 'cdn.torbox.app' || host.endsWith('.cdn.torbox.app');
  if (!trusted || url.protocol !== 'https:' || url.username || url.password ||
      (url.port && url.port !== '443')) throw Error('The file host was not verified.');
  url.hash = '';
  return url;
}
function safeFilename(value) {
  if (typeof value !== 'string' || !value.trim() || value.length > 1024) throw Error('Invalid filename.');
  return value
    .replace(/[\\/]/g, '_')
    .replace(/[\x00-\x1f\x7f"<>|:*?]/g, '_')
    .replace(/^\.+/, '_')
    .trim().slice(0, MAX_FILENAME) || 'torbox-download';
}
function issue(url, filename) {
  const iv = randomBytes(12), cipher = createCipheriv('aes-256-gcm', secret, iv);
  const plaintext = Buffer.from(JSON.stringify({ url: url.href, filename, expires: Date.now() + TTL_MS }));
  const encrypted = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  return Buffer.concat([iv, cipher.getAuthTag(), encrypted]).toString('base64url');
}
function unpack(ticket) {
  if (!/^[A-Za-z0-9_-]+$/.test(ticket) || ticket.length < 45 || ticket.length > MAX_TICKET) throw Error('Invalid download ticket.');
  const data = Buffer.from(ticket, 'base64url'), iv = data.subarray(0, 12), tag = data.subarray(12, 28);
  const decipher = createDecipheriv('aes-256-gcm', secret, iv);
  decipher.setAuthTag(tag);
  const plaintext = Buffer.concat([decipher.update(data.subarray(28)), decipher.final()]);
  const value = JSON.parse(plaintext.toString('utf8'));
  if (!Number.isSafeInteger(value.expires) || Date.now() > value.expires ||
      value.expires > Date.now() + TTL_MS || typeof value.filename !== 'string')
    throw Error('The download ticket expired.');
  return { url: validCdn(value.url), filename: safeFilename(value.filename) };
}
function fail(res, status, message) {
  if (res.headersSent) return res.destroy();
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify({ success: false, error: 'DROP_DOWNLOAD_FAILED', detail: message }));
}
function json(res, data) {
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify({ success: true, data }));
}
async function bounded(stream, limit) {
  let total = 0; const chunks = [];
  for await (const chunk of stream) {
    total += chunk.length;
    if (total > limit) throw Error('Request too large.');
    chunks.push(Buffer.from(chunk));
  }
  return Buffer.concat(chunks);
}

export async function createTicket(req, res, fetchFn, key) {
  try {
    if (req.method !== 'POST') return fail(res, 405, 'Method not allowed.');
    if (!/^application\/json(?:;|$)/i.test(req.headers['content-type'] || '')) return fail(res, 415, 'A JSON request is required.');
    if (Number(req.headers['content-length']) > MAX_JSON) return fail(res, 413, 'Request too large.');
    const form = JSON.parse((await bounded(req, MAX_JSON)).toString('utf8'));
    if (!form || typeof form !== 'object' || Array.isArray(form)) throw Error('Invalid request.');
    const type = form.type;
    const id = form.id, fileId = form.file_id, zip = form.zip === true;
    if (!['webdl', 'torrent'].includes(type) ||
        !Number.isSafeInteger(id) || id < 0 ||
        (fileId != null && (!Number.isSafeInteger(fileId) || fileId < 0)) ||
        (fileId == null && (!zip || type !== 'torrent')) ||
        (zip && type !== 'torrent')) throw Error('Invalid download identity.');
    const name = safeFilename(form.file_name);
    const path = type === 'torrent' ? 'torrents/requestdl' : 'webdl/requestdl';
    const url = new URL(path, UPSTREAM);
    url.searchParams.set('token', key);
    url.searchParams.set(type === 'torrent' ? 'torrent_id' : 'web_id', String(id));
    if (fileId != null) url.searchParams.set('file_id', String(fileId));
    url.searchParams.set('zip_link', String(zip));
    url.searchParams.set('redirect', 'false');
    url.searchParams.set('append_name', 'true');
    const upstream = await fetchFn(url, {
      method: 'GET', headers: { Accept: 'application/json' },
      redirect: 'error', signal: AbortSignal.timeout(28000),
    });
    if (!upstream.ok) return fail(res, 502, 'TorBox did not issue a downloadable file link.');
    const envelope = JSON.parse((await bounded(upstream.body, 16384)).toString('utf8'));
    if (!envelope || envelope.success !== true || envelope.error)
      return fail(res, 502, 'TorBox did not issue a downloadable file link.');
    const result = envelope.data;
    const raw = typeof result === 'string' ? result : result?.url || result?.download_url || result?.link;
    const cdn = validCdn(raw);
    // Relative relay path: never build a download URL from untrusted Host headers.
    return json(res, { url: '/media/' + issue(cdn, name), expires_in: TTL_MS / 1000 });
  } catch {
    return fail(res, 502, 'Could not prepare a secure browser download. Please try again.');
  }
}

export async function streamTicket(req, res, fetchFn, ticket) {
  if (req.method !== 'GET') return fail(res, 405, 'Method not allowed.');
  let source;
  try { source = unpack(ticket); }
  catch { return fail(res, 410, 'This secure link expired. Return to TorBox Drop to request a fresh one.'); }
  const controller = new AbortController();
  const onClose = () => { if (!res.writableEnded) controller.abort(); };
  req.once('aborted', onClose);
  res.once('close', onClose);
  try {
    const headers = { Accept: 'application/octet-stream' };
    const range = req.headers.range;
    if (range) {
      if (!/^bytes=\d+-\d*$/.test(range) || range.length > 80) return fail(res, 416, 'Unsupported byte range.');
      headers.Range = range;
    }
    let url = source.url;
    let upstream;
    for (let i = 0; i < 4; i++) {
      upstream = await fetchFn(url, { method: 'GET', headers, redirect: 'manual', signal: controller.signal });
      if (![301, 302, 303, 307, 308].includes(upstream.status)) break;
      if (i === 3) throw Error('Too many redirects.');
      // Never allow credential-bearing URLs to be redirected to other hosts.
      const next = validCdn(new URL(upstream.headers.get('location'), url).href);
      if (next.hostname !== url.hostname) throw Error('Unexpected file-host redirect.');
      url = next;
    }
    if (![200, 206].includes(upstream.status) || !upstream.body)
      return fail(res, 502, 'TorBox could not serve this file. Request a fresh link and retry.');
    const safe = source.filename;
    const fallback = safe.replace(/[^\x20-\x7e]/g, '_').replace(/["\\]/g, '_');
    res.setHeader('Content-Disposition', 'attachment; filename="' + fallback + '"; filename*=UTF-8\'\'' + encodeURIComponent(safe));
    res.setHeader('Content-Type', 'application/octet-stream');
    res.setHeader('Content-Security-Policy', "default-src 'none'");
    for (const header of ['content-length', 'content-range', 'accept-ranges']) {
      const value = upstream.headers.get(header);
      if (value && value.length < 128) res.setHeader(header, value);
    }
    res.setHeader('Access-Control-Expose-Headers', 'Content-Length, Content-Range, Accept-Ranges');
    res.writeHead(upstream.status);
    await pipeline(Readable.fromWeb(upstream.body), res, { signal: controller.signal });
  } catch {
    if (!res.headersSent) fail(res, 502, 'The secure file transfer failed. Return to TorBox Drop and retry.');
    else res.destroy();
  } finally {
    req.off('aborted', onClose);
    res.off('close', onClose);
  }
}
