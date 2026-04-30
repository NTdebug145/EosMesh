/**
 * EosMesh JavaScript SDK
 * 去中心化API收发解析库
 * 内置Token自动管理与刷新、请求重试、错误处理、好友/消息等完整操作
 */

class EosMeshClient {
    /**
     * 构造函数
     * @param {string} baseUrl - API基础地址，例如 'https://your-station.com/index.php' 或 '/api.php'
     * @param {Object} options - 配置项
     * @param {string} options.token - 初始Token（如从本地存储恢复）
     * @param {string} options.userId - 初始用户ID
     * @param {boolean} options.autoRefresh - 是否自动刷新过期的Token，默认true
     * @param {Function} options.onTokenUpdate - Token更新时的回调 (newToken) => {}
     * @param {number} options.refreshCooldown - 刷新冷却时间(毫秒)，对应服务端4分钟限制，默认240000
     */
    constructor(baseUrl, options = {}) {
        this.baseUrl = baseUrl;
        this.token = options.token || null;
        this.userId = options.userId || null;
        this.autoRefresh = options.autoRefresh !== false;
        this.onTokenUpdate = options.onTokenUpdate || (() => {});
        this.refreshCooldown = options.refreshCooldown || 240000; // 4分钟
        this._lastRefreshTime = 0;           // 上次刷新时间戳(ms)
        this._refreshPromise = null;          // 防止并发刷新
        this._pendingQueue = [];               // 等待刷新期间挂起的请求
    }

    // ======================== 辅助方法 ========================

    /** 设置Token并触发回调 */
    setToken(token) {
        this.token = token;
        if (token) this.onTokenUpdate(token);
    }

    /** 获取当前Token */
    getToken() {
        return this.token;
    }

    /** 设置当前用户ID */
    setUserId(userId) {
        this.userId = userId;
    }

    /** 获取当前用户ID */
    getUserId() {
        return this.userId;
    }

    /** 清除本地认证信息 */
    clearAuth() {
        this.token = null;
        this.userId = null;
        this.onTokenUpdate(null);
    }

    /** 生成头像URL（公共接口，无需token） */
    getAvatarUrl(uid) {
        return `${this.baseUrl}?action=get_avatar&uid=${encodeURIComponent(uid)}`;
    }

    // ======================== 核心请求方法 ========================

    /**
     * 通用请求方法（自动处理JSON、FormData、重试、刷新Token）
     * @param {string} action - API动作名
     * @param {string} method - HTTP方法 'GET'|'POST'
     * @param {Object|FormData|null} data - 请求数据，GET时附加Query，POST时自动JSON或FormData
     * @param {Object} extra - 额外选项: { isFormData: true, skipAuth: false, retry: true }
     * @returns {Promise<any>} 返回解析后的数据（data字段或完整响应）
     */
    async _request(action, method = 'POST', data = null, extra = {}) {
        const { isFormData = false, skipAuth = false, retry = true } = extra;
        const url = new URL(this.baseUrl);
        url.searchParams.set('action', action);

        let body = null;
        let headers = {};

        if (!skipAuth && this.token) {
            headers['Authorization'] = this.token;
        }

        if (method === 'GET') {
            if (data && typeof data === 'object') {
                Object.entries(data).forEach(([key, val]) => {
                    if (val !== undefined && val !== null) url.searchParams.set(key, val);
                });
            }
        } else {
            if (isFormData && data instanceof FormData) {
                body = data;
                // 不设置Content-Type，让浏览器自动设置boundary
            } else {
                headers['Content-Type'] = 'application/json';
                body = JSON.stringify(data || {});
            }
        }

        const fetchOptions = {
            method,
            headers,
            body,
        };

        try {
            const response = await fetch(url.toString(), fetchOptions);
            // 处理图片等非JSON响应（例如get_avatar）
            const contentType = response.headers.get('content-type');
            if (contentType && contentType.includes('image/')) {
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                const blob = await response.blob();
                return { blob, url: URL.createObjectURL(blob) };
            }

            // 标准JSON响应
            const result = await response.json();
            if (!response.ok || result.code !== 200) {
                const err = new Error(result.msg || 'Request failed');
                err.code = result.code;
                err.response = result;
                throw err;
            }
            return result.data;   // 统一返回data字段
        } catch (error) {
            // 处理401 Token无效/过期 且 允许重试 且 不是跳过认证的请求
            if (error.code === 401 && !skipAuth && retry && this.autoRefresh && this.token) {
                // 尝试刷新Token
                const refreshed = await this._attemptRefresh();
                if (refreshed) {
                    // 刷新成功，重试当前请求（retry=false防止无限递归）
                    return this._request(action, method, data, { ...extra, retry: false });
                } else {
                    // 刷新失败，清除认证
                    this.clearAuth();
                    throw new Error('Token expired and refresh failed, please login again');
                }
            }
            throw error;
        }
    }

