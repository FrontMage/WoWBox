package com.winlator.box;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class TitanProfileSyncTransactionTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void successReplacesBothTreesAndDeletesTransientBackup() throws Exception {
        Fixture fixture = fixture(step -> {});

        fixture.transaction.activate(1, 1, fixture.newBytes);

        assertEquals("new-wtf", read(new File(fixture.target, "WTF/value.txt")));
        assertEquals("new-addons", read(new File(
                fixture.target, "Interface/AddOns/value.txt")));
        assertFalse(new File(fixture.work, "backup").exists());
        assertFalse(new File(fixture.work, "stage").exists());
        assertFalse(new File(fixture.work, "transaction.properties").exists());
    }

    @Test
    public void everyActivationFailureRestoresBothOldTrees() throws Exception {
        String[] steps = {
                "move-old-wtf",
                "activate-new-wtf",
                "move-old-addons",
                "activate-new-addons",
                "verify-active"
        };
        for (String failedStep : steps) {
            Fixture fixture = fixture(step -> {
                if (failedStep.equals(step)) throw new IOException("injected " + step);
            });
            try {
                fixture.transaction.activate(1, 1, fixture.newBytes);
                fail("activation should fail at " + failedStep);
            }
            catch (IOException expected) {
                assertTrue(expected.getMessage().contains("injected"));
            }
            assertEquals("old-wtf", read(new File(fixture.target, "WTF/value.txt")));
            assertEquals("old-addons", read(new File(
                    fixture.target, "Interface/AddOns/value.txt")));
            assertFalse(new File(fixture.work, "backup").exists());
            assertFalse(new File(fixture.work, "transaction.properties").exists());
        }
    }

    @Test
    public void recoveryAfterProcessDeathRestoresOldTrees() throws Exception {
        Fixture fixture = fixture(step -> {
            if ("activate-new-addons".equals(step)) throw new SimulatedDeath();
        });
        try {
            fixture.transaction.activate(1, 1, fixture.newBytes);
            fail("simulated death should escape activation");
        }
        catch (SimulatedDeath expected) {}

        new TitanProfileSyncTransaction(fixture.target, fixture.work).recover();

        assertEquals("old-wtf", read(new File(fixture.target, "WTF/value.txt")));
        assertEquals("old-addons", read(new File(
                fixture.target, "Interface/AddOns/value.txt")));
        assertFalse(new File(fixture.work, "backup").exists());
        assertFalse(new File(fixture.work, "transaction.properties").exists());
    }

    private Fixture fixture(TitanProfileSyncTransaction.FailureInjector injector)
            throws Exception {
        File root = temporaryFolder.newFolder();
        File target = new File(root, "titan");
        File work = new File(root, "work");
        write(new File(target, "WTF/value.txt"), "old-wtf");
        write(new File(target, "Interface/AddOns/value.txt"), "old-addons");
        TitanProfileSyncTransaction transaction =
                new TitanProfileSyncTransaction(target, work, injector);
        File stage = transaction.prepareStaging();
        write(new File(stage, "WTF/value.txt"), "new-wtf");
        write(new File(stage, "Interface/AddOns/value.txt"), "new-addons");
        return new Fixture(
                target,
                work,
                transaction,
                "new-wtf".getBytes(StandardCharsets.UTF_8).length
                        + "new-addons".getBytes(StandardCharsets.UTF_8).length);
    }

    private static void write(File file, String value) throws Exception {
        File parent = file.getParentFile();
        assertTrue(parent.isDirectory() || parent.mkdirs());
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static final class Fixture {
        final File target;
        final File work;
        final TitanProfileSyncTransaction transaction;
        final long newBytes;

        Fixture(
                File target,
                File work,
                TitanProfileSyncTransaction transaction,
                long newBytes) {
            this.target = target;
            this.work = work;
            this.transaction = transaction;
            this.newBytes = newBytes;
        }
    }

    private static final class SimulatedDeath extends Error {}
}
