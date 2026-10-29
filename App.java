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
 * 数据文件：DATA_DIR/words.json（默认 data/words.json）。
 * 种子分两种：程序同目录的 words.json 是「公开种子」（演示样例），仅在首次启动、
 * 数据文件不存在时用来初始化词库，老用户不会被它影响；
 * DATA_DIR/seed.json 是「个人种子」（使用者自己的词表，可含真题原句，只存本地 data/、
 * 不进 Git），每次启动时把其中的新增词并入数据文件，不影响已有复习进度。
 * 写接口（复习打卡 / 加词）需要请求头 X-Vocab-Pass 与环境变量 VOCAB_PASS 一致。
 * 当天重练（仿墨墨）：每个词每天的第一次作答决定当天记录与艾宾浩斯排期——
 * 第一次就点「认识」直接按排期走、当天不再出现；第一次点「不认识」则当天记录为
 * 不认识（排期按忘记处理），但这个词会在当天队列里反复出现，直到当天累计
 * 「连续点对 INDAY_NEED 次」才算今日过关（重练中的作答只影响连对计数，
 * 不改排期、不额外增加 lapses）。重练状态存在词条数据里，刷新/重启不丢。
 */
public class App {

    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final LocalDate EXAM_DATE = LocalDate.of(2026, 12, 12);
    // 艾宾浩斯复习节点：学会后第 1、2、4、7、15、30 天各复习一次，共 6 轮
    static final int[] OFFSETS = {1, 2, 4, 7, 15, 30};
    // 当天答错后，需要在当天连续点对几次「认识」才算今日过关（记录仍为不认识）
    static final int INDAY_NEED = 3;

    static final Path DATA_DIR = Paths.get(System.getenv().getOrDefault("DATA_DIR", "data"));
    static final Path DATA_FILE = DATA_DIR.resolve("words.json");
    static final Path SEED_FILE = Paths.get("words.json");
    static final Path PERSONAL_SEED_FILE = DATA_DIR.resolve("seed.json");
    static final String PASS = System.getenv("VOCAB_PASS");

