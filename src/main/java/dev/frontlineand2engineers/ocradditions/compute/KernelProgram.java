package dev.frontlineand2engineers.ocradditions.compute;

import dev.frontlineand2engineers.ocradditions.compute.CryptoKernels.Algorithm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Simple, high-level CUDA-style expression kernel compiler and fast slot-indexed evaluator.
 * Supports math, 32/53-bit bitwise ops, strings, arrays, and single-pass hash primitives
 * with zero assembly or low-level boilerplate.
 */
public final class KernelProgram {
    public static final int MAX_SOURCE_LENGTH = 4096;
    public static final int MAX_STATEMENTS = 64;
    public static final int MAX_AST_NODES = 256;

    private final List<Statement> statements;
    private final Map<String, Integer> varSlots;
    private final int slotCount;
    private final int hashOpCount;
    private final int astNodeCount;

    private KernelProgram(
            List<Statement> statements,
            Map<String, Integer> varSlots,
            int hashOpCount,
            int astNodeCount
    ) {
        this.statements = statements;
        this.varSlots = varSlots;
        this.slotCount = varSlots.size();
        this.hashOpCount = hashOpCount;
        this.astNodeCount = astNodeCount;
    }

    public int slotCount() {
        return slotCount;
    }

    public int statementCount() {
        return statements.size();
    }

    public int hashOpCount() {
        return hashOpCount;
    }

    public int astNodeCount() {
        return astNodeCount;
    }

    /**
     * Weighted per-thread operation cost used for energy calculation.
     */
    public double opWeight() {
        return Math.max(0.25, astNodeCount * 0.05 + hashOpCount * 1.0);
    }

    public int slotOf(String varName) {
        Integer slot = varSlots.get(varName);
        return slot != null ? slot : -1;
    }

    public Object[] createScratchVars() {
        return new Object[slotCount];
    }

    public UniformBinding bindUniforms(Map<String, Object> uniforms) {
        List<BoundScalar> scalars = new ArrayList<>();
        Map<String, Object> arrays = new LinkedHashMap<>();
        int idSlot = slotOf("id");
        int countSlot = slotOf("count");

        if (uniforms != null) {
            for (Map.Entry<String, Object> e : uniforms.entrySet()) {
                String name = e.getKey();
                Object val = e.getValue();
                if (val instanceof double[] || val instanceof String[]) {
                    arrays.put(name, val);
                } else {
                    int slot = slotOf(name);
                    if (slot >= 0) {
                        scalars.add(new BoundScalar(slot, val));
                    }
                }
            }
        }
        return new UniformBinding(idSlot, countSlot, scalars.toArray(new BoundScalar[0]), arrays);
    }

    public void execute(Object[] vars, UniformBinding binding, long threadId, long totalCount) {
        if (binding.idSlot >= 0) {
            vars[binding.idSlot] = (double) threadId;
        }
        if (binding.countSlot >= 0) {
            vars[binding.countSlot] = (double) totalCount;
        }
        for (BoundScalar s : binding.scalars) {
            vars[s.slot] = s.value;
        }
        for (Statement stmt : statements) {
            vars[stmt.targetSlot] = stmt.expr.eval(vars, binding.arrays);
        }
    }

