package com.winlator.box;

public class BoxExecutable {
    public String name;
    public String absolutePath;
    public String relativePath;
    public String drive;
    public long lastModified;

    public BoxExecutable(String name, String absolutePath, String relativePath, String drive, long lastModified) {
        this.name = name;
        this.absolutePath = absolutePath;
        this.relativePath = relativePath;
        this.drive = drive;
        this.lastModified = lastModified;
    }
}
