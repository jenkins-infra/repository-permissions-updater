package io.jenkins.infra.repository_permissions_updater.hosting;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class HostingRequestParserTest {

    @Test
    void basic() {
        HashSet<VerificationMessage> hostingIssues = new HashSet<>();
        HostingRequest hostingRequest = HostingRequestParser.parse("""
                        ### Repository URL

                        https://github.com/timja/dummy-jenkins-plugin

                        ### New Repository Name

                        dummy-plugin

                        ### Description

                        TODO Tell users how to configure your plugin here, include screenshots, pipeline examples and configuration-as-code examples.

                        ### GitHub and Jenkins project users

                        @timja timja
                        user2 @user2

                        ### Automated release via GitHub Actions (recommended)

                        yes

                        """, hostingIssues);

        assertNotNull(hostingRequest);
        assertEquals("https://github.com/timja/dummy-jenkins-plugin", hostingRequest.getRepositoryUrl());

        assertEquals("dummy-plugin", hostingRequest.getNewRepoName());

        assertTrue(hostingRequest.isEnableCD());

        assertEquals(List.of("@timja", "@user2"), hostingRequest.getGithubUsers());
        assertEquals(List.of("timja", "user2"), hostingRequest.getJenkinsProjectUsers());
        assertTrue(hostingIssues.isEmpty());
    }

    @Test
    void missingJenkinsUser() {
        HashSet<VerificationMessage> hostingIssues = new HashSet<>();
        HostingRequest hostingRequest = HostingRequestParser.parse("""
                        ### Repository URL

                        https://github.com/timja/dummy-jenkins-plugin

                        ### New Repository Name

                        dummy-plugin

                        ### Description

                        TODO Tell users how to configure your plugin here, include screenshots, pipeline examples and configuration-as-code examples.

                        ### GitHub and Jenkins project users

                        @timja @timja

                        ### Automated release via GitHub Actions (recommended)

                        no

                        """, hostingIssues);

        assertNotNull(hostingRequest);
        assertEquals("https://github.com/timja/dummy-jenkins-plugin", hostingRequest.getRepositoryUrl());

        assertEquals("dummy-plugin", hostingRequest.getNewRepoName());

        assertFalse(hostingRequest.isEnableCD());

        assertEquals(Collections.singletonList("@timja"), hostingRequest.getGithubUsers());
        assertTrue(hostingRequest.getJenkinsProjectUsers().isEmpty());
        assertFalse(hostingIssues.isEmpty());
    }

    @Test
    void missingGithubUser() {
        HashSet<VerificationMessage> hostingIssues = new HashSet<>();
        HostingRequest hostingRequest = HostingRequestParser.parse("""
                        ### Repository URL

                        https://github.com/timja/dummy-jenkins-plugin

                        ### New Repository Name

                        dummy-plugin

                        ### Description

                        TODO Tell users how to configure your plugin here, include screenshots, pipeline examples and configuration-as-code examples.

                        ### GitHub and Jenkins project users

                        timja timja

                        ### Automated release via GitHub Actions (recommended)

                        yes

                        """, hostingIssues);

        assertNotNull(hostingRequest);
        assertEquals("https://github.com/timja/dummy-jenkins-plugin", hostingRequest.getRepositoryUrl());

        assertEquals("dummy-plugin", hostingRequest.getNewRepoName());

        assertTrue(hostingRequest.isEnableCD());

        assertTrue(hostingRequest.getGithubUsers().isEmpty());
        assertEquals(Collections.singletonList("timja"), hostingRequest.getJenkinsProjectUsers());
        assertFalse(hostingIssues.isEmpty());
    }

    @Test
    void withHostingChecksCheckbox() {
        HashSet<VerificationMessage> hostingIssues = new HashSet<>();
        HostingRequest request = HostingRequestParser.parse(
                "### Repository URL\n\n"
                        + "https://github.com/timja/dummy-jenkins-plugin\n\n"
                        + "### New Repository Name\n\n"
                        + "dummy-plugin\n\n"
                        + "### Description\n\n"
                        + "A plugin\n\n"
                        + "### GitHub and Jenkins project users\n\n"
                        + "@timja timja\n\n"
                        + "### Automated release via GitHub Actions (recommended)\n\n"
                        + "Yes\n\n"
                        + "### Hosting checks\n\n"
                        + "- [X] I have [run the hosting checks locally](https://github.com/jenkins-infra/repository-permissions-updater#running-the-hosting-checks-locally) against this repository and fixed all issues marked Required",
                hostingIssues);

        assertEquals("https://github.com/timja/dummy-jenkins-plugin", request.getRepositoryUrl());
        assertEquals("dummy-plugin", request.getNewRepoName());
        assertEquals(Collections.singletonList("@timja"), request.getGithubUsers());
        assertEquals(Collections.singletonList("timja"), request.getJenkinsProjectUsers());
        assertTrue(request.isEnableCD());
        assertTrue(hostingIssues.isEmpty(), hostingIssues::toString);
    }
}
