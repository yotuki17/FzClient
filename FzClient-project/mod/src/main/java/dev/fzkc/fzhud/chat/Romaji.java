package dev.fzkc.fzhud.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.render.R;
import dev.fzkc.fzhud.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiTextField;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Romaji typed in the chat goes out as Japanese: "kyouhaiitenkidane" -> "今日はいい天気だね".
 * <p>
 * Romaji becomes hiragana here, on this PC. Kanji come from Google's Japanese conversion (the one Google's
 * transliteration uses), only when that option is on. A grey line above the chat box shows what will be sent
 * before you press Enter.
 * <p>
 * Left alone: words that are not romaji ("gg", "nice"), common English words that happen to read as romaji
 * ("hi", "no", "game"), text where most of it is not romaji, anything with a capital letter or a character that
 * is not plain ASCII (so typing one capital letter, or already typing Japanese, sends the text as it is), and
 * commands other than the ones that carry a chat message (/pc, /gc, /ac, /r, /msg name ...).
 */
public class Romaji {
    // ------------------------------------------------------------------ romaji -> hiragana

    private static final Map<String, String> KANA = new HashMap<>();
    private static void put(String romaji, String kana) { KANA.put(romaji, kana); }
    static {
        String[] v = {"a", "i", "u", "e", "o"};
        String[] base = {"あ", "い", "う", "え", "お"};
        for (int i = 0; i < 5; i++) put(v[i], base[i]);
        // consonant rows: romaji consonant, kana for a i u e o
        String[][] rows = {
                {"k", "かきくけこ"}, {"g", "がぎぐげご"}, {"s", "さしすせそ"}, {"z", "ざじずぜぞ"}, {"t", "たちつてと"},
                {"d", "だぢづでど"}, {"n", "なにぬねの"}, {"h", "はひふへほ"}, {"b", "ばびぶべぼ"}, {"p", "ぱぴぷぺぽ"},
                {"m", "まみむめも"}, {"r", "らりるれろ"}, {"l", "ぁぃぅぇぉ"}, {"x", "ぁぃぅぇぉ"}};
        for (String[] r : rows) for (int i = 0; i < 5; i++) put(r[0] + v[i], String.valueOf(r[1].charAt(i)));
        // little ya / yu / yo after the i-sound of a row
        String[][] yRows = {{"ky", "き"}, {"gy", "ぎ"}, {"sy", "し"}, {"sh", "し"}, {"zy", "じ"}, {"jy", "じ"}, {"j", "じ"},
                {"ty", "ち"}, {"cy", "ち"}, {"ch", "ち"}, {"dy", "ぢ"}, {"ny", "に"}, {"hy", "ひ"}, {"by", "び"},
                {"py", "ぴ"}, {"my", "み"}, {"ry", "り"}};
        for (String[] r : yRows) {
            put(r[0] + "a", r[1] + "ゃ");
            put(r[0] + "u", r[1] + "ゅ");
            put(r[0] + "o", r[1] + "ょ");
            put(r[0] + "e", r[1] + "ぇ");
        }
        put("kyi", "きぃ"); put("syi", "しぃ"); put("tyi", "ちぃ"); put("nyi", "にぃ"); put("hyi", "ひぃ");
        put("shi", "し"); put("ji", "じ"); put("chi", "ち"); put("tsu", "つ"); put("fu", "ふ");
        put("fa", "ふぁ"); put("fi", "ふぃ"); put("fe", "ふぇ"); put("fo", "ふぉ"); put("fyu", "ふゅ");
        put("ya", "や"); put("yu", "ゆ"); put("yo", "よ"); put("ye", "いぇ"); put("yi", "い");
        put("wa", "わ"); put("wo", "を"); put("wi", "うぃ"); put("we", "うぇ"); put("wu", "う");
        put("va", "ゔぁ"); put("vi", "ゔぃ"); put("vu", "ゔ"); put("ve", "ゔぇ"); put("vo", "ゔぉ");
        put("thi", "てぃ"); put("thu", "てゅ"); put("dhi", "でぃ"); put("dhu", "でゅ"); put("twu", "とぅ"); put("dwu", "どぅ");
        put("tsa", "つぁ"); put("tsi", "つぃ"); put("tse", "つぇ"); put("tso", "つぉ");
        put("lya", "ゃ"); put("lyu", "ゅ"); put("lyo", "ょ"); put("xya", "ゃ"); put("xyu", "ゅ"); put("xyo", "ょ");
        put("ltu", "っ"); put("xtu", "っ"); put("ltsu", "っ"); put("lwa", "ゎ"); put("xwa", "ゎ");
        put("nn", "ん"); put("xn", "ん");
    }

