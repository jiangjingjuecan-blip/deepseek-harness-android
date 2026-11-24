package com.sunwin.deepseekharness;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A closed vocabulary. Raw lines, URLs, exception messages and opaque IDs cannot reach disk. */
final class Diagnostics {
    enum Event { SERVICE_START, SERVICE_STOP, RUNTIME_START, RUNTIME_READY, RUNTIME_ERROR,
        PROOT_DSH_EXIT, RUNTIME_TIMEOUT, HTTP_BOOTSTRAP, HTTP_ACCOUNT, HTTP_MODELS, HTTP_CHAT,
        WS_OPEN, STREAM_ERROR, LOGIN_PENDING, LOGIN_EXCHANGE, LOGIN_STORED, LOGIN_CONFIRMED,
        LOGIN_SIGNED_OUT, LOGIN_FAILED, MODEL_ERROR, BALANCE_ERROR, CHAT_COMPLETE, CHAT_INTERRUPTED,
        LOADER_ERROR, PERMISSION_ERROR, NATIVE_SIGNAL, PROCESS_ERROR, NETWORK_ERROR,
        RUNTIME_RENAME_UNAVAILABLE, NATIVE_MODULE_ERROR, PLATFORM_HTTP }
    private static final Pattern SIGNAL = Pattern.compile("signal (\\d{1,2})(?:\\D|$)");
    private static final Pattern HTTP_STATUS = Pattern.compile("^\\s*status:\\s*([1-5][0-9]{2}),?\\s*$");
    private final File current, previous;
    Diagnostics(File dir) {
        current = new File(dir, "diagnostics.log");
        previous = new File(dir, "diagnostics.previous");
    }
    static Event classify(String line) {
        if (line.contains("ENOSYS") && line.contains("rename")) return Event.RUNTIME_RENAME_UNAVAILABLE;
        if (line.contains("ERR_DLOPEN_FAILED")) return Event.NATIVE_MODULE_ERROR;
        if (line.startsWith("proot error:") && line.contains("Permission denied")) return Event.PERMISSION_ERROR;
        if (line.contains("CANNOT LINK EXECUTABLE") || line.contains("error while loading shared libraries")) return Event.LOADER_ERROR;
        if (line.startsWith("proot info:") && SIGNAL.matcher(line).find()) return Event.NATIVE_SIGNAL;
        if (line.startsWith("proot error:")) return Event.PROCESS_ERROR;
        return null;
    }
    void childLine(String line) {
        Matcher status = HTTP_STATUS.matcher(line);
        if (status.matches()) { add(Event.PLATFORM_HTTP, Integer.parseInt(status.group(1))); return; }
        Event event = classify(line);
        if (event == null) return;
        Matcher sig = SIGNAL.matcher(line);
        if (event == Event.NATIVE_SIGNAL && sig.find()) add(event, Integer.parseInt(sig.group(1)));
        else add(event);
    }
    void add(Event e) { write(e.name()); }
    void add(Event e, int number) { write(e.name() + " " + number); }
    private synchronized void write(String entry) {
        if (current.length() > 65536 && (previous.delete() || !previous.exists())) current.renameTo(previous);
        try (FileOutputStream out = new FileOutputStream(current, true)) {
            out.write((Instant.now() + " " + entry + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }
    synchronized void export(OutputStream out) throws IOException {
        for (File f : new File[]{previous, current}) if (f.isFile()) Files.copy(f.toPath(), out);
    }
}
