# PushTV 📺

**PushTV** 是一款全 AI 构建、专为 Android TV 打造的极速大屏工具箱。它集成了局域网无线文件闪传、WebDAV 云端网盘备份与恢复、智能应用管理、GitHub 自动版本追踪与更新等功能，彻底解决电视端输入难、安装 App 麻烦、跨设备软件管理繁琐等痛点。

---

## 🌟 核心功能

### 1. 极速文件传送门 🚀
*   **免 U 盘无线闪传**：内置轻量高效的 Web 服务，同一局域网下的手机或电脑浏览器直接打开即可上传 APK。
*   **扫码即连**：电视大屏端自动生成对应 IP 的二维码，手机扫码免输网址快速接入。
*   **多文件拖拽与反馈**：支持批量拖拽 APK，前端实时展现上传进度与完成提示。
*   **已接收文件管理**：首页轻量展示「已接收」历史列表，支持单项调起安装与一键清空。

### 2. WebDAV 云端网盘备份与恢复系统 ☁️ *(New in v1.3)*
*   **主流网盘全兼容**：支持坚果云、群晖 NAS、Alist、Nextcloud 等标准 WebDAV 协议服务。
    *   **本机应用一键备份**：自动提取已安装第三方应用，支持单项或多选批量上传至云端网盘，智能检测云端重复版本。
    *   **云端安装包智能比对**：全量读取远端 APK 文件，智能解析包名与版本，精准比对电视本机安装状态（未安装 / 已安装同版本 / 版本差异）。
    *   **独立下载与恢复**：直接从云端下载安装包到电视，支持多选批量下载及远端文件物理彻底删除。

### 4. GitHub 自动更新追踪 🔄
*   **版本自动比对**：支持为任意应用绑定 GitHub 仓库 Release 地址。
*   **一键全量检测**：大屏端点击“检测更新”，自动比对最新远端发布版本。

### 5. 便捷 Web 远程管理后台 💻
*   **电脑端舒适配置**：在电脑大屏浏览器为电视应用设置 GitHub 仓库地址，彻底免去电视遥控器拼写输入的痛苦。
*   **实时双向同步**：网页端的操作与配置实时同步生效至电视端。

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

1.  **安装应用**：使用 Android Studio 编译或直接安装 Release APK 到 Android TV / 电视盒子（支持 Android 9.0+，API 28+）。
2.  **无线传包**：打开 PushTV，手机/电脑扫码或输入大屏上的 IP 地址（如 `http://192.168.1.5:8899`），即可无线推流 APK。
3.  **云端备份**：在电视主页进入「备份与恢复」，配置您的 WebDAV 服务器地址与账号，即可畅享大屏云备份与一键跨设备恢复。
4.  **自动更新**：在 Web 端切换到“应用管理”，为常用软件填入 GitHub 仓库（如 `owner/repo`），电视端点击“检测更新”即可大屏升级。

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
