'use strict';
const $ = id => document.getElementById(id);
// Session-only: no query-string secrets, cookies, external scripts, or localStorage.
let token = sessionStorage.getItem('wanlu-token') || '';
let selected = null, kind = 'minutes', documentText = '', refreshing = false;
async function api(path, options = {}) {
  const response = await fetch(path, {...options, headers: {Authorization: `Bearer ${token}`}, cache: 'no-store'});
  if (response.status === 401) { logout(); throw new Error('口令不正确或已失效'); }
  if (!response.ok) throw new Error(response.status === 404 ? '这份内容尚未生成' : '请求失败，请稍后重试');
  return response;
}
function notice(message) { $('notice').textContent = message; }
function logout() {
  token = ''; sessionStorage.removeItem('wanlu-token');
  $('login').hidden = false; $('library').hidden = true; $('detail').hidden = true; $('logout').hidden = true;
  $('jobs').replaceChildren(); $('content').textContent = ''; selected = null; documentText = '';
}
async function refresh() {
  if (refreshing || !token) return;
  const activeToken = token;
  refreshing = true;
  try {
    const jobs = await (await api('/api/jobs')).json();
    if (token !== activeToken) return;
    $('login').hidden = true; $('logout').hidden = false; $('library').hidden = !!selected;
    $('jobs').replaceChildren();
    if (!jobs.length) { const p = document.createElement('p'); p.textContent = '还没有录音。连接手机热点，在手表上传第一段记录。'; $('jobs').append(p); }
    for (const job of jobs) {
      const article = document.createElement('article'); article.className = 'job';
      const pill = document.createElement('span'); pill.className = `pill ${job.status === 'error' ? 'error' : ''}`;
      pill.textContent = {done:'已完成',queued:'等待处理',running:'正在处理',error:'处理失败'}[job.status] || job.status;
      const title = document.createElement('h3'); title.textContent = job.name.replace(/\.m4a$/, '').replace(/^REC_/, '录音 ');
      const meta = document.createElement('p'); meta.textContent = `${new Date(job.created * 1000).toLocaleString('zh-CN')} · ${(job.size / 1048576).toFixed(1)} MB · ${job.step}`;
      article.append(pill, title, meta);
      if (job.status === 'done') { const button = document.createElement('button'); button.textContent = '查看记录 →'; button.onclick = () => openJob(job); article.append(button); }
      if (job.status === 'error') { const button = document.createElement('button'); button.textContent = '重新处理'; button.onclick = async () => {
        if (!confirm('重新处理会再次调用转写和纪要 API，可能产生费用。继续？')) return;
        try { await api(`/api/jobs/${job.id}/retry`, {method:'POST'}); await refresh(); } catch(e) { notice(e.message); }
      }; article.append(button); }
      $('jobs').append(article);
    }
    notice('');
  } catch (e) { notice(e.message); } finally { refreshing = false; }
}
async function openJob(job) {
  selected = job; $('library').hidden = true; $('detail').hidden = false; $('detail-title').textContent = job.name;
  await loadDocument('minutes');
}
async function loadDocument(next) {
  kind = next; documentText = ''; $('content').textContent = '加载中…'; $('download').disabled = true;
  const current = selected;
  try {
    const text = await (await api(`/api/jobs/${current.id}/${next}`)).text();
    if (selected !== current || kind !== next) return;
    documentText = text; $('content').textContent = text; $('download').disabled = false;
  } catch(e) { if (selected === current && kind === next) $('content').textContent = e.message; }
}
$('login-form').onsubmit = async event => { event.preventDefault(); token = $('token').value.trim(); await refresh(); if (token && $('login').hidden) { sessionStorage.setItem('wanlu-token', token); $('token').value = ''; } };
$('logout').onclick = logout; $('refresh').onclick = refresh;
$('back').onclick = () => { selected = null; $('detail').hidden = true; $('library').hidden = false; refresh(); };
$('show-minutes').onclick = () => loadDocument('minutes'); $('show-transcript').onclick = () => loadDocument('transcript');
$('download').onclick = () => {
  if (!documentText) return;
  const url = URL.createObjectURL(new Blob([documentText], {type:'text/markdown;charset=utf-8'}));
  const a = document.createElement('a'); a.href = url; a.download = kind === 'minutes' ? '会议纪要.md' : '完整转写.md'; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
};
if (token) refresh();
setInterval(() => { if (!document.hidden && !selected) refresh(); }, 15000);
