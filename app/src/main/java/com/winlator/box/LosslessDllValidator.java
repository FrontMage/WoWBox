package com.winlator.box;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Minimal, dependency-free PE resource validator for a user-owned Lossless.dll.
 *
 * The frame generation layer never executes this AMD64 DLL. It extracts the
 * LSFG shader blobs stored as RT_RCDATA resources, so validation is limited to
 * the PE identity and the exact resource contract consumed by lsfg-vk.
 */
public final class LosslessDllValidator {
    public static final int IMAGE_FILE_MACHINE_AMD64 = 0x8664;
    public static final int PE32_PLUS_MAGIC = 0x20b;
    public static final int RT_RCDATA = 10;
    public static final int FIRST_REQUIRED_RESOURCE_ID = 255;
    public static final int LAST_REQUIRED_RESOURCE_ID = 302;
    public static final long MAX_DLL_SIZE = 64L * 1024L * 1024L;

    private LosslessDllValidator() {}

    public static final class Validation {
        public final boolean valid;
        public final String reason;
        public final long size;
        public final String sha256;
        public final String version;
        public final int machine;
        public final List<Integer> missingResourceIds;

        private Validation(
                boolean valid,
                String reason,
                long size,
                String sha256,
                String version,
                int machine,
                List<Integer> missingResourceIds) {
            this.valid = valid;
            this.reason = reason;
            this.size = size;
            this.sha256 = sha256;
            this.version = version;
            this.machine = machine;
            this.missingResourceIds = Collections.unmodifiableList(
                    new ArrayList<>(missingResourceIds));
        }

        public static Validation invalid(String reason) {
            return new Validation(false, reason, 0, "", "", 0, Collections.emptyList());
        }
    }

    public static Validation validate(File file) {
        if (file == null || !file.isFile()) return Validation.invalid("dll_missing");
        if (file.length() <= 0 || file.length() > MAX_DLL_SIZE) {
            return new Validation(
                    false, "dll_size_invalid", file.length(), "", "", 0,
                    Collections.emptyList());
        }

        try {
            byte[] data = readAll(file);
            String sha256 = sha256(data);
            if (data.length < 0x100 || u16(data, 0) != 0x5a4d) {
                return result(false, "not_pe", data, sha256, "", 0,
                        Collections.emptyList());
            }

            int peOffset = checkedInt(u32(data, 0x3c), "pe_offset");
            requireRange(data, peOffset, 24, "pe_header");
            if (u32(data, peOffset) != 0x00004550L) {
                return result(false, "pe_signature_invalid", data, sha256, "", 0,
                        Collections.emptyList());
            }

            int machine = u16(data, peOffset + 4);
            if (machine != IMAGE_FILE_MACHINE_AMD64) {
                return result(false, "pe_machine_not_amd64", data, sha256, "", machine,
                        Collections.emptyList());
            }

            int sectionCount = u16(data, peOffset + 6);
            int optionalSize = u16(data, peOffset + 20);
            int optionalOffset = peOffset + 24;
            requireRange(data, optionalOffset, optionalSize, "optional_header");
            if (u16(data, optionalOffset) != PE32_PLUS_MAGIC) {
                return result(false, "pe_not_pe32_plus", data, sha256, "", machine,
                        Collections.emptyList());
            }
            if (optionalSize < 144) {
                return result(false, "pe_optional_header_short", data, sha256, "", machine,
                        Collections.emptyList());
            }

            long resourceRva = u32(data, optionalOffset + 128);
            long resourceSize = u32(data, optionalOffset + 132);
            int sectionTable = optionalOffset + optionalSize;
            requireRange(data, sectionTable, sectionCount * 40, "section_table");
            int resourceBase = rvaToOffset(
                    data, resourceRva, sectionTable, sectionCount);
            if (resourceRva == 0 || resourceSize < 16 || resourceBase < 0) {
                return result(false, "resource_directory_missing", data, sha256, "", machine,
                        Collections.emptyList());
            }

            int rcdataDirectory = findIdDirectory(data, resourceBase, resourceBase, RT_RCDATA);
            if (rcdataDirectory < 0) {
                return result(false, "rcdata_directory_missing", data, sha256, "", machine,
                        requiredResourceIds());
            }

            List<Integer> missing = new ArrayList<>();
            for (int id = FIRST_REQUIRED_RESOURCE_ID; id <= LAST_REQUIRED_RESOURCE_ID; id++) {
                int languageDirectory = findIdDirectory(data, resourceBase, rcdataDirectory, id);
                if (languageDirectory < 0 ||
                        !hasNonEmptyResourceData(
                                data, resourceBase, languageDirectory, sectionTable, sectionCount)) {
                    missing.add(id);
                }
            }

            String version = findFixedFileVersion(data);
            if (!missing.isEmpty()) {
                return result(false, "required_shader_resources_missing", data, sha256, version,
                        machine, missing);
            }
            return result(true, "", data, sha256, version, machine, missing);
        }
        catch (Exception e) {
            return new Validation(
                    false,
                    "dll_parse_error:" + e.getMessage(),
                    file.length(),
                    "",
                    "",
                    0,
                    Collections.emptyList());
        }
    }

