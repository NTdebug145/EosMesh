#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import sys
import json
import time
import random
import string
import hashlib
import hmac
import shutil
import socket
from functools import wraps

import bcrypt
from flask import Flask, request, jsonify, send_file, abort

# ---------- 全局常量 ----------
STATION_VERSION = 'b26.4.2'
SERVER_TYPE = 'python'

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(BASE_DIR, 'data')
USER_DIR = os.path.join(DATA_DIR, 'user')
CHAT_DIR = os.path.join(DATA_DIR, 'chat', 'friend')
AVATAR_DIR = os.path.join(DATA_DIR, 'avatar')
CONFIG_FILE = os.path.join(BASE_DIR, 'station.ini')
USER_INDEX_FILE = os.path.join(DATA_DIR, 'user_index.json')

stationID = None
daysToKeep = 3
completelyDelete = True

os.makedirs(DATA_DIR, exist_ok=True)
os.makedirs(USER_DIR, exist_ok=True)
os.makedirs(CHAT_DIR, exist_ok=True)
os.makedirs(AVATAR_DIR, exist_ok=True)

# ---------- 配置 ----------
def init_config():
    global stationID, daysToKeep, completelyDelete
    if not os.path.exists(CONFIG_FILE):
        new_id = generate_random_string(16)
        with open(CONFIG_FILE, 'w') as f:
            f.write(f"[station]\nstationID={new_id}\nstationNumberDaysInformationStored=3\nstationWhetherCompletelyDeleteUserData=true\n")
    config = {}
    with open(CONFIG_FILE, 'r') as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith('[') and '=' in line:
                key, val = line.split('=', 1)
                config[key] = val
    stationID = config.get('stationID', generate_random_string(16))
    daysToKeep = int(config.get('stationNumberDaysInformationStored', 3))
    completelyDelete = config.get('stationWhetherCompletelyDeleteUserData', 'true').lower() == 'true'

def generate_random_string(length):
    chars = string.ascii_uppercase + string.digits
    return ''.join(random.choice(chars) for _ in range(length))

def get_user_file_by_id(uid):
    if not os.path.exists(USER_INDEX_FILE):
        return None
    with open(USER_INDEX_FILE, 'r') as f:
        index = json.load(f)
    file_num = index.get(uid)
    if file_num is None:
        return None
    return os.path.join(USER_DIR, f'user_{file_num}.json')

def get_user_data(uid):
    file_path = get_user_file_by_id(uid)
    if not file_path or not os.path.exists(file_path):
        return None
    with open(file_path, 'r') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            user = json.loads(line)
            if user.get('id') == uid and not user.get('deleted', False):
                return user
    return None

def save_user_data(user):
    uid = user['id']
    index = {}
    if os.path.exists(USER_INDEX_FILE):
        with open(USER_INDEX_FILE, 'r') as f:
            index = json.load(f)
    if uid not in index:
        files = [f for f in os.listdir(USER_DIR) if f.startswith('user_') and f.endswith('.json')]
        max_num = 0
        for f in files:
            try:
                num = int(f.split('_')[1].split('.')[0])
                if num > max_num:
                    max_num = num
            except:
                pass
        target_num = None
        for i in range(1, max_num + 2):
            file_path = os.path.join(USER_DIR, f'user_{i}.json')
            if not os.path.exists(file_path):
                target_num = i
                break
            with open(file_path, 'r') as f:
                line_count = sum(1 for _ in f)
            if line_count < 50:
                target_num = i
                break
        if target_num is None:
            target_num = max_num + 1
        index[uid] = target_num
        with open(USER_INDEX_FILE, 'w') as f:
            json.dump(index, f)
    file_num = index[uid]
    file_path = os.path.join(USER_DIR, f'user_{file_num}.json')
    users = []
    if os.path.exists(file_path):
        with open(file_path, 'r') as f:
            for line in f:
                line = line.strip()
                if line:
                    u = json.loads(line)
                    if u['id'] == uid:
                        users.append(user)
                    else:
                        users.append(u)
    else:
        users.append(user)
    with open(file_path, 'w') as f:
        for u in users:
            f.write(json.dumps(u) + '\n')

def update_user_data(uid, callback):
    user = get_user_data(uid)
    if not user:
        return False
    user = callback(user)
    save_user_data(user)
    return True

def authenticate(token):
    if not token:
        abort(401, description='Missing token')
    parts = token.split(':')
    if len(parts) != 2:
        abort(401, description='Invalid token format')
    uid, signature = parts
    user = get_user_data(uid)
    if not user:
        abort(401, description='User not found')
    expected = hmac.new(stationID.encode(), (uid + user['password']).encode(), hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, signature):
        abort(401, description='Invalid token')
    return user

