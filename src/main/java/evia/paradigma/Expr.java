package evia.paradigma;

/**
 * AST of the tiny expression language used inside blocks, plus its evaluator.
 * Values are either Double or String. Booleans are 1.0 / 0.0.
 */
sealed interface Expr {
    /** Variable lookup used during evaluation (program variables and this.fields). */
    interface Env {
        Object get(String name);
    }

    record Num(double value) implements Expr {}
    record Str(String value) implements Expr {}
    record Var(String name) implements Expr {}
    record Unary(String op, Expr operand) implements Expr {}
    record Binary(String op, Expr left, Expr right) implements Expr {}

    static Object eval(Expr e, Env vars) {
        return switch (e) {
            case Num n -> n.value();
            case Str s -> s.value();
            case Var v -> vars.get(v.name());
            case Unary u -> {
                Object x = eval(u.operand(), vars);
                yield u.op().equals("-") ? (Object) (-num(x)) : bool(!truthy(x));
            }
            case Binary b -> binary(b, vars);
        };
    }

    private static Object binary(Binary b, Env vars) {
        String op = b.op();
        // short-circuit operators
        if (op.equals("&&")) return bool(truthy(eval(b.left(), vars)) && truthy(eval(b.right(), vars)));
        if (op.equals("||")) return bool(truthy(eval(b.left(), vars)) || truthy(eval(b.right(), vars)));

        Object l = eval(b.left(), vars);
        Object r = eval(b.right(), vars);
        return switch (op) {
            case "+" -> (l instanceof String || r instanceof String) ? str(l) + str(r) : (Object) (num(l) + num(r));
            case "-" -> num(l) - num(r);
            case "*" -> num(l) * num(r);
            case "/" -> num(l) / nonZero(num(r));
            case "%" -> num(l) % nonZero(num(r));
            case "==" -> bool(equal(l, r));
            case "!=" -> bool(!equal(l, r));
            case "<" -> bool(compare(l, r) < 0);
            case "<=" -> bool(compare(l, r) <= 0);
            case ">" -> bool(compare(l, r) > 0);
            case ">=" -> bool(compare(l, r) >= 0);
            default -> throw new FlowException("unknown operator " + op);
        };
    }

    // ---- value helpers ----

    static double num(Object x) {
        if (x instanceof Double d) return d;
        try {
            return Double.parseDouble(x.toString().trim());
        } catch (NumberFormatException e) {
            throw new FlowException("'" + x + "' is not a number");
        }
    }

    static String str(Object x) {
        if (x instanceof Double d) {
            double v = d;
            if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
            return Double.toString(v);
        }
        return String.valueOf(x);
    }

    static boolean truthy(Object x) {
        if (x instanceof Double d) return d != 0.0;
        return !x.toString().isEmpty();
    }

    private static Double bool(boolean b) {
        return b ? 1.0 : 0.0;
    }

    private static double nonZero(double d) {
        if (d == 0.0) throw new FlowException("division by zero");
        return d;
    }

    private static boolean equal(Object l, Object r) {
        if (l instanceof Double a && r instanceof Double b) return a.doubleValue() == b.doubleValue();
        return str(l).equals(str(r));
    }

    private static int compare(Object l, Object r) {
        if (l instanceof String a && r instanceof String b) return a.compareTo(b);
        return Double.compare(num(l), num(r));
    }
}
