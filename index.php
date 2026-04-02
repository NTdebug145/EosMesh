<?php
/**
 * EosMesh - 去中心化API（独立文件存储版，修复映射覆盖问题）
 * 每个用户独立文件，彻底解决数据覆盖，兼容 PHP 7.3+
 */

ob_clean();
error_reporting(E_ALL);
ini_set('display_errors', 0);
ini_set('log_errors', 1);
ini_set('error_log', __DIR__ . '/data/error.log');

set_exception_handler(function($e) {
    sendResponse(500, 'Server error: ' . $e->getMessage());
});

header("Access-Control-Allow-Origin: *");
header("Access-Control-Allow-Methods: GET, POST, OPTIONS");
header("Access-Control-Allow-Headers: Content-Type, Authorization");
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(200);
    exit();
}
header('Content-Type: application/json; charset=utf-8');

define('STATION_VERSION', 'b26.4.2');
define('SERVER_TYPE', 'php');
define('ROOT_DIR', __DIR__);
define('DATA_DIR', ROOT_DIR . '/data');
define('USER_DIR', DATA_DIR . '/user');
define('CHAT_DIR', DATA_DIR . '/chat/friend');
define('AVATAR_DIR', DATA_DIR . '/avatar');
define('CONFIG_FILE', ROOT_DIR . '/station.ini');
define('MAX_MESSAGE_LEN', 1500);
define('MAX_AVATAR_SIZE', 2 * 1024 * 1024);

foreach ([DATA_DIR, USER_DIR, CHAT_DIR, AVATAR_DIR] as $dir) {
    if (!file_exists($dir)) mkdir($dir, 0755, true);
}

if (!file_exists(CONFIG_FILE)) {
    $stationID = generateRandomString(16);
    file_put_contents(CONFIG_FILE, "[station]\nstationID={$stationID}\nstationNumberDaysInformationStored=3\nstationWhetherCompletelyDeleteUserData=true\n");
}
$fullConfig = parse_ini_file(CONFIG_FILE, true);
$config = $fullConfig['station'];
$stationID = $config['stationID'];
$daysToKeep = (int)$config['stationNumberDaysInformationStored'];
$completelyDelete = filter_var($config['stationWhetherCompletelyDeleteUserData'], FILTER_VALIDATE_BOOLEAN);

// 用户名映射缓存（username → uid）
$usernameMapFile = DATA_DIR . '/username_map.json';
if (!file_exists($usernameMapFile)) file_put_contents($usernameMapFile, json_encode([]));

function generateRandomString($length) {
    $chars = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ';
    $max = strlen($chars) - 1;
    $str = '';
    for ($i = 0; $i < $length; $i++) {
        $str .= $chars[mt_rand(0, $max)];
    }
    return $str;
}

function getUserData($uid) {
    $file = USER_DIR . '/' . $uid . '.json';
    if (!file_exists($file)) return null;
    $fp = fopen($file, 'r');
    if (flock($fp, LOCK_SH)) {
        $data = file_get_contents($file);
        flock($fp, LOCK_UN);
        fclose($fp);
        $user = json_decode($data, true);
        if ($user && empty($user['deleted'])) return $user;
    } else {
        fclose($fp);
    }
    return null;
}

function saveUserData($user) {
    $uid = $user['id'];
    $file = USER_DIR . '/' . $uid . '.json';
    $fp = fopen($file, 'c+');
    if (flock($fp, LOCK_EX)) {
        ftruncate($fp, 0);
        rewind($fp);
        fwrite($fp, json_encode($user));
        fflush($fp);
        flock($fp, LOCK_UN);
        fclose($fp);
        return true;
    }
    fclose($fp);
    return false;
}

function updateUserData($uid, $callback) {
    $user = getUserData($uid);
    if (!$user) return false;
    $user = $callback($user);
    return saveUserData($user);
}

/**
 * 获取用户名映射（线程安全，返回数组）
 */
function getUsernameMap() {
    global $usernameMapFile;
    $fp = fopen($usernameMapFile, 'r');
    if (!$fp) return [];
    if (flock($fp, LOCK_SH)) {
        $content = '';
        while (!feof($fp)) $content .= fread($fp, 8192);
        flock($fp, LOCK_UN);
        fclose($fp);
        $map = json_decode($content, true);
        return is_array($map) ? $map : [];
    }
    fclose($fp);
    return [];
}

