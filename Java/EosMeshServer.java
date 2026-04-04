import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class EosMeshServer {
    private static final String STATION_VERSION = "b26.4.4";
    private static final String SERVER_TYPE = "java";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_MESSAGE_LEN = 1500;
    private static final long MAX_AVATAR_SIZE = 2 * 1024 * 1024;

    private static String ROOT_DIR;
    private static String DATA_DIR;
    private static String USER_DIR;
    private static String CHAT_DIR;
    private static String AVATAR_DIR;
    private static String CONFIG_FILE;
    private static String USERNAME_MAP_FILE;   // username -> uid

    private static String stationID;
    private static int daysToKeep;
    private static boolean completelyDelete;

    public static void main(String[] args) throws IOException {
        // 初始化目录
        ROOT_DIR = System.getProperty("user.dir");
        DATA_DIR = ROOT_DIR + File.separator + "data";
        USER_DIR = DATA_DIR + File.separator + "user";
        CHAT_DIR = DATA_DIR + File.separator + "chat" + File.separator + "friend";
        AVATAR_DIR = DATA_DIR + File.separator + "avatar";
        CONFIG_FILE = ROOT_DIR + File.separator + "station.ini";
        USERNAME_MAP_FILE = DATA_DIR + File.separator + "username_map.json";

        createDirIfNotExists(DATA_DIR);
        createDirIfNotExists(USER_DIR);
        createDirIfNotExists(CHAT_DIR);
        createDirIfNotExists(AVATAR_DIR);

        initConfig();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/", new ApiHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("127.0.0.1:" + port);
        System.out.println("EosMesh Server started on port " + port);
    }

    private static void createDirIfNotExists(String path) {
        File dir = new File(path);
        if (!dir.exists()) dir.mkdirs();
    }

    private static void initConfig() throws IOException {
        File configFile = new File(CONFIG_FILE);
        if (!configFile.exists()) {
            String stationId = generateRandomString(16);
            String content = "[station]\nstationID=" + stationId +
                    "\nstationNumberDaysInformationStored=3\nstationWhetherCompletelyDeleteUserData=true\n";
            Files.write(Paths.get(CONFIG_FILE), content.getBytes(StandardCharsets.UTF_8));
        }

        Properties props = new Properties();
        try (InputStream input = new FileInputStream(CONFIG_FILE)) {
            props.load(input);
        }
        stationID = props.getProperty("stationID");
        daysToKeep = Integer.parseInt(props.getProperty("stationNumberDaysInformationStored", "3"));
        completelyDelete = Boolean.parseBoolean(props.getProperty("stationWhetherCompletelyDeleteUserData", "true"));

        // 初始化用户名映射文件
        File mapFile = new File(USERNAME_MAP_FILE);
        if (!mapFile.exists()) {
            Files.write(Paths.get(USERNAME_MAP_FILE), "{}".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String generateRandomString(int length) {
        String chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    // ==================== 用户名映射操作 ====================
    private static Map<String, String> readUsernameMap() throws IOException {
        return readJsonMap(USERNAME_MAP_FILE);
    }

    private static void writeUsernameMap(Map<String, String> map) throws IOException {
        writeJsonMap(USERNAME_MAP_FILE, map);
    }

    private static String getUidByUsername(String username) throws IOException {
        Map<String, String> map = readUsernameMap();
        return map.get(username);
    }

    private static void putUsernameMapping(String username, String uid) throws IOException {
        FileLock lock = null;
        try (RandomAccessFile raf = new RandomAccessFile(USERNAME_MAP_FILE, "rw");
             FileChannel channel = raf.getChannel()) {
            lock = channel.lock();
            Map<String, String> map = readJsonMapFromChannel(channel);
            if (uid == null) map.remove(username);
            else map.put(username, uid);
            writeJsonMapToChannel(channel, map);
        } finally {
            if (lock != null && lock.isValid()) lock.release();
        }
    }

    // ==================== 用户数据操作（每个用户独立文件） ====================
    private static String getUserFilePath(String uid) {
        return USER_DIR + File.separator + uid + ".json";
    }

    private static Map<String, Object> getUserData(String uid) throws IOException {
        String path = getUserFilePath(uid);
        File file = new File(path);
        if (!file.exists()) return null;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r");
             FileChannel channel = raf.getChannel();
             FileLock lock = channel.lock(0, Long.MAX_VALUE, true)) {
            byte[] bytes = new byte[(int) raf.length()];
            raf.readFully(bytes);
            Map<String, Object> user = Json.parseMap(new String(bytes, StandardCharsets.UTF_8));
            if (user != null && Boolean.TRUE.equals(user.get("deleted"))) return null;
            return user;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    private static void saveUserData(Map<String, Object> user) throws IOException {
        String uid = (String) user.get("id");
        String path = getUserFilePath(uid);
        File file = new File(path);
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw");
             FileChannel channel = raf.getChannel();
             FileLock lock = channel.lock()) {
            raf.setLength(0);
            raf.write(Json.stringify(user).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void updateUserData(String uid, UserUpdater updater) throws IOException {
        Map<String, Object> user = getUserData(uid);
        if (user == null) return;
        updater.update(user);
        saveUserData(user);
    }

    @FunctionalInterface
    interface UserUpdater {
        void update(Map<String, Object> user);
    }

    // ==================== 通用JSON文件读写（带锁） ====================
    private static Map<String, String> readJsonMap(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) return new HashMap<>();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r");
             FileChannel channel = raf.getChannel();
             FileLock lock = channel.lock(0, Long.MAX_VALUE, true)) {
            return readJsonMapFromChannel(channel);
        }
    }

    private static Map<String, String> readJsonMapFromChannel(FileChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
        channel.read(buffer);
        buffer.flip();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        String content = new String(bytes, StandardCharsets.UTF_8);
        Map<String, Object> raw = Json.parseMap(content);
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            result.put(e.getKey(), String.valueOf(e.getValue()));
        }
        return result;
    }

    private static void writeJsonMapToChannel(FileChannel channel, Map<String, String> map) throws IOException {
        channel.truncate(0);
        channel.position(0);
        byte[] bytes = Json.stringify(map).getBytes(StandardCharsets.UTF_8);
        channel.write(ByteBuffer.wrap(bytes));
    }

    private static void writeJsonMap(String filePath, Map<String, String> map) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(filePath, "rw");
             FileChannel channel = raf.getChannel();
             FileLock lock = channel.lock()) {
            writeJsonMapToChannel(channel, map);
        }
    }

    // ==================== 认证 ====================
    private static Map<String, Object> authenticate(HttpExchange exchange) throws IOException {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null) {
            String query = exchange.getRequestURI().getQuery();
            if (query != null) {
                Map<String, String> params = parseQueryParams(query);
                authHeader = params.get("token");
            }
        }
        if (authHeader == null) {
            sendResponse(exchange, 401, "Missing token", null);
            return null;
        }
        String[] parts = authHeader.split(":");
        if (parts.length != 2) {
            sendResponse(exchange, 401, "Invalid token", null);
            return null;
        }
        String uid = parts[0];
        String signature = parts[1];
        Map<String, Object> user = getUserData(uid);
        if (user == null) {
            sendResponse(exchange, 401, "User not found", null);
            return null;
        }
        String expected = hmacSha256(uid + user.get("password"), stationID);
        if (!expected.equals(signature)) {
            sendResponse(exchange, 401, "Invalid token", null);
            return null;
        }
        return user;
    }

    private static String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== 密码哈希 ====================
    private static String hashPassword(String password) {
        String salt = generateRandomString(16);
        String hash = sha256(salt + password);
        return salt + ":" + hash;
    }

    private static boolean verifyPassword(String password, String stored) {
        String[] parts = stored.split(":");
        if (parts.length != 2) return false;
        String salt = parts[0];
        String hash = parts[1];
        return hash.equals(sha256(salt + password));
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== 工具 ====================
    private static Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null) return params;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf("=");
            if (idx > 0) {
                String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                params.put(key, value);
            }
        }
        return params;
    }

    private static Map<String, Object> parseJsonBody(HttpExchange exchange) throws IOException {
        String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                .lines().collect(Collectors.joining());
        if (body.isEmpty()) return new HashMap<>();
        return Json.parseMap(body);
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String message, Object data) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", statusCode);
        response.put("msg", message);
        if (data != null) response.put("data", data);
        byte[] bytes = Json.stringify(response).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ==================== 消息存储 ====================
    private static void appendMessageToFile(String filePath, Map<String, Object> message) throws IOException {
        File file = new File(filePath);
        List<Map<String, Object>> messages = new ArrayList<>();
        if (file.exists()) {
            try (RandomAccessFile raf = new RandomAccessFile(file, "r");
                 FileChannel channel = raf.getChannel();
                 FileLock lock = channel.lock(0, Long.MAX_VALUE, true)) {
                byte[] bytes = new byte[(int) raf.length()];
                raf.readFully(bytes);
                String content = new String(bytes, StandardCharsets.UTF_8);
                for (String line : content.split("\n")) {
                    if (line.trim().isEmpty()) continue;
                    messages.add(Json.parseMap(line));
                }
            }
        }
        messages.add(message);
        long cutoff = Instant.now().getEpochSecond() - (daysToKeep * 86400L);
        messages = messages.stream()
                .filter(m -> ((Number) m.get("time")).longValue() >= cutoff)
                .collect(Collectors.toList());

        try (RandomAccessFile raf = new RandomAccessFile(file, "rw");
             FileChannel channel = raf.getChannel();
             FileLock lock = channel.lock()) {
            raf.setLength(0);
            for (Map<String, Object> msg : messages) {
                raf.write((Json.stringify(msg) + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    // ==================== API Handlers ====================
    static class ApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            String action = parseQueryParams(exchange.getRequestURI().getQuery()).get("action");
            if (action == null || action.isEmpty()) {
                sendResponse(exchange, 400, "Invalid action", null);
                return;
            }

            try {
                switch (action) {
                    case "register":
                        register(exchange);
                        break;
                    case "login":
                        login(exchange);
                        break;
                    case "upload_avatar":
                        Map<String, Object> user = authenticate(exchange);
                        if (user != null) uploadAvatar(exchange, user);
                        break;
                    case "add_friend":
                        user = authenticate(exchange);
                        if (user != null) addFriend(exchange, user);
                        break;
                    case "handle_friend_request":
                        user = authenticate(exchange);
                        if (user != null) handleFriendRequest(exchange, user);
                        break;
                    case "send_message":
                        user = authenticate(exchange);
                        if (user != null) sendMessage(exchange, user);
                        break;
                    case "get_messages":
                        user = authenticate(exchange);
                        if (user != null) getMessages(exchange, user);
                        break;
                    case "delete_account":
                        user = authenticate(exchange);
                        if (user != null) deleteAccount(exchange, user);
                        break;
                    case "get_station_version":
                        getStationVersion(exchange);
                        break;
                    case "get_server_type":
                        getServerType(exchange);
                        break;
                    case "get_verify_setting":
                        user = authenticate(exchange);
                        if (user != null) getVerifySetting(exchange, user);
                        break;
                    case "set_verify_setting":
                        user = authenticate(exchange);
                        if (user != null) setVerifySetting(exchange, user);
                        break;
                    case "get_friend_requests":
                        user = authenticate(exchange);
                        if (user != null) getFriendRequests(exchange, user);
                        break;
                    case "accept_friend_request":
                        user = authenticate(exchange);
                        if (user != null) acceptFriendRequest(exchange, user);
                        break;
                    case "get_avatar":
                        getAvatar(exchange);
                        break;
                    case "get_friends":
                        user = authenticate(exchange);
                        if (user != null) getFriends(exchange, user);
                        break;
                    case "get_user_info":
                        getUserInfo(exchange);
                        break;
                    case "get_station_id":
                        getStationId(exchange);
                        break;
                    default:
                        sendResponse(exchange, 400, "Invalid action", null);
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "Server error: " + e.getMessage(), null);
            }
        }
    }

    // 注册
    private static void register(HttpExchange exchange) throws IOException {
        Map<String, Object> input = parseJsonBody(exchange);
        String username = ((String) input.getOrDefault("username", "")).trim();
        String password = (String) input.get("password");

        if (username.isEmpty() || password == null || password.isEmpty()) {
            sendResponse(exchange, 400, "Username and password required", null);
            return;
        }
        if (username.length() > 12) {
            sendResponse(exchange, 400, "Username too long (max 12 characters)", null);
            return;
        }

        // 使用映射文件加锁检查用户名唯一性
        FileLock mapLock = null;
        try (RandomAccessFile mapRaf = new RandomAccessFile(USERNAME_MAP_FILE, "rw");
             FileChannel mapChannel = mapRaf.getChannel()) {
            mapLock = mapChannel.lock();
            Map<String, String> nameMap = readJsonMapFromChannel(mapChannel);
            if (nameMap.containsKey(username)) {
                sendResponse(exchange, 409, "Username already exists", null);
                return;
            }
            // 二次确认：扫描用户文件（防止映射文件不一致）
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(USER_DIR), "*.json")) {
                for (Path p : stream) {
                    Map<String, Object> u = getUserData(p.getFileName().toString().replace(".json", ""));
                    if (u != null && username.equals(u.get("username")) && !Boolean.TRUE.equals(u.get("deleted"))) {
                        sendResponse(exchange, 409, "Username already exists", null);
                        return;
                    }
                }
            }

            String uid = generateRandomString(32);
            String passwordHash = hashPassword(password);
            long now = Instant.now().getEpochSecond();

            Map<String, Object> user = new LinkedHashMap<>();
            user.put("id", uid);
            user.put("username", username);
            user.put("password", passwordHash);
            user.put("friend_verify", "need_verify");
            user.put("registered_at", now);
            user.put("station_id", stationID);
            user.put("friend_requests", new ArrayList<>());
            user.put("friends", new ArrayList<>());
            user.put("message_count", 0);
            user.put("avatar", null);
            user.put("deleted", false);

            saveUserData(user);
            nameMap.put(username, uid);
            writeJsonMapToChannel(mapChannel, nameMap);
            mapLock.release();

            String token = uid + ":" + hmacSha256(uid + passwordHash, stationID);
            Map<String, String> data = new LinkedHashMap<>();
            data.put("uid", uid);
            data.put("token", token);
            sendResponse(exchange, 200, "Registered successfully", data);
        } catch (IOException e) {
            if (mapLock != null && mapLock.isValid()) mapLock.release();
            throw e;
        }
    }

    // 登录
    private static void login(HttpExchange exchange) throws IOException {
        Map<String, Object> input = parseJsonBody(exchange);
        String username = ((String) input.getOrDefault("username", "")).trim();
        String password = (String) input.get("password");

        if (username.isEmpty() || password == null || password.isEmpty()) {
            sendResponse(exchange, 400, "Username and password required", null);
            return;
        }

        String uid = getUidByUsername(username);
        if (uid == null) {
            sendResponse(exchange, 401, "Invalid username or password", null);
            return;
        }
        Map<String, Object> user = getUserData(uid);
        if (user == null || !verifyPassword(password, (String) user.get("password"))) {
            sendResponse(exchange, 401, "Invalid username or password", null);
            return;
        }

        String token = uid + ":" + hmacSha256(uid + (String) user.get("password"), stationID);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("uid", uid);
        data.put("token", token);
        sendResponse(exchange, 200, "Login successful", data);
    }

    // 上传头像（multipart解析增强）
    private static void uploadAvatar(HttpExchange exchange, Map<String, Object> user) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.startsWith("multipart/form-data")) {
            sendResponse(exchange, 400, "Avatar file required", null);
            return;
        }

        String boundary = "--" + contentType.substring(contentType.indexOf("boundary=") + 9);
        byte[] body = exchange.getRequestBody().readAllBytes();
        String bodyStr = new String(body, StandardCharsets.ISO_8859_1);
        // 查找文件数据开始位置
        int fileStart = bodyStr.indexOf("\r\n\r\n", bodyStr.indexOf("filename="));
        if (fileStart == -1) {
            sendResponse(exchange, 400, "Invalid file data", null);
            return;
        }
        fileStart += 4;
        int fileEnd = bodyStr.indexOf(boundary, fileStart);
        if (fileEnd == -1) fileEnd = bodyStr.length();
        byte[] fileData = Arrays.copyOfRange(body, fileStart, fileEnd);
        // 去除尾部可能多余的 \r\n
        int trim = fileData.length;
        while (trim > 0 && (fileData[trim - 1] == '\r' || fileData[trim - 1] == '\n')) trim--;
        fileData = Arrays.copyOf(fileData, trim);

        if (fileData.length > MAX_AVATAR_SIZE) {
            sendResponse(exchange, 400, "File too large (max 2MB)", null);
            return;
        }

        // 检测图片类型
        String ext = "png";
        if (fileData.length > 4) {
            if (fileData[0] == (byte) 0xFF && fileData[1] == (byte) 0xD8) ext = "jpg";
            else if (fileData[0] == 'G' && fileData[1] == 'I' && fileData[2] == 'F') ext = "gif";
            else if (fileData[0] == (byte) 0x89 && fileData[1] == 'P' && fileData[2] == 'N' && fileData[3] == 'G') ext = "png";
            else {
                sendResponse(exchange, 400, "Unsupported image format", null);
                return;
            }
        }

        // 删除旧头像
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(AVATAR_DIR), (String) user.get("id") + ".*")) {
            for (Path p : stream) Files.deleteIfExists(p);
        } catch (IOException ignored) {}

        String avatarPath = AVATAR_DIR + File.separator + user.get("id") + "." + ext;
        Files.write(Paths.get(avatarPath), fileData);
        updateUserData((String) user.get("id"), u -> u.put("avatar", avatarPath));
        sendResponse(exchange, 200, "Avatar uploaded", null);
    }

    // 获取头像
    private static void getAvatar(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQueryParams(exchange.getRequestURI().getQuery());
        String uid = params.get("uid");
        if (uid == null || uid.isEmpty()) {
            sendResponse(exchange, 400, "Missing user ID", null);
            return;
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        Path avatarDir = Paths.get(AVATAR_DIR);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(avatarDir, uid + ".*")) {
            Path avatarFile = null;
            for (Path p : stream) {
                avatarFile = p;
                break;
            }
            if (avatarFile == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String mime = Files.probeContentType(avatarFile);
            if (mime == null) mime = "application/octet-stream";
            exchange.getResponseHeaders().set("Content-Type", mime);
            exchange.sendResponseHeaders(200, Files.size(avatarFile));
            try (OutputStream os = exchange.getResponseBody()) {
                Files.copy(avatarFile, os);
            }
        } catch (Exception e) {
            exchange.sendResponseHeaders(404, -1);
        }
    }

    // 添加好友
// 添加好友
private static void addFriend(HttpExchange exchange, Map<String, Object> user) throws IOException {
    Map<String, Object> input = parseJsonBody(exchange);
    String targetName = ((String) input.getOrDefault("username", "")).trim();
    if (targetName.isEmpty()) {
        sendResponse(exchange, 400, "Target username or UID required", null);
        return;
    }

    Map<String, Object> targetUser = getUserData(targetName);
    if (targetUser == null) {
        String uid = getUidByUsername(targetName);
        if (uid != null) targetUser = getUserData(uid);
    }
    if (targetUser == null || Boolean.TRUE.equals(targetUser.get("deleted"))) {
        sendResponse(exchange, 404, "User not found", null);
        return;
    }
    if (targetUser.get("id").equals(user.get("id"))) {
        sendResponse(exchange, 400, "Cannot add yourself", null);
        return;
    }
    List<String> friends = (List<String>) user.get("friends");
    if (friends.contains(targetUser.get("id"))) {
        sendResponse(exchange, 400, "Already friends", null);
        return;
    }

    String verifyMode = (String) targetUser.get("friend_verify");
    if ("deny_all".equals(verifyMode)) {
        sendResponse(exchange, 403, "User does not accept friend requests", null);
        return;
    } else if ("need_verify".equals(verifyMode)) {
        // 检查是否已有待处理请求
        List<Map<String, Object>> requests = (List<Map<String, Object>>) targetUser.get("friend_requests");
        for (Map<String, Object> req : requests) {
            if (user.get("id").equals(req.get("from_uid")) && "pending".equals(req.get("status"))) {
                sendResponse(exchange, 409, "Friend request already sent", null);
                return;
            }
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("from_uid", user.get("id"));
        request.put("time", Instant.now().getEpochSecond());
        request.put("status", "pending");
        updateUserData((String) targetUser.get("id"), u -> {
            List<Map<String, Object>> reqs = (List<Map<String, Object>>) u.get("friend_requests");
            reqs.add(request);
            u.put("friend_requests", reqs);
        });
        sendResponse(exchange, 200, "Friend request sent", null);
    } else { // allow_all
        final Map<String, Object> finalTarget = targetUser;   // 关键：转为 final 变量
        updateUserData((String) user.get("id"), u -> {
            ((List<String>) u.get("friends")).add((String) finalTarget.get("id"));
        });
        updateUserData((String) finalTarget.get("id"), u -> {
            ((List<String>) u.get("friends")).add((String) user.get("id"));
        });
        sendResponse(exchange, 200, "Friend added", null);
    }
}

    // 处理好友申请（接受/拒绝）
    private static void handleFriendRequest(HttpExchange exchange, Map<String, Object> user) throws IOException {
        Map<String, Object> input = parseJsonBody(exchange);
        String fromUid = (String) input.get("from_uid");
        String action = (String) input.get("action");
        if (fromUid == null || (!"accept".equals(action) && !"reject".equals(action))) {
            sendResponse(exchange, 400, "Invalid parameters", null);
            return;
        }

        final boolean[] updated = {false};
        updateUserData((String) user.get("id"), u -> {
            List<Map<String, Object>> requests = (List<Map<String, Object>>) u.get("friend_requests");
            for (Map<String, Object> req : requests) {
                if (fromUid.equals(req.get("from_uid")) && "pending".equals(req.get("status"))) {
                    if ("accept".equals(action)) {
                        req.put("status", "accepted");
                        updated[0] = true;
                    } else {
                        req.put("status", "rejected");
                    }
                    break;
                }
            }
        });
        if (!updated[0]) {
            sendResponse(exchange, 404, "Request not found", null);
            return;
        }
        if ("accept".equals(action)) {
            Map<String, Object> targetUser = getUserData(fromUid);
            if (targetUser == null || Boolean.TRUE.equals(targetUser.get("deleted"))) {
                sendResponse(exchange, 404, "Target user no longer exists", null);
                return;
            }
            updateUserData((String) user.get("id"), u -> {
                if (!((List<String>) u.get("friends")).contains(fromUid)) {
                    ((List<String>) u.get("friends")).add(fromUid);
                }
            });
            updateUserData(fromUid, u -> {
                if (!((List<String>) u.get("friends")).contains(user.get("id"))) {
                    ((List<String>) u.get("friends")).add((String) user.get("id"));
                }
            });
            sendResponse(exchange, 200, "Friend request accepted", null);
        } else {
            sendResponse(exchange, 200, "Friend request rejected", null);
        }
    }

    // 发送消息
    private static void sendMessage(HttpExchange exchange, Map<String, Object> user) throws IOException {
        Map<String, Object> input = parseJsonBody(exchange);
        String toUid = (String) input.get("to_uid");
        String content = ((String) input.getOrDefault("content", "")).trim();
        if (toUid == null || toUid.isEmpty() || content.isEmpty()) {
            sendResponse(exchange, 400, "Missing parameters", null);
            return;
        }
        if (content.length() > MAX_MESSAGE_LEN) {
            sendResponse(exchange, 400, "Message too long (max 1500 characters)", null);
            return;
        }

        Map<String, Object> targetUser = getUserData(toUid);
        if (targetUser == null) {
            sendResponse(exchange, 404, "Target user not found", null);
            return;
        }
        List<String> myFriends = (List<String>) user.get("friends");
        List<String> hisFriends = (List<String>) targetUser.get("friends");
        if (!myFriends.contains(toUid) || !hisFriends.contains(user.get("id"))) {
            sendResponse(exchange, 403, "Not friends", null);
            return;
        }

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("from", user.get("id"));
        message.put("to", toUid);
        message.put("content", content);
        message.put("time", Instant.now().getEpochSecond());

        String senderDir = CHAT_DIR + File.separator + user.get("id");
        new File(senderDir).mkdirs();
        String senderFile = senderDir + File.separator + toUid + ".json";
        appendMessageToFile(senderFile, message);

        String receiverDir = CHAT_DIR + File.separator + toUid;
        new File(receiverDir).mkdirs();
        String receiverFile = receiverDir + File.separator + user.get("id") + ".json";
        appendMessageToFile(receiverFile, message);

        updateUserData((String) user.get("id"), u -> {
            Number oldCount = (Number) u.getOrDefault("message_count", 0);
            u.put("message_count", oldCount.intValue() + 1);
        });
        sendResponse(exchange, 200, "Message sent", null);
    }

    // 获取消息记录（支持分页和增量）
    private static void getMessages(HttpExchange exchange, Map<String, Object> user) throws IOException {
        Map<String, String> params = parseQueryParams(exchange.getRequestURI().getQuery());
        String friendUid = params.get("friend_uid");
        if (friendUid == null || friendUid.isEmpty()) {
            sendResponse(exchange, 400, "Friend UID required", null);
            return;
        }
        if (!((List<String>) user.get("friends")).contains(friendUid)) {
            sendResponse(exchange, 403, "Not friends", null);
            return;
        }

        String chatFile = CHAT_DIR + File.separator + user.get("id") + File.separator + friendUid + ".json";
        List<Map<String, Object>> messages = new ArrayList<>();
        File file = new File(chatFile);
        if (file.exists()) {
            try (RandomAccessFile raf = new RandomAccessFile(file, "r");
                 FileChannel channel = raf.getChannel();
                 FileLock lock = channel.lock(0, Long.MAX_VALUE, true)) {
                byte[] bytes = new byte[(int) raf.length()];
                raf.readFully(bytes);
                String content = new String(bytes, StandardCharsets.UTF_8);
                for (String line : content.split("\n")) {
                    if (line.trim().isEmpty()) continue;
                    messages.add(Json.parseMap(line));
                }
            }
            messages.sort(Comparator.comparingLong(m -> ((Number) m.get("time")).longValue()));
        }

        // 增量模式
        String sinceParam = params.get("since");
        if (sinceParam != null && !sinceParam.isEmpty()) {
            long since = Long.parseLong(sinceParam);
            List<Map<String, Object>> filtered = messages.stream()
                    .filter(m -> ((Number) m.get("time")).longValue() >= since)
                    .collect(Collectors.toList());
            sendResponse(exchange, 200, "Messages retrieved", filtered);
            return;
        }

        // 分页模式
        int page = 1, limit = 20;
        try {
            page = Math.max(1, Integer.parseInt(params.getOrDefault("page", "1")));
            limit = Math.min(100, Math.max(1, Integer.parseInt(params.getOrDefault("limit", "20"))));
        } catch (NumberFormatException ignored) {}
        int total = messages.size();
        int offset = (page - 1) * limit;
        List<Map<String, Object>> paged = messages.stream().skip(offset).limit(limit).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messages", paged);
        result.put("total", total);
        result.put("page", page);
        result.put("limit", limit);
        result.put("total_pages", (int) Math.ceil((double) total / limit));
        sendResponse(exchange, 200, "Messages retrieved", result);
    }

    // 删除账号
    private static void deleteAccount(HttpExchange exchange, Map<String, Object> user) throws IOException {
        String uid = (String) user.get("id");
        if (completelyDelete) {
            // 删除用户文件
            Files.deleteIfExists(Paths.get(getUserFilePath(uid)));
            // 删除用户名映射
            putUsernameMapping((String) user.get("username"), null);
            // 删除头像
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(AVATAR_DIR), uid + ".*")) {
                for (Path p : stream) Files.deleteIfExists(p);
            } catch (IOException ignored) {}
            // 删除聊天记录
            Path chatUserDir = Paths.get(CHAT_DIR + File.separator + uid);
            if (Files.exists(chatUserDir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(chatUserDir)) {
                    for (Path p : stream) Files.deleteIfExists(p);
                }
                Files.deleteIfExists(chatUserDir);
            }
            // 从所有好友的好友列表中移除自己
            List<String> friends = (List<String>) user.get("friends");
            for (String friendUid : friends) {
                updateUserData(friendUid, u -> {
                    List<String> fList = (List<String>) u.get("friends");
                    fList.remove(uid);
                    u.put("friends", fList);
                });
            }
        } else {
            // 软删除
            updateUserData(uid, u -> u.put("deleted", true));
            putUsernameMapping((String) user.get("username"), null);
            // 从所有好友的好友列表中移除自己
            List<String> friends = (List<String>) user.get("friends");
            for (String friendUid : friends) {
                updateUserData(friendUid, u -> {
                    List<String> fList = (List<String>) u.get("friends");
                    fList.remove(uid);
                    u.put("friends", fList);
                });
            }
        }
        sendResponse(exchange, 200, "Account deleted", null);
    }

    private static void getStationVersion(HttpExchange exchange) throws IOException {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("version", STATION_VERSION);
        sendResponse(exchange, 200, "Station version retrieved", data);
    }

    private static void getServerType(HttpExchange exchange) throws IOException {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", SERVER_TYPE);
        sendResponse(exchange, 200, "Server type retrieved", data);
    }

    private static void getVerifySetting(HttpExchange exchange, Map<String, Object> user) throws IOException {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("mode", (String) user.get("friend_verify"));
        sendResponse(exchange, 200, "Verify setting retrieved", data);
    }

    private static void setVerifySetting(HttpExchange exchange, Map<String, Object> user) throws IOException {
        Map<String, Object> input = parseJsonBody(exchange);
        String mode = (String) input.get("mode");
        if (!Arrays.asList("allow_all", "need_verify", "deny_all").contains(mode)) {
            sendResponse(exchange, 400, "Invalid mode", null);
            return;
        }
        updateUserData((String) user.get("id"), u -> u.put("friend_verify", mode));
        sendResponse(exchange, 200, "Verify setting updated", null);
    }

    private static void getFriendRequests(HttpExchange exchange, Map<String, Object> user) throws IOException {
        List<Map<String, Object>> pending = new ArrayList<>();
        List<Map<String, Object>> requests = (List<Map<String, Object>>) user.get("friend_requests");
        for (Map<String, Object> req : requests) {
            if ("pending".equals(req.get("status"))) {
                String fromUid = (String) req.get("from_uid");
                Map<String, Object> fromUser = getUserData(fromUid);
                if (fromUser != null) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", fromUid);
                    item.put("from_uid", fromUid);
                    item.put("from_username", fromUser.get("username"));
                    item.put("message", "申请添加您为好友");
                    item.put("time", req.get("time"));
                    pending.add(item);
                }
            }
        }
        sendResponse(exchange, 200, "Friend requests retrieved", pending);
    }

    private static void acceptFriendRequest(HttpExchange exchange, Map<String, Object> user) throws IOException {
        Map<String, Object> input = parseJsonBody(exchange);
        String requestId = (String) input.get("request_id");
        if (requestId == null || requestId.isEmpty()) {
            sendResponse(exchange, 400, "Missing request_id", null);
            return;
        }

        final boolean[] found = {false};
        updateUserData((String) user.get("id"), u -> {
            List<Map<String, Object>> reqs = (List<Map<String, Object>>) u.get("friend_requests");
            for (Map<String, Object> req : reqs) {
                if (requestId.equals(req.get("from_uid")) && "pending".equals(req.get("status"))) {
                    req.put("status", "accepted");
                    found[0] = true;
                    break;
                }
            }
        });
        if (!found[0]) {
            sendResponse(exchange, 404, "Request not found", null);
            return;
        }

        Map<String, Object> targetUser = getUserData(requestId);
        if (targetUser == null || Boolean.TRUE.equals(targetUser.get("deleted"))) {
            sendResponse(exchange, 404, "Target user no longer exists", null);
            return;
        }

        updateUserData((String) user.get("id"), u -> {
            if (!((List<String>) u.get("friends")).contains(requestId)) {
                ((List<String>) u.get("friends")).add(requestId);
            }
        });
        updateUserData(requestId, u -> {
            if (!((List<String>) u.get("friends")).contains(user.get("id"))) {
                ((List<String>) u.get("friends")).add((String) user.get("id"));
            }
        });
        // 清理已处理的好友请求记录
        updateUserData((String) user.get("id"), u -> {
            List<Map<String, Object>> reqs = (List<Map<String, Object>>) u.get("friend_requests");
            reqs.removeIf(req -> requestId.equals(req.get("from_uid")) && "accepted".equals(req.get("status")));
        });
        sendResponse(exchange, 200, "Friend request accepted", null);
    }

    private static void getFriends(HttpExchange exchange, Map<String, Object> user) throws IOException {
        List<Map<String, Object>> friendsList = new ArrayList<>();
        List<String> friendIds = (List<String>) user.get("friends");
        for (String fid : friendIds) {
            Map<String, Object> friend = getUserData(fid);
            if (friend != null && !Boolean.TRUE.equals(friend.get("deleted"))) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("uid", friend.get("id"));
                info.put("username", friend.get("username"));
                info.put("avatar", friend.get("avatar"));
                info.put("registered_at", friend.get("registered_at"));
                info.put("station_id", friend.get("station_id"));
                friendsList.add(info);
            }
        }
        sendResponse(exchange, 200, "Friends list retrieved", friendsList);
    }

    private static void getUserInfo(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQueryParams(exchange.getRequestURI().getQuery());
        String uid = params.get("uid");
        if (uid == null || uid.isEmpty()) {
            Map<String, Object> body = parseJsonBody(exchange);
            uid = (String) body.get("uid");
        }
        if (uid == null || uid.isEmpty()) {
            sendResponse(exchange, 400, "Missing user ID or username", null);
            return;
        }

        Map<String, Object> user = getUserData(uid);
        if (user == null) {
            String uidByName = getUidByUsername(uid);
            if (uidByName != null) user = getUserData(uidByName);
        }
        if (user == null || Boolean.TRUE.equals(user.get("deleted"))) {
            sendResponse(exchange, 404, "User not found", null);
            return;
        }

        Map<String, Object> publicInfo = new LinkedHashMap<>();
        publicInfo.put("uid", user.get("id"));
        publicInfo.put("username", user.get("username"));
        publicInfo.put("avatar", user.get("avatar"));
        publicInfo.put("registered_at", user.get("registered_at"));
        publicInfo.put("station_id", user.get("station_id"));
        publicInfo.put("friend_verify", user.get("friend_verify"));
        Number msgCount = (Number) user.getOrDefault("message_count", 0);
        publicInfo.put("message_count", msgCount.intValue());
        sendResponse(exchange, 200, "User info retrieved", publicInfo);
    }

    private static void getStationId(HttpExchange exchange) throws IOException {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("station_id", stationID);
        sendResponse(exchange, 200, "Station ID retrieved", data);
    }

    // ==================== 简易JSON工具 ====================
    static class Json {
        @SuppressWarnings("unchecked")
        static Map<String, Object> parseMap(String json) {
            return (Map<String, Object>) parse(json);
        }

        static Object parse(String json) {
            return new JSONParser().parse(json);
        }

        static String stringify(Object obj) {
            return JSONStringifier.stringify(obj);
        }
    }

    // 极简JSON解析器（支持Map、List、String、Number、Boolean）
    static class JSONParser {
        private int pos = 0;
        private String src;

        Object parse(String s) {
            this.src = s;
            this.pos = 0;
            return parseValue();
        }

        private Object parseValue() {
            skipWhitespace();
            if (pos >= src.length()) return null;
            char c = src.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') return parseNull();
            return parseNumber();
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (src.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                if (src.charAt(pos) != ':') throw new RuntimeException("Expected ':'");
                pos++;
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char c = src.charAt(pos);
                if (c == '}') {
                    pos++;
                    break;
                }
                if (c != ',') throw new RuntimeException("Expected ',' or '}'");
                pos++;
            }
            return map;
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++;
            skipWhitespace();
            if (src.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char c = src.charAt(pos);
                if (c == ']') {
                    pos++;
                    break;
                }
                if (c != ',') throw new RuntimeException("Expected ',' or ']'");
                pos++;
                skipWhitespace();
            }
            return list;
        }

        private String parseString() {
            pos++; // "
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '"') {
                    pos++;
                    return sb.toString();
                }
                if (c == '\\') {
                    pos++;
                    char esc = src.charAt(pos);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        default: sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
                pos++;
            }
            throw new RuntimeException("Unterminated string");
        }

        private Number parseNumber() {
            int start = pos;
            if (src.charAt(pos) == '-') pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            if (pos < src.length() && src.charAt(pos) == '.') {
                pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            }
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                pos++;
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            }
            String numStr = src.substring(start, pos);
            if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
                return Double.parseDouble(numStr);
            }
            return Long.parseLong(numStr);
        }

        private Boolean parseBoolean() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return true;
            }
            if (src.startsWith("false", pos)) {
                pos += 5;
                return false;
            }
            throw new RuntimeException("Expected boolean");
        }

        private Object parseNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new RuntimeException("Expected null");
        }

        private void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }
    }

    static class JSONStringifier {
        static String stringify(Object obj) {
            if (obj == null) return "null";
            if (obj instanceof String) return "\"" + escape((String) obj) + "\"";
            if (obj instanceof Number) return obj.toString();
            if (obj instanceof Boolean) return obj.toString();
            if (obj instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) obj;
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!first) sb.append(",");
                    sb.append("\"").append(escape(String.valueOf(e.getKey()))).append("\":");
                    sb.append(stringify(e.getValue()));
                    first = false;
                }
                sb.append("}");
                return sb.toString();
            }
            if (obj instanceof List) {
                List<?> list = (List<?>) obj;
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (Object item : list) {
                    if (!first) sb.append(",");
                    sb.append(stringify(item));
                    first = false;
                }
                sb.append("]");
                return sb.toString();
            }
            return "\"" + escape(obj.toString()) + "\"";
        }

        private static String escape(String s) {
            StringBuilder sb = new StringBuilder();
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default: sb.append(c);
                }
            }
            return sb.toString();
        }
    }
}