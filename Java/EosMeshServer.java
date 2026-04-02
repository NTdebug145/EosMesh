import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
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
    private static final String STATION_VERSION = "b26.4.2";
    private static final String SERVER_TYPE = "java";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static String ROOT_DIR;
    private static String DATA_DIR;
    private static String USER_DIR;
    private static String CHAT_DIR;
    private static String AVATAR_DIR;
    private static String CONFIG_FILE;
    private static String USER_INDEX_FILE;

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
        USER_INDEX_FILE = DATA_DIR + File.separator + "user_index.json";

        createDirIfNotExists(DATA_DIR);
        createDirIfNotExists(USER_DIR);
        createDirIfNotExists(CHAT_DIR);
        createDirIfNotExists(AVATAR_DIR);

        // 初始化配置
        initConfig();

        // 创建HTTP服务器，随机端口
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

        // 初始化用户索引文件
        File idxFile = new File(USER_INDEX_FILE);
        if (!idxFile.exists()) {
            Files.write(Paths.get(USER_INDEX_FILE), "{}".getBytes(StandardCharsets.UTF_8));
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

    // ==================== 用户数据操作 ====================
    private static synchronized Map<String, Integer> readUserIndex() throws IOException {
        String content = new String(Files.readAllBytes(Paths.get(USER_INDEX_FILE)), StandardCharsets.UTF_8);
        Map<String, Object> raw = Json.parseMap(content);
        Map<String, Integer> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            // JSON 数字可能被解析为 Long 或 Integer，统一转为 Integer
            Number num = (Number) entry.getValue();
            result.put(entry.getKey(), num.intValue());
        }
        return result;
    }

    private static synchronized void writeUserIndex(Map<String, Integer> index) throws IOException {
        Files.write(Paths.get(USER_INDEX_FILE), Json.stringify(index).getBytes(StandardCharsets.UTF_8));
    }

    private static File getUserFileByUid(String uid) throws IOException {
        Map<String, Integer> index = readUserIndex();
        Integer fileNum = index.get(uid);
        if (fileNum == null) return null;
        return new File(USER_DIR, "user_" + fileNum + ".json");
    }

    private static Map<String, Object> getUserData(String uid) throws IOException {
        File userFile = getUserFileByUid(uid);
        if (userFile == null || !userFile.exists()) return null;
        List<String> lines = Files.readAllLines(userFile.toPath(), StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            Map<String, Object> user = Json.parseMap(line);
            if (uid.equals(user.get("id")) && !Boolean.TRUE.equals(user.get("deleted"))) {
                return user;
            }
        }
        return null;
    }

    private static void saveUserData(Map<String, Object> user) throws IOException {
        String uid = (String) user.get("id");
        Map<String, Integer> index = readUserIndex();
        Integer fileNum = index.get(uid);
        if (fileNum == null) {
            // 分配文件
            File[] files = new File(USER_DIR).listFiles((dir, name) -> name.matches("user_\\d+\\.json"));
            int maxNum = 0;
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    int num = Integer.parseInt(name.substring(5, name.lastIndexOf('.')));
                    maxNum = Math.max(maxNum, num);
                }
            }
            int targetNum = -1;
            for (int i = 1; i <= maxNum + 1; i++) {
                File f = new File(USER_DIR, "user_" + i + ".json");
                if (!f.exists()) {
                    targetNum = i;
                    break;
                }
                List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
                if (lines.size() < 50) {
                    targetNum = i;
                    break;
                }
            }
            if (targetNum == -1) targetNum = maxNum + 1;
            index.put(uid, targetNum);
            writeUserIndex(index);
            fileNum = targetNum;
        }

        File targetFile = new File(USER_DIR, "user_" + fileNum + ".json");
        List<String> lines = targetFile.exists() ? Files.readAllLines(targetFile.toPath(), StandardCharsets.UTF_8) : new ArrayList<>();
        List<String> newLines = new ArrayList<>();
        boolean found = false;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            Map<String, Object> existing = Json.parseMap(line);
            if (uid.equals(existing.get("id"))) {
                newLines.add(Json.stringify(user));
                found = true;
            } else {
                newLines.add(line);
            }
        }
        if (!found) {
            newLines.add(Json.stringify(user));
        }
        Files.write(targetFile.toPath(), newLines, StandardCharsets.UTF_8);
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
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                messages.add(Json.parseMap(line));
            }
        }
        messages.add(message);
        long cutoff = Instant.now().getEpochSecond() - (daysToKeep * 86400L);
        messages = messages.stream()
                .filter(m -> ((Number) m.get("time")).longValue() >= cutoff)
                .collect(Collectors.toList());
        // 写回
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filePath), StandardCharsets.UTF_8))) {
            for (Map<String, Object> msg : messages) {
                writer.write(Json.stringify(msg));
                writer.newLine();
            }
        }
    }

    // ==================== API Handlers ====================
    static class ApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // 处理CORS预检
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

        // 检查用户名唯一性
        File[] userFiles = new File(USER_DIR).listFiles((dir, name) -> name.startsWith("user_") && name.endsWith(".json"));
        if (userFiles != null) {
            for (File f : userFiles) {
                List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
                for (String line : lines) {
                    if (line.trim().isEmpty()) continue;
                    Map<String, Object> u = Json.parseMap(line);
                    if (username.equals(u.get("username")) && !Boolean.TRUE.equals(u.get("deleted"))) {
                        sendResponse(exchange, 409, "Username already exists", null);
                        return;
                    }
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
        String token = uid + ":" + hmacSha256(uid + passwordHash, stationID);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("uid", uid);
        data.put("token", token);
        sendResponse(exchange, 200, "Registered successfully", data);
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

        File[] userFiles = new File(USER_DIR).listFiles((dir, name) -> name.startsWith("user_") && name.endsWith(".json"));
        Map<String, Object> foundUser = null;
        if (userFiles != null) {
            outer:
            for (File f : userFiles) {
                List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
                for (String line : lines) {
                    if (line.trim().isEmpty()) continue;
                    Map<String, Object> u = Json.parseMap(line);
                    if (username.equals(u.get("username")) && !Boolean.TRUE.equals(u.get("deleted"))) {
                        foundUser = u;
                        break outer;
                    }
                }
            }
        }

        if (foundUser == null || !verifyPassword(password, (String) foundUser.get("password"))) {
            sendResponse(exchange, 401, "Invalid username or password", null);
            return;
        }

        String token = (String) foundUser.get("id") + ":" + hmacSha256(foundUser.get("id") + (String) foundUser.get("password"), stationID);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("uid", (String) foundUser.get("id"));
        data.put("token", token);
        sendResponse(exchange, 200, "Login successful", data);
    }

    // 上传头像
    private static void uploadAvatar(HttpExchange exchange, Map<String, Object> user) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.startsWith("multipart/form-data")) {
            sendResponse(exchange, 400, "Avatar file required", null);
            return;
        }

        String boundary = "--" + contentType.substring(contentType.indexOf("boundary=") + 9);
        byte[] body = exchange.getRequestBody().readAllBytes();
        String bodyStr = new String(body, StandardCharsets.ISO_8859_1);
        // 简单解析文件部分
        int start = bodyStr.indexOf("\r\n\r\n", bodyStr.indexOf("filename="));
        if (start == -1) {
            sendResponse(exchange, 400, "Invalid file data", null);
            return;
        }
        start += 4;
        int end = bodyStr.indexOf(boundary, start);
        if (end == -1) end = bodyStr.length();
        byte[] fileData = Arrays.copyOfRange(body, start, end);
        // 去除末尾的\r\n
        int trimLen = fileData.length;
        while (trimLen > 0 && (fileData[trimLen-1] == '\r' || fileData[trimLen-1] == '\n')) trimLen--;
        fileData = Arrays.copyOf(fileData, trimLen);

        String ext = "png"; // 默认
        // 简单根据前几个字节判断类型
        if (fileData.length > 4) {
            if (fileData[0] == (byte)0xFF && fileData[1] == (byte)0xD8) {
                ext = "jpg";
            } else if (fileData[0] == 'G' && fileData[1] == 'I' && fileData[2] == 'F') {
                ext = "gif";
            } else if (fileData[0] == (byte)0x89 && fileData[1] == 'P' && fileData[2] == 'N' && fileData[3] == 'G') {
                ext = "png";
            }
        }
        if (fileData.length > 2 * 1024 * 1024) {
            sendResponse(exchange, 400, "File too large (max 2MB)", null);
            return;
        }
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
    private static void addFriend(HttpExchange exchange, Map<String, Object> user) throws IOException {
        Map<String, Object> input = parseJsonBody(exchange);
        String targetUsername = ((String) input.getOrDefault("username", "")).trim();
        if (targetUsername.isEmpty()) {
            sendResponse(exchange, 400, "Target username required", null);
            return;
        }

        Map<String, Object> targetUser = findUserByUsernameOrId(targetUsername);
        if (targetUser == null) {
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
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("from_uid", user.get("id"));
            request.put("time", Instant.now().getEpochSecond());
            request.put("status", "pending");
            updateUserData((String) targetUser.get("id"), u -> {
                List<Map<String, Object>> reqs = (List<Map<String, Object>>) u.get("friend_requests");
                for (Map<String, Object> r : reqs) {
                    if (user.get("id").equals(r.get("from_uid")) && "pending".equals(r.get("status"))) {
                        return;
                    }
                }
                reqs.add(request);
                u.put("friend_requests", reqs);
            });
            sendResponse(exchange, 200, "Friend request sent", null);
        } else { // allow_all
            updateUserData((String) user.get("id"), u -> {
                ((List<String>) u.get("friends")).add((String) targetUser.get("id"));
            });
            updateUserData((String) targetUser.get("id"), u -> {
                ((List<String>) u.get("friends")).add((String) user.get("id"));
            });
            sendResponse(exchange, 200, "Friend added", null);
        }
    }

    private static Map<String, Object> findUserByUsernameOrId(String usernameOrId) throws IOException {
        File[] files = new File(USER_DIR).listFiles((dir, name) -> name.startsWith("user_") && name.endsWith(".json"));
        if (files == null) return null;
        for (File f : files) {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                Map<String, Object> u = Json.parseMap(line);
                if (!Boolean.TRUE.equals(u.get("deleted"))) {
                    if (usernameOrId.equals(u.get("username")) || usernameOrId.equals(u.get("id"))) {
                        return u;
                    }
                }
            }
        }
        return null;
    }

    // 处理好友申请（兼容旧handle_friend_request）
    private static void handleFriendRequest(HttpExchange exchange, Map<String, Object> user) throws IOException {
        Map<String, Object> input = parseJsonBody(exchange);
        String fromUid = (String) input.get("from_uid");
        String action = (String) input.get("action");
        if (fromUid == null || (!"accept".equals(action) && !"reject".equals(action))) {
            sendResponse(exchange, 400, "Invalid parameters", null);
            return;
        }
        // 查找并更新请求状态
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
        if (updated[0] && "accept".equals(action)) {
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
        } else if (updated[0]) {
            sendResponse(exchange, 200, "Friend request rejected", null);
        } else {
            sendResponse(exchange, 404, "Request not found", null);
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

    // 获取消息记录
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
        File file = new File(chatFile);
        if (!file.exists()) {
            sendResponse(exchange, 200, "No messages", new ArrayList<>());
            return;
        }
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        List<Map<String, Object>> messages = new ArrayList<>();
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            messages.add(Json.parseMap(line));
        }
        messages.sort(Comparator.comparingLong(m -> ((Number) m.get("time")).longValue()));
        sendResponse(exchange, 200, "Messages retrieved", messages);
    }

    // 删除账号
    private static void deleteAccount(HttpExchange exchange, Map<String, Object> user) throws IOException {
        if (completelyDelete) {
            File userFile = getUserFileByUid((String) user.get("id"));
            if (userFile != null && userFile.exists()) {
                List<String> lines = Files.readAllLines(userFile.toPath(), StandardCharsets.UTF_8);
                List<String> newLines = new ArrayList<>();
                for (String line : lines) {
                    if (line.trim().isEmpty()) continue;
                    Map<String, Object> u = Json.parseMap(line);
                    if (!user.get("id").equals(u.get("id"))) {
                        newLines.add(line);
                    }
                }
                Files.write(userFile.toPath(), newLines, StandardCharsets.UTF_8);
            }
            Map<String, Integer> index = readUserIndex();
            index.remove(user.get("id"));
            writeUserIndex(index);
            // 删除头像
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(AVATAR_DIR), (String) user.get("id") + ".*")) {
                for (Path p : stream) Files.deleteIfExists(p);
            } catch (Exception ignored) {}
            // 删除聊天记录
            Path chatUserDir = Paths.get(CHAT_DIR + File.separator + user.get("id"));
            if (Files.exists(chatUserDir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(chatUserDir)) {
                    for (Path p : stream) Files.deleteIfExists(p);
                }
                Files.deleteIfExists(chatUserDir);
            }
        } else {
            updateUserData((String) user.get("id"), u -> u.put("deleted", true));
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
        sendResponse(exchange, 200, "Friend request accepted", null);
    }

    private static void getFriends(HttpExchange exchange, Map<String, Object> user) throws IOException {
        List<Map<String, Object>> friendsList = new ArrayList<>();
        List<String> friendIds = (List<String>) user.get("friends");
        for (String fid : friendIds) {
            Map<String, Object> friend = getUserData(fid);
            if (friend != null) {
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
        if (uid == null) {
            Map<String, Object> body = parseJsonBody(exchange);
            uid = (String) body.get("uid");
        }
        if (uid == null || uid.isEmpty()) {
            sendResponse(exchange, 400, "Missing user ID", null);
            return;
        }
        Map<String, Object> user = null;
        File[] files = new File(USER_DIR).listFiles((dir, name) -> name.startsWith("user_") && name.endsWith(".json"));
        if (files != null) {
            outer:
            for (File f : files) {
                List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
                for (String line : lines) {
                    if (line.trim().isEmpty()) continue;
                    Map<String, Object> u = Json.parseMap(line);
                    if (uid.equals(u.get("id")) && !Boolean.TRUE.equals(u.get("deleted"))) {
                        user = u;
                        break outer;
                    }
                }
            }
        }
        if (user == null) {
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