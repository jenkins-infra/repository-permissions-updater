package io.jenkins.infra.repository_permissions_updater.hosting;

public record Version(int major, String suffix) implements Comparable<Version> {

    public static final Version EMPTY = new Version(0);

    public Version {
        if (major < 0) {
            throw new IllegalArgumentException("negative major");
        }
        if (suffix != null && suffix.isEmpty()) {
            suffix = null;
        }
    }

    public Version(int major) {
        this(major, (String) null);
    }

    public Version(int major, int minor) {
        this(major, Integer.toString(minor));
    }

    public Version(int major, int minor, int micro) {
        this(major, minor + "." + micro);
    }

    public Version(String version) {
        if (version == null) {
            throw new IllegalArgumentException("version is null");
        }
        String v = version.trim();
        if (v.isEmpty()) {
            throw new IllegalArgumentException("version is empty");
        }
        int dot = v.indexOf('.');
        int parsedMajor;
        String parsedSuffix;
        if (dot < 0) {
            parsedMajor = Integer.parseInt(v);
            parsedSuffix = null;
        } else {
            parsedMajor = Integer.parseInt(v.substring(0, dot));
            String rest = v.substring(dot + 1);
            if (rest.isEmpty()) {
                parsedSuffix = null;
            } else {
                parsedSuffix = rest;
            }
        }
        this(parsedMajor, parsedSuffix);
    }

    public static Version parse(String version) {
        if (version == null) {
            return EMPTY;
        }
        String v = version.trim();
        if (v.isEmpty()) {
            return EMPTY;
        }
        return new Version(v);
    }

    @Override
    public String toString() {
        return suffix == null ? Integer.toString(major) : major + "." + suffix;
    }

    @Override
    public int compareTo(Version other) {
        if (other == this) {
            return 0;
        }
        int r = Integer.compare(this.major, other.major);
        if (r != 0) {
            return r;
        }
        if (this.suffix == null && other.suffix == null) {
            return 0;
        }
        if (this.suffix == null) {
            return -1;
        }
        if (other.suffix == null) {
            return 1;
        }
        return compareSuffix(this.suffix, other.suffix);
    }

    private static int compareSuffix(String a, String b) {
        Integer na = parseIntOrNull(a);
        Integer nb = parseIntOrNull(b);
        if (na != null && nb != null) {
            return Integer.compare(na, nb);
        }
        return a.compareTo(b);
    }

    private static Integer parseIntOrNull(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
