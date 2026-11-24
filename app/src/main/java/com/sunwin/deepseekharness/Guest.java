package com.sunwin.deepseekharness;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.system.*;
import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** PRoot and every guest ELF are installer-owned native libraries, including the glibc loader. */
final class Guest {
    static final String CHAT_DIR="/home/dsh/chat";
    private final Context context;
    private final Path root, nativeDir;
    private boolean dnsConfigured;
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
    synchronized void initialDns() throws IOException {
        // A default-network callback can arrive before the guest finishes staging.
        if (dnsConfigured) return;
        ConnectivityManager networks = context.getSystemService(ConnectivityManager.class);
        updateDns(networks.getLinkProperties(networks.getActiveNetwork()));
    }
    synchronized void updateDns(LinkProperties link) throws IOException {
        StringBuilder dns = new StringBuilder();
        if (link != null) for (InetAddress ip : link.getDnsServers()) {
            dns.append("nameserver ").append(ip.getHostAddress()).append('\n');
        }
        dns.append("options timeout:3 attempts:2\n");
        Path etc = root.resolve("etc");
        Files.createDirectories(etc);
        Path tmp = Files.createTempFile(etc, "resolver", ".tmp");
        try {
            Files.write(tmp, dns.toString().getBytes(StandardCharsets.US_ASCII));
            Files.move(tmp, etc.resolve("resolv.conf"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            dnsConfigured=true;
        } finally { Files.deleteIfExists(tmp); }
    }
    private static final List<String> HOST_LIBS=Arrays.asList("libproot.so","libproot_loader.so","libtalloc.so","libandroid-shmem.so");
    private Map<String,String> installedBindings(String arch) throws IOException {
        Map<String,String> bindings=new LinkedHashMap<>();
        Set<String> names=new HashSet<>(HOST_LIBS);
        try (BufferedReader r=new BufferedReader(new InputStreamReader(context.getAssets().open("bindings.tsv"),StandardCharsets.UTF_8))) {
            for (String line;(line=r.readLine())!=null;) {
                String[] pair=line.split("\t",-1);
                if (pair.length!=2 || !pair[0].matches("lib[A-Za-z0-9_+.\\-]+\\.so") || !pair[1].startsWith("/")
                        || !Paths.get(pair[1]).normalize().toString().equals(pair[1]) || bindings.put(pair[1],pair[0])!=null)
                    throw new ChatFailure("运行库绑定清单无效");
                names.add(pair[0]);
                AccessFault.binding(pair[0],pair[1]);
            }
        }
        String cli="/opt/dsh/deepseek-harness-sdk-runtime-linux-"+arch;
        String platform="linux-"+arch+"-gnu";
        String builtin="/opt/native-cache/node-addon-require-builtin-"+platform+"/0.1.9/"+platform+"/"+platform+"-napi-v9.node";
        String ld="arm64".equals(arch)?"/lib/ld-linux-aarch64.so.1":"/lib64/ld-linux-x86-64.so.2";
        requireBinding(bindings,cli,"libdsh.so");
        requireBinding(bindings,cli+"-rg","libdsh_rg.so");
        requireBinding(bindings,"/bin/busybox","libguest_busybox.so");
        requireBinding(bindings,"/bin/guest-check","libguest_check.so");
        requireBinding(bindings,ld,"libguest_ld.so");
        requireBinding(bindings,builtin,"libdsh_builtin.so");
        names.add("libdsh_flock.so");
        for (String name:names) {
            AccessFault.library(name);
            verifyLibrary(nativeDir,name);
        }
        // A removed manifest row must not silently leave a guest ELF as the only candidate.
        try (DirectoryStream<Path> installed=Files.newDirectoryStream(nativeDir,"lib*.so")) {
            for (Path path:installed) {
                String name=path.getFileName().toString();
                if (!names.contains(name)) {
                    AccessFault.library(name);
                    throw new ChatFailure(new AccessFault(AccessFault.Process.ANDROID,"BIND_MISMATCH",0,
                            "binding","nativeLibraryDir/"+name,""));
                }
            }
        }
        String flock="/opt/pkg-cache/pkg/"+digest(nativeDir.resolve("libdsh_flock.so"))
                +"/@deepseek-ai/node-addon-system-linux-"+arch+"/bin/glibc/system.node";
        requireBinding(bindings,flock,"libdsh_flock.so");
        return bindings;
    }
    private static void requireBinding(Map<String,String> bindings,String path,String name) throws ChatFailure {
        if (!name.equals(bindings.get(path))) {
            AccessFault.binding(name,path);
            throw new ChatFailure(new AccessFault(AccessFault.Process.ANDROID,"BIND_MISMATCH",0,"binding",path,""));
        }
    }
    private static String digest(Path path) throws IOException {
        try {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            StringBuilder hex=new StringBuilder();
            for (byte b:bytes) hex.append(String.format(Locale.ROOT,"%02x",b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    static void verifyLibrary(Path nativeDir,String name) throws IOException {
        // An empty bind target in the writable guest must never satisfy this check.
        Path path=nativeDir.resolve(name);
        String reported="nativeLibraryDir/"+name;
        try {
            BasicFileAttributes stat=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if (!stat.isRegularFile() || stat.size()==0) throw new ChatFailure(new AccessFault(
                    AccessFault.Process.ANDROID,"ENOEXEC",8,"stat",reported,""));
            if (!Os.access(path.toString(),OsConstants.X_OK)) throw new ChatFailure(new AccessFault(
                    AccessFault.Process.ANDROID,"EACCES",13,"access",reported,""));
        } catch (NoSuchFileException e) {
            throw new ChatFailure(new AccessFault(AccessFault.Process.ANDROID,"ENOENT",2,"stat",reported,""));
        } catch (AccessDeniedException e) {
            throw new ChatFailure(new AccessFault(AccessFault.Process.ANDROID,"EACCES",13,"stat",reported,""));
        } catch (ErrnoException e) {
            throw new ChatFailure(new AccessFault(AccessFault.Process.ANDROID,OsConstants.errnoName(e.errno),e.errno,"access",reported,""));
        }
    }
    ProcessBuilder prepare() throws IOException {
        String arch = readAsset("runtime-arch");
        Map<String,String> bindings=installedBindings(arch);
        for (String dir : new String[]{"bin","lib","lib64","tmp","proc","sys","dev","run/apk-libs","etc/ssl/certs",
                "home/dsh/.dsh/sessions","home/dsh/chat","opt/pkg-cache","opt/dsh/linux-" + arch, "usr/bin"}) Files.createDirectories(root.resolve(dir));
        Path scratch=context.getCacheDir().toPath().resolve("proot");
        Files.createDirectories(scratch);
        Files.delete(Files.createTempFile(scratch,"check-",null));
        copy("guest/ca-certificates.crt", "etc/ssl/certs/ca-certificates.crt");
        copy("guest/downloads.json", "opt/dsh/linux-" + arch + "/downloads.json");
        copy("chat.yml", "etc/chat.yml");
        copy("phone-context.cjs", "etc/phone-context.cjs");
        write("etc/hosts","127.0.0.1 localhost\n::1 localhost\n");
        write("etc/nsswitch.conf","passwd: files\ngroup: files\nhosts: files dns\n");
        write("etc/passwd","root:x:0:0:root:/home/dsh:/bin/sh\n");
        write("etc/group","root:x:0:\n");
        initialDns();
        for (String name : new String[]{"sh","env","id","uname","cat","ls","mkdir","pwd","which"}) {
            Path alias = root.resolve("bin").resolve(name);
            if (!Files.isSymbolicLink(alias)) Files.createSymbolicLink(alias, Paths.get("busybox"));
        }
        // The CLI can include original plugin error values in its private startup report.
        // Cover its report path so the original output only enters our filtered process pipe.
        Path reportPath=root.resolve("home/dsh/.dsh/logs");
        if (!Files.exists(reportPath)) Files.createFile(reportPath);
        ArrayList<String> args = new ArrayList<>(Arrays.asList(nativeDir.resolve("libproot.so").toString(),
                "--kill-on-exit", "--link2symlink", "-0", "-r", root.toString(), "-w", CHAT_DIR,
                "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/dev/null:/home/dsh/.dsh/logs",
                "-b", nativeDir+":/run/apk-libs"));
        for (Map.Entry<String,String> bind:bindings.entrySet()) {
            Path dest=root.resolve(bind.getKey().substring(1));
            Files.createDirectories(dest.getParent());
            // Remove only the old release's empty mount points. PRoot handles an absent leaf.
            if (Files.isRegularFile(dest,LinkOption.NOFOLLOW_LINKS) && Files.size(dest)==0) Files.delete(dest);
            args.add("-b");
            args.add(nativeDir.resolve(bind.getValue())+":"+bind.getKey()+"!");
        }
        args.add("/run/apk-libs/libguest_check.so");
        for (Map.Entry<String,String> bind:bindings.entrySet()) {
            args.add(bind.getValue()); args.add(bind.getKey());
        }
        args.add("--");
        args.addAll(Arrays.asList("/bin/busybox","env"));
        if ("x64".equals(arch)) args.add("LD_PRELOAD=/lib/chat-rename.so");
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
        env.put("PROOT_TMP_DIR", scratch.toString());
        env.put("PROOT_DONT_POLLUTE_ROOTFS","1");
        env.put("PROOT_NO_SECCOMP", "1");
        env.put("LD_LIBRARY_PATH", nativeDir + ":/lib");
        env.put("HOME", "/home/dsh");
        env.put("PATH", "/bin:/usr/bin");
        env.put("TMPDIR", "/tmp");
        env.put("LANG", "C.UTF-8");
        env.put("NARB_NATIVE_CACHE_DIR", "/opt/native-cache");
        env.put("NARB_DISABLE_LOCAL_BUILD","1");
        env.put("PKG_NATIVE_CACHE_PATH","/opt/pkg-cache");
        env.put("DSH_HOME", "/home/dsh/.dsh");
        env.put("DSH_TELEMETRY_DISABLED", "1");
        env.put("NODE_EXTRA_CA_CERTS", "/etc/ssl/certs/ca-certificates.crt");
        return pb;
    }
    Process start() throws IOException { return prepare().start(); }
}
