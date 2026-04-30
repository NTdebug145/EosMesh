<?php
/**
 * EosMesh - 去中心化API（无文件锁版本）
 * 加入 token 刷新机制：有效期5分钟，每4分钟可刷新一次
 */

if (ob_get_level() == 0) ob_start();

error_reporting(E_ALL);
ini_set('display_errors', 0);
ini_set('log_errors', 1);
ini_set('error_log', __DIR__ . '/data/error.log');

set_error_handler(function($severity, $message, $file, $line) {
    if (!(error_reporting() & $severity)) return false;
    throw new ErrorException($message, 0, $severity, $file, $line);
});

set_exception_handler(function($e) {
    http_response_code(500);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode([
        'code' => 500,
        'msg' => 'Server error: ' . $e->getMessage(),
        'data' => null
    ]);
    exit;
});

header("Access-Control-Allow-Origin: *");
header("Access-Control-Allow-Methods: GET, POST, OPTIONS");
header("Access-Control-Allow-Headers: Content-Type, Authorization");
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(200);
    exit();
}
header('Content-Type: application/json; charset=utf-8');


define('STATION_VERSION', 'b26.4.17');
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
    $data = file_get_contents($file);
    $user = json_decode($data, true);
    if ($user && empty($user['deleted'])) return $user;
    return null;
}

function saveUserData($user) {
    $uid = $user['id'];
    $file = USER_DIR . '/' . $uid . '.json';
    return file_put_contents($file, json_encode($user)) !== false;
}

function updateUserData($uid, $callback) {
    $user = getUserData($uid);
    if (!$user) return false;
    $user = $callback($user);
    return saveUserData($user);
}

function getUsernameMap() {
    global $usernameMapFile;
    if (!file_exists($usernameMapFile)) return [];
    $content = file_get_contents($usernameMapFile);
    $map = json_decode($content, true);
    return is_array($map) ? $map : [];
}

function updateUsernameMap($username, $uid = null) {
    global $usernameMapFile;
    $map = getUsernameMap();
    if ($uid === null) {
        unset($map[$username]);
    } else {
        $map[$username] = $uid;
    }
    return file_put_contents($usernameMapFile, json_encode($map)) !== false;
}

function getUidByUsername($username) {
    $map = getUsernameMap();
    return $map[$username] ?? null;
}

/**
 * 生成具有 5 分钟有效期的 Token
 */
function generateToken($uid, $passwordHash) {
    global $stationID;
    $expires = time() + 300;
    $data = $uid . ':' . $expires . ':' . $passwordHash;
    $signature = hash_hmac('sha256', $data, $stationID);
    return $uid . ':' . $expires . ':' . $signature;
}

/**
 * 统一认证：验证 Token 格式、有效期、签名
 */
function authenticate() {
    global $stationID;
    $headers = getallheaders();
    $token = $headers['Authorization'] ?? $_GET['token'] ?? null;
    if (!$token) sendResponse(401, 'Missing token');

    $parts = explode(':', $token);
    if (count($parts) != 3) sendResponse(401, 'Invalid token format');

    list($uid, $expires, $signature) = $parts;
    if (!ctype_digit($expires) || (int)$expires < time()) {
        sendResponse(401, 'Token expired');
    }

    $user = getUserData($uid);
    if (!$user) sendResponse(401, 'User not found');

    $data = $uid . ':' . $expires . ':' . $user['password'];
    $expected = hash_hmac('sha256', $data, $stationID);
    if (!hash_equals($expected, $signature)) {
        sendResponse(401, 'Invalid token');
    }

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
        case 'refresh_token': refreshToken(); break;   // 新增：刷新token
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
        case 'get_friend_avatar_img_md5': $user = authenticate(); getFriendAvatarImgMd5($user); break;
        default: sendResponse(400, 'Invalid action');
    }
} catch (Exception $e) {
    sendResponse(500, 'Server error: ' . $e->getMessage());
}

// ---------------------- 功能实现 ----------------------
function register() {
    global $stationID;
    $input = json_decode(file_get_contents('php://input'), true);
    $username = trim($input['username'] ?? '');
    $password = $input['password'] ?? '';
    
    if (empty($username) || empty($password)) sendResponse(400, 'Username and password required');
    if (strlen($username) > 12) sendResponse(400, 'Username too long (max 12)');

    $map = getUsernameMap();
    if (isset($map[$username])) sendResponse(409, 'Username already exists');
    
    $uid = generateRandomString(32);
    $passwordHash = password_hash($password, PASSWORD_DEFAULT);
    $now = time();
    $user = [
        'id' => $uid,
        'username' => $username,
        'password' => $passwordHash,
        'friend_verify' => 'need_verify',
        'registered_at' => $now,
        'station_id' => $stationID,
        'friend_requests' => [],
        'friends' => [],
        'message_count' => 0,
        'avatar_md5' => null,
        'avatar_ext' => null,
        'deleted' => false,
        'last_refresh_time' => 0,   // 新增：上次刷新token的时间戳
    ];
    
    if (!saveUserData($user)) sendResponse(500, 'Failed to save user');
    updateUsernameMap($username, $uid);

    $token = generateToken($uid, $passwordHash);
    sendResponse(200, 'Registered successfully', ['uid' => $uid, 'token' => $token]);
}

function login() {
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
    $token = generateToken($user['id'], $user['password']);
    sendResponse(200, 'Login successful', ['uid' => $user['id'], 'token' => $token]);
}

