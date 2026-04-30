# EosMesh 轻量级去中心化 IM 演示项目

EosMesh 是一个用于技术演示的轻量级即时通讯系统，展示基于文件存储的社交核心功能。项目由**前端客户端**和**多语言后端**组成，支持 PHP、Java、Python 三种服务端实现，便于开发者学习不同语言下的 RESTful API 设计、Token 认证、文件存储等概念。

![a](icon.png)

> ⚠️ **重要提醒**  
> 本项目仅为小型技术演示，未针对高并发、数据安全、分布式部署等生产环境进行优化，**不适合大规模部署或实际商业应用**。  
> **Python 服务端极不稳定**，仅少数时期维护更新，**强烈不建议**用于任何长期运行或生产场景。

---

## ✨ 主要功能

- **用户系统**：注册/登录（密码哈希存储）、头像上传（JPG/PNG/GIF ≤2MB）、账号注销（软删除/完全删除可配置）
- **好友管理**：按用户名或 32 位 UID 搜索用户、添加好友（三种验证模式：允许所有人/需验证/拒绝添加）、处理好友申请、好友列表展示
- **即时通讯**：一对一私聊、消息自动滚动/手动滚动暂停、聊天记录按时间排序、过期消息自动清理
- **界面体验**：亮色/暗色主题切换（保存于 localStorage）、响应式布局（移动/桌面）、汉堡菜单（关于页、头像上传入口）

---

## 🗂️ 项目结构

```
EosMesh/
├── Cilent.html               # 前端客户端（React + Material-UI 单页应用）
├── index.php                 # PHP 后端 API（原生，无框架）
├── EosMeshServer.java        # Java 后端（基于 com.sun.net.httpserver）
├── EosMeshServer.py          # Python 后端（基于 Flask，极不稳定）
├── station.ini               # 站点配置（首次运行自动生成）
└── data/                     # 数据存储目录（自动创建）
    ├── user_index.json       # 用户 ID → 分片文件编号映射
    ├── user/                 # 用户数据分片存储（每文件 ≤50 用户）
    ├── chat/friend/          # 聊天记录（按用户分目录）
    └── avatar/               # 用户头像文件
```

---

## 🚀 服务端部署与启动

### 共通数据说明
- 所有服务端均使用相同的文件存储结构（`data/` 目录），理论上可互相替换数据，但跨语言迁移需谨慎。
- 首次启动时会自动生成 `station.ini` 配置文件，其中包含站点 ID、消息保留天数、删除策略等，可按需修改。

### 1. PHP 版（原始实现）

**特点**：原生 PHP，无任何依赖，兼容大多数虚拟主机；性能尚可，适合快速演示。

**环境要求**：PHP 7.4+（需启用 `fileinfo`、`json` 等扩展）

**部署步骤**：
1. 将 `index.php` 上传至 Web 服务器目录（如 Apache 的 `htdocs` 或 Nginx 的 `html`）。
2. 确保 `data/` 目录及其子目录具有写入权限（例如 `chmod 755 -R data`）。
3. （可选）配置 URL 重写隐藏 `index.php`，但非必需。
4. 访问 `http://your-server/index.php?action=get_station_version` 测试是否正常返回 JSON。

**启动命令**（PHP 内置服务器，仅测试用）：
```bash
php -S 0.0.0.0:8080
```
然后在浏览器打开客户端，填写 API 地址 `http://服务器IP:8080/index.php`。

---

### 2. Java 版

**特点**：使用 JDK 内置 `com.sun.net.httpserver`，无需第三方库；并发性能优于 PHP/Python 版；适合学习 Java 原生 HTTP 服务。

**环境要求**：JDK 8 或更高版本

**编译与运行**：
```bash
javac EosMeshServer.java
java EosMeshServer
```
启动后会输出类似 `127.0.0.1:54321` 的本地地址和随机端口。服务将监听该地址，客户端需填写完整 URL，例如：
```
http://127.0.0.1:54321/
```
> 注意：Java 版默认监听所有网络接口（`0.0.0.0`），外部访问需确保防火墙允许该端口。

**跨平台**：适用于 Windows / Linux / macOS，只需安装 JRE。

---