/**
 * 更新用户名映射（原子操作）
 */
function updateUsernameMap($username, $uid = null) {
    global $usernameMapFile;
    $fp = fopen($usernameMapFile, 'c+');
    if (!$fp) return false;
    if (!flock($fp, LOCK_EX)) {
        fclose($fp);
        return false;
    }
    // 读取当前完整内容
    $content = '';
    while (!feof($fp)) $content .= fread($fp, 8192);
    $map = json_decode($content, true);
    if (!is_array($map)) $map = [];
    if ($uid === null) {
        unset($map[$username]);
    } else {
        $map[$username] = $uid;
    }
    // 写回
    ftruncate($fp, 0);
    rewind($fp);
    fwrite($fp, json_encode($map));
    fflush($fp);
    flock($fp, LOCK_UN);
    fclose($fp);
    return true;
}

/**
 * 通过用户名查找 UID（使用映射缓存）
 */
function getUidByUsername($username) {
    $map = getUsernameMap();
    return $map[$username] ?? null;
}

function authenticate() {
    $headers = getallheaders();
    $token = $headers['Authorization'] ?? $_GET['token'] ?? null;
    if (!$token) sendResponse(401, 'Missing token');
    $parts = explode(':', $token);
    if (count($parts) != 2) sendResponse(401, 'Invalid token');
    list($uid, $signature) = $parts;
    $user = getUserData($uid);
    if (!$user) sendResponse(401, 'User not found');
    $expected = hash_hmac('sha256', $uid . $user['password'], $GLOBALS['stationID']);
    if (!hash_equals($expected, $signature)) sendResponse(401, 'Invalid token');
    return $user;
}

function sendResponse($code, $message, $data = null) {
    http_response_code($code);
    echo json_encode(['code' => $code, 'msg' => $message, 'data' => $data]);
    exit;
}

$action = $_GET['action'] ?? '';
try {
    switch ($action) {
        case 'register': register(); break;
        case 'login': login(); break;
        case 'upload_avatar': $user = authenticate(); uploadAvatar($user); break;
        case 'add_friend': $user = authenticate(); addFriend($user); break;
        case 'handle_friend_request': $user = authenticate(); handleFriendRequest($user); break;
        case 'send_message': $user = authenticate(); sendMessage($user); break;
        case 'get_messages': $user = authenticate(); getMessages($user); break;
        case 'delete_account': $user = authenticate(); deleteAccount($user); break;
        case 'get_station_version': getStationVersion(); break;
        case 'get_server_type': getServerType(); break;
        case 'get_verify_setting': $user = authenticate(); getVerifySetting($user); break;
        case 'set_verify_setting': $user = authenticate(); setVerifySetting($user); break;
        case 'get_friend_requests': $user = authenticate(); getFriendRequests($user); break;
        case 'accept_friend_request': $user = authenticate(); acceptFriendRequest($user); break;
        case 'get_avatar': getAvatar(); break;
        case 'get_friends': $user = authenticate(); getFriends($user); break;
        case 'get_user_info': getUserInfo(); break;
        case 'get_station_id': sendResponse(200, 'Station ID retrieved', ['station_id' => $GLOBALS['stationID']]); break;
        default: sendResponse(400, 'Invalid action');
    }
} catch (Exception $e) {
    sendResponse(500, 'Server error: ' . $e->getMessage());
}

