import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executors;

public class App {
    static final LocalDate EXAM_DATE = LocalDate.of(2026, 12, 12);
    static List<String> quotes = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path q = Paths.get("quotes.txt");
        if (Files.exists(q)) quotes = Files.readAllLines(q, StandardCharsets.UTF_8);
        if (quotes.isEmpty()) quotes.add("Keep going.");

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", ex -> {
            if (!ex.getRequestURI().getPath().equals("/")) {
                send(ex, 404, "text/plain; charset=utf-8", "not found");
                return;
            }
            byte[] html = Files.readAllBytes(Paths.get("static/index.html"));
            sendBytes(ex, 200, "text/html; charset=utf-8", html);
        });

        server.createContext("/api/today", ex -> {
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
            long days = ChronoUnit.DAYS.between(today, EXAM_DATE);
            String quote = quotes.get(today.getDayOfYear() % quotes.size());
            String json = "{\"date\":\"" + today + "\",\"examDate\":\"" + EXAM_DATE
                    + "\",\"daysLeft\":" + days + ",\"quote\":\"" + quote + "\"}";
            send(ex, 200, "application/json; charset=utf-8", json);
        });

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("已启动，监听 " + port + " 端口");
    }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        sendBytes(ex, code, type, body.getBytes(StandardCharsets.UTF_8));
    }

    static void sendBytes(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }
}
