## EosMesh 项目介绍

EosMesh 是一个轻量级的去中心化即时通讯（IM）演示项目，旨在展示基于 PHP + 文件存储的简易社交系统核心功能。项目由两部分构成：
- **前端客户端**（`Cilent.html`）：单页面 Web 应用，使用 React + Material-UI 构建，提供用户交互界面。
- **后端 API**（`index.php`）：PHP 编写的 RESTful 接口，处理用户认证、好友关系、消息收发、头像上传等业务逻辑，所有数据以 JSON 文件形式存储在服务器本地。

**⚠️ 重要说明：本项目仅为小型技术演示，未针对高并发、数据安全、分布式部署等生产环境需求进行优化，不适合大规模部署或实际商业应用。**

---

### 主要功能

1. **用户系统**
   - 注册 / 登录（用户名 + 密码，密码使用 `password_hash` 加密）
   - 用户头像上传（支持 JPG/PNG/GIF，最大 2MB）
   - 账号注销（支持软删除或完全删除数据，取决于配置）

2. **好友管理**
   - 通过用户名或 32 位 UID 搜索用户
   - 添加好友（支持三种验证方式：任何人可加、需验证、禁止添加）
   - 好友申请的处理（接受/拒绝）
   - 显示好友列表及头像

3. **即时通讯**
   - 与好友进行一对一私聊
   - 消息自动滚动至底部，支持手动滚动时暂停自动滚动
   - 消息记录按时间排序，并定期清理过期消息（可配置保留天数）

4. **界面与体验**
   - 亮色/暗色主题切换（偏好保存于 localStorage）
   - 响应式布局，适配移动端与桌面端
   - 汉堡菜单包含关于页面（显示 GitHub 链接与版本号）和头像上传入口

---

### 技术栈

| 层级         | 技术                                                                 |
|--------------|----------------------------------------------------------------------|
| **前端**     | HTML5 / CSS3 / JavaScript (ES6)<br>React 18 (通过 UMD 引入)<br>Material-UI 5 (UI 组件库)<br>Emotion (CSS-in-JS) |
| **后端**     | PHP 7+ (原生，无框架)<br>文件系统作为数据库 (JSON 格式存储)              |
| **通信**     | RESTful API，使用 `fetch` 请求，支持跨域 (CORS)                         |
| **认证**     | Token 机制：`uid:HMAC-SHA256(uid+密码哈希, stationID)`                |


### 架构与数据存储

#### 后端目录结构
```
/
├── index.php                # API 入口，路由所有请求
├── station.ini              # 站点配置（stationID、消息保留天数、删除策略）
└── data/                    # 数据目录（自动生成）
    ├── user_index.json      # 用户 ID 到文件编号的映射
    ├── user/                # 用户数据分片存储（每个文件最多 50 个用户）
    │   ├── user_1.json
    │   └── user_2.json
    ├── chat/friend/         # 聊天记录
    │   ├── {uid_A}/         # 按用户分目录
    │   │   └── {uid_B}.json # 与 B 的聊天记录（JSON 行格式）
    │   └── {uid_B}/...
    └── avatar/              # 用户头像文件（以 uid.扩展名 存储）
```

#### 关键设计说明
- **无数据库**：所有数据（用户、好友关系、消息）均以 JSON 文件存储，利用文件系统的读写实现持久化。
- **用户分片**：为避免单个用户文件过大，将用户数据分散到多个 `user_{n}.json` 文件中，每个文件最多容纳 50 个用户。
- **消息过期**：根据 `station.ini` 中的 `stationNumberDaysInformationStored` 配置，自动清理超过保留天数的聊天记录。
- **认证安全**：Token 基于 HMAC-SHA256 签名，服务端校验签名与用户密码哈希的关联性，防止伪造。
- **并发处理**：对聊天文件的写入使用 `flock` 进行文件锁，避免多请求同时写入导致数据损坏。

---

### 部署说明

1. **环境要求**
   - PHP 7.4 或更高版本
   - 支持 `glob`、`file_put_contents` 等文件操作函数
   - Web 服务器（Apache / Nginx）需正确配置 URL 重写（可选，用于隐藏 `index.php`）

