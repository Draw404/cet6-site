import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 六级小站：倒计时首页 + 艾宾浩斯背词模块。
 * 数据文件：DATA_DIR/words.json（默认 data/words.json），
 * 首次启动时若不存在，则从程序同目录的 words.json（种子）复制；
 * 之后种子里新增的词会在启动时自动并入数据文件，不影响已有复习进度。
 * 写接口（复习打卡 / 加词）需要请求头 X-Vocab-Pass 与环境变量 VOCAB_PASS 一致。
 */
public class App {

    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final LocalDate EXAM_DATE = LocalDate.of(2026, 12, 12);
    // 艾宾浩斯复习节点：学会后第 1、2、4、7、15、30 天各复习一次，共 6 轮
    static final int[] OFFSETS = {1, 2, 4, 7, 15, 30};

    static final Path DATA_DIR = Paths.get(System.getenv().getOrDefault("DATA_DIR", "data"));
    static final Path DATA_FILE = DATA_DIR.resolve("words.json");
    static final Path SEED_FILE = Paths.get("words.json");
    static final String PASS = System.getenv("VOCAB_PASS");

    static final Object LOCK = new Object();
    static List<WordRec> words = new ArrayList<>();

    // ---------------- 词条 ----------------
    static class WordRec {
        String word = "", phonetic = "", pos = "", meaning = "";
        String sentence = "", sentenceCn = "", source = "", added = "";
        String learned = null;   // 首次学会日期 yyyy-MM-dd，未学为 null
        String due = null;       // 下次复习日期，未排期/已毕业为 null
        int stage = 0;           // 已完成的复习轮数 0..6
        int lapses = 0;          // 点过「不认识」的次数

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("word", word);
            m.put("phonetic", phonetic);
            m.put("pos", pos);
            m.put("meaning", meaning);
            m.put("sentence", sentence);
            m.put("sentenceCn", sentenceCn);
            m.put("source", source);
            m.put("added", added);
            m.put("learned", learned);
            m.put("due", due);
            m.put("stage", stage);
            m.put("lapses", lapses);
            return m;
        }

