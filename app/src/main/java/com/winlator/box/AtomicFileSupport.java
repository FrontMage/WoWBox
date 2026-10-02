package com.winlator.box;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class AtomicFileSupport {
    private AtomicFileSupport() {}

    static String readUtf8(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static void writeUtf8(File file, String value) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Unable to create " + parent);
        }
        File pending = new File(file.getAbsolutePath() + ".pending");
        if (pending.exists() && !pending.delete()) {
            throw new IOException("Unable to clear " + pending);
        }
        try (FileOutputStream output = new FileOutputStream(pending)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
        if (file.exists() && !file.delete()) {
            pending.delete();
            throw new IOException("Unable to replace " + file);
        }
        if (!pending.renameTo(file)) {
            pending.delete();
            throw new IOException("Unable to activate " + file);
        }
    }
}