### 3. Python 版（⚠️ 极不稳定，仅用于实验）

> **严重警告**  
> Python 后端基于 Flask，存在以下已知问题：
> - 多线程环境下文件锁机制不完善，高并发时易数据损坏
> - 头像上传解析有 bug，部分图片无法正确保存
> - 长期运行内存占用增长，需手动重启
> - 维护频率极低，**不推荐任何实际使用**

**环境要求**：Python 3.7+，需安装依赖：
```bash
pip install flask bcrypt
```

**运行方式**：
```bash
python EosMeshServer.py
```
输出类似 `127.0.0.1:54322` 的地址。客户端填写该地址即可。

> 建议仅用于单用户功能验证，切勿用于多人测试或演示。

---

## 💻 客户端使用说明

客户端是一个单 HTML 文件 `Cilent.html`，基于 React 18 + Material-UI 构建，无需编译或安装。

### 快速开始
1. 将 `Cilent.html` 放置在任何 Web 服务器上（或直接用浏览器打开 `file://` 协议，但可能因跨域限制无法正常使用）。
2. 推荐使用本地 Web 服务器，例如：
   - Python 简易服务器：`python -m http.server 3000`
   - 或使用 VS Code Live Server 等工具。
3. 在浏览器中访问客户端地址（如 `http://localhost:3000/Cilent.html`）。
4. 首次打开时，页面会要求填写**站点链接**（即后端 API 的完整 URL，例如 `http://192.168.1.100:8080/index.php`）。
5. 填写后即可进行注册、登录、添加好友、聊天等操作。

### 客户端主要界面
- **登录/注册页**：设置 API 地址、输入用户名密码。
- **主界面**：左侧好友列表，右侧聊天区域；顶部工具栏可添加好友、打开设置。
- **汉堡菜单**：包含“用户”页（查看个人信息及消息总数）、“关于”页（显示版本及 GitHub 链接）、“消息”页（管理好友验证方式和待处理申请）、“上传头像”入口。
- **设置**：可修改 API 地址、切换暗色主题、退出登录。

### 客户端与后端的对接
- 所有 API 请求通过 `fetch` 发送，遵循 RESTful 风格，使用 `action` 参数区分接口。
- 认证采用 Token 机制（`uid:HMAC-SHA256`），服务端验证签名。
- 头像通过独立接口 `?action=get_avatar&uid=xxx` 获取，支持跨域。

---

## 🧱 架构与数据存储

### 核心设计
- **无数据库**：所有数据以 JSON 文件形式存储，直接利用文件系统。
- **用户分片**：避免单个文件过大，每个 `user_{n}.json` 最多 50 个用户记录。
- **聊天记录按对话分文件**：`data/chat/friend/{uidA}/{uidB}.json` 存储 A 与 B 的所有消息，JSON Lines 格式。
- **消息过期**：根据 `station.ini` 中的 `stationNumberDaysInformationStored` 自动清理旧消息。
- **并发控制**：PHP/Java 版本对聊天文件写入使用 `flock` 或文件锁；Python 版锁机制不完善。

### 配置文件 `station.ini`
```ini
[station]
stationID = "随机16位字符串"
stationNumberDaysInformationStored = 3
stationWhetherCompletelyDeleteUserData = true
```

---

## ⚠️ 项目局限性（为什么不适合生产环境）

| 方面         | 问题描述                                                                 |
|--------------|--------------------------------------------------------------------------|
| **性能**     | 文件存储无索引，用户量增大后查询效率极低。                                |
| **并发**     | 简单文件锁在高并发下易阻塞或数据损坏。                                    |
| **扩展性**   | 分片策略简单，无法水平扩展；聊天记录单文件会逐渐变大。                    |
| **安全性**   | Token 永久有效，无刷新机制；API 无速率限制，易被暴力破解。                |
| **可靠性**   | 无事务、无备份，服务器故障可能丢失数据。                                  |
| **功能完整** | 缺少群聊、已读回执、离线推送、多媒体消息等现代 IM 功能。                  |
| **部署**     | 依赖文件系统权限，无法在多服务器间共享数据（无集中存储）。                |

---

## 📜 许可证与作者

