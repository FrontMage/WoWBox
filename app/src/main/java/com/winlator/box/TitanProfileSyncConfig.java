package com.winlator.box;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

public final class TitanProfileSyncConfig {
    public static final String DEFAULT_SOURCE_PATH =
            "D:\\World of Warcraft\\_classic_titan_";
    public static final String TARGET_GUEST_PATH =
            "C:\\Program Files (x86)\\World of Warcraft\\_classic_titan_";
    public static final int DEFAULT_PORT = 22;

    private static final String PREFERENCES = "titan_profile_sync";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_SOURCE_PATH = "source_path";

    public final String host;
    public final int port;
    public final String username;
    public final String sourcePath;

    public TitanProfileSyncConfig(String host, int port, String username, String sourcePath) {
        this.host = host != null ? host.trim() : "";
        this.port = port;
        this.username = username != null ? username.trim() : "";
        this.sourcePath = normalizeSourcePath(sourcePath);
    }

    public static TitanProfileSyncConfig load(Context context) {
        SharedPreferences preferences =
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        return new TitanProfileSyncConfig(
                preferences.getString(KEY_HOST, ""),
                preferences.getInt(KEY_PORT, DEFAULT_PORT),
                preferences.getString(KEY_USERNAME, ""),
                preferences.getString(KEY_SOURCE_PATH, DEFAULT_SOURCE_PATH)
        );
    }

