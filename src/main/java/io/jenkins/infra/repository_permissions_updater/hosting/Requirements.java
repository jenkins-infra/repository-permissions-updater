package io.jenkins.infra.repository_permissions_updater.hosting;

import java.util.List;

/**
 * Requirements for hosting Jenkins plugins repositories.
 */
public class Requirements {
    public static final Version LOWEST_PARENT_POM_VERSION = new Version(6, "6.2211.v27f680c93c53");
    public static final Version PARENT_POM_WITH_JENKINS_VERSION = new Version(2);
    public static final Version LOWEST_JENKINS_VERSION = new Version(2, 541, 3);
    public static final List<Integer> ALLOWED_JDK_VERSIONS = List.of(21, 25);
}