    private static boolean vowel(char c) { return c == 'a' || c == 'i' || c == 'u' || c == 'e' || c == 'o'; }

    /** One word of lower case letters (and - ' ) as hiragana, or null when it is not romaji. */
    static String word(String w) { return word(w, false); }

    /**
     * strict (other people's chat): no little kana typed with l / x at all. A doubled l or x is never a little っ
     * either way ("hello" would read as へっぉ).
     */
    static String word(String w, boolean strict) {
        StringBuilder out = new StringBuilder();
        int i = 0, n = w.length();
        while (i < n) {
            char c = w.charAt(i);
            if (c == '-') { out.append('ー'); i++; continue; }
            if (c == '\'') return null;   // only right after an n (handled there)
            char next = i + 1 < n ? w.charAt(i + 1) : 0;
            if (c == 'n') {
                if (next == '\'') { out.append('ん'); i += 2; continue; }
                if (next == 'n') {
                    // "nn" then a vowel or y: the first n is ん and the second starts な / にゃ ("konnichiha")
                    char after = i + 2 < n ? w.charAt(i + 2) : 0;
                    if (vowel(after) || after == 'y') { out.append('ん'); i++; continue; }
                    out.append('ん'); i += 2; continue;
                }
                if (!vowel(next) && next != 'y') { out.append('ん'); i++; continue; }
            }
            // Hepburn writes ん before b / p / m as m: "gambatte", "sempai", "kampai"
            if (c == 'm' && (next == 'b' || next == 'p')) { out.append('ん'); i++; continue; }
            if (c == 'l' || c == 'x') {
                if (strict || next == c) return null;
            }
            // a doubled consonant is a little っ: "kitte", "matcha"
            if (!vowel(c) && c != 'n' && (next == c || (c == 't' && next == 'c' && i + 2 < n && w.charAt(i + 2) == 'h'))) {
                out.append('っ'); i++; continue;
            }
            String k = null;
            int len = 0;
            for (int l = Math.min(4, n - i); l >= 1 && k == null; l--) {
                k = KANA.get(w.substring(i, i + l));
                len = l;
            }
            if (k == null) return null;
            out.append(k);
            i += len;
        }
        return out.toString();
    }

    /** English words (and chat words) that would also read as romaji: never changed. */
    private static final Set<String> ENGLISH = new HashSet<>(Arrays.asList(
            "a", "i", "o", "u", "e", "hi", "no", "so", "go", "to", "do", "me", "we", "be", "he", "she", "ya", "yo", "ye",
            "mine", "gone", "done", "take", "make", "came", "game", "games", "name", "time", "tie", "see", "nope",
            "rare", "wise", "wide", "side", "ride", "home", "sure", "pure", "cute", "mute", "use", "bro", "pro", "noob",
            "sumo", "bridge", "zone", "tone", "bone", "note", "vote", "hope", "rope", "joke", "woke", "wake",
            "bake", "mate", "date", "hate", "rate", "gate", "fire", "wire", "hire", "tire", "dire", "more", "bore",
            "core", "tore", "wore", "nose", "rose", "pose", "dose", "pipe", "ripe", "wipe", "type", "hype",
            "mode", "code", "node", "rude", "dude", "nuke", "duke", "fake", "safe", "save", "wave", "cave", "move",
            "love", "dove", "mike", "bike", "hike", "like", "nice", "rice", "dice", "lmao", "ez", "gg", "op", "afk",
            "omg", "kek", "lol", "wow", "yes", "yeah", "ok", "okay", "who", "why", "how", "now", "new", "few",
            "same", "fine", "haha", "hahaha", "hehe", "hehehe", "you", "your", "youre", "are", "here", "one", "anyone",
            "someone", "gonna", "wanna", "mean", "die", "too", "tea", "sea", "bee", "house", "mouse", "dome", "pope",
            "mama", "papa", "nana", "baby", "body", "lady", "tiny", "pony", "tuna", "soda", "piano", "radio", "video",
            "zero", "hero", "demo", "memo", "menu", "emu", "ego", "amigo", "mango", "tango", "banana", "tomato", "potato",
            "minute", "remote", "debate", "senate", "resume", "refuse", "decide", "genome", "karaoke", "agenda",
            "bye", "hey", "yay", "yo", "meme", "memes", "nose", "dose", "rose", "sane", "lane", "pane", "cane", "mane",
            "wine", "dine", "nine", "line", "pine", "vine", "tune", "dune", "june", "rune", "mono", "dino", "nano", "kinda"));

