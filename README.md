# PushTV 📺

**PushTV** 是一款全AI构建专为 Android TV 打造的极速工具箱。它集成了文件无线传输、应用管理、版本更新检测等功能，旨在彻底解决电视端输入难、安装 App 麻烦、管理软件不直观等痛点。

---

## 🌟 核心功能

### 1. 极速文件传送门 🚀
*   **免 U 盘传输**：通过内置的 Ktor 服务器，在同局域网下的手机或电脑浏览器中直接上传 APK。
*   **扫码即连**：电视端自动生成二维码，手机扫码即可访问管理页面。
*   **多文件上传**：支持批量拖拽 APK 文件，实时显示上传进度。

### 2. 智能应用管理 📦
*   **全量列表**：清晰展示电视上已安装的所有第三方应用及系统应用。
*   **一键卸载/打开**：在电视端直接管理软件生命周期。
*   **收藏功能**：将常用软件加入“我的收藏”，快速定位。

### 3. GitHub 自动更新检测 🔄
*   **版本追踪**：支持为应用设置 GitHub 项目地址。
*   **智能检测**：一键检测所有收藏应用的最新 Release 版本。
*   **更新日志预览**：在电视大屏上直接阅读详细的更新说明。
*   **静默安装引导**：下载完成后自动触发安装流程。

### 4. 强大的 Web 后台 💻
*   **远程配置**：在电脑端为电视 App 设置 GitHub 更新地址，免去遥控器输入的痛苦。
*   **应用搜索**：在网页端快速通过名称或包名过滤应用。
*   **实时同步**：网页端的操作会立即同步到电视端。

---

## 🛠️ 技术栈

*   **UI 框架**: [Jetpack Compose for TV](https://developer.android.com/jetpack/compose/tv) (Material 3)
*   **网络服务**: [Ktor Server](https://ktor.io/) (内置 Netty 引擎)
*   **图片加载**: [Coil](https://coil-kt.github.io/coil/)
*   **数据存储**: [Jetpack DataStore](https://developer.android.com/topic/libraries/architecture/datastore) (Preferences)
*   **扫码支持**: [ZXing](https://github.com/zxing/zxing)
*   **动画支持**: Compose Animation 

---

## 🚀 快速开始

1.  **编译与安装**：使用 Android Studio 编译并安装到 Android TV 或电视盒子上（Android 9.0+）。
2.  **启动服务**：打开 App，确保电视与手机/电脑处于同一 Wi-Fi 下。
3.  **上传文件**：
    *   在浏览器输入电视显示的 IP 地址（如 `http://192.168.1.5:8899`）。
    *   点击“文件传输”，将 APK 拖入虚线框。
4.  **配置更新**：
    *   在 Web 端切换到“应用管理”。
    *   搜索对应 App，填入 GitHub 仓库地址（如 `owner/repo`）。
    *   回到电视端“软件列表”，点击“检测更新”。




---

## 📄 开源协议

本项目采用 [MIT License](LICENSE) 协议开源。

---


## 🙌 贡献与支持

如果你觉得这个项目对你有帮助，欢迎：
*   给予项目一个 **Star** 🌟
*   提交 **Issue** 提出改进建议或 Bug 反馈
*   发起 **Pull Request** 贡献代码

---
**PushTV - 让你的电视更智能，让管理更简单。**
