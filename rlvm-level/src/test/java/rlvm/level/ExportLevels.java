package rlvm.level;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test-side reader of the level-related records of a lean4export NDJSON file (or of a
 * {@code *.levels.ndjson} slice made by {@code bench/modules/rlvm-level/extract_levels.py}): name
 * records, level records and {@code levelParams} lists. Not a general export parser.
 *
 * <p>Names become keys whose Java string order is Lean's {@code name::cmp} order: per component a
 * tag (1 numeric, 2 string), the payload (20-digit decimal / UTF-8 bytes as chars) and a 0
 * terminator.
 */
final class ExportLevels {
    private static final Pattern NAME_ID = Pattern.compile("\"in\":(\\d+)");
    private static final Pattern PRE = Pattern.compile("\"pre\":(\\d+)");
    private static final Pattern NUM = Pattern.compile("\"i\":(\\d+)");
    private static final Pattern STR = Pattern.compile("\"str\":(\"(?:[^\"\\\\]|\\\\.)*\")");
    private static final Pattern LEVEL = Pattern.compile(
            "^\\{\"il\":(\\d+),\"(succ|param|max|imax)\":(?:(\\d+)|\\[(\\d+),(\\d+)\\])\\}$");
    private static final Pattern LPARAMS = Pattern.compile("\"levelParams\":\\[([0-9,]*)\\]");

    final Lv.Names names = new Lv.Names();
    final LevelStore store = new LevelStore(names);
    /** Store ids of the level records, in file order. */
    final List<Integer> levels = new ArrayList<>();
    /** Distinct levelParams lists as interned name lists of {@link #store}. */
    final List<Integer> paramLists = new ArrayList<>();
    private final Map<Long, String> nameKey = new HashMap<>();
    private final Map<Long, Integer> levelOfExportId = new HashMap<>();

    static ExportLevels load(Path file) throws IOException {
        ExportLevels e = new ExportLevels();
        e.nameKey.put(0L, "");
        e.levelOfExportId.put(0L, LevelStore.ZERO);
        Map<String, Boolean> seenLists = new HashMap<>();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = compact(line);
                if (line.startsWith("{\"in\":")) {
                    e.readName(line);
                } else if (line.startsWith("{\"il\":")) {
                    e.readLevel(line);
                } else if (line.contains("\"il\":")) {
                    // a level record in a key order this reader does not know: do not skip it silently
                    throw new IllegalArgumentException("unsupported level record: " + line);
                } else if (line.contains("\"levelParams\":[")) {
                    Matcher m = LPARAMS.matcher(line);
                    while (m.find()) {
                        if (seenLists.put(m.group(1), Boolean.TRUE) == null) {
                            e.readParamList(m.group(1));
                        }
                    }
                }
            }
        }
        return e;
    }

    /** Removes blanks outside JSON strings (hand-written test files are not in compact form). */
    static String compact(String line) {
        if (line.indexOf(' ') < 0 && line.indexOf('\t') < 0) {
            return line;
        }
        StringBuilder sb = new StringBuilder(line.length());
        boolean inString = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inString) {
                sb.append(c);
                if (c == '\\' && i + 1 < line.length()) {
                    sb.append(line.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
                sb.append(c);
            } else if (c != ' ' && c != '\t') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private void readName(String line) {
        Matcher id = NAME_ID.matcher(line);
        Matcher pre = PRE.matcher(line);
        if (!id.find() || !pre.find()) {
            throw new IllegalArgumentException("bad name record: " + line);
        }
        String prefix = nameKey.get(Long.parseLong(pre.group(1)));
        if (prefix == null) {
            throw new IllegalArgumentException("name prefix not defined: " + line);
        }
        StringBuilder key = new StringBuilder(prefix);
        Matcher num = NUM.matcher(line);
        if (line.contains("\"num\":") && num.find()) {
            String digits = num.group(1);
            key.append('\u0001');
            for (int i = digits.length(); i < 20; i++) {
                key.append('0');
            }
            key.append(digits);
        } else {
            // the component is the second "str" key (the first is the record kind)
            int inner = line.indexOf("\"str\":", line.indexOf("\"str\":{") + 7);
            Matcher str = STR.matcher(line);
            if (inner < 0 || !str.find(inner)) {
                throw new IllegalArgumentException("bad name record: " + line);
            }
            key.append('\u0002');
            for (byte b : unescape(str.group(1)).getBytes(StandardCharsets.UTF_8)) {
                key.append((char) (b & 0xFF));
            }
        }
        key.append('\u0000');
        nameKey.put(Long.parseLong(id.group(1)), key.toString());
    }

    private static String unescape(String json) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < json.length() - 1; i++) {
            char c = json.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            c = json.charAt(++i);
            switch (c) {
                case 'n':
                    sb.append('\n');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'u':
                    sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                    i += 4;
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }

    private int nameId(long exportId) {
        String key = nameKey.get(exportId);
        if (key == null) {
            throw new IllegalArgumentException("name not defined: " + exportId);
        }
        return names.id(key);
    }

    private int level(String exportId) {
        Integer l = levelOfExportId.get(Long.parseLong(exportId));
        if (l == null) {
            throw new IllegalArgumentException("level not defined: " + exportId);
        }
        return l;
    }

    private void readLevel(String line) {
        Matcher m = LEVEL.matcher(line);
        if (!m.matches()) {
            throw new IllegalArgumentException("bad level record: " + line);
        }
        int l;
        switch (m.group(2)) {
            case "succ":
                l = store.succ(level(m.group(3)));
                break;
            case "param":
                l = store.param(nameId(Long.parseLong(m.group(3))));
                break;
            case "max":
                l = store.max(level(m.group(4)), level(m.group(5)));
                break;
            default:
                l = store.imax(level(m.group(4)), level(m.group(5)));
                break;
        }
        levelOfExportId.put(Long.parseLong(m.group(1)), l);
        levels.add(l);
    }

    private void readParamList(String csv) {
        if (csv.isEmpty()) {
            return;
        }
        String[] parts = csv.split(",");
        int[] buf = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            buf[i] = nameId(Long.parseLong(parts[i]));
        }
        paramLists.add(store.internList(buf, 0, buf.length));
    }
}
