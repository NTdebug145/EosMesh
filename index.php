<?php
/**
 * EosMesh - 去中心化API（增强版：支持文件上传/下载，分块上传，大小限制）
 * 加入 token 刷新机制：有效期5分钟，每4分钟可刷新一次
 * 
 * 安全改进：彻底移除全局索引文件，文件路径动态扫描，不存储任何直链
 */

if (ob_get_level() == 0) ob_start();

error_reporting(E_ALL);
ini_set('display_errors', 0);
ini_set('log_errors', 1);
ini_set('error_log', __DIR__ . '/data/error.log');

ini_set('upload_max_filesize', '100M');
ini_set('post_max_size', '110M');
ini_set('max_execution_time', 3600);
ini_set('memory_limit', '512M');

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

define('STATION_VERSION', 'b26.4.30');
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
    $defaultConfig = "[station]\nstationID={$stationID}\nstationNumberDaysInformationStored=3\nstationWhetherCompletelyDeleteUserData=true\nuploadFileSizeLimitGB=1\n";
    file_put_contents(CONFIG_FILE, $defaultConfig);
}
$fullConfig = parse_ini_file(CONFIG_FILE, true);
$config = $fullConfig['station'];
$stationID = $config['stationID'];
$daysToKeep = (int)$config['stationNumberDaysInformationStored'];
$completelyDelete = filter_var($config['stationWhetherCompletelyDeleteUserData'], FILTER_VALIDATE_BOOLEAN);
$uploadFileSizeLimitGB = isset($config['uploadFileSizeLimitGB']) ? (int)$config['uploadFileSizeLimitGB'] : 1;
if ($uploadFileSizeLimitGB <= 0) $uploadFileSizeLimitGB = 1;
$maxFileSizeBytes = $uploadFileSizeLimitGB * 1024 * 1024 * 1024;

$usernameMapFile = DATA_DIR . '/username_map.json';
if (!file_exists($usernameMapFile)) file_put_contents($usernameMapFile, json_encode([]));

// 文件存储相关常量（彻底移除全局索引）
define('FILES_BASE_DIR', DATA_DIR . '/files/' . $stationID);
define('CHUNK_TMP_DIR', DATA_DIR . '/chunk_tmp');
define('DOWNLOAD_TOKEN_TTL', 3600);

foreach ([FILES_BASE_DIR, CHUNK_TMP_DIR] as $dir) {
    if (!file_exists($dir)) mkdir($dir, 0755, true);
}

// 删除遗留的 file_index.json（如果存在）
$legacyIndex = DATA_DIR . '/file_index.json';
if (file_exists($legacyIndex)) {
    unlink($legacyIndex);
}

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

function generateToken($uid, $passwordHash) {
    global $stationID;
    $expires = time() + 300;
    $data = $uid . ':' . $expires . ':' . $passwordHash;
    $signature = hash_hmac('sha256', $data, $stationID);
    return $uid . ':' . $expires . ':' . $signature;
}

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

// ---------------------- 动态扫描（无任何索引文件）---------------------
$fileLocationCache = [];

function findFileLocation($fileId) {
    global $fileLocationCache;
    if (isset($fileLocationCache[$fileId])) {
        return $fileLocationCache[$fileId];
    }

    $fileIdDir = FILES_BASE_DIR . '/' . $fileId;
    if (!is_dir($fileIdDir)) return null;

    $iterator = new RecursiveIteratorIterator(
        new RecursiveDirectoryIterator($fileIdDir, RecursiveDirectoryIterator::SKIP_DOTS)
    );
    foreach ($iterator as $file) {
        if ($file->getFilename() === 'fileinfo.json') {
            $metaPath = $file->getPathname();
            $meta = json_decode(file_get_contents($metaPath), true);
            if (is_array($meta) && isset($meta['file_id']) && $meta['file_id'] === $fileId) {
                $physicalPath = $file->getPath() . '/' . $meta['md5'];
                if (file_exists($physicalPath)) {
                    $result = [
                        'meta_path' => $metaPath,
                        'physical_path' => $physicalPath,
                        'meta' => $meta
                    ];
                    $fileLocationCache[$fileId] = $result;
                    return $result;
                }
            }
        }
    }
    return null;
}