    // 第 7 关「云同步」：PWA/APK 的整包数据（词库+进度）存这里，与背词模块的 words.json 互不干扰。
    static final Path SYNC_FILE = DATA_DIR.resolve("sync-store.json");
    static final Path SYNC_BACKUP_DIR = DATA_DIR.resolve("sync-backups");
    // 第 8 关：云同步多槽位。每个口令一个独立槽位（文件名＝口令的 SHA-256，互不干扰）；
    // 合法口令＝站长 VOCAB_PASS ＋ data/passes.txt 里逐行列出的口令（给同学发口令时往该文件加一行，无需重启）。
    static final Path SYNC_SLOTS_DIR = DATA_DIR.resolve("sync-slots");
    static final Path SYNC_SLOT_BACKUP_DIR = DATA_DIR.resolve("sync-slot-backups");
    static final Path PASSES_FILE = DATA_DIR.resolve("passes.txt");
    static final Path APP_DIR = Paths.get("static", "app");

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
        String day = null;       // 最近一次「当天首答」的日期 yyyy-MM-dd
        String dayResult = "";   // 当天首答结果："pass"=第一次就认识 / "fail"=第一次不认识
        int dayStreak = 0;       // 当天重练中已连续点对的次数（仅 dayResult=fail 时有意义）
        boolean dayDone = false; // 今天是否已完成（首答认识、或重练连对达标）

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
            m.put("day", day);
            m.put("dayResult", dayResult);
            m.put("dayStreak", dayStreak);
            m.put("dayDone", dayDone);
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
            w.day = m.get("day") == null ? null : str(m.get("day"));
            w.dayResult = str(m.get("dayResult"));
            w.dayStreak = num(m.get("dayStreak"));
            w.dayDone = bool(m.get("dayDone"));
            return w;
        }
    }

    static String str(Object o) { return o == null ? "" : o.toString(); }

    static int num(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(str(o)); } catch (Exception e) { return 0; }
    }

    static boolean bool(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        return "true".equals(str(o));
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
        boolean freshInit = !Files.exists(DATA_FILE);
        if (freshInit && Files.exists(SEED_FILE)) {
            Files.copy(SEED_FILE, DATA_FILE);
            System.out.println("已从公开种子初始化词库：" + DATA_FILE);
        }
        words = Files.exists(DATA_FILE) ? readWordFile(DATA_FILE) : new ArrayList<>();
        // 个人种子（data/seed.json）每次启动并入新增词（按词条名不区分大小写判重），不影响已有进度。
        // 公开种子只在首次初始化时用（已在上面复制），老用户不会被演示样例影响。
        if (Files.exists(PERSONAL_SEED_FILE)) {
            mergeSeed(PERSONAL_SEED_FILE);
        }
        System.out.println("词库共 " + words.size() + " 个词");
    }

    static void mergeSeed(Path seedPath) throws IOException {
        List<WordRec> seed = readWordFile(seedPath);
        boolean changed = false;
        for (WordRec sw : seed) {
            if (findWord(sw.word) == null && !sw.word.isBlank()) {
                words.add(sw);
                changed = true;
                System.out.println("并入新词（个人种子）：" + sw.word);
            }
        }
        if (changed) saveWords();
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

    // 是否正处在「当天重练未过关」状态：首答在今天、首答不认识、还没连对达标
    static boolean isDrilling(WordRec w, LocalDate today) {
        return today.toString().equals(w.day) && "fail".equals(w.dayResult) && !w.dayDone;
    }

    /**
     * 处理一次作答。返回 {first, done, streak, need}：
     * first=是不是当天第一次作答；done=这个词今天是否可以收工；
     * streak=当前连对次数；need=过关所需连对次数。
     * 规则：当天首答决定记录与排期（走 applyReview）；首答不认识后进入重练，
     * 重练中的作答只动连对计数，不改排期、不加 lapses，连对达 INDAY_NEED 次过关。
     */
    static Map<String, Object> handleReview(WordRec w, boolean known, LocalDate today) {
        Map<String, Object> r = new LinkedHashMap<>();
        String todayStr = today.toString();
        if (!todayStr.equals(w.day)) {
            // 当天第一次作答：定记录、定排期
            applyReview(w, known, today);
            w.day = todayStr;
            w.dayResult = known ? "pass" : "fail";
            w.dayStreak = 0;
            w.dayDone = known;
            r.put("first", true);
            r.put("done", known);
            r.put("streak", 0);
        } else if (isDrilling(w, today)) {
            // 当天重练中：只记连对
            if (known) {
                w.dayStreak++;
                if (w.dayStreak >= INDAY_NEED) w.dayDone = true;
            } else {
                w.dayStreak = 0; // 重练中又点不认识：连对清零
            }
            r.put("first", false);
            r.put("done", w.dayDone);
            r.put("streak", w.dayStreak);
        } else {
            // 今天已经收工（首答认识、或重练已过关），重复提交直接忽略
            r.put("first", false);
            r.put("done", true);
            r.put("streak", w.dayStreak);
        }
        r.put("need", INDAY_NEED);
        return r;
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

    static String passOf(HttpExchange ex) {
        return ex.getRequestHeaders().getFirst("X-Vocab-Pass");
    }

    /** 同步接口的口令校验：站长口令，或 data/passes.txt 里登记过的口令（每次现读，加口令不用重启）。 */
    static boolean checkSyncPass(String h) {
        if (h == null || h.isBlank()) return false;
        if (PASS != null && !PASS.isBlank() && PASS.equals(h)) return true;
        try {
            if (Files.exists(PASSES_FILE)) {
                for (String line : Files.readAllLines(PASSES_FILE, StandardCharsets.UTF_8)) {
                    String p = line.trim();
                    if (!p.isEmpty() && !p.startsWith("#") && p.equals(h)) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    static String slotKey(String pass) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(pass.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static Path slotFile(String pass) {
        return SYNC_SLOTS_DIR.resolve(slotKey(pass) + ".json");
    }

    /** 旧版单槽数据迁移：把 data/sync-store.json 复制进站长自己的槽位（原文件留作备用）。 */
    static void migrateLegacySync() {
        try {
            if (PASS == null || PASS.isBlank()) return;
            if (!Files.exists(SYNC_FILE)) return;
            Path ownerSlot = slotFile(PASS);
            if (Files.exists(ownerSlot)) return;
            Files.createDirectories(SYNC_SLOTS_DIR);
            Files.copy(SYNC_FILE, ownerSlot);
            System.out.println("已把旧单槽同步数据迁移到站长槽位");
        } catch (Exception e) {
            System.out.println("同步数据迁移失败：" + e.getMessage());
        }
    }

    // 同步接口要被 GitHub Pages（https 源）和 APK（file 源）跨源调用，必须带 CORS 头。
    static void cors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "X-Vocab-Pass, Content-Type");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    }

    /** 读取已存的同步包络 {rev, savedAt, store}；不存在返回 null。 */
    static Map<String, Object> readSyncEnvelope(String pass) throws IOException {
        Path f = slotFile(pass);
        if (!Files.exists(f)) return null;
        Object parsed = Json.parse(Files.readString(f, StandardCharsets.UTF_8));
        if (parsed instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) parsed;
            return m;
        }
        return null;
    }

    /** 写入同步包络到该口令的槽位：先把该槽旧文件备份到 sync-slot-backups/（每槽留最近 10 份），再原子替换。 */
    static void writeSyncEnvelope(String pass, Map<String, Object> envelope) throws IOException {
        Files.createDirectories(SYNC_SLOTS_DIR);
        Path target = slotFile(pass);
        String key = slotKey(pass);
        if (Files.exists(target)) {
            Files.createDirectories(SYNC_SLOT_BACKUP_DIR);
            String stamp = java.time.LocalDateTime.now(ZONE)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Files.copy(target, SYNC_SLOT_BACKUP_DIR.resolve(key + "-" + stamp + ".json"),
                    StandardCopyOption.REPLACE_EXISTING);
            List<Path> backups = new ArrayList<>();
            try (java.util.stream.Stream<Path> st = Files.list(SYNC_SLOT_BACKUP_DIR)) {
                st.filter(p -> p.getFileName().toString().startsWith(key + "-")).forEach(backups::add);
            }
            backups.sort(null);
            while (backups.size() > 10) {
                Files.deleteIfExists(backups.remove(0));
            }
        }
        Path tmp = SYNC_SLOTS_DIR.resolve(key + ".json.tmp");
        Files.writeString(tmp, Json.write(envelope), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String contentTypeOf(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        return "application/octet-stream";
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
                if (!LocalDate.parse(w.due).isAfter(today) || isDrilling(w, today)) due++;
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
        migrateLegacySync();
        List<String> quotes = Files.readAllLines(Paths.get("quotes.txt"), StandardCharsets.UTF_8);

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            try {
                // 跨源预检（浏览器在带 X-Vocab-Pass 的请求前会先发 OPTIONS）。
                if (method.equals("OPTIONS") && path.startsWith("/api/sync/")) {
                    cors(ex);
                    ex.sendResponseHeaders(204, -1);
                    return;
                }
                // /app/ 是背词 PWA 的服务器同源副本：浏览器从 https 页面调 http 接口会被拦，
                // 从服务器自己的 http 页面调则没问题，所以在这里也托管一份相同文件。
                if (path.equals("/app")) {
                    ex.getResponseHeaders().set("Location", "/app/");
                    ex.sendResponseHeaders(302, -1);
                    return;
                }
                if (path.startsWith("/app/")) {
                    String rel = path.substring("/app/".length());
                    if (rel.isBlank()) rel = "index.html";
                    Path f = APP_DIR.resolve(rel).normalize();
                    if (!f.startsWith(APP_DIR) || !Files.isRegularFile(f)) {
                        send(ex, 404, "text/plain; charset=utf-8", "Not Found");
                        return;
                    }
                    byte[] bytes = Files.readAllBytes(f);
                    ex.getResponseHeaders().set("Content-Type", contentTypeOf(f.getFileName().toString()));
                    ex.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
                    return;
                }
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
                                } else if (w.due != null && (!LocalDate.parse(w.due).isAfter(today) || isDrilling(w, today))) {
                                    // 到期词 + 今天首答不认识、还在重练的词（排期虽在明天，今天仍要练完）
                                    due.add(w.toMap());
                                }
                            }
                        }
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("date", today.toString());
                        m.put("due", due);
                        m.put("fresh", fresh);
                        m.put("counts", counts(today));
                        m.put("indayNeed", INDAY_NEED);
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
                            LocalDate today = LocalDate.now(ZONE);
                            Map<String, Object> r = handleReview(w, known, today);
                            saveWords();
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("word", w.toMap());
                            m.put("counts", counts(today));
                            m.putAll(r);
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
                    case "/api/sync/store": {
                        cors(ex);
                        String syncPass = passOf(ex);
                        if (!checkSyncPass(syncPass)) { sendJson(ex, 401, error("口令不对或未在服务器登记")); break; }
                        if (method.equals("GET")) {
                            synchronized (LOCK) {
                                Map<String, Object> env = readSyncEnvelope(syncPass);
                                Map<String, Object> m = new LinkedHashMap<>();
                                if (env == null) {
                                    m.put("exists", false);
                                } else {
                                    m.put("exists", true);
                                    m.putAll(env);
                                }
                                sendJson(ex, 200, m);
                            }
                        } else if (method.equals("POST")) {
                            Map<String, Object> body = readJsonBody(ex);
                            Object storeObj = body.get("store");
                            if (!(storeObj instanceof Map) || !(((Map<?, ?>) storeObj).get("words") instanceof List)) {
                                sendJson(ex, 400, error("数据格式不对：缺少 store.words"));
                                break;
                            }
                            @SuppressWarnings("unchecked")
                            Map<String, Object> storeMap = (Map<String, Object>) storeObj;
                            long rev = 0;
                            Object revObj = body.get("rev");
                            if (revObj instanceof Number) rev = ((Number) revObj).longValue();
                            synchronized (LOCK) {
                                Map<String, Object> env = new LinkedHashMap<>();
                                env.put("rev", rev);
                                env.put("savedAt", java.time.LocalDateTime.now(ZONE).toString());
                                env.put("store", storeMap);
                                writeSyncEnvelope(syncPass, env);
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("ok", true);
                                m.put("rev", rev);
                                m.put("words", ((List<?>) storeMap.get("words")).size());
                                sendJson(ex, 200, m);
                            }
                        } else {
                            sendJson(ex, 405, error("只支持 GET / POST"));
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