    /** Common Japanese chat words without any of the sounds {@link #JAPANESE_SOUND} looks for. */
    private static final Set<String> JAPANESE_WORDS = new HashSet<>(Arrays.asList(
            "nani", "kusa", "sugoi", "sugee", "yabai", "yaba", "maji", "mazi", "iya", "hai", "iie", "dame", "baka",
            "kawaii", "umai", "heta", "otsu", "gomen", "sumimasen", "arigato", "naruhodo", "kore", "sore", "dore",
            "nanika", "dareka", "daremo", "zenzen", "mada", "mou"));
    /**
     * Sounds English rarely has but romaji does: tsu, shi, chi, ji, fu, kya-type syllables, ou / uu / ei, ん before a
     * consonant, a doubled consonant (っ), and common endings (desu, masu, nai, tta...).
     */
    private static final java.util.regex.Pattern JAPANESE_SOUND = java.util.regex.Pattern.compile(
            "tsu|shi|chi|ji|fu|[kgnhbpmr]y[auo]|sh[auo]|ch[auo]|j[auo]|ou|uu|ei|n[bcdfghjkmprstwz]|m[bp]"
                    + "|([kstpgdbz])\\1|desu|masu|nai$|tta$|tte$|kute$|shou|deshou|darou|kana$|yone$|dayo$|dane$");

    /** A word typed by someone else that is very likely romaji, not an English word. */
    private static boolean japaneseLooking(String core) {
        return JAPANESE_WORDS.contains(core) || core.length() >= 7 || JAPANESE_SOUND.matcher(core).find();
    }

    /** What to send for this text, in parts: {text, "kana" or null}. Null when nothing changes. */
    static List<String[]> parts(String text) { return parts(text, false); }

    /**
     * incoming: a message someone else sent (no commands; capitals are fine, "Arigatou" is read as "arigatou";
     * stricter about what counts as romaji, since a wrong guess there changes what another person said: at least
     * one word must look clearly Japanese).
     */
    static List<String[]> parts(String text, boolean incoming) {
        // a line that is Japanese without them: the English-looking words in it are Japanese too ("suru no")
        return build(text, incoming, false) == null ? null : build(text, incoming, true);
    }

    private static boolean letter(char c) { return (c >= 'a' && c <= 'z') || c == '\'' || c == '-'; }

    private static String wide(String s) {
        return s.replace('.', '。').replace(',', '、').replace('!', '！').replace('?', '？').replace('~', '～');
    }

    /**
     * One word as hiragana; digits and punctuation inside it stay ("3ji" -> 3じ, "sou,demo" -> そう、でも).
     * Null when a run of letters in it is not romaji.
     */
    private static String token(String core, boolean strict) {
        StringBuilder out = new StringBuilder();
        int i = 0, n = core.length();
        while (i < n) {
            int j = i;
            boolean isLetter = letter(core.charAt(i));
            while (j < n && letter(core.charAt(j)) == isLetter) j++;
            String run = core.substring(i, j);
            if (isLetter) {
                String k = word(run, strict);
                if (k == null) return null;
                out.append(k);
            } else {
                out.append(wide(run));
            }
            i = j;
        }
        return out.toString();
    }

