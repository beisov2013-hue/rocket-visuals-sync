import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rocket Visuals — сервер синхронизации косметики (Custom Sword, шляпы, позже плащи).
 *
 * Мод каждого игрока сообщает сюда, что у него надето, и забирает косметику игроков рядом.
 * Хранит только UUID игрока и номера выбранных видов, в памяти, 10 минут с последнего сообщения.
 *
 *   POST /cosmetics           {"uuid":"<uuid>","sword":2,"hat":-1}   (-1 — выключено)
 *   GET  /cosmetics?uuids=a,b → {"a":{"sword":2}}                    (только включённое)
 *   GET  /                    → "Rocket Visuals sync OK"
 *
 * Новые виды косметики (например "cape") сервер принимает сам, менять его не нужно.
 *
 * Запуск (нужна Java 21+):  java RocketSyncServer.java [порт]
 * Порт берётся из аргумента, переменной окружения PORT (так делают хостинги) или 8765.
 */
public class RocketSyncServer {
	private static final long TTL_MS = 10 * 60 * 1000;
	private static final int MAX_BODY = 1024;
	private static final int MAX_UUIDS = 100;
	private static final int MAX_KEYS = 8;
	private static final int MAX_VALUE = 63;
	private static final Pattern UUID_RE =
			Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
	private static final Pattern UUID_FIELD = Pattern.compile("\"uuid\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"");
	private static final Pattern VALUE_FIELD = Pattern.compile("\"([a-z]{1,16})\"\\s*:\\s*(-?\\d{1,3})");

	/** Что надето у игрока: вид косметики → номер (только включённое) и когда игрок последний раз писал. */
	private record Entry(Map<String, Integer> items, long seen) {
	}

	private static final Map<String, Entry> PLAYERS = new ConcurrentHashMap<>();

	public static void main(String[] args) throws IOException {
		int port = args.length > 0 ? Integer.parseInt(args[0])
				: System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 8765;
		HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
		server.createContext("/", RocketSyncServer::handle);
		server.setExecutor(Executors.newFixedThreadPool(4));
		server.start();
		System.out.println("Rocket Visuals sync server on port " + port);
	}

	private static void handle(HttpExchange ex) throws IOException {
		try {
			String path = ex.getRequestURI().getPath();
			String method = ex.getRequestMethod();
			if (method.equals("POST") && path.equals("/cosmetics")) {
				post(ex);
			} else if (method.equals("GET") && path.equals("/cosmetics")) {
				get(ex);
			} else if (method.equals("GET") && path.equals("/")) {
				send(ex, 200, "text/plain", "Rocket Visuals sync OK");
			} else {
				send(ex, 404, "text/plain", "not found");
			}
		} catch (Exception e) {
			send(ex, 400, "text/plain", "bad request");
		} finally {
			ex.close();
		}
	}

	private static void post(HttpExchange ex) throws IOException {
		byte[] body;
		try (InputStream in = ex.getRequestBody()) {
			body = in.readNBytes(MAX_BODY + 1);
		}
		if (body.length > MAX_BODY) {
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

	private static void get(HttpExchange ex) throws IOException {
		cleanup();
		String query = ex.getRequestURI().getRawQuery();
		String list = "";
		if (query != null) {
			for (String part : query.split("&")) {
				if (part.startsWith("uuids=")) {
					list = URLDecoder.decode(part.substring(6), StandardCharsets.UTF_8);
				}
			}
		}
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

	private static void cleanup() {
		long now = System.currentTimeMillis();
		PLAYERS.entrySet().removeIf(e -> now - e.getValue().seen > TTL_MS);
	}

	private static void send(HttpExchange ex, int code, String type, String text) throws IOException {
		byte[] data = text.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
		ex.sendResponseHeaders(code, data.length);
		try (OutputStream out = ex.getResponseBody()) {
			out.write(data);
		}
	}
}
