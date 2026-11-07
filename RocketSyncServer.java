import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rocket Visuals — сервер мода.
 *
 * Косметика (Custom Sword, шляпы, позже плащи) — в памяти, 10 минут с последнего сообщения:
 *   POST /cosmetics           {"uuid":"<uuid>","sword":2,"hat":-1}   (-1 — выключено)
 *   GET  /cosmetics?uuids=a,b → {"a":{"sword":2}}                    (только включённое)
 *
 * Ключи и версии мода — в базе (переменная окружения DATABASE_URL, например бесплатный Neon):
 *   POST /api/activate  key, device       → активировать ключ на этом компьютере
 *   POST /api/check     key, device       → сколько дней осталось + последняя версия
 *   GET  /api/download?key=&device=       → файл последней версии мода (только с действующим ключом)
 *   GET  /admin                           → панель владельца (пароль — переменная ADMIN_PASSWORD)
 *
 * Запуск (нужна Java 21+ и драйвер PostgreSQL):  java -cp postgresql.jar RocketSyncServer.java [порт]
 * Порт берётся из аргумента, переменной окружения PORT (так делают хостинги) или 8765.
 */
public class RocketSyncServer {
	private static final long TTL_MS = 10 * 60 * 1000;
	private static final long DAY_MS = 24L * 60 * 60 * 1000;
	private static final int MAX_BODY = 1024;
	private static final int MAX_FORM = 4096;
	private static final int MAX_JAR = 30 * 1024 * 1024;
	private static final int MAX_UUIDS = 100;
	private static final int MAX_KEYS = 8;
	private static final int MAX_VALUE = 63;
	private static final Pattern UUID_RE =
			Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
	private static final Pattern UUID_FIELD = Pattern.compile("\"uuid\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"");
	private static final Pattern VALUE_FIELD = Pattern.compile("\"([a-z]{1,16})\"\\s*:\\s*(-?\\d{1,3})");
	private static final Pattern KEY_RE = Pattern.compile("^RV(-[A-Z2-9]{4}){3}$");
	private static final Pattern DEVICE_RE = Pattern.compile("^[a-f0-9]{32}$");
	private static final Pattern VERSION_RE =Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){1,3}$");
	/** Без похожих символов (0/O, 1/I/L), чтобы ключ было легко переписать руками. */
	private static final String KEY_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
	private static final SecureRandom RANDOM = new SecureRandom();

	/** Что надето у игрока: вид косметики → номер (только включённое) и когда игрок последний раз писал. */
	private record Entry(Map<String, Integer> items, long seen) {
	}

	private static final Map<String, Entry> PLAYERS = new ConcurrentHashMap<>();
	/** Простая защита от перебора ключей: попытки активации с одного адреса за минуту. */
	private static final Map<String, long[]> ATTEMPTS = new ConcurrentHashMap<>();

	private static String dbUrl;
	private static Properties dbProps;
	private static String adminPassword;
	private static String adminPage;

	public static void main(String[] args) throws Exception {
		int port = args.length > 0 ? Integer.parseInt(args[0])
				: System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 8765;
		adminPassword = System.getenv("ADMIN_PASSWORD");
		Path page = Path.of("admin.html");
		adminPage = Files.exists(page) ? Files.readString(page) : "admin.html not found";
		initDatabase(System.getenv("DATABASE_URL"));

		HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
		server.createContext("/", RocketSyncServer::handle);
		server.setExecutor(Executors.newFixedThreadPool(8));
		server.start();
		System.out.println("Rocket Visuals server on port " + port + (dbUrl == null ? " (без базы: ключи выключены)" : ""));
	}

	private static void handle(HttpExchange ex) throws IOException {
		try {
			String path = ex.getRequestURI().getPath();
			String method = ex.getRequestMethod();
			if (method.equals("POST") && path.equals("/cosmetics")) {
				postCosmetics(ex);
			} else if (method.equals("GET") && path.equals("/cosmetics")) {
				getCosmetics(ex);
			} else if (method.equals("GET") && path.equals("/health")) {
				send(ex, 200, "text/plain", "Rocket Visuals sync OK");
			} else if (method.equals("GET") && (path.equals("/") || path.startsWith("/img/") || path.equals("/download"))) {
				site(ex, path);
			} else if (path.startsWith("/api/")) {
				if (!requireDatabase(ex)) {
					return;
				}
				switch (method + " " + path) {
					case "POST /api/register" -> register(ex);
					case "POST /api/login" -> login(ex);
					case "POST /api/me" -> me(ex);
					case "POST /api/activate" -> activate(ex);
					case "POST /api/check" -> check(ex);
					case "GET /api/download" -> download(ex);
					default -> send(ex, 404, "text/plain", "not found");
				}
			} else if (method.equals("GET") && path.equals("/admin")) {
				send(ex, 200, "text/html", adminPage);
			} else if (path.startsWith("/admin/api/")) {
				if (!requireDatabase(ex) || !requireAdmin(ex)) {
					return;
				}
				admin(ex, method, path.substring("/admin/api/".length()));
			} else {
				send(ex, 404, "text/plain", "not found");
			}
		} catch (SQLException e) {
			System.err.println("DB: " + e.getMessage());
			send(ex, 500, "application/json", "{\"ok\":false,\"error\":\"Ошибка базы данных\"}");
		} catch (Exception e) {
			send(ex, 400, "text/plain", "bad request");
		} finally {
			ex.close();
		}
	}