def append_message_to_file(file_path, message):
    messages = []
    if os.path.exists(file_path):
        with open(file_path, 'r') as f:
            for line in f:
                line = line.strip()
                if line:
                    messages.append(json.loads(line))
    messages.append(message)
    cutoff = int(time.time()) - (daysToKeep * 86400)
    messages = [m for m in messages if m['time'] >= cutoff]
    with open(file_path, 'w') as f:
        for m in messages:
            f.write(json.dumps(m) + '\n')

def login_required(f):
    @wraps(f)
    def decorated(*args, **kwargs):
        token = request.headers.get('Authorization')
        if not token:
            token = request.args.get('token')
        if not token:
            return jsonify({'code': 401, 'msg': 'Missing token'}), 401
        try:
            user = authenticate(token)
            request.current_user = user
            return f(*args, **kwargs)
        except Exception as e:
            return jsonify({'code': 401, 'msg': str(e)}), 401
    return decorated

# ---------- Flask 应用 ----------
app = Flask(__name__)

# 全局 CORS 配置（只设置一次，避免重复）
@app.after_request
def after_request(response):
    response.headers.add('Access-Control-Allow-Origin', '*')
    response.headers.add('Access-Control-Allow-Headers', 'Content-Type,Authorization')
    response.headers.add('Access-Control-Allow-Methods', 'GET,POST,OPTIONS')
    return response

# 处理 OPTIONS 预检请求（所有路径）
@app.route('/', defaults={'path': ''}, methods=['OPTIONS'])
@app.route('/<path:path>', methods=['OPTIONS'])
def options_handler(path):
    return '', 200

# ---------- API 端点（全部在根路径）----------
@app.route('/', methods=['POST'])
def handle_post():
    action = request.args.get('action')
    if action == 'register':
        return register()
    elif action == 'login':
        return login()
    elif action == 'upload_avatar':
        return upload_avatar()
    elif action == 'add_friend':
        return add_friend()
    elif action == 'handle_friend_request':
        return handle_friend_request()
    elif action == 'send_message':
        return send_message()
    elif action == 'delete_account':
        return delete_account()
    elif action == 'set_verify_setting':
        return set_verify_setting()
    elif action == 'accept_friend_request':
        return accept_friend_request()
    else:
        return jsonify({'code': 400, 'msg': 'Invalid action'}), 400

@app.route('/', methods=['GET'])
def handle_get():
    action = request.args.get('action')
    if action == 'get_messages':
        return get_messages()
    elif action == 'get_station_version':
        return get_station_version()
    elif action == 'get_server_type':
        return get_server_type()
    elif action == 'get_verify_setting':
        return get_verify_setting()
    elif action == 'get_friend_requests':
        return get_friend_requests()
    elif action == 'get_avatar':
        return get_avatar()
    elif action == 'get_friends':
        return get_friends()
    elif action == 'get_user_info':
        return get_user_info()
    elif action == 'get_station_id':
        return get_station_id()
    else:
        return jsonify({'code': 400, 'msg': 'Invalid action'}), 400

# 实现各个函数（与之前相同，但移除内部 CORS 设置，并且路由改为根路径后无需改变）
# 注意：这些函数内部不应再手动添加 CORS 头，因为 after_request 已经全局处理。

def register():
    data = request.get_json()
    username = data.get('username', '').strip()
    password = data.get('password', '')
    if not username or not password:
        return jsonify({'code': 400, 'msg': 'Username and password required'}), 400
    if len(username) > 12:
        return jsonify({'code': 400, 'msg': 'Username too long (max 12 characters)'}), 400
    # 检查用户名唯一性
    for root, dirs, files in os.walk(USER_DIR):
        for f in files:
            if f.startswith('user_') and f.endswith('.json'):
                with open(os.path.join(root, f), 'r') as fp:
                    for line in fp:
                        line = line.strip()
                        if line:
                            u = json.loads(line)
                            if u.get('username') == username and not u.get('deleted', False):
                                return jsonify({'code': 409, 'msg': 'Username already exists'}), 409
    uid = generate_random_string(32)
    password_hash = bcrypt.hashpw(password.encode(), bcrypt.gensalt()).decode()
    now = int(time.time())
    user = {
        'id': uid,
        'username': username,
        'password': password_hash,
        'friend_verify': 'need_verify',
        'registered_at': now,
        'station_id': stationID,
        'friend_requests': [],
        'friends': [],
        'message_count': 0,
        'avatar': None,
        'deleted': False
    }
    save_user_data(user)
    token = f"{uid}:{hmac.new(stationID.encode(), (uid + password_hash).encode(), hashlib.sha256).hexdigest()}"
    return jsonify({'code': 200, 'msg': 'Registered successfully', 'data': {'uid': uid, 'token': token}}), 200