2. **快速部署**
   - 将 `index.php` 上传至服务器任意目录
   - 确保 `data/` 目录及子目录具有读写权限（如 `chmod 755` 或更高）
   - 前端 `Cilent.html` 可放在任意 Web 服务器，首次访问时需在登录界面填写后端 API 地址（即 `index.php` 的完整 URL）

3. **配置说明**
   - `station.ini` 在首次运行时自动生成，可手动修改：
     ```ini
     [station]
     stationID = "随机16位字符串"                # 站点唯一标识，影响 Token 计算
     stationNumberDaysInformationStored = 3     # 聊天记录保留天数
     stationWhetherCompletelyDeleteUserData = true   # true=彻底删除，false=软删除
     ```

---

### 项目局限性（为什么不适合大规模部署）

| 方面           | 问题描述                                                                                                 |
|----------------|----------------------------------------------------------------------------------------------------------|
| **性能**       | 所有数据基于文件读写，无索引和缓存机制。用户量增加时，查询好友列表、搜索用户等操作将遍历全部 JSON 文件，效率极低。 |
| **并发能力**   | 文件锁仅能提供基础的并发保护，高并发场景下极易出现阻塞或数据损坏。                                                |
| **扩展性**     | 数据分片策略简单，无法水平扩展。聊天记录单文件存储，长期运行后文件过大影响读写速度。                                |
| **安全性**     | 密码以 bcrypt 哈希存储，但 Token 有效期永久（无刷新机制）；所有 API 均无速率限制，易被暴力破解或滥用。                 |
| **可靠性**     | 无数据备份、无事务支持，服务器宕机或文件写入失败可能导致数据丢失。                                                |
| **功能完整度** | 缺少群聊、消息已读回执、离线推送、多媒体消息等现代 IM 核心功能。                                                |
| **部署复杂度** | 依赖文件系统权限，无法在多服务器间共享数据（无集中存储）。                                                       |

---

### 总结

EosMesh 是一个适合学习 PHP + 文件存储架构的演示项目，展示了从零构建一个简易社交系统所需的基本模块。其代码结构清晰、前后端分离，便于理解 RESTful API 设计、Token 认证、前端状态管理等概念。**但请务必注意，该项目不满足生产环境的高可用、高性能、高安全标准，仅推荐用于个人学习、内部测试或技术展示。**

**项目作者**：GitHub @NTdebug145
**许可证**：GPL v3



## EosMesh Project Introduction

EosMesh is a lightweight, decentralized instant messaging (IM) demonstration project designed to showcase the core functionality of a simple social system built with PHP and file‑based storage. The project consists of two parts:  
- **Frontend client** (`Cilent.html`): a single‑page web application built with React and Material‑UI, providing the user interface.  
- **Backend API** (`index.php`): a PHP‑based RESTful API that handles user authentication, friend relationships, messaging, avatar uploads, and other business logic. All data is stored locally on the server as JSON files.

**⚠️ Important note: This project is a small‑scale technical demonstration and has not been optimized for production requirements such as high concurrency, data security, or distributed deployment. It is not suitable for large‑scale deployment or real‑world commercial use.**

---

### Key Features

1. **User System**  
   - Registration / login (username + password, passwords hashed with `password_hash`)  
   - Avatar upload (supports JPG/PNG/GIF, max 2 MB)  
   - Account deletion (supports either soft deletion or complete removal of data, configurable)

2. **Friend Management**  
   - Search for users by username or 32‑character UID  
   - Add friends (supports three verification modes: anyone can add, requires approval, or no one can add)  
   - Process friend requests (accept / reject)  
   - Display friend list with avatars

3. **Instant Messaging**  
   - One‑to‑one private chat with friends  
   - Messages automatically scroll to the bottom; auto‑scrolling pauses when the user manually scrolls up  
   - Chat history is sorted by time, with old messages automatically cleaned up (retention period configurable)

4. **Interface & Experience**  
   - Light / dark theme switching (preference saved in localStorage)  
   - Responsive layout, adapted for both mobile and desktop  
   - Hamburger menu containing an “About” page (displays a GitHub link) and an avatar upload entry

---

### Technology Stack

