package com.sunwin.deepseekharness;

import org.json.JSONObject;
import java.nio.file.AccessDeniedException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;

/** Extract only operation names and package-owned path components. Child output,
 * RPC messages, endpoint addresses and user-chosen filenames are never exported. */
final class AccessFault {
    enum Process { ANDROID, SUPERVISOR, PROOT, LOADER, BUSYBOX, GUEST_CHECK, DSH_NODE }
    private static final String UNKNOWN="unknown";
    private static final String OPS="execve|exec|spawn|dlopen|mmap|openat|open|mkstemp|mkdir|write|read|flock|linkat|link|unlink|rename|symlink|connect|bind|socket|access|fstat|lstat|statx|stat|scandir|opendir|chdir|binding";
    private static final Pattern OP=Pattern.compile("\\b("+OPS+")\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern PATH=Pattern.compile("(?:^|[\\s'\"(=])(/[^\\s'\"),:]+)");
    private static final Pattern DENIED=Pattern.compile("\\b(EACCES|EPERM)\\b|permission denied|operation not permitted|error=13\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern OS_CODE=Pattern.compile("\\b(?:EACCES|EPERM)\\b|error=13\\b",Pattern.CASE_INSENSITIVE);
    private static final Set<String> BOUND=ConcurrentHashMap.newKeySet();
    private static final Set<String> LIBS=ConcurrentHashMap.newKeySet();
    private static final List<String> DATA=Arrays.asList("/home/dsh/.dsh/sessions","/home/dsh/.dsh/attachments",
            "/home/dsh/.dsh/storage","/home/dsh/.dsh/logs","/home/dsh/.dsh/.credentials.yaml",
            "/home/dsh/.dsh","/home/dsh/.cache","/home/dsh/chat","/home/dsh","/opt/pkg-cache","/opt/native-cache",
            "/opt/dsh","/tmp","/etc/resolv.conf","/etc","/bin","/lib","/lib64","/proc/net","/proc/self","/dev");
    final Process process;
    final String errno, syscall, path, dest;
    final int number;

    static void library(String name) { LIBS.add(name); }
    static void binding(String name,String guest) { library(name); BOUND.add(guest); }
    AccessFault(Process process,String errno,int number,String op,String path,String dest) {
        this.process=process; this.errno=errno; this.number=number;
        this.syscall=OP.matcher(op).matches()?op.toLowerCase(Locale.ROOT):UNKNOWN;
        this.path=safePath(path); this.dest=safePath(dest);
    }
    static String safePath(String path) {
        if (path==null || path.isEmpty()) return UNKNOWN;
        int guest=path.indexOf("/no_backup/linux/");
        if (guest>=0) path=path.substring(guest+"/no_backup/linux".length());
        if (BOUND.contains(path) || path.equals("/system/bin/sh") || path.equals("/bin/busybox")
                || path.equals("/bin/guest-check") || path.equals("/bin/sh")) return path;
        String base=path.substring(path.lastIndexOf('/')+1);
        if (LIBS.contains(base) && (path.startsWith("/data/app/") || path.startsWith("/run/apk-libs/")
                || path.startsWith("nativeLibraryDir/"))) return "nativeLibraryDir/"+base;
        if (path.equals("cache/proot") || path.matches(".*/com\\.sunwin\\.deepseekharness/cache/proot(?:/.*)?")) return "cache/proot";
        if (path.contains("/com.sunwin.deepseekharness/files/photos/")) return "files/photos/…";
        if (path.contains("/com.sunwin.deepseekharness/databases/")) return "databases/…";
        if (path.matches("/home/dsh/\\.cache/pkg/[^/]+/@deepseek-ai/node-addon-system-linux-(?:arm64|x64)/bin/glibc/system\\.node"))
            return "/home/dsh/.cache/pkg/…/@deepseek-ai/"+(path.contains("-arm64/")?"node-addon-system-linux-arm64":"node-addon-system-linux-x64")+"/bin/glibc/system.node";
        if (path.startsWith("/home/dsh/.dsh/sessions/--home-dsh-chat--/")) {
            String prefix="/home/dsh/.dsh/sessions/--home-dsh-chat--/…/";
            if (base.matches("session\\.lock|session(?:\\.v[0-9]+)?\\.jsonl(?:\\.zstd)?")) return prefix+base;
            if (base.endsWith(".tmp")) return prefix+"….tmp";
            return prefix+"…";
        }
        for (String dir:DATA) {
            if (path.equals(dir)) return dir;
            if (path.startsWith(dir+"/")) {
                if (path.substring(dir.length()+1).matches("\\.dsh-check-[a-zA-Z0-9]{6}(?:\\.link)?"))
                    return dir+"/.dsh-check-…"+(path.endsWith(".link")?".link":"");
                return dir+"/…";
            }
        }
        if (path.matches("(?:127\\.0\\.0\\.1|\\[::1\\])(?::[0-9]{1,5})?")) return "loopback";
        return UNKNOWN;
    }
    String label() {
        String op;
        switch (syscall) {
            case "exec": case "execve": case "spawn": op="执行"; break;
            case "mmap": case "dlopen": op="加载"; break;
            case "open": case "openat": op="打开"; break;
            case "mkstemp": case "mkdir": case "write": op="写入"; break;
            case "link": case "linkat": case "symlink": op="建链接"; break;
            case "rename": op="重命名"; break;
            case "flock": op="加锁"; break;
            case "connect": op="连接"; break;
            case "bind": op="监听"; break;
            case "socket": op="创建套接字"; break;
            case "unlink": op="删除"; break;
            case "binding": op="绑定"; break;
            case "read": case "opendir": case "scandir": op="读取"; break;
            default: op="访问";
        }
        String target=dest.equals(UNKNOWN)?path:dest;
        if (target.length()>54) target="…/"+target.substring(target.lastIndexOf('/')+1);
        if ("BIND_MISMATCH".equals(errno)) return "绑定不符 · "+target;
        if ("LOCK_MISMATCH".equals(errno)) return "文件锁不符 · "+target;
        if (target.equals(UNKNOWN)) {
            String who=process==Process.DSH_NODE?"DSH":process.name();
            return errno+" · "+who+" "+op+"受限";
        }
        return errno+" · "+op+" "+target;
    }
    String diagnostic() {
        return "process="+process+" syscall="+syscall+" path="+path+(dest.equals(UNKNOWN)?"":" dest="+dest)
                +" errno="+(number==0?UNKNOWN:errno+"("+number+")");
    }
    int specificity() { return (UNKNOWN.equals(syscall)?0:1)+(UNKNOWN.equals(path)?0:1)+(UNKNOWN.equals(dest)?0:1); }
    private static String denied(String text) {
        Matcher m=DENIED.matcher(text);
        if (!m.find()) return "";
        return m.group().toLowerCase(Locale.ROOT).matches("eperm|operation not permitted")?"EPERM":"EACCES";
    }
    static AccessFault text(Process who,String text) {
        if (denied(text).isEmpty() && text.contains("failed to map segment from shared object")) {
            Matcher path=PATH.matcher(text);
            return new AccessFault(who,"ERR_DLOPEN_FAILED",0,"mmap",path.find()?path.group(1):"","");
        }
        return parse(who,text,"","","","");
    }
    private static AccessFault parse(Process who,String text,String code,String op,String path,String dest) {
        if (code.isEmpty()) code=denied(text);
        if (code.isEmpty()) return null;
        if (!OP.matcher(op).matches()) {
            Matcher m=OP.matcher(text);
            op=m.find()?m.group():"";
            if (text.contains("failed to map segment")) op="mmap";
            if (text.contains("can't execute") || text.contains("cannot execute")) op="execve";
        }
        Matcher m=PATH.matcher(text);
        if (path.isEmpty() && m.find()) {
            path=m.group(1);
            if (dest.isEmpty() && ("link".equals(op)||"rename".equals(op)||"linkat".equals(op)) && m.find()) dest=m.group(1);
        }
        if (("bind".equals(op)||"connect".equals(op)) && text.contains("127.0.0.1")) path="127.0.0.1";
        return new AccessFault(who,code,"EPERM".equals(code)?1:13,op,path,dest);
    }
    static AccessFault remote(JSONObject error) { return nested(error,"",0); }
    private static AccessFault nested(JSONObject e,String inherited,int depth) {
        if (e==null || depth>4) return null;
        String text=e.optString("code")+" "+e.optString("errno")+" "+e.optString("message")+" "+(e.opt("reason") instanceof String?e.optString("reason"):"");
        String code=denied(text);
        int errno=e.optInt("errno",0);
        if (code.isEmpty()) code=Math.abs(errno)==13?"EACCES":Math.abs(errno)==1?"EPERM":inherited;
        // A provider can say "Permission denied" for an HTTP/account policy.
        // Only attribute OS errno when the envelope or the operation identifies it.
        boolean system=OS_CODE.matcher(text).find() || Math.abs(errno)==13 || Math.abs(errno)==1 || !inherited.isEmpty()
                || e.has("syscall") || (OP.matcher(text).find() && PATH.matcher(text).find());
        AccessFault best=system?parse(Process.DSH_NODE,text,code,e.optString("syscall"),
                e.optString("path",e.optString("address")),e.optString("dest")):null;
        if (best==null && text.contains("failed to map segment from shared object")) best=text(Process.DSH_NODE,text);
        for (String key:new String[]{"details","cause","error","reason"}) {
            JSONObject item=e.optJSONObject(key);
            AccessFault child=nested(item,system || (item!=null && item.has("syscall"))?code:"",depth+1);
            if (child!=null && (best==null || child.specificity()>best.specificity())) best=child;
        }
        return best;
    }
    static AccessFault caught(Throwable error) {
        String path="", dest="";
        AccessFault best=null;
        for (Throwable t=error;t!=null;t=t.getCause()) {
            if (t instanceof java.nio.file.FileSystemException) {
                java.nio.file.FileSystemException e=(java.nio.file.FileSystemException)t;
                path=e.getFile(); dest=e.getOtherFile();
            }
            if (t instanceof android.system.ErrnoException) {
                android.system.ErrnoException e=(android.system.ErrnoException)t;
                if (e.errno==13 || e.errno==1) {
                    // ErrnoException's public message starts with the libcore
                    // function name. Only the operation vocabulary can escape.
                    Matcher m=OP.matcher(String.valueOf(e.getMessage()));
                    return new AccessFault(Process.ANDROID,e.errno==13?"EACCES":"EPERM",e.errno,
                            m.find()?m.group():"",path,dest);
                }
            }
            if (t instanceof AccessDeniedException) best=new AccessFault(Process.ANDROID,"EACCES",13,"",path,dest);
            AccessFault fault=text(Process.ANDROID,String.valueOf(t.getMessage()));
            if (fault!=null && (best==null || fault.specificity()>best.specificity())) best=fault;
        }
        return best;
    }
}
