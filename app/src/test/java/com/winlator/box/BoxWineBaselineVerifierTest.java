package com.winlator.box;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class BoxWineBaselineVerifierTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    private final class Fixture {
        final File root;
        final File dll;
        final File alternate;
        final List<BoxWineBaselineVerifier.Entry> entries = new ArrayList<>();
        Fixture() throws Exception {
            root = temp.newFolder();
            assertTrue(new File(root, "bin").mkdirs());
            assertTrue(new File(root, "lib").mkdirs());
            dll = new File(root, "lib/xinput.dll");
            alternate = temp.newFile();
            write(dll, "Wine original");
            write(alternate, "different-sized bridge implementation");
            write(new File(root, "bin/wine"), "wine executable");
            Files.createSymbolicLink(new File(root, "bin/tool").toPath(), Paths.get("wine"));
            entries.add(new BoxWineBaselineVerifier.Entry("bin", "dir", null, null, null));
            entries.add(file("bin/wine", new File(root, "bin/wine"), null));
            entries.add(new BoxWineBaselineVerifier.Entry("bin/tool", "symlink", null, null, "wine"));
            entries.add(new BoxWineBaselineVerifier.Entry("lib", "dir", null, null, null));
            entries.add(file("lib/xinput.dll", dll, Arrays.asList(hash(dll), hash(alternate))));
        }

        void verify() throws IOException { BoxWineBaselineVerifier.verify(root, entries); }
    }

    @Test public void acceptsExpectedTreeAndTrustedAlternativeBridge() throws Exception {
        Fixture fixture = new Fixture();
        fixture.verify();
        write(fixture.dll, "different-sized bridge implementation");
        fixture.verify();
    }

    @Test public void refusesUnexpectedFileEvenIfOtherEntriesAreValid() throws Exception {
        Fixture fixture = new Fixture();
        write(new File(fixture.root, "lib/unexpected.dll"), "extra");
        expectFailure(fixture, "Unexpected");
    }

    @Test public void refusesMissingFile() throws Exception {
        Fixture fixture = new Fixture();
        Files.delete(fixture.dll.toPath());
        expectFailure(fixture, "missing");
    }

    @Test public void refusesChangedFileAndUnknownBridge() throws Exception {
        Fixture fixture = new Fixture();
        write(fixture.dll, "untrusted replacement");
        expectFailure(fixture, "hash mismatch");
    }

    @Test public void refusesWrongLinkEvenIfStillInsideTree() throws Exception {
        Fixture fixture = new Fixture();
        File link = new File(fixture.root, "bin/tool");
        Files.delete(link.toPath());
        Files.createSymbolicLink(link.toPath(), Paths.get("../lib/xinput.dll"));
        expectFailure(fixture, "symlink mismatch");
    }

    @Test public void refusesEscapingOrAbsoluteSymlinks() throws Exception {
        Fixture fixture = new Fixture();
        File link = new File(fixture.root, "bin/tool");
        Files.delete(link.toPath());
        Files.createSymbolicLink(link.toPath(), Paths.get("../../outside"));
        expectFailure(fixture, "symlink mismatch");
        fixture.entries.set(2, new BoxWineBaselineVerifier.Entry("bin/tool", "symlink", null, null, "../../outside"));
        expectFailure(fixture, "Escaping");
    }

    @Test public void refusesDuplicateAndTraversalManifestPaths() throws Exception {
        Fixture fixture = new Fixture();
        fixture.entries.add(fixture.entries.get(0));
        expectFailure(fixture, "Duplicate");
        fixture.entries.remove(fixture.entries.size() - 1);
        fixture.entries.add(new BoxWineBaselineVerifier.Entry("../outside", "dir", null, null, null));
        expectFailure(fixture, "Unsafe");
    }

    @Test public void refusesTypeMismatchAndUntrustedAllowedList() throws Exception {
        Fixture fixture = new Fixture();
        fixture.entries.set(3, new BoxWineBaselineVerifier.Entry("lib", "file", hash(fixture.dll), null, null));
        expectFailure(fixture, "type mismatch");
        fixture.entries.set(3, new BoxWineBaselineVerifier.Entry("lib", "dir", null, null, null));
        fixture.entries.set(4, file("lib/xinput.dll", fixture.dll, Collections.singletonList(hash(fixture.alternate))));
        expectFailure(fixture, "omit baseline");
    }

    private static BoxWineBaselineVerifier.Entry file(String path, File file, List<String> allowed) throws Exception {
        return new BoxWineBaselineVerifier.Entry(path, "file", hash(file), allowed, null);
    }

    private static String hash(File file) throws Exception {
        return BoxReleaseTransaction.sha256(file, false);
    }

    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static void expectFailure(Fixture fixture, String message) throws Exception {
        try { fixture.verify(); fail("baseline verification must reject " + message); }
        catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains(message)); }
    }
}