// ---------------------- 功能实现 ----------------------
function register() {
    global $stationID, $usernameMapFile;
    $input = json_decode(file_get_contents('php://input'), true);
    $username = trim($input['username'] ?? '');
    $password = $input['password'] ?? '';
    if (empty($username) || empty($password)) sendResponse(400, 'Username and password required');
    if (strlen($username) > 12) sendResponse(400, 'Username too long (max 12)');

    // 检查用户名唯一性（使用映射文件加锁）
    $mapFp = fopen($usernameMapFile, 'c+');
    if (!$mapFp) sendResponse(500, 'System error');
    if (!flock($mapFp, LOCK_EX)) sendResponse(500, 'System busy');
    
    // 从当前句柄读取完整映射
    $content = '';
    while (!feof($mapFp)) $content .= fread($mapFp, 8192);
    $map = json_decode($content, true);
    if (!is_array($map)) $map = [];
    
    if (isset($map[$username])) {
        flock($mapFp, LOCK_UN); fclose($mapFp);
        sendResponse(409, 'Username already exists');
    }
    // 二次确认（扫描用户文件，防止缓存不一致）
    $files = glob(USER_DIR . '/*.json');
    foreach ($files as $file) {
        $data = file_get_contents($file);
        $u = json_decode($data, true);
        if ($u && $u['username'] === $username && empty($u['deleted'])) {
            flock($mapFp, LOCK_UN); fclose($mapFp);
            sendResponse(409, 'Username already exists');
        }
    }

    $uid = generateRandomString(32);
    $passwordHash = password_hash($password, PASSWORD_DEFAULT);
    $now = time();
    $user = [
        'id' => $uid, 'username' => $username, 'password' => $passwordHash,
        'friend_verify' => 'need_verify', 'registered_at' => $now, 'station_id' => $stationID,
        'friend_requests' => [], 'friends' => [], 'message_count' => 0, 'avatar' => null, 'deleted' => false,
    ];
    if (!saveUserData($user)) {
        flock($mapFp, LOCK_UN); fclose($mapFp);
        sendResponse(500, 'Failed to save user');
    }
    $map[$username] = $uid;
    // 写回映射文件
    ftruncate($mapFp, 0);
    rewind($mapFp);
    fwrite($mapFp, json_encode($map));
    fflush($mapFp);
    flock($mapFp, LOCK_UN);
    fclose($mapFp);

    $token = $uid . ':' . hash_hmac('sha256', $uid . $passwordHash, $stationID);
    sendResponse(200, 'Registered successfully', ['uid' => $uid, 'token' => $token]);
}

function login() {
    global $stationID;
    $input = json_decode(file_get_contents('php://input'), true);
    $username = trim($input['username'] ?? '');
    $password = $input['password'] ?? '';
    if (empty($username) || empty($password)) sendResponse(400, 'Username and password required');

    $uid = getUidByUsername($username);
    if (!$uid) sendResponse(401, 'Invalid username or password');
    $user = getUserData($uid);
    if (!$user || !password_verify($password, $user['password'])) {
        sendResponse(401, 'Invalid username or password');
    }
    $token = $user['id'] . ':' . hash_hmac('sha256', $user['id'] . $user['password'], $stationID);
    sendResponse(200, 'Login successful', ['uid' => $user['id'], 'token' => $token]);
}

