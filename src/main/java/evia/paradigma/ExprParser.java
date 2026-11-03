package evia.paradigma;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for block expressions.
 *
 * <pre>
 * or      := and ( "||" and )*
 * and     := eq  ( "&&" eq )*
 * eq      := rel ( ("==" | "!=") rel )*
 * rel     := add ( ("<=" | "<" | ">=" | ">") add )*
 * add     := mul ( ("+" | "-") mul )*
 * mul     := unary ( ("*" | "/" | "%") unary )*
 * unary   := ("-" | "!") unary | primary
 * primary := NUMBER | STRING | true | false | IDENT | "this." IDENT | "(" or ")"
 * list    := [ or ( "," or )* ]          (argument lists of custom blocks)
 * </pre>
 */
final class ExprParser {
    private final String src;
    private int pos;

    private ExprParser(String src) {
        this.src = src;
    }

    static Expr parse(String src) {
        ExprParser p = new ExprParser(src);
        Expr e = p.or();
        p.skipWs();
        if (p.pos < src.length()) throw p.error("unexpected '" + src.charAt(p.pos) + "'");
        return e;
    }

    /** Comma-separated list of expressions; empty text gives an empty list. */
    static List<Expr> parseList(String src) {
        ExprParser p = new ExprParser(src);
        List<Expr> out = new ArrayList<>();
        p.skipWs();
        if (p.pos >= src.length()) return out;
        do {
            out.add(p.or());
        } while (p.eat(","));
        p.skipWs();
        if (p.pos < src.length()) throw p.error("unexpected '" + src.charAt(p.pos) + "'");
        return out;
    }

    private Expr or() {
        Expr e = and();
        while (eat("||")) e = new Expr.Binary("||", e, and());
        return e;
    }

    private Expr and() {
        Expr e = eq();
        while (eat("&&")) e = new Expr.Binary("&&", e, eq());
        return e;
    }

    private Expr eq() {
        Expr e = rel();
        while (true) {
            if (eat("==")) e = new Expr.Binary("==", e, rel());
            else if (eat("!=")) e = new Expr.Binary("!=", e, rel());
            else return e;
        }
    }

    private Expr rel() {
        Expr e = add();
        while (true) {
            if (eat("<=")) e = new Expr.Binary("<=", e, add());
            else if (eat(">=")) e = new Expr.Binary(">=", e, add());
            else if (eat("<")) e = new Expr.Binary("<", e, add());
            else if (eat(">")) e = new Expr.Binary(">", e, add());
            else return e;
        }
    }

    private Expr add() {
        Expr e = mul();
        while (true) {
            if (eat("+")) e = new Expr.Binary("+", e, mul());
            else if (eat("-")) e = new Expr.Binary("-", e, mul());
            else return e;
        }
    }

    private Expr mul() {
        Expr e = unary();
        while (true) {
            if (eat("*")) e = new Expr.Binary("*", e, unary());
            else if (eat("/")) e = new Expr.Binary("/", e, unary());
            else if (eat("%")) e = new Expr.Binary("%", e, unary());
            else return e;
        }
    }

    private Expr unary() {
        if (eat("-")) return new Expr.Unary("-", unary());
        if (eat("!")) return new Expr.Unary("!", unary());
        return primary();
    }

    private Expr primary() {
        skipWs();
        if (pos >= src.length()) throw error("unexpected end of expression");
        char c = src.charAt(pos);

        if (Character.isDigit(c) || c == '.') {
            int start = pos;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) pos++;
            try {
                return new Expr.Num(Double.parseDouble(src.substring(start, pos)));
            } catch (NumberFormatException e) {
                throw error("bad number '" + src.substring(start, pos) + "'");
            }
        }
        if (c == '"') {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < src.length() && src.charAt(pos) != '"') {
                char ch = src.charAt(pos++);
                if (ch == '\\' && pos < src.length()) {
                    char esc = src.charAt(pos++);
                    sb.append(switch (esc) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        default -> esc;
                    });
                } else {
                    sb.append(ch);
                }
            }
            if (pos >= src.length()) throw error("missing closing quote");
            pos++;
            return new Expr.Str(sb.toString());
        }
        if (Character.isLetter(c) || c == '_') {
            int start = pos;
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
            String name = src.substring(start, pos);
            if (name.equals("this") && pos < src.length() && src.charAt(pos) == '.') {
                pos++;
                int fs = pos;
                while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
                if (fs == pos || Character.isDigit(src.charAt(fs))) throw error("expected a field name after 'this.'");
                return new Expr.Var("this." + src.substring(fs, pos));
            }
            return switch (name) {
                case "true" -> new Expr.Num(1);
                case "false" -> new Expr.Num(0);
                default -> new Expr.Var(name);
            };
        }
        if (eat("(")) {
            Expr e = or();
            if (!eat(")")) throw error("missing ')'");
            return e;
        }
        throw error("unexpected '" + c + "'");
    }

    // ---- lexer helpers ----

    private boolean eat(String token) {
        skipWs();
        if (src.startsWith(token, pos)) {
            pos += token.length();
            return true;
        }
        return false;
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private FlowException error(String msg) {
        return new FlowException("syntax error at column " + (pos + 1) + ": " + msg);
    }
}
