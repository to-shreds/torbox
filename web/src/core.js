/* TorBox Drop web 2.1.2. No browser storage, analytics, or third-party script dependencies. */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.TorBoxCore = api;
})(typeof globalThis === 'object' ? globalThis : this, function () {
  'use strict';
  const configured = typeof document !== 'undefined' ? document.querySelector('meta[name="torbox-api"]')?.content : '';
  const API = configured ? new URL(configured, location.href).href : 'https://api.torbox.app/v1/api/';
  const RELAY = !API.startsWith('https://api.torbox.app/');
  const TYPES = { torrent: { prefix: 'torrents', id: 'torrent_id', editId: 'torrent_id', control: 'controltorrent', edit: 'edittorrent' }, webdl: { prefix: 'webdl', id: 'web_id', editId: 'webdl_id', control: 'controlwebdownload', edit: 'editwebdownload' } };
  const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });
  const num = reportedNumber;
  const id = v => { if (!/^\d+$/.test(String(v)) || !Number.isSafeInteger(Number(v))) throw new Error('TorBox returned an invalid item ID.'); return Number(v); };
  function timestamp(v) {
    if (v == null || v === '') return null;
    let n;
    if (typeof v === 'number' || /^\d+(\.\d+)?$/.test(v)) n = Number(v) * (Number(v) < 1e11 ? 1000 : 1);
    else { const s = String(v); n = Date.parse(/^\d{4}-\d\d-\d\d[T ]\d\d:\d\d/.test(s) && !/(Z|[+-]\d\d:\d\d)$/i.test(s) ? s.replace(' ', 'T') + 'Z' : s); }
    return Number.isFinite(n) && Math.abs(n) < 8.64e15 ? n : null;
  }
  function dateLabel(v, now = Date.now()) {
    const t = timestamp(v);
    if (t == null) return { date: 'Not reported', age: '', full: 'TorBox did not report this date.' };
    const d = new Date(t), seconds = Math.floor((now - t) / 1000);
    const age = seconds < -60 ? 'Future date' : seconds < 60 ? 'Just now' : seconds < 3600 ? Math.floor(seconds / 60) + 'm ago' : seconds < 86400 ? Math.floor(seconds / 3600) + 'h ago' : Math.floor(seconds / 86400) + 'd ago';
    return { date: d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' }), age, full: d.toLocaleString() };
  }
  function bytes(v) {
    const n = num(v); if (n == null) return 'Not reported'; if (n < 1000) return n + ' B';
    const e = Math.min(5, Math.floor(Math.log(n) / Math.log(1000)));
    return (n / 1000 ** e).toLocaleString(undefined, { maximumFractionDigits: 2 }) + ' ' + ['B', 'KB', 'MB', 'GB', 'TB', 'PB'][e];
  }
  // Published TorBox plan allowances, checked against the official help center on 2026-10-04.
  // Bandwidth is a fair-use baseline, never a fixed cap or a remaining-byte quota.
  const PLANS = Object.freeze({
    0: Object.freeze({ name: 'Free', slots: 1, airlock: 0, bandwidth: 5e12 }),
    1: Object.freeze({ name: 'Essential', slots: 3, airlock: 300e9, bandwidth: 10e12 }),
    2: Object.freeze({ name: 'Pro', slots: 10, airlock: 1e12, bandwidth: 30e12 }),
    3: Object.freeze({ name: 'Standard', slots: 5, airlock: 500e9, bandwidth: 20e12 })
  });
  function reportedNumber(v) {
    if (typeof v !== 'number' && (typeof v !== 'string' || !/^\d+(?:\.\d+)?$/.test(v.trim()))) return null;
    const n = Number(v); return Number.isFinite(n) && n >= 0 && n <= Number.MAX_SAFE_INTEGER ? n : null;
  }
  function accountInfo(raw) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new Error('TorBox returned unreadable account information.');
    const code = typeof raw.plan === 'string' && !/^\d+$/.test(raw.plan.trim())
      ? Object.keys(PLANS).find(k => PLANS[k].name.toLowerCase() === raw.plan.trim().toLowerCase())
      : reportedNumber(raw.plan);
    const plan = PLANS[code] || null, extra = reportedNumber(raw.additional_concurrent_slots);
    return { plan: plan?.name || 'Not reported', code: plan ? Number(code) : null,
      slots: plan ? Math.min(10, plan.slots + (Number.isInteger(extra) ? extra : 0)) : null, extraSlots: Number.isInteger(extra) ? extra : 0,
      airlockLimit: plan?.airlock ?? null, bandwidthBaseline: plan?.bandwidth ?? null,
      expires: timestamp(raw.premium_expires_at ?? raw.subscription_expires_at), subscribed: raw.is_subscribed === true,
      cooldown: timestamp(raw.cooldown_until), email: typeof raw.email === 'string' ? raw.email : typeof raw.base_email === 'string' ? raw.base_email : 'Connected to TorBox' };
  }
  function bandwidthTotal(raw) {
    if (!raw || !Array.isArray(raw.bandwidth)) return null;
    let total = 0; const seen = new Set();
    for (const row of raw.bandwidth) {
      const date = timestamp(row?.date), value = reportedNumber(row?.bytes_downloaded);
      if (date == null || value == null || seen.has(date)) return null;
      seen.add(date); total += value;
      if (!Number.isSafeInteger(total)) return null;
    }
    // The endpoint returns the previous 30 days. Do not filter partially overlapping buckets.
    return total;
  }
  function usageTotals(rows) {
    if (!Array.isArray(rows)) return { active: null, airlock: null };
    let active = 0, airlock = 0;
    for (const row of rows) {
      if (typeof row.active !== 'boolean') active = null;
      else if (active != null && row.active) active++;
      const locked = Object.hasOwn(row, 'usageAirlocked') ? row.usageAirlocked : row.airlocked;
      if (typeof locked !== 'boolean') airlock = null;
      else if (airlock != null && locked) {
        const size = reportedNumber(row.size);
        if (size == null || !Number.isSafeInteger(airlock + size)) airlock = null;
        else airlock += size;
      }
    }
    return { active, airlock };
  }
  function normalize(raw, type, queued = false) {
    if (!raw || !TYPES[type]) throw new Error('TorBox returned an unreadable item.');
    const n = id(raw.id), ready = raw.download_finished === true && raw.download_present === true;
    const size = num(raw.size), done = num(raw.total_downloaded);
    const progress = ready ? 1 : size > 0 && done != null && done <= size ? done / size : num(raw.progress);
    const state = queued ? 'Queued' : ready ? 'Ready' : String(raw.download_state || raw.state || 'Unknown').replace(/_/g, ' ');
    return { ...raw, id: n, type, key: `${queued ? 'queue:' : ''}${type}:${n}`, name: String(raw.name || raw.hash || 'Unnamed download'), ready, queued,
      size, progress: progress == null ? null : Math.max(0, Math.min(1, progress)), state,
      added: timestamp(raw.created_at), cachedAt: timestamp(raw.cached_at), updated: timestamp(raw.updated_at),
      tags: Array.isArray(raw.tags) ? raw.tags.filter(t => typeof t === 'string') : [], airlocked: raw.airlocked === true, usageAirlocked: raw.airlocked };
  }
  function sortRows(rows, field = 'added', direction = 'desc') {
    return [...rows].sort((a, b) => {
      const x = a[field], y = b[field];
      // Missing values stay last in either direction.
      if (x == null && y != null) return 1; if (y == null && x != null) return -1;
      const c = x == null && y == null ? 0 : typeof x === 'number' && typeof y === 'number' ? x - y : collator.compare(String(x), String(y));
      return c * (direction === 'desc' ? -1 : 1) || collator.compare(a.name, b.name) || String(a.key).localeCompare(String(b.key));
    });
  }
  function cleanPath(s) { return String(s || '').replace(/\\/g, '/').split('/').filter(p => p && p !== '.' && p !== '..').join('/'); }
  function files(raw) {
    if (!Array.isArray(raw)) return [];
    const seen = new Set();
    return raw.map(f => {
      const n = id(f.id); if (seen.has(n)) throw new Error('TorBox returned duplicate file IDs. Refresh before downloading.'); seen.add(n);
      const path = cleanPath(f.name || f.short_name || `File ${n}`);
      const name = path.split('/').pop();
      return { id: n, key: `file:${n}`, name, path, size: num(f.size), infected: f.infected === true, mime: typeof f.mimetype === 'string' ? f.mimetype : '', extension: name.includes('.') ? name.split('.').pop().toLowerCase() : '' };
    });
  }
  function browse(all, folder = '', query = '', extension = '', sort = 'name', direction = 'asc') {
    const q = query.toLocaleLowerCase().trim(), prefix = folder ? folder + '/' : '';
    const matching = all.filter(f => f.path.startsWith(prefix) && (!q || f.path.toLocaleLowerCase().includes(q)) && (!extension || f.extension === extension));
    const found = [], folders = new Map();
    for (const f of matching) {
      const relative = f.path.slice(prefix.length), slash = relative.indexOf('/');
      if (!q && !extension && slash >= 0) {
        const name = relative.slice(0, slash), path = prefix + name;
        const d = folders.get(path) || { key: 'folder:' + path, name, path, folder: true, count: 0, size: 0 };
        d.count++; if (f.size == null) d.size = null; else if (d.size != null) d.size += f.size; folders.set(path, d);
      } else found.push(f);
    }
    return [...sortRows([...folders.values()], sort, direction), ...sortRows(found, sort, direction)];
  }
  function containsKey(value, key) {
    if (!key) return false;
    let s = String(value);
    for (let i = 0; i < 3; i++) { if (s.includes(key) || s.toLowerCase().includes(encodeURIComponent(key).toLowerCase())) return true; try { s = decodeURIComponent(s); } catch { break; } }
    return false;
  }
  function safeDownload(url, key) {
    let u; try { u = new URL(url); } catch { throw new Error('TorBox returned an invalid download link.'); }
    const h = u.hostname.toLowerCase();
    if (u.protocol !== 'https:' || u.username || u.password || h === 'api.torbox.app' || (u.port && u.port !== '443')) throw new Error('Unsafe download destination was blocked.');
    const credentialBearing = containsKey(url, key);
    if (credentialBearing) {
      const trusted = h === 'storage.torbox.app' || /^storage-[a-z0-9-]+\.torbox\.app$/.test(h) || h === 'cdn.torbox.app' || h.endsWith('.cdn.torbox.app');
      const tokens = [...u.searchParams].filter(([n]) => n.toLowerCase() === 'token');
      if (!trusted || tokens.length !== 1 || tokens[0][1] !== key) throw new Error('An unexpected credential-bearing download link was blocked.');
    }
    return { url: u.href, credentialBearing };
  }
  function redact(value, key) {
    let s = String(value || 'TorBox could not complete this request.');
    if (containsKey(s, key)) return 'TorBox could not complete this request. Sensitive response details were hidden.';
    return s.replace(/https?:\/\/\S+/g, '[link]').replace(/(?:bearer\s+|(?:api[_-]?key|token|authorization)["'\s:=]+)\S+/gi, '[redacted]').slice(0, 350);
  }
  class Client {
    #key; #fetch; #controllers = new Set(); #epoch = 0;
    retryAt = 0;
    constructor(key, fetcher = globalThis.fetch.bind(globalThis)) { this.#key = String(key).trim(); this.#fetch = fetcher; if (!this.#key || /[\r\n]/.test(this.#key)) throw new Error('Enter a valid TorBox API key.'); }
    clear() { this.#key = ''; this.#epoch++; for (const c of this.#controllers) c.abort(); this.#controllers.clear(); }
    async request(path, { query = {}, method = 'GET', body, download = false, emptyNotFound = false } = {}) {
      if (!this.#key) throw new Error('Sign in again.');
      if (Date.now() < this.retryAt) throw new Error(`TorBox asked us to wait. Try again in ${Math.ceil((this.retryAt - Date.now()) / 1000)} seconds.`);
      if (!/^[a-z0-9/]+$/.test(path)) throw new Error('Invalid API operation.');
      const url = new URL(path, API), epoch = this.#epoch, controller = new AbortController();
      for (const [k, v] of Object.entries(query)) if (v != null) url.searchParams.set(k, String(v));
      const headers = { Accept: 'application/json' };
      if (download && !RELAY) url.searchParams.set('token', this.#key); else headers.Authorization = 'Bearer ' + this.#key;
      if (body && !(body instanceof FormData) && !(body instanceof URLSearchParams)) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(body); }
      this.#controllers.add(controller); const timer = setTimeout(() => controller.abort(), path === 'user/me' ? 70000 : 35000);
      try {
        const response = await this.#fetch(url.href, { method, headers, body, signal: controller.signal, credentials: 'omit', cache: 'no-store', referrerPolicy: 'no-referrer', redirect: 'error' });
        if (epoch !== this.#epoch) throw new Error('Session ended.');
        let data; try { data = await response.json(); } catch { throw new Error('TorBox returned an unreadable response. Please try again.'); }
        if (response.status === 401 || ['BAD_TOKEN', 'NO_AUTH'].includes(data.error)) { const e = new Error('TorBox rejected this API key. Sign in with a valid key.'); e.auth = true; throw e; }
        if (response.status === 429) {
          const value = response.headers.get('Retry-After'); const seconds = /^\d+$/.test(value || '') ? Number(value) : Math.ceil((Date.parse(value) - Date.now()) / 1000);
          this.retryAt = Date.now() + Math.max(1, Number.isFinite(seconds) ? seconds : 60) * 1000;
          throw new Error(`TorBox is rate limiting requests. Try again in ${Math.ceil((this.retryAt - Date.now()) / 1000)} seconds.`);
        }
        if (emptyNotFound && data.error === 'ITEM_NOT_FOUND' && ![401, 403].includes(response.status)) return [];
        if (!response.ok || data.success !== true || data.error) throw new Error(redact(data.detail || data.message || `TorBox rejected the request (HTTP ${response.status}).`, this.#key));
        return data.data;
      } catch (error) {
        if (epoch !== this.#epoch) throw new Error('Session ended.');
        if (error.name === 'AbortError') throw new Error(method === 'GET' ? 'TorBox took too long to respond. Try again.' : 'No confirmation received. Refresh to check whether TorBox accepted the action before retrying.');
        if (error instanceof TypeError) throw new Error(method === 'GET' ? 'Could not reach TorBox. Check your connection and try again.' : 'Connection lost before confirmation. Refresh to check whether TorBox accepted the action before retrying.');
        throw error;
      } finally { clearTimeout(timer); this.#controllers.delete(controller); }
    }
    me() { return this.request('user/me', { query: { settings: true } }); }
    stats() { return this.request('user/stats', { query: { general: false, bandwidth: true, bandwidth_grouping: 'day' } }); }
    async usenetUsage(fresh = false) {
      const rows = new Map(), limit = 1000;
      for (let offset = 0; offset < 500000; offset += limit) {
        const data = await this.request('usenet/mylist', { query: { offset, limit, bypass_cache: fresh }, emptyNotFound: true });
        if (!Array.isArray(data)) throw new Error('TorBox returned unreadable Usenet usage.');
        const before = rows.size;
        for (const raw of data) { const n = id(raw?.id); rows.set(n, { id: n, active: raw.active, airlocked: raw.airlocked, size: reportedNumber(raw.size) }); }
        if (data.length < limit) return [...rows.values()];
        if (rows.size === before) throw new Error('TorBox repeated a page of Usenet usage.');
      }
      throw new Error('TorBox usage exceeded the supported page limit.');
    }
    async list(type, queued = false, fresh = false) {
      const rows = new Map(), limit = 1000;
      for (let offset = 0; offset < 500000; offset += limit) {
        const data = await this.request(queued ? 'queued/getqueued' : TYPES[type].prefix + '/mylist', { query: { ...(queued ? { type } : {}), offset, limit, bypass_cache: fresh }, emptyNotFound: true });
        if (!Array.isArray(data)) throw new Error('TorBox returned an unreadable file list.');
        const before = rows.size;
        for (const raw of data) { const row = normalize(raw, type, queued); rows.set(row.key, row); }
        if (data.length < limit) return [...rows.values()];
        if (rows.size === before) throw new Error('TorBox repeated a page of results. Refresh to load the full list.');
      }
      throw new Error('TorBox list exceeded the supported page limit. Results were not truncated silently.');
    }
    async detail(item) {
      const d = await this.request(TYPES[item.type].prefix + '/mylist', { query: { id: id(item.id), bypass_cache: true } });
      const raw = (Array.isArray(d) ? d : d ? [d] : []).find(r => Number(r.id) === item.id);
      if (!raw) throw new Error('This item is no longer available on TorBox.');
      return { item: normalize(raw, item.type), files: files(raw.files) };
    }
    async link(item, file = null, zip = false) {
      if (!item.ready) throw new Error('This content is not complete and present on TorBox yet.');
      if (zip && (item.type !== 'torrent' || item.allow_zipped !== true)) throw new Error('TorBox does not offer a ZIP for this item.');
      const d = await this.request(TYPES[item.type].prefix + '/requestdl', { download: true, query: { [TYPES[item.type].id]: id(item.id), ...(file ? { file_id: id(file.id) } : {}), zip_link: zip, redirect: false, append_name: true } });
      return safeDownload(typeof d === 'string' ? d : d?.url || d?.download_url || d?.link, this.#key);
    }
    control(item, operation) {
      if (item.queued) return this.request('queued/controlqueued', { method: 'POST', body: { queued_id: id(item.id), operation, all: false } });
      if (!['delete', 'pause', 'resume', 'reannounce'].includes(operation) || item.type === 'webdl' && operation !== 'delete') throw new Error('This operation is not supported for that item.');
      const t = TYPES[item.type]; return this.request(t.prefix + '/' + t.control, { method: 'POST', body: { [t.editId]: id(item.id), operation, all: false } });
    }
    async edit(item, changes) {
      const current = (await this.detail(item)).item, t = TYPES[item.type];
      if (!current.ready) throw new Error('TorBox allows editing only when the item is ready.');
      const { name, tags, airlocked } = { ...current, ...changes };
      await this.request(t.prefix + '/' + t.edit, { method: 'PUT', body: { [t.editId]: id(item.id), name, tags, airlocked, alternative_hashes: Array.isArray(current.alternative_hashes) ? current.alternative_hashes : [] } });
      return { ...current, name, tags, airlocked };
    }
    add({ value, file, name, queued = false, cached = false, seed = 1 }) {
      const torrent = !!file || /^magnet:\?/i.test(value);
      if (!torrent) { let u; try { u = new URL(value); } catch { throw new Error('Enter a magnet, an HTTP(S) link, or choose a .torrent file.'); } if (!['http:', 'https:'].includes(u.protocol) || u.username || u.password) throw new Error('Enter a valid HTTP(S) download link.'); }
      const body = torrent ? new FormData() : new URLSearchParams();
      if (file) { if (!file.name.toLowerCase().endsWith('.torrent') || file.size === 0 || file.size > 20 * 1024 * 1024) throw new Error('Choose a nonempty .torrent file smaller than 20 MB.'); body.set('file', file, file.name); }
      else body.set(torrent ? 'magnet' : 'link', value.trim());
      body.set('as_queued', String(queued)); body.set('add_only_if_cached', String(cached));
      if (name.trim()) body.set('name', name.trim()); if (torrent) { body.set('seed', String(seed)); body.set('allow_zip', 'true'); }
      return this.request(torrent ? 'torrents/createtorrent' : 'webdl/createwebdownload', { method: 'POST', body });
    }
  }
  return { API, TYPES, Client, timestamp, dateLabel, bytes, accountInfo, bandwidthTotal, usageTotals, normalize, sortRows, files, browse, cleanPath, safeDownload, containsKey, redact };
});