def login():
    data = request.get_json()
    username = data.get('username', '').strip()
    password = data.get('password', '')
    if not username or not password:
        return jsonify({'code': 400, 'msg': 'Username and password required'}), 400
    user = None
    for root, dirs, files in os.walk(USER_DIR):
        for f in files:
            if f.startswith('user_') and f.endswith('.json'):
                with open(os.path.join(root, f), 'r') as fp:
                    for line in fp:
                        line = line.strip()
                        if line:
                            u = json.loads(line)
                            if u.get('username') == username and not u.get('deleted', False):
                                user = u
                                break
                if user:
                    break
        if user:
            break
    if not user or not bcrypt.checkpw(password.encode(), user['password'].encode()):
        return jsonify({'code': 401, 'msg': 'Invalid username or password'}), 401
    token = f"{user['id']}:{hmac.new(stationID.encode(), (user['id'] + user['password']).encode(), hashlib.sha256).hexdigest()}"
    return jsonify({'code': 200, 'msg': 'Login successful', 'data': {'uid': user['id'], 'token': token}}), 200

@login_required
def upload_avatar():
    if 'avatar' not in request.files:
        return jsonify({'code': 400, 'msg': 'Avatar file required'}), 400
    file = request.files['avatar']
    if file.filename == '':
        return jsonify({'code': 400, 'msg': 'No file selected'}), 400
    ext = os.path.splitext(file.filename)[1].lower()
    if ext not in ['.jpg', '.jpeg', '.png', '.gif']:
        return jsonify({'code': 400, 'msg': 'Invalid image format'}), 400
    file.seek(0, os.SEEK_END)
    size = file.tell()
    file.seek(0)
    if size > 2 * 1024 * 1024:
        return jsonify({'code': 400, 'msg': 'File too large (max 2MB)'}), 400
    avatar_path = os.path.join(AVATAR_DIR, f"{request.current_user['id']}{ext}")
    file.save(avatar_path)
    update_user_data(request.current_user['id'], lambda u: {**u, 'avatar': avatar_path})
    return jsonify({'code': 200, 'msg': 'Avatar uploaded'}), 200

@login_required
def add_friend():
    data = request.get_json()
    target_username = data.get('username', '').strip()
    if not target_username:
        return jsonify({'code': 400, 'msg': 'Target username required'}), 400
    target_user = None
    for root, dirs, files in os.walk(USER_DIR):
        for f in files:
            if f.startswith('user_') and f.endswith('.json'):
                with open(os.path.join(root, f), 'r') as fp:
                    for line in fp:
                        line = line.strip()
                        if line:
                            u = json.loads(line)
                            if not u.get('deleted', False) and (u['username'] == target_username or u['id'] == target_username):
                                target_user = u
                                break
                if target_user:
                    break
        if target_user:
            break
    if not target_user:
        return jsonify({'code': 404, 'msg': 'User not found'}), 404
    if target_user['id'] == request.current_user['id']:
        return jsonify({'code': 400, 'msg': 'Cannot add yourself'}), 400
    if target_user['id'] in request.current_user.get('friends', []):
        return jsonify({'code': 400, 'msg': 'Already friends'}), 400
    verify_mode = target_user.get('friend_verify', 'need_verify')
    if verify_mode == 'deny_all':
        return jsonify({'code': 403, 'msg': 'User does not accept friend requests'}), 403
    elif verify_mode == 'need_verify':
        req = {'from_uid': request.current_user['id'], 'time': int(time.time()), 'status': 'pending'}
        update_user_data(target_user['id'], lambda u: {**u, 'friend_requests': u.get('friend_requests', []) + [req]})
        return jsonify({'code': 200, 'msg': 'Friend request sent'}), 200
    else:
        update_user_data(request.current_user['id'], lambda u: {**u, 'friends': u.get('friends', []) + [target_user['id']]})
        update_user_data(target_user['id'], lambda u: {**u, 'friends': u.get('friends', []) + [request.current_user['id']]})
        return jsonify({'code': 200, 'msg': 'Friend added'}), 200