function uploadAvatar($user) {
    if (!isset($_FILES['avatar']) || $_FILES['avatar']['error'] !== UPLOAD_ERR_OK) {
        sendResponse(400, 'Avatar file required');
    }
    $file = $_FILES['avatar'];
    if ($file['size'] > MAX_AVATAR_SIZE) sendResponse(400, 'File too large (max 2MB)');

    $finfo = finfo_open(FILEINFO_MIME_TYPE);
    $mime = finfo_file($finfo, $file['tmp_name']);
    finfo_close($finfo);
    $allowedMimes = ['image/jpeg', 'image/png', 'image/gif'];
    if (!in_array($mime, $allowedMimes)) sendResponse(400, 'Invalid image type');

    $ext = '';
    if ($mime === 'image/jpeg') $ext = 'jpg';
    elseif ($mime === 'image/png') $ext = 'png';
    elseif ($mime === 'image/gif') $ext = 'gif';
    if (!$ext) sendResponse(400, 'Unsupported image format');

    foreach (glob(AVATAR_DIR . '/' . $user['id'] . '.*') as $old) unlink($old);
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

function addFriend($user) {
    $input = json_decode(file_get_contents('php://input'), true);
    $targetName = trim($input['username'] ?? '');
    if (empty($targetName)) sendResponse(400, 'Target username or UID required');

    $targetUser = getUserData($targetName);
    if (!$targetUser) {
        $targetUid = getUidByUsername($targetName);
        if ($targetUid) $targetUser = getUserData($targetUid);
    }
    if (!$targetUser || !empty($targetUser['deleted'])) sendResponse(404, 'User not found');
    if ($targetUser['id'] === $user['id']) sendResponse(400, 'Cannot add yourself');
    if (in_array($targetUser['id'], $user['friends'])) sendResponse(400, 'Already friends');

    $verify = $targetUser['friend_verify'];
    if ($verify === 'deny_all') sendResponse(403, 'User does not accept friend requests');
    elseif ($verify === 'need_verify') {
        $hasPending = false;
        foreach ($targetUser['friend_requests'] as $req) {
            if ($req['from_uid'] === $user['id'] && $req['status'] === 'pending') {
                $hasPending = true;
                break;
            }
        }
        if ($hasPending) sendResponse(409, 'Friend request already sent');
        $request = ['from_uid' => $user['id'], 'time' => time(), 'status' => 'pending'];
        updateUserData($targetUser['id'], function($u) use ($request) {
            $u['friend_requests'][] = $request;
            return $u;
        });
        sendResponse(200, 'Friend request sent');
    } else {
        updateUserData($user['id'], function($u) use ($targetUser) {
            if (!in_array($targetUser['id'], $u['friends'])) $u['friends'][] = $targetUser['id'];
            return $u;
        });
        updateUserData($targetUser['id'], function($u) use ($user) {
            if (!in_array($user['id'], $u['friends'])) $u['friends'][] = $user['id'];
            return $u;
        });
        sendResponse(200, 'Friend added');
    }
}

function handleFriendRequest($user) {
    $input = json_decode(file_get_contents('php://input'), true);
    $fromUid = $input['from_uid'] ?? '';
    $action = $input['action'] ?? '';
    if (empty($fromUid) || !in_array($action, ['accept', 'reject'])) sendResponse(400, 'Invalid parameters');

    $updated = false;
    updateUserData($user['id'], function($u) use ($fromUid, $action, &$updated) {
        foreach ($u['friend_requests'] as &$req) {
            if ($req['from_uid'] === $fromUid && $req['status'] === 'pending') {
                $req['status'] = $action === 'accept' ? 'accepted' : 'rejected';
                $updated = true;
                break;
            }
        }
        return $u;
    });
    if (!$updated) sendResponse(404, 'Request not found');
    if ($action === 'accept') {
        $targetUser = getUserData($fromUid);
        if (!$targetUser || !empty($targetUser['deleted'])) sendResponse(404, 'Target user no longer exists');
        updateUserData($user['id'], function($u) use ($fromUid) {
            if (!in_array($fromUid, $u['friends'])) $u['friends'][] = $fromUid;
            return $u;
        });
        updateUserData($fromUid, function($u) use ($user) {
            if (!in_array($user['id'], $u['friends'])) $u['friends'][] = $user['id'];
            return $u;
        });
        sendResponse(200, 'Friend request accepted');
    } else {
        sendResponse(200, 'Friend request rejected');
    }
}

function sendMessage($user) {
    $input = json_decode(file_get_contents('php://input'), true);
    $toUid = $input['to_uid'] ?? '';
    $content = trim($input['content'] ?? '');
    if (empty($toUid) || empty($content)) sendResponse(400, 'Missing parameters');
    if (mb_strlen($content, 'UTF-8') > MAX_MESSAGE_LEN) sendResponse(400, 'Message too long (max 1500 characters)');

    $targetUser = getUserData($toUid);
    if (!$targetUser || !empty($targetUser['deleted'])) sendResponse(404, 'Target user not found');
    if (!in_array($toUid, $user['friends']) || !in_array($user['id'], $targetUser['friends'])) {
        sendResponse(403, 'Not friends');
    }

    $message = ['from' => $user['id'], 'to' => $toUid, 'content' => $content, 'time' => time()];
    $senderDir = CHAT_DIR . '/' . $user['id'];
    if (!file_exists($senderDir)) mkdir($senderDir, 0755, true);
    $receiverDir = CHAT_DIR . '/' . $toUid;
    if (!file_exists($receiverDir)) mkdir($receiverDir, 0755, true);
    appendMessageToFile($senderDir . '/' . $toUid . '.json', $message);
    appendMessageToFile($receiverDir . '/' . $user['id'] . '.json', $message);

    updateUserData($user['id'], function($u) {
        $u['message_count']++;
        return $u;
    });
    sendResponse(200, 'Message sent');
}

function appendMessageToFile($filePath, $message) {
    global $daysToKeep;
    $fp = fopen($filePath, 'a+');
    if (flock($fp, LOCK_EX)) {
        rewind($fp);
        $messages = [];
        while (($line = fgets($fp)) !== false) {
            $line = trim($line);
            if ($line) {
                $msg = json_decode($line, true);
                if (is_array($msg) && isset($msg['time'])) {
                    $messages[] = $msg;
                }
            }
        }
        $messages[] = $message;
        $cutoff = time() - ($daysToKeep * 86400);
        
        // 已修复：兼容所有PHP版本，无语法错误
        $messages = array_filter($messages, function($msg) use ($cutoff) {
            return $msg['time'] >= $cutoff;
        });
        
        ftruncate($fp, 0);
        rewind($fp);
        foreach ($messages as $msg) {
            fwrite($fp, json_encode($msg) . "\n");
        }
        fflush($fp);
        flock($fp, LOCK_UN);
    }
    fclose($fp);
}

function getMessages($user) {
    $friendUid = $_GET['friend_uid'] ?? '';
    if (empty($friendUid)) sendResponse(400, 'Friend UID required');
    if (!in_array($friendUid, $user['friends'])) sendResponse(403, 'Not friends');

    $chatFile = CHAT_DIR . '/' . $user['id'] . '/' . $friendUid . '.json';
    $messages = [];
    if (file_exists($chatFile)) {
        $lines = file($chatFile, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
        foreach ($lines as $line) {
            $msg = json_decode($line, true);
            if (is_array($msg) && isset($msg['time'])) {
                $messages[] = $msg;
            }
        }
        // 按时间升序排序（旧→新）
        usort($messages, function($a, $b) { return $a['time'] - $b['time']; });
    }

    // 增量模式：只返回 since 时间之后的消息
    $since = isset($_GET['since']) ? (int)$_GET['since'] : 0;
    if ($since > 0) {
        $result = array_values(array_filter($messages, function($msg) use ($since) {
            return $msg['time'] > $since;
        }));
        sendResponse(200, 'Messages retrieved', $result);
        return;
    }

    // 分页模式
    $page = isset($_GET['page']) ? max(1, (int)$_GET['page']) : 1;
    $limit = isset($_GET['limit']) ? min(100, max(1, (int)$_GET['limit'])) : 20;
    $total = count($messages);
    $offset = ($page - 1) * $limit;
    $pagedMessages = array_slice($messages, $offset, $limit);

    sendResponse(200, 'Messages retrieved', [
        'messages' => $pagedMessages,
        'total' => $total,
        'page' => $page,
        'limit' => $limit,
        'total_pages' => ceil($total / $limit)
    ]);
}

function deleteAccount($user) {
    global $completelyDelete;
    if ($completelyDelete) {
        $userFile = USER_DIR . '/' . $user['id'] . '.json';
        if (file_exists($userFile)) unlink($userFile);
        updateUsernameMap($user['username'], null);
        foreach (glob(AVATAR_DIR . '/' . $user['id'] . '.*') as $f) unlink($f);
        $chatDir = CHAT_DIR . '/' . $user['id'];
        if (is_dir($chatDir)) {
            foreach (glob($chatDir . '/*.json') as $f) unlink($f);
            rmdir($chatDir);
        }
        foreach ($user['friends'] as $friendUid) {
            updateUserData($friendUid, function($u) use ($user) {
                $u['friends'] = array_values(array_filter($u['friends'], function($id) use ($user) { return $id !== $user['id']; }));
                return $u;
            });
        }
    } else {
        updateUserData($user['id'], function($u) { $u['deleted'] = true; return $u; });
        updateUsernameMap($user['username'], null);
        foreach ($user['friends'] as $friendUid) {
            updateUserData($friendUid, function($u) use ($user) {
                $u['friends'] = array_values(array_filter($u['friends'], function($id) use ($user) { return $id !== $user['id']; }));
                return $u;
            });
        }
    }
    sendResponse(200, 'Account deleted');
}

function getStationVersion() { sendResponse(200, 'OK', ['version' => STATION_VERSION]); }
function getServerType() { sendResponse(200, 'OK', ['type' => SERVER_TYPE]); }
function getVerifySetting($user) { sendResponse(200, 'OK', ['mode' => $user['friend_verify']]); }
function setVerifySetting($user) {
    $input = json_decode(file_get_contents('php://input'), true);
    $mode = $input['mode'] ?? '';
    if (!in_array($mode, ['allow_all','need_verify','deny_all'])) sendResponse(400, 'Invalid mode');
    updateUserData($user['id'], function($u) use ($mode) { $u['friend_verify'] = $mode; return $u; });
    sendResponse(200, 'Verify setting updated');
}
function getFriendRequests($user) {
    $pending = array_filter($user['friend_requests'], function($r) { return $r['status'] === 'pending'; });
    $result = [];
    foreach ($pending as $req) {
        $from = getUserData($req['from_uid']);
        if ($from && empty($from['deleted'])) {
            $result[] = [
                'id' => $req['from_uid'],
                'from_uid' => $req['from_uid'],
                'from_username' => $from['username'],
                'message' => '申请添加您为好友',
                'time' => $req['time']
            ];
        }
    }
    sendResponse(200, 'OK', $result);
}
function acceptFriendRequest($user) {
    $input = json_decode(file_get_contents('php://input'), true);
    $requestId = $input['request_id'] ?? '';
    if (empty($requestId)) sendResponse(400, 'Missing request_id');

    $found = false;
    updateUserData($user['id'], function($u) use ($requestId, &$found) {
        foreach ($u['friend_requests'] as &$req) {
            if ($req['from_uid'] === $requestId && $req['status'] === 'pending') {
                $req['status'] = 'accepted';
                $found = true;
                break;
            }
        }
        return $u;
    });
    if (!$found) sendResponse(404, 'Request not found');

    $target = getUserData($requestId);
    if (!$target || !empty($target['deleted'])) sendResponse(404, 'Target user no longer exists');

    updateUserData($user['id'], function($u) use ($requestId) {
        if (!in_array($requestId, $u['friends'])) $u['friends'][] = $requestId;
        return $u;
    });
    updateUserData($requestId, function($u) use ($user) {
        if (!in_array($user['id'], $u['friends'])) $u['friends'][] = $user['id'];
        return $u;
    });

    updateUserData($user['id'], function($u) use ($requestId) {
        $u['friend_requests'] = array_values(array_filter($u['friend_requests'], function($r) use ($requestId) {
            return !($r['from_uid'] === $requestId && $r['status'] === 'accepted');
        }));
        return $u;
    });
    sendResponse(200, 'Friend request accepted');
}
function getAvatar() {
    $uid = $_GET['uid'] ?? '';
    if (empty($uid)) sendResponse(400, 'Missing user ID');
    header("Access-Control-Allow-Origin: *");
    $pattern = AVATAR_DIR . '/' . $uid . '.*';
    $files = glob($pattern);
    if (empty($files)) { http_response_code(404); exit; }
    $avatarFile = $files[0];
    $mime = mime_content_type($avatarFile);
    header('Content-Type: ' . $mime);
    readfile($avatarFile);
    exit;
}
function getFriends($user) {
    $friends = [];
    foreach ($user['friends'] as $friendUid) {
        $f = getUserData($friendUid);
        if ($f && empty($f['deleted'])) {
            $friends[] = [
                'uid' => $f['id'], 'username' => $f['username'], 'avatar' => $f['avatar'],
                'registered_at' => $f['registered_at'], 'station_id' => $f['station_id']
            ];
        }
    }
    sendResponse(200, 'Friends list retrieved', $friends);
}
function getUserInfo() {
    $input = json_decode(file_get_contents('php://input'), true);
    $uidOrName = $input['uid'] ?? $_GET['uid'] ?? null;
    if (!$uidOrName) sendResponse(400, 'Missing user ID or username');

    $user = getUserData($uidOrName);
    if (!$user) {
        $uid = getUidByUsername($uidOrName);
        if ($uid) $user = getUserData($uid);
    }
    if (!$user || !empty($user['deleted'])) sendResponse(404, 'User not found');
    sendResponse(200, 'User info retrieved', [
        'uid' => $user['id'], 'username' => $user['username'], 'avatar' => $user['avatar'],
        'registered_at' => $user['registered_at'], 'station_id' => $user['station_id'],
        'friend_verify' => $user['friend_verify'], 'message_count' => $user['message_count'] ?? 0
    ]);
}
?>