    private static Validation result(
            boolean valid,
            String reason,
            byte[] data,
            String sha256,
            String version,
            int machine,
            List<Integer> missing) {
        return new Validation(valid, reason, data.length, sha256, version, machine, missing);
    }

    private static byte[] readAll(File file) throws Exception {
        int length = checkedInt(file.length(), "file_size");
        byte[] data = new byte[length];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int count = input.read(data, offset, data.length - offset);
                if (count < 0) throw new IllegalArgumentException("unexpected_eof");
                offset += count;
            }
        }
        return data;
    }

    private static int findIdDirectory(
            byte[] data, int resourceBase, int directoryOffset, int requestedId) {
        requireRange(data, directoryOffset, 16, "resource_directory");
        int namedEntries = u16(data, directoryOffset + 12);
        int idEntries = u16(data, directoryOffset + 14);
        int count = namedEntries + idEntries;
        requireRange(data, directoryOffset + 16, count * 8, "resource_entries");
        for (int i = 0; i < count; i++) {
            int entry = directoryOffset + 16 + i * 8;
            long name = u32(data, entry);
            if ((name & 0x80000000L) != 0 || (name & 0xffffL) != requestedId) continue;
            long child = u32(data, entry + 4);
            if ((child & 0x80000000L) == 0) return -1;
            int offset = checkedInt(child & 0x7fffffffL, "resource_child");
            requireRange(data, resourceBase + offset, 16, "resource_child_directory");
            return resourceBase + offset;
        }
        return -1;
    }

    private static boolean hasNonEmptyResourceData(
            byte[] data,
            int resourceBase,
            int languageDirectory,
            int sectionTable,
            int sectionCount) {
        requireRange(data, languageDirectory, 16, "language_directory");
        int count = u16(data, languageDirectory + 12) + u16(data, languageDirectory + 14);
        requireRange(data, languageDirectory + 16, count * 8, "language_entries");
        for (int i = 0; i < count; i++) {
            int entry = languageDirectory + 16 + i * 8;
            long child = u32(data, entry + 4);
            if ((child & 0x80000000L) != 0) continue;
            int dataEntry = resourceBase + checkedInt(child, "resource_data_entry");
            requireRange(data, dataEntry, 16, "resource_data");
            long dataRva = u32(data, dataEntry);
            long size = u32(data, dataEntry + 4);
            int fileOffset = rvaToOffset(data, dataRva, sectionTable, sectionCount);
            if (size > 0 && fileOffset >= 0 && size <= Integer.MAX_VALUE &&
                    fileOffset <= data.length - (int)size) {
                return true;
            }
        }
        return false;
    }

    private static int rvaToOffset(
            byte[] data, long rva, int sectionTable, int sectionCount) {
        for (int i = 0; i < sectionCount; i++) {
            int section = sectionTable + i * 40;
            long virtualSize = u32(data, section + 8);
            long virtualAddress = u32(data, section + 12);
            long rawSize = u32(data, section + 16);
            long rawOffset = u32(data, section + 20);
            long span = Math.max(virtualSize, rawSize);
            if (rva < virtualAddress || rva >= virtualAddress + span) continue;
            long offset = rawOffset + (rva - virtualAddress);
            if (offset < 0 || offset >= data.length) return -1;
            return checkedInt(offset, "rva_file_offset");
        }
        return -1;
    }

    private static String findFixedFileVersion(byte[] data) {
        for (int i = 0; i <= data.length - 16; i++) {
            if ((data[i] & 0xff) != 0xbd ||
                    (data[i + 1] & 0xff) != 0x04 ||
                    (data[i + 2] & 0xff) != 0xef ||
                    (data[i + 3] & 0xff) != 0xfe) {
                continue;
            }
            long ms = u32(data, i + 8);
            long ls = u32(data, i + 12);
            int major = (int)((ms >>> 16) & 0xffff);
            int minor = (int)(ms & 0xffff);
            int build = (int)((ls >>> 16) & 0xffff);
            int revision = (int)(ls & 0xffff);
            return String.format(Locale.US, "%d.%d.%d.%d", major, minor, build, revision);
        }
        return "";
    }

    private static List<Integer> requiredResourceIds() {
        List<Integer> result = new ArrayList<>();
        for (int id = FIRST_REQUIRED_RESOURCE_ID; id <= LAST_REQUIRED_RESOURCE_ID; id++) {
            result.add(id);
        }
        return result;
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] result = digest.digest(data);
        StringBuilder value = new StringBuilder();
        for (byte item : result) value.append(String.format(Locale.US, "%02x", item & 0xff));
        return value.toString();
    }

    private static int checkedInt(long value, String field) {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + "_out_of_range");
        }
        return (int)value;
    }

    private static void requireRange(byte[] data, int offset, int length, String field) {
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IllegalArgumentException(field + "_out_of_range");
        }
    }

    private static int u16(byte[] data, int offset) {
        requireRange(data, offset, 2, "u16");
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static long u32(byte[] data, int offset) {
        requireRange(data, offset, 4, "u32");
        return ((long)data[offset] & 0xff) |
                (((long)data[offset + 1] & 0xff) << 8) |
                (((long)data[offset + 2] & 0xff) << 16) |
                (((long)data[offset + 3] & 0xff) << 24);
    }
}
