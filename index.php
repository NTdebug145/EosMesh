<?php
/**
 * EosMesh - 去中心化API脚本
 * 纯API接口，无HTML输出
 */

ob_clean();

// 允许跨域
header("Access-Control-Allow-Origin: *");
header("Access-Control-Allow-Methods: GET, POST, OPTIONS");
header("Access-Control-Allow-Headers: Content-Type, Authorization");

// 处理预检请求 (OPTIONS)
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    // 明确返回 CORS 头部
    header("Access-Control-Allow-Origin: *");
    header("Access-Control-Allow-Methods: GET, POST, OPTIONS");
    header("Access-Control-Allow-Headers: Content-Type, Authorization");
    http_response_code(200);
    exit();
}

// 错误报告（生产环境应关闭）
error_reporting(E_ALL);
ini_set('display_errors', 1);

// 设置响应头为JSON
header('Content-Type: application/json; charset=utf-8');

// 硬编码版本和服务器类型
define('STATION_VERSION', 'b26.4.2');
define('SERVER_TYPE', 'php');

// 定义目录常量
define('ROOT_DIR', __DIR__);
define('DATA_DIR', ROOT_DIR . '/data');
define('USER_DIR', DATA_DIR . '/user');
define('CHAT_DIR', DATA_DIR . '/chat/friend');
define('AVATAR_DIR', DATA_DIR . '/avatar');
define('CONFIG_FILE', ROOT_DIR . '/station.ini');

// 初始化目录结构
if (!file_exists(DATA_DIR)) mkdir(DATA_DIR, 0755, true);
if (!file_exists(USER_DIR)) mkdir(USER_DIR, 0755, true);
if (!file_exists(CHAT_DIR)) mkdir(CHAT_DIR, 0755, true);
if (!file_exists(AVATAR_DIR)) mkdir(AVATAR_DIR, 0755, true);

// 首次运行生成配置文件（只包含 [station] 节）
if (!file_exists(CONFIG_FILE)) {
    $stationID = generateRandomString(16);
    $configContent = "[station]\nstationID={$stationID}\nstationNumberDaysInformationStored=3\nstationWhetherCompletelyDeleteUserData=true\n";
    file_put_contents(CONFIG_FILE, $configContent);
}

// 读取完整配置
$fullConfig = parse_ini_file(CONFIG_FILE, true);
$config = $fullConfig['station'];
$stationID = $config['stationID'];
$daysToKeep = (int)$config['stationNumberDaysInformationStored'];
$completelyDelete = filter_var($config['stationWhetherCompletelyDeleteUserData'], FILTER_VALIDATE_BOOLEAN);

// 用户索引文件（映射用户ID -> 文件编号）
$userIndexFile = DATA_DIR . '/user_index.json';
if (!file_exists($userIndexFile)) {
    file_put_contents($userIndexFile, json_encode([]));
}

// 辅助函数
function generateRandomString($length) {
    $characters = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ';
    $charactersLength = strlen($characters);
    $randomString = '';
    for ($i = 0; $i < $length; $i++) {
        $randomString .= $characters[rand(0, $charactersLength - 1)];
    }
    return $randomString;
}

function getUserFileByID($uid) {
    $index = json_decode(file_get_contents($GLOBALS['userIndexFile']), true);
    if (!isset($index[$uid])) {
        return null;
    }
    $fileNum = $index[$uid];
    return USER_DIR . "/user_{$fileNum}.json";
}