@login_required
def handle_friend_request():
    data = request.get_json()
    from_uid = data.get('from_uid', '')
    action_type = data.get('action', '')
    if not from_uid or action_type not in ['accept', 'reject']:
        return jsonify({'code': 400, 'msg': 'Invalid parameters'}), 400
    user = request.current_user
    updated = False
    def update_requests(u):
        nonlocal updated
        requests = u.get('friend_requests', [])
        for req in requests:
            if req['from_uid'] == from_uid and req.get('status') == 'pending':
                req['status'] = 'accepted' if action_type == 'accept' else 'rejected'
                updated = True
                break
        return {**u, 'friend_requests': requests}
    update_user_data(user['id'], update_requests)
    if updated and action_type == 'accept':
        update_user_data(user['id'], lambda u: {**u, 'friends': list(set(u.get('friends', []) + [from_uid]))})
        update_user_data(from_uid, lambda u: {**u, 'friends': list(set(u.get('friends', []) + [user['id']]))})
        return jsonify({'code': 200, 'msg': 'Friend request accepted'}), 200
    elif updated:
        return jsonify({'code': 200, 'msg': 'Friend request rejected'}), 200
    else:
        return jsonify({'code': 404, 'msg': 'Request not found'}), 404

@login_required
def send_message():
    data = request.get_json()
    to_uid = data.get('to_uid', '')
    content = data.get('content', '').strip()
    if not to_uid or not content:
        return jsonify({'code': 400, 'msg': 'Missing parameters'}), 400
    target_user = get_user_data(to_uid)
    if not target_user:
        return jsonify({'code': 404, 'msg': 'Target user not found'}), 404
    if to_uid not in request.current_user.get('friends', []) or request.current_user['id'] not in target_user.get('friends', []):
        return jsonify({'code': 403, 'msg': 'Not friends'}), 403
    message = {'from': request.current_user['id'], 'to': to_uid, 'content': content, 'time': int(time.time())}
    sender_dir = os.path.join(CHAT_DIR, request.current_user['id'])
    os.makedirs(sender_dir, exist_ok=True)
    sender_file = os.path.join(sender_dir, f"{to_uid}.json")
    append_message_to_file(sender_file, message)
    receiver_dir = os.path.join(CHAT_DIR, to_uid)
    os.makedirs(receiver_dir, exist_ok=True)
    receiver_file = os.path.join(receiver_dir, f"{request.current_user['id']}.json")
    append_message_to_file(receiver_file, message)
    update_user_data(request.current_user['id'], lambda u: {**u, 'message_count': u.get('message_count', 0) + 1})
    return jsonify({'code': 200, 'msg': 'Message sent'}), 200

@login_required
def get_messages():
    friend_uid = request.args.get('friend_uid', '')
    if not friend_uid:
        return jsonify({'code': 400, 'msg': 'Friend UID required'}), 400
    if friend_uid not in request.current_user.get('friends', []):
        return jsonify({'code': 403, 'msg': 'Not friends'}), 403
    chat_file = os.path.join(CHAT_DIR, request.current_user['id'], f"{friend_uid}.json")
    if not os.path.exists(chat_file):
        return jsonify({'code': 200, 'msg': 'No messages', 'data': []}), 200
    messages = []
    with open(chat_file, 'r') as f:
        for line in f:
            line = line.strip()
            if line:
                messages.append(json.loads(line))
    messages.sort(key=lambda x: x['time'])
    return jsonify({'code': 200, 'msg': 'Messages retrieved', 'data': messages}), 200

@login_required
def delete_account():
    uid = request.current_user['id']
    if completelyDelete:
        file_path = get_user_file_by_id(uid)
        if file_path and os.path.exists(file_path):
            users = []
            with open(file_path, 'r') as f:
                for line in f:
                    line = line.strip()
                    if line:
                        u = json.loads(line)
                        if u['id'] != uid:
                            users.append(u)
            with open(file_path, 'w') as f:
                for u in users:
                    f.write(json.dumps(u) + '\n')
        if os.path.exists(USER_INDEX_FILE):
            with open(USER_INDEX_FILE, 'r') as f:
                index = json.load(f)
            if uid in index:
                del index[uid]
                with open(USER_INDEX_FILE, 'w') as f:
                    json.dump(index, f)
        for ext in ['.jpg', '.jpeg', '.png', '.gif']:
            ava_path = os.path.join(AVATAR_DIR, uid + ext)
            if os.path.exists(ava_path):
                os.remove(ava_path)
        chat_dir = os.path.join(CHAT_DIR, uid)
        if os.path.isdir(chat_dir):
            shutil.rmtree(chat_dir)
    else:
        update_user_data(uid, lambda u: {**u, 'deleted': True})
    return jsonify({'code': 200, 'msg': 'Account deleted'}), 200

