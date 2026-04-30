# EosMesh 项目介绍

EosMesh 是一个轻量级的去中心化即时通讯（IM）演示项目，旨在展示基于 PHP + 文件存储的简易社交系统核心功能。项目由两部分构成：

- **前端客户端**（`Cilent.html`）：单页面 Web 应用，使用 React + Material-UI 构建。
- **后端 API**（`index.php`）：PHP 编写的 RESTful 接口，处理用户认证、好友关系、消息收发、头像上传等业务逻辑，所有数据以 JSON 文件形式存储在服务器本地。

![图标](icon.png)

> ⚠️ **重要说明**：本项目仅为小型技术演示，未针对高并发、数据安全、分布式部署等生产环境需求进行优化，**不适合大规模部署或实际商业应用**。

---

## 主要功能

1. **用户系统**
   - 注册/登录（用户名 + 密码，密码使用 `password_hash` 加密）
   - 头像上传（支持 JPG/PNG/GIF，最大 2MB）
   - 账号注销（支持软删除或彻底删除，可配置）

2. **好友管理**
   - 通过用户名或 32 位 UID 搜索用户
   - 添加好友（三种验证方式：任何人可加、需验证、禁止添加）
   - 处理好友申请（接受/拒绝）
   - 显示好友列表及头像

3. **即时通讯**
   - 与好友进行一对一私聊
   - 消息自动滚动至底部，支持手动滚动时暂停自动滚动
   - 聊天记录按时间排序，并定期清理过期消息（可配置保留天数）

4. **界面与体验**
   - 亮色/暗色主题切换（偏好保存于 localStorage）
   - 响应式布局，适配移动端与桌面端
   - 汉堡菜单包含关于页面（显示 GitHub 链接与版本号）和头像上传入口

---

## 技术栈

| 层级         | 技术                                                                 |
|--------------|----------------------------------------------------------------------|
| **前端 (Web)** | HTML5/CSS3/JS (ES6)，React 18 (UMD)，Material-UI 5，Emotion          |
| **前端 (Android)** | Kotlin，Material Design，RecyclerView，协程，EncryptedSharedPreferences |
| **后端**     | PHP 7.4+ (原生，无框架)，文件系统作为数据库 (JSON 存储)               |
| **通信**     | RESTful API，`fetch` 请求，支持 CORS                                 |
| **认证**     | Token 机制：`uid:HMAC‑SHA256(uid+密码哈希, stationID)`，有效期5分钟   |

---


### 关键设计说明

- **无数据库**：所有数据（用户、好友关系、消息）均以 JSON 文件存储，利用文件系统实现持久化。
- **用户数据分片**：每个用户独立文件，避免单文件过大；通过 `username_map.json` 快速查找 UID。
- **消息过期**：根据 `station.ini` 中的 `stationNumberDaysInformationStored` 配置，自动清理超过保留天数的聊天记录。
- **认证安全**：Token 基于 HMAC-SHA256 签名，有效期 5 分钟，每 4 分钟可刷新一次，降低盗用风险。
- **并发处理**：对聊天文件的写入使用 `flock` 进行文件锁，避免多请求同时写入导致数据损坏。

---

## 部署说明

### 环境要求

- PHP 7.4 或更高版本
- 支持 `glob`、`file_put_contents` 等文件操作函数
- Web 服务器（Apache/Nginx），建议配置 URL 重写以隐藏 `index.php`

### 快速部署

1. 将 `index.php` 上传至服务器任意目录。
2. 确保 `data/` 目录及子目录具有读写权限（如 `chmod 755`）。
3. 前端 `Cilent.html` 可放在任意 Web 服务器，首次访问时需在登录界面填写后端 API 地址（即 `index.php` 的完整 URL）。

### 配置说明

`station.ini` 在首次运行时自动生成，可手动修改：

```ini
[station]
stationID = "随机16位字符串"                # 站点唯一标识，影响 Token 计算
stationNumberDaysInformationStored = 3     # 聊天记录保留天数
stationWhetherCompletelyDeleteUserData = true   # true=彻底删除，false=软删除
```
---

## 项目局限性（为什么不适合大规模部署）

