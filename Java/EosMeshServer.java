import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.mindrot.jbcrypt.BCrypt;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class EosMeshServer {
    private static final String STATION_VERSION = "b26.4.17";
    private static final String SERVER_TYPE = "java";
    private static final String ROOT_DIR = System.getProperty("user.dir");
    private static final String DATA_DIR = ROOT_DIR + File.separator + "data";
    private static final String USER_DIR = DATA_DIR + File.separator + "user";
    private static final String CHAT_DIR = DATA_DIR + File.separator + "chat" + File.separator + "friend";
    private static final String AVATAR_DIR = DATA_DIR + File.separator + "avatar";
    private static final String CONFIG_FILE = ROOT_DIR + File.separator + "station.ini";
    private static final int MAX_MESSAGE_LEN = 1500;
    private static final long MAX_AVATAR_SIZE = 2 * 1024 * 1024;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static String stationID;
    private static int daysToKeep;
    private static boolean completelyDelete;
    private static final Path USERNAME_MAP_FILE = Paths.get(DATA_DIR, "username_map.json");

    public static void main(String[] args) throws IOException {
        // 创建必要目录
        for (String dir : Arrays.asList(DATA_DIR, USER_DIR, CHAT_DIR, AVATAR_DIR)) {
            Files.createDirectories(Paths.get(dir));
        }

        // 初始化配置文件
        initConfig();

        // 初始化用户名映射文件
        if (!Files.exists(USERNAME_MAP_FILE)) {
            Files.write(USERNAME_MAP_FILE, "{}".getBytes(StandardCharsets.UTF_8));
        }

        // 随机端口
        int port = findRandomPort();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new ApiHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("EosMesh server started on port " + port);
        System.out.println("Station ID: " + stationID);
    }

    private static int findRandomPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void initConfig() throws IOException {
        Path configPath = Paths.get(CONFIG_FILE);
        if (!Files.exists(configPath)) {
            String randomID = generateRandomString(16);
            String content = "[station]\nstationID=" + randomID + "\nstationNumberDaysInformationStored=3\nstationWhetherCompletelyDeleteUserData=true\n";
            Files.write(configPath, content.getBytes(StandardCharsets.UTF_8));
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(configPath)) {
            // 简单处理 INI 节
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = ((BufferedReader) reader).readLine()) != null) {
                if (line.startsWith("[") || line.trim().isEmpty()) continue;
                sb.append(line).append("\n");
            }
            props.load(new StringReader(sb.toString()));
        }
        stationID = props.getProperty("stationID");
        daysToKeep = Integer.parseInt(props.getProperty("stationNumberDaysInformationStored", "3"));
        completelyDelete = Boolean.parseBoolean(props.getProperty("stationWhetherCompletelyDeleteUserData", "true"));
    }

    private static String generateRandomString(int length) {
        String chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        Random random = new Random();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    // ---------------------- 工具方法 ----------------------
    private static UserData getUserData(String uid) throws IOException {
        Path file = Paths.get(USER_DIR, uid + ".json");
        if (!Files.exists(file)) return null;
        UserData user = objectMapper.readValue(file.toFile(), UserData.class);
        if (user != null && !user.deleted) return user;
        return null;
    }

    private static void saveUserData(UserData user) throws IOException {
        Path file = Paths.get(USER_DIR, user.id + ".json");
        objectMapper.writeValue(file.toFile(), user);
    }

    private static void updateUserData(String uid, UserUpdater updater) throws IOException {
        UserData user = getUserData(uid);
        if (user == null) return;
        user = updater.update(user);
        saveUserData(user);
    }

    private static Map<String, String> getUsernameMap() throws IOException {
        if (!Files.exists(USERNAME_MAP_FILE)) return new HashMap<>();
        byte[] bytes = Files.readAllBytes(USERNAME_MAP_FILE);
        return objectMapper.readValue(bytes, Map.class);
    }

    private static void updateUsernameMap(String username, String uid) throws IOException {
        Map<String, String> map = getUsernameMap();
        if (uid == null) {
            map.remove(username);
        } else {
            map.put(username, uid);
        }
        objectMapper.writeValue(USERNAME_MAP_FILE.toFile(), map);
    }

    private static String getUidByUsername(String username) throws IOException {
        Map<String, String> map = getUsernameMap();
        return map.get(username);
    }

    private static UserData authenticate(HttpExchange exchange) throws IOException {
        String token = null;
        // 从 Authorization 头获取
        List<String> authHeaders = exchange.getRequestHeaders().get("Authorization");
        if (authHeaders != null && !authHeaders.isEmpty()) {
            token = authHeaders.get(0);
        }
        if (token == null) {
            // 从查询参数获取
            String query = exchange.getRequestURI().getQuery();
            if (query != null) {
                Map<String, String> params = parseQueryParams(query);
                token = params.get("token");
            }
        }
        if (token == null) {
            sendResponse(exchange, 401, "Missing token", null);
            return null;
        }
        String[] parts = token.split(":");
        if (parts.length != 2) {
            sendResponse(exchange, 401, "Invalid token", null);
            return null;
        }
        String uid = parts[0];
        String signature = parts[1];
        UserData user = getUserData(uid);
        if (user == null) {
            sendResponse(exchange, 401, "User not found", null);
            return null;
        }
        String expected = hmacSha256(uid + user.password, stationID);
        if (!expected.equals(signature)) {
            sendResponse(exchange, 401, "Invalid token", null);
            return null;
        }
        return user;
    }

    private static String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec spec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(spec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException(e);
        }
    }

    private static void sendResponse(HttpExchange exchange, int code, String message, Object data) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", code);
        resp.put("msg", message);
        resp.put("data", data);
        byte[] bytes = objectMapper.writeValueAsBytes(resp);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null) return params;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf("=");
            if (idx > 0) {
                params.put(pair.substring(0, idx), pair.substring(idx + 1));
            }
        }
        return params;
    }

    // ---------------------- 消息文件操作 ----------------------
    private static void appendMessageToFile(Path filePath, Message msg) throws IOException {
        List<Message> messages = new ArrayList<>();
        if (Files.exists(filePath)) {
            try (Stream<String> lines = Files.lines(filePath)) {
                messages = lines.map(line -> {
                    try {
                        return objectMapper.readValue(line, Message.class);
                    } catch (IOException e) {
                        return null;
                    }
                }).filter(Objects::nonNull).collect(Collectors.toList());
            }
        }
        messages.add(msg);
        long cutoff = Instant.now().getEpochSecond() - (daysToKeep * 86400L);
        messages.removeIf(m -> m.time < cutoff);
        // 写入文件，每行一条JSON
        List<String> lines = messages.stream().map(m -> {
            try {
                return objectMapper.writeValueAsString(m);
            } catch (IOException e) {
                return "";
            }
        }).collect(Collectors.toList());
        Files.write(filePath, lines, StandardCharsets.UTF_8);
    }

    // ---------------------- API 处理方法 ----------------------
    static class ApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // CORS 预检
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQueryParams(query);
            String action = params.getOrDefault("action", "");

            try {
                switch (action) {
                    case "register":
                        register(exchange);
                        break;
                    case "login":
                        login(exchange);
                        break;
                    case "upload_avatar":
                        uploadAvatar(exchange);
                        break;
                    case "add_friend":
                        addFriend(exchange);
                        break;
                    case "handle_friend_request":
                        handleFriendRequest(exchange);
                        break;
                    case "send_message":
                        sendMessage(exchange);
                        break;
                    case "get_messages":
                        getMessages(exchange);
                        break;
                    case "delete_account":
                        deleteAccount(exchange);
                        break;
                    case "get_station_version":
                        getStationVersion(exchange);
                        break;
                    case "get_server_type":
                        getServerType(exchange);
                        break;
                    case "get_verify_setting":
                        getVerifySetting(exchange);
                        break;
                    case "set_verify_setting":
                        setVerifySetting(exchange);
                        break;
                    case "get_friend_requests":
                        getFriendRequests(exchange);
                        break;
                    case "accept_friend_request":
                        acceptFriendRequest(exchange);
                        break;
                    case "get_avatar":
                        getAvatar(exchange);
                        break;
                    case "get_friends":
                        getFriends(exchange);
                        break;
                    case "get_user_info":
                        getUserInfo(exchange);
                        break;
                    case "get_station_id":
                        sendResponse(exchange, 200, "Station ID retrieved", Map.of("station_id", stationID));
                        break;
                    default:
                        sendResponse(exchange, 400, "Invalid action", null);
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "Server error: " + e.getMessage(), null);
            }
        }

        private void register(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed", null);
                return;
            }
            JsonNode input = objectMapper.readTree(exchange.getRequestBody());
            String username = input.has("username") ? input.get("username").asText().trim() : "";
            String password = input.has("password") ? input.get("password").asText() : "";
            if (username.isEmpty() || password.isEmpty()) {
                sendResponse(exchange, 400, "Username and password required", null);
                return;
            }
            if (username.length() > 12) {
                sendResponse(exchange, 400, "Username too long (max 12)", null);
                return;
            }
            // 检查用户名唯一性
            Map<String, String> map = getUsernameMap();
            if (map.containsKey(username)) {
                sendResponse(exchange, 409, "Username already exists", null);
                return;
            }
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(Paths.get(USER_DIR), "*.json")) {
                for (Path file : ds) {
                    UserData u = objectMapper.readValue(file.toFile(), UserData.class);
                    if (u != null && u.username.equals(username) && !u.deleted) {
                        sendResponse(exchange, 409, "Username already exists", null);
                        return;
                    }
                }
            }

            String uid = generateRandomString(32);
            String passwordHash = BCrypt.hashpw(password, BCrypt.gensalt());
            long now = Instant.now().getEpochSecond();
            UserData user = new UserData();
            user.id = uid;
            user.username = username;
            user.password = passwordHash;
            user.friendVerify = "need_verify";
            user.registeredAt = now;
            user.stationId = stationID;
            user.friendRequests = new ArrayList<>();
            user.friends = new ArrayList<>();
            user.messageCount = 0;
            user.avatar = null;
            user.deleted = false;

            saveUserData(user);
            updateUsernameMap(username, uid);

            String token = uid + ":" + hmacSha256(uid + passwordHash, stationID);
            sendResponse(exchange, 200, "Registered successfully", Map.of("uid", uid, "token", token));
        }

        private void login(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed", null);
                return;
            }
            JsonNode input = objectMapper.readTree(exchange.getRequestBody());
            String username = input.has("username") ? input.get("username").asText().trim() : "";
            String password = input.has("password") ? input.get("password").asText() : "";
            if (username.isEmpty() || password.isEmpty()) {
                sendResponse(exchange, 400, "Username and password required", null);
                return;
            }
            String uid = getUidByUsername(username);
            if (uid == null) {
                sendResponse(exchange, 401, "Invalid username or password", null);
                return;
            }
            UserData user = getUserData(uid);
            if (user == null || !BCrypt.checkpw(password, user.password)) {
                sendResponse(exchange, 401, "Invalid username or password", null);
                return;
            }
            String token = user.id + ":" + hmacSha256(user.id + user.password, stationID);
            sendResponse(exchange, 200, "Login successful", Map.of("uid", user.id, "token", token));
        }

        private void uploadAvatar(HttpExchange exchange) throws IOException {
            UserData user = authenticate(exchange);
            if (user == null) return;

            // 解析 multipart
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.startsWith("multipart/form-data")) {
                sendResponse(exchange, 400, "Invalid content type", null);
                return;
            }
            String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);
            // 简单解析，仅支持一个文件字段 "avatar"
            InputStream is = exchange.getRequestBody();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            byte[] body = baos.toByteArray();
            String bodyStr = new String(body, StandardCharsets.ISO_8859_1);
            String[] parts = bodyStr.split("--" + boundary);
            for (String part : parts) {
                if (part.contains("Content-Disposition: form-data; name=\"avatar\"")) {
                    int headerEnd = part.indexOf("\r\n\r\n");
                    if (headerEnd == -1) continue;
                    String content = part.substring(headerEnd + 4);
                    // 去除结尾的 \r\n
                    content = content.substring(0, content.lastIndexOf('\r') >= 0 ? content.lastIndexOf('\r') : content.length());
                    byte[] fileData = content.getBytes(StandardCharsets.ISO_8859_1);
                    if (fileData.length > MAX_AVATAR_SIZE) {
                        sendResponse(exchange, 400, "File too large (max 2MB)", null);
                        return;
                    }
                    // 检测 MIME 类型（简单通过文件头）
                    String mime = detectMimeType(fileData);
                    if (!Arrays.asList("image/jpeg", "image/png", "image/gif").contains(mime)) {
                        sendResponse(exchange, 400, "Invalid image type", null);
                        return;
                    }
                    String ext = "";
                    if ("image/jpeg".equals(mime)) ext = "jpg";
                    else if ("image/png".equals(mime)) ext = "png";
                    else if ("image/gif".equals(mime)) ext = "gif";

                    // 删除旧头像
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(Paths.get(AVATAR_DIR), user.id + ".*")) {
                        for (Path p : ds) Files.delete(p);
                    } catch (IOException ignored) {}
                    Path avatarPath = Paths.get(AVATAR_DIR, user.id + "." + ext);
                    Files.write(avatarPath, fileData);
                    updateUserData(user.id, u -> {
                        u.avatar = avatarPath.toString();
                        return u;
                    });
                    sendResponse(exchange, 200, "Avatar uploaded", null);
                    return;
                }
            }
            sendResponse(exchange, 400, "Avatar file required", null);
        }

        private String detectMimeType(byte[] data) {
            if (data.length > 4) {
                if (data[0] == (byte)0xFF && data[1] == (byte)0xD8) return "image/jpeg";
                if (data[0] == (byte)0x89 && data[1] == (byte)0x50 && data[2] == (byte)0x4E && data[3] == (byte)0x47) return "image/png";
                if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F') return "image/gif";
            }
            return "application/octet-stream";
        }

        private void addFriend(HttpExchange exchange) throws IOException {
    UserData user = authenticate(exchange);
    if (user == null) return;
    JsonNode input = objectMapper.readTree(exchange.getRequestBody());
    String targetName = input.has("username") ? input.get("username").asText().trim() : "";
    if (targetName.isEmpty()) {
        sendResponse(exchange, 400, "Target username or UID required", null);
        return;
    }
    UserData targetUser = getUserData(targetName);
    if (targetUser == null) {
        String targetUid = getUidByUsername(targetName);
        if (targetUid != null) targetUser = getUserData(targetUid);
    }
    if (targetUser == null || targetUser.deleted) {
        sendResponse(exchange, 404, "User not found", null);
        return;
    }
    // 提取 final 变量供 lambda 使用
    final String targetId = targetUser.id;
    final String currentUserId = user.id;

    if (targetId.equals(currentUserId)) {
        sendResponse(exchange, 400, "Cannot add yourself", null);
        return;
    }
    if (user.friends.contains(targetId)) {
        sendResponse(exchange, 400, "Already friends", null);
        return;
    }

    String verify = targetUser.friendVerify;
    if ("deny_all".equals(verify)) {
        sendResponse(exchange, 403, "User does not accept friend requests", null);
        return;
    } else if ("need_verify".equals(verify)) {
        boolean hasPending = targetUser.friendRequests.stream()
                .anyMatch(r -> r.fromUid.equals(currentUserId) && "pending".equals(r.status));
        if (hasPending) {
            sendResponse(exchange, 409, "Friend request already sent", null);
            return;
        }
        FriendRequest req = new FriendRequest();
        req.fromUid = currentUserId;
        req.time = Instant.now().getEpochSecond();
        req.status = "pending";
        updateUserData(targetId, u -> {
            u.friendRequests.add(req);
            return u;
        });
        sendResponse(exchange, 200, "Friend request sent", null);
    } else { // allow_all
        updateUserData(currentUserId, u -> {
            if (!u.friends.contains(targetId)) u.friends.add(targetId);
            return u;
        });
        updateUserData(targetId, u -> {
            if (!u.friends.contains(currentUserId)) u.friends.add(currentUserId);
            return u;
        });
        sendResponse(exchange, 200, "Friend added", null);
    }
}

        private void handleFriendRequest(HttpExchange exchange) throws IOException {
            UserData user = authenticate(exchange);
            if (user == null) return;
            JsonNode input = objectMapper.readTree(exchange.getRequestBody());
            String fromUid = input.has("from_uid") ? input.get("from_uid").asText() : "";
            String action = input.has("action") ? input.get("action").asText() : "";
            if (fromUid.isEmpty() || !Arrays.asList("accept", "reject").contains(action)) {
                sendResponse(exchange, 400, "Invalid parameters", null);
                return;
            }
            boolean[] updated = {false};
            updateUserData(user.id, u -> {
                for (FriendRequest req : u.friendRequests) {
                    if (req.fromUid.equals(fromUid) && "pending".equals(req.status)) {
                        req.status = "accept".equals(action) ? "accepted" : "rejected";
                        updated[0] = true;
                        break;
                    }
                }
                return u;
            });
            if (!updated[0]) {
                sendResponse(exchange, 404, "Request not found", null);
                return;
            }
            if ("accept".equals(action)) {
                UserData targetUser = getUserData(fromUid);
                if (targetUser == null || targetUser.deleted) {
                    sendResponse(exchange, 404, "Target user no longer exists", null);
                    return;
                }
                updateUserData(user.id, u -> {
                    if (!u.friends.contains(fromUid)) u.friends.add(fromUid);
                    return u;
                });
                updateUserData(fromUid, u -> {
                    if (!u.friends.contains(user.id)) u.friends.add(user.id);
                    return u;
                });
                sendResponse(exchange, 200, "Friend request accepted", null);
            } else {
                sendResponse(exchange, 200, "Friend request rejected", null);
            }
        }

        private void sendMessage(HttpExchange exchange) throws IOException {
            UserData user = authenticate(exchange);
            if (user == null) return;
            JsonNode input = objectMapper.readTree(exchange.getRequestBody());
            String toUid = input.has("to_uid") ? input.get("to_uid").asText() : "";
            String content = input.has("content") ? input.get("content").asText().trim() : "";
            if (toUid.isEmpty() || content.isEmpty()) {
                sendResponse(exchange, 400, "Missing parameters", null);
                return;
            }
            if (content.length() > MAX_MESSAGE_LEN) {
                sendResponse(exchange, 400, "Message too long (max 1500 characters)", null);
                return;
            }
            UserData targetUser = getUserData(toUid);
            if (targetUser == null || targetUser.deleted) {
                sendResponse(exchange, 404, "Target user not found", null);
                return;
            }
            if (!user.friends.contains(toUid) || !targetUser.friends.contains(user.id)) {
                sendResponse(exchange, 403, "Not friends", null);
                return;
            }

            Message msg = new Message();
            msg.from = user.id;
            msg.to = toUid;
            msg.content = content;
            msg.time = Instant.now().getEpochSecond();

            Path senderDir = Paths.get(CHAT_DIR, user.id);
            Files.createDirectories(senderDir);
            Path receiverDir = Paths.get(CHAT_DIR, toUid);
            Files.createDirectories(receiverDir);
            appendMessageToFile(senderDir.resolve(toUid + ".json"), msg);
            appendMessageToFile(receiverDir.resolve(user.id + ".json"), msg);

            updateUserData(user.id, u -> {
                u.messageCount++;
                return u;
            });
            sendResponse(exchange, 200, "Message sent", null);
        }

        private void getMessages(HttpExchange exchange) throws IOException {
            UserData user = authenticate(exchange);
            if (user == null) return;
            Map<String, String> params = parseQueryParams(exchange.getRequestURI().getQuery());
            String friendUid = params.get("friend_uid");
            if (friendUid == null || friendUid.isEmpty()) {
                sendResponse(exchange, 400, "Friend UID required", null);
                return;
            }
            if (!user.friends.contains(friendUid)) {
                sendResponse(exchange, 403, "Not friends", null);
                return;
            }

            Path chatFile = Paths.get(CHAT_DIR, user.id, friendUid + ".json");
            List<Message> messages = new ArrayList<>();
            if (Files.exists(chatFile)) {
                try (Stream<String> lines = Files.lines(chatFile)) {
                    messages = lines.map(line -> {
                        try {
                            return objectMapper.readValue(line, Message.class);
                        } catch (IOException e) {
                            return null;
                        }
                    }).filter(Objects::nonNull).sorted(Comparator.comparingLong(m -> m.time)).collect(Collectors.toList());
                }
            }

            String sinceStr = params.get("since");
            if (sinceStr != null && !sinceStr.isEmpty()) {
                long since = Long.parseLong(sinceStr);
                List<Message> result = messages.stream().filter(m -> m.time >= since).collect(Collectors.toList());
                sendResponse(exchange, 200, "Messages retrieved", result);
                return;
            }

            int page = Math.max(1, Integer.parseInt(params.getOrDefault("page", "1")));
            int limit = Math.min(100, Math.max(1, Integer.parseInt(params.getOrDefault("limit", "20"))));
            int total = messages.size();
            int offset = (page - 1) * limit;
            List<Message> paged = messages.subList(Math.min(offset, total), Math.min(offset + limit, total));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("messages", paged);
            data.put("total", total);
            data.put("page", page);
            data.put("limit", limit);
            data.put("total_pages", (int) Math.ceil((double) total / limit));
            sendResponse(exchange, 200, "Messages retrieved", data);
        }

        private void deleteAccount(HttpExchange exchange) throws IOException {
            UserData user = authenticate(exchange);
            if (user == null) return;
            if (completelyDelete) {
                Path userFile = Paths.get(USER_DIR, user.id + ".json");
                Files.deleteIfExists(userFile);
                updateUsernameMap(user.username, null);
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(Paths.get(AVATAR_DIR), user.id + ".*")) {
                    for (Path p : ds) Files.delete(p);
                } catch (IOException ignored) {}
                Path chatDir = Paths.get(CHAT_DIR, user.id);
                if (Files.exists(chatDir)) {
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(chatDir, "*.json")) {
                        for (Path p : ds) Files.delete(p);
                    }
                    Files.delete(chatDir);
                }
                for (String friendUid : user.friends) {
                    updateUserData(friendUid, u -> {
                        u.friends.remove(user.id);
                        return u;
                    });
                }
            } else {
                updateUserData(user.id, u -> {
                    u.deleted = true;
                    return u;
                });
                updateUsernameMap(user.username, null);
                for (String friendUid : user.friends) {
                    updateUserData(friendUid, u -> {
                        u.friends.remove(user.id);
                        return u;
                    });
                }
            }
            sendResponse(exchange, 200, "Account deleted", null);
        }

        private void getStationVersion(HttpExchange exchange) throws IOException {
            sendResponse(exchange, 200, "OK", Map.of("version", STATION_VERSION));
        }

        private void getServerType(HttpExchange exchange) throws IOException {
            sendResponse(exchange, 200, "OK", Map.of("type", SERVER_TYPE));
        }

        private void getVerifySetting(HttpExchange exchange) throws IOException {
            UserData user = authenticate(exchange);
            if (user == null) return;
            sendResponse(exchange, 200, "OK", Map.of("mode", user.friendVerify));
        }

        private void setVerifySetting(HttpExchange exchange) throws IOException {
            UserData user = authenticate(exchange);
            if (user == null) return;
            JsonNode input = objectMapper.readTree(exchange.getRequestBody());
            String mode = input.has("mode") ? input.get("mode").asText() : "";
            if (!Arrays.asList("allow_all", "need_verify", "deny_all").contains(mode)) {
                sendResponse(exchange, 400, "Invalid mode", null);
                return;
            }
            updateUserData(user.id, u -> {
                u.friendVerify = mode;
                return u;
            });
            sendResponse(exchange, 200, "Verify setting updated", null);
        }

        private void getFriendRequests(HttpExchange exchange) throws IOException {
            UserData user = authenticate(exchange);
            if (user == null) return;
            List<Map<String, Object>> result = new ArrayList<>();
            for (FriendRequest req : user.friendRequests) {
                if ("pending".equals(req.status)) {
                    UserData from = getUserData(req.fromUid);
                    if (from != null && !from.deleted) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", req.fromUid);
                        item.put("from_uid", req.fromUid);
                        item.put("from_username", from.username);
                        item.put("message", "申请添加您为好友");
                        item.put("time", req.time);
                        result.add(item);
                    }
                }
            }
            sendResponse(exchange, 200, "OK", result);
        }

        private void acceptFriendRequest(HttpExchange exchange) throws IOException {
            UserData user = authenticate(exchange);
            if (user == null) return;
            JsonNode input = objectMapper.readTree(exchange.getRequestBody());
            String requestId = input.has("request_id") ? input.get("request_id").asText() : "";
            if (requestId.isEmpty()) {
                sendResponse(exchange, 400, "Missing request_id", null);
                return;
            }
            boolean[] found = {false};
            updateUserData(user.id, u -> {
                for (FriendRequest req : u.friendRequests) {
                    if (req.fromUid.equals(requestId) && "pending".equals(req.status)) {
                        req.status = "accepted";
                        found[0] = true;
                        break;
                    }
                }
                return u;
            });
            if (!found[0]) {
                sendResponse(exchange, 404, "Request not found", null);
                return;
            }
            UserData target = getUserData(requestId);
            if (target == null || target.deleted) {
                sendResponse(exchange, 404, "Target user no longer exists", null);
                return;
            }
            updateUserData(user.id, u -> {
                if (!u.friends.contains(requestId)) u.friends.add(requestId);
                return u;
            });
            updateUserData(requestId, u -> {
                if (!u.friends.contains(user.id)) u.friends.add(user.id);
                return u;
            });
            // 移除已处理的请求
            updateUserData(user.id, u -> {
                u.friendRequests.removeIf(r -> r.fromUid.equals(requestId) && "accepted".equals(r.status));
                return u;
            });
            sendResponse(exchange, 200, "Friend request accepted", null);
        }

        private void getAvatar(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseQueryParams(exchange.getRequestURI().getQuery());
            String uid = params.get("uid");
            if (uid == null || uid.isEmpty()) {
                sendResponse(exchange, 400, "Missing user ID", null);
                return;
            }
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(Paths.get(AVATAR_DIR), uid + ".*")) {
                Iterator<Path> it = ds.iterator();
                if (!it.hasNext()) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                Path avatarFile = it.next();
                String mime = Files.probeContentType(avatarFile);
                if (mime == null) mime = "application/octet-stream";
                exchange.getResponseHeaders().set("Content-Type", mime);
                exchange.sendResponseHeaders(200, Files.size(avatarFile));
                try (OutputStream os = exchange.getResponseBody()) {
                    Files.copy(avatarFile, os);
                }
            }
        }

        private void getFriends(HttpExchange exchange) throws IOException {
            UserData user = authenticate(exchange);
            if (user == null) return;
            List<Map<String, Object>> friends = new ArrayList<>();
            for (String friendUid : user.friends) {
                UserData f = getUserData(friendUid);
                if (f != null && !f.deleted) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("uid", f.id);
                    item.put("username", f.username);
                    item.put("avatar", f.avatar);
                    item.put("registered_at", f.registeredAt);
                    item.put("station_id", f.stationId);
                    friends.add(item);
                }
            }
            sendResponse(exchange, 200, "Friends list retrieved", friends);
        }

        private void getUserInfo(HttpExchange exchange) throws IOException {
            String uidOrName = null;
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                JsonNode input = objectMapper.readTree(exchange.getRequestBody());
                uidOrName = input.has("uid") ? input.get("uid").asText() : null;
            } else {
                Map<String, String> params = parseQueryParams(exchange.getRequestURI().getQuery());
                uidOrName = params.get("uid");
            }
            if (uidOrName == null || uidOrName.isEmpty()) {
                sendResponse(exchange, 400, "Missing user ID or username", null);
                return;
            }
            UserData user = getUserData(uidOrName);
            if (user == null) {
                String uid = getUidByUsername(uidOrName);
                if (uid != null) user = getUserData(uid);
            }
            if (user == null || user.deleted) {
                sendResponse(exchange, 404, "User not found", null);
                return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("uid", user.id);
            data.put("username", user.username);
            data.put("avatar", user.avatar);
            data.put("registered_at", user.registeredAt);
            data.put("station_id", user.stationId);
            data.put("friend_verify", user.friendVerify);
            data.put("message_count", user.messageCount);
            sendResponse(exchange, 200, "User info retrieved", data);
        }
    }

    // ---------------------- 数据模型 ----------------------
    static class UserData {
        public String id;
        public String username;
        public String password;
        public String friendVerify;
        public long registeredAt;
        public String stationId;
        public List<FriendRequest> friendRequests = new ArrayList<>();
        public List<String> friends = new ArrayList<>();
        public int messageCount;
        public String avatar;
        public boolean deleted;
    }

    static class FriendRequest {
        public String fromUid;
        public long time;
        public String status;
    }

    static class Message {
        public String from;
        public String to;
        public String content;
        public long time;
    }

    interface UserUpdater {
        UserData update(UserData user);
    }
}