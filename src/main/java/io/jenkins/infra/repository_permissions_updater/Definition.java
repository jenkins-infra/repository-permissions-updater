package io.jenkins.infra.repository_permissions_updater;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@SuppressFBWarnings("UUF_UNUSED_PUBLIC_OR_PROTECTED_FIELD")
public class Definition {
    public static class CD {
        public boolean enabled;
    }
    private String name = "";
    private String[] paths = new String[0];
    private String[] developers = new String[0];

    private String github;

    public CD getCd() {
        return cd;
    }

    public void setCd(CD cd) {
        this.cd = cd;
    }

    private CD cd;
    private Object security; // unused, just metadata for Jenkins security team

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String[] getPaths() {
        return paths.clone();
    }

    public void setPaths(String[] paths) {
        this.paths = paths.clone();
    }

    public String[] getDevelopers() {
        return developers.clone();
    }

    public void setDevelopers(String[] developers) {
        this.developers = developers.clone();
    }

    public void setGithub(String github) {
        this.github = github;
    }

    public void setSecurity(Object security) {
        this.security = security;
    }

    public String getGithub() {
        return github;
    }
}