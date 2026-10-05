(function () {
  'use strict';
  const C = TorBoxCore, $ = id => document.getElementById(id), PAGE_SIZE = 100;
  const inFlightDownloads = new Set();
  if (!document.querySelector('meta[name="torbox-api"]')?.content) {
    $('api-key').disabled = true; $('connect').disabled = true; $('connect').textContent = 'Web activation pending';
    $('login-error').textContent = 'The web client is built, but its API relay still needs activation. Use the Android APK below in the meantime.'; $('login-error').hidden = false;
  }
  const S = { client: null, session: 0, revision: 0, nav: 0, refresh: 0, refreshing: false, view: 'all', rows: [], queue: [], item: null, files: [], folder: '', page: 0, selected: new Set(), deleted: new Set(), jobs: [], updated: null,
    account: null, bandwidth: null, usenet: [], loaded: new Set(), quotaUpdated: null, quotaStale: false, density: 'compact' };
  const views = { all: 'All files', active: 'Active', finished: 'Finished', queue: 'Queue', airlock: 'AirLock', downloads: 'Device downloads' };
  function el(tag, attrs = {}, text = '') {
    const n = document.createElement(tag);
    for (const [key, value] of Object.entries(attrs)) {
      if (key.startsWith('on')) n.addEventListener(key.slice(2), value);
      else if (key === 'class') n.className = value;
      else if (value !== false && value != null) n.setAttribute(key, value === true ? '' : String(value));
    }
    if (text !== '') n.textContent = String(text); return n;
  }
  function button(text, action, cls = '') { return el('button', { type: 'button', class: cls, onclick: action }, text); }
  function notice(text, kind = '') { $('notice').textContent = text; $('notice').className = 'notice ' + kind; $('notice').hidden = !text; }
  function live(session, client) { return session === S.session && client === S.client; }
  function clearSession(message = '') {
    S.client?.clear(); S.client = null; S.session++; S.nav++; S.refresh++; S.refreshing = false;
    for (const job of S.jobs) { job.controller?.abort(); if (job.url?.startsWith('blob:')) URL.revokeObjectURL(job.url); }
    inFlightDownloads.clear();
    S.rows = []; S.queue = []; S.files = []; S.jobs = []; S.item = null; S.folder = ''; S.selected.clear(); S.deleted.clear(); S.updated = null;
    S.account = null; S.bandwidth = null; S.usenet = []; S.loaded.clear(); S.quotaUpdated = null; S.quotaStale = false;
    setDensity('compact');
    $('quota-cards').replaceChildren(); $('quota-status').textContent = '';
    $('api-key').value = ''; $('rows').replaceChildren(); $('jobs').replaceChildren(); $('dialog-body').replaceChildren(); $('dialog').close(); $('account').textContent = ''; $('file-meta').textContent = '';
    $('app').hidden = true; $('login').hidden = false; $('login-error').textContent = message; $('login-error').hidden = !message; $('connect').disabled = false; $('connect').textContent = 'Unlock my TorBox';
    $('api-key').focus();
  }
  async function guarded(work, { node, errorNode } = {}) {
    const session = S.session, client = S.client;
    if (!client || node?.disabled) return;
    if (node) node.disabled = true;
    try { return await work(client, () => live(session, client)); }
    catch (e) { if (live(session, client)) { if (e.auth) clearSession(e.message); else if (errorNode) { errorNode.textContent = e.message; errorNode.hidden = false; } else notice(e.message, 'error'); } }
    finally { if (node?.isConnected) node.disabled = false; }
  }
  $('login-form').addEventListener('submit', async e => {
    e.preventDefault(); if ($('connect').disabled) return;
    let client; try { client = new C.Client($('api-key').value); } catch (error) { $('login-error').textContent = error.message; $('login-error').hidden = false; return; }
    $('api-key').value = ''; S.client?.clear(); S.client = client; const session = ++S.session;
    $('connect').disabled = true; $('connect').textContent = 'Connecting (may take a minute)…'; $('login-error').hidden = true;
    try {
      const account = await client.me(); if (!live(session, client)) return;
      S.account = C.accountInfo(account); $('account').textContent = S.account.email;
      $('login').hidden = true; $('app').hidden = false; S.view = 'all'; S.page = 0; $('search').value = ''; $('sort').value = 'added:desc'; $('type').value = ''; render(); await refresh(false, true);
    } catch (error) { if (live(session, client)) clearSession(error.message); }
    finally { $('connect').disabled = false; $('connect').textContent = 'Unlock my TorBox'; }
  });
  $('sign-out').onclick = () => clearSession();
  window.addEventListener('pagehide', () => clearSession());
  window.addEventListener('pageshow', event => { if (event.persisted) clearSession(); });
  function rootRows() {
    return (S.view === 'queue' ? S.queue : S.rows).filter(r => !S.deleted.has(r.key)).filter(r => S.view === 'active' ? !r.ready : S.view === 'finished' ? r.ready : S.view === 'airlock' ? r.airlocked : true);
  }
  async function refresh(manual = false, initial = false) {
    if (!S.client || S.refreshing) return;
    const session = S.session, client = S.client, serial = ++S.refresh, revision = S.revision;
    S.refreshing = true; $('refresh').disabled = true; $('refresh').textContent = 'Refreshing…'; renderQuotas();
    try {
      const pairs = [['torrent', false], ['webdl', false], ['torrent', true], ['webdl', true]];
      const item = S.item, nav = S.nav, oldAccount = S.account;
      const account = initial ? Promise.resolve(S.account) : client.me().then(C.accountInfo);
      const usenet = account.catch(() => oldAccount).then(info => info?.code === 2 ? client.usenetUsage(manual) : info?.code != null ? [] : Promise.reject(new Error('Usenet usage needs a reported plan.')));
      const results = await Promise.allSettled([account, client.stats(), usenet, ...pairs.map(([type, queued]) => client.list(type, queued, manual)), item ? client.detail(item) : Promise.resolve(null)]);
      if (!live(session, client) || serial !== S.refresh || revision !== S.revision) return;
      // Check every auth failure before applying any of this refresh's private data.
      for (const r of results) if (r.status === 'rejected' && r.reason.auth) throw r.reason;
      const failed = [];
      S.quotaStale = results.slice(0, 7).some(r => r.status === 'rejected');
      if (results[0].status === 'fulfilled') { S.account = results[0].value; $('account').textContent = S.account.email; }
      if (results[1].status === 'fulfilled') { S.bandwidth = C.bandwidthTotal(results[1].value); S.loaded.add('bandwidth'); }
      if (results[2].status === 'fulfilled') { S.usenet = results[2].value; S.loaded.add('usenet'); }
      pairs.forEach(([type, queued], i) => {
        const r = results[i + 3];
        if (r.status === 'rejected') { failed.push((type === 'torrent' ? 'Torrents' : 'Web downloads') + (queued ? ' queue' : '') + ': ' + r.reason.message); return; }
        const field = queued ? 'queue' : 'rows';
        S[field] = S[field].filter(row => row.type !== type).concat(r.value.filter(row => !S.deleted.has(row.key)));
        S.loaded.add(type + (queued ? '-queue' : ''));
      });
      const detail = results[7];
      if (item && S.item?.key === item.key && nav === S.nav) {
        if (detail.status === 'fulfilled') { S.item = detail.value.item; S.files = detail.value.files; pruneSelection(); updateFileFilters(); }
        else failed.push(detail.reason.message);
      }
      const accountFailed = results.slice(0, 3).some(r => r.status === 'rejected');
      notice(failed.length ? 'Some lists could not be refreshed. ' + failed.join(' ') : accountFailed ? 'Account usage could not be refreshed. Last known values are shown; try Refresh again.' : '', failed.length || accountFailed ? 'error' : '');
      S.updated = Date.now(); if (!S.quotaStale) S.quotaUpdated = S.updated; render();
    } catch (error) { if (live(session, client)) { if (error.auth) clearSession(error.message); else notice(error.message, 'error'); } }
    finally { if (live(session, client) && serial === S.refresh) { S.refreshing = false; $('refresh').disabled = false; $('refresh').textContent = 'Refresh'; renderQuotas(); } }
  }
  $('refresh').onclick = () => refresh(true);
  setInterval(() => { if (S.client && document.visibilityState === 'visible' && !S.item && !$('dialog').open && S.view !== 'downloads') refresh(); }, 60000);
  for (const b of document.querySelectorAll('[data-view]')) b.onclick = () => {
    S.nav++; S.item = null; S.files = []; S.folder = ''; S.view = b.dataset.view; S.selected.clear(); S.page = 0; $('search').value = ''; $('sort').value = 'added:desc'; notice(''); render();
  };
  for (const id of ['search', 'type', 'sort', 'extension']) $(id).addEventListener(id === 'search' ? 'input' : 'change', () => { S.page = 0; render(); });
  function setDensity(value) {
    S.density = ['compact', 'cozy', 'detailed'].includes(value) ? value : 'compact';
    $('app').dataset.density = S.density; $('density').value = S.density;
  }
  $('density').onchange = () => setDensity($('density').value);
  $('prev').onclick = () => { S.page = Math.max(0, S.page - 1); render(); };
  $('next').onclick = () => { S.page++; render(); };
  $('clear-selection').onclick = () => { S.selected.clear(); render(); };
  function counts() {
    for (const [view, count] of Object.entries({ all: S.rows.length, active: S.rows.filter(r => !r.ready).length, finished: S.rows.filter(r => r.ready).length, queue: S.queue.length, airlock: S.rows.filter(r => r.airlocked).length, downloads: S.jobs.length })) $('count-' + view).textContent = count.toLocaleString();
  }
  function renderQuotas() {
    if (!S.client || !S.account) return;
    const a = S.account, loaded = ['torrent', 'webdl', 'usenet'].every(k => S.loaded.has(k));
    const usage = C.usageTotals(loaded ? [...S.rows, ...S.usenet] : null), bandwidth = S.bandwidth;
    const queue = ['torrent-queue', 'webdl-queue'].every(k => S.loaded.has(k)) ? S.queue.length : null;
    const count = n => n == null ? 'Not reported' : n.toLocaleString();
    const card = (id, label, value, detail, used = null, limit = null) => {
      const box = el('div', { class: 'quota-card', id });
      box.append(el('span', { class: 'quota-label' }, label), el('strong', { class: 'quota-value' }, value), el('span', { class: 'quota-detail' }, detail));
      if (used != null && limit > 0) box.append(el('progress', { max: limit, value: Math.min(used, limit), 'aria-label': label + ' usage', title: C.bytes(used) + ' / ' + C.bytes(limit) }));
      return box;
    };
    const expiry = a.code === 0 ? 'Free plan · 1 add/day, 10/month' : a.expires == null ? 'Expiry not reported' : (a.expires <= Date.now() ? 'Expired ' : 'Expires ') + new Date(a.expires).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
    const slots = usage.active == null ? 'Not reported' : count(usage.active) + (a.slots != null ? ' / ' + count(a.slots) : ' active');
    const slotDetail = a.slots == null ? 'Slot allowance not reported' : 'Plan limit' + (a.extraSlots ? ' + reported extra slots' : '') + ' · Downloading + seeding';
    const airlock = C.bytes(usage.airlock), airlockDetail = a.airlockLimit == null ? 'Allowance not reported' : 'of ' + C.bytes(a.airlockLimit) + ' plan allowance' + (usage.airlock != null ? ' · ' + C.bytes(Math.max(0, a.airlockLimit - usage.airlock)) + ' free' : '');
    const bandwidthDetail = a.bandwidthBaseline == null ? 'Dynamic fair use; baseline not reported' : C.bytes(a.bandwidthBaseline) + ' fair-use baseline · Dynamic threshold';
    $('quota-cards').replaceChildren(card('quota-plan', 'Your plan', a.plan, expiry), card('quota-slots', 'Active slots', slots, slotDetail), card('quota-airlock', 'AirLock storage', airlock, airlockDetail, usage.airlock, a.airlockLimit), card('quota-bandwidth', 'Bandwidth · past 30 days', C.bytes(bandwidth), bandwidthDetail, bandwidth, a.bandwidthBaseline), card('quota-queue', 'Queued items', count(queue), 'Torrent + web download queue'));
    $('quota-status').textContent = S.refreshing ? 'Refreshing usage…' : S.quotaStale ? 'Some usage could not refresh. Last known values shown.' : S.quotaUpdated ? 'Updated ' + new Date(S.quotaUpdated).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }) : 'Loading usage…';
    $('quota-status').classList.toggle('quota-stale', S.quotaStale);
  }
  function dateCell(value) { const d = C.dateLabel(value), td = el('td', { class: 'date date-column', title: d.full }); td.append(el('span', {}, d.date), el('span', { class: 'sub' }, d.age)); return td; }
  function header(label, field, cls = '') { const th = el('th', { scope: 'col', class: cls }); if (field) { const [sort, dir] = $('sort').value.split(':'); th.setAttribute('aria-sort', sort === field ? dir === 'asc' ? 'ascending' : 'descending' : 'none'); th.append(button(label + (sort === field ? dir === 'asc' ? ' ↑' : ' ↓' : ''), () => { $('sort').value = `${field}:${sort === field && dir === 'asc' ? 'desc' : 'asc'}`; S.page = 0; render(); })); } else th.textContent = label; return th; }
  function render() {
    if (!S.client) return;
    counts(); renderQuotas(); for (const b of document.querySelectorAll('[data-view]')) b.setAttribute('aria-current', b.dataset.view === S.view ? 'page' : 'false');
    $('view-title').textContent = S.item ? S.item.name : views[S.view];
    $('view-subtitle').textContent = S.item ? 'Browse folders and choose files' : S.view === 'downloads' ? 'Downloads started during this session' : 'Your torrents and web downloads';
    const jobs = S.view === 'downloads' && !S.item;
    $('jobs').hidden = !jobs; $('list-panel').hidden = jobs; $('toolbar').hidden = jobs; $('date-note').hidden = jobs;
    $('file-table').classList.toggle('library-table', !S.item);
    $('column-hint').hidden = !!S.item;
    $('type').hidden = !!S.item; $('extension').hidden = !S.item; $('breadcrumbs').hidden = !S.item; $('file-meta').hidden = !S.item; $('file-actions').hidden = !S.item; $('search').placeholder = S.item ? 'Search this folder and subfolders' : 'Search names and tags';
    $('selection').hidden = !S.item || S.selected.size === 0; $('selected-count').textContent = S.selected.size + ' selected';
    for (const option of $('sort').options) option.disabled = !!S.item && /^(added|cachedAt):/.test(option.value);
    if (jobs) { renderJobs(); return; }
    if (S.item) renderBreadcrumbs();
    const [field, direction] = $('sort').value.split(':'), q = $('search').value.trim().toLocaleLowerCase();
    let rows = S.item ? C.browse(S.files, S.folder, q, $('extension').value, field, direction) : C.sortRows(rootRows().filter(r => (!$('type').value || r.type === $('type').value) && (!q || (r.name + ' ' + r.tags.join(' ')).toLocaleLowerCase().includes(q))), field, direction);
    const pages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE)); S.page = Math.min(S.page, pages - 1);
    const head = el('tr');
    if (S.item) { const th = header('', null, 'check-cell'), check = el('input', { type: 'checkbox', 'aria-label': 'Select visible files' }); const visible = rows.slice(S.page * PAGE_SIZE, (S.page + 1) * PAGE_SIZE).filter(r => !r.folder && !r.infected); check.checked = visible.length > 0 && visible.every(f => S.selected.has(f.id)); check.disabled = !visible.length; check.onchange = () => { visible.forEach(f => check.checked ? S.selected.add(f.id) : S.selected.delete(f.id)); render(); }; th.append(check); head.append(th); }
    head.append(header('Name', 'name', 'name-column'));
    if (!S.item) head.append(header('Added', 'added', 'date-column'), header('Cached', 'cachedAt', 'date-column'));
    head.append(header('Size', 'size', 'size-column'), header(S.item ? 'Type' : 'Status', null, 'state-column'), header('Actions', null, 'actions-column')); $('table-head').replaceChildren(head);
    const fragment = document.createDocumentFragment();
    for (const row of rows.slice(S.page * PAGE_SIZE, (S.page + 1) * PAGE_SIZE)) fragment.append(S.item ? fileRow(row) : itemRow(row));
    $('rows').replaceChildren(fragment); $('empty').hidden = rows.length > 0;
    $('empty').replaceChildren(el('strong', {}, q || $('type').value || (S.item && $('extension').value) ? 'No matches' : S.item ? 'This folder has no files' : S.refreshing ? 'Loading your files…' : 'Nothing here yet'), el('span', { class: 'muted' }, q ? 'Try a different search or clear your filters.' : 'Refresh to check TorBox for updates.'));
    $('list-summary').textContent = rows.length.toLocaleString() + (S.item ? ' entries' : ' items') + (S.updated ? ' · Updated ' + new Date(S.updated).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }) : '');
    $('page-label').textContent = `${S.page + 1} / ${pages}`; $('prev').disabled = S.page === 0; $('next').disabled = S.page >= pages - 1;
  }
  function itemRow(item) {
    const meta = (item.type === 'torrent' ? 'Torrent' : 'Web download') + (item.tags.length ? ' · ' + item.tags.join(', ') : '') + (item.airlocked ? ' · AirLocked' : '');
    const tr = el('tr'), name = el('td', { class: 'name-cell', title: item.name + ' · ' + meta }), entry = el('div', { class: 'entry' }), text = el('div', { class: 'entry-name' });
    const open = button(item.name, () => item.queued ? details(item) : openFiles(item), 'name-button'); open.title = item.name;
    text.append(open, el('span', { class: 'sub', title: meta }, meta));
    entry.append(el('span', { class: 'entry-icon', 'aria-hidden': 'true' }, '▤'), text); name.append(entry);
    const state = el('td', { class: 'state-column' }), info = el('div', { class: 'status-info', title: item.state }); info.append(el('span', { class: 'state' + (item.ready ? ' ready' : /fail|error|missing|expired/i.test(item.state) ? ' problem' : '') }, item.state));
    if (!item.ready && !item.queued && item.progress != null) {
      const summary = Math.round(item.progress * 100) + '%' + (item.download_speed > 0 ? ' · ' + C.bytes(item.download_speed) + '/s' : ''), progress = el('div', { class: 'progress-meta' });
      info.title = item.state + ' · ' + summary;
      progress.append(el('progress', { max: 1, value: item.progress, 'aria-label': 'Download progress' }), el('span', { class: 'sub', title: summary }, summary)); info.append(progress);
    }
    state.append(info);
    tr.append(name, dateCell(item.added), dateCell(item.cachedAt), el('td', { class: 'size-column' }, C.bytes(item.size)), state);
    const actions = el('td', { class: 'actions-column' }), wrap = el('div', { class: 'row-actions' });
    wrap.append(button(item.queued ? 'Start' : 'Files', e => item.queued ? control(item, 'start', e.currentTarget) : openFiles(item)), button('Details', () => details(item)));
    actions.append(wrap); tr.append(actions); return tr;
  }
  function fileRow(file) {
    const tr = el('tr'), select = el('td', { class: 'check-cell' });
    if (!file.folder) { const check = el('input', { type: 'checkbox', 'aria-label': 'Select ' + file.name }); check.checked = S.selected.has(file.id); check.disabled = file.infected; check.onchange = () => { check.checked ? S.selected.add(file.id) : S.selected.delete(file.id); render(); }; select.append(check); }
    const name = el('td', { class: 'name-cell', title: file.path + (file.infected ? ' · TorBox flagged this file as infected' : '') }), entry = el('div', { class: 'entry' }), text = el('div', { class: 'entry-name' });
    const filename = file.folder ? button(file.name, () => { S.folder = file.path; S.page = 0; $('search').value = ''; render(); }, 'name-button') : el('span', { class: 'name-button' }, file.name); filename.title = file.name; text.append(filename);
    if ($('search').value || $('extension').value) text.append(el('span', { class: 'sub', title: file.path }, file.path));
    if (file.folder) text.append(el('span', { class: 'sub' }, file.count + ' files'));
    if (file.infected) text.append(el('span', { class: 'state problem', title: 'TorBox flagged this file as infected' }, 'TorBox flagged this file as infected'));
    entry.append(el('span', { class: 'entry-icon', 'aria-hidden': 'true' }, file.folder ? '▰' : '▤'), text); name.append(entry);
    tr.append(select, name, el('td', { class: 'size-column' }, C.bytes(file.size)), el('td', { class: 'state-column' }, file.folder ? 'Folder' : file.extension.toUpperCase() || 'File'));
    const actions = el('td', { class: 'actions-column' }), wrap = el('div', { class: 'row-actions' });
    if (file.folder) wrap.append(button('Open', () => { S.folder = file.path; S.page = 0; render(); }));
    else {
      const dl = button('Download', e => startDownload(S.item, file, false, e.currentTarget)); dl.disabled = !S.item.ready; wrap.append(dl);
      const more = button('', () => fileDetails(file), 'file-more'); more.setAttribute('aria-label', 'More'); more.title = 'More file actions';
      more.append(el('span', { class: 'more-label' }, 'More'), el('span', { class: 'more-icon', 'aria-hidden': 'true' }, '⋯')); more.disabled = !S.item.ready; wrap.append(more);
    }
    actions.append(wrap); tr.append(actions); return tr;
  }
  function pruneSelection() { const available = new Set(S.files.filter(f => !f.infected).map(f => f.id)); S.selected = new Set([...S.selected].filter(id => available.has(id))); }
  async function openFiles(item) {
    const session = S.session, nav = ++S.nav; notice('Loading files…');
    await guarded(async (client, active) => {
      const result = await client.detail(item); if (!active() || nav !== S.nav || session !== S.session) return;
      S.item = result.item; S.files = result.files; S.folder = ''; S.page = 0; S.selected.clear(); $('search').value = ''; $('sort').value = 'name:asc'; updateFileFilters(); notice(''); render();
    });
  }
  function updateFileFilters() {
    const old = $('extension').value; $('extension').replaceChildren(el('option', { value: '' }, 'All file types'));
    for (const ext of [...new Set(S.files.map(f => f.extension).filter(Boolean))].sort()) $('extension').append(el('option', { value: ext }, ext.toUpperCase()));
    $('extension').value = [...$('extension').options].some(o => o.value === old) ? old : '';
  }
  function renderBreadcrumbs() {
    const box = $('breadcrumbs'); box.replaceChildren(button('All files', () => { S.nav++; S.item = null; S.view = 'all'; S.files = []; S.selected.clear(); S.page = 0; $('search').value = ''; $('sort').value = 'added:desc'; notice(''); render(); }));
    box.append(el('span', { class: 'crumb-divider', 'aria-hidden': 'true' }, '/'), button('Files', () => { S.folder = ''; S.page = 0; $('search').value = ''; render(); }));
    const parts = S.folder.split('/').filter(Boolean);
    parts.forEach((p, i) => box.append(el('span', { class: 'crumb-divider', 'aria-hidden': 'true' }, '/'), button(p, () => { S.folder = parts.slice(0, i + 1).join('/'); S.page = 0; $('search').value = ''; render(); })));
    const a = C.dateLabel(S.item.added), c = C.dateLabel(S.item.cachedAt);
    $('file-meta').textContent = `Added: ${a.full}${a.age ? ' (' + a.age + ')' : ''} · Cached: ${c.full}${c.age ? ' (' + c.age + ')' : ''}`;
    $('file-actions').replaceChildren();
    if (S.item.allow_zipped === true && S.item.type === 'torrent') { const b = button('Download whole ZIP', e => startDownload(S.item, null, true, e.currentTarget)); b.disabled = !S.item.ready || S.files.some(f => f.infected); $('file-actions').append(b); }
    $('file-actions').append(button('Item details', () => details(S.item)));
    $('file-actions').style.marginBottom = '14px';
  }
  function modal(title) { $('dialog-title').textContent = title; $('dialog-body').replaceChildren(); if (!$('dialog').open) $('dialog').showModal(); return $('dialog-body'); }
  $('close-dialog').onclick = () => $('dialog').close();
  $('dialog').addEventListener('close', () => { $('dialog-body').replaceChildren(); });
  function modalError(body) { const n = el('p', { class: 'notice error', role: 'alert', hidden: true }); body.append(n); return n; }
  function details(item) {
    const body = modal(item.name), dl = el('dl');
    for (const [k, v] of [['Status', item.state], ['Type', item.type === 'torrent' ? 'Torrent' : 'Web download'], ['Size', C.bytes(item.size)], ['Added', C.dateLabel(item.added).full], ['Cached', C.dateLabel(item.cachedAt).full], ['Updated', C.dateLabel(item.updated).full], ['Tags', item.tags.join(', ') || 'None'], ['AirLock', item.airlocked ? 'Protected' : 'Not protected']]) dl.append(el('dt', {}, k), el('dd', {}, v));
    body.append(dl); const error = modalError(body), actions = el('div', { class: 'actions' });
    if (item.queued) actions.append(button('Start', e => control(item, 'start', e.currentTarget, error)));
    else {
      actions.append(button('Browse files', () => { $('dialog').close(); openFiles(item); }));
      if (item.type === 'torrent') for (const op of ['pause', 'resume', 'reannounce']) actions.append(button(op[0].toUpperCase() + op.slice(1), e => control(item, op, e.currentTarget, error)));
      if (item.ready) { actions.append(button('Rename / tags', () => editDialog(item)), button(item.airlocked ? 'Remove AirLock' : 'Add AirLock', e => editItem(item, { airlocked: !item.airlocked }, e.currentTarget, error))); }
    }
    actions.append(button('Delete', () => deleteDialog(item), 'danger')); body.append(actions);
  }
  async function control(item, operation, node, errorNode) {
    await guarded(async (client, active) => {
      await client.control(item, operation); if (!active()) return; S.revision++;
      if (operation === 'delete' || item.queued && operation === 'start') { S.deleted.add(item.key); S.rows = S.rows.filter(r => r.key !== item.key); S.queue = S.queue.filter(r => r.key !== item.key); if (S.item?.key === item.key) { S.item = null; S.files = []; S.selected.clear(); } }
      $('dialog').close(); notice(operation === 'delete' ? 'Deleted from TorBox.' : operation === 'start' ? 'TorBox accepted the start request. The item may take a moment to appear.' : 'TorBox accepted the ' + operation + ' request.', 'success'); render(); refresh(true);
    }, { node, errorNode });
  }
  function deleteDialog(item) {
    const body = modal('Delete from TorBox?'); body.append(el('p', {}, `Delete “${item.name}”? This removes it from your TorBox account. Files already saved to your device are unaffected.`)); const error = modalError(body), actions = el('div', { class: 'dialog-actions' }); actions.append(button('Cancel', () => $('dialog').close()), button('Delete', e => control(item, 'delete', e.currentTarget, error), 'danger')); body.append(actions);
  }
  function labeled(body, label, input) { const wrap = el('label', { class: 'field' }, label); wrap.append(input); body.append(wrap); return input; }
  function editDialog(item) {
    const body = modal('Rename / tags'), name = labeled(body, 'Name', el('input', { value: item.name, maxlength: 500 })), tags = labeled(body, 'Tags (separate with commas)', el('input', { value: item.tags.join(', '), maxlength: 2000 }));
    const error = modalError(body); body.append(button('Save', e => { if (!name.value.trim()) { error.textContent = 'Enter a name.'; error.hidden = false; return; } editItem(item, { name: name.value.trim(), tags: [...new Set(tags.value.split(',').map(x => x.trim()).filter(Boolean))] }, e.currentTarget, error); }, 'primary'));
  }
  async function editItem(item, changes, node, errorNode) {
    await guarded(async (client, active) => { const updated = await client.edit(item, changes); if (!active()) return; S.revision++; S.rows = S.rows.map(r => r.key === item.key ? updated : r); if (S.item?.key === item.key) S.item = updated; $('dialog').close(); notice('Saved on TorBox.', 'success'); render(); refresh(true); }, { node, errorNode });
  }
  $('add').onclick = () => {
    const body = modal('Add to TorBox'), form = el('form'); body.append(form);
    const value = labeled(form, 'Magnet or download link', el('textarea', { placeholder: 'magnet:?… or https://…', spellcheck: false }));
    const file = labeled(form, 'Or choose a .torrent file', el('input', { type: 'file', accept: '.torrent,application/x-bittorrent' }));
    const name = labeled(form, 'Custom name (optional)', el('input', { maxlength: 500 }));
    const seed = el('select'); for (const [v, t] of [[1, 'Automatic seeding'], [0, 'Do not seed'], [2, 'Seed until stopped']]) seed.append(el('option', { value: v }, t)); labeled(form, 'Torrent seeding', seed);
    const queued = el('input', { type: 'checkbox' }), cached = el('input', { type: 'checkbox' });
    for (const [input, text] of [[queued, 'Add to queue'], [cached, 'Only add if cached']]) { const l = el('label', { class: 'check' }); l.append(input, document.createTextNode(text)); form.append(l); }
    const error = modalError(form), submit = el('button', { type: 'submit', class: 'primary' }, 'Add to TorBox'); form.append(submit);
    form.onsubmit = e => { e.preventDefault(); if (file.files.length && value.value.trim()) { error.textContent = 'Choose either a link or a .torrent file.'; error.hidden = false; return; }
      guarded(async (client, active) => { const result = await client.add({ value: value.value.trim(), file: file.files[0], name: name.value, queued: queued.checked, cached: cached.checked, seed: Number(seed.value) }); if (!active()) return; S.revision++;
        const n = result?.torrent_id ?? result?.webdownload_id ?? result?.web_id ?? result?.id; if (n != null) { S.deleted.delete('torrent:' + n); S.deleted.delete('webdl:' + n); }
        $('dialog').close(); notice('TorBox accepted the addition. Refresh if it does not appear immediately.', 'success'); refresh(true);
      }, { node: submit, errorNode: error }); };
  };
  function fileDetails(file) {
    const item = S.item, body = modal(file.name); body.append(el('p', { class: 'muted' }, file.path), el('p', {}, C.bytes(file.size))); const error = modalError(body);
    if (file.infected) { body.append(el('p', { class: 'notice error' }, 'TorBox flagged this file as infected. Open, copy, and share are disabled.')); return; }
    const actions = el('div', { class: 'actions' });
    for (const action of ['Open', 'Copy link', 'Share']) actions.append(button(action, e => guarded(async (client, active) => {
      const link = await client.link(item, file); if (!active()) return;
      if (link.credentialBearing) { error.textContent = 'TorBox included your API key in this link. Copying, sharing, or opening it in another app could expose your key. Use Download instead.'; error.hidden = false; return; }
      if (action === 'Copy link') { await navigator.clipboard.writeText(link.url); if (active()) { error.className = 'notice success'; error.textContent = 'Temporary link copied.'; error.hidden = false; } }
      else if (action === 'Share' && navigator.share) { try { await navigator.share({ title: file.name, url: link.url }); } catch (e) { if (e.name !== 'AbortError') throw new Error('Your browser could not share this link. Try Copy link.'); } }
      else { const a = el('a', { class: 'button', href: link.url, target: '_blank', rel: 'noopener noreferrer' }, action === 'Share' ? 'Open temporary link' : 'Open file'); body.append(a); }
    }, { node: e.currentTarget, errorNode: error })));
    body.append(actions);
  }
  function addJob(name) {
    const job = { id: crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random(), name, status: 'Preparing a fresh download link…', busy: true, controller: new AbortController() }; S.jobs.unshift(job); counts(); return job;
  }
  async function startDownload(item, file, zip, node, forceBrowser = false) {
    if (!item || node?.disabled) return;
    if (file?.infected && !window.confirm('TorBox flagged this file as infected. Download it anyway?')) return;
    if (zip && S.files.some(f => f.infected)) { notice('ZIP download is blocked because a file was flagged as infected.', 'error'); return; }
    const downloadKey = item.key + ':' + (zip ? 'zip' : file.id);
    if (inFlightDownloads.has(downloadKey)) { notice('This file already has a download being prepared.'); return; }
    inFlightDownloads.add(downloadKey);
    const session = S.session, client = S.client;
    const name = zip ? item.name + '.zip' : file.name;
    // Invoke the picker immediately while the original click still has user activation.
    let handlePromise = null;
    if (!forceBrowser && window.showSaveFilePicker) handlePromise = window.showSaveFilePicker({ suggestedName: name.replace(/[\\/:*?"<>|\x00-\x1f]/g, '_').slice(0, 180) });
    if (node) node.disabled = true;
    const job = addJob(name); notice('Preparing “' + name + '”…');
    try {
      let handle;
      if (handlePromise) { try { handle = await handlePromise; } catch (e) { if (e.name === 'AbortError') { job.status = 'Cancelled.'; return; } throw new Error('Could not open the save dialog. Try the browser download option in Device downloads.'); } }
      if (!live(session, client)) return;
      const fresh = await client.detail(item); if (!live(session, client)) return;
      const freshFile = file && fresh.files.find(f => f.id === file.id);
      if (file && !freshFile) throw new Error('This file is no longer available. Refresh its folder.');
      if (freshFile?.infected && !file.infected) throw new Error('TorBox now flags this file as infected. Refresh and review the warning before downloading.');
      if (zip && fresh.files.some(f => f.infected)) throw new Error('ZIP download is blocked because a file was flagged as infected.');
      const link = await client.link(fresh.item, freshFile, zip); if (!live(session, client)) return;
      if (job.controller.signal.aborted) { job.status = 'Cancelled.'; return; }
      job.link = link; job.retry = () => startDownload(item, file, zip, null, true);
      if (handle) {
        let writer;
        try {
          writer = await handle.createWritable();
          const response = await fetch(link.url, { credentials: 'omit', cache: 'no-store', referrerPolicy: 'no-referrer', signal: job.controller.signal, redirect: 'error' });
          if (!response.ok || !response.body) throw new Error('The file server rejected this download.');
          const reader = response.body.getReader(), total = Number(response.headers.get('Content-Length')) || freshFile?.size || 0; let done = 0, last = 0;
          while (true) {
            const chunk = await reader.read(); if (chunk.done) break;
            if (!live(session, client)) { await reader.cancel(); throw new Error('Session ended.'); }
            await writer.write(chunk.value); done += chunk.value.byteLength;
            if (Date.now() - last > 300) { job.status = 'Saving ' + C.bytes(done) + (total ? ' / ' + C.bytes(total) : ''); last = Date.now(); if (S.view === 'downloads') renderJobs(); }
          }
          if (total > 0 && done !== total) throw new Error('The transfer ended before the complete file arrived. Please retry.');
          await writer.close(); writer = null; job.status = 'Saved to your device.'; job.done = true; job.link = null;
        } catch (error) {
          if (writer) await writer.abort().catch(() => {});
          if (!live(session, client)) return;
          if (job.controller.signal.aborted) { job.status = 'Cancelled.'; job.link = null; return; }
          throw new Error('Direct save could not finish. The file server may block browser streaming. Use “Browser download” in Device downloads, or retry.');
        }
      } else {
        // No in-memory Blob: large files go straight from TorBox to the browser download manager.
        if (link.credentialBearing) {
          job.status = 'Ready. TorBox included your API key in the file link. Your browser may retain that link in its download history.';
          job.needsConsent = true;
        } else { browserSave(job); }
      }
      if (live(session, client)) { notice(job.done ? 'Saved “' + name + '”.' : 'Download ready. Open Device downloads to save the file and view its status.', 'success'); S.view = 'downloads'; S.item = null; S.files = []; S.selected.clear(); render(); }
    } catch (error) { if (live(session, client)) { job.status = error.message; job.failed = true; job.retry ||= () => startDownload(item, file, zip, null, true); if (error.auth) clearSession(error.message); else { notice(error.message, 'error'); S.view = 'downloads'; S.item = null; render(); } } }
    finally { inFlightDownloads.delete(downloadKey); job.busy = false; if (node?.isConnected) node.disabled = false; if (live(session, client)) { counts(); if (S.view === 'downloads') renderJobs(); } }
  }
  function browserSave(job) {
    const a = el('a', { href: job.link.url, download: job.name, target: '_blank', rel: 'noopener noreferrer' }); document.body.append(a); a.click(); a.remove();
    job.needsConsent = false; job.status = 'Sent to your browser. Check its download list for progress. If it did not start, use Save file below.';
  }
  function renderJobs() {
    $('jobs').replaceChildren();
    if (!S.jobs.length) { const empty = el('div', { class: 'empty' }); empty.append(el('strong', {}, 'No device downloads yet'), el('span', { class: 'muted' }, 'Choose a file, then Download.')); $('jobs').append(empty); return; }
    for (const job of S.jobs) {
      const row = el('div', { class: 'download-job' }), actions = el('div', { class: 'actions' }); row.append(el('strong', {}, job.name), el('p', { class: 'status', role: 'status' }, job.status));
      if (job.busy) actions.append(button('Cancel', () => { job.controller.abort(); job.status = 'Cancelling…'; renderJobs(); }));
      else if (job.needsConsent) actions.append(button('Continue with browser download', () => { browserSave(job); renderJobs(); }));
      else if (job.link && !job.failed) actions.append(el('a', { class: 'button', href: job.link.url, download: job.name, target: '_blank', rel: 'noopener noreferrer' }, 'Save file'));
      if (!job.busy && !job.done && job.retry) actions.append(button(job.failed ? 'Browser download' : 'Get a fresh link', job.retry));
      row.append(actions); $('jobs').append(row);
    }
  }
  $('download-selected').onclick = async e => {
    const item = S.item, files = S.files.filter(f => S.selected.has(f.id) && !f.infected);
    if (!item || !files.length) return;
    // Individual save links avoid browser multi-download blocking and huge ZIPs.
    const node = e.currentTarget; node.disabled = true; const session = S.session, client = S.client;
    try {
      const fresh = await client.detail(item);
      for (const f of files) {
        if (!live(session, client)) break;
        const actual = fresh.files.find(x => x.id === f.id); if (!actual || actual.infected) throw new Error('A selected file changed or was flagged as infected. Refresh and review it.');
        const job = addJob(actual.name);
        try { job.link = await client.link(fresh.item, actual); if (!live(session, client)) break; job.needsConsent = job.link.credentialBearing; job.status = job.needsConsent ? 'TorBox included your API key in this link. Browser download history may retain it.' : 'Ready to save. Use Save file.'; job.retry = () => startDownload(item, actual, false, null, true); }
        catch (error) { job.status = error.message; job.failed = true; throw error; }
        finally { job.busy = false; }
      }
      if (live(session, client)) { S.view = 'downloads'; S.item = null; S.selected.clear(); notice('Save each selected file from the list below.', 'success'); render(); }
    } catch (error) { if (live(session, client)) { if (error.auth) clearSession(error.message); else { S.view = 'downloads'; S.item = null; notice(error.message, 'error'); render(); } } }
    finally { node.disabled = false; }
  };
  document.addEventListener('keydown', event => { if (event.key === 'Backspace' && S.item && !$('dialog').open && !['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement?.tagName)) { event.preventDefault(); S.folder = S.folder.split('/').slice(0, -1).join('/'); S.page = 0; render(); } });
})();