        static WordRec fromMap(Map<String, Object> m) {
            WordRec w = new WordRec();
            w.word = str(m.get("word"));
            w.phonetic = str(m.get("phonetic"));
            w.pos = str(m.get("pos"));
            w.meaning = str(m.get("meaning"));
            w.sentence = str(m.get("sentence"));
            w.sentenceCn = str(m.get("sentenceCn"));
            w.source = str(m.get("source"));
            w.added = str(m.get("added"));
            w.learned = m.get("learned") == null ? null : str(m.get("learned"));
            w.due = m.get("due") == null ? null : str(m.get("due"));
            w.stage = num(m.get("stage"));
            w.lapses = num(m.get("lapses"));
            return w;
        }
    }

    static String str(Object o) { return o == null ? "" : o.toString(); }

    static int num(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(str(o)); } catch (Exception e) { return 0; }
    }

    // ---------------- 极简 JSON ----------------
    static class Json {
        static Object parse(String s) {
            int[] p = {0};
            Object v = value(s, p);
            return v;
        }

        static void ws(String s, int[] p) {
            while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) p[0]++;
        }

        static Object value(String s, int[] p) {
            ws(s, p);
            if (p[0] >= s.length()) return null;
            char c = s.charAt(p[0]);
            if (c == '{') {
                p[0]++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws(s, p);
                if (p[0] < s.length() && s.charAt(p[0]) == '}') { p[0]++; return m; }
                while (true) {
                    ws(s, p);
                    String k = (String) value(s, p);
                    ws(s, p);
                    if (p[0] < s.length() && s.charAt(p[0]) == ':') p[0]++;
                    Object v = value(s, p);
                    m.put(k, v);
                    ws(s, p);
                    if (p[0] < s.length() && s.charAt(p[0]) == ',') { p[0]++; continue; }
                    if (p[0] < s.length() && s.charAt(p[0]) == '}') p[0]++;
                    break;
                }
                return m;
            }
            if (c == '[') {
                p[0]++;
                List<Object> l = new ArrayList<>();
                ws(s, p);
                if (p[0] < s.length() && s.charAt(p[0]) == ']') { p[0]++; return l; }
                while (true) {
                    l.add(value(s, p));
                    ws(s, p);
                    if (p[0] < s.length() && s.charAt(p[0]) == ',') { p[0]++; continue; }
                    if (p[0] < s.length() && s.charAt(p[0]) == ']') p[0]++;
                    break;
                }
                return l;
            }
            if (c == '"') {
                p[0]++;
                StringBuilder sb = new StringBuilder();
                while (p[0] < s.length()) {
                    char ch = s.charAt(p[0]++);
                    if (ch == '"') break;
                    if (ch == '\\' && p[0] < s.length()) {
                        char e = s.charAt(p[0]++);
                        switch (e) {
                            case 'n': sb.append('\n'); break;
                            case 't': sb.append('\t'); break;
                            case 'r': sb.append('\r'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'u':
                                sb.append((char) Integer.parseInt(s.substring(p[0], p[0] + 4), 16));
                                p[0] += 4;
                                break;
                            default: sb.append(e);
                        }
                    } else {
                        sb.append(ch);
                    }
                }
                return sb.toString();
            }
            if (s.startsWith("true", p[0])) { p[0] += 4; return Boolean.TRUE; }
            if (s.startsWith("false", p[0])) { p[0] += 5; return Boolean.FALSE; }
            if (s.startsWith("null", p[0])) { p[0] += 4; return null; }
            int start = p[0];
            while (p[0] < s.length() && "-+0123456789.eE".indexOf(s.charAt(p[0])) >= 0) p[0]++;
            String n = s.substring(start, p[0]);
            try { return Double.parseDouble(n); } catch (Exception e) { return 0.0; }
        }

        static String write(Object o) {
            StringBuilder sb = new StringBuilder();
            write(o, sb);
            return sb.toString();
        }

        static void write(Object o, StringBuilder sb) {
            if (o == null) { sb.append("null"); return; }
            if (o instanceof String) {
                sb.append('"');
                for (char ch : ((String) o).toCharArray()) {
                    switch (ch) {
                        case '"': sb.append("\\\""); break;
                        case '\\': sb.append("\\\\"); break;
                        case '\n': sb.append("\\n"); break;
                        case '\r': sb.append("\\r"); break;
                        case '\t': sb.append("\\t"); break;
                        default:
                            if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                            else sb.append(ch);
                    }
                }
                sb.append('"');
                return;
            }
            if (o instanceof Number || o instanceof Boolean) { sb.append(o.toString()); return; }
            if (o instanceof Map) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    write(String.valueOf(e.getKey()), sb);
                    sb.append(':');
                    write(e.getValue(), sb);
                }
                sb.append('}');
                return;
            }
            if (o instanceof List) {
                sb.append('[');
                boolean first = true;
                for (Object v : (List<?>) o) {
                    if (!first) sb.append(',');
                    first = false;
                    write(v, sb);
                }
                sb.append(']');
                return;
            }
            write(o.toString(), sb);
        }
    }

    // ---------------- 数据加载与保存 ----------------
    @SuppressWarnings("unchecked")
    static List<WordRec> readWordFile(Path f) throws IOException {
        String text = Files.readString(f, StandardCharsets.UTF_8);
        Object parsed = Json.parse(text);
        List<WordRec> out = new ArrayList<>();
        if (parsed instanceof List) {
            for (Object item : (List<Object>) parsed) {
                if (item instanceof Map) out.add(WordRec.fromMap((Map<String, Object>) item));
            }
        }
        return out;
    }

    static void saveWords() throws IOException {
        List<Object> arr = new ArrayList<>();
        for (WordRec w : words) arr.add(w.toMap());
        String text = Json.write(arr);
        Path tmp = DATA_DIR.resolve("words.json.tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, DATA_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tmp, DATA_FILE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void loadWords() throws IOException {
        Files.createDirectories(DATA_DIR);
        if (!Files.exists(DATA_FILE) && Files.exists(SEED_FILE)) {
            Files.copy(SEED_FILE, DATA_FILE);
            System.out.println("已从种子文件初始化词库：" + DATA_FILE);
        }
        words = Files.exists(DATA_FILE) ? readWordFile(DATA_FILE) : new ArrayList<>();
        // 并入种子中的新增词（按词条名不区分大小写判重），不影响已有进度
        if (Files.exists(SEED_FILE)) {
            List<WordRec> seed = readWordFile(SEED_FILE);
            boolean changed = false;
            for (WordRec sw : seed) {
                if (findWord(sw.word) == null && !sw.word.isBlank()) {
                    words.add(sw);
                    changed = true;
                    System.out.println("并入新词：" + sw.word);
                }
            }
            if (changed) saveWords();
        }
        System.out.println("词库共 " + words.size() + " 个词");
    }

    static WordRec findWord(String word) {
        if (word == null) return null;
        for (WordRec w : words) {
            if (w.word.equalsIgnoreCase(word.trim())) return w;
        }
        return null;
    }

    // ---------------- 艾宾浩斯排期 ----------------
    static void applyReview(WordRec w, boolean known, LocalDate today) {
        if (w.learned == null) {
            // 首次学习：从今天起锚定，明天进入第 1 轮复习
            w.learned = today.toString();
            w.stage = 0;
            w.due = today.plusDays(OFFSETS[0]).toString();
            if (!known) w.lapses++;
            return;
        }
        if (!known) {
            // 忘了：打回起点，以今天为新的锚点重走阶梯
            w.lapses++;
            w.learned = today.toString();
            w.stage = 0;
            w.due = today.plusDays(OFFSETS[0]).toString();
            return;
        }
        w.stage++;
        if (w.stage >= OFFSETS.length) {
            w.due = null; // 6 轮全过，毕业
        } else {
            LocalDate anchor = LocalDate.parse(w.learned);
            LocalDate next = anchor.plusDays(OFFSETS[w.stage]);
            if (!next.isAfter(today)) next = today.plusDays(1);
            w.due = next.toString();
        }
    }

    // ---------------- HTTP 工具 ----------------
    static void send(HttpExchange ex, int code, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static void sendJson(HttpExchange ex, int code, Object payload) throws IOException {
        send(ex, code, "application/json; charset=utf-8", Json.write(payload));
    }

    static Map<String, Object> error(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }

    static boolean checkPass(HttpExchange ex) {
        if (PASS == null || PASS.isBlank()) return false;
        String h = ex.getRequestHeaders().getFirst("X-Vocab-Pass");
        return PASS.equals(h);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> readJsonBody(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Object parsed = Json.parse(body);
        if (parsed instanceof Map) return (Map<String, Object>) parsed;
        return new LinkedHashMap<>();
    }

    static Map<String, Object> counts(LocalDate today) {
        int fresh = 0, due = 0, graduated = 0, learning = 0;
        for (WordRec w : words) {
            if (w.learned == null) {
                if (!LocalDate.parse(w.added).isAfter(today)) fresh++;
            } else if (w.due == null) {
                graduated++;
            } else {
                learning++;
                if (!LocalDate.parse(w.due).isAfter(today)) due++;
            }
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("total", words.size());
        c.put("fresh", fresh);
        c.put("due", due);
        c.put("learning", learning);
        c.put("graduated", graduated);
        return c;
    }

    // ---------------- 主程序 ----------------
    public static void main(String[] args) throws Exception {
        loadWords();
        List<String> quotes = Files.readAllLines(Paths.get("quotes.txt"), StandardCharsets.UTF_8);

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            try {
                switch (path) {
                    case "/": {
                        byte[] html = Files.readAllBytes(Paths.get("static", "index.html"));
                        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                        ex.sendResponseHeaders(200, html.length);
                        try (OutputStream os = ex.getResponseBody()) { os.write(html); }
                        break;
                    }
                    case "/vocab": {
                        byte[] html = Files.readAllBytes(Paths.get("static", "vocab.html"));
                        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                        ex.sendResponseHeaders(200, html.length);
                        try (OutputStream os = ex.getResponseBody()) { os.write(html); }
                        break;
                    }
                    case "/api/today": {
                        LocalDate today = LocalDate.now(ZONE);
                        long daysLeft = ChronoUnit.DAYS.between(today, EXAM_DATE);
                        int idx = Math.floorMod(today.getDayOfYear(), quotes.size());
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("date", today.toString());
                        m.put("examDate", EXAM_DATE.toString());
                        m.put("daysLeft", daysLeft);
                        m.put("quote", quotes.get(idx));
                        sendJson(ex, 200, m);
                        break;
                    }
                    case "/api/vocab/today": {
                        LocalDate today = LocalDate.now(ZONE);
                        List<Object> due = new ArrayList<>();
                        List<Object> fresh = new ArrayList<>();
                        synchronized (LOCK) {
                            for (WordRec w : words) {
                                if (w.learned == null) {
                                    if (!LocalDate.parse(w.added).isAfter(today)) fresh.add(w.toMap());
                                } else if (w.due != null && !LocalDate.parse(w.due).isAfter(today)) {
                                    due.add(w.toMap());
                                }
                            }
                        }
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("date", today.toString());
                        m.put("due", due);
                        m.put("fresh", fresh);
                        m.put("counts", counts(today));
                        sendJson(ex, 200, m);
                        break;
                    }
                    case "/api/vocab/all": {
                        List<Object> all = new ArrayList<>();
                        synchronized (LOCK) {
                            for (WordRec w : words) all.add(w.toMap());
                        }
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("words", all);
                        sendJson(ex, 200, m);
                        break;
                    }
                    case "/api/vocab/review": {
                        if (!method.equals("POST")) { sendJson(ex, 405, error("只支持 POST")); break; }
                        if (!checkPass(ex)) { sendJson(ex, 401, error("口令不对或服务器未设置 VOCAB_PASS")); break; }
                        Map<String, Object> body = readJsonBody(ex);
                        String word = str(body.get("word"));
                        boolean known = Boolean.TRUE.equals(body.get("known"));
                        synchronized (LOCK) {
                            WordRec w = findWord(word);
                            if (w == null) { sendJson(ex, 404, error("词不存在：" + word)); break; }
                            applyReview(w, known, LocalDate.now(ZONE));
                            saveWords();
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("word", w.toMap());
                            m.put("counts", counts(LocalDate.now(ZONE)));
                            sendJson(ex, 200, m);
                        }
                        break;
                    }
                    case "/api/vocab/add": {
                        if (!method.equals("POST")) { sendJson(ex, 405, error("只支持 POST")); break; }
                        if (!checkPass(ex)) { sendJson(ex, 401, error("口令不对或服务器未设置 VOCAB_PASS")); break; }
                        Map<String, Object> body = readJsonBody(ex);
                        synchronized (LOCK) {
                            String word = str(body.get("word")).trim();
                            if (word.isBlank()) { sendJson(ex, 400, error("word 不能为空")); break; }
                            if (findWord(word) != null) { sendJson(ex, 409, error("词已存在：" + word)); break; }
                            WordRec w = new WordRec();
                            w.word = word;
                            w.phonetic = str(body.get("phonetic"));
                            w.pos = str(body.get("pos"));
                            w.meaning = str(body.get("meaning"));
                            w.sentence = str(body.get("sentence"));
                            w.sentenceCn = str(body.get("sentenceCn"));
                            w.source = str(body.get("source"));
                            String added = str(body.get("added"));
                            w.added = added.isBlank() ? LocalDate.now(ZONE).toString() : added;
                            words.add(w);
                            saveWords();
                            sendJson(ex, 200, w.toMap());
                        }
                        break;
                    }
                    default:
                        send(ex, 404, "text/plain; charset=utf-8", "Not Found");
                }
            } catch (Exception e) {
                e.printStackTrace();
                try { sendJson(ex, 500, error("服务器内部错误：" + e.getMessage())); } catch (Exception ignored) {}
            }
        });

        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        System.out.println("已启动，监听 " + port + " 端口");
    }
}
