const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('wanluDesktop',Object.freeze({openLogin:()=>ipcRenderer.invoke('wanlu:login')}));
