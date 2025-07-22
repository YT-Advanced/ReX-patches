// These java classes are taken from: https://github.com/felipeucelli/JavaTube/blob/ec9011fa2ed584b867d276e683c421059b87bec5/src/main/java/com/github/felipeucelli/javatube/JsInterpreter.java
// This code is based on jsinterp.py, available at "https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/jsinterp.py"
// TODO: Scripts written in python have been converted to Java to run javascript.
//       Migrate to 'eclipsesource/J2V8' or 'mozilla/rhino' for better performance.

package app.revanced.extension.shared.innertube.utils;

import android.annotation.TargetApi;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@TargetApi(26)
class LocalNameSpace extends java.util.AbstractMap<String, Object> {
    private final Map<String, Object> maps;

    public LocalNameSpace(Map<String, Object> maps) {
        this.maps = maps;
    }

    public Object getValue(String key) {
        return maps.get(key);
    }

    public Map<String, Object> getAll() {
        return maps;
    }

    public LocalNameSpace newChild(Map<String, Object> obj) {
        // MODIFIED: Tạo một map mới bằng cách sao chép map của cha để ngăn chặn các hiệu ứng phụ.
        // Việc này đảm bảo rằng scope con không thể vô tình thay đổi scope cha.
        // Triển khai ban đầu đã sửa đổi trực tiếp map của cha.
        Map<String, Object> newMap = new HashMap<>(this.maps);
        newMap.putAll(obj);
        return new LocalNameSpace(newMap);
    }

