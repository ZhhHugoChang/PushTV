![PushTV 应用图标](./app/src/main/res/mipmap-xhdpi/ic_launcher.webp)
# PushTV

**PushTV** 是一款全 AI 构建、专为 Android TV 打造的极速大屏工具箱。它集成了局域网无线文件闪传、WebDAV 云端网盘备份与恢复、智能应用管理、GitHub 自动版本追踪与更新等功能，彻底解决电视端输入难、安装 App 麻烦、跨设备软件管理繁琐等痛点。

---

## 🌟 核心功能

- **局域网传包**。手机或电脑扫码上传 APK，支持批量传输和电视端安装。
- **WebDAV 备份与恢复**。备份电视应用到网盘或 NAS，按需下载恢复。
- **GitHub 更新与下载加速**。批量检测更新、查看更新日志和安装包，支持自动或手动选择下载代理。
- **网页管理**。用手机或电脑配置应用的更新地址、GitHub Token 和自定义代理节点。

---

## 🛠️ 技术栈

*   **UI 框架**: [Jetpack Compose for TV](https://developer.android.com/jetpack/compose/tv) (Material 3)
*   **网络服务**: [Ktor Server](https://ktor.io/) (内置 Netty 引擎)
*   **网络客户端**: [OkHttp](https://square.github.io/okhttp/) (扩展支持 WebDAV PROPFIND / MKCOL / DELETE 协议)
*   **图片加载**: [Coil](https://coil-kt.github.io/coil/)
*   **数据存储**: [Jetpack DataStore](https://developer.android.com/topic/libraries/architecture/datastore) (Preferences)
*   **扫码支持**: [ZXing](https://github.com/zxing/zxing)
*   **动效构建**: Compose Animation

---

## 🚀 快速开始

根据电视的 Android 版本和支持的 CPU 架构选择安装包。以下是 1.7.0 的三个构建版本。

| 安装包 | 适用设备 |
| --- | --- |
| `PushTV_v1.7.0-legacy_release_armeabi-v7a.apk` | 支持 32 位 ARM，最低 Android 6.0（API 23），适合 Android 6.0–8.1 的电视 |
| `PushTV_v1.7.0_release_arm64-v8a.apk` | 支持 64 位 ARM，最低 Android 9.0（API 28） |
| `PushTV_v1.7.0_release_armeabi-v7a.apk` | 支持 32 位 ARM，最低 Android 9.0（API 28） |

1. **无线传包**。打开电视上的 PushTV，用同一局域网内的手机或电脑扫码，或输入电视显示的地址（如 `http://192.168.1.5:8899`），即可上传 APK。
2. **云端备份**。在电视主页进入「备份与恢复」，配置 WebDAV 服务器地址与账号。
3. **检查更新**。在管理页切换到「更新管理」，填入 GitHub 仓库（如 `owner/repo`），再到电视端点击「检测更新」。

---


## 📄 开源协议

本项目采用 [MIT License](LICENSE) 协议开源。

---

## 🙌 贡献与支持

如果你觉得这个项目对你有帮助，欢迎：
*   给予项目一个 **Star** 🌟
*   提交 **Issue** 提出改进建议或 Bug 反馈

---

**PushTV - 让你的电视更智能，让管理更简单。**
