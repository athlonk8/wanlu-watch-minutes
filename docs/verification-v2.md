# 2.0 验证记录 — 2026-10-23

## 已通过

- Android：JDK 21、Gradle 8.11.1、SDK 35，`-PwanluPreview=true :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 返回 0。2 项 JVM 测试，0 失败；lint 无错误，保留依赖版本、图标、备份规则等建议。
- Python：`python -m unittest discover -s pipeline/tests -v` 返回 0，10 项测试通过。覆盖真实 HTTP 鉴权、上传和校验、重复与并发去重、中断上传、队列恢复、失败后人工重试、模拟处理后的手机结果读取。处理器使用模拟数据，不调用付费 API。
- 浏览器：390×844 视口下，验证错误口令、登录、列表、纪要、转写、Markdown 下载、退出清空内容。无 JavaScript 异常，无水平溢出；内容中的 HTML / script 作为文本显示。手机页面截图使用演示数据。
- 实机：OWW261，Android 11，466×466 圆屏。已安装 `com.zcode.watchrec.preview`，应用名“腕录 2.0”，versionCode 8 / versionName 2.0。核对三页截图，修正初版底部文字裁切。录音计时、前台服务、停止保存均已验证；ffprobe 检查两段测试录音为 AAC、44100 Hz、单声道，时长约 33.7 秒及 4.6 秒，随后清理这两段测试文件。
- 数据：旧版 1.6 的 APK 和 6 段录音已备份至本机工作区外层 `.tools/wanlu-device-backup`。6 段原录音共 5,895,303 字节，通过 `adb exec-in` 导入新版私有目录，逐一校验 SHA-256 一致，并保留原录音时间。旧版应用及其数据未删除。没有上传用户录音。
- 打包：`compose.yaml` 已通过 YAML 解析；`node --check pipeline/mobile/app.js` 和 `git diff --check` 通过。

## 尚未验证

- 群晖暂不可访问，未部署 Docker 容器，未验证 NAS 域名、证书、公网可达性及手机热点上传。
- 未使用真实百炼密钥或调用付费转写/纪要 API。复用仓库原有处理器；上线后应以一段新录音完成真实链路验收。
- 本机没有 Docker，尚未执行容器构建。
- 原安装包签名与本机构建签名不同，因此采用并排安装；未卸载旧版，也未覆盖其签名。

源代码在本地分支 `feat/watch-cloud-hotspot`，本次未提交或推送远端。