function getFileMeta($fileId) {
    $loc = findFileLocation($fileId);
    return $loc ? $loc['meta'] : null;
}

function updateFileMeta($fileId, $meta) {
    $loc = findFileLocation($fileId);
    if (!$loc) return false;
    $meta['file_id'] = $fileId;
    return file_put_contents($loc['meta_path'], json_encode($meta)) !== false;
}

function generateDownloadToken($fileId, $userId) {
    global $stationID;
    $expires = time() + DOWNLOAD_TOKEN_TTL;
    // 只使用 fileId 和过期时间
    $data = $fileId . ':' . $expires;
    $signature = hash_hmac('sha256', $data, $stationID);
    return $fileId . ':' . $expires . ':' . $signature;
}

function verifyDownloadToken($token, $fileId) {
    global $stationID;
    $parts = explode(':', $token);
    if (count($parts) != 3) return false;
    list($tokenFileId, $expires, $signature) = $parts;
    if ($tokenFileId !== $fileId || (int)$expires < time()) return false;
    $data = $fileId . ':' . $expires;
    $expected = hash_hmac('sha256', $data, $stationID);
    return hash_equals($expected, $signature);
}

function storePhysicalFile($fileId, $userId, $tmpPath, $fileMd5, $originalName, $fileSize = null) {
    global $maxFileSizeBytes;
    if ($fileSize === null) $fileSize = filesize($tmpPath);
    if ($fileSize > $maxFileSizeBytes) return false;
    
    $date = getdate();
    $year = $date['year'];
    $month = str_pad($date['mon'], 2, '0', STR_PAD_LEFT);
    $day = str_pad($date['mday'], 2, '0', STR_PAD_LEFT);
    $randomDir = generateRandomString(8);
    $targetDir = FILES_BASE_DIR . '/' . $fileId . '/' . $userId . '/' . $year . '/' . $month . '/' . $day . '/' . $randomDir;
    if (!is_dir($targetDir)) mkdir($targetDir, 0755, true);
    
    $physicalFile = $targetDir . '/' . $fileMd5;
    $metaFile = $targetDir . '/fileinfo.json';
    
    if (!move_uploaded_file($tmpPath, $physicalFile)) return false;
    
    $meta = [
        'file_id' => $fileId,
        'original_name' => basename($originalName),
        'md5' => $fileMd5,
        'size' => $fileSize,
        'download_count' => 0,
        'allow_download' => true,
        'uploader_uid' => $userId,
        'upload_time' => time()
    ];
    file_put_contents($metaFile, json_encode($meta));
    return true;
}

// ---------------------- 文件上传功能 ----------------------
function uploadFile($user) {
    global $maxFileSizeBytes;
    if (!isset($_FILES['file']) || $_FILES['file']['error'] !== UPLOAD_ERR_OK) {
        sendResponse(400, 'Missing file or upload error');
    }
    $file = $_FILES['file'];
    $originalName = $file['name'];
    $tmpPath = $file['tmp_name'];
    $fileSize = $file['size'];
    
    if ($fileSize > $maxFileSizeBytes) {
        sendResponse(413, 'File size exceeds limit (' . ($maxFileSizeBytes / 1024 / 1024 / 1024) . ' GB)');
    }
    
    $fileMd5 = md5_file($tmpPath);
    $fileId = generateRandomString(16);
    if (!storePhysicalFile($fileId, $user['id'], $tmpPath, $fileMd5, $originalName, $fileSize)) {
        sendResponse(500, 'Failed to save file');
    }
    
    updateUserData($user['id'], function($u) use ($fileId) {
        if (!isset($u['files'])) $u['files'] = [];
        $u['files'][] = $fileId;
        return $u;
    });
    
    sendResponse(200, 'File uploaded successfully', ['file_id' => $fileId]);
}

