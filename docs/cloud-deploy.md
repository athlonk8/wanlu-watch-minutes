# 群晖部署腕录云端

这份配置让群晖接收手表上传，调用百炼生成转写和纪要，并向手机提供查看页。日常使用不依赖电脑；手表连接手机热点。群晖需要能从手机网络通过 HTTPS 访问，也需要能够访问百炼。

## 群晖恢复访问后

1. 在支持 Container Manager / Docker 的群晖上，放入本仓库代码，按根目录 `compose.yaml` 创建项目。若该机型不支持容器，需要另选可运行 Python 3.12 的服务器。
2. 将根目录 `.env.example` 复制为 `.env`。在群晖本地设置 `DASHSCOPE_API_KEY` 和一个至少 32 字符的随机 `WANLU_TOKEN`。口令可用 `python -c "import secrets; print(secrets.token_urlsafe(32))"` 生成。不要把真实 `.env` 提交到 Git。
3. 构建并启动：`docker compose up -d --build`。首次需要网络下载 Python 基础镜像和 requests。检查容器日志确认启动成功，不要把含密钥的配置输出到聊天中。
4. 设置一个可公网访问的域名 / 群晖 DDNS，配置有效的 HTTPS 证书。在 DSM 反向代理中，把该域名的 HTTPS 443 转发到本机 HTTP `127.0.0.1:8734`。仅开放所需 HTTPS 入口；Compose 默认只向 NAS 的本机回环地址暴露 8734。
5. 确认反向代理允许至少 128 MiB 请求体、至少 120 秒读超时，并设置适合家庭出口带宽的上传超时（例如 20 分钟）。域名必须从手机关闭 Wi-Fi、使用移动数据时也能访问。遇到运营商 CGNAT，需要已有的 VPN / 隧道或其他可达入口；单纯配置 DDNS 不能解决它。
6. 手机上打开 `https://你的域名`，输入 `WANLU_TOKEN`。看到“还没有录音”即代表鉴权和服务正常。浏览器口令只在当前标签页会话中保存。
7. 手表连手机热点，进入腕录第三页，填写同一 HTTPS 域名和口令，点“验证并保存”。如希望每次结束即上传，打开“结束后自动上传”；默认关闭。第一次启用不会补传旧录音。旧录音可在列表中逐条点“上传并生成纪要”。

手表不保存百炼 API Key，不显示纪要正文，不需要 Google Play 服务或手机配套 App。上传送达和云端处理是两个状态：手表显示“已送达云端”仅代表文件安全入队，处理进度和结果请在手机查看。

## 数据与费用

- 每次手动上传、或开启自动上传后的新录音，会触发转写与纪要 API，使用服务端百炼默认参数（paraformer-v2、说话人分离、qwen-plus）。
- 录音上传使用 SHA-256 校验和去重；网络重试不会创建重复处理任务。即使服务器已经处理完成，重复上传同一音频也复用原任务。
- 上传通过 WorkManager 等待可用网络并退避重试；HTTPS 地址、鉴权或大小错误需要手动修正后重传。系统省电策略可能延迟任务，必要时在手表设置中允许腕录后台运行。
- 云端任务保存在 SQLite，待处理任务重启后继续。处理中的任务被重启打断时，标为失败，**不会自动再次调用付费 API**；手机端可确认费用后重试。
- 容器 `/data` 使用命名卷 `wanlu-data`，包含原始录音、转写、纪要、任务数据库。升级容器会保留它；不要执行删除数据卷的命令。按需设置群晖备份并监控空间。当前版本不自动删除任何录音。
- 本地手表录音同样保留，只有用户两次确认删除才会移除。最大单次上传 128 MiB，与录音最长 4 小时匹配。
- 如需改变转写/纪要参数，可按 `pipeline/config.example.ini` 在 NAS 保存私有 `config.ini`，只读挂载至 `/app/pipeline/config.ini`。密钥留在 NAS；手机页面不提供密钥管理接口。

## 本地开发与验证

```powershell
python -m unittest discover -s pipeline/tests -v
node --check pipeline/mobile/app.js
cd watchrec_app
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

后端可在本机以 `python pipeline/cloud_server.py` 启动；默认绑定 `127.0.0.1:8734`，必须在本机环境配置 `DASHSCOPE_API_KEY` 和 `WANLU_TOKEN`。手表强制 HTTPS，不接受明文 HTTP 或带路径、查询参数、用户名密码的地址。无需开放旧 `pipeline/server.py` 面板到公网，旧面板包含 ADB 与本地配置管理功能。

若旧 APK 的签名密钥不在本机，使用 `gradlew.bat -PwanluPreview=true :app:assembleDebug` 构建并排安装包：应用名“腕录 2.0”，包名 `com.zcode.watchrec.preview`。它保留旧版应用和数据，录音目录独立。新版将录音保存到应用私有 `files` 目录，并继续识别原包名升级时可访问的 1.x 外部目录。调试包可通过 `adb shell run-as 包名` 导入/导出自己的 `.m4a`；不要直接向 OPPO 的外部应用目录 `adb push`，厂商存储策略可能拒绝写入。标准构建的原包名不变，必须使用原签名密钥才能覆盖升级。

测试使用合成音频和模拟的转写/纪要处理器，验证真实 HTTP 上传到手机结果接口的完整协议；不会上传用户已有录音，也不会消耗百炼额度。实际热点、百炼和群晖公网链路仍需在部署后用一段新录音验收。