    @NonNull
    @Override
    public String toString() {
        // MODIFIED: Sử dụng StringBuilder thay vì StringBuffer để có hiệu năng tốt hơn.
        StringBuilder sb = new StringBuilder();
        sb.append("LocalNameSpace {\n");
        for (Map.Entry<String, Object> entry : maps.entrySet()) {
            sb.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public Object put(String key, Object value) {
        return maps.put(key, value);
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("Clearing is not supported");
    }

    @Override
    public Object remove(Object key) {
        throw new UnsupportedOperationException("Removing is not supported");
    }

    @NonNull
    @Override
    public Set<Entry<String, Object>> entrySet() {
        return Collections.emptySet();
    }
}

@TargetApi(26)
class FunctionWithRepr {
    private final Object func;
    private final String repr_;

    public FunctionWithRepr(Object func, String repr_) {
        this.func = func;
        this.repr_ = repr_;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public Object call(Object... args) throws InvocationTargetException, IllegalAccessException {
        if (func instanceof Function function) {
            return function.apply(args);
        } else if (func instanceof Method method) {
            return method.invoke(args);
        }
        throw new IllegalStateException("Unsupported function type");
    }

    @NonNull
    @Override
    public String toString() {
        if (repr_ != null) {
            return repr_;
        }
        if (func instanceof Function<?, ?> function) {
            return function.getClass().getName();
        } else if (func instanceof Method method) {
            return method.getDeclaringClass().getName() + "." + method.getName();
        }
        throw new IllegalStateException("Unsupported function type");
    }
}

@TargetApi(26)
class JsToJson {
    static List<RegexAndBase> INTEGER_TABLE = new ArrayList<>();

    // MODIFIED: Biên dịch Pattern một lần và lưu vào hằng số static để cải thiện hiệu năng.
    private static final Pattern JS_TO_JSON_PATTERN = Pattern.compile("""
            (?sx)
                    '(?:\\\\.|[^\\\\'])*'|"(?:\\\\.|[^\\\\"])*"|`(?:\\\\.|[^\\\\`])*`|
                    /\\*(?:(?!\\*/).)*?\\*/|//[^\\n]*\\n|,(?=\\s*(?:/\\*(?:(?!\\*/).)*?\\*/|//[^\\n]*\\n)?\\s*[]}])|
                    void\\s0|(?:(?<![0-9])[eE]|[a-df-zA-DF-Z_$])[.a-zA-Z_$0-9]*|
                    \\b(?:0[xX][0-9a-fA-F]+|0+[0-7]+)(?:\\s*(?:/\\*(?:(?!\\*/).)*?\\*/|//[^\\n]*\\n)?\\s*:)?|
                    [0-9]+(?=\\s*(?:/\\*(?:(?!\\*/).)*?\\*/|//[^\\n]*\\n)?\\s*:)|
                    !+
            """);
    private static final Pattern PROCESS_ESCAPES_PATTERN = Pattern.compile("(?s)(\")|\\\\(.)");
    private static final Pattern TEMPLATE_SUBSTITUTE_PATTERN = Pattern.compile("(?s)\\$\\{([^}]+)\\}");


    static {
        INTEGER_TABLE.add(new RegexAndBase("'(?s)^(0[xX][0-9a-fA-F]+)\\\\s*(?:/\\\\*(?:(?!\\\\*/).)*?\\\\*/|//[^\\\\n]*\\\\n)?\\\\s*:?$'", 16));
        INTEGER_TABLE.add(new RegexAndBase("'(?s)^(0+[0-7]+)\\\\s*(?:/\\\\*(?:(?!\\\\*/).)*?\\\\*/|//[^\\\\n]*\\\\n)?\\\\s*:?$'", 8));
    }

    Map<?, ?> vars;
    boolean strict;
    String code;

    JsToJson(String code, Map<?, ?> vars, boolean strict) {
        this.code = code;
        this.vars = vars;
        this.strict = strict;
    }

    Object jsToJson() throws Exception {
        // MODIFIED: Sử dụng hằng số Pattern đã được biên dịch trước.
        Matcher matcher = JS_TO_JSON_PATTERN.matcher(code);
        // MODIFIED: Sử dụng StringBuilder thay vì StringBuffer để có hiệu năng tốt hơn.
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String replacement = fixKv(matcher);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    private static String processEscapes(String escapedString) {
        // MODIFIED: Sử dụng hằng số Pattern đã được biên dịch trước.
        Matcher matcher = PROCESS_ESCAPES_PATTERN.matcher(escapedString);
        String escape;
        // MODIFIED: Sử dụng StringBuilder thay vì StringBuffer để có hiệu năng tốt hơn.
        StringBuilder e = new StringBuilder();
        while (matcher.find()) {
            String group1 = matcher.group(1);
            String group2 = matcher.group(2);
            if (group1 != null) {
                escape = group1;
            } else if (group2 != null) {
                escape = group2;
            } else {
                continue;
            }
            matcher.appendReplacement(e, Matcher.quoteReplacement(escape));
        }
        matcher.appendTail(e);

        return e.toString();
    }

    private String fixKv(Matcher m2) throws Exception {
        String v = Objects.requireNonNull(m2.group(0));
        if (v.equals("true") || v.equals("false")) {
            return v;
        } else if (v.equals("null")) {
            return "None";
        } else if (v.equals("undefined") || v.equals("void 0")) {
            return "None";
        } else if (v.startsWith("/*") || v.startsWith("//") || v.startsWith("!") || v.equals(",")) {
            return "";
        }

        if (v.charAt(0) == '\'' || v.charAt(0) == '"' || v.charAt(0) == '`') {
            if (v.charAt(0) == '`') {
                // MODIFIED: Sử dụng hằng số Pattern đã được biên dịch trước.
                Matcher m = TEMPLATE_SUBSTITUTE_PATTERN.matcher(v.substring(1, v.length() - 1));
                if (m.find()) {
                    v = templateSubstitute(m.group(1));
                }
            } else {
                v = v.substring(1, v.length() - 1);
            }
            return processEscapes(v);
        }
        for (RegexAndBase regexAndBase : INTEGER_TABLE) {
            Matcher matcher = regexAndBase.getPattern().matcher(v);
            if (matcher.matches()) {
                int i = Integer.parseInt(Objects.requireNonNull(matcher.group(1)), regexAndBase.getBase());
                return v.endsWith(":") ? '"' + i + "\":" : String.valueOf(i);
            }
        }
        throw new Exception("Unknown value: " + v);
    }

    @TargetApi(26)
    private static class RegexAndBase {
        private final Pattern pattern;
        private final int base;

        private RegexAndBase(String regex, int base) {
            // MODIFIED: Biên dịch Pattern một lần tại đây thay vì mỗi lần gọi.
            this.pattern = Pattern.compile(regex);
            this.base = base;
        }

        private Pattern getPattern() {
            return pattern;
        }

        private int getBase() {
            return base;
        }
    }

    private String templateSubstitute(String m) throws Exception {
        String evaluated = (String) new JsToJson(m, vars, strict).jsToJson();
        if (evaluated.charAt(0) == '"') {
            return new JSONObject(evaluated).toString();
        }
        return evaluated;
    }
}

@TargetApi(26)
class JS_Continue extends RuntimeException {
    public JS_Continue() {
        super("Invalid continue");
    }
}

@TargetApi(26)
class JS_Break extends RuntimeException {
    public JS_Break() {
        super("Invalid break");
    }
}

@TargetApi(26)
class JS_Throw extends RuntimeException {
    String error;

    public JS_Throw(Object e) {
        super("Uncaught exception " + (e == null ? "null" : e.toString()));
        error = e == null ? "null" : e.toString();
    }
}

@TargetApi(26)
class JS_Undefined {
}

@TargetApi(26)
public class JsInterpreter {
    private final String code;
    private final Map<Object, Object> _functions = new HashMap<>();
    private final Map<Object, Object> _objects = new HashMap<>();
    private static final Map<Character, Character> MATCHING_PARENS = new HashMap<>();

    // ADDED: Một lớp chuyên dụng để giữ kết quả của việc thông dịch một câu lệnh.
    // Điều này an toàn về kiểu và dễ đọc hơn so với việc trả về một Object[].
    private static class StatementResult {
        final Object value;
        final boolean shouldReturn;

        StatementResult(Object value, boolean shouldReturn) {
            this.value = value;
            this.shouldReturn = shouldReturn;
        }
    }

    static {
        MATCHING_PARENS.put('(', ')');
        MATCHING_PARENS.put('{', '}');
        MATCHING_PARENS.put('[', ']');
    }

    private static final String QUOTES = "'\"/";
    private int namedObjectCounter = 0;
    private static final Map<String, BiFunction<Object, Object, Object>> OPERATORS = createOperatorsMap();
    private static final Map<String, BiFunction<Object, Object, Object>> UNARY_OPERATORS_X = createUnaryXOperatorsMap();
    private static final Map<String, BiFunction<Object, Object, Object>> ALL_OPERATORS = mergeOperators();

    // MODIFIED: Biên dịch các Pattern một lần và lưu vào hằng số static để cải thiện hiệu năng.
    private static final Pattern STATEMENT_PREFIX_PATTERN = Pattern.compile("(?<var>(?:^var|^const|^let)\\s)|^return(?:\\s+|(?=[\"'])|$)|(?<throw>^throw\\s+)");
    private static final Pattern CONTROL_FLOW_PATTERN = Pattern.compile("(?x)(?<try>try)\\s*\\{|(?<if>if)\\s*\\(|(?<switch>switch)\\s*\\(|(?<for>for)\\s*\\(");
    private static final Pattern ELSE_PATTERN = Pattern.compile("else\\s*\\{");
    private static final Pattern CATCH_PATTERN = Pattern.compile("catch\\s*(?<err>\\(\\s*[a-zA-Z_$][\\w$]*\\s*\\))?\\{");
    private static final Pattern FINALLY_PATTERN = Pattern.compile("^finally\\s*\\{");
    private static final Pattern SWITCH_IN_FOR_PATTERN = Pattern.compile("switch\\s*\\(");
    private static final Pattern INCREMENT_DECREMENT_PATTERN = Pattern.compile("(?x)(?<presign>\\+\\+|--)(?<var1>[a-zA-Z_$][\\w$]*)|(?<var2>[a-zA-Z_$][\\w$]*)(?<postsign>\\+\\+|--)");
    private static final Pattern COMPLEX_EXPRESSION_PATTERN = Pattern.compile("(?x)(?<assign>(?<out>[a-zA-Z_$][\\w$]*)(?:\\[(?<index>[^\\[\\]]+(?:\\[[^\\[\\]]+(?:\\[[^\\]]+\\])?\\])?)])?\\s*(?<op>\\||\\*\\*|-|\\+|\\^|&&|\\?|/|%|\\|\\||&|>>|<<|\\*|\\?\\?)?=(?!=)(?<expr>.*)$)|(?<return>(?!if|return|true|false|null|undefined|NaN)(?<name>^[a-zA-Z_$][\\w$]*)$)|(?<attribute>(?<var>[a-zA-Z_$][\\w$]*)(?:(?<nullish>\\?)?\\.(?<member>[^(]+)|\\[(?<member2>[^\\[\\]]+(?:\\[[^\\[\\]]+(?:\\[[^\\]]+\\])?\\])?)]\\s*))|(?<indexing>(?<in>[a-zA-Z_$][\\w$]*)\\[(?<idx>.+)]$)|(?<function>(?<fname>[a-zA-Z_$][\\w$]*)\\((?<args>.*)\\)$)");
    private static final Pattern GLOBAL_VAR_PATTERN_TEMPLATE = Pattern.compile("var\\s?%s=(?<var>.*?)[,;]");
    private static final Pattern OBJECT_DEF_PATTERN_TEMPLATE = Pattern.compile("(?x)(?<![a-zA-Z$0-9.])%s\\s*=\\s*\\{\\s*(?<fields>((?:[a-zA-Z$0-9]+|\"[a-zA-Z$0-9]+\"|'[a-zA-Z$0-9]+')\\s*:\\s*function\\s*\\(.*?\\)\\s*\\{.*?\\}(?:,\\s*)?)*)\\}\s*;");
    private static final Pattern OBJECT_FIELD_PATTERN = Pattern.compile("(?x)(?<key>[a-zA-Z$0-9]+|\"[a-zA-Z$0-9]+\"|'[a-zA-Z$0-9]+')\\s*:\\s*function\\s*\\((?<args>(?:[a-zA-Z_$][\\w$]*|,)*)\\)\\{(?<code>[^}]+)\\}");
    private static final Pattern FUNCTION_DEF_PATTERN_TEMPLATE = Pattern.compile("(?x)(?s)(?:function\\s+(%s)|[{;,]\\s*(%s)\\s*=\\s*function|(?:var|const|let)\\s+(%s)\\s*=\\s*function)\\s*\\((?<args>[^)]*)\\)\\s*(?<code>\\{.+\\})");
    private static final Pattern PLAYER_JS_GLOBAL_VAR_PATTERN = Pattern.compile("(?x)(?<q1>[\\\"\\'])use\\s+strict(\\k<q1>);\\s*(?<code>var\\s+(?<name>[a-zA-Z0-9_$]+)\\s*=\\s*(?<value>(?<q2>[\\\"\\']).*?(\\k<q2>)\\.split\\((?<q3>[\\\"\\']).*?(\\k<q3>)\\)|\\[\\s*(?:(?<q4>[\\\"\\']).*?(\\k<q4>)\\s*,?\\s*)+\\]))[;,]");
    private static final Pattern UNDEFINED_CHECK_PATTERN_TEMPLATE = Pattern.compile(";\\s*if\\s*\\(\\s*typeof\\s+[a-zA-Z0-9_$]+\\s*===?\\s*(['\"])undefined\\1\\s*\\)\\s*return\\s+%s;");
    private static final Pattern NESTED_FUNCTION_PATTERN = Pattern.compile("function\\((?<args>[^)]*)\\)\\s*\\{");


    private static Map<String, BiFunction<Object, Object, Object>> mergeOperators() {
        Map<String, BiFunction<Object, Object, Object>> mergedMap = new LinkedHashMap<>();
        mergedMap.putAll(OPERATORS);
        mergedMap.putAll(UNARY_OPERATORS_X);
        return mergedMap;
    }


    private static final Object JS_Undefined = new Object();
    private static final Map<Character, Integer> RE_FLAGS = new HashMap<>();

    static {
        RE_FLAGS.put('i', Pattern.CASE_INSENSITIVE);
        RE_FLAGS.put('m', Pattern.MULTILINE);
        RE_FLAGS.put('s', Pattern.DOTALL);
        RE_FLAGS.put('x', Pattern.COMMENTS);
        RE_FLAGS.put('u', Pattern.UNICODE_CASE);
    }

    public JsInterpreter(String code) {
        this.code = code;
    }

    private static Map<String, BiFunction<Object, Object, Object>> createOperatorsMap() {
        Map<String, BiFunction<Object, Object, Object>> OPERATORS = new LinkedHashMap<>();

        OPERATORS.put("?", null);
        OPERATORS.put("??", null);
        OPERATORS.put("||", null);
        OPERATORS.put("&&", null);

        OPERATORS.put("|", JsInterpreter::jsBitOpOr);
        OPERATORS.put("^", JsInterpreter::jsBitOpXor);
        OPERATORS.put("&", JsInterpreter::jsBitOpAnd);

        OPERATORS.put("===", JsInterpreter::jsEqOpIs);
        OPERATORS.put("!==", JsInterpreter::jsEqOpIsNot);
        OPERATORS.put("==", JsInterpreter::jsEqOpEq);
        OPERATORS.put("!=", JsInterpreter::jsEqOpNe);

        OPERATORS.put("<=", JsInterpreter::jsCompOpLe);
        OPERATORS.put(">=", JsInterpreter::jsCompOpGe);
        OPERATORS.put("<", JsInterpreter::jsCompOpLt);
        OPERATORS.put(">", JsInterpreter::jsCompOpGt);

        OPERATORS.put(">>", JsInterpreter::jsBitOpRShift);
        OPERATORS.put("<<", JsInterpreter::jsBitOpLShift);

        OPERATORS.put("+", JsInterpreter::jsArithOpAdd);
        OPERATORS.put("-", JsInterpreter::jsArithOpSub);

        OPERATORS.put("*", JsInterpreter::jsArithOpMul);
        OPERATORS.put("%", JsInterpreter::jsModOp);
        OPERATORS.put("/", JsInterpreter::jsDivOp);
        OPERATORS.put("**", JsInterpreter::jsExpOp);

        return OPERATORS;
    }

    private static Map<String, BiFunction<Object, Object, Object>> createUnaryXOperatorsMap() {
        Map<String, BiFunction<Object, Object, Object>> OPERATORS = new LinkedHashMap<>();

        OPERATORS.put("typeof", JsInterpreter::jsTypeof);

        return OPERATORS;
    }

    private static Object jsTypeof(Object expr, Object o) {
        if (expr == null) return "object";
        if (expr instanceof Boolean) return "boolean";
        if (expr instanceof Number)
            return "number";
        if (expr instanceof String) return "string";
        if (expr instanceof Runnable) return "function";

        return "object";
    }

    private static int jsBitOpOr(Object a, Object b) {
        return zeroise(a) | zeroise(b);
    }

    private static int jsBitOpXor(Object a, Object b) {
        return zeroise(a) ^ zeroise(b);
    }

    private static int jsBitOpAnd(Object a, Object b) {
        return zeroise(a) & zeroise(b);
    }

    private static boolean jsEqOpIs(Object a, Object b) {
        a = a == null ? "null" : a;
        b = b == null ? "null" : b;
        return a.equals(b);
    }

    private static boolean jsEqOpIsNot(Object a, Object b) {
        a = a == null ? "null" : a;
        b = b == null ? "null" : b;
        return !a.equals(b);
    }

    private static boolean jsEqOpEq(Object a, Object b) {
        if (a instanceof JS_Undefined || b instanceof JS_Undefined) {
            return true;
        }
        return a == b;
    }

    private static boolean jsEqOpNe(Object a, Object b) {
        if (a instanceof JS_Undefined || b instanceof JS_Undefined) {
            return true;
        }
        return a != b;
    }

    private static boolean jsCompOpLe(Object a, Object b) {
        return castToInt(a) <= castToInt(b);
    }

    private static boolean jsCompOpGe(Object a, Object b) {
        return castToInt(a) >= castToInt(b);
    }

    private static boolean jsCompOpLt(Object a, Object b) {
        return castToInt(a) < castToInt(b);
    }

    private static boolean jsCompOpGt(Object a, Object b) {
        return castToInt(a) > castToInt(b);
    }

    private static int jsBitOpRShift(Object a, Object b) {
        return zeroise(a) >> zeroise(b);
    }

    private static int jsBitOpLShift(Object a, Object b) {
        return zeroise(a) << zeroise(b);
    }

    private static Object jsArithOpAdd(Object a, Object b) {
        if (a instanceof JS_Undefined || b instanceof JS_Undefined) {
            return Double.NaN;
        }
        if (a instanceof Number && b instanceof Number) {
            return castToInt(a) + castToInt(b);
        } else {
            return a + (String) b;
        }
    }

    private static Object jsArithOpSub(Object a, Object b) {
        if (a instanceof JS_Undefined || b instanceof JS_Undefined) {
            return Double.NaN;
        }
        return castToInt(a) - castToInt(b);
    }

    private static Object jsArithOpMul(Object a, Object b) {
        if (a instanceof JS_Undefined || b instanceof JS_Undefined) {
            return Double.NaN;
        }
        return castToInt(a) * castToInt(b);
    }

    private static Object jsModOp(Object a, Object b) {
        if (a instanceof JS_Undefined || b instanceof JS_Undefined || b == null) {
            return Double.NaN;
        }
        return castToInt(a) % castToInt(b);
    }

    private static Object jsDivOp(Object a, Object b) {
        if ((a instanceof JS_Undefined || b instanceof JS_Undefined) || a == null || b == null) {
            return Double.NaN;
        }
        return ((a instanceof Integer) ? (int) a : (double) a) / ((b instanceof Integer) ? (int) b : (double) b);
    }

    private static Object jsExpOp(Object a, Object b) {
        if (b == null) {
            return 1;
        } else if (a instanceof JS_Undefined || b instanceof JS_Undefined) {
            return Double.NaN;
        }
        return Math.pow(((a instanceof Integer) ? (int) a : (double) a), ((b instanceof Integer) ? (int) b : (double) b));
    }

    private static int zeroise(Object x) {
        if (x instanceof Boolean) {
            return (boolean) x ? 1 : 0;
        }
        if (x == null) {
            return 0;
        }
        if (Double.isNaN(Double.parseDouble(x.toString())) || Double.isInfinite(Double.parseDouble(x.toString()))) {
            return 0;
        }
        return (int) x;
    }

    private static int castToInt(Object x) {
        if (!(x instanceof Number)) {
            return 0;
        }
        return x instanceof Integer ? (int) x : x instanceof Double ? (int) Math.ceil((Double) x) : 0;
    }

    private List<String> castObjectToListString(Object obj) {
        if (obj instanceof List<?>) {
            List<String> stringList = new ArrayList<>();
            for (Object item : (List<?>) obj) {
                if (item instanceof String) {
                    stringList.add((String) item);
                }
            }
            return stringList;
        }
        throw new ClassCastException("It was not possible to convert object to List<String>, because the object is a " + obj.getClass().getName());
    }

    private Map<String, Object> castObjectToMap(Object obj) {
        if (obj instanceof Map<?, ?>) {
            Map<String, Object> resultMap = new HashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
                if (entry.getKey() instanceof String) {
                    resultMap.put((String) entry.getKey(), entry.getValue());
                }
            }
            return resultMap;
        }
        throw new ClassCastException("It was not possible to convert object to Map<String, Object>, because the object is a " + obj.getClass().getName());
    }

    private static Object jsTernary(Object cndn, Object if_true, Object if_false) {
        if (cndn == null || cndn == JS_Undefined || cndn.equals(Boolean.FALSE) || cndn.equals(0) || cndn.equals("") || Objects.equals(cndn, "None")) {
            return if_false;
        }

        if (cndn instanceof Double) {
            double value = (Double) cndn;
            if (Double.isNaN(value)) {
                return if_false;
            }
        }
        return if_true;
    }

    private static List<String> _separate(String expr, String delim, Integer maxSplit) {
        if (expr.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Character, Integer> counters = new HashMap<>();
        for (char c : MATCHING_PARENS.values()) {
            counters.put(c, 0);
        }
        int start = 0;
        int splits = 0;
        int pos = 0;
        int delimLen = delim.length() - 1;
        // MODIFIED: Thay thế "magic strings" bằng các kiểu dữ liệu phù hợp hơn để dễ đọc.
        char inQuote = 0; // 0 có nghĩa là không ở trong dấu ngoặc kép
        boolean escaping = false;
        boolean isAfterOp = true;
        boolean inRegexCharGroup = false;

        List<String> parts = new ArrayList<>();
        for (int idx = 0; idx < expr.length(); idx++) {
            char ch = expr.charAt(idx);
            if (inQuote == 0 && MATCHING_PARENS.containsKey(ch)) {
                counters.put(MATCHING_PARENS.get(ch), Objects.requireNonNull(counters.get(MATCHING_PARENS.get(ch))) + 1);
            } else if (inQuote == 0 && counters.containsKey(ch)) {
                var counter = counters.get(ch);
                if (counter != null && counter > 0) {
                    counters.put(ch, counter - 1);
                }
            } else if (!escaping) {
                if (isQuote(ch) && (inQuote == ch || inQuote == 0) && (inQuote != 0 || !isAfterOp || ch != '/')) {
                    inQuote = (inQuote != 0 && !inRegexCharGroup) ? 0 : ch;
                } else if (inQuote == '/' && (ch == '[' || ch == ']')) {
                    inRegexCharGroup = ch == '[';
                }
            }
            escaping = (!escaping && inQuote != 0 && ch == '\\');
            boolean inUnaryOp = (inQuote == 0 && !inRegexCharGroup && isAfterOp && (ch == '-' || ch == '+'));
            if (inQuote == 0 && isOpChar(ch)) {
                isAfterOp = true;
            } else if (!Character.isWhitespace(ch)) {
                isAfterOp = false;
            }

            if (ch != delim.charAt(pos) || anyCountersNonZero(counters) || inQuote != 0 || inUnaryOp) {
                pos = 0;
                continue;
            } else if (pos != delimLen) {
                pos++;
                continue;
            }

            parts.add(expr.substring(start, idx - delimLen));
            start = idx + 1;
            pos = 0;
            splits++;
            if (maxSplit != null && splits >= maxSplit) {
                break;
            }
        }
        parts.add(expr.substring(start));
        return parts;
    }

    private static boolean isQuote(char ch) {
        return QUOTES.indexOf(ch) != -1;
    }

    private static boolean isOpChar(char ch) {
        return " +-*/%&|^=<>!,;{}:[".indexOf(ch) != -1;
    }

    private static boolean anyCountersNonZero(Map<Character, Integer> counters) {
        for (int count : counters.values()) {
            if (count != 0) {
                return true;
            }
        }
        return false;
    }

    private List<String> separateAtParen(String expr, String delim) throws Exception {
        if (delim == null) {
            delim = String.valueOf(MATCHING_PARENS.get(expr.charAt(0)));
        }
        List<String> separated = _separate(expr, delim, 1);
        if (separated.size() < 2) {
            throw new Exception("No terminating paren " + delim + " expr: " + expr);
        }
        return separated;
    }

    Object interpretExpression(String expr, LocalNameSpace localVars, int allowRecursion) throws Exception {
        // MODIFIED: Sử dụng lớp StatementResult thay vì Object[]
        StatementResult result = interpretStatement(expr, localVars, allowRecursion);
        if (result.shouldReturn) {
            throw new Exception("Cannot return from an expression. Expr: " + expr);
        }
        return result.value;
    }

    @SuppressWarnings("unchecked")
    // MODIFIED: Thay đổi kiểu trả về từ Object[] thành StatementResult để an toàn và rõ ràng hơn.
    private StatementResult interpretStatement(String stmt, LocalNameSpace localVars, int allowRecursion) throws Exception {
        if (allowRecursion < 0) {
            throw new Exception("Recursion limit reached");
        }
        allowRecursion -= 1;

        Object ret = null;
        boolean shouldReturn = false;
        List<String> subStatements = _separate(stmt, ";", null);
        String expr = stmt = subStatements.isEmpty() ? "" : subStatements.remove(subStatements.size() - 1).trim();
        for (String subStmt : subStatements) {
            // MODIFIED: Xử lý kết quả từ StatementResult
            StatementResult result = interpretStatement(subStmt, localVars, allowRecursion);
            ret = result.value;
            shouldReturn = result.shouldReturn;
            if (shouldReturn) {
                return new StatementResult(ret, true);
            }
        }
        // MODIFIED: Sử dụng hằng số Pattern đã được biên dịch trước.
        Matcher matcher = STATEMENT_PREFIX_PATTERN.matcher(stmt);
        if (matcher.find()) {
            expr = stmt.substring(Objects.requireNonNull(matcher.group(0)).length()).trim();
            if (matcher.group("throw") != null) {
                throw new JS_Throw(interpretExpression(expr, localVars, allowRecursion));
            }
            shouldReturn = matcher.group("var") == null;
        }
        if (expr.isEmpty()) {
            return new StatementResult(null, shouldReturn);
        }
        if (isQuote(expr.charAt(0))) {
            List<String> result = _separate(expr, String.valueOf(expr.charAt(0)), 1);
            String inner = result.get(0);
            String outer = result.get(1);
            if (expr.charAt(0) == '/') {
                Object[] result2 = regexFlags(outer);
                int flags = (int) result2[0];
                outer = (String) result2[1];
                inner = inner + "/" + flags;
            } else {
                inner = (String) new JsToJson(inner + expr.charAt(0), new HashMap<>(), true).jsToJson();
            }
            if (outer.isEmpty()) {
                return new StatementResult(inner, shouldReturn);
            }
            expr = namedObject(localVars, inner) + outer;
        }

        if (expr.startsWith("new ")) {
            String obj = expr.substring(4);
            if (obj.startsWith("Date(")) {
                List<String> result = separateAtParen(obj.substring(4), null);
                String left = result.get(0).substring(1);
                String right = result.get(1);

                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
                ZonedDateTime zonedDateTime = ZonedDateTime.parse((String) interpretExpression(left, localVars, allowRecursion), formatter);
                Instant instant = zonedDateTime.toInstant();

                expr = dump(instant.toEpochMilli(), localVars) + right;
            } else {
                throw new Exception("Unsupported object: " + obj);
            }
        }

        if (expr.startsWith("void ")) {
            interpretStatement(expr.substring(5), localVars, allowRecursion);
            return new StatementResult(JS_Undefined, shouldReturn);
        }


        for (String op : UNARY_OPERATORS_X.keySet()) {
            if (!expr.startsWith(op)) {
                continue;
            }
            String operand = expr.substring(op.length());
            if (operand.isEmpty() || operand.charAt(0) != ' ') {
                continue;
            }
            Object[] opResult = handleOperators(expr, localVars, allowRecursion);
            if (opResult.length > 0) {
                return new StatementResult(opResult[0], shouldReturn);
            }
        }

        if (expr.startsWith("{")) {
            List<String> result = separateAtParen(expr, "}");
            String inner = result.get(0).substring(1);
            String outer = result.get(1);
            List<List<String>> subExpressions = new ArrayList<>();
            for (String subExpr : _separate(inner, ",", null)) {
                subExpressions.add(_separate(subExpr.strip(), ":", 1));
            }
            boolean flag = true;
            for (List<String> subExpr : subExpressions) {
                if (subExpr.size() != 2) {
                    flag = false;
                    break;
                }
            }
            if (flag) {
                int finalAllowRecursion2 = allowRecursion;
                class InnerClass {
                    Object[] dictItem(Object key, Object val) throws Exception {
                        val = interpretExpression((String) val, localVars, finalAllowRecursion2);
                        if (((String) key).matches("[a-zA-Z_$][\\w$]*")) {
                            return new Object[]{key, val};
                        }
                        return new Object[]{interpretExpression((String) key, localVars, finalAllowRecursion2), val};
                    }
                }
                Map<Object, Object> dict = new HashMap<>();
                for (List<String> sub : subExpressions) {
                    Object[] result2 = new InnerClass().dictItem(sub.get(0), sub.get(1));
                    dict.put(result2[0], result2[1]);
                }
                return new StatementResult(dict, shouldReturn);
            }
            StatementResult result3 = interpretStatement(inner, localVars, allowRecursion);
            inner = (String) result3.value;
            boolean shouldAbort = result3.shouldReturn;
            if (outer.isEmpty() || shouldAbort) {
                return new StatementResult(inner, shouldAbort || shouldReturn);
            } else {
                expr = dump(inner, localVars) + outer;
            }
        }

        if (expr.startsWith("(")) {
            List<String> result = separateAtParen(expr, null);
            Object inner = result.get(0).substring(1);
            String outer = result.get(1);
            StatementResult result2 = interpretStatement((String) inner, localVars, allowRecursion);
            inner = result2.value;
            boolean shouldAbort = result2.shouldReturn;
            if (outer.isEmpty() || shouldAbort) {
                return new StatementResult(inner, shouldAbort || shouldReturn);
            } else {
                expr = dump(inner, localVars) + outer;
            }
        }

        if (expr.startsWith("[")) {
            List<String> result = separateAtParen(expr, null);
            String inner = result.get(0).substring(1).strip();
            String outer = result.get(1).strip();
            List<Object> interpretedItems = new ArrayList<>();
            for (String item : _separate(inner, ",", null)) {
                interpretedItems.add(interpretExpression(item, localVars, allowRecursion));
            }
            String name = namedObject(localVars, interpretedItems);
            expr = name + outer;
        }

        // MODIFIED: Sử dụng hằng số Pattern đã được biên dịch trước.
        Matcher m = CONTROL_FLOW_PATTERN.matcher(expr);
        if (m.lookingAt()) {
            if (m.group("if") != null) {
                return handleIfStatement(expr, m, localVars, allowRecursion, shouldReturn);
            } else if (m.group("try") != null) {
                return handleTryCatch(expr, m, localVars, allowRecursion, shouldReturn);
            } else if (m.group("for") != null) {
                return handleForLoop(expr, m, localVars, allowRecursion, shouldReturn);
            } else if (m.group("switch") != null) {
                return handleSwitch(expr, m, localVars, allowRecursion, shouldReturn);
            }
        }

        List<String> subExpressions = _separate(expr, ",", null);
        if (subExpressions.size() > 1) {
            for (String subExpr : subExpressions) {
                StatementResult result = interpretStatement(subExpr, localVars, allowRecursion);
                ret = result.value;
                if (result.shouldReturn) {
                    return new StatementResult(ret, true);
                }
            }
            return new StatementResult(ret, false);
        }
        // MODIFIED: Sử dụng hằng số Pattern đã được biên dịch trước.
        Matcher m3 = INCREMENT_DECREMENT_PATTERN.matcher(expr);
        while (m3.find()) {
            String var = m3.group("var1") != null ? m3.group("var1") : m3.group("var2");
            int start = m3.start();
            int end = m3.end();
            String sign = m3.group("presign") != null ? m3.group("presign") : m3.group("postsign");
            ret = localVars.getValue(var);
            if (ret == null) {
                break;
            }
            localVars.put(var, sign.charAt(0) == '+' ? (int) ret + 1 : (int) ret - 1);
            if (m3.group("presign") != null) {
                ret = localVars.getValue(var);
            }
            expr = expr.substring(0, start) + dump(ret, localVars) + expr.substring(end);
        }

        if (expr.isEmpty()) {
            return new StatementResult(null, shouldReturn);
        }

        // MODIFIED: Sử dụng hằng số Pattern đã được biên dịch trước.
        Matcher m2 = COMPLEX_EXPRESSION_PATTERN.matcher(expr);
        boolean find = m2.matches();

        if (find && m2.group("assign") != null) {
            String out = m2.group("out");
            Object leftVal = localVars.getValue(out);

            if (m2.group("index") == null) {
                localVars.put(out, operator(m2.group("op"), leftVal, m2.group("expr"), expr, localVars, allowRecursion));
                return new StatementResult(localVars.getValue(out), shouldReturn);
            } else if (leftVal == null || leftVal == JS_Undefined) {
                throw new Exception("Cannot index undefined variable " + m.group("out"));
            }
            Object idx = interpretExpression(m2.group("index"), localVars, allowRecursion);
            if (!(idx instanceof Integer || idx instanceof Double)) {
                throw new Exception("List index " + idx + " must be integer");
            }

            assert leftVal instanceof ArrayList;
            ((ArrayList<Object>) leftVal).set(
                    (int) idx,
                    operator(
                            m2.group("op"),
                            index(leftVal, idx, false),
                            m2.group("expr"),
                            expr,
                            localVars,
                            allowRecursion
                    )
            );

            return new StatementResult(((ArrayList<Object>) leftVal).get((int) idx), shouldReturn);

        } else if (expr.matches("-?\\d+(\\.\\d+)?")) {
            return new StatementResult(Integer.parseInt(expr), shouldReturn);

        } else if (expr.equals("break")) {
            throw new JS_Break();
        } else if (expr.equals("continue")) {
            throw new JS_Continue();
        } else if (expr.equals("undefined")) {
            return new StatementResult(new JS_Undefined(), shouldReturn);
        } else if (expr.equals("NaN")) {
            return new StatementResult(Double.NaN, shouldReturn);

        } else if (find && m2.group("return") != null) {
            Object r = localVars.getValue(m2.group("name"));
            if (r == null) {
                return new StatementResult(extractGlobalVar(m2.group("name"), localVars), shouldReturn);
            } else {
                return new StatementResult(r, shouldReturn);
            }
        }

        if (find && m2.group("indexing") != null && m2.start() == 0) {
            Object val = localVars.getValue(m2.group("in"));
            Object idx = interpretExpression(m2.group("idx"), localVars, allowRecursion);
            return new StatementResult(index(val, idx, false), shouldReturn);
        }

        Object[] opResult = handleOperators(expr, localVars, allowRecursion);
        if (opResult.length > 0) {
            return new StatementResult(opResult[0], shouldReturn);
        }

        try {
            Object obj = new JsToJson(expr, new HashMap<>(), true).jsToJson();
            try {
                try {
                    double d = Double.parseDouble(obj.toString());
                    return new StatementResult(Math.ceil(d), shouldReturn);
                } catch (Exception ignored) {
                }
                return new StatementResult(obj, shouldReturn);
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }

        if (find && m2.group("attribute") != null) {
            String variable = m2.group("var");
            String member = m2.group("member");
            String nullish = m2.group("nullish");
            String remaining;
            if (member == null) {
                member = interpretExpression(m2.group("member2"), localVars, allowRecursion).toString();
            }
            String argStr = expr.substring(m2.end());
            if (argStr.startsWith("(")) {
                List<String> result = separateAtParen(argStr, null);
                argStr = result.get(0).substring(1).strip();
                remaining = result.get(1).strip();
            } else {
                remaining = argStr;
                argStr = null;
            }
            final String[] finalMember = {member};
            String finalArgStr = argStr;
            int finalAllowRecursion = allowRecursion;
            class InnerClass {
                void assertion(Object cndn, String msg) throws Exception {
                    try {
                        if (!(boolean) cndn) {
                            throw new Exception(finalMember[0] + msg);
                        }
                    } catch (Exception e) {
                        if (cndn.toString().isEmpty()) {
                            throw new Exception(finalMember[0] + msg);
                        }
                    }
                }

                Object evalMethod() throws Exception {
                    Objects.requireNonNull(variable);
                    Object obj = localVars.getValue(variable);
                    if (finalArgStr == null) {
                        return index(obj, finalMember[0], Boolean.parseBoolean(nullish));
                    }
                    if (obj == null && !variable.equals("String") && !variable.equals("Math") && !variable.equals("Array")) {
                        if (!_objects.containsValue(variable)) {
                            try {
                                _objects.put(variable, extractObject(variable, localVars));
                            } catch (Exception e) {
                                if (nullish == null) {
                                    throw new Exception(e);
                                }
                            }
                            obj = _objects.isEmpty() ? new JS_Undefined() : _objects.get(variable);
                        }
                    }
                    if (nullish != null && obj instanceof JS_Undefined) {
                        return new JS_Undefined();
                    }
                    List<Object> argvals = new ArrayList<>();
                    for (String v : _separate(finalArgStr, ",", null)) {
                        argvals.add(interpretExpression(v, localVars, finalAllowRecursion));
                    }

                    if (finalMember[0].startsWith("prototype.")) {
                        String[] parts = finalMember[0].split("\\.");
                        String new_member = parts[1];
                        String func_prototype = parts[2];

                        assertion(!argvals.isEmpty(), "takes one or more arguments");

                        if (func_prototype.equals("call")) {
                            obj = argvals.get(0);
                            argvals = argvals.subList(1, argvals.size());
                        } else if (func_prototype.equals("apply")) {
                            assertion(argvals.size() == 2, "takes two arguments");
                            obj = argvals.get(0);
                            argvals = (List<Object>) argvals.get(1);
                            assertion(argvals != null, "second argument needs to be a list");
                        } else {
                            throw new RuntimeException("Unsupported Function method " + func_prototype);
                        }

                        finalMember[0] = new_member;
                    }

                    Objects.requireNonNull(argvals);
                    if (obj == null && variable.equals("String")) {
                        if (finalMember[0].equals("fromCharCode")) {
                            assertion(argvals, "takes one or more arguments");
                            StringBuilder result = new StringBuilder();
                            for (Object ob : argvals) {
                                if (ob instanceof Integer) {
                                    int unicodeValue = (int) ob;
                                    result.append((char) unicodeValue);
                                }
                            }
                            return result.toString();
                        }
                        throw new Exception("Unsupported String method " + finalMember[0]);
                    } else if (obj == null && variable.equals("Math")) {
                        if (finalMember[0].equals("pow")) {
                            assertion(Objects.requireNonNull(argvals).size() == 2, "takes two arguments");
                            return castToInt(Math.pow(castToInt(argvals.get(0)), castToInt(argvals.get(1))));
                        }
                        throw new Exception("Unsupported Math method " + finalMember[0]);
                    }
                    switch (finalMember[0]) {
                        case "split" -> {
                            assertion(argvals, "takes one or more arguments");
                            assertion(argvals.size() == 1, "with limit argument is not implemented");
                            assert obj != null;

                            String arg = (String) argvals.get(0);
                            return new ArrayList<>(Arrays.asList(((String) obj).split(Pattern.quote(arg))));
                        }
                        case "join" -> {
                            assertion(obj instanceof List<?>, "must be applied on a list");
                            assertion(argvals.size() == 1, "takes exactly one argument");

                            String arg = (String) argvals.get(0);
                            return String.join(arg, castObjectToListString(obj));
                        }
                        case "reverse" -> {
                            assertion(argvals.isEmpty(), "does not take any arguments");
                            assert obj != null;
                            if (obj instanceof List<?>) {
                                Collections.reverse((List<?>) obj);
                                return obj;
                            }
                        }
                        case "slice" -> {
                            assertion(obj instanceof List<?>, "must be applied on a list");
                            assertion(argvals.size() == 2, "takes exactly one argument");
                            assert obj != null;

                            return obj.toString().substring((Integer) argvals.get(0), argvals.size() > 1 ? (int) argvals.get(1) : obj.toString().length());
                        }
                        case "splice" -> {
                            assertion(obj instanceof List<?>, "must be applied on a list");
                            assertion(argvals, "takes one or more arguments");
                            assert obj instanceof List;

                            int index = (int) argvals.get(0);
                            int howMany = argvals.size() > 1 ? (int) argvals.get(1) : ((List<?>) obj).size();
                            if (index < 0) {
                                index += ((List<?>) obj).size();
                            }
                            List<Object> addItems = argvals.size() > 1 ? argvals.subList(2, argvals.size()) : new ArrayList<>();
                            List<Object> res = new ArrayList<>();
                            int min = Math.min(index + howMany, ((List<?>) obj).size());
                            for (int i = index; i < min; i++) {
                                res.add(((List<?>) obj).remove(index));
                            }
                            for (int i = 0; i < addItems.size(); i++) {
                                ((List<Object>) obj).add(index + i, addItems.get(i));
                            }
                            return res;
                        }
                        case "unshift" -> {
                            assertion(obj instanceof List<?>, "must be applied on a list");
                            assertion(argvals, "takes one or more arguments");
                            assert obj != null;
                            assert obj instanceof List;

                            Collections.reverse(argvals);
                            for (Object item : argvals) {
                                ((List<Object>) obj).add(0, item);
                            }
                            return obj;
                        }
                        case "pop" -> {
                            assertion(obj instanceof List<?>, "must be applied on a list");
                            assertion(argvals.isEmpty(), "does not take any arguments");
                            assert obj instanceof List<?>;

                            return ((List<?>) obj).remove(((List<?>) obj).size() - 1);
                        }
                        case "push" -> {
                            assertion(argvals, "takes one or more arguments");
                            assert obj != null;

                            ((List<Object>) obj).addAll(argvals);
                            return obj;
                        }
                        case "forEach" -> {
                            assertion(argvals, "takes one or more arguments");
                            assertion(argvals.size() <= 2, "takes at-most 2 arguments");
                            assert obj != null;

                            Object f = argvals.get(0);
                            Object _this = argvals.size() > 1 ? argvals.get(1) : "";
                            Map<String, Object> t = new HashMap<>();
                            t.put("this", _this);
                            List<Object> result = new ArrayList<>();
                            for (int i = 0; i < ((List<?>) obj).size(); i++) {
                                result.add(((FunctionWithRepr) f).call((new Object[]{((List<?>) obj).get(i), i, obj}), finalAllowRecursion, t));
                            }
                            return result;
                        }
                        case "indexOf" -> {
                            assertion(argvals, "takes one or more arguments");
                            assertion(argvals.size() <= 2, "takes at-most 2 arguments");
                            assert obj instanceof List;

                            Object idx = argvals.get(0);
                            int start = argvals.size() > 1 ? (int) argvals.get(1) : 0;
                            return customIndexOf((List<Object>) obj, idx, start);
                        }
                        case "charCodeAt" -> {
                            assertion(obj instanceof String, "must be applied on a string");
                            assertion(argvals.size() == 1, "takes exactly one argument");
                            assert obj instanceof String;

                            int idx = argvals.get(0) instanceof Integer ? (int) argvals.get(0) : 0;
                            if (idx >= ((String) obj).length()) {
                                return 0;
                            }
                            char charAtIndex = ((String) obj).charAt(idx);
                            return (int) charAtIndex;
                        }
                    }
                    if (obj instanceof List<?> list) {
                        return list.get(Integer.parseInt(finalMember[0]));
                    } else if (obj instanceof Map<?, ?> map && map.get(finalMember[0]) instanceof FunctionWithRepr functionWithRepr) {
                        return functionWithRepr.call(argvals.toArray(new Object[0]), finalAllowRecursion);
                    } else {
                        throw new Exception("Cannot get index for unsupported object type " + obj);
                    }
                }
            }
            if (!remaining.isEmpty()) {
                StatementResult result = interpretStatement(namedObject(localVars, new InnerClass().evalMethod()) + remaining, localVars, allowRecursion);
                return new StatementResult(result.value, shouldReturn || result.shouldReturn);
            } else {
                return new StatementResult(new InnerClass().evalMethod(), shouldReturn);
            }

        } else if (find && m2.group("function") != null) {
            String fName = m2.group("fname");
            List<Object> argVals = new ArrayList<>();
            List<String> sp = _separate(m2.group("args"), ",", null);
            for (String v : sp) {
                argVals.add(interpretExpression(v, localVars, allowRecursion));
            }
            if (localVars.getValue(fName) != null) {
                return new StatementResult(((FunctionWithRepr) localVars.getValue(fName)).call(argVals.toArray(new Object[0]), allowRecursion), shouldReturn);
            } else if (_functions.containsValue(fName)) {
                _functions.put(fName, extractFuName(fName));
            }
            return new StatementResult(((FunctionWithRepr) Objects.requireNonNull(_functions.get(fName))).call(argVals.toArray(new Object[0]), allowRecursion), shouldReturn);
        }
        throw new Exception("Unsupported JS expression: " + expr);
    }

    // MODIFIED: Các phương thức xử lý cấu trúc điều khiển được tách ra từ interpretStatement
    private StatementResult handleIfStatement(String expr, Matcher m, LocalNameSpace localVars, int allowRecursion, boolean shouldReturn) throws Exception {
        List<String> result = separateAtParen(expr.substring(m.end() - 1), null);
        Object cndn = result.get(0).substring(1);
        expr = result.get(1);
        List<String> result2;
        if (expr.startsWith("{")) {
            result2 = separateAtParen(expr, null);
        } else {
            result2 = separateAtParen(String.format(" %s;", expr), ";");
        }
        String ifExpr = result2.get(0).substring(1);
        expr = result2.get(1);
        String elseExpr = "";
        Matcher m2 = ELSE_PATTERN.matcher(expr);
        if (m2.lookingAt()) {
            List<String> result3 = separateAtParen(expr.substring(m2.end() - 1), null);
            elseExpr = result3.get(0).substring(1);
            expr = result3.get(1);
        }
        cndn = jsTernary(interpretExpression((String) cndn, localVars, allowRecursion), true, false);
        StatementResult result4 = interpretStatement((boolean) cndn ? ifExpr : elseExpr, localVars, allowRecursion);
        if (result4.shouldReturn) {
            return result4;
        }
        return interpretStatement(expr, localVars, allowRecursion);
    }

    private StatementResult handleTryCatch(String expr, Matcher m, LocalNameSpace localVars, int allowRecursion, boolean shouldReturn) throws Exception {
        List<String> result = separateAtParen(expr.substring(m.end() - 1), null);
        String tryExpr = result.get(0).substring(1);
        expr = result.get(1);
        Object err = null;
        StatementResult tryResult = new StatementResult(null, false);
        try {
            tryResult = interpretStatement(tryExpr, localVars, allowRecursion);
            if (tryResult.shouldReturn) {
                return tryResult;
            }
        } catch (Exception e) {
            err = e;
        }

        StatementResult pending = new StatementResult(null, false);
        Matcher m2 = CATCH_PATTERN.matcher(expr);
        if (m2.lookingAt()) {
            List<String> result3 = separateAtParen(expr.substring(m2.end() - 1), null);
            String subExpr = result3.get(0).substring(1);
            expr = result3.get(1);
            if (err != null) {
                Map<String, Object> catchVars = new HashMap<>();
                if (m2.group("err") != null) {
                    String errName = m2.group("err").replaceAll("[()\\s]", "");
                    catchVars.put(errName, (err instanceof JS_Throw) ? ((JS_Throw) err).error : err);
                }
                err = null;
                pending = interpretStatement(subExpr, localVars.newChild(catchVars), allowRecursion);
            }
        }

        Matcher m4 = FINALLY_PATTERN.matcher(expr);
        if (m4.lookingAt()) {
            List<String> result4 = separateAtParen(expr.substring(m4.end() - 1), null);
            String subExpr = result4.get(0).substring(1);
            expr = result4.get(1);
            StatementResult finallyResult = interpretStatement(subExpr, localVars, allowRecursion);
            if (finallyResult.shouldReturn) {
                return finallyResult;
            }
        }

        if (pending.shouldReturn) {
            return pending;
        }
        if (err != null) {
            if (err instanceof RuntimeException) throw (RuntimeException) err;
            throw new Exception(String.valueOf(err));
        }
        return interpretStatement(expr, localVars, allowRecursion);
    }

    private StatementResult handleForLoop(String expr, Matcher m, LocalNameSpace localVars, int allowRecursion, boolean shouldReturn) throws Exception {
        List<String> result = separateAtParen(expr.substring(m.end() - 1), null);
        String constructor = result.get(0).substring(1);
        String remaining = result.get(1);
        String body;
        if (remaining.startsWith("{")) {
            List<String> result2 = separateAtParen(remaining, null);
            body = result2.get(0).substring(1);
            expr = result2.get(1);
        } else {
            Matcher switch_m = SWITCH_IN_FOR_PATTERN.matcher(remaining);
            if (switch_m.lookingAt()) {
                List<String> result3 = separateAtParen(remaining.substring(switch_m.end() - 1), null);
                String switch_val = result3.get(0).substring(1);
                remaining = result3.get(1);
                List<String> result4 = separateAtParen(remaining, "}");
                body = result4.get(0).substring(1);
                expr = result4.get(1);
                body = "switch(" + switch_val + "){" + body + "}";
            } else {
                body = remaining;
                expr = "";
            }
        }
        List<String> result5 = _separate(constructor, ";", null);
        String start = result5.get(0);
        String cndn = result5.get(1);
        String increment = result5.size() == 3 ? result5.get(2) : "";
        interpretExpression(start, localVars, allowRecursion);
        while (true) {
            if (!((boolean) jsTernary(interpretExpression(cndn, localVars, allowRecursion), true, false))) {
                break;
            }
            try {
                StatementResult loopResult = interpretStatement(body, localVars, allowRecursion);
                if (loopResult.shouldReturn) {
                    return loopResult;
                }
            } catch (JS_Break jsBreak) {
                break;
            } catch (JS_Continue ignored) {
            }
            interpretExpression(increment, localVars, allowRecursion);
        }
        return interpretStatement(expr, localVars, allowRecursion);
    }

    private StatementResult handleSwitch(String expr, Matcher m, LocalNameSpace localVars, int allowRecursion, boolean shouldReturn) throws Exception {
        List<String> result = separateAtParen(expr.substring(m.end() - 1), null);
        Object switchVal = result.get(0).substring(1);
        String remaining = result.get(1);
        switchVal = interpretExpression((String) switchVal, localVars, allowRecursion);
        List<String> result2 = separateAtParen(remaining, "}");
        String body = result2.get(0).substring(1);
        expr = result2.get(1);
        String replacedBody = body.replace("default:", "case default:");
        String[] cases = replacedBody.split("case ");
        List<String> items = Arrays.asList(cases).subList(1, cases.length);
        boolean[] defaults = {false, true};
        loop:
        for (boolean isDefault : defaults) {
            boolean matched = false;
            for (String item : items) {
                List<String> result3 = _separate(item, ":", 1);
                String _case = result3.get(0);
                String stmt = result3.get(1);
                if (isDefault) {
                    matched = matched || _case.equals("default");
                } else if (!matched) {
                    matched = !_case.equals("default") && jsEqOpIs(switchVal, interpretExpression(_case, localVars, allowRecursion));
                }
                if (!matched) {
                    continue;
                }
                try {
                    StatementResult caseResult = interpretStatement(stmt, localVars, allowRecursion);
                    if (caseResult.shouldReturn) {
                        return caseResult;
                    }
                } catch (JS_Break jsBreak) {
                    break loop;
                }
            }
            if (matched) {
                break;
            }
        }
        return interpretStatement(expr, localVars, allowRecursion);
    }

    private Object extractGlobalVar(String var, LocalNameSpace localVars) {
        // MODIFIED: Sử dụng hằng số Pattern đã được biên dịch trước.
        Matcher matcher = Pattern.compile("var\\s?" + Pattern.quote(var) + "=(?<var>.*?)[,;]").matcher(code);
        if (matcher.find()) {
            Object code = matcher.group("var");
            localVars.put(var, code);
            return code;
        } else {
            return null;
        }
    }

    private Object extractObject(String objName, LocalNameSpace globalStack) throws Exception {
        Map<Object, FunctionWithRepr> obj = new HashMap<>();
        // MODIFIED: Sử dụng hằng số Pattern đã được biên dịch trước.
        Pattern pattern = Pattern.compile("(?x)" +
                "(?<![a-zA-Z$0-9.])" + Matcher.quoteReplacement(objName) + "\\s*=\\s*\\{\\s*" +
                "(?<fields>((?:[a-zA-Z$0-9]+|\"[a-zA-Z$0-9]+\"|'[a-zA-Z$0-9]+')" +
                "\\s*:\\s*function\\s*\\(.*?\\)\\s*\\{.*?\\}(?:,\\s*)?)*)" +
                "\\}\\s*;");
        Matcher matcher = pattern.matcher(code);
        if (!matcher.find()) {
            throw new Exception("Could not find object " + objName);
        }
        String fields = Objects.requireNonNull(matcher.group("fields"));
        // MODIFIED: Sử dụng hằng số Pattern đã được biên dịch trước.
        Matcher fieldsM = OBJECT_FIELD_PATTERN.matcher(fields);
        while (fieldsM.find()) {
            List<String> argNames = List.of(Objects.requireNonNull(fieldsM.group("args")).split(","));
            String name = removeQuotes(fieldsM.group("key"));
            String code = fieldsM.group("code");
            obj.put(name, new FunctionWithRepr(buildFunction(argNames, code, globalStack.getAll()), "f<" + name + ">"));
        }
        return obj;
    }

    private String removeQuotes(String s) {
        if (s == null || s.length() < 2) {
            return s;
        }
        String[] quotes = {"\"", "'"};
        for (String quote : quotes) {
            if (s.charAt(0) == quote.charAt(0) && s.charAt(s.length() - 1) == quote.charAt(0)) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    private static int customIndexOf(List<Object> list, Object value, int start) {
        for (int i = start; i < list.size(); i++) {
            if (list.get(i).equals(value)) {
                return i;
            }
        }
        return -1;
    }

    private Object[] handleOperators(String expr, LocalNameSpace localVars, int allowRecursion) throws Exception {
        for (String op : ALL_OPERATORS.keySet()) {
            List<String> separated = _separate(expr, op, null);
            StringBuilder rightExpr = new StringBuilder(separated.remove(separated.size() - 1));
            while (true) {
                if ((op.equals("?") || op.equals("<") || op.equals(">") || op.equals("*") || op.equals("-")) && separated.size() > 1 && separated.get(separated.size() - 1).isEmpty()) {
                    separated.remove(separated.size() - 1);
                } else if (!(!separated.isEmpty() && op.equals("?") && rightExpr.toString().startsWith("."))) {
                    break;
                }
                rightExpr.insert(0, op);
                if (!op.equals("-")) {
                    rightExpr.insert(0, separated.remove(separated.size() - 1) + op);
                }
            }
            if (separated.isEmpty()) {
                continue;
            }
            Object leftVal = interpretExpression(String.join(op, separated), localVars, allowRecursion);
            return new Object[]{operator(op, leftVal, rightExpr.toString(), expr, localVars, allowRecursion), true};
        }
        return new Object[]{};
    }

    private Object operator(String op, Object leftVal, Object rightExpr, String expr, LocalNameSpace localVars, int allowRecursion) throws Exception {
        if (Objects.equals(op, "||") || Objects.equals(op, "&&")) {
            if ((Objects.equals(op, "&&") ^ (boolean) jsTernary(leftVal, true, false))) {
                return leftVal;
            }
        } else if (Objects.equals(op, "??")) {
            if (leftVal != null) {
                return leftVal;
            }
        } else if (Objects.equals(op, "?")) {
            rightExpr = jsTernary(leftVal, _separate((String) rightExpr, ":", 1).get(0), _separate((String) rightExpr, ":", 1).get(1));
        }

        Object rightVal = interpretExpression((String) rightExpr, localVars, allowRecursion);
        if (!OPERATORS.containsKey(op) || OPERATORS.get(op) == null) {
            return rightVal;

        }
        try {
            return Objects.requireNonNull(OPERATORS.get(op)).apply(leftVal, rightVal);
        } catch (Exception e) {
            throw new Exception("Failed to evaluate: " + leftVal + " " + op + " " + rightVal + " expr: " + expr);
        }
    }

    private Object index(Object obj, Object idx, boolean allow_undefined) throws Exception {
        if (idx.equals("length")) {
            if (obj instanceof ArrayList) {
                return ((ArrayList<?>) obj).size();
            } else {
                throw new Exception("Cannot get length for non-list object " + obj);
            }
        }
        try {
            if (obj instanceof ArrayList) {
                int finalIdx;
                if (idx instanceof Double) {
                    finalIdx = (int) Math.round((double) idx);
                } else {
                    if (idx instanceof String) {
                        try {
                            finalIdx = Integer.parseInt((String) idx);
                        } catch (NumberFormatException e) {
                            finalIdx = (int) Double.parseDouble((String) idx);
                        }
                    } else {
                        finalIdx = (int) idx;
                    }
                }
                return ((ArrayList<?>) obj).get(finalIdx);
            } else if (obj instanceof Map) {
                return ((Map<?, ?>) obj).get(idx);
            } else {
                throw new Exception("Cannot get index for unsupported object type " + obj);
            }
        } catch (Exception e) {
            if (allow_undefined) {
                return JS_Undefined;
            }
            throw new Exception("Cannot get index " + idx, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Object dump(Object obj, Object namespace) {
        if (obj == null) {
            obj = "null";
        }
        if (obj.toString().matches("-?\\d+(\\.\\d+)?")) {
            return Integer.parseInt(obj.toString());
        } else if (obj instanceof Boolean) {
            return obj.toString();
        } else if (Objects.equals(obj, "null")) {
            return "None";
        }
        assert namespace instanceof Map;
        return namedObject((Map<String, Object>) namespace, obj);
    }

    private static Object[] regexFlags(String expr) {
        int flags = 0;
        int idx;
        if (expr == null || expr.isEmpty()) {
            return new Object[]{flags, expr};
        }
        for (idx = 0; idx < expr.length(); idx++) {
            char ch = expr.charAt(idx);
            Integer tmp = RE_FLAGS.get(ch);
            if (tmp == null) {
                break;
            }
            flags |= tmp;
        }
        return new Object[]{flags, expr.substring(idx)};
    }

    private static List<String> getGroupNames(String regex) {
        List<String> groupNames = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>").matcher(regex);
        while (matcher.find()) {
            groupNames.add(matcher.group(1));
        }
        return groupNames;
    }

    private Object extractFunctionFromCode(List<String> argNames, String code, Map<String, Object> localVars) throws Exception {
        while (true) {
            // MODIFIED: Sử dụng hằng số Pattern đã được biên dịch trước.
            Matcher matcher = NESTED_FUNCTION_PATTERN.matcher(code);
            int start;
            int bodyStart;
            List<String> args;
            if (matcher.find()) {
                start = matcher.start();
                bodyStart = matcher.end();
                args = Arrays.asList(Objects.requireNonNull(matcher.group("args")).split(","));

                List<String> separated = separateAtParen(code.substring(bodyStart - 1), "}");
                String body = separated.get(0).substring(1);
                String remaining = separated.get(1);
                String name = namedObject(localVars, extractFunctionFromCode(args, body, localVars));
                code = code.substring(0, start) + name + remaining;
            } else {
                break;
            }
        }
        return buildFunction(argNames, code, localVars);
    }

    private String namedObject(Map<String, Object> namespace, Object obj) {
        namedObjectCounter++;
        String name = "__javatube_jsinterpreter_obj" + namedObjectCounter;
        if (obj instanceof Function && !(obj instanceof FunctionWithRepr)) {
            obj = new FunctionWithRepr(obj, "F<" + namedObjectCounter + ">");
        }
        namespace.put(name, obj);
        return name;
    }

    private Map<String, String> extractFunctionCode(String funName) throws Exception {
        String quotedFunName = Pattern.quote(funName);
        // MODIFIED: Sử dụng hằng số Pattern đã được biên dịch trước.
        Pattern pattern = Pattern.compile(
                String.format(FUNCTION_DEF_PATTERN_TEMPLATE.pattern(), quotedFunName, quotedFunName, quotedFunName)
        );
        Matcher matcher = pattern.matcher(code);
        String args;
        String code;
        Map<String, String> r = new HashMap<>();
        if (matcher.find()) {
            args = matcher.group("args");
            code = separateAtParen(matcher.group("code"), "}").get(0).substring(1);
            r.put("code", code);
            r.put("args", args);
        } else {
            throw new Exception("Could not find JS function " + funName);
        }
        return r;
    }

    String[] extractPlayerJsGlobalVar(String jsCode) {
        // MODIFIED: Sử dụng hằng số Pattern đã được biên dịch trước.
        Matcher matcher = PLAYER_JS_GLOBAL_VAR_PATTERN.matcher(jsCode);
        if (matcher.find()) {
            String name = matcher.group("name");
            String code = matcher.group("code");
            String value = matcher.group("value");
            return new String[]{name, code, value};
        } else {
            return new String[]{null, null, null};
        }
    }

    private String fixup_n_function_code(String[] argnames, String code, String fullCode) {
        String globalVar = extractPlayerJsGlobalVar(fullCode)[1];
        if (globalVar != null) {
            code = globalVar + "; " + code;
        }
        // MODIFIED: Sử dụng hằng số Pattern đã được biên dịch trước.
        Pattern pattern = Pattern.compile(String.format(UNDEFINED_CHECK_PATTERN_TEMPLATE.pattern(), Pattern.quote(argnames[0])));
        Matcher matcher = pattern.matcher(code);

        return matcher.replaceAll(";");
    }

    private FunctionWithRepr extractFuName(String funName) throws Exception {
        Map<String, String> code = extractFunctionCode(funName);
        Object obj = extractFunctionFromCode(List.of(Objects.requireNonNull(code.get("args")).split(",")), fixup_n_function_code(Objects.requireNonNull(code.get("args")).split(","), code.get("code"), this.code), new HashMap<>());
        return new FunctionWithRepr(obj, "F<" + funName + ">");
    }

    public Object callFunction(String funName, Object arg) throws Exception {
        return extractFuName(funName).call(arg);
    }

    private static Map<String, Object> zipLongest(Object[] arr1, Object[] arr2) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < arr1.length; i++) {
            Object value1 = arr1[i];
            Object value2 = (i < arr2.length) ? arr2[i] : null;
            result.put(value1.toString(), value2);
        }
        return result;
    }

    private Function<Object[], Object> buildFunction(List<String> argNames, String code, Map<String, Object> globalStack) throws JS_Throw {
        Object[] argNamesArray = argNames.toArray();

        return args -> {
            int allowRecursion = args.length == 1 ? 100 : (int) args[1];
            globalStack.putAll(zipLongest(argNamesArray, args.length == 1 ? args : (Object[]) args[0]));
            if (args.length == 3) {
                globalStack.putAll(castObjectToMap(args[2]));
            }
            LocalNameSpace varStack = new LocalNameSpace(globalStack);
            StatementResult result;
            try {
                // MODIFIED: Sử dụng StatementResult
                result = interpretStatement(code.replace("\n", " "), varStack, allowRecursion - 1);
            } catch (JS_Throw t) {
                throw t;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            if (result.shouldReturn) {
                return result.value;
            }
            return null;
        };
    }
}