function multipartInit($user) {
    global $maxFileSizeBytes;
    $input = json_decode(file_get_contents('php://input'), true);
    $originalName = $input['original_name'] ?? '';
    $totalChunks = (int)($input['total_chunks'] ?? 0);
    $chunkSize = (int)($input['chunk_size'] ?? 0);
    if (!$originalName || $totalChunks <= 0 || $chunkSize <= 0) {
        sendResponse(400, 'Missing parameters: original_name, total_chunks, chunk_size');
    }
    $totalSize = $totalChunks * $chunkSize;
    if ($totalSize > $maxFileSizeBytes) {
        sendResponse(413, 'Total file size exceeds limit (' . ($maxFileSizeBytes / 1024 / 1024 / 1024) . ' GB)');
    }
    
    $uploadId = generateRandomString(32);
    $tmpDir = CHUNK_TMP_DIR . '/' . $uploadId;
    mkdir($tmpDir, 0755, true);
    
    $session = [
        'upload_id' => $uploadId,
        'user_id' => $user['id'],
        'original_name' => $originalName,
        'total_chunks' => $totalChunks,
        'chunk_size' => $chunkSize,
        'received_chunks' => [],
        'created_at' => time()
    ];
    file_put_contents($tmpDir . '/session.json', json_encode($session));
    sendResponse(200, 'Multipart upload initialized', ['upload_id' => $uploadId]);
}

function multipartUpload($user) {
    $uploadId = $_POST['upload_id'] ?? '';
    $partNumber = (int)($_POST['part_number'] ?? 0);
    if (!$uploadId || $partNumber <= 0 || !isset($_FILES['chunk'])) {
        sendResponse(400, 'Missing upload_id, part_number or chunk file');
    }
    
    if ($_FILES['chunk']['error'] !== UPLOAD_ERR_OK) {
        $errors = [
            UPLOAD_ERR_INI_SIZE => '文件超过服务器 upload_max_filesize 限制',
            UPLOAD_ERR_FORM_SIZE => '文件超过表单 MAX_FILE_SIZE 限制',
            UPLOAD_ERR_PARTIAL => '文件只有部分被上传',
            UPLOAD_ERR_NO_FILE => '没有文件被上传',
            UPLOAD_ERR_NO_TMP_DIR => '找不到临时文件夹',
            UPLOAD_ERR_CANT_WRITE => '文件写入失败',
            UPLOAD_ERR_EXTENSION => 'PHP扩展阻止了文件上传',
        ];
        $errorMsg = $errors[$_FILES['chunk']['error']] ?? '未知上传错误';
        sendResponse(400, 'Chunk upload error: ' . $errorMsg);
    }
    
    $tmpDir = CHUNK_TMP_DIR . '/' . $uploadId;
    $sessionFile = $tmpDir . '/session.json';
    if (!file_exists($sessionFile)) sendResponse(404, 'Upload session not found');
    
    $session = json_decode(file_get_contents($sessionFile), true);
    if ($session['user_id'] !== $user['id']) sendResponse(403, 'Permission denied');
    
    $chunkFile = $tmpDir . '/' . $partNumber . '.part';
    if (!move_uploaded_file($_FILES['chunk']['tmp_name'], $chunkFile)) {
        sendResponse(500, 'Failed to save chunk');
    }
    
    $session['received_chunks'][] = $partNumber;
    file_put_contents($sessionFile, json_encode($session));
    sendResponse(200, 'Chunk uploaded successfully');
}