	// ======================== сайт мода ========================

	private static final Pattern IMG_RE = Pattern.compile("^/img/[a-z0-9-]{1,40}\\.(png|jpg|webp)$");

	/** Страница мода (папка site): главная, картинки и установщик для скачивания. */
	private static void site(HttpExchange ex, String path) throws IOException {
		Path file;
		String type;
		if (path.equals("/")) {
			file = Path.of("site", "index.html");
			type = "text/html; charset=utf-8";
		} else if (path.equals("/download")) {
			file = Path.of("site", "RocketVisualsLauncher.exe");
			type = "application/octet-stream";
			ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"RocketVisualsLauncher.exe\"");
		} else if (IMG_RE.matcher(path).matches()) {
			file = Path.of("site", "img", path.substring(5));
			type = path.endsWith(".png") ? "image/png" : path.endsWith(".webp") ? "image/webp" : "image/jpeg";
			ex.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
		} else {
			send(ex, 404, "text/plain", "not found");
			return;
		}
		if (!Files.isRegularFile(file)) {
			send(ex, 404, "text/plain", "not found");
			return;
		}
		ex.getResponseHeaders().set("Content-Type", type);
		long size = Files.size(file);
		ex.sendResponseHeaders(200, size);
		try (OutputStream out = ex.getResponseBody()) {
			Files.copy(file, out);
		}
	}

	// ======================== косметика ========================

	private static void postCosmetics(HttpExchange ex) throws IOException {
		byte[] body = readBody(ex, MAX_BODY);
		if (body == null) {
			send(ex, 413, "text/plain", "too large");
			return;
		}
		String text = new String(body, StandardCharsets.UTF_8);
		Matcher u = UUID_FIELD.matcher(text);
		if (!u.find()) {
			send(ex, 400, "text/plain", "no uuid");
			return;
		}
		String uuid = u.group(1).toLowerCase(Locale.ROOT);
		if (!UUID_RE.matcher(uuid).matches()) {
			send(ex, 400, "text/plain", "bad uuid");
			return;
		}
		Map<String, Integer> items = new LinkedHashMap<>();
		Matcher m = VALUE_FIELD.matcher(text);
		while (m.find() && items.size() < MAX_KEYS) {
			int value = Integer.parseInt(m.group(2));
			if (value >= 0 && value <= MAX_VALUE) {
				items.put(m.group(1), value);
			}
		}
		if (items.isEmpty()) {
			PLAYERS.remove(uuid); // всё выключено — другие видят обычный вид
		} else {
			PLAYERS.put(uuid, new Entry(Map.copyOf(items), System.currentTimeMillis()));
		}
		send(ex, 200, "application/json", "{\"ok\":true}");
	}

	private static void getCosmetics(HttpExchange ex) throws IOException {
		long now = System.currentTimeMillis();
		PLAYERS.entrySet().removeIf(e -> now - e.getValue().seen > TTL_MS);
		String list = query(ex).getOrDefault("uuids", "");
		StringBuilder json = new StringBuilder("{");
		int count = 0;
		for (String raw : list.split(",")) {
			if (count++ >= MAX_UUIDS) {
				break;
			}
			String uuid = raw.trim().toLowerCase(Locale.ROOT);
			Entry e = UUID_RE.matcher(uuid).matches() ? PLAYERS.get(uuid) : null;
			if (e == null) {
				continue;
			}
			if (json.length() > 1) {
				json.append(',');
			}
			json.append('"').append(uuid).append("\":{");
			boolean first = true;
			for (Map.Entry<String, Integer> item : e.items.entrySet()) {
				if (!first) {
					json.append(',');
				}
				first = false;
				json.append('"').append(item.getKey()).append("\":").append(item.getValue());
			}
			json.append('}');
		}
		json.append('}');
		send(ex, 200, "application/json", json.toString());
	}

	// ======================== база ========================

	/** DATABASE_URL вида postgresql://user:pass@host/db?sslmode=require (так его даёт Neon). */
	private static void initDatabase(String url) throws SQLException {
		if (url == null || url.isBlank()) {
			return;
		}
		if (url.startsWith("jdbc:")) {
			dbUrl = url; // для проверки на своём компьютере
			dbProps = new Properties();
		} else {
			URI uri = URI.create(url.trim());
			String[] user = uri.getRawUserInfo().split(":", 2);
			dbProps = new Properties();
			dbProps.setProperty("user", URLDecoder.decode(user[0], StandardCharsets.UTF_8));
			dbProps.setProperty("password", URLDecoder.decode(user[1], StandardCharsets.UTF_8));
			String q = uri.getRawQuery() == null ? "sslmode=require" : uri.getRawQuery();
			// channel_binding драйвер Java понимает не во всех версиях — оставляем только sslmode
			q = q.replaceAll("&?channel_binding=[^&]*", "");
			dbUrl = "jdbc:postgresql://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + uri.getRawPath() + "?" + q;
		}
		try (Connection c = db(); Statement s = c.createStatement()) {
			s.execute("CREATE TABLE IF NOT EXISTS rv_keys ("
					+ "code VARCHAR(32) PRIMARY KEY, days INT NOT NULL, note VARCHAR(200),"
					+ " created BIGINT NOT NULL, activated BIGINT, expires BIGINT, device VARCHAR(64),"
					+ " disabled BOOLEAN NOT NULL DEFAULT FALSE, last_seen BIGINT)");
			s.execute("CREATE TABLE IF NOT EXISTS rv_versions ("
					+ "version VARCHAR(32) PRIMARY KEY, uploaded BIGINT NOT NULL, notes VARCHAR(2000),"
					+ " sha256 VARCHAR(64) NOT NULL, size INT NOT NULL, data BYTEA NOT NULL)");
			// аккаунты установщика: ник + пароль (хранится только хэш)
			s.execute("CREATE TABLE IF NOT EXISTS rv_users ("
					+ "nick_key VARCHAR(32) PRIMARY KEY, nick VARCHAR(32) NOT NULL, salt VARCHAR(64) NOT NULL,"
					+ " hash VARCHAR(128) NOT NULL, created BIGINT NOT NULL)");
			s.execute("CREATE TABLE IF NOT EXISTS rv_sessions ("
					+ "token VARCHAR(64) PRIMARY KEY, nick_key VARCHAR(32) NOT NULL, created BIGINT NOT NULL)");
			s.execute("ALTER TABLE rv_keys ADD COLUMN IF NOT EXISTS nick VARCHAR(32)");
		}
	}

	private static Connection db() throws SQLException {
		return DriverManager.getConnection(dbUrl, dbProps);
	}

	private static boolean requireDatabase(HttpExchange ex) throws IOException {
		if (dbUrl != null) {
			return true;
		}
		send(ex, 503, "application/json", "{\"ok\":false,\"error\":\"На сервере не настроена база (DATABASE_URL)\"}");
		return false;
	}

	// ======================== аккаунты ========================

	private static final Pattern NICK_RE = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

	private static void register(HttpExchange ex) throws IOException, SQLException {
		if (tooManyAttempts(ex)) {
			send(ex, 429, "application/json", error("Слишком много попыток, подожди минуту"));
			return;
		}
		Map<String, String> f = form(ex);
		String nick = f.getOrDefault("nick", "").trim();
		String password = f.getOrDefault("password", "");
		if (!NICK_RE.matcher(nick).matches()) {
			send(ex, 400, "application/json", error("Ник: 3–16 символов, английские буквы, цифры и _"));
			return;
		}
		if (password.length() < 6 || password.length() > 100) {
			send(ex, 400, "application/json", error("Пароль: минимум 6 символов"));
			return;
		}
		try (Connection c = db()) {
			if (userExists(c, nick)) {
				send(ex, 409, "application/json", error("Такой ник уже занят"));
				return;
			}
			byte[] salt = new byte[16];
			RANDOM.nextBytes(salt);
			update(c, "INSERT INTO rv_users (nick_key, nick, salt, hash, created) VALUES (?, ?, ?, ?, ?)",
					nick.toLowerCase(Locale.ROOT), nick, HexFormat.of().formatHex(salt), hashPassword(password, salt),
					System.currentTimeMillis());
			sendSession(ex, c, nick);
		}
	}

	private static void login(HttpExchange ex) throws IOException, SQLException {
		if (tooManyAttempts(ex)) {
			send(ex, 429, "application/json", error("Слишком много попыток, подожди минуту"));
			return;
		}
		Map<String, String> f = form(ex);
		String nickKey = f.getOrDefault("nick", "").trim().toLowerCase(Locale.ROOT);
		String password = f.getOrDefault("password", "");
		try (Connection c = db()) {
			try (PreparedStatement st = c.prepareStatement("SELECT nick, salt, hash FROM rv_users WHERE nick_key=?")) {
				st.setString(1, nickKey);
				try (ResultSet rs = st.executeQuery()) {
					if (rs.next()) {
						String hash = hashPassword(password, HexFormat.of().parseHex(rs.getString(2)));
						if (MessageDigest.isEqual(hash.getBytes(StandardCharsets.UTF_8), rs.getString(3).getBytes(StandardCharsets.UTF_8))) {
							sendSession(ex, c, rs.getString(1));
							return;
						}
					}
				}
			}
			send(ex, 401, "application/json", error("Неверный ник или пароль"));
		}
	}

	/** Кто вошёл по этому токену. */
	private static void me(HttpExchange ex) throws IOException, SQLException {
		try (Connection c = db()) {
			String nick = nickByToken(c, form(ex).get("token"));
			if (nick == null) {
				send(ex, 401, "application/json", error("Нужно войти заново"));
				return;
			}
			send(ex, 200, "application/json", "{\"ok\":true,\"nick\":" + quote(nick) + "}");
		}
	}

	private static void sendSession(HttpExchange ex, Connection c, String nick) throws IOException, SQLException {
		byte[] raw = new byte[24];
		RANDOM.nextBytes(raw);
		String token = HexFormat.of().formatHex(raw);
		update(c, "INSERT INTO rv_sessions (token, nick_key, created) VALUES (?, ?, ?)",
				token, nick.toLowerCase(Locale.ROOT), System.currentTimeMillis());
		send(ex, 200, "application/json", "{\"ok\":true,\"nick\":" + quote(nick) + ",\"token\":" + quote(token) + "}");
	}

	private static boolean userExists(Connection c, String nick) throws SQLException {
		try (PreparedStatement st = c.prepareStatement("SELECT 1 FROM rv_users WHERE nick_key=?")) {
			st.setString(1, nick.toLowerCase(Locale.ROOT));
			try (ResultSet rs = st.executeQuery()) {
				return rs.next();
			}
		}
	}

	private static String nickByToken(Connection c, String token) throws SQLException {
		if (token == null || !token.matches("^[a-f0-9]{48}$")) {
			return null;
		}
		try (PreparedStatement st = c.prepareStatement(
				"SELECT u.nick FROM rv_sessions s JOIN rv_users u ON u.nick_key = s.nick_key WHERE s.token=?")) {
			st.setString(1, token);
			try (ResultSet rs = st.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		}
	}

	private static String hashPassword(String password, byte[] salt) {
		try {
			var spec = new javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, 120_000, 256);
			byte[] hash = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
			return HexFormat.of().formatHex(hash);
		} catch (java.security.GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	// ======================== ключи для игроков ========================

	private static void activate(HttpExchange ex) throws IOException, SQLException {
		if (tooManyAttempts(ex)) {
			send(ex, 429, "application/json", error("Слишком много попыток, подожди минуту"));
			return;
		}
		Map<String, String> form = form(ex);
		String code = normalizeKey(form.get("key"));
		String device = form.getOrDefault("device", "");
		if (code == null || !DEVICE_RE.matcher(device).matches()) {
			send(ex, 400, "application/json", error("Неверный формат ключа"));
			return;
		}
		try (Connection c = db()) {
			String nick = nickByToken(c, form.get("token"));
			if (nick == null) {
				send(ex, 401, "application/json", error("Нужно войти в аккаунт"));
				return;
			}
			Map<String, Object> key = findKey(c, code);
			if (key == null) {
				send(ex, 404, "application/json", error("Такого ключа нет"));
				return;
			}
			if ((Boolean) key.get("disabled")) {
				send(ex, 403, "application/json", error("Ключ отключён"));
				return;
			}
			if (key.get("nick") == null) {
				update(c, "UPDATE rv_keys SET nick=? WHERE code=? AND nick IS NULL", nick, code);
				key = findKey(c, code);
			}
			if (!nick.equalsIgnoreCase((String) key.get("nick"))) {
				send(ex, 403, "application/json", error("Этот ключ уже привязан к другому аккаунту"));
				return;
			}
			long now = System.currentTimeMillis();
			if (key.get("device") == null) {
				// первая активация — срок пошёл; после «отвязать» — срок прежний, меняется только компьютер
				long expires = key.get("activated") == null ? now + (Integer) key.get("days") * DAY_MS : (Long) key.get("expires");
				try (PreparedStatement st = c.prepareStatement(
						"UPDATE rv_keys SET device=?, activated=COALESCE(activated, ?), expires=?, last_seen=? WHERE code=? AND device IS NULL")) {
					st.setString(1, device);
					st.setLong(2, now);
					st.setLong(3, expires);
					st.setLong(4, now);
					st.setString(5, code);
					st.executeUpdate();
				}
				key = findKey(c, code);
			}
			if (!device.equals(key.get("device"))) {
				send(ex, 403, "application/json", error("Ключ уже активирован на другом компьютере"));
				return;
			}
			sendStatus(ex, c, key, now);
		}
	}

	private static void check(HttpExchange ex) throws IOException, SQLException {
		Map<String, String> form = form(ex);
		String code = normalizeKey(form.get("key"));
		String device = form.getOrDefault("device", "");
		try (Connection c = db()) {
			Map<String, Object> key = code == null ? null : findKey(c, code);
			if (key == null || !device.equals(key.get("device"))) {
				send(ex, 403, "application/json", error("Ключ не активирован на этом компьютере"));
				return;
			}
			long now = System.currentTimeMillis();
			try (PreparedStatement st = c.prepareStatement("UPDATE rv_keys SET last_seen=? WHERE code=?")) {
				st.setLong(1, now);
				st.setString(2, code);
				st.executeUpdate();
			}
			sendStatus(ex, c, key, now);
		}
	}

	/** Ответ игроку: действует ли ключ, до какого времени и какая последняя версия мода. */
	private static void sendStatus(HttpExchange ex, Connection c, Map<String, Object> key, long now) throws IOException, SQLException {
		long expires = (Long) key.get("expires");
		boolean active = !(Boolean) key.get("disabled") && expires > now;
		StringBuilder json = new StringBuilder("{\"ok\":true,\"active\":").append(active)
				.append(",\"expires\":").append(expires)
				.append(",\"now\":").append(now)
				.append(",\"daysLeft\":").append(Math.max(0, (expires - now + DAY_MS - 1) / DAY_MS));
		if ((Boolean) key.get("disabled")) {
			json.append(",\"error\":\"Ключ отключён\"");
		} else if (!active) {
			json.append(",\"error\":\"Срок ключа закончился\"");
		}
		try (PreparedStatement st = c.prepareStatement(
				"SELECT version, sha256, size, notes FROM rv_versions ORDER BY uploaded DESC LIMIT 1");
		     ResultSet rs = st.executeQuery()) {
			if (rs.next()) {
				json.append(",\"latest\":{\"version\":").append(quote(rs.getString(1)))
						.append(",\"sha256\":").append(quote(rs.getString(2)))
						.append(",\"size\":").append(rs.getInt(3))
						.append(",\"notes\":").append(quote(rs.getString(4))).append('}');
			}
		}
		send(ex, 200, "application/json", json.append('}').toString());
	}

	private static void download(HttpExchange ex) throws IOException, SQLException {
		Map<String, String> q = query(ex);
		String code = normalizeKey(q.get("key"));
		String device = q.getOrDefault("device", "");
		try (Connection c = db()) {
			Map<String, Object> key = code == null ? null : findKey(c, code);
			if (key == null || !device.equals(key.get("device")) || (Boolean) key.get("disabled")
					|| (Long) key.get("expires") <= System.currentTimeMillis()) {
				send(ex, 403, "text/plain", "key is not active");
				return;
			}
			try (PreparedStatement st = c.prepareStatement("SELECT version, data FROM rv_versions ORDER BY uploaded DESC LIMIT 1")) {
				ResultSet rs = st.executeQuery();
				if (!rs.next()) {
					send(ex, 404, "text/plain", "no versions");
					return;
				}
				byte[] data = rs.getBytes(2);
				ex.getResponseHeaders().set("Content-Type", "application/java-archive");
				ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"rocketvisuals-" + rs.getString(1) + ".jar\"");
				ex.sendResponseHeaders(200, data.length);
				try (OutputStream out = ex.getResponseBody()) {
					out.write(data);
				}
			}
		}
	}

	private static Map<String, Object> findKey(Connection c, String code) throws SQLException {
		try (PreparedStatement st = c.prepareStatement(
				"SELECT code, days, note, created, activated, expires, device, disabled, last_seen, nick FROM rv_keys WHERE code=?")) {
			st.setString(1, code);
			try (ResultSet rs = st.executeQuery()) {
				return rs.next() ? keyRow(rs) : null;
			}
		}
	}

	private static Map<String, Object> keyRow(ResultSet rs) throws SQLException {
		Map<String, Object> row = new HashMap<>();
		row.put("code", rs.getString(1));
		row.put("days", rs.getInt(2));
		row.put("note", rs.getString(3));
		row.put("created", rs.getLong(4));
		long activated = rs.getLong(5);
		row.put("activated", rs.wasNull() ? null : activated);
		long expires = rs.getLong(6);
		row.put("expires", rs.wasNull() ? 0L : expires);
		row.put("device", rs.getString(7));
		row.put("disabled", rs.getBoolean(8));
		long seen = rs.getLong(9);
		row.put("lastSeen", rs.wasNull() ? null : seen);
		row.put("nick", rs.getString(10));
		return row;
	}

	private static String normalizeKey(String raw) {
		if (raw == null) {
			return null;
		}
		String k = raw.trim().toUpperCase(Locale.ROOT);
		return KEY_RE.matcher(k).matches() ? k : null;
	}

	private static boolean tooManyAttempts(HttpExchange ex) {
		String ip = ex.getRequestHeaders().getFirst("X-Forwarded-For");
		if (ip == null) {
			ip = ex.getRemoteAddress().getAddress().getHostAddress();
		}
		ip = ip.split(",")[0].trim();
		long minute = System.currentTimeMillis() / 60000;
		long[] slot = ATTEMPTS.compute(ip, (k, v) -> v == null || v[0] != minute ? new long[]{minute, 0} : v);
		if (ATTEMPTS.size() > 10000) {
			ATTEMPTS.entrySet().removeIf(e -> e.getValue()[0] != minute);
		}
		synchronized (slot) {
			return ++slot[1] > 10;
		}
	}

	// ======================== панель владельца ========================

	private static boolean requireAdmin(HttpExchange ex) throws IOException {
		String given = ex.getRequestHeaders().getFirst("X-Admin-Password");
		if (adminPassword == null || adminPassword.length() < 8) {
			send(ex, 503, "application/json", error("На сервере не задан ADMIN_PASSWORD (минимум 8 символов)"));
			return false;
		}
		if (given == null || !MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), adminPassword.getBytes(StandardCharsets.UTF_8))) {
			send(ex, 401, "application/json", error("Неверный пароль"));
			return false;
		}
		return true;
	}

	private static void admin(HttpExchange ex, String method, String action) throws IOException, SQLException {
		try (Connection c = db()) {
			switch (method + " " + action) {
				case "GET keys" -> {
					StringBuilder json = new StringBuilder("[");
					try (PreparedStatement st = c.prepareStatement(
							"SELECT code, days, note, created, activated, expires, device, disabled, last_seen, nick FROM rv_keys ORDER BY created DESC");
					     ResultSet rs = st.executeQuery()) {
						while (rs.next()) {
							Map<String, Object> k = keyRow(rs);
							if (json.length() > 1) {
								json.append(',');
							}
							json.append("{\"code\":").append(quote((String) k.get("code")))
									.append(",\"days\":").append(k.get("days"))
									.append(",\"note\":").append(quote((String) k.get("note")))
									.append(",\"created\":").append(k.get("created"))
									.append(",\"activated\":").append(k.get("activated"))
									.append(",\"expires\":").append(k.get("expires"))
									.append(",\"bound\":").append(k.get("device") != null)
									.append(",\"disabled\":").append(k.get("disabled"))
									.append(",\"lastSeen\":").append(k.get("lastSeen"))
									.append(",\"nick\":").append(quote((String) k.get("nick"))).append('}');
						}
					}
					send(ex, 200, "application/json", json.append(']').toString());
				}
				case "POST keys" -> {
					Map<String, String> f = form(ex);
					int days = clamp(parseInt(f.get("days"), 30), 1, 3650);
					int count = clamp(parseInt(f.get("count"), 1), 1, 50);
					String note = trimTo(f.getOrDefault("note", ""), 200);
					StringBuilder json = new StringBuilder("{\"ok\":true,\"keys\":[");
					try (PreparedStatement st = c.prepareStatement(
							"INSERT INTO rv_keys (code, days, note, created, disabled) VALUES (?, ?, ?, ?, FALSE)")) {
						for (int i = 0; i < count; i++) {
							String code = newKey();
							st.setString(1, code);
							st.setInt(2, days);
							st.setString(3, note);
							st.setLong(4, System.currentTimeMillis());
							st.executeUpdate();
							json.append(i > 0 ? "," : "").append(quote(code));
						}
					}
					send(ex, 200, "application/json", json.append("]}").toString());
				}
				case "POST extend" -> {
					Map<String, String> f = form(ex);
					String code = normalizeKey(f.get("key"));
					int days = clamp(parseInt(f.get("days"), 0), -3650, 3650);
					Map<String, Object> k = code == null ? null : findKey(c, code);
					if (k == null) {
						send(ex, 404, "application/json", error("Ключ не найден"));
						return;
					}
					if (k.get("activated") == null) {
						// ещё не активирован — просто добавляем дни к сроку ключа
						update(c, "UPDATE rv_keys SET days=GREATEST(1, days+?) WHERE code=?", days, code);
					} else {
						// продлеваем от сегодня, если срок уже прошёл, иначе от конца срока
						long base = Math.max((Long) k.get("expires"), System.currentTimeMillis());
						update(c, "UPDATE rv_keys SET expires=? WHERE code=?", base + days * DAY_MS, code);
					}
					send(ex, 200, "application/json", "{\"ok\":true}");
				}
				case "POST disable", "POST enable" -> {
					String code = normalizeKey(form(ex).get("key"));
					update(c, "UPDATE rv_keys SET disabled=? WHERE code=?", action.equals("disable"), code);
					send(ex, 200, "application/json", "{\"ok\":true}");
				}
				case "POST unbind" -> {
					// игрок сменил компьютер: следующая активация привяжет ключ заново, срок сохраняется
					String code = normalizeKey(form(ex).get("key"));
					update(c, "UPDATE rv_keys SET device=NULL WHERE code=?", code);
					send(ex, 200, "application/json", "{\"ok\":true}");
				}
				case "POST delete" -> {
					String code = normalizeKey(form(ex).get("key"));
					update(c, "DELETE FROM rv_keys WHERE code=?", code);
					send(ex, 200, "application/json", "{\"ok\":true}");
				}
				case "GET users" -> {
					// все аккаунты установщика: когда зарегистрировался, когда последний раз входил, какие ключи
					Map<String, StringBuilder> keysByNick = new HashMap<>();
					try (PreparedStatement st = c.prepareStatement(
							"SELECT LOWER(nick), code, note, expires, disabled, last_seen FROM rv_keys WHERE nick IS NOT NULL ORDER BY activated DESC");
					     ResultSet rs = st.executeQuery()) {
						while (rs.next()) {
							StringBuilder list = keysByNick.computeIfAbsent(rs.getString(1), k -> new StringBuilder());
							if (!list.isEmpty()) {
								list.append(',');
							}
							long seen = rs.getLong(6);
							list.append("{\"code\":").append(quote(rs.getString(2)))
									.append(",\"note\":").append(quote(rs.getString(3)))
									.append(",\"expires\":").append(rs.getLong(4))
									.append(",\"disabled\":").append(rs.getBoolean(5))
									.append(",\"lastSeen\":").append(rs.wasNull() ? "null" : String.valueOf(seen)).append('}');
						}
					}
					StringBuilder json = new StringBuilder("[");
					try (PreparedStatement st = c.prepareStatement(
							"SELECT u.nick_key, u.nick, u.created, (SELECT MAX(s.created) FROM rv_sessions s WHERE s.nick_key = u.nick_key)"
									+ " FROM rv_users u ORDER BY u.created DESC");
					     ResultSet rs = st.executeQuery()) {
						while (rs.next()) {
							if (json.length() > 1) {
								json.append(',');
							}
							long login = rs.getLong(4);
							StringBuilder keys = keysByNick.get(rs.getString(1));
							json.append("{\"nick\":").append(quote(rs.getString(2)))
									.append(",\"created\":").append(rs.getLong(3))
									.append(",\"lastLogin\":").append(rs.wasNull() ? "null" : String.valueOf(login))
									.append(",\"keys\":[").append(keys == null ? "" : keys).append("]}");
						}
					}
					send(ex, 200, "application/json", json.append(']').toString());
				}
				case "GET versions" -> {
					StringBuilder json = new StringBuilder("[");
					try (PreparedStatement st = c.prepareStatement(
							"SELECT version, uploaded, size, notes FROM rv_versions ORDER BY uploaded DESC");
					     ResultSet rs = st.executeQuery()) {
						while (rs.next()) {
							if (json.length() > 1) {
								json.append(',');
							}
							json.append("{\"version\":").append(quote(rs.getString(1)))
									.append(",\"uploaded\":").append(rs.getLong(2))
									.append(",\"size\":").append(rs.getInt(3))
									.append(",\"notes\":").append(quote(rs.getString(4))).append('}');
						}
					}
					send(ex, 200, "application/json", json.append(']').toString());
				}
				case "POST versions" -> {
					Map<String, String> q = query(ex);
					String version = q.getOrDefault("version", "").trim();
					if (!VERSION_RE.matcher(version).matches()) {
						send(ex, 400, "application/json", error("Версия должна быть вида 1.0.1"));
						return;
					}
					byte[] jar = readBody(ex, MAX_JAR);
					if (jar == null || jar.length < 4 || jar[0] != 'P' || jar[1] != 'K') {
						send(ex, 400, "application/json", error("Это не .jar файл (или он больше 30 МБ)"));
						return;
					}
					String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar));
					update(c, "DELETE FROM rv_versions WHERE version=?", version);
					try (PreparedStatement st = c.prepareStatement(
							"INSERT INTO rv_versions (version, uploaded, notes, sha256, size, data) VALUES (?, ?, ?, ?, ?, ?)")) {
						st.setString(1, version);
						st.setLong(2, System.currentTimeMillis());
						st.setString(3, trimTo(q.getOrDefault("notes", ""), 2000));
						st.setString(4, sha);
						st.setInt(5, jar.length);
						st.setBytes(6, jar);
						st.executeUpdate();
					}
					send(ex, 200, "application/json", "{\"ok\":true}");
				}
				case "POST versions/delete" -> {
					update(c, "DELETE FROM rv_versions WHERE version=?", form(ex).get("version"));
					send(ex, 200, "application/json", "{\"ok\":true}");
				}
				case "POST users/reset" -> {
					// игрок забыл пароль: новый временный пароль показывается владельцу один раз, все входы сбрасываются
					String nickKey = form(ex).getOrDefault("nick", "").trim().toLowerCase(Locale.ROOT);
					if (!userExists(c, nickKey)) {
						send(ex, 404, "application/json", error("Нет такого аккаунта"));
						return;
					}
					StringBuilder pass = new StringBuilder();
					for (int i = 0; i < 10; i++) {
						pass.append(KEY_ALPHABET.toLowerCase(Locale.ROOT).charAt(RANDOM.nextInt(KEY_ALPHABET.length())));
					}
					byte[] salt = new byte[16];
					RANDOM.nextBytes(salt);
					update(c, "UPDATE rv_users SET salt=?, hash=? WHERE nick_key=?",
							HexFormat.of().formatHex(salt), hashPassword(pass.toString(), salt), nickKey);
					update(c, "DELETE FROM rv_sessions WHERE nick_key=?", nickKey);
					send(ex, 200, "application/json", "{\"ok\":true,\"password\":" + quote(pass.toString()) + "}");
				}
				default -> send(ex, 404, "application/json", error("not found"));
			}
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String newKey() {
		StringBuilder k = new StringBuilder("RV");
		for (int g = 0; g < 3; g++) {
			k.append('-');
			for (int i = 0; i < 4; i++) {
				k.append(KEY_ALPHABET.charAt(RANDOM.nextInt(KEY_ALPHABET.length())));
			}
		}
		return k.toString();
	}

	private static void update(Connection c, String sql, Object... args) throws SQLException {
		try (PreparedStatement st = c.prepareStatement(sql)) {
			for (int i = 0; i < args.length; i++) {
				st.setObject(i + 1, args[i]);
			}
			st.executeUpdate();
		}
	}

	// ======================== общее ========================

	/** Тело запроса, не больше limit байт; null — слишком большое. */
	private static byte[] readBody(HttpExchange ex, int limit) throws IOException {
		try (InputStream in = ex.getRequestBody()) {
			byte[] body = in.readNBytes(limit + 1);
			return body.length > limit ? null : body;
		}
	}

	private static Map<String, String> form(HttpExchange ex) throws IOException {
		byte[] body = readBody(ex, MAX_FORM);
		return parsePairs(body == null ? "" : new String(body, StandardCharsets.UTF_8));
	}

	private static Map<String, String> query(HttpExchange ex) {
		String q = ex.getRequestURI().getRawQuery();
		return parsePairs(q == null ? "" : q);
	}

	private static Map<String, String> parsePairs(String text) {
		Map<String, String> map = new HashMap<>();
		for (String part : text.split("&")) {
			int eq = part.indexOf('=');
			if (eq > 0) {
				map.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
						URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
			}
		}
		return map;
	}

	private static int parseInt(String s, int fallback) {
		try {
			return Integer.parseInt(s.trim());
		} catch (Exception e) {
			return fallback;
		}
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static String trimTo(String s, int max) {
		s = s.trim();
		return s.length() > max ? s.substring(0, max) : s;
	}

	private static String error(String message) {
		return "{\"ok\":false,\"error\":" + quote(message) + "}";
	}

	private static String quote(String s) {
		if (s == null) {
			return "null";
		}
		StringBuilder b = new StringBuilder("\"");
		for (char ch : s.toCharArray()) {
			switch (ch) {
				case '"' -> b.append("\\\"");
				case '\\' -> b.append("\\\\");
				case '\n' -> b.append("\\n");
				case '\r' -> b.append("\\r");
				case '\t' -> b.append("\\t");
				default -> {
					if (ch < 0x20) {
						b.append(String.format("\\u%04x", (int) ch));
					} else {
						b.append(ch);
					}
				}
			}
		}
		return b.append('"').toString();
	}

	private static void send(HttpExchange ex, int code, String type, String text) throws IOException {
		byte[] data = text.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
		ex.getResponseHeaders().set("Cache-Control", "no-store");
		ex.sendResponseHeaders(code, data.length);
		try (OutputStream out = ex.getResponseBody()) {
			out.write(data);
		}
	}
}
