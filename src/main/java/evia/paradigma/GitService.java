package evia.paradigma;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the git installed on this computer. It uses whatever login git already has
 * (Git Credential Manager, SSH keys, gh auth ...); EVIA Paradigma never sees passwords or tokens.
 */
final class GitService {
    private GitService() {}

    record Result(int code, String output) {
        boolean ok() {
            return code == 0;
        }
    }

    private static String cached;

    /** The git executable, or null if git is not installed. */
    static synchronized String git() {
        if (cached != null) return cached.isEmpty() ? null : cached;
        String override = System.getProperty("evia.git");
        List<String> candidates = new ArrayList<>();
        if (override != null) candidates.add(override);
        candidates.add("git");
        if (ExternalEditor.WINDOWS) {
            for (String base : new String[]{System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"),
                    System.getenv("LOCALAPPDATA") == null ? null : System.getenv("LOCALAPPDATA") + "\\Programs"}) {
                if (base != null) candidates.add(base + "\\Git\\cmd\\git.exe");
            }
        } else {
            candidates.add("/usr/bin/git");
            candidates.add("/opt/homebrew/bin/git");
            candidates.add("/usr/local/bin/git");
        }
        for (String c : candidates) {
            try {
                Process p = new ProcessBuilder(c, "--version").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    cached = c;
                    return c;
                }
            } catch (IOException | InterruptedException e) {
                // try the next one
            }
        }
        cached = "";
        return null;
    }

    static boolean available() {
        return git() != null;
    }

    /** Runs git in {@code dir}; never waits for a terminal prompt. */
    static Result run(Path dir, String... args) throws IOException {
        return run(dir, 600, args);
    }

    static Result run(Path dir, int timeoutSeconds, String... args) throws IOException {
        String git = git();
        if (git == null) throw new IOException("git is not installed (https://git-scm.com/downloads)");
        List<String> cmd = new ArrayList<>();
        cmd.add(git);
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_EDITOR", "true");   // never open an editor we can't see
        pb.environment().putIfAbsent("LC_ALL", "C");
        Process p = pb.start();
        p.getOutputStream().close();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                in.transferTo(buf);
            } catch (IOException ignored) {
                // process ended
            }
        });
        reader.start();
        try {
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("git " + args[0] + " did not finish in " + timeoutSeconds + " s");
            }
            reader.join(2000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        return new Result(p.exitValue(), buf.toString(StandardCharsets.UTF_8).strip());
    }

    private static Result check(Result r, String what) throws IOException {
        if (!r.ok()) throw new IOException(what + " failed:\n" + r.output());
        return r;
    }

    static boolean isRepo(Path dir) {
        try {
            Result r = run(dir, 30, "rev-parse", "--is-inside-work-tree");
            return r.ok() && r.output().endsWith("true");
        } catch (IOException e) {
            return false;
        }
    }

    /** {@code git init} with branch main (also on git older than 2.28). */
    static void init(Path dir) throws IOException {
        Result r = run(dir, 60, "init", "-b", "main");
        if (!r.ok()) {
            check(run(dir, 60, "init"), "git init");
            check(run(dir, 60, "symbolic-ref", "HEAD", "refs/heads/main"), "git symbolic-ref");
        }
    }

    static String config(Path dir, String key) throws IOException {
        Result r = run(dir, 30, "config", "--get", key);
        return r.ok() ? r.output() : "";
    }

    static void setLocalConfig(Path dir, String key, String value) throws IOException {
        check(run(dir, 30, "config", key, value), "git config");
    }

    static boolean hasIdentity(Path dir) throws IOException {
        return !config(dir, "user.name").isEmpty() && !config(dir, "user.email").isEmpty();
    }

    /** {@code git status --short}; empty when there is nothing to commit. */
    static String status(Path dir) throws IOException {
        return check(run(dir, 60, "status", "--short", "--untracked-files=all"), "git status").output();
    }

    static String branch(Path dir) throws IOException {
        Result r = run(dir, 30, "rev-parse", "--abbrev-ref", "HEAD");
        return r.ok() ? r.output() : "main";
    }

    /** Stages everything and commits. Returns git's summary. */
    static String commitAll(Path dir, String message) throws IOException {
        check(run(dir, 120, "add", "-A"), "git add");
        Path msg = Files.createTempFile("evia-commit", ".txt");
        try {
            Files.writeString(msg, message, StandardCharsets.UTF_8);
            return check(run(dir, 120, "commit", "-F", msg.toString()), "git commit").output();
        } finally {
            Files.deleteIfExists(msg);
        }
    }

    static String remoteUrl(Path dir) throws IOException {
        Result r = run(dir, 30, "remote", "get-url", "origin");
        return r.ok() ? r.output() : "";
    }

    static void setRemote(Path dir, String url) throws IOException {
        if (remoteUrl(dir).isEmpty()) check(run(dir, 30, "remote", "add", "origin", url), "git remote add");
        else check(run(dir, 30, "remote", "set-url", "origin", url), "git remote set-url");
    }

    static boolean hasUpstream(Path dir) throws IOException {
        return run(dir, 30, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}").ok();
    }

    static String push(Path dir) throws IOException {
        Result r = hasUpstream(dir) ? run(dir, "push") : run(dir, "push", "-u", "origin", "HEAD");
        return check(r, "git push").output();
    }

    /** Fetches and merges the remote branch; refuses (instead of creating a merge) when histories diverged. */
    static String pull(Path dir) throws IOException {
        Result r = hasUpstream(dir) ? run(dir, "pull", "--ff-only")
                : run(dir, "pull", "--ff-only", "origin", branch(dir));
        return check(r, "git pull").output();
    }

    static String log(Path dir) throws IOException {
        Result r = run(dir, 60, "log", "--date=short", "--pretty=format:%h  %ad  %an  %s", "-n", "100");
        return r.ok() ? r.output() : "(no commits yet)";
    }

    private static final Pattern GITHUB = Pattern.compile(
            "^(?:https?://(?:[^@/]+@)?github\\.com/|git@github\\.com:|ssh://git@github\\.com/)([^/]+)/(.+?)(?:\\.git)?/?$");

    /** https://github.com/owner/repo for a GitHub remote URL (https or ssh), else null. */
    static String webUrl(String remote) {
        Matcher m = GITHUB.matcher(remote.trim());
        return m.matches() ? "https://github.com/" + m.group(1) + "/" + m.group(2) : null;
    }

    /** Accepts "owner/repo", a github.com page URL, an https clone URL or an ssh URL. */
    static String normalizeRemote(String text) {
        String s = text.trim();
        if (s.matches("[A-Za-z0-9-]+/[A-Za-z0-9._-]+")) return "https://github.com/" + s.replaceFirst("\\.git$", "") + ".git";
        String web = webUrl(s);
        if (web != null && s.startsWith("http")) return web + ".git";
        return s;
    }
}
