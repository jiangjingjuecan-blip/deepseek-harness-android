package com.sunwin.deepseekharness;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** PRoot and every guest ELF are installer-owned native libraries, including the glibc loader. */
final class Guest {
    private final Context context;
    private final Path root, nativeDir;
    Guest(Context context) {
        this.context = context;
        root = context.getNoBackupFilesDir().toPath().resolve("linux");
        nativeDir = Paths.get(context.getApplicationInfo().nativeLibraryDir);
    }
    private void write(String rel, String text) throws IOException {
        Files.write(root.resolve(rel), text.getBytes(StandardCharsets.UTF_8));
    }
    private void copy(String asset, String to) throws IOException {
        try (InputStream in = context.getAssets().open(asset)) {
            Files.copy(in, root.resolve(to), StandardCopyOption.REPLACE_EXISTING);
        }
    }
    private String readAsset(String name) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open(name), StandardCharsets.UTF_8))) {
            return reader.readLine();
        }
    }
    void updateDns() throws IOException {
        ConnectivityManager networks = context.getSystemService(ConnectivityManager.class);
        LinkProperties link = networks.getLinkProperties(networks.getActiveNetwork());
        StringBuilder dns = new StringBuilder();
        if (link != null) for (InetAddress ip : link.getDnsServers()) {
            dns.append("nameserver ").append(ip.getHostAddress()).append('\n');
        }
        dns.append("options timeout:3 attempts:2\n");
        Path etc = root.resolve("etc");
        Files.createDirectories(etc);
        Path tmp = Files.createTempFile(etc, "resolver", ".tmp");
        Files.write(tmp, dns.toString().getBytes(StandardCharsets.US_ASCII));
        Files.move(tmp, etc.resolve("resolv.conf"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    Process start() throws IOException {
        String arch = readAsset("runtime-arch");
        for (String dir : new String[]{"bin","lib","lib64","tmp","proc","sys","dev","etc/ssl/certs",
                "home/dsh/.dsh","home/dsh/chat","opt/dsh/linux-" + arch, "usr/bin"}) Files.createDirectories(root.resolve(dir));
        copy("guest/ca-certificates.crt", "etc/ssl/certs/ca-certificates.crt");
        copy("guest/downloads.json", "opt/dsh/linux-" + arch + "/downloads.json");
        copy("chat.yml", "etc/chat.yml");
        write("etc/hosts","127.0.0.1 localhost\n::1 localhost\n");
        write("etc/nsswitch.conf","passwd: files\ngroup: files\nhosts: files dns\n");
        write("etc/passwd","root:x:0:0:root:/home/dsh:/bin/sh\n");
        write("etc/group","root:x:0:\n");
        updateDns();
        for (String name : new String[]{"sh","env","id","uname","cat","ls","mkdir","pwd","which"}) {
            Path alias = root.resolve("bin").resolve(name);
            if (!Files.isSymbolicLink(alias)) Files.createSymbolicLink(alias, Paths.get("busybox"));
        }
        // The CLI can include original plugin error values in its private startup report.
        // Cover its report path so the original output only enters our filtered process pipe.
        Path reportPath=root.resolve("home/dsh/.dsh/logs");
        if (!Files.exists(reportPath)) Files.createFile(reportPath);
        ArrayList<String> args = new ArrayList<>(Arrays.asList(nativeDir.resolve("libproot.so").toString(),
                "--kill-on-exit", "-0", "-r", root.toString(), "-w", "/home/dsh/chat",
                "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/dev/null:/home/dsh/.dsh/logs"));
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open("bindings.tsv"), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                String[] pair = line.split("\t");
                Path dest = root.resolve(pair[1].substring(1));
                Files.createDirectories(dest.getParent());
                if (!Files.exists(dest)) Files.createFile(dest);
                args.add("-b");
                args.add(nativeDir.resolve(pair[0]) + ":" + pair[1]);
            }
        }
        if ("x64".equals(arch)) args.addAll(Arrays.asList("/bin/busybox", "env", "LD_PRELOAD=/lib/chat-rename.so"));
        args.addAll(Arrays.asList("/opt/dsh/deepseek-harness-sdk-runtime-linux-" + arch,
                "web", "--patch", "/etc/chat.yml", "--no-open", "--host", "127.0.0.1", "--port", "0"));
        String supervisor;
        try (InputStream script=context.getAssets().open("runtime-supervisor.sh");
             Scanner reader=new Scanner(script,"UTF-8")) {
            supervisor=reader.useDelimiter("\\A").next();
        }
        List<String> command=new ArrayList<>(Arrays.asList("/system/bin/sh","-c",supervisor,"harness"));
        command.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true);
        Map<String,String> env = pb.environment();
        env.clear();
        env.put("PROOT_LOADER", nativeDir.resolve("libproot_loader.so").toString());
        env.put("PROOT_TMP_DIR", context.getCacheDir().getAbsolutePath());
        env.put("PROOT_NO_SECCOMP", "1");
        env.put("LD_LIBRARY_PATH", nativeDir + ":/lib");
        env.put("HOME", "/home/dsh");
        env.put("PATH", "/bin:/usr/bin");
        env.put("TMPDIR", "/tmp");
        env.put("LANG", "C.UTF-8");
        env.put("NARB_NATIVE_CACHE_DIR", "/opt/native-cache");
        env.put("DSH_HOME", "/home/dsh/.dsh");
        env.put("DSH_TELEMETRY_DISABLED", "1");
        env.put("NODE_EXTRA_CA_CERTS", "/etc/ssl/certs/ca-certificates.crt");
        return pb.start();
    }
}
