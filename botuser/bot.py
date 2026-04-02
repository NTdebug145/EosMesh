#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EosMesh 机器人 - 持续运行，自动回复指令
"""

import requests
import json
import time
import sys
import os
from typing import Optional, Dict, Any, List

# ==================== 配置 ====================
BASE_URL = "http://127.0.0.1:8001/SomeTest/EosMesh/index.php"   # 修改为你的实际地址
BOT_USERNAME = "eos_bot"
BOT_PASSWORD = "bot123456"
CRED_FILE = "bot_cred.json"
POLL_INTERVAL = 3           # 检查新消息间隔（秒）
MAX_MESSAGE_CACHE = 100     # 每个好友最多缓存的消息时间戳数量
# =============================================

class EosMeshBot:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip('/')
        self.token: Optional[str] = None
        self.uid: Optional[str] = None
        self.last_msg_time: Dict[str, int] = {}   # 记录每个好友最后处理的消息时间戳

    def _request(self, action: str, data: Optional[Dict] = None, files: Optional[Dict] = None) -> Dict[str, Any]:
        url = f"{self.base_url}?action={action}"
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = self.token

        try:
            if files:
                resp = requests.post(url, data=data, files=files, headers=headers, timeout=10)
            else:
                resp = requests.post(url, json=data, headers=headers, timeout=10)
        except requests.exceptions.ConnectionError as e:
            raise Exception(f"连接失败: {e}")

        if resp.status_code != 200:
            raise Exception(f"HTTP {resp.status_code}: {resp.text}")

        result = resp.json()
        if result.get("code") != 200:
            raise Exception(f"API 错误: {result.get('msg')}")
        return result

    def register(self, username: str, password: str) -> tuple:
        result = self._request("register", {"username": username, "password": password})
        data = result.get("data", {})
        return data.get("uid"), data.get("token")

    def login(self, username: str, password: str) -> tuple:
        result = self._request("login", {"username": username, "password": password})
        data = result.get("data", {})
        return data.get("uid"), data.get("token")

    def get_friends(self) -> List[Dict]:
        """返回好友列表，每个元素包含 uid, username 等"""
        result = self._request("get_friends")
        return result.get("data", [])

    def get_messages(self, friend_uid: str) -> List[Dict]:
        """获取与某个好友的所有消息（按时间升序）"""
        url = f"{self.base_url}?action=get_messages&friend_uid={friend_uid}"
        headers = {"Authorization": self.token}
        resp = requests.get(url, headers=headers, timeout=10)
        if resp.status_code != 200:
            raise Exception(f"HTTP {resp.status_code}: {resp.text}")
        result = resp.json()
        if result.get("code") != 200:
            raise Exception(f"API 错误: {result.get('msg')}")
        messages = result.get("data", [])
        # 确保按时间排序
        messages.sort(key=lambda x: x.get("time", 0))
        return messages

    def send_message(self, to_uid: str, content: str):
        self._request("send_message", {"to_uid": to_uid, "content": content})

    def get_new_messages(self, friend_uid: str) -> List[Dict]:
        """获取该好友发来的、未被处理过的新消息（只处理对方发给机器人的）"""
        all_msgs = self.get_messages(friend_uid)
        last_time = self.last_msg_time.get(friend_uid, 0)
        new_msgs = []
        for msg in all_msgs:
            # 只处理对方发给机器人的消息（from == friend_uid，to == 机器人uid）
            if msg.get("from") == friend_uid and msg.get("to") == self.uid:
                msg_time = msg.get("time", 0)
                if msg_time > last_time:
                    new_msgs.append(msg)
        # 更新最后处理时间戳（取新消息中的最大时间）
        if new_msgs:
            max_time = max(m.get("time", 0) for m in new_msgs)
            self.last_msg_time[friend_uid] = max_time
        return new_msgs

def handle_command(content: str) -> str:
    """根据消息内容生成回复"""
    cmd = content.strip().lower()
    if cmd in ["help", "帮助", "/help"]:
        return (
            "🤖 机器人帮助：\n"
            "- help / 帮助：显示本消息\n"
            "- time / 时间：显示当前服务器时间\n"
            "- ping：返回 pong\n"
            "- 其他消息：原样返回（测试用）"
        )
    elif cmd in ["time", "时间"]:
        from datetime import datetime
        now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        return f"🕐 当前时间：{now}"
    elif cmd == "ping":
        return "pong"
    else:
        # 默认回复：回显消息
        return f"收到：{content}"

def load_credentials() -> Optional[tuple]:
    if os.path.exists(CRED_FILE):
        with open(CRED_FILE, "r") as f:
            cred = json.load(f)
            return cred.get("uid"), cred.get("token")
    return None

def save_credentials(uid: str, token: str):
    with open(CRED_FILE, "w") as f:
        json.dump({"uid": uid, "token": token}, f)

def main():
    print("🤖 EosMesh 机器人启动中...")
    bot = EosMeshBot(BASE_URL)

    # 登录或注册
    cred = load_credentials()
    if cred:
        bot.uid, bot.token = cred
        print(f"✅ 加载凭证，UID: {bot.uid}")
        try:
            bot.get_friends()  # 验证 token
            print("✅ 凭证有效")
        except Exception as e:
            print(f"⚠️ 凭证无效: {e}，尝试重新登录...")
            cred = None

    if not cred:
        try:
            bot.uid, bot.token = bot.login(BOT_USERNAME, BOT_PASSWORD)
            print(f"✅ 登录成功，UID: {bot.uid}")
        except Exception:
            print("⚠️ 登录失败，正在注册新账号...")
            bot.uid, bot.token = bot.register(BOT_USERNAME, BOT_PASSWORD)
            print(f"✅ 注册成功，UID: {bot.uid}")
        save_credentials(bot.uid, bot.token)

    print(f"🤖 机器人已上线，UID: {bot.uid}")
    print(f"🔁 开始轮询新消息（间隔 {POLL_INTERVAL} 秒），按 Ctrl+C 停止...")

    # 主循环：定期获取好友列表，检查每个好友的新消息
    friends_list = []       # 缓存好友列表 [{uid, username}]
    last_friend_refresh = 0
    FRIEND_REFRESH_INTERVAL = 60   # 每60秒刷新一次好友列表

    try:
        while True:
            now = time.time()
            # 定期刷新好友列表（避免好友变动）
            if now - last_friend_refresh > FRIEND_REFRESH_INTERVAL:
                try:
                    friends_list = bot.get_friends()
                    last_friend_refresh = now
                    print(f"📇 好友列表已刷新，共 {len(friends_list)} 位好友")
                except Exception as e:
                    print(f"⚠️ 获取好友列表失败: {e}")

            # 遍历每个好友，检查新消息
            for friend in friends_list:
                friend_uid = friend.get("uid")
                if not friend_uid:
                    continue
                try:
                    new_msgs = bot.get_new_messages(friend_uid)
                    for msg in new_msgs:
                        content = msg.get("content", "")
                        print(f"📩 来自 {friend.get('username', friend_uid)}: {content}")
                        # 生成回复
                        reply = handle_command(content)
                        # 发送回复
                        bot.send_message(friend_uid, reply)
                        print(f"📤 回复: {reply}")
                except Exception as e:
                    print(f"❌ 处理好友 {friend_uid} 消息时出错: {e}")

            time.sleep(POLL_INTERVAL)

    except KeyboardInterrupt:
        print("\n🛑 机器人已停止")

if __name__ == "__main__":
    main()