    /**
     * 内部刷新Token逻辑（带冷却检测及并发控制）
     * @returns {Promise<boolean>} 刷新是否成功
     */
    async _attemptRefresh() {
        // 已有刷新请求在进行中，直接等待其结果
        if (this._refreshPromise) {
            return this._refreshPromise;
        }

        this._refreshPromise = (async () => {
            const now = Date.now();
            // 检查服务端冷却时间（每4分钟才允许刷新）
            if (this._lastRefreshTime && (now - this._lastRefreshTime) < this.refreshCooldown) {
                const waitTime = this.refreshCooldown - (now - this._lastRefreshTime);
                console.warn(`Token刷新过于频繁，等待 ${Math.ceil(waitTime / 1000)} 秒`);
                await new Promise(resolve => setTimeout(resolve, waitTime));
            }

            try {
                // 调用刷新接口（skipAuth=false 会携带当前token）
                const newTokenData = await this.refreshToken();
                if (newTokenData && newTokenData.token) {
                    this.setToken(newTokenData.token);
                    this._lastRefreshTime = Date.now();
                    return true;
                }
                return false;
            } catch (e) {
                console.error('刷新Token失败', e);
                return false;
            } finally {
                this._refreshPromise = null;
            }
        })();

        return this._refreshPromise;
    }

    // ======================== 公开API（按字母/功能排序） ========================

    /**
     * 接受好友请求（使用request_id，即对方uid）
     * @param {string} requestId - 好友请求发起者的uid
     * @returns {Promise<any>}
     */
    async acceptFriendRequest(requestId) {
        return this._request('accept_friend_request', 'POST', { request_id: requestId });
    }

    /**
     * 添加好友（根据对方用户名或uid自动处理请求/直接添加）
     * @param {string} username - 对方用户名
     * @returns {Promise<any>}
     */
    async addFriend(username) {
        return this._request('add_friend', 'POST', { username });
    }

    /**
     * 删除当前账户（根据站点头配置决定彻底删除或软删除）
     * @returns {Promise<any>}
     */
    async deleteAccount() {
        const result = await this._request('delete_account', 'POST', {});
        this.clearAuth();
        return result;
    }

    /**
     * 获取好友头像MD5列表（用于增量更新）
     * @returns {Promise<Object>} 键值对 uid -> md5
     */
    async getFriendAvatarImgMd5() {
        return this._request('get_friend_avatar_img_md5', 'GET');
    }

    /**
     * 获取好友请求列表（仅pending状态）
     * @returns {Promise<Array>} 每个元素包含 from_uid, from_username, message, time
     */
    async getFriendRequests() {
        return this._request('get_friend_requests', 'GET');
    }

    /**
     * 获取好友列表（含用户名、头像url、注册时间等）
     * @returns {Promise<Array>}
     */
    async getFriends() {
        return this._request('get_friends', 'GET');
    }

    /**
     * 获取与指定好友的聊天记录（支持分页和增量）
     * @param {string} friendUid - 好友uid
     * @param {Object} options - { since?: number, page?: number, limit?: number }
     * @returns {Promise<Object|Array>} 若使用since返回消息数组，否则返回分页对象 { messages, total, page, limit, total_pages }
     */
    async getMessages(friendUid, options = {}) {
        const params = { friend_uid: friendUid, ...options };
        return this._request('get_messages', 'GET', params);
    }

    /**
     * 获取服务端类型 (php)
     * @returns {Promise<string>} type
     */
    async getServerType() {
        return this._request('get_server_type', 'GET', null, { skipAuth: true });
    }