function multipartComplete($user) {
    global $maxFileSizeBytes;
    $input = json_decode(file_get_contents('php://input'), true);
    $uploadId = $input['upload_id'] ?? '';
    if (!$uploadId) sendResponse(400, 'Missing upload_id');
    
    $tmpDir = CHUNK_TMP_DIR . '/' . $uploadId;
    $sessionFile = $tmpDir . '/session.json';
    if (!file_exists($sessionFile)) sendResponse(404, 'Upload session not found');
    
    $session = json_decode(file_get_contents($sessionFile), true);
    if ($session['user_id'] !== $user['id']) sendResponse(403, 'Permission denied');
    
    $expected = range(1, $session['total_chunks']);
    $received = $session['received_chunks'];
    sort($received);
    if ($expected !== $received) {
        sendResponse(400, 'Missing chunks, cannot complete');
    }
    
    // 合并分块
    $mergedTmp = tempnam(sys_get_temp_dir(), 'merge_');
    $out = fopen($mergedTmp, 'wb');
    $totalSize = 0;
    for ($i = 1; $i <= $session['total_chunks']; $i++) {
        $partFile = $tmpDir . '/' . $i . '.part';
        $totalSize += filesize($partFile);
        if ($totalSize > $maxFileSizeBytes) {
            fclose($out);
            unlink($mergedTmp);
            sendResponse(413, 'Total file size exceeds limit');
        }
        $in = fopen($partFile, 'rb');
        stream_copy_to_stream($in, $out);
        fclose($in);
        unlink($partFile);
    }
    fclose($out);
    
    if ($totalSize > $maxFileSizeBytes) {
        unlink($mergedTmp);
        sendResponse(413, 'Total file size exceeds limit');
    }
    
    $fileMd5 = md5_file($mergedTmp);
    $fileId = generateRandomString(16);
    
    $date = getdate();
    $year = $date['year'];
    $month = str_pad($date['mon'], 2, '0', STR_PAD_LEFT);
    $day = str_pad($date['mday'], 2, '0', STR_PAD_LEFT);
    $randomDir = generateRandomString(8);
    $targetDir = FILES_BASE_DIR . '/' . $fileId . '/' . $user['id'] . '/' . $year . '/' . $month . '/' . $day . '/' . $randomDir;
    if (!is_dir($targetDir)) mkdir($targetDir, 0755, true);
    
    $physicalFile = $targetDir . '/' . $fileMd5;
    $metaFile = $targetDir . '/fileinfo.json';
    
    if (!rename($mergedTmp, $physicalFile)) {
        if (file_exists($mergedTmp)) unlink($mergedTmp);
        sendResponse(500, 'Failed to save merged file');
    }
    
    $meta = [
        'file_id' => $fileId,
        'original_name' => basename($session['original_name']),
        'md5' => $fileMd5,
        'size' => $totalSize,
        'download_count' => 0,
        'allow_download' => true,
        'uploader_uid' => $user['id'],
        'upload_time' => time()
    ];
    file_put_contents($metaFile, json_encode($meta));
    
    // 清理临时目录
    array_map('unlink', glob($tmpDir . '/*'));
    rmdir($tmpDir);
    
    updateUserData($user['id'], function($u) use ($fileId) {
        if (!isset($u['files'])) $u['files'] = [];
        $u['files'][] = $fileId;
        return $u;
    });
    
    sendResponse(200, 'File uploaded successfully (multipart)', ['file_id' => $fileId]);
}

function getFileInfo($user) {
    $input = json_decode(file_get_contents('php://input'), true);
    $fileId = $input['file_id'] ?? $_GET['file_id'] ?? '';
    if (!$fileId) sendResponse(400, 'Missing file_id');
    
    $meta = getFileMeta($fileId);
    if (!$meta) sendResponse(404, 'File not found');
    
    if ($meta['uploader_uid'] === $user['id']) {
        sendResponse(200, 'File info retrieved', [
            'file_id' => $fileId,
            'original_name' => $meta['original_name'],
            'md5' => $meta['md5'],
            'size' => $meta['size'],
            'download_count' => $meta['download_count'],
            'allow_download' => $meta['allow_download'],
            'upload_time' => $meta['upload_time']
        ]);
    } else {
        sendResponse(200, 'File info retrieved (public)', [
            'file_id' => $fileId,
            'original_name' => $meta['original_name'],
            'size' => $meta['size'],
            'allow_download' => $meta['allow_download']
        ]);
    }
}

function setFileDownloadable($user) {
    $input = json_decode(file_get_contents('php://input'), true);
    $fileId = $input['file_id'] ?? '';
    $allowDownload = (bool)($input['allow_download'] ?? false);
    if (!$fileId) sendResponse(400, 'Missing file_id');
    
    $meta = getFileMeta($fileId);
    if (!$meta) sendResponse(404, 'File not found');
    if ($meta['uploader_uid'] !== $user['id']) sendResponse(403, 'Only uploader can change this setting');
    
    $meta['allow_download'] = $allowDownload;
    if (updateFileMeta($fileId, $meta)) {
        sendResponse(200, 'Download permission updated');
    } else {
        sendResponse(500, 'Failed to update');
    }
}

