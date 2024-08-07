package io.jenkins.infra.repository_permissions_updater.hosting.model;

import java.util.NoSuchElementException;
import java.util.StringTokenizer;

public class Version implements Comparable<Version> {

    private final int major;
    private final int minor;
    private final int patch;
    private static final String SEPARATOR = ".";

    public static final Version EMPTY = new Version(0, 0, 0);

    public Version(int major, int minor, int patch) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        validate();
    }

    public Version(int major) {
        this(major, -1, -1);
    }

    public Version(int major, int minor) {
        this(major, minor, -1);
    }

    public Version(String version) {
        int major;
        int minor = -1;
        int patch = -1;

        try {
            StringTokenizer st = new StringTokenizer(version, SEPARATOR, true);
            major = Integer.parseInt(st.nextToken());

            if (st.hasMoreTokens()) {
                st.nextToken(); // consume delimiter
                minor = Integer.parseInt(st.nextToken());

                if (st.hasMoreTokens()) {
                    st.nextToken(); // consume delimiter
                    patch = Integer.parseInt(st.nextToken());

                    if (st.hasMoreTokens()) {
                        throw new IllegalArgumentException("invalid format");
                    }
                }
            }
        } catch (NoSuchElementException e) {
            throw new IllegalArgumentException("invalid format");
        }

        this.major = major;
        this.minor = minor;
        this.patch = patch;
        validate();
    }

    private void validate() {
        if (major < 0) {
            throw new IllegalArgumentException("negative major");
        }

        if (minor < 0 && patch >= 0) {
            throw new IllegalArgumentException("negative minor with micro provided");
        }
    }

    public static Version parse(String version) {
        if (version == null) {
            return EMPTY;
        }

        version = version.trim();
        if (version.length() == 0) {
            return EMPTY;
        }

        return new Version(version);
    }

    public int getMajor() {
        return major;
    }

    public int getMinor() {
        return Math.max(minor, 0);
    }

    public int getPatch() {
        return Math.max(patch, 0);
    }

    public String toString() {
        if (major >= 0 && minor >= 0 && patch < 0) {
            return major + SEPARATOR + minor;
        } else if (major >= 0 && minor < 0) {
            return "" + major;
        }
        return major + SEPARATOR + minor + SEPARATOR + patch;
    }

    public int hashCode() {
        return (major << 24) + (minor << 16) + (patch << 8);
    }

    public boolean equals(Object object) {
        if (object == this) {
            return true;
        }

        if (!(object instanceof Version)) {
            return false;
        }

        Version other = (Version) object;
        return (major == other.major) && (minor == other.minor)
                && (patch == other.patch);
    }

    public int compareTo(Version other) {
        if (other == this) {
            return 0;
        }

        int localMinor = Math.max(minor, 0);
        int localMicro = Math.max(patch, 0);

        int result = major - other.major;
        if (result != 0) {
            return result;
        }

        result = localMinor - other.getMinor();
        if (result != 0) {
            return result;
        }

        result = localMicro - other.getPatch();
        return result;
    }
}