    private static List<String[]> build(String text, boolean incoming, boolean englishToo) {
        if (text == null || text.trim().isEmpty()) return null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 126 || c < 32) return null;      // already Japanese, or something else
        }
        String head = "", body = text;
        if (incoming) body = text.toLowerCase(java.util.Locale.ROOT);
        else if (text.startsWith("/")) {
            int words = messageAfter(text);
            if (words < 0) return null;
            int at = 0;
            for (int w = 0; w < words; w++) {
                at = text.indexOf(' ', at);
                if (at < 0) return null;
                while (at < text.length() && text.charAt(at) == ' ') at++;
            }
            head = text.substring(0, at);
            body = text.substring(at);
        }
        // a capital letter in the message (not in "/msg Name"): send as typed
        for (int i = 0; i < body.length(); i++) if (body.charAt(i) >= 'A' && body.charAt(i) <= 'Z') return null;
        List<String[]> out = new ArrayList<>();
        if (!head.isEmpty()) out.add(new String[]{head, null});
        int letters = 0, converted = 0, longest = 0;
        boolean japanese = false;
        StringBuilder plain = new StringBuilder();
        for (String tok : body.split("(?<= )|(?= )")) {
            if (tok.equals(" ")) { plain.append(tok); continue; }
            // trailing punctuation stays outside the word: "arigatou!" -> ありがとう！
            int end = tok.length();
            while (end > 0 && ".,!?~".indexOf(tok.charAt(end - 1)) >= 0) end--;
            String core = tok.substring(0, end), tail = tok.substring(end);
            int lettersHere = 0;
            for (char c : core.toCharArray()) if (c >= 'a' && c <= 'z') lettersHere++;
            letters += lettersHere;
            String kana = lettersHere == 0 || (!englishToo && ENGLISH.contains(core)) ? null : token(core, incoming);
            if (kana == null) {
                plain.append(tok);
                continue;
            }
            converted += lettersHere;
            longest = Math.max(longest, lettersHere);
            if (!ENGLISH.contains(core) && japaneseLooking(core.replaceAll("[^a-z]", ""))) japanese = true;
            if (plain.length() > 0) { out.add(new String[]{plain.toString(), null}); plain.setLength(0); }
            out.add(new String[]{kana, "kana"});
            if (!tail.isEmpty()) out.add(new String[]{wide(tail), null});
        }
        if (plain.length() > 0) out.add(new String[]{plain.toString(), null});
        // mostly not romaji ("i am good"), or only a tiny word: leave it
        if (converted == 0 || converted * 10 < letters * (incoming ? 8 : 6) || longest < (incoming ? 4 : 3)) return null;
        if (incoming && !japanese) return null;   // "same", "haha", "are you here": English that reads as romaji
        // spaces between Japanese words are dropped ("kyou ha" -> "きょうは")
        for (int i = 1; i + 1 < out.size(); i++) {
            if (out.get(i)[1] == null && out.get(i)[0].trim().isEmpty() && out.get(i - 1)[1] != null && out.get(i + 1)[1] != null) {
                out.get(i)[0] = "";
            }
        }
        // join neighbouring Japanese pieces into one, so Google sees the whole phrase ("きょう"+"は" -> 今日は)
        List<String[]> merged = new ArrayList<>();
        for (String[] s : out) {
            if (s[0].isEmpty()) continue;
            String[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && last[1] != null && s[1] != null) last[0] += s[0];
            else merged.add(new String[]{s[0], s[1]});
        }
        return merged;
    }

    /** Words before the message in a command that carries a chat message, or -1 for other commands. */
    private static int messageAfter(String text) {
        int sp = text.indexOf(' ');
        if (sp < 0) return -1;
        String cmd = text.substring(1, sp).toLowerCase();
        switch (cmd) {
            case "pc": case "gc": case "ac": case "oc": case "r": case "reply": case "shout": case "cc": case "achat":
                return 1;
            case "msg": case "w": case "tell": case "message": case "whisper": case "m": case "t":
                return 2;
            default:
                return -1;
        }
    }

    /** The hiragana version (no kanji), or null when nothing changes. */
    static String kana(String text) {
        List<String[]> p = parts(text);
        if (p == null) return null;
        StringBuilder sb = new StringBuilder();
        for (String[] s : p) sb.append(s[0]);
        return sb.toString();
    }

    // ------------------------------------------------------------------ kanji (Google)

    private static ExecutorService worker(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    /** Previews and other people's lines. */
    private static final ExecutorService POOL = worker("FzClient romaji");
    /** A message you pressed Enter on: never waits behind the others. */
    private static final ExecutorService SEND = worker("FzClient romaji send");
    /** typed text -> what to send with kanji (small, newest kept). */
    private static final Map<String, String> CACHE = new LinkedHashMap<String, String>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > 200; }
    };
    /** When Google last failed (offline, rate limit): no new requests for a while, the hiragana is used. */
    private static volatile long failedAt;
    private static final long FAIL_PAUSE_MS = 20000;
    private static final Set<String> asking = new HashSet<>();

    static String cached(String text) {
        synchronized (CACHE) { return CACHE.get(text); }
    }

    /** Google failed a moment ago: don't ask again yet (this would otherwise repeat every frame). */
    static boolean googleResting() {
        return System.currentTimeMillis() - failedAt < FAIL_PAUSE_MS;
    }

    /** Hiragana -> the first candidate of every piece Google splits it into (numbers stay as typed). */
    private static String google(String hira) throws Exception {
        String url = "https://www.google.com/transliterate?langpair=ja-Hira%7Cja&text=" + URLEncoder.encode(hira, "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(2500);
        c.setReadTimeout(2500);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        try (InputStream in = c.getInputStream()) {
            JsonArray root = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
            StringBuilder sb = new StringBuilder();
            for (JsonElement seg : root) {
                JsonArray a = seg.getAsJsonArray();
                String reading = a.get(0).getAsString();
                JsonArray cands = a.get(1).getAsJsonArray();
                boolean kana = false;
                for (char ch : reading.toCharArray()) if (ch >= 0x3040 && ch <= 0x30FF) kana = true;
                // "3" would come back as "３", "、" as it is: only pieces with kana get a candidate
                sb.append(kana && cands.size() > 0 ? cands.get(0).getAsString() : reading);
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    /** With kanji (asks Google; on any trouble the hiragana, and Google rests a while). Not on the game thread. */
    private static String withKanji(String text, List<String[]> parts) {
        StringBuilder sb = new StringBuilder();
        boolean ok = true;
        for (String[] p : parts) {
            if (p[1] == null || !ok) { sb.append(p[0]); continue; }
            try {
                sb.append(google(p[0]));
            } catch (Exception e) {
                ok = false;
                failedAt = System.currentTimeMillis();
                sb.append(p[0]);
            }
        }
        String out = sb.toString();
        if (ok) synchronized (CACHE) { CACHE.put(text, out); }
        return out;
    }

    static void ask(final String text, final List<String[]> parts, final Runnable then) { ask(text, parts, then, false); }

    /** Asks Google in the background; then (if any) runs on the game thread afterwards, answer or not. */
    static void ask(final String text, final List<String[]> parts, final Runnable then, boolean urgent) {
        if (googleResting() && cached(text) == null) {
            if (then != null) Minecraft.getMinecraft().addScheduledTask(then);
            return;
        }
        synchronized (asking) {
            if (!asking.add(text) && then == null) return;
        }
        (urgent ? SEND : POOL).execute(() -> {
            try {
                if (cached(text) == null && !googleResting()) withKanji(text, parts);
            } finally {
                synchronized (asking) { asking.remove(text); }
                if (then != null) Minecraft.getMinecraft().addScheduledTask(then);
            }
        });
    }

    // ------------------------------------------------------------------ the chat box

    /** Longest Enter is held back waiting for kanji; then the hiragana goes out. */
    private static final long SEND_WAIT_MS = 2000;

    private String shownText = "";
    private long changedAt;
    /** Text waiting for Google after Enter (Enter was held back), or null. */
    private String sending;
    private long sendingAt;
    private GuiChat sendingGui;
    private GuiTextField sendingField;

    private static boolean on() { return Modules.chat.enabled && Modules.romajiOn.get(); }

    @SubscribeEvent
    public void onDraw(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (!on() || !(event.gui instanceof GuiChat)) return;
        if (sending != null && System.currentTimeMillis() - sendingAt > SEND_WAIT_MS) finish(sending);
        GuiTextField tf = JapaneseInput.chatField((GuiChat) event.gui);
        if (tf == null) return;
        String text = tf.getText();
        if (!text.equals(shownText)) {
            shownText = text;
            changedAt = System.currentTimeMillis();
        }
        String full = ChatExtras.outgoing(text);
        List<String[]> p = parts(full);
        if (p == null) return;
        String line = null;
        boolean waiting = false;
        if (Modules.romajiKanji.get()) {
            line = cached(full);
            if (line == null && !googleResting()) {
                waiting = true;
                if (System.currentTimeMillis() - changedAt > 250) ask(full, p, null);
            }
        }
        if (line == null) line = kana(full);
        String show = "→ " + line + (waiting ? "  …" : "");
        String hint = Lang.t("大文字を入れるとそのまま送信");
        float w = R.width(show) + 12f + R.width(hint) + 8f;
        // above the chat tabs when they are showing
        float x = 2f, y = event.gui.height - 27f - (ChatExtras.tabsOn() ? 12f : 0f);
        R.rounded(x, y, Math.min(w, event.gui.width - 4f), 11f, 2f, 0xC0101A1E);
        R.text(show, x + 4f, y + 2f, 0xFFE6EEF0);
        R.text(hint, x + 4f + R.width(show) + 12f, y + 2f, 0xFF6F858A);
    }

    /** After ChatExtras (HIGH) has put the channel in front, so the text here is what really goes out. */
    @SubscribeEvent
    public void onKey(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (!on() || !(event.gui instanceof GuiChat)) return;
        if (!Keyboard.getEventKeyState()) return;
        int key = Keyboard.getEventKey();
        if (key != Keyboard.KEY_RETURN && key != Keyboard.KEY_NUMPADENTER) return;
        final GuiChat gui = (GuiChat) event.gui;
        final GuiTextField tf = JapaneseInput.chatField(gui);
        if (tf == null) return;
        final String text = tf.getText();
        if (sending != null) { event.setCanceled(true); return; }   // already on its way
        final List<String[]> p = parts(text);
        if (p == null) return;
        String now = !Modules.romajiKanji.get() || googleResting() ? kana(text) : cached(text);
        if (now == null) now = Modules.romajiKanji.get() ? null : kana(text);
        if (now != null) {
            tf.setText(now);   // vanilla Enter sends it
            return;
        }
        // kanji not here yet: hold Enter back and send when Google answers (or the hiragana after SEND_WAIT_MS)
        event.setCanceled(true);
        sending = text;
        sendingAt = System.currentTimeMillis();
        sendingGui = gui;
        sendingField = tf;
        ask(text, p, () -> finish(text), true);
    }

    /** Sends the held message (kanji if they are here, else hiragana). Game thread; only once per message. */
    private void finish(String text) {
        if (sending == null || !sending.equals(text)) return;   // already sent (timeout) or another message
        GuiChat gui = sendingGui;
        GuiTextField tf = sendingField;
        sending = null;
        sendingGui = null;
        sendingField = null;
        Minecraft mc = Minecraft.getMinecraft();
        String out = cached(text);
        if (out == null) out = kana(text);
        if (out == null || mc.thePlayer == null) return;
        boolean open = mc.currentScreen == gui;
        if (open && !text.equals(tf.getText())) return;   // changed after Enter: they are writing something else
        if (out.length() > 100) out = out.substring(0, 100);
        // what GuiScreen.sendChatMessage does: history, client commands (/tr ...), else to the server.
        // Also when the chat was closed meanwhile: the message was meant to be sent.
        mc.ingameGUI.getChatGUI().addToSentMessages(out);
        if (net.minecraftforge.client.ClientCommandHandler.instance.executeCommand(mc.thePlayer, out) == 0) {
            mc.thePlayer.sendChatMessage(out);
        }
        if (open) mc.displayGuiScreen(null);
    }
}