    public static KernelProgram compile(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Kernel source cannot be empty");
        }
        if (source.length() > MAX_SOURCE_LENGTH) {
            throw new IllegalArgumentException("Kernel source exceeds max length (" + MAX_SOURCE_LENGTH + ")");
        }
        List<Token> tokens = tokenize(source);
        Parser parser = new Parser(tokens);
        return parser.parseProgram();
    }

    public static String formatValue(Object v) {
        if (v == null) return "";
        if (v instanceof String s) return s;
        if (v instanceof Boolean b) return b ? "true" : "false";
        if (v instanceof Number n) return formatNumber(n.doubleValue());
        return v.toString();
    }

    public static String formatNumber(double d) {
        if (Double.isFinite(d) && d == Math.rint(d) && Math.abs(d) < 9_007_199_254_740_992.0) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    public static boolean asBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0.0 && !Double.isNaN(n.doubleValue());
        if (v instanceof String s) return !s.isEmpty() && !"false".equalsIgnoreCase(s);
        return true;
    }

    public static double asDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof Boolean b) return b ? 1.0 : 0.0;
        if (v instanceof String s) {
            try {
                String clean = s.trim();
                if (clean.startsWith("0x") || clean.startsWith("0X")) {
                    return (double) Long.parseUnsignedLong(clean.substring(2), 16);
                }
                return Double.parseDouble(clean);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Cannot convert string '" + s + "' to number");
            }
        }
        throw new IllegalArgumentException("Expected number, got " + (v == null ? "nil" : v.getClass().getSimpleName()));
    }

    public static long asLong(Object v) {
        return (long) asDouble(v);
    }

    // -------------------------------------------------------------------------
    // Bindings & Statements
    // -------------------------------------------------------------------------

    public record BoundScalar(int slot, Object value) {
    }

    public record UniformBinding(
            int idSlot,
            int countSlot,
            BoundScalar[] scalars,
            Map<String, Object> arrays
    ) {
    }

    private record Statement(int targetSlot, Expr expr) {
    }

    // -------------------------------------------------------------------------
    // Lexer
    // -------------------------------------------------------------------------

    private enum TokType {
        IDENT, NUMBER, STRING,
        PLUS, MINUS, STAR, SLASH, PERCENT, CARET,
        EQ, NEQ, LT, LTE, GT, GTE,
        ASSIGN, AND, OR, NOT,
        LPAREN, RPAREN, LBRACK, RBRACK, COMMA, SEP, EOF
    }

    private record Token(TokType type, String text, double numValue) {
    }

    private static List<Token> tokenize(String src) {
        List<Token> out = new ArrayList<>();
        int n = src.length();
        int i = 0;

        while (i < n) {
            char c = src.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r') {
                i++;
                continue;
            }
            if (c == '\n' || c == ';') {
                out.add(new Token(TokType.SEP, "\n", 0));
                i++;
                continue;
            }
            if (c == '#' || (c == '/' && i + 1 < n && src.charAt(i + 1) == '/')
                    || (c == '-' && i + 1 < n && src.charAt(i + 1) == '-')) {
                while (i < n && src.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < n && src.charAt(i) != quote) {
                    char ch = src.charAt(i++);
                    if (ch == '\\' && i < n) {
                        char esc = src.charAt(i++);
                        switch (esc) {
                            case 'n' -> sb.append('\n');
                            case 't' -> sb.append('\t');
                            case 'r' -> sb.append('\r');
                            case '\\' -> sb.append('\\');
                            case '"' -> sb.append('"');
                            case '\'' -> sb.append('\'');
                            default -> sb.append(esc);
                        }
                    } else {
                        sb.append(ch);
                    }
                }
                if (i >= n) {
                    throw new IllegalArgumentException("Unterminated string literal in kernel");
                }
                i++;
                out.add(new Token(TokType.STRING, sb.toString(), 0));
                continue;
            }
            if (Character.isDigit(c)) {
                int start = i;
                if (c == '0' && i + 1 < n && (src.charAt(i + 1) == 'x' || src.charAt(i + 1) == 'X')) {
                    i += 2;
                    while (i < n && Character.digit(src.charAt(i), 16) >= 0) i++;
                    String hex = src.substring(start + 2, i);
                    if (hex.isEmpty()) throw new IllegalArgumentException("Invalid hex literal");
                    out.add(new Token(TokType.NUMBER, src.substring(start, i), Long.parseUnsignedLong(hex, 16)));
                } else {
                    while (i < n && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) i++;
                    if (i < n && (src.charAt(i) == 'e' || src.charAt(i) == 'E')) {
                        i++;
                        if (i < n && (src.charAt(i) == '+' || src.charAt(i) == '-')) i++;
                        while (i < n && Character.isDigit(src.charAt(i))) i++;
                    }
                    String numStr = src.substring(start, i);
                    out.add(new Token(TokType.NUMBER, numStr, Double.parseDouble(numStr)));
                }
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) i++;
                String word = src.substring(start, i);
                switch (word) {
                    case "and" -> out.add(new Token(TokType.AND, word, 0));
                    case "or" -> out.add(new Token(TokType.OR, word, 0));
                    case "not" -> out.add(new Token(TokType.NOT, word, 0));
                    default -> out.add(new Token(TokType.IDENT, word, 0));
                }
                continue;
            }
            String two = i + 1 < n ? src.substring(i, i + 2) : "";
            switch (two) {
                case "==" -> { out.add(new Token(TokType.EQ, two, 0)); i += 2; continue; }
                case "!=", "~=" -> { out.add(new Token(TokType.NEQ, two, 0)); i += 2; continue; }
                case "<=" -> { out.add(new Token(TokType.LTE, two, 0)); i += 2; continue; }
                case ">=" -> { out.add(new Token(TokType.GTE, two, 0)); i += 2; continue; }
                case "&&" -> { out.add(new Token(TokType.AND, two, 0)); i += 2; continue; }
                case "||" -> { out.add(new Token(TokType.OR, two, 0)); i += 2; continue; }
                default -> {}
            }
            switch (c) {
                case '+' -> out.add(new Token(TokType.PLUS, "+", 0));
                case '-' -> out.add(new Token(TokType.MINUS, "-", 0));
                case '*' -> out.add(new Token(TokType.STAR, "*", 0));
                case '/' -> out.add(new Token(TokType.SLASH, "/", 0));
                case '%' -> out.add(new Token(TokType.PERCENT, "%", 0));
                case '^' -> out.add(new Token(TokType.CARET, "^", 0));
                case '<' -> out.add(new Token(TokType.LT, "<", 0));
                case '>' -> out.add(new Token(TokType.GT, ">", 0));
                case '=' -> out.add(new Token(TokType.ASSIGN, "=", 0));
                case '!' -> out.add(new Token(TokType.NOT, "!", 0));
                case '(' -> out.add(new Token(TokType.LPAREN, "(", 0));
                case ')' -> out.add(new Token(TokType.RPAREN, ")", 0));
                case '[' -> out.add(new Token(TokType.LBRACK, "[", 0));
                case ']' -> out.add(new Token(TokType.RBRACK, "]", 0));
                case ',' -> out.add(new Token(TokType.COMMA, ",", 0));
                default -> throw new IllegalArgumentException("Unexpected character in kernel: '" + c + "'");
            }
            i++;
        }
        out.add(new Token(TokType.EOF, "", 0));
        return out;
    }

    // -------------------------------------------------------------------------
    // Parser
    // -------------------------------------------------------------------------

    private static final class Parser {
        private final List<Token> tokens;
        private final Map<String, Integer> slots = new LinkedHashMap<>();
        private int pos = 0;
        private int nodeCount = 0;
        private int hashOps = 0;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        private Token peek() {
            return tokens.get(pos);
        }

        private Token peekNext() {
            return pos + 1 < tokens.size() ? tokens.get(pos + 1) : tokens.get(tokens.size() - 1);
        }

        private Token advance() {
            return tokens.get(pos++);
        }

        private boolean match(TokType t) {
            if (peek().type == t) {
                advance();
                return true;
            }
            return false;
        }

        private Token expect(TokType t, String msg) {
            if (peek().type != t) {
                throw new IllegalArgumentException(msg + " (got '" + peek().text + "')");
            }
            return advance();
        }

        private int getOrAllocSlot(String name) {
            return slots.computeIfAbsent(name, k -> slots.size());
        }

        private Expr track(Expr e) {
            nodeCount++;
            if (nodeCount > MAX_AST_NODES) {
                throw new IllegalArgumentException("Kernel AST exceeds max complexity (" + MAX_AST_NODES + " nodes)");
            }
            return e;
        }

        KernelProgram parseProgram() {
            List<Statement> stmts = new ArrayList<>();
            while (peek().type != TokType.EOF) {
                while (match(TokType.SEP)) {
                    // skip empty separators
                }
                if (peek().type == TokType.EOF) break;

                if (stmts.size() >= MAX_STATEMENTS) {
                    throw new IllegalArgumentException("Kernel exceeds max statement count (" + MAX_STATEMENTS + ")");
                }

                if (peek().type == TokType.IDENT && peekNext().type == TokType.ASSIGN) {
                    String varName = advance().text;
                    advance(); // '='
                    Expr expr = parseOr();
                    int slot = getOrAllocSlot(varName);
                    stmts.add(new Statement(slot, expr));
                } else {
                    Expr expr = parseOr();
                    int slot = getOrAllocSlot("result");
                    stmts.add(new Statement(slot, expr));
                }

                if (peek().type != TokType.EOF && !match(TokType.SEP)) {
                    throw new IllegalArgumentException("Expected newline or ';' after statement, got '" + peek().text + "'");
                }
            }
            if (stmts.isEmpty()) {
                throw new IllegalArgumentException("Kernel contains no statements");
            }
            return new KernelProgram(List.copyOf(stmts), Map.copyOf(slots), hashOps, nodeCount);
        }

        private Expr parseOr() {
            Expr left = parseAnd();
            while (match(TokType.OR)) {
                Expr r = parseAnd();
                Expr l = left;
                left = track((vars, arrs) -> asBool(l.eval(vars, arrs)) || asBool(r.eval(vars, arrs)));
            }
            return left;
        }

        private Expr parseAnd() {
            Expr left = parseCmp();
            while (match(TokType.AND)) {
                Expr r = parseCmp();
                Expr l = left;
                left = track((vars, arrs) -> asBool(l.eval(vars, arrs)) && asBool(r.eval(vars, arrs)));
            }
            return left;
        }

        private Expr parseCmp() {
            Expr left = parseAdd();
            while (true) {
                TokType t = peek().type;
                if (t == TokType.EQ || t == TokType.NEQ || t == TokType.LT
                        || t == TokType.LTE || t == TokType.GT || t == TokType.GTE) {
                    advance();
                    Expr r = parseAdd();
                    Expr l = left;
                    left = track((vars, arrs) -> compareValues(t, l.eval(vars, arrs), r.eval(vars, arrs)));
                } else {
                    break;
                }
            }
            return left;
        }

        private Expr parseAdd() {
            Expr left = parseMul();
            while (true) {
                if (match(TokType.PLUS)) {
                    Expr r = parseMul();
                    Expr l = left;
                    left = track((vars, arrs) -> asDouble(l.eval(vars, arrs)) + asDouble(r.eval(vars, arrs)));
                } else if (match(TokType.MINUS)) {
                    Expr r = parseMul();
                    Expr l = left;
                    left = track((vars, arrs) -> asDouble(l.eval(vars, arrs)) - asDouble(r.eval(vars, arrs)));
                } else {
                    break;
                }
            }
            return left;
        }

        private Expr parseMul() {
            Expr left = parsePow();
            while (true) {
                if (match(TokType.STAR)) {
                    Expr r = parsePow();
                    Expr l = left;
                    left = track((vars, arrs) -> asDouble(l.eval(vars, arrs)) * asDouble(r.eval(vars, arrs)));
                } else if (match(TokType.SLASH)) {
                    Expr r = parsePow();
                    Expr l = left;
                    left = track((vars, arrs) -> {
                        double denom = asDouble(r.eval(vars, arrs));
                        return denom == 0.0 ? 0.0 : asDouble(l.eval(vars, arrs)) / denom;
                    });
                } else if (match(TokType.PERCENT)) {
                    Expr r = parsePow();
                    Expr l = left;
                    left = track((vars, arrs) -> {
                        double denom = asDouble(r.eval(vars, arrs));
                        return denom == 0.0 ? 0.0 : asDouble(l.eval(vars, arrs)) % denom;
                    });
                } else {
                    break;
                }
            }
            return left;
        }

        private Expr parsePow() {
            Expr left = parseUnary();
            if (match(TokType.CARET)) {
                Expr r = parsePow();
                return track((vars, arrs) -> Math.pow(asDouble(left.eval(vars, arrs)), asDouble(r.eval(vars, arrs))));
            }
            return left;
        }

        private Expr parseUnary() {
            if (match(TokType.MINUS)) {
                Expr inner = parseUnary();
                return track((vars, arrs) -> -asDouble(inner.eval(vars, arrs)));
            }
            if (match(TokType.NOT)) {
                Expr inner = parseUnary();
                return track((vars, arrs) -> !asBool(inner.eval(vars, arrs)));
            }
            return parsePrimary();
        }

        private Expr parsePrimary() {
            Token t = peek();
            if (t.type == TokType.NUMBER) {
                advance();
                double val = t.numValue;
                return track((vars, arrs) -> val);
            }
            if (t.type == TokType.STRING) {
                advance();
                String val = t.text;
                return track((vars, arrs) -> val);
            }
            if (match(TokType.LPAREN)) {
                Expr inner = parseOr();
                expect(TokType.RPAREN, "Expected ')'");
                return inner;
            }
            if (t.type == TokType.IDENT) {
                advance();
                String name = t.text;
                if ("true".equals(name)) return track((vars, arrs) -> Boolean.TRUE);
                if ("false".equals(name)) return track((vars, arrs) -> Boolean.FALSE);

                if (match(TokType.LPAREN)) {
                    List<Expr> args = new ArrayList<>();
                    if (peek().type != TokType.RPAREN) {
                        do {
                            args.add(parseOr());
                        } while (match(TokType.COMMA));
                    }
                    expect(TokType.RPAREN, "Expected ')' after function arguments");
                    return buildFunctionCall(name, args);
                }

                if (match(TokType.LBRACK)) {
                    Expr idxExpr = parseOr();
                    expect(TokType.RBRACK, "Expected ']' after array index");
                    return track((vars, arrs) -> readArray(arrs, name, (int) asLong(idxExpr.eval(vars, arrs))));
                }

                int slot = getOrAllocSlot(name);
                return track((vars, arrs) -> vars[slot]);
            }
            throw new IllegalArgumentException("Unexpected token in expression: '" + t.text + "'");
        }

        private Expr buildFunctionCall(String rawName, List<Expr> args) {
            String fn = rawName.toLowerCase(Locale.ROOT);
            if ("iif".equals(fn) || "ifelse".equals(fn)) {
                checkArgCount(rawName, args, 3);
                Expr c = args.get(0), a = args.get(1), b = args.get(2);
                return track((vars, arrs) -> asBool(c.eval(vars, arrs)) ? a.eval(vars, arrs) : b.eval(vars, arrs));
            }

            if ("sha256".equals(fn) || "sha512".equals(fn) || "sha3".equals(fn) || "sha3_256".equals(fn) || "md5".equals(fn)) {
                checkArgCount(rawName, args, 1);
                hashOps++;
                Algorithm algo = switch (fn) {
                    case "sha512" -> Algorithm.SHA512;
                    case "sha3", "sha3_256" -> Algorithm.SHA3_256;
                    case "md5" -> Algorithm.MD5;
                    default -> Algorithm.SHA256;
                };
                Expr arg = args.get(0);
                return track((vars, arrs) -> {
                    byte[] input = CryptoKernels.utf8(formatValue(arg.eval(vars, arrs)));
                    return CryptoKernels.toHex(CryptoKernels.hashBytes(algo, input));
                });
            }

            return switch (fn) {
                case "concat" -> {
                    if (args.isEmpty() || args.size() > 16) {
                        throw new IllegalArgumentException("concat expects 1..16 arguments");
                    }
                    Expr[] arr = args.toArray(new Expr[0]);
                    yield track((vars, arrs) -> {
                        StringBuilder sb = new StringBuilder();
                        for (Expr e : arr) {
                            sb.append(formatValue(e.eval(vars, arrs)));
                        }
                        return sb.toString();
                    });
                }
                case "str" -> {
                    checkArgCount(rawName, args, 1);
                    Expr a = args.get(0);
                    yield track((vars, arrs) -> formatValue(a.eval(vars, arrs)));
                }
                case "num" -> {
                    checkArgCount(rawName, args, 1);
                    Expr a = args.get(0);
                    yield track((vars, arrs) -> asDouble(a.eval(vars, arrs)));
                }
                case "len" -> {
                    checkArgCount(rawName, args, 1);
                    Expr a = args.get(0);
                    yield track((vars, arrs) -> (double) formatValue(a.eval(vars, arrs)).length());
                }
                case "sub" -> {
                    if (args.size() < 2 || args.size() > 3) {
                        throw new IllegalArgumentException("sub expects 2 or 3 arguments: sub(str, start[, len])");
                    }
                    Expr sExpr = args.get(0), startExpr = args.get(1);
                    Expr lenExpr = args.size() == 3 ? args.get(2) : null;
                    yield track((vars, arrs) -> {
                        String s = formatValue(sExpr.eval(vars, arrs));
                        int start = Math.clamp((int) asLong(startExpr.eval(vars, arrs)), 0, s.length());
                        int end = lenExpr != null
                                ? Math.clamp(start + (int) asLong(lenExpr.eval(vars, arrs)), start, s.length())
                                : s.length();
                        return s.substring(start, end);
                    });
                }
                case "startswith" -> {
                    checkArgCount(rawName, args, 2);
                    Expr sExpr = args.get(0), pExpr = args.get(1);
                    yield track((vars, arrs) -> formatValue(sExpr.eval(vars, arrs)).startsWith(formatValue(pExpr.eval(vars, arrs))));
                }
                case "contains" -> {
                    checkArgCount(rawName, args, 2);
                    Expr sExpr = args.get(0), pExpr = args.get(1);
                    yield track((vars, arrs) -> formatValue(sExpr.eval(vars, arrs)).contains(formatValue(pExpr.eval(vars, arrs))));
                }
                case "upper" -> {
                    checkArgCount(rawName, args, 1);
                    Expr a = args.get(0);
                    yield track((vars, arrs) -> formatValue(a.eval(vars, arrs)).toUpperCase(Locale.ROOT));
                }
                case "lower" -> {
                    checkArgCount(rawName, args, 1);
                    Expr a = args.get(0);
                    yield track((vars, arrs) -> formatValue(a.eval(vars, arrs)).toLowerCase(Locale.ROOT));
                }
                case "leadingzerobits", "zerobits" -> {
                    checkArgCount(rawName, args, 1);
                    Expr a = args.get(0);
                    yield track((vars, arrs) -> (double) CryptoKernels.leadingZeroBitsHex(formatValue(a.eval(vars, arrs))));
                }
                case "leadingzeros", "zerochars" -> {
                    checkArgCount(rawName, args, 1);
                    Expr a = args.get(0);
                    yield track((vars, arrs) -> (double) CryptoKernels.leadingZeroNibblesHex(formatValue(a.eval(vars, arrs))));
                }
                case "hexbyte" -> {
                    checkArgCount(rawName, args, 2);
                    Expr hExpr = args.get(0), iExpr = args.get(1);
                    yield track((vars, arrs) -> {
                        String hex = formatValue(hExpr.eval(vars, arrs));
                        int off = (int) asLong(iExpr.eval(vars, arrs)) * 2;
                        if (off < 0 || off + 1 >= hex.length()) return 0.0;
                        int hi = Character.digit(hex.charAt(off), 16);
                        int lo = Character.digit(hex.charAt(off + 1), 16);
                        return (hi < 0 || lo < 0) ? 0.0 : (double) ((hi << 4) | lo);
                    });
                }
                case "hexat" -> {
                    checkArgCount(rawName, args, 2);
                    Expr hExpr = args.get(0), iExpr = args.get(1);
                    yield track((vars, arrs) -> {
                        String hex = formatValue(hExpr.eval(vars, arrs));
                        int off = (int) asLong(iExpr.eval(vars, arrs));
                        if (off < 0 || off >= hex.length()) return 0.0;
                        int d = Character.digit(hex.charAt(off), 16);
                        return d < 0 ? 0.0 : (double) d;
                    });
                }
                case "tohex" -> {
                    if (args.isEmpty() || args.size() > 2) {
                        throw new IllegalArgumentException("toHex expects 1 or 2 arguments: toHex(n[, width])");
                    }
                    Expr nExpr = args.get(0);
                    Expr wExpr = args.size() == 2 ? args.get(1) : null;
                    yield track((vars, arrs) -> {
                        String hex = Long.toHexString(asLong(nExpr.eval(vars, arrs)));
                        if (wExpr != null) {
                            int w = Math.clamp((int) asLong(wExpr.eval(vars, arrs)), 1, 32);
                            if (hex.length() < w) {
                                hex = "0".repeat(w - hex.length()) + hex;
                            }
                        }
                        return hex;
                    });
                }
                case "abs" -> unaryMath(args, Math::abs);
                case "floor" -> unaryMath(args, Math::floor);
                case "ceil" -> unaryMath(args, Math::ceil);
                case "round" -> unaryMath(args, x -> (double) Math.round(x));
                case "sqrt" -> unaryMath(args, Math::sqrt);
                case "sin" -> unaryMath(args, Math::sin);
                case "cos" -> unaryMath(args, Math::cos);
                case "tan" -> unaryMath(args, Math::tan);
                case "log" -> unaryMath(args, Math::log);
                case "exp" -> unaryMath(args, Math::exp);
                case "min" -> binaryMath(args, Math::min);
                case "max" -> binaryMath(args, Math::max);
                case "pow" -> binaryMath(args, Math::pow);
                case "clamp" -> {
                    checkArgCount(rawName, args, 3);
                    Expr x = args.get(0), lo = args.get(1), hi = args.get(2);
                    yield track((vars, arrs) -> Math.max(asDouble(lo.eval(vars, arrs)),
                            Math.min(asDouble(hi.eval(vars, arrs)), asDouble(x.eval(vars, arrs)))));
                }
                case "lerp" -> {
                    checkArgCount(rawName, args, 3);
                    Expr a = args.get(0), b = args.get(1), tExpr = args.get(2);
                    yield track((vars, arrs) -> {
                        double av = asDouble(a.eval(vars, arrs));
                        double bv = asDouble(b.eval(vars, arrs));
                        double tv = asDouble(tExpr.eval(vars, arrs));
                        return av + (bv - av) * tv;
                    });
                }
                case "band" -> binaryBitwise(args, (a, b) -> (double) (a & b));
                case "bor" -> binaryBitwise(args, (a, b) -> (double) (a | b));
                case "bxor" -> binaryBitwise(args, (a, b) -> (double) (a ^ b));
                case "bnot" -> {
                    checkArgCount(rawName, args, 1);
                    Expr a = args.get(0);
                    yield track((vars, arrs) -> (double) (~asLong(a.eval(vars, arrs))));
                }
                case "shl" -> binaryBitwise(args, (a, b) -> (double) (a << (int) b));
                case "shr" -> binaryBitwise(args, (a, b) -> (double) (a >>> (int) b));
                case "ashr" -> binaryBitwise(args, (a, b) -> (double) (a >> (int) b));
                case "rotl32" -> binaryBitwise(args, (a, b) -> (double) Integer.toUnsignedLong(Integer.rotateLeft((int) a, (int) b)));
                case "rotr32" -> binaryBitwise(args, (a, b) -> (double) Integer.toUnsignedLong(Integer.rotateRight((int) a, (int) b)));
                default -> throw new IllegalArgumentException("Unknown kernel function: " + rawName);
            };
        }

        private Expr unaryMath(List<Expr> args, java.util.function.DoubleUnaryOperator fn) {
            checkArgCount("math", args, 1);
            Expr a = args.get(0);
            return track((vars, arrs) -> fn.applyAsDouble(asDouble(a.eval(vars, arrs))));
        }

        private Expr binaryMath(List<Expr> args, java.util.function.DoubleBinaryOperator fn) {
            checkArgCount("math", args, 2);
            Expr a = args.get(0), b = args.get(1);
            return track((vars, arrs) -> fn.applyAsDouble(asDouble(a.eval(vars, arrs)), asDouble(b.eval(vars, arrs))));
        }

        private Expr binaryBitwise(List<Expr> args, BitOp fn) {
            checkArgCount("bitwise", args, 2);
            Expr a = args.get(0), b = args.get(1);
            return track((vars, arrs) -> fn.apply(asLong(a.eval(vars, arrs)), asLong(b.eval(vars, arrs))));
        }

        private static void checkArgCount(String name, List<Expr> args, int expected) {
            if (args.size() != expected) {
                throw new IllegalArgumentException(name + " expects " + expected + " argument(s), got " + args.size());
            }
        }
    }

    @FunctionalInterface
    private interface Expr {
        Object eval(Object[] vars, Map<String, Object> arrays);
    }

    @FunctionalInterface
    private interface BitOp {
        double apply(long a, long b);
    }

    private static Object readArray(Map<String, Object> arrays, String name, int index) {
        Object arr = arrays.get(name);
        if (arr == null) {
            throw new IllegalArgumentException("Unknown array parameter: " + name);
        }
        if (arr instanceof double[] dArr) {
            if (index < 0 || index >= dArr.length) {
                throw new IllegalArgumentException("Array index out of bounds for " + name + ": " + index + " (size " + dArr.length + ")");
            }
            return dArr[index];
        }
        if (arr instanceof String[] sArr) {
            if (index < 0 || index >= sArr.length) {
                throw new IllegalArgumentException("Array index out of bounds for " + name + ": " + index + " (size " + sArr.length + ")");
            }
            return sArr[index];
        }
        throw new IllegalArgumentException("Parameter is not indexable: " + name);
    }

    private static boolean compareValues(TokType op, Object left, Object right) {
        if (op == TokType.EQ) {
            if (left instanceof Number && right instanceof Number) {
                return asDouble(left) == asDouble(right);
            }
            return formatValue(left).equals(formatValue(right));
        }
        if (op == TokType.NEQ) {
            if (left instanceof Number && right instanceof Number) {
                return asDouble(left) != asDouble(right);
            }
            return !formatValue(left).equals(formatValue(right));
        }
        if (left instanceof String ls && right instanceof String rs) {
            int cmp = ls.compareTo(rs);
            return switch (op) {
                case LT -> cmp < 0;
                case LTE -> cmp <= 0;
                case GT -> cmp > 0;
                case GTE -> cmp >= 0;
                default -> false;
            };
        }
        double a = asDouble(left);
        double b = asDouble(right);
        return switch (op) {
            case LT -> a < b;
            case LTE -> a <= b;
            case GT -> a > b;
            case GTE -> a >= b;
            default -> false;
        };
    }
}