| Layer            | Technology                                                                                     |
|------------------|------------------------------------------------------------------------------------------------|
| **Frontend**     | HTML5 / CSS3 / JavaScript (ES6)<br>React 18 (loaded via UMD)<br>Material‑UI 5 (UI components)<br>Emotion (CSS‑in‑JS) |
| **Backend**      | PHP 7+ (native, no framework)<br>File system as a database (JSON storage)                      |
| **Communication**| RESTful API, requests made with `fetch`, CORS enabled                                          |
| **Authentication**| Token mechanism: `uid:HMAC‑SHA256(uid + password_hash, stationID)`                            |

---

### Architecture & Data Storage

#### Backend Directory Structure
```
/
├── index.php                # API entry point, routes all requests
├── station.ini              # Site configuration (stationID, message retention, deletion policy)
└── data/                    # Data directory (auto‑generated)
    ├── user_index.json      # Mapping from user ID to file number
    ├── user/                # Sharded user data (max 50 users per file)
    │   ├── user_1.json
    │   └── user_2.json
    ├── chat/friend/         # Chat history
    │   ├── {uid_A}/         # Directory per user
    │   │   └── {uid_B}.json # Messages with B (JSON lines format)
    │   └── {uid_B}/...
    └── avatar/              # User avatars (stored as uid.extension)
```

#### Key Design Points
- **No database**: All data (users, friends, messages) is stored as JSON files, leveraging the file system for persistence.  
- **User sharding**: To avoid overly large user files, user data is distributed across multiple `user_{n}.json` files, each holding up to 50 users.  
- **Message expiration**: According to the `stationNumberDaysInformationStored` setting in `station.ini`, old messages are automatically removed after the configured number of days.  
- **Authentication security**: Tokens are signed with HMAC‑SHA256, and the server verifies the signature against the user’s password hash to prevent forgery.  
- **Concurrency handling**: File locking (`flock`) is used when writing to chat files to avoid data corruption from simultaneous requests.

---

### Deployment Instructions

1. **Requirements**  
   - PHP 7.4 or higher  
   - Support for file operations such as `glob` and `file_put_contents`  
   - A web server (Apache / Nginx) with proper URL rewriting (optional, to hide `index.php`)

2. **Quick Deployment**  
   - Upload `index.php` to any directory on your server.  
   - Ensure the `data/` directory and its subdirectories have write permissions (e.g., `chmod 755` or higher).  
   - The frontend `Cilent.html` can be hosted on any web server. On first use, you will need to enter the backend API URL (the full path to `index.php`) in the login interface.

3. **Configuration**  
   - `station.ini` is automatically generated on first run and can be modified manually:  
     ```ini
     [station]
     stationID = "random 16‑character string"     # Unique site identifier, affects token generation
     stationNumberDaysInformationStored = 3       # Days to retain chat history
     stationWhetherCompletelyDeleteUserData = true   # true = hard deletion, false = soft deletion
     ```

---

### Limitations (Why It Is Not Suitable for Large‑Scale Deployment)

| Aspect             | Issue                                                                                                                               |
|--------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| **Performance**    | All data is stored and read via file operations; there are no indexes or caching. As user numbers grow, operations like searching for friends or users will traverse all JSON files, becoming extremely inefficient. |
| **Concurrency**    | File locks provide only basic protection. Under high concurrency, the system is prone to blocking or data corruption.                                             |
| **Scalability**    | The sharding strategy is simplistic and cannot be scaled horizontally. Chat history is stored in single files, which can become large over time and impact performance. |
| **Security**       | Passwords are hashed with bcrypt, but tokens never expire (no refresh mechanism). The API lacks rate limiting, making it vulnerable to brute‑force attacks or abuse. |
| **Reliability**    | No data backups, no transactions. Server crashes or file write failures can lead to data loss.                                                         |
| **Feature Completeness** | Missing essential modern IM features such as group chats, read receipts, offline push notifications, and rich media support.                                 |
| **Deployment Complexity** | Depends on file system permissions and cannot share data across multiple servers (no centralized storage).                                                       |

---

### Summary

EosMesh is a demonstration project suitable for learning PHP and file‑based storage architectures. It illustrates the basic modules required to build a simple social system from scratch. The code is well‑structured and follows a clear separation between frontend and backend, making it easy to understand concepts such as RESTful API design, token authentication, and frontend state management. **However, please note that this project does not meet the high‑availability, high‑performance, or high‑security standards required for production environments. It is intended only for personal study, internal testing, or technical demonstration.**

**License**: GPL v3