function generateDownloadUrl($user) {
    $input = json_decode(file_get_contents('php://input'), true);
    $fileId = $input['file_id'] ?? '';
    if (!$fileId) sendResponse(400, 'Missing file_id');
    
    $meta = getFileMeta($fileId);
    if (!$meta) sendResponse(404, 'File not found');
    if (!$meta['allow_download'] && $meta['uploader_uid'] !== $user['id']) {
        sendResponse(403, 'File is not allowed to download');
    }
    
    $token = generateDownloadToken($fileId, $user['id']);
    $downloadUrl = (isset($_SERVER['HTTPS']) ? 'https://' : 'http://')
                   . $_SERVER['HTTP_HOST']
                   . rtrim(dirname($_SERVER['SCRIPT_NAME']), '/')
                   . '/index.php?action=get_file&fid=' . urlencode($fileId)
                   . '&dt=' . urlencode($token);
    sendResponse(200, 'Download link generated', ['url' => $downloadUrl, 'expires_in' => DOWNLOAD_TOKEN_TTL]);
}

function getFile() {
    $fileId = $_GET['fid'] ?? '';
    $token = $_GET['dt'] ?? '';
    if (!$fileId || !$token) sendResponse(400, 'Missing fid or dt');
    
    if (!verifyDownloadToken($token, $fileId)) sendResponse(403, 'Invalid or expired download token');
    
    $loc = findFileLocation($fileId);
    if (!$loc) sendResponse(404, 'File not found');
    $meta = $loc['meta'];
    
    // 增加下载次数
    $meta['download_count']++;
    updateFileMeta($fileId, $meta);
    
    $physicalPath = $loc['physical_path'];
    if (!file_exists($physicalPath)) sendResponse(404, 'File data missing');
    
    header('Content-Type: application/octet-stream');
    header('Content-Disposition: attachment; filename="' . addslashes($meta['original_name']) . '"');
    header('Content-Length: ' . $meta['size']);
    readfile($physicalPath);
    exit;
}

// ---------------------- 原有功能（保持不变）---------------------
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
        'last_refresh_time' => 0,
        'files' => [],
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

function refreshToken() {
    $user = authenticate();
    $now = time();
    $last = $user['last_refresh_time'] ?? 0;
    if ($now - $last < 240) {
        sendResponse(429, 'Token refresh too frequent, please wait ' . (240 - ($now - $last)) . ' seconds');
    }
    $newToken = generateToken($user['id'], $user['password']);
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
        //'files' => $user['files'] ?? []
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

function getStationConfig() {
    global $uploadFileSizeLimitGB, $maxFileSizeBytes;
    sendResponse(200, 'OK', [
        'upload_limit_gb' => $uploadFileSizeLimitGB,
        'upload_limit_bytes' => $maxFileSizeBytes
    ]);
}

function getUserFiles($user) {
    $files = $user['files'] ?? [];
    sendResponse(200, 'User files retrieved', ['files' => $files]);
}

// ---------------------- 路由分发 ----------------------
$action = $_GET['action'] ?? '';
try {
    switch ($action) {
        case 'register': register(); break;
        case 'login': login(); break;
        case 'refresh_token': refreshToken(); break;
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
        // 文件相关
        case 'upload_file': $user = authenticate(); uploadFile($user); break;
        case 'multipart_init': $user = authenticate(); multipartInit($user); break;
        case 'multipart_upload': $user = authenticate(); multipartUpload($user); break;
        case 'multipart_complete': $user = authenticate(); multipartComplete($user); break;
        case 'get_file_info': $user = authenticate(); getFileInfo($user); break;
        case 'set_file_downloadable': $user = authenticate(); setFileDownloadable($user); break;
        case 'generate_download_url': $user = authenticate(); generateDownloadUrl($user); break;
        case 'get_file': getFile(); break;
        case 'get_station_config': getStationConfig(); break;

        case 'get_user_files': $user = authenticate(); getUserFiles($user); break;
        default: sendResponse(400, 'Invalid action');
    }
} catch (Exception $e) {
    sendResponse(500, 'Server error: ' . $e->getMessage());
}
?>