def get_station_version():
    return jsonify({'code': 200, 'msg': 'Station version retrieved', 'data': {'version': STATION_VERSION}}), 200

def get_server_type():
    return jsonify({'code': 200, 'msg': 'Server type retrieved', 'data': {'type': SERVER_TYPE}}), 200

@login_required
def get_verify_setting():
    mode = request.current_user.get('friend_verify', 'need_verify')
    return jsonify({'code': 200, 'msg': 'Verify setting retrieved', 'data': {'mode': mode}}), 200

@login_required
def set_verify_setting():
    data = request.get_json()
    mode = data.get('mode', '')
    if mode not in ['allow_all', 'need_verify', 'deny_all']:
        return jsonify({'code': 400, 'msg': 'Invalid mode'}), 400
    update_user_data(request.current_user['id'], lambda u: {**u, 'friend_verify': mode})
    return jsonify({'code': 200, 'msg': 'Verify setting updated'}), 200

@login_required
def get_friend_requests():
    pending = [req for req in request.current_user.get('friend_requests', []) if req.get('status') == 'pending']
    result = []
    for req in pending:
        from_user = get_user_data(req['from_uid'])
        if from_user:
            result.append({
                'id': req['from_uid'],
                'from_uid': req['from_uid'],
                'from_username': from_user['username'],
                'message': '申请添加您为好友',
                'time': req['time']
            })
    return jsonify({'code': 200, 'msg': 'Friend requests retrieved', 'data': result}), 200

@login_required
def accept_friend_request():
    data = request.get_json()
    request_id = data.get('request_id', '')
    if not request_id:
        return jsonify({'code': 400, 'msg': 'Missing request_id'}), 400
    user = request.current_user
    found = False
    def update_requests(u):
        nonlocal found
        requests = u.get('friend_requests', [])
        for req in requests:
            if req['from_uid'] == request_id and req.get('status') == 'pending':
                req['status'] = 'accepted'
                found = True
                break
        return {**u, 'friend_requests': requests}
    update_user_data(user['id'], update_requests)
    if not found:
        return jsonify({'code': 404, 'msg': 'Request not found'}), 404
    update_user_data(user['id'], lambda u: {**u, 'friends': list(set(u.get('friends', []) + [request_id]))})
    update_user_data(request_id, lambda u: {**u, 'friends': list(set(u.get('friends', []) + [user['id']]))})
    return jsonify({'code': 200, 'msg': 'Friend request accepted'}), 200

def get_avatar():
    uid = request.args.get('uid', '')
    if not uid:
        return jsonify({'code': 400, 'msg': 'Missing user ID'}), 400
    for ext in ['.jpg', '.jpeg', '.png', '.gif']:
        ava_path = os.path.join(AVATAR_DIR, uid + ext)
        if os.path.exists(ava_path):
            return send_file(ava_path, mimetype=f'image/{ext[1:]}')
    abort(404)

@login_required
def get_friends():
    friends = []
    for fid in request.current_user.get('friends', []):
        fdata = get_user_data(fid)
        if fdata:
            friends.append({
                'uid': fdata['id'],
                'username': fdata['username'],
                'avatar': fdata.get('avatar'),
                'registered_at': fdata['registered_at'],
                'station_id': fdata.get('station_id')
            })
    return jsonify({'code': 200, 'msg': 'Friends list retrieved', 'data': friends}), 200

def get_user_info():
    if request.method == 'POST':
        data = request.get_json()
        uid = data.get('uid') if data else None
    else:
        uid = request.args.get('uid')
    if not uid:
        return jsonify({'code': 400, 'msg': 'Missing user ID'}), 400
    user = get_user_data(uid)
    if not user:
        return jsonify({'code': 404, 'msg': 'User not found'}), 404
    public_info = {
        'uid': user['id'],
        'username': user['username'],
        'avatar': user.get('avatar'),
        'registered_at': user['registered_at'],
        'station_id': user.get('station_id'),
        'friend_verify': user.get('friend_verify', 'need_verify'),
        'message_count': user.get('message_count', 0)
    }
    return jsonify({'code': 200, 'msg': 'User info retrieved', 'data': public_info}), 200

def get_station_id():
    return jsonify({'code': 200, 'msg': 'Station ID retrieved', 'data': {'station_id': stationID}}), 200

# ---------- 启动服务 ----------
def find_free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(('', 0))
        return s.getsockname()[1]

if __name__ == '__main__':
    init_config()
    port = find_free_port()
    print(f"127.0.0.1:{port}")
    app.run(host='127.0.0.1', port=port, debug=False, threaded=True)