/**
 * 刷新 token（需要当前 token 有效，且距离上次刷新超过 4 分钟）
 */
function refreshToken() {
    // 先验证当前 token 是否有效（未过期且签名正确）
    $user = authenticate();  // 如果 token 过期或无效会直接返回错误

    $now = time();
    $last = $user['last_refresh_time'] ?? 0;
    if ($now - $last < 240) {  // 4 分钟内不能再次刷新
        sendResponse(429, 'Token refresh too frequent, please wait ' . (240 - ($now - $last)) . ' seconds');
    }

    // 生成新 token
    $newToken = generateToken($user['id'], $user['password']);

    // 更新用户的 last_refresh_time
    updateUserData($user['id'], function($u) use ($now) {
        $u['last_refresh_time'] = $now;
        return $u;
    });

    sendResponse(200, 'Token refreshed', ['token' => $newToken]);
}

function uploadAvatar($user) {
    global $stationID;
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

    $md5 = md5_file($file['tmp_name']);
    $avatarDir = AVATAR_DIR . '/' . $stationID . '/' . $user['id'];
    if (!is_dir($avatarDir)) mkdir($avatarDir, 0755, true);
    
    $oldFiles = glob($avatarDir . '/*');
    foreach ($oldFiles as $old) unlink($old);
    
    $avatarPath = $avatarDir . '/' . $md5 . '.' . $ext;
    if (move_uploaded_file($file['tmp_name'], $avatarPath)) {
        updateUserData($user['id'], function($u) use ($md5, $ext) {
            $u['avatar_md5'] = $md5;
            $u['avatar_ext'] = $ext;
            return $u;
        });
        sendResponse(200, 'Avatar uploaded');
    } else {
        sendResponse(500, 'Failed to save avatar');
    }
}

function getAvatar() {
    global $stationID;
    $uid = $_GET['uid'] ?? '';
    if (empty($uid)) sendResponse(400, 'Missing user ID');
    
    $user = getUserData($uid);
    if (!$user || !empty($user['deleted'])) {
        http_response_code(404);
        exit;
    }
    
    $avatarMd5 = $user['avatar_md5'] ?? null;
    $avatarExt = $user['avatar_ext'] ?? null;
    if ($avatarMd5 && $avatarExt) {
        $avatarFile = AVATAR_DIR . '/' . $stationID . '/' . $uid . '/' . $avatarMd5 . '.' . $avatarExt;
        if (file_exists($avatarFile)) {
            $mime = mime_content_type($avatarFile);
            header('Content-Type: ' . $mime);
            header("Access-Control-Allow-Origin: *");
            readfile($avatarFile);
            exit;
        }
    }
    
    http_response_code(404);
    exit;
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
    $messages = [];
    if (file_exists($filePath)) {
        $lines = file($filePath, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
        foreach ($lines as $line) {
            $msg = json_decode($line, true);
            if (is_array($msg) && isset($msg['time'])) {
                $messages[] = $msg;
            }
        }
    }
    $messages[] = $message;
    $cutoff = time() - ($daysToKeep * 86400);
    $messages = array_filter($messages, function($msg) use ($cutoff) {
        return $msg['time'] >= $cutoff;
    });
    $content = '';
    foreach ($messages as $msg) {
        $content .= json_encode($msg) . "\n";
    }
    file_put_contents($filePath, $content);
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
        usort($messages, function($a, $b) { return $a['time'] - $b['time']; });
    }

    $since = isset($_GET['since']) ? (int)$_GET['since'] : 0;
    if ($since > 0) {
        $result = array_values(array_filter($messages, function($msg) use ($since) {
            return $msg['time'] >= $since;
        }));
        sendResponse(200, 'Messages retrieved', $result);
        return;
    }

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
    global $completelyDelete, $stationID;
    if ($completelyDelete) {
        $userFile = USER_DIR . '/' . $user['id'] . '.json';
        if (file_exists($userFile)) unlink($userFile);
        updateUsernameMap($user['username'], null);
        $avatarDir = AVATAR_DIR . '/' . $stationID . '/' . $user['id'];
        if (is_dir($avatarDir)) {
            foreach (glob($avatarDir . '/*') as $f) unlink($f);
            rmdir($avatarDir);
        }
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
function getFriends($user) {
    $friends = [];
    foreach ($user['friends'] as $friendUid) {
        $f = getUserData($friendUid);
        if ($f && empty($f['deleted'])) {
            $friends[] = [
                'uid' => $f['id'],
                'username' => $f['username'],
                'avatar' => '/api.php?action=get_avatar&uid=' . $f['id'],
                'registered_at' => $f['registered_at'],
                'station_id' => $f['station_id']
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
        'uid' => $user['id'],
        'username' => $user['username'],
        'avatar' => '/api.php?action=get_avatar&uid=' . $user['id'],
        'registered_at' => $user['registered_at'],
        'station_id' => $user['station_id'],
        'friend_verify' => $user['friend_verify'],
        'message_count' => $user['message_count'] ?? 0
    ]);
}

function getFriendAvatarImgMd5($user) {
    $result = [];
    foreach ($user['friends'] as $friendUid) {
        $friend = getUserData($friendUid);
        if ($friend && empty($friend['deleted'])) {
            $md5 = $friend['avatar_md5'] ?? null;
            $result[$friendUid] = $md5;
        } else {
            $result[$friendUid] = null;
        }
    }
    sendResponse(200, 'Friend avatar md5 list', $result);
}
?>