- **项目作者**：GitHub [@NTdebug145](https://github.com/NTdebug145)
- **许可证**：GPL v3
- **客户端版本**：b26.4.2 - HTML
- **后端版本**：b26.4.2（PHP/Java/Python 均实现相同 API 协议）

---

## 📌 补充说明

- 若需自定义消息保留天数，请直接修改 `station.ini` 中的 `stationNumberDaysInformationStored`。
- 切换后端语言时，**请清空 `data/` 目录**（因为不同语言实现的用户密码哈希算法不同，无法兼容已有数据）。
- 客户端的 API 地址支持 `http://` 和 `https://`，但服务端默认未启用 SSL，生产环境应前置 Nginx 反向代理并配置证书。



# EosMesh – Lightweight Decentralized IM Demo

EosMesh is a technical demonstration of a lightweight instant messaging system, showcasing core social features built on file-based storage. It consists of a **single‑page frontend client** (`Cilent.html`) and **backend implementations in three languages** (PHP, Java, Python), allowing developers to explore RESTful API design, token authentication, and file persistence across different stacks.

![a](icon.png)

> ⚠️ **Important Note**  
> This project is a **proof of concept only**. It is **not optimized for production** (high concurrency, security, scalability, etc.).  
> The **Python backend is highly unstable** – it receives only sporadic maintenance and **should not be used for any serious or long‑running deployment**.

---

## ✨ Key Features

- **User system** – Registration / login (password hashed), avatar upload (JPG/PNG/GIF ≤2MB), account deletion (soft or hard delete, configurable).
- **Friend management** – Search by username or 32‑char UID, add friends (three verification modes: allow all / require approval / deny all), accept/reject friend requests, view friend list with avatars.
- **Instant messaging** – One‑to‑one private chats, auto‑scroll with manual scroll override, chat history sorted by time, automatic cleanup of old messages (retention configurable).
- **UI & experience** – Light/dark theme toggle (saved in localStorage), responsive layout (mobile/desktop), hamburger menu with About page and avatar upload entry.

---

## 📁 Project Structure

```
EosMesh/
├── Cilent.html               # Frontend client (React + Material‑UI SPA)
├── index.php                 # PHP backend (native, no framework)
├── EosMeshServer.java        # Java backend (built‑in com.sun.net.httpserver)
├── EosMeshServer.py          # Python backend (Flask – highly unstable)
├── station.ini               # Site configuration (auto‑generated on first run)
└── data/                     # Data storage (auto‑created)
    ├── user_index.json       # User ID → shard file mapping
    ├── user/                 # Sharded user data (≤50 users per file)
    ├── chat/friend/          # Chat history (per‑user directories)
    └── avatar/               # User avatars
```

---

## 🚀 Backend Deployment & Startup

### Common Notes
- All backends use the same file‑based storage (`data/`). Data can theoretically be moved between implementations, but cross‑language migration is **not supported** due to differences in password hashing.
- On first start, `station.ini` is created. You can edit it to change message retention days or deletion policy.

---

### 1. PHP Backend (Original Implementation)

**Characteristics** – Pure PHP, no dependencies, runs on most shared hosting; decent performance for demonstrations.

**Requirements** – PHP 7.4+ (with `fileinfo`, `json` extensions).

**Deployment**  
1. Upload `index.php` to your web server directory (e.g., Apache `htdocs` or Nginx `html`).  
2. Ensure `data/` and its subdirectories are writable (`chmod 755 -R data`).  
3. (Optional) Configure URL rewriting to hide `index.php`.  
4. Test by visiting `http://your-server/index.php?action=get_station_version` – it should return JSON.

**Quick test with PHP built‑in server**  
```bash
php -S 0.0.0.0:8080
```
Then point the client to `http://server-ip:8080/index.php`.

---

### 2. Java Backend

**Characteristics** – Uses JDK’s built‑in `com.sun.net.httpserver`; no third‑party libraries; better concurrency than PHP/Python; good for learning native HTTP services in Java.

**Requirements** – JDK 8 or higher.

**Compile & Run**  
```bash
javac EosMeshServer.java
java EosMeshServer
```
The server prints an address like `127.0.0.1:54321` – it listens on all interfaces (`0.0.0.0`). The client must use the full URL, e.g., `http://127.0.0.1:54321/`.

**Platform** – Works on Windows, Linux, macOS (JRE required).

---

### 3. Python Backend (⚠️ Highly Unstable)

> **Serious Warning**  
> The Python backend (Flask) has multiple issues:
> - Incomplete file locking → data corruption under concurrent requests.
> - Avatar upload parsing is buggy (many images fail to save).
> - Memory leaks over time → requires frequent restarts.
> - Maintenance is extremely rare. **Not recommended for any use case.**

**Requirements** – Python 3.7+, install dependencies:
```bash
pip install flask bcrypt
```

**Run**  
```bash
python EosMeshServer.py
```
Outputs `127.0.0.1:54322`. Use this URL in the client.

> Only use for quick feature validation on a single user – never for multi‑user tests or demos.

---

## 💻 Frontend Client

The client is a single HTML file `Cilent.html` built with React 18 + Material‑UI. No build step required.

### How to Use
1. Serve `Cilent.html` with any web server (e.g., `python -m http.server 3000` or VS Code Live Server).  
   *Opening directly with `file://` may cause CORS issues.*  
2. Open the client in a browser (e.g., `http://localhost:3000/Cilent.html`).  
3. On the first screen, enter the **backend API URL** (e.g., `http://192.168.1.100:8080/index.php`).  
4. Register or log in, then add friends and start chatting.

### Client Interface Overview
- **Login/Register** – Set API URL, enter username/password.  
- **Main view** – Friend list on the left, chat area on the right; top bar has “Add friend” and “Settings”.  
- **Hamburger menu** – Contains “User” (profile with message count), “About” (versions and GitHub link), “Messages” (friend verification mode and pending requests), “Upload avatar”.  
- **Settings** – Change API URL, toggle dark mode, log out.

### API Integration
- All requests use `fetch` with the `action` query parameter.  
- Authentication uses a token: `uid:HMAC-SHA256(uid+password_hash, stationID)`.  
- Avatars are retrieved via `?action=get_avatar&uid=xxx` (CORS enabled).

---

## 🧱 Architecture & Data Storage

- **No database** – All data stored as JSON files.  
- **User sharding** – Users are distributed across `user_{n}.json` files (max 50 per file).  
- **Chat storage** – `data/chat/friend/{uidA}/{uidB}.json` stores messages between A and B (JSON Lines format).  
- **Message expiry** – Old messages are automatically purged based on `stationNumberDaysInformationStored` in `station.ini`.  
- **Concurrency** – PHP and Java use file locking (`flock` or `FileLock`); Python does not have reliable locking.

### Sample `station.ini`
```ini
[station]
stationID = "Random16CharString"
stationNumberDaysInformationStored = 3
stationWhetherCompletelyDeleteUserData = true
```

---

## ⚠️ Limitations (Why Not for Production)

| Area           | Issue                                                                 |
|----------------|-----------------------------------------------------------------------|
| Performance    | No indexes, full file scans for user searches → very slow at scale.   |
| Concurrency    | Basic file locks cause blocking or corruption under high load.        |
| Scalability    | Simple sharding, cannot scale horizontally; chat files grow over time.|
| Security       | Tokens never expire; no rate limiting; vulnerable to brute force.     |
| Reliability    | No transactions, no backups; server crash may corrupt data.           |
| Features       | Missing group chats, read receipts, push notifications, rich media.   |
| Deployment     | Requires shared filesystem; cannot be distributed across servers.     |

---

## 📜 License & Author

- **Author** – GitHub [@NTdebug145](https://github.com/NTdebug145)  
- **License** – GPL v3  
- **Client version** – b26.4.2 – HTML  
- **Backend version** – b26.4.2 (PHP/Java/Python all implement the same API protocol)

---

## 📌 Additional Notes

- To change message retention, edit `station.ini` and restart the server.  
- **Do not mix backends without clearing the `data/` directory** – password hashing algorithms differ, and user records will be incompatible.  
- For HTTPS, put a reverse proxy (Nginx/Apache) in front of the backend.  
- The Python backend is **not safe for any concurrent usage** – use only for isolated testing.

**Enjoy exploring EosMesh!**
