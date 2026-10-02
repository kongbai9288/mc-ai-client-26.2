package com.kongbai.mcai.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 工具。
 *
 * <p>本项目刻意不引入 Gson / Jackson：fabric-loader 不提供 JSON 库，
 * Minecraft 自带 Gson 但不同版本位置不稳，且我们不依赖 Fabric API。
 * 这里只实现够用的子集：构建 + 解析，保证零外部依赖。
 */
public final class Json {

    public static final class Obj {
        private final Map<String, Object> m = new LinkedHashMap<>();

        public Obj put(String k, Object v) { m.put(k, v); return this; }
        public Obj put(String k, String v) { m.put(k, v); return this; }
        public Obj put(String k, int v) { m.put(k, v); return this; }
        public Obj put(String k, long v) { m.put(k, v); return this; }
        public Obj put(String k, double v) { m.put(k, v); return this; }
        public Obj put(String k, boolean v) { m.put(k, v); return this; }
        public Map<String, Object> raw() { return m; }

        @Override public String toString() { return write(this); }
    }

    public static final class Arr {
        private final List<Object> l = new ArrayList<>();
        public Arr add(Object v) { l.add(v); return this; }
        public List<Object> raw() { return l; }
        @Override public String toString() { return write(this); }
    }

    public static Obj obj() { return new Obj(); }
    public static Arr arr() { return new Arr(); }

    // ---------------------------------------------------------------- 序列化

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder(256);
        writeTo(sb, o);
        return sb.toString();
    }

    private static void writeTo(StringBuilder sb, Object o) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof Obj v) { writeMap(sb, v.raw()); return; }
        if (o instanceof Arr v) { writeList(sb, v.raw()); return; }
        if (o instanceof Map<?, ?> v) { writeMap(sb, v); return; }
        if (o instanceof List<?> v) { writeList(sb, v); return; }
        if (o instanceof String s) { writeString(sb, s); return; }
        if (o instanceof Boolean || o instanceof Number) { sb.append(o); return; }
        writeString(sb, String.valueOf(o));
    }

    private static void writeMap(StringBuilder sb, Map<?, ?> m) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(':');
            writeTo(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeList(StringBuilder sb, List<?> l) {
        sb.append('[');
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(',');
            writeTo(sb, l.get(i));
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------- 反序列化

    /** 解析 JSON 文本为 Map / List / String / Double / Boolean / null。 */
    public static Object parse(String s) {
        P p = new P(s);
        p.ws();
        Object v = p.value();
        p.ws();
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObj(String s) {
        Object o = parse(s);
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    public static String str(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return v instanceof String s ? s : def;
    }

    public static int integer(Map<String, Object> m, String k, int def) {
        Object v = m.get(k);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; } }
        return def;
    }

    public static double dbl(Map<String, Object> m, String k, double def) {
        Object v = m.get(k);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; } }
        return def;
    }

    public static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object v = m.get(k);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return def;
    }

    private static final class P {
        private final String s;
        private int i;
        P(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        char peek() { return i < s.length() ? s.charAt(i) : 0; }
        char next() { return s.charAt(i++); }

        Object value() {
            char c = peek();
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> { lit("true"); yield Boolean.TRUE; }
                case 'f' -> { lit("false"); yield Boolean.FALSE; }
                case 'n' -> { lit("null"); yield null; }
                default -> number();
            };
        }

        void lit(String w) {
            if (!s.startsWith(w, i)) throw err("期望 " + w);
            i += w.length();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            next(); // {
            ws();
            if (peek() == '}') { next(); return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                if (next() != ':') throw err("期望 :");
                ws();
                m.put(k, value());
                ws();
                char c = next();
                if (c == '}') return m;
                if (c != ',') throw err("期望 , 或 }");
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            next(); // [
            ws();
            if (peek() == ']') { next(); return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = next();
                if (c == ']') return l;
                if (c != ',') throw err("期望 , 或 ]");
            }
        }

        String string() {
            if (next() != '"') throw err("期望字符串");
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = next();
                switch (e) {
                    case '"'  -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/'  -> sb.append('/');
                    case 'b'  -> sb.append('\b');
                    case 'f'  -> sb.append('\f');
                    case 'n'  -> sb.append('\n');
                    case 'r'  -> sb.append('\r');
                    case 't'  -> sb.append('\t');
                    case 'u'  -> {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw err("非法转义 \\" + e);
                }
            }
        }

        Object number() {
            int st = i;
            while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            String t = s.substring(st, i);
            if (t.isEmpty()) throw err("期望数字");
            try {
                if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
                    return Long.parseLong(t);
                }
                return Double.parseDouble(t);
            } catch (NumberFormatException e) {
                throw err("非法数字 " + t);
            }
        }

        RuntimeException err(String msg) {
            return new IllegalArgumentException("JSON 解析失败 @" + i + ": " + msg);
        }
    }
}