    /**
     * 获取站点ID
     * @returns {Promise<string>} station_id
     */
    async getStationId() {
        const data = await this._request('get_station_id', 'GET', null, { skipAuth: true });
        return data.station_id;
    }

    /**
     * 获取站点版本号
     * @returns {Promise<string>} version
     */
    async getStationVersion() {
        const data = await this._request('get_station_version', 'GET', null, { skipAuth: true });
        return data.version;
    }

    /**
     * 获取用户信息（公开信息，不需要是好友）
     * @param {string} uidOrName - 用户uid或用户名
     * @returns {Promise<Object>}
     */
    async getUserInfo(uidOrName) {
        return this._request('get_user_info', 'GET', { uid: uidOrName }, { skipAuth: false });
    }

    /**
     * 获取当前用户的加好友验证模式
     * @returns {Promise<string>} 'allow_all'|'need_verify'|'deny_all'
     */
    async getVerifySetting() {
        return this._request('get_verify_setting', 'GET');
    }

    /**
     * 处理好友请求（接受或拒绝）
     * @param {string} fromUid - 发起请求者的uid
     * @param {string} action - 'accept' 或 'reject'
     * @returns {Promise<any>}
     */
    async handleFriendRequest(fromUid, action) {
        return this._request('handle_friend_request', 'POST', { from_uid: fromUid, action });
    }

    /**
     * 登录
     * @param {string} username
     * @param {string} password
     * @returns {Promise<Object>} { uid, token }
     */
    async login(username, password) {
        const result = await this._request('login', 'POST', { username, password }, { skipAuth: true });
        if (result.uid && result.token) {
            this.setToken(result.token);
            this.setUserId(result.uid);
        }
        return result;
    }

    /**
     * 刷新Token（内部调用，也可手动调用）
     * @returns {Promise<Object>} { token }
     */
    async refreshToken() {
        const result = await this._request('refresh_token', 'POST', {}, { skipAuth: false });
        return result;
    }

    /**
     * 注册新用户
     * @param {string} username - 最大12字符
     * @param {string} password
     * @returns {Promise<Object>} { uid, token }
     */
    async register(username, password) {
        const result = await this._request('register', 'POST', { username, password }, { skipAuth: true });
        if (result.uid && result.token) {
            this.setToken(result.token);
            this.setUserId(result.uid);
        }
        return result;
    }

    /**
     * 拒绝好友请求（便捷方法，基于handleFriendRequest）
     * @param {string} fromUid
     * @returns {Promise<any>}
     */
    async rejectFriendRequest(fromUid) {
        return this.handleFriendRequest(fromUid, 'reject');
    }

    /**
     * 发送消息给好友
     * @param {string} toUid - 好友uid
     * @param {string} content - 文本内容，最大1500字符
     * @returns {Promise<any>}
     */
    async sendMessage(toUid, content) {
        if (content.length > 1500) throw new Error('Message too long (max 1500 chars)');
        return this._request('send_message', 'POST', { to_uid: toUid, content });
    }

    /**
     * 设置当前用户的加好友验证模式
     * @param {string} mode - 'allow_all', 'need_verify', 'deny_all'
     * @returns {Promise<any>}
     */
    async setVerifySetting(mode) {
        if (!['allow_all', 'need_verify', 'deny_all'].includes(mode)) {
            throw new Error('Invalid mode. Use allow_all, need_verify, or deny_all');
        }
        return this._request('set_verify_setting', 'POST', { mode });
    }

    /**
     * 上传头像
     * @param {File|Blob} avatarFile - 图片文件（jpeg/png/gif），最大2MB
     * @returns {Promise<any>}
     */
    async uploadAvatar(avatarFile) {
        const formData = new FormData();
        formData.append('avatar', avatarFile);
        return this._request('upload_avatar', 'POST', formData, { isFormData: true });
    }

    // ======================== 便捷辅助 ========================

    /** 判断是否已登录（有token） */
    isAuthenticated() {
        return !!this.token;
    }
}

// 支持CommonJS和ES模块导出
if (typeof module !== 'undefined' && module.exports) {
    module.exports = EosMeshClient;
} else {
    window.EosMeshClient = EosMeshClient;
}