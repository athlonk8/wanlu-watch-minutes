'use strict';
const status = document.getElementById('status');
let expiryTimer;
async function loadRecording() {
  const response = await fetch('/api/recording', {cache:'no-store'});
  if (!response.ok) {
    document.getElementById('pair').hidden = false;
    document.getElementById('name').textContent = '连接你的手表';
    status.textContent = response.status === 410 ? '会话已到期，请在手表重新开启。' : '输入手表上的六位配对码，或重新扫描二维码。';
    return;
  }
  const file = await response.json();
  document.getElementById('pair').hidden = true;
  const match = /^REC_(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})/.exec(file.name);
  document.getElementById('name').textContent = match ? `${match[1]}-${match[2]}-${match[3]} ${match[4]}:${match[5]} 的录音` : file.name;
  document.getElementById('meta').textContent = `${(file.size / 1048576).toFixed(1)} MB · 原始 M4A 文件`;
  const download = document.getElementById('download');
  download.hidden = false; download.download = file.name;
  download.onclick = () => { status.textContent = '下载已发起，请在浏览器下载列表确认完成，再到听悟导入。'; };
  const expiry = new Date(file.expiresAt).toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'});
  status.textContent = `请在 ${expiry} 前完成下载。下载后仍需在听悟手动导入。`;
  clearTimeout(expiryTimer);
  expiryTimer = setTimeout(() => { download.hidden = true; status.textContent = '传输会话已到期，请在手表重新开启。'; }, Math.max(0, file.expiresAt - Date.now()));
}
document.getElementById('pair').onsubmit = async event => {
  event.preventDefault();
  try {
    const response = await fetch('/api/session', {method:'POST', headers:{'X-Wanlu-Pin':document.getElementById('pin').value}, cache:'no-store'});
    document.getElementById('pin').value = '';
    if (!response.ok) throw new Error('配对码不正确或已失效，请核对手表；多次失败后需重新开启传输。');
    await loadRecording();
  } catch (error) { status.textContent = error.message; }
};
(async () => {
  const token = location.hash.slice(1);
  history.replaceState(null, '', '/');
  try {
    if (token) {
      const session = await fetch('/api/session', {method:'POST', headers:{'X-Wanlu-Token':token}, cache:'no-store'});
      if (!session.ok) throw new Error('二维码已失效，请在手表重新开启并扫码。');
    }
    await loadRecording();
  } catch (error) {
    document.getElementById('name').textContent = '暂时无法连接';
    document.getElementById('pair').hidden = false; status.textContent = error.message;
  }
})();