    public void save(Context context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_HOST, host)
                .putInt(KEY_PORT, port)
                .putString(KEY_USERNAME, username)
                .putString(KEY_SOURCE_PATH, sourcePath)
                .apply();
    }

    public String validate() {
        if (host.isEmpty()) return "SSH host is required";
        if (host.length() > 255 || containsControl(host)
                || host.contains("/") || host.contains("\\")) {
            return "SSH host is invalid";
        }
        if (port < 1 || port > 65535) return "SSH port must be between 1 and 65535";
        if (username.isEmpty()) return "SSH username is required";
        if (username.length() > 128 || containsControl(username)) {
            return "SSH username is invalid";
        }
        if (!isSafeWindowsAbsolutePath(sourcePath)) {
            return "Source path must be an absolute Windows path without shell characters";
        }
        return "";
    }

    public String hostIdentity() {
        return host.toLowerCase(Locale.ROOT) + ":" + port;
    }

    public String buildTarCommand() {
        if (!isSafeWindowsAbsolutePath(sourcePath)) {
            throw new IllegalArgumentException("unsafe_source_path");
        }
        return "tar.exe -cf - -C \"" + sourcePath
                + "\" \"WTF\" \"Interface\\AddOns\"";
    }

    public String buildMetadataCommand() {
        String script = buildMetadataScript();
        String compressedScript = gzipBase64(script);
        String wrapper = "$d=[Convert]::FromBase64String('" + compressedScript + "');"
                + "$m=New-Object IO.MemoryStream(,$d);"
                + "$g=New-Object IO.Compression.GzipStream"
                + "($m,[IO.Compression.CompressionMode]::Decompress);"
                + "$r=New-Object IO.StreamReader($g,[Text.Encoding]::UTF8);"
                + "&([ScriptBlock]::Create($r.ReadToEnd()))";
        String encodedScript = Base64.getEncoder().encodeToString(
                wrapper.getBytes(StandardCharsets.UTF_16LE));
        return "powershell.exe -NoLogo -NoProfile -NonInteractive "
                + "-ExecutionPolicy Bypass -EncodedCommand " + encodedScript;
    }

    String buildMetadataScript() {
        String encodedPath = Base64.getEncoder().encodeToString(
                sourcePath.getBytes(StandardCharsets.UTF_8));
        return "$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue';"
                + "trap{[Console]::Error.WriteLine($_.Exception.Message);exit 1};"
                + "[Console]::OutputEncoding=New-Object System.Text.UTF8Encoding($false);"
                + "$root=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('"
                + encodedPath + "'));"
                + "$root=$root.TrimEnd('\\');"
                + "$wtf=Join-Path $root 'WTF';"
                + "$addons=Join-Path $root 'Interface\\AddOns';"
                + "if(!(Test-Path -LiteralPath $wtf -PathType Container)){throw 'WTF_MISSING'};"
                + "if(!(Test-Path -LiteralPath $addons -PathType Container)){throw 'ADDONS_MISSING'};"
                + "if(!(Get-Command tar.exe -ErrorAction SilentlyContinue)){throw 'TAR_MISSING'};"
                + "$wow=@(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue|"
                + "Where-Object{($_.Name -ieq 'Wow.exe' -or $_.Name -ieq 'WowClassic.exe')"
                + "-and $_.ExecutablePath -and $_.ExecutablePath.StartsWith("
                + "$root+'\\',[StringComparison]::OrdinalIgnoreCase)});"
                + "if($wow.Count -gt 0){throw 'WOW_RUNNING'};"
                + "$all=@(Get-ChildItem -LiteralPath $wtf,$addons -Force -Recurse);"
                + "$links=@($all|Where-Object{($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0});"
                + "if($links.Count -gt 0){throw 'REPARSE_POINT_FOUND'};"
                + "$files=@($all|Where-Object{!$_.PSIsContainer}|Sort-Object FullName);"
                + "$sha=[Security.Cryptography.SHA256]::Create();"
                + "$manifestSha=[Security.Cryptography.SHA256]::Create();"
                + "$manifest=New-Object byte[] 32;"
                + "[long]$bytes=0;[int]$wtfCount=0;[int]$addonCount=0;"
                + "foreach($f in $files){"
                + "$rel=$f.FullName.Substring($root.Length).TrimStart('\\').Replace('\\','/');"
                + "$line=$rel+'`t'+$f.Length+'`t'+$f.LastWriteTimeUtc.Ticks+'`n';"
                + "$data=[Text.Encoding]::UTF8.GetBytes($line);"
                + "[void]$sha.TransformBlock($data,0,$data.Length,$data,0);"
                + "$manifestLine=$rel+'`t'+$f.Length+'`n';"
                + "$manifestData=[Text.Encoding]::UTF8.GetBytes($manifestLine);"
                + "$entryHash=$manifestSha.ComputeHash($manifestData);"
                + "for($i=0;$i-lt 32;$i++){$manifest[$i]=$manifest[$i]-bxor $entryHash[$i]};"
                + "$bytes+=$f.Length;"
                + "if($rel.StartsWith('WTF/',[StringComparison]::OrdinalIgnoreCase)){$wtfCount++}"
                + "else{$addonCount++}"
                + "};"
                + "[void]$sha.TransformFinalBlock((New-Object byte[] 0),0,0);"
                + "$hash=([BitConverter]::ToString($sha.Hash)).Replace('-','').ToLowerInvariant();"
                + "$manifestHash=([BitConverter]::ToString($manifest)).Replace('-','').ToLowerInvariant();"
                + "[ordered]@{ok=$true;wtfFiles=$wtfCount;addonFiles=$addonCount;"
                + "totalFiles=$files.Count;totalBytes=$bytes;snapshot=$hash;"
                + "contentSnapshot=$manifestHash}|ConvertTo-Json -Compress";
    }

    private static String gzipBase64(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(value.getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException error) {
            throw new IllegalStateException("Unable to encode PowerShell preflight", error);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    public static boolean isSafeWindowsAbsolutePath(String value) {
        if (value == null) return false;
        String path = normalizeSourcePath(value);
        if (path.length() < 3 || path.length() > 1024) return false;
        if (!Character.isLetter(path.charAt(0)) || path.charAt(1) != ':'
                || path.charAt(2) != '\\') return false;
        if (containsControl(path)) return false;
        String forbidden = "\"&|<>^%!";
        for (int i = 0; i < path.length(); i++) {
            if (forbidden.indexOf(path.charAt(i)) >= 0) return false;
        }
        String[] segments = path.substring(3).split("\\\\", -1);
        for (String segment : segments) {
            if (".".equals(segment) || "..".equals(segment)) return false;
        }
        return true;
    }

    private static String normalizeSourcePath(String value) {
        String path = value != null ? value.trim().replace('/', '\\') : "";
        while (path.length() > 3 && path.endsWith("\\")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return true;
        }
        return false;
    }
}
