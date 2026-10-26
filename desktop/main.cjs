// The local renderer never receives account cookies or the desktop exchange token.
const {app, BrowserWindow, session, ipcMain, dialog} = require('electron');
const {spawn} = require('node:child_process');
const crypto = require('node:crypto');
const net = require('node:net');
const path = require('node:path');
const fs = require('node:fs');
const root = path.resolve(__dirname, '..');
app.setName('WanLu');
const helper = Boolean(process.env.WANLU_PANEL_URL);
const smoke = process.env.WANLU_SMOKE_TEST;
const profile = process.env.WANLU_USER_DATA_DIR || (app.isPackaged ? app.getPath('userData') : path.join(root,'.local','tingwu-browser'));
fs.mkdirSync(profile,{recursive:true});
app.setPath('userData',profile);
if(process.platform==='win32')app.setAppUserModelId('com.wanlu.desktop');
let panel = process.env.WANLU_PANEL_URL;
let token = process.env.WANLU_DESKTOP_TOKEN;
let main, login, backend, timer, quitting=false, syncing=false, lastCookie=null,loginFailed=false,syncIssue='';
const ownsLock=app.requestSingleInstanceLock();
if(!ownsLock) app.quit();
const official=url=>{try{const u=new URL(url);return u.protocol==='https:'&&['aliyun.com','taobao.com','alipay.com'].some(d=>u.hostname===d||u.hostname.endsWith('.'+d));}catch{return false;}};
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function freePort(){return new Promise((resolve,reject)=>{const server=net.createServer();server.once('error',reject);server.listen(0,'127.0.0.1',()=>{const port=server.address().port;server.close(()=>resolve(port));});});}
async function startBackend(){
  const port=await freePort(); token=crypto.randomBytes(32).toString('hex');panel='http://127.0.0.1:'+port;
  const data=path.join(profile,'data');fs.mkdirSync(data,{recursive:true});
  const env={...process.env,WANLU_PORT:String(port),WANLU_BIND:'127.0.0.1',WANLU_DATA_DIR:data,WANLU_DESKTOP_TOKEN:token,PYTHONUTF8:'1'};
  const executable=app.isPackaged?path.join(process.resourcesPath,'backend','wanlu-backend.exe'):(process.env.WANLU_PYTHON||'python');
  const args=app.isPackaged?[]:[path.join(root,'pipeline','server.py')];
  const adb=app.isPackaged?path.join(process.resourcesPath,'adb','adb.exe'):null;
  if(adb)env.WANLU_ADB=adb;
  backend=spawn(executable,args,{cwd:data,env,windowsHide:true,stdio:'ignore'});
  let startError=null;backend.on('error',error=>{startError=error;});
  for(let attempt=0;attempt<120;attempt++){
    if(startError||backend.exitCode!==null)throw new Error('本地服务启动失败，请重新启动腕录。');
    try{const response=await fetch(panel+'/api/health',{signal:AbortSignal.timeout(500)});const health=await response.json();if(health.service==='wanlu-panel')return;}catch{}
    await delay(250);
  }
  throw new Error('本地服务启动超时，请检查程序文件是否完整。');
}
function openLogin(){if(login&&!login.isDestroyed()){login.show();login.focus();if(loginFailed){loginFailed=false;login.loadURL('https://tingwu.aliyun.com/register/login?tw_redirect='+encodeURIComponent('https://tingwu.aliyun.com/home')).catch(()=>{loginFailed=true;});}}}
app.on('second-instance',()=>{if(main){main.show();main.focus();}if(helper)openLogin();});
ipcMain.handle('wanlu:login',event=>{if(main&&event.sender===main.webContents&&event.senderFrame.url.startsWith(panel+'/'))openLogin();});
app.on('before-quit',event=>{if(!backend){quitting=true;clearInterval(timer);return;}event.preventDefault();if(quitting)return;quitting=true;clearInterval(timer);const owned=backend;(async()=>{try{await fetch(panel+'/api/desktop/shutdown',{method:'POST',headers:{Authorization:'Bearer '+token},signal:AbortSignal.timeout(1500)});}catch{}for(let i=0;i<10&&owned.exitCode===null;i++)await delay(200);if(owned.exitCode===null)owned.kill();backend=null;app.quit();})();});
if(ownsLock)app.whenReady().then(async()=>{
  if(helper){if(!token||!/^http:\/\/127\.0\.0\.1:\d+$/.test(panel))throw new Error('电脑服务地址无效');}
  else await startBackend();
  const account=session.fromPartition('persist:wanlu-tingwu');
  function protect(window){
    window.webContents.on('will-navigate',(event,url)=>{if(!official(url))event.preventDefault();});
    window.webContents.setWindowOpenHandler(({url})=>{if(login?.isVisible()&&official(url)){const popup=new BrowserWindow({width:950,height:780,parent:login,autoHideMenuBar:true,webPreferences:{partition:'persist:wanlu-tingwu',nodeIntegration:false,contextIsolation:true,sandbox:true}});protect(popup);popup.loadURL(url);}return{action:'deny'};});
  }
  if(!helper){
  main=new BrowserWindow({show:!smoke,width:1380,height:950,minWidth:850,minHeight:650,title:'腕录 · 听悟工作台',autoHideMenuBar:true,icon:path.join(__dirname,'assets','icon.png'),webPreferences:{preload:path.join(__dirname,'preload.cjs'),nodeIntegration:false,contextIsolation:true,sandbox:true}});
  main.webContents.on('will-navigate',(event,url)=>{if(!url.startsWith(panel+'/'))event.preventDefault();});
  main.webContents.setWindowOpenHandler(({url})=>url.startsWith(panel+'/')?{action:'allow',overrideBrowserWindowOptions:{autoHideMenuBar:true,webPreferences:{nodeIntegration:false,contextIsolation:true,sandbox:true}}}:{action:'deny'});
  await main.loadURL(panel);
  }
  if(smoke==='1'){
    const health=await fetch(panel+'/api/health').then(r=>r.json());
    const state=await fetch(panel+'/api/state').then(r=>r.json());
    fs.writeFileSync(path.join(profile,'smoke-result.json'),JSON.stringify({version:health.version,service:health.service,packaged:app.isPackaged,backendPid:backend?.pid,watchConnected:state.watch.connected,recordings:state.watch.files.length}));
    app.quit();return;
  }
  login=new BrowserWindow({width:1100,height:820,title:'通义听悟 · 官方账号登录',show:false,autoHideMenuBar:true,webPreferences:{partition:'persist:wanlu-tingwu',nodeIntegration:false,contextIsolation:true,sandbox:true}});
  protect(login);
  login.on('close',event=>{if(!quitting&&main&&!main.isDestroyed()){event.preventDefault();login.hide();}});
  if(main)main.on('closed',()=>{main=null;app.quit();});
  async function sync(){
    if(syncing)return false;syncing=true;
    try{
      if(lastCookie){const status=await fetch(panel+'/api/tingwu/status',{signal:AbortSignal.timeout(5000)}).then(r=>r.json());if(!status.connected)lastCookie=null;}
      const cookies=await account.cookies.get({url:'https://tingwu.aliyun.com'});
      const cookie=cookies.map(c=>`${c.name}=${c.value}`).join('; ');
      if(cookie&&cookie!==lastCookie){const response=await fetch(panel+'/api/tingwu/session',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:JSON.stringify({cookie}),signal:AbortSignal.timeout(25000)});if(response.ok){const data=await response.json();if(data.connected){lastCookie=cookie;syncIssue='';await account.cookies.flushStore();return true;}}else{syncIssue='HTTP '+response.status;}}
      return Boolean(lastCookie);
    }catch(error){syncIssue=error.name;return false;}finally{syncing=false;}
  }
  account.cookies.on('changed',()=>{lastCookie=null;});
  timer=setInterval(sync,5000);
  login.webContents.on('did-finish-load',sync);
  await sync();
  await login.loadURL('https://tingwu.aliyun.com/register/login?tw_redirect='+encodeURIComponent('https://tingwu.aliyun.com/home')).catch(()=>{loginFailed=true;});
  // Browser helper is explicitly requested by the login button. Desktop startup
  // keeps the official page hidden even when the account needs another login.
  if(helper&&!smoke)openLogin();
  if(smoke==='session'){
    for(let attempt=0;attempt<10&&!lastCookie;attempt++){await delay(2000);await sync();}
    fs.writeFileSync(path.join(profile,'session-smoke.json'),JSON.stringify({connected:Boolean(lastCookie),issue:syncIssue,backendPid:backend?.pid}));
    app.quit();
  }
}).catch(error=>{if(smoke)fs.writeFileSync(path.join(profile,'smoke-error.json'),JSON.stringify({issue:error.name}));else dialog.showErrorBox('腕录启动失败',error.message);app.quit();});
app.on('window-all-closed',()=>app.quit());
