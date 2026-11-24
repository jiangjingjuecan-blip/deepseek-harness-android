package com.sunwin.deepseekharness;

import android.app.Application;
import android.net.LinkProperties;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE, application=Application.class)
public class GuestTest {
    @Test public void executableInWritableGuestCannotReplaceMissingInstallerLibrary() throws Exception {
        Application app=RuntimeEnvironment.getApplication();
        Path nativeDir=app.getDir("native",0).toPath();
        Path misplaced=app.getNoBackupFilesDir().toPath().resolve("linux/bin/busybox");
        Files.createDirectories(misplaced.getParent());
        Files.write(misplaced,new byte[]{0x7f,'E','L','F'});
        assertTrue(misplaced.toFile().setExecutable(true,true));
        AccessFault.library("libguest_busybox.so");
        ChatFailure missing=assertThrows(ChatFailure.class,()->Guest.verifyLibrary(nativeDir,"libguest_busybox.so"));
        assertEquals("ENOENT",missing.code);
        assertTrue(missing.diagnostic().contains("syscall=stat path=nativeLibraryDir/libguest_busybox.so"));
        Files.createSymbolicLink(nativeDir.resolve("libguest_busybox.so"),misplaced);
        assertEquals("ENOEXEC",assertThrows(ChatFailure.class,()->Guest.verifyLibrary(nativeDir,"libguest_busybox.so")).code);
    }
    @Test public void startupAndNetworkChangesUseTheCurrentDnsSnapshot() throws Exception {
        Application app=RuntimeEnvironment.getApplication();
        app.getApplicationInfo().nativeLibraryDir=app.getDir("native",0).getPath();
        Guest guest=new Guest(app);
        Path resolver=app.getNoBackupFilesDir().toPath().resolve("linux/etc/resolv.conf");
        LinkProperties link=new LinkProperties();
        link.setDnsServers(Collections.singletonList(InetAddress.getByName("192.0.2.53")));
        guest.updateDns(link);
        guest.initialDns();
        assertEquals("nameserver 192.0.2.53",Files.readAllLines(resolver).get(0));
        link.setDnsServers(Collections.singletonList(InetAddress.getByName("198.51.100.53")));
        guest.updateDns(link);
        assertEquals("nameserver 198.51.100.53",Files.readAllLines(resolver).get(0));
        guest.updateDns(null);
        assertEquals(Collections.singletonList("options timeout:3 attempts:2"),Files.readAllLines(resolver));
    }
}