function getUserData($uid) {
    $file = getUserFileByID($uid);
    if (!$file || !file_exists($file)) {
        return null;
    }
    $lines = file($file, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
    foreach ($lines as $line) {
        $user = json_decode($line, true);
        if ($user && $user['id'] === $uid && (!isset($user['deleted']) || $user['deleted'] !== true)) {
            return $user;
        }
    }
    return null;
}

function saveUserData($user) {
    $uid = $user['id'];
    $index = json_decode(file_get_contents($GLOBALS['userIndexFile']), true);
    if (!isset($index[$uid])) {
        // 分配文件
        $files = glob(USER_DIR . '/user_*.json');
        $maxNum = 0;
        foreach ($files as $file) {
            if (preg_match('/user_(\d+)\.json/', $file, $matches)) {
                $maxNum = max($maxNum, (int)$matches[1]);
            }
        }
        $targetFileNum = null;
        for ($i = 1; $i <= $maxNum + 1; $i++) {
            $filePath = USER_DIR . "/user_{$i}.json";
            if (!file_exists($filePath)) {
                $targetFileNum = $i;
                break;
            }
            $lines = file($filePath, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
            if (count($lines) < 50) {
                $targetFileNum = $i;
                break;
            }
        }
        if ($targetFileNum === null) $targetFileNum = $maxNum + 1;
        $index[$uid] = $targetFileNum;
        file_put_contents($GLOBALS['userIndexFile'], json_encode($index));
    }
    $fileNum = $index[$uid];
    $filePath = USER_DIR . "/user_{$fileNum}.json";
    // 读取现有数据，替换或追加
    $lines = file_exists($filePath) ? file($filePath, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) : [];
    $found = false;
    $newLines = [];
    foreach ($lines as $line) {
        $u = json_decode($line, true);
        if ($u && $u['id'] === $uid) {
            $newLines[] = json_encode($user);
            $found = true;
        } else {
            $newLines[] = $line;
        }
    }
    if (!$found) {
        $newLines[] = json_encode($user);
    }
    file_put_contents($filePath, implode("\n", $newLines) . "\n");
}

function updateUserData($uid, $callback) {
    $user = getUserData($uid);
    if (!$user) return false;
    $user = $callback($user);
    saveUserData($user);
    return true;
}

function authenticate() {
    $headers = getallheaders();
    $token = $headers['Authorization'] ?? $_GET['token'] ?? null;
    if (!$token) {
        sendResponse(401, 'Missing token');
    }
    // token格式: uid:signature
    $parts = explode(':', $token);
    if (count($parts) != 2) {
        sendResponse(401, 'Invalid token');
    }
    list($uid, $signature) = $parts;
    $user = getUserData($uid);
    if (!$user) {
        sendResponse(401, 'User not found');
    }
    // 简单签名：HMAC-SHA256(uid + password_hash, stationID)
    $expected = hash_hmac('sha256', $uid . $user['password'], $GLOBALS['stationID']);
    if (!hash_equals($expected, $signature)) {
        sendResponse(401, 'Invalid token');
    }
    return $user;
}

function sendResponse($code, $message, $data = null) {
    header("Access-Control-Allow-Origin: *");
    header("Access-Control-Allow-Methods: GET, POST, OPTIONS");
    header("Access-Control-Allow-Headers: Content-Type, Authorization");
    http_response_code($code);
    echo json_encode(['code' => $code, 'msg' => $message, 'data' => $data]);
    exit;
}

// 路由
$action = $_GET['action'] ?? '';

try {
    switch ($action) {
        case 'register':
            register();
            break;
        case 'login':
            login();
            break;
        case 'upload_avatar':
            $user = authenticate();
            uploadAvatar($user);
            break;
        case 'add_friend':
            $user = authenticate();
            addFriend($user);
            break;
        case 'handle_friend_request':
            $user = authenticate();
            handleFriendRequest($user);
            break;
        case 'send_message':
            $user = authenticate();
            sendMessage($user);
            break;
        case 'get_messages':
            $user = authenticate();
            getMessages($user);
            break;
        case 'delete_account':
            $user = authenticate();
            deleteAccount($user);
            break;

case 'get_station_version':
    getStationVersion();
    break;

case 'get_server_type':
    getServerType();
    break;

case 'get_verify_setting':
    $user = authenticate();
    getVerifySetting($user);
    break;

case 'set_verify_setting':
    $user = authenticate();
    setVerifySetting($user);
    break;

case 'get_friend_requests':
    $user = authenticate();
    getFriendRequests($user);
    break;

case 'accept_friend_request':
    $user = authenticate();
    acceptFriendRequest($user);
    break;

case 'get_avatar':
    $uid = $_GET['uid'] ?? '';
    if (empty($uid)) {
        sendResponse(400, 'Missing user ID');
    }
    // 添加 CORS 头部，确保头像可以被跨域读取
    header("Access-Control-Allow-Origin: *");
    header("Access-Control-Allow-Methods: GET, POST, OPTIONS");
    header("Access-Control-Allow-Headers: Content-Type, Authorization");
    
    // 查找用户头像文件
    $avatarPattern = AVATAR_DIR . '/' . $uid . '.*';
    $files = glob($avatarPattern);
    if (empty($files)) {
        // 无头像则返回 404
        http_response_code(404);
        exit;
    }
    $avatarFile = $files[0];
    $mime = mime_content_type($avatarFile);
    header('Content-Type: ' . $mime);
    readfile($avatarFile);
    exit;
    break;

case 'get_friends':
    $user = authenticate();
    $friends = [];
    foreach ($user['friends'] as $friendUid) {
        $friendData = getUserData($friendUid);
        if ($friendData) {
            $friends[] = [
                'uid' => $friendData['id'],
                'username' => $friendData['username'],
                'avatar' => $friendData['avatar'],
                'registered_at' => $friendData['registered_at'],
                'station_id' => $friendData['station_id'],
            ];
        }
    }
    sendResponse(200, 'Friends list retrieved', $friends);
    break;

case 'get_user_info':
    $input = json_decode(file_get_contents('php://input'), true);
    $uid = $input['uid'] ?? $_GET['uid'] ?? null;
    if (!$uid) {
        sendResponse(400, 'Missing user ID');
    }
    $user = null;
    // 根据uid查找
    $files = glob(USER_DIR . '/user_*.json');
    foreach ($files as $file) {
        $lines = file($file, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
        foreach ($lines as $line) {
            $u = json_decode($line, true);
            if ($u && $u['id'] === $uid && (!isset($u['deleted']) || $u['deleted'] !== true)) {
                $user = $u;
                break 2;
            }
        }
    }
    if (!$user) {
        sendResponse(404, 'User not found');
    }
    $publicInfo = [
        'uid' => $user['id'],
        'username' => $user['username'],
        'avatar' => $user['avatar'],
        'registered_at' => $user['registered_at'],
        'station_id' => $user['station_id'],
        'friend_verify' => $user['friend_verify'],
    ];
    sendResponse(200, 'User info retrieved', $publicInfo);
    break;

case 'get_station_id':
    sendResponse(200, 'Station ID retrieved', ['station_id' => $stationID]);
    break;

        default:
            sendResponse(400, 'Invalid action');
    }
} catch (Exception $e) {
    sendResponse(500, 'Server error: ' . $e->getMessage());
}

// 注册
function register() {
    global $stationID;
    $input = json_decode(file_get_contents('php://input'), true);
    $username = trim($input['username'] ?? '');
    $password = $input['password'] ?? '';

    if (empty($username) || empty($password)) {
        sendResponse(400, 'Username and password required');
    }
    if (strlen($username) > 12) {
        sendResponse(400, 'Username too long (max 12 characters)');
    }

    // 检查用户名唯一性
    $files = glob(USER_DIR . '/user_*.json');
    foreach ($files as $file) {
        $lines = file($file, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
        foreach ($lines as $line) {
            $user = json_decode($line, true);
            if ($user && $user['username'] === $username && (!isset($user['deleted']) || $user['deleted'] !== true)) {
                sendResponse(409, 'Username already exists');
            }
        }
    }

    $uid = generateRandomString(32);
    $passwordHash = password_hash($password, PASSWORD_DEFAULT);
    $now = time();
    $user = [
        'id' => $uid,
        'username' => $username,
        'password' => $passwordHash,
        'friend_verify' => 'need_verify', // allow_all, need_verify, deny_all
        'registered_at' => $now,
        'station_id' => $stationID,
        'friend_requests' => [], // 请求列表: [{'from_uid': 'xxx', 'time': timestamp, 'status': 'pending'}, ...]
        'friends' => [],
        'message_count' => 0,
        'avatar' => null,
        'deleted' => false,
    ];

    saveUserData($user);

    // 生成token
    $token = $uid . ':' . hash_hmac('sha256', $uid . $passwordHash, $stationID);
    sendResponse(200, 'Registered successfully', ['uid' => $uid, 'token' => $token]);
}

// 登录
function login() {
    global $stationID;
    $input = json_decode(file_get_contents('php://input'), true);
    $username = trim($input['username'] ?? '');
    $password = $input['password'] ?? '';

    if (empty($username) || empty($password)) {
        sendResponse(400, 'Username and password required');
    }

    $files = glob(USER_DIR . '/user_*.json');
    $foundUser = null;
    foreach ($files as $file) {
        $lines = file($file, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
        foreach ($lines as $line) {
            $user = json_decode($line, true);
            if ($user && $user['username'] === $username && (!isset($user['deleted']) || $user['deleted'] !== true)) {
                $foundUser = $user;
                break 2;
            }
        }
    }

    if (!$foundUser || !password_verify($password, $foundUser['password'])) {
        sendResponse(401, 'Invalid username or password');
    }

    $token = $foundUser['id'] . ':' . hash_hmac('sha256', $foundUser['id'] . $foundUser['password'], $stationID);
    sendResponse(200, 'Login successful', ['uid' => $foundUser['id'], 'token' => $token]);
}

// 上传头像
function uploadAvatar($user) {
    if (!isset($_FILES['avatar']) || $_FILES['avatar']['error'] !== UPLOAD_ERR_OK) {
        sendResponse(400, 'Avatar file required');
    }
    $file = $_FILES['avatar'];
    $ext = pathinfo($file['name'], PATHINFO_EXTENSION);
    if (!in_array(strtolower($ext), ['jpg', 'jpeg', 'png', 'gif'])) {
        sendResponse(400, 'Invalid image format');
    }
    $maxSize = 2 * 1024 * 1024; // 2MB
    if ($file['size'] > $maxSize) {
        sendResponse(400, 'File too large (max 2MB)');
    }
    $avatarPath = AVATAR_DIR . '/' . $user['id'] . '.' . $ext;
    if (move_uploaded_file($file['tmp_name'], $avatarPath)) {
        updateUserData($user['id'], function($u) use ($avatarPath) {
            $u['avatar'] = $avatarPath;
            return $u;
        });
        sendResponse(200, 'Avatar uploaded');
    } else {
        sendResponse(500, 'Failed to save avatar');
    }
}

// 添加好友
function addFriend($user) {
    $input = json_decode(file_get_contents('php://input'), true);
    $targetUsername = trim($input['username'] ?? '');
    if (empty($targetUsername)) {
        sendResponse(400, 'Target username required');
    }

// 查找目标用户：先按用户名查找，如果找不到再按 uid 查找
$targetUser = null;
$files = glob(USER_DIR . '/user_*.json');
foreach ($files as $file) {
    $lines = file($file, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
    foreach ($lines as $line) {
        $u = json_decode($line, true);
        if ($u && (!isset($u['deleted']) || $u['deleted'] !== true)) {
            if ($u['username'] === $targetUsername || $u['id'] === $targetUsername) {
                $targetUser = $u;
                break 2;
            }
        }
    }
}
    if (!$targetUser) {
        sendResponse(404, 'User not found');
    }
    if ($targetUser['id'] === $user['id']) {
        sendResponse(400, 'Cannot add yourself');
    }

    // 检查是否已经是好友
    if (in_array($targetUser['id'], $user['friends'])) {
        sendResponse(400, 'Already friends');
    }

    // 检查验证方式
$verify = $targetUser['friend_verify'];
if ($verify === 'deny_all') {
    sendResponse(403, 'User does not accept friend requests');
} elseif ($verify === 'need_verify') {
    // 发送好友申请
    $request = [
        'from_uid' => $user['id'],
        'time' => time(),
        'status' => 'pending'
    ];
    updateUserData($targetUser['id'], function($u) use ($request) {
        foreach ($u['friend_requests'] as $r) {
            if ($r['from_uid'] === $request['from_uid'] && $r['status'] === 'pending') {
                return $u;
            }
        }
        $u['friend_requests'][] = $request;
        return $u;
    });
    sendResponse(200, 'Friend request sent');
} else { // allow_all
    // 直接添加好友
    updateUserData($user['id'], function($u) use ($targetUser) {
        $u['friends'][] = $targetUser['id'];
        return $u;
    });
    updateUserData($targetUser['id'], function($u) use ($user) {
        $u['friends'][] = $user['id'];
        return $u;
    });
    sendResponse(200, 'Friend added');
}
}

// 处理好友申请（接受/拒绝）
function handleFriendRequest($user) {
    $input = json_decode(file_get_contents('php://input'), true);
    $fromUid = $input['from_uid'] ?? '';
    $action = $input['action'] ?? ''; // accept, reject
    if (empty($fromUid) || !in_array($action, ['accept', 'reject'])) {
        sendResponse(400, 'Invalid parameters');
    }

    $updated = false;
    updateUserData($user['id'], function($u) use ($fromUid, $action, &$updated) {
        foreach ($u['friend_requests'] as &$req) {
            if ($req['from_uid'] === $fromUid && $req['status'] === 'pending') {
                if ($action === 'accept') {
                    $req['status'] = 'accepted';
                    $updated = true;
                } else {
                    $req['status'] = 'rejected';
                }
                break;
            }
        }
        return $u;
    });

    if ($updated && $action === 'accept') {
        // 双方加为好友
        updateUserData($user['id'], function($u) use ($fromUid) {
            if (!in_array($fromUid, $u['friends'])) {
                $u['friends'][] = $fromUid;
            }
            return $u;
        });
        updateUserData($fromUid, function($u) use ($user) {
            if (!in_array($user['id'], $u['friends'])) {
                $u['friends'][] = $user['id'];
            }
            return $u;
        });
        sendResponse(200, 'Friend request accepted');
    } elseif ($updated) {
        sendResponse(200, 'Friend request rejected');
    } else {
        sendResponse(404, 'Request not found');
    }
}

// 发送消息
// 发送消息
function sendMessage($user) {
    $input = json_decode(file_get_contents('php://input'), true);
    $toUid = $input['to_uid'] ?? '';
    $content = trim($input['content'] ?? '');
    if (empty($toUid) || empty($content)) {
        sendResponse(400, 'Missing parameters');
    }

    // 检查是否好友
    $targetUser = getUserData($toUid);
    if (!$targetUser) {
        sendResponse(404, 'Target user not found');
    }
    if (!in_array($toUid, $user['friends']) || !in_array($user['id'], $targetUser['friends'])) {
        sendResponse(403, 'Not friends');
    }

    $message = [
        'from' => $user['id'],
        'to' => $toUid,
        'content' => $content,
        'time' => time()
    ];

    // 保存到发送者目录
    $senderDir = CHAT_DIR . '/' . $user['id'];
    if (!file_exists($senderDir)) mkdir($senderDir, 0755, true);
    $senderFile = $senderDir . '/' . $toUid . '.json';
    appendMessageToFile($senderFile, $message);

    // 保存到接收者目录
    $receiverDir = CHAT_DIR . '/' . $toUid;
    if (!file_exists($receiverDir)) mkdir($receiverDir, 0755, true);
    $receiverFile = $receiverDir . '/' . $user['id'] . '.json';
    appendMessageToFile($receiverFile, $message);

    // 更新用户消息计数
    updateUserData($user['id'], function($u) {
        $u['message_count']++;
        return $u;
    });

    sendResponse(200, 'Message sent');
}

// 辅助函数：将消息追加到聊天文件，并清理过期消息
function appendMessageToFile($filePath, $message) {
    $messages = [];
    if (file_exists($filePath)) {
        $lines = file($filePath, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
        foreach ($lines as $line) {
            $messages[] = json_decode($line, true);
        }
    }
    $messages[] = $message;

    // 清理过期消息
    $cutoff = time() - ($GLOBALS['daysToKeep'] * 86400);
    $messages = array_filter($messages, function($msg) use ($cutoff) {
        return $msg['time'] >= $cutoff;
    });

    // 写回文件
    $fp = fopen($filePath, 'w');
    if (flock($fp, LOCK_EX)) {
        foreach ($messages as $msg) {
            fwrite($fp, json_encode($msg) . "\n");
        }
        flock($fp, LOCK_UN);
    }
    fclose($fp);
}

// 获取消息（与特定好友的聊天记录）
function getMessages($user) {
    $friendUid = $_GET['friend_uid'] ?? '';
    if (empty($friendUid)) {
        sendResponse(400, 'Friend UID required');
    }

    // 检查是否好友
    if (!in_array($friendUid, $user['friends'])) {
        sendResponse(403, 'Not friends');
    }

    $chatFile = CHAT_DIR . '/' . $user['id'] . '/' . $friendUid . '.json';
    if (!file_exists($chatFile)) {
        sendResponse(200, 'No messages', []);
    }

    $lines = file($chatFile, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
    $messages = array_map(function($line) {
        return json_decode($line, true);
    }, $lines);

    // 可选：按时间排序
    usort($messages, function($a, $b) {
        return $a['time'] - $b['time'];
    });

    sendResponse(200, 'Messages retrieved', $messages);
}

// 注销账号
function deleteAccount($user) {
    global $completelyDelete;
    if ($completelyDelete) {
        // 完全删除：从用户文件中移除
        $file = getUserFileByID($user['id']);
        if ($file) {
            $lines = file($file, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
            $newLines = [];
            foreach ($lines as $line) {
                $u = json_decode($line, true);
                if ($u && $u['id'] !== $user['id']) {
                    $newLines[] = $line;
                }
            }
            file_put_contents($file, implode("\n", $newLines) . "\n");
        }
        // 从索引中移除
        $index = json_decode(file_get_contents($GLOBALS['userIndexFile']), true);
        unset($index[$user['id']]);
        file_put_contents($GLOBALS['userIndexFile'], json_encode($index));
        // 可选：删除头像和聊天记录
        @unlink(AVATAR_DIR . '/' . $user['id'] . '.*');
        $chatDir = CHAT_DIR . '/' . $user['id'];
        if (is_dir($chatDir)) {
            array_map('unlink', glob($chatDir . '/*.json'));
            rmdir($chatDir);
        }
    } else {
        // 软删除：标记为deleted
        updateUserData($user['id'], function($u) {
            $u['deleted'] = true;
            return $u;
        });
    }
    sendResponse(200, 'Account deleted');
}

// 获取当前用户的验证方式
function getVerifySetting($user) {
    sendResponse(200, 'Verify setting retrieved', ['mode' => $user['friend_verify']]);
}

// 设置验证方式
function setVerifySetting($user) {
    $input = json_decode(file_get_contents('php://input'), true);
    $mode = $input['mode'] ?? '';
    if (!in_array($mode, ['allow_all', 'need_verify', 'deny_all'])) {
        sendResponse(400, 'Invalid mode');
    }
    updateUserData($user['id'], function($u) use ($mode) {
        $u['friend_verify'] = $mode;
        return $u;
    });
    sendResponse(200, 'Verify setting updated');
}

// 获取待处理的好友申请列表
function getFriendRequests($user) {
    $pendingRequests = array_filter($user['friend_requests'], function($req) {
        return $req['status'] === 'pending';
    });
    $result = [];
    foreach ($pendingRequests as $req) {
        $fromUser = getUserData($req['from_uid']);
        if ($fromUser) {
            $result[] = [
                'id' => $req['from_uid'],          // 用 from_uid 作为请求唯一标识
                'from_uid' => $req['from_uid'],
                'from_username' => $fromUser['username'],
                'message' => '申请添加您为好友',
                'time' => $req['time']
            ];
        }
    }
    sendResponse(200, 'Friend requests retrieved', $result);
}

// 接受好友申请（前端会传入 request_id，即 from_uid）
function acceptFriendRequest($user) {
    $input = json_decode(file_get_contents('php://input'), true);
    $requestId = $input['request_id'] ?? '';
    if (empty($requestId)) {
        sendResponse(400, 'Missing request_id');
    }

    // 查找申请并更新状态
    $requestFound = false;
    updateUserData($user['id'], function($u) use ($requestId, &$requestFound) {
        foreach ($u['friend_requests'] as &$req) {
            if ($req['from_uid'] === $requestId && $req['status'] === 'pending') {
                $req['status'] = 'accepted';
                $requestFound = true;
                break;
            }
        }
        return $u;
    });

    if (!$requestFound) {
        sendResponse(404, 'Request not found');
    }

    // 双方加为好友
    updateUserData($user['id'], function($u) use ($requestId) {
        if (!in_array($requestId, $u['friends'])) {
            $u['friends'][] = $requestId;
        }
        return $u;
    });
    updateUserData($requestId, function($u) use ($user) {
        if (!in_array($user['id'], $u['friends'])) {
            $u['friends'][] = $user['id'];
        }
        return $u;
    });

    sendResponse(200, 'Friend request accepted');
}

function getStationVersion() {
    sendResponse(200, 'Station version retrieved', ['version' => STATION_VERSION]);
}

function getServerType() {
    sendResponse(200, 'Server type retrieved', ['type' => SERVER_TYPE]);
}
?>