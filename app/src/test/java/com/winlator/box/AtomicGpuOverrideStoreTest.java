package com.winlator.box;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class AtomicGpuOverrideStoreTest {
    private static final String LIB = "driver.so";
    private static void write(File dir, String name, String value) throws Exception {
        Files.write(new File(dir, name).toPath(), value.getBytes(StandardCharsets.UTF_8));
    }
    private static String hash(File dir, String name) throws Exception { return WinePrefixDeltaReconciler.sha256(new File(dir, name)); }

    @Test public void installsOnceAndNeverOverwritesAConflict() throws Exception {
        File root = Files.createTempDirectory("gpu-test-").toFile();
        File sample = Files.createTempDirectory("gpu-sample-").toFile();
        write(sample,"meta.json","meta"); write(sample,LIB,"lib"); String mh=hash(sample,"meta.json"), lh=hash(sample,LIB);
        AtomicInteger extracts = new AtomicInteger();
        AtomicGpuOverrideStore.StageWriter writer = stage -> { extracts.incrementAndGet(); write(stage,"meta.json","meta"); write(stage,LIB,"lib"); };
        AtomicGpuOverrideStore.prepare(root,"v39",LIB,mh,lh,writer);
        AtomicGpuOverrideStore.prepare(root,"v39",LIB,mh,lh,writer);
        assertEquals(1,extracts.get());
        write(new File(root,"v39"),LIB,"custom");
        try { AtomicGpuOverrideStore.prepare(root,"v39",LIB,mh,lh,writer); fail("expected conflict"); }
        catch (java.io.IOException expected) { assertEquals("gpu_destination_conflict", expected.getMessage()); }
        assertEquals("custom", new String(Files.readAllBytes(new File(root,"v39/"+LIB).toPath()),StandardCharsets.UTF_8));
    }
    @Test public void failedStageDoesNotReplaceOldDriver() throws Exception {
        File root=Files.createTempDirectory("gpu-test-").toFile(); File old=new File(root,"old"); assertTrue(old.mkdir()); write(old,LIB,"old");
        try { AtomicGpuOverrideStore.prepare(root,"v39",LIB,"wrong","wrong",stage -> { write(stage,"meta.json","bad"); write(stage,LIB,"bad"); }); fail("expected failure"); }
        catch (java.io.IOException expected) { assertEquals("gpu_staged_hash_mismatch",expected.getMessage()); }
        assertFalse(new File(root,"v39").exists()); assertEquals("old",new String(Files.readAllBytes(new File(old,LIB).toPath()),StandardCharsets.UTF_8));
    }
    @Test public void concurrentInstallersConvergeOnOneCompleteDriver() throws Exception {
        File root=Files.createTempDirectory("gpu-test-").toFile(), sample=Files.createTempDirectory("gpu-sample-").toFile(); write(sample,"meta.json","m"); write(sample,LIB,"l"); String mh=hash(sample,"meta.json"), lh=hash(sample,LIB);
        AtomicInteger extracts=new AtomicInteger(); CountDownLatch start=new CountDownLatch(1); java.util.List<Throwable> errors=java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Runnable run=() -> { try { start.await(); AtomicGpuOverrideStore.prepare(root,"v39",LIB,mh,lh,stage -> { extracts.incrementAndGet(); write(stage,"meta.json","m"); write(stage,LIB,"l"); }); } catch(Throwable t) { errors.add(t); } };
        Thread a=new Thread(run),b=new Thread(run); a.start(); b.start(); start.countDown(); a.join(); b.join();
        assertTrue(errors.toString(),errors.isEmpty()); assertEquals(1,extracts.get()); assertTrue(AtomicGpuOverrideStore.verified(new File(root,"v39"),LIB,mh,lh));
    }
    @Test public void abandonedStageIsNotMistakenForInstalledDriverOrDeleted() throws Exception {
        File root=Files.createTempDirectory("gpu-test-").toFile(), sample=Files.createTempDirectory("gpu-sample-").toFile(); write(sample,"meta.json","m"); write(sample,LIB,"l"); String mh=hash(sample,"meta.json"), lh=hash(sample,LIB);
        File stale=new File(root,".wowbox-redmagic-v39-stale"); assertTrue(stale.mkdir()); write(stale,"unfinished-user-file","leave-me");
        AtomicGpuOverrideStore.prepare(root,"v39",LIB,mh,lh,stage -> { write(stage,"meta.json","m"); write(stage,LIB,"l"); });
        assertTrue(AtomicGpuOverrideStore.verified(new File(root,"v39"),LIB,mh,lh)); assertTrue(new File(stale,"unfinished-user-file").isFile());
    }
    @Test public void validRuntimeTempAndFreshOwnerSurviveNextVerification() throws Exception {
        File root=Files.createTempDirectory("gpu-test-").toFile(), sample=Files.createTempDirectory("gpu-sample-").toFile(); write(sample,"meta.json","m"); write(sample,LIB,"l"); String mh=hash(sample,"meta.json"), lh=hash(sample,LIB);
        AtomicGpuOverrideStore.prepare(root,"v39",LIB,mh,lh,stage -> { write(stage,"meta.json","m"); write(stage,LIB,"l"); });
        File dest=new File(root,"v39"), temp=new File(dest,"temp"); assertTrue(temp.mkdir()); write(temp,"0_patched.so","os-loader-cache"); write(dest,".wowbox-owner","wowbox-initial-gpu-v1\n");
        AtomicGpuOverrideStore.prepare(root,"v39",LIB,mh,lh,stage -> { fail("must reuse existing verified driver"); });
        assertTrue(AtomicGpuOverrideStore.verified(dest,LIB,mh,lh)); assertTrue(new File(temp,"0_patched.so").isFile());
    }
    @Test public void symlinkOrUnexpectedTempContentsFailClosedWithoutDeletion() throws Exception {
        File root=Files.createTempDirectory("gpu-test-").toFile(), sample=Files.createTempDirectory("gpu-sample-").toFile(); write(sample,"meta.json","m"); write(sample,LIB,"l"); String mh=hash(sample,"meta.json"), lh=hash(sample,LIB);
        AtomicGpuOverrideStore.prepare(root,"v39",LIB,mh,lh,stage -> { write(stage,"meta.json","m"); write(stage,LIB,"l"); });
        File dest=new File(root,"v39"), temp=new File(dest,"temp"); assertTrue(temp.mkdir()); File unrelated=new File(root,"user-file"); write(root,"user-file","keep");
        Files.createSymbolicLink(new File(temp,"0_patched.so").toPath(), unrelated.toPath());
        try { AtomicGpuOverrideStore.prepare(root,"v39",LIB,mh,lh,stage -> fail("do not replace")); fail("expected conflict"); }
        catch(java.io.IOException expected) { assertEquals("gpu_destination_conflict",expected.getMessage()); }
        assertTrue(Files.isSymbolicLink(new File(temp,"0_patched.so").toPath())); assertEquals("keep",new String(Files.readAllBytes(unrelated.toPath()),StandardCharsets.UTF_8));
    }
    @Test public void externalProcessLockSerializesInstallation() throws Exception {
        File root=Files.createTempDirectory("gpu-test-").toFile(), sample=Files.createTempDirectory("gpu-sample-").toFile(); write(sample,"meta.json","m"); write(sample,LIB,"l"); String mh=hash(sample,"meta.json"), lh=hash(sample,LIB);
        File lock=new File(root,".wowbox-redmagic-v39.lock");
        String code="import fcntl,sys,time; f=open(sys.argv[1], 'a+'); fcntl.lockf(f,fcntl.LOCK_EX); print('LOCKED',flush=True); time.sleep(1); fcntl.lockf(f,fcntl.LOCK_UN)";
        Process p=new ProcessBuilder("python3","-c",code,lock.getAbsolutePath()).redirectErrorStream(true).start();
        assertEquals("LOCKED",new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream())).readLine());
        long start=System.nanoTime();
        AtomicGpuOverrideStore.prepare(root,"v39",LIB,mh,lh,stage -> { write(stage,"meta.json","m"); write(stage,LIB,"l"); });
        long elapsed=(System.nanoTime()-start)/1_000_000;
        assertEquals(0,p.waitFor()); assertTrue("did not wait for other process lock: "+elapsed,elapsed>=500);
        assertTrue(AtomicGpuOverrideStore.verified(new File(root,"v39"),LIB,mh,lh));
    }
}