| 方面           | 问题描述                                                                                                 |
|----------------|----------------------------------------------------------------------------------------------------------|
| **性能**       | 所有数据基于文件读写，无索引和缓存。用户量增加时，操作效率极低。                                           |
| **并发能力**   | 文件锁仅提供基础保护，高并发下易阻塞或数据损坏。                                                          |
| **扩展性**     | 数据分片策略简单，无法水平扩展。聊天记录单文件存储，长期运行后影响读写速度。                               |
| **安全性**     | 密码 bcrypt 哈希存储，但 Token 虽有 5 分钟有效期和刷新机制，仍无速率限制，易被暴力破解。                  |
| **可靠性**     | 无数据备份、无事务，服务器故障或写入失败可能导致数据丢失。                                                |
| **功能完整度** | 缺少群聊、消息已读回执、离线推送、多媒体消息等现代 IM 核心功能。                                          |
| **部署复杂度** | 依赖文件系统权限，无法在多服务器间共享数据（无集中存储）。                                                 |

---

## 总结

EosMesh 是一个适合学习 PHP + 文件存储架构的演示项目，展示了从零构建简易社交系统所需的基本模块。代码结构清晰、前后端分离，便于理解 RESTful API 设计、Token 认证、前端状态管理等概念。**但该项目不满足生产环境的高可用、高性能、高安全标准，仅推荐用于个人学习、内部测试或技术展示。**

**项目作者**：GitHub [@NTdebug145](https://github.com/NTdebug145)  
**许可证**：GPL v3

---

## 附加说明

- **Android 客户端**：项目同时提供了一个原生 Android 客户端（Kotlin），支持相同的 API 接口，具备消息推送、头像缓存、加密存储等功能。客户端版本号：`b26.4.30`。
- **Token 刷新**：服务端实现了 Token 刷新接口 `refresh_token`，客户端会每 4 分钟自动刷新，保证长期在线。
- **头像缓存**：Android 客户端根据头像 MD5 值进行缓存，减少网络请求。

---

### EosMesh Project Introduction

EosMesh is a lightweight, decentralized instant messaging (IM) demonstration project designed to showcase the core functionality of a simple social system built with PHP and file‑based storage. The project consists of two parts:

- **Frontend client** (`Cilent.html`): a single‑page web application built with React and Material‑UI.
- **Backend API** (`index.php`): a PHP‑based RESTful API that handles user authentication, friend relationships, messaging, avatar uploads, and other business logic. All data is stored locally on the server as JSON files.

![图标](icon.png)

> **⚠️ Important note**: This project is a small‑scale technical demonstration and has not been optimized for production requirements such as high concurrency, data security, or distributed deployment. It is not suitable for large‑scale deployment or real‑world commercial use.

### Key Features

1. **User System**: Registration/login, avatar upload, account deletion (configurable soft/hard delete).
2. **Friend Management**: Search by username/UID, add friends (three verification modes), process requests, display friend list with avatars.
3. **Instant Messaging**: One‑to‑one private chat, auto-scroll, message expiration cleanup.
4. **Interface & Experience**: Light/dark theme toggle, responsive layout, hamburger menu with about page and avatar upload.

### Technology Stack

- **Frontend (Web)**: HTML5/CSS3/JS (ES6), React 18, Material-UI 5, Emotion.
- **Frontend (Android)**: Kotlin, Material Design, Coroutines, EncryptedSharedPreferences.
- **Backend**: PHP 7.4+ (native, no framework), file-based JSON storage.
- **Communication**: RESTful API, CORS enabled.
- **Authentication**: Token mechanism `uid:HMAC‑SHA256(uid+password_hash, stationID)` with 5-minute validity and refresh support.

### Deployment Instructions

- PHP 7.4+ with file write permissions.
- Upload `index.php` to server, ensure `data/` directory is writable.
- Frontend (`Cilent.html`) can be hosted separately; enter API URL on first login.
- Configuration via `station.ini` (auto-generated).

### Limitations

- No indexes, caches, or transactions → poor performance and reliability under load.
- File locks provide basic concurrency only.
- Lacks modern IM features (group chat, read receipts, offline push).
- Not suitable for multi-server deployment.

### Summary

EosMesh is a learning-oriented demonstration project for PHP file-based architectures. It is **not production-ready** but is ideal for studying RESTful APIs, token authentication, and frontend state management.

**License**: GPL v3
