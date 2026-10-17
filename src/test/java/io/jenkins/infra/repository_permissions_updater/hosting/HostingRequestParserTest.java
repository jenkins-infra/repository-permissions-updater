package io.jenkins.infra.repository_permissions_updater.hosting;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HostingRequestParserTest {

    @Test
    void basic() {
        Map<String, HostingRequestParser.Field> hostingRequest =
                HostingRequestParser.convert("### Repository URL\n" + "\n"
                        + "https://github.com/timja/dummy-jenkins-plugin\n"
                        + "\n"
                        + "### New Repository Name\n"
                        + "\n"
                        + "dummy-plugin\n"
                        + "\n"
                        + "### Description\n"
                        + "\n"
                        + "TODO Tell users how to configure your plugin here, include screenshots, pipeline examples and configuration-as-code examples.\n"
                        + "\n"
                        + "### GitHub users to have commit permission\n"
                        + "\n"
                        + "@timja \n"
                        + "\n"
                        + "### Jenkins project users to have release permission\n"
                        + "\n"
                        + "timja\n"
                        + "\n"
                        + "### Issue tracker\n"
                        + "\n"
                        + "GitHub issues");

        assertNotNull(hostingRequest);
        assertEquals(
                "https://github.com/timja/dummy-jenkins-plugin",
                hostingRequest.get("Repository URL").asString());

        assertEquals("dummy-plugin", hostingRequest.get("New Repository Name").asString());

        assertEquals("GitHub issues", hostingRequest.get("Issue tracker").asString());

        assertEquals(
                Collections.singletonList("@timja"),
                hostingRequest.get("GitHub users to have commit permission").asList());
    }

    @Test
    void withHostingChecksCheckbox() {
        HostingRequest request = HostingRequestParser.parse(
                "### Repository URL\n\n"
                        + "https://github.com/timja/dummy-jenkins-plugin\n\n"
                        + "### New Repository Name\n\n"
                        + "dummy-plugin\n\n"
                        + "### Description\n\n"
                        + "A plugin\n\n"
                        + "### GitHub users to have commit permission\n\n"
                        + "@timja\n\n"
                        + "### Jenkins project users to have release permission\n\n"
                        + "timja\n\n"
                        + "### Automated release via GitHub Actions (recommended)\n\n"
                        + "Yes\n\n"
                        + "### Hosting checks\n\n"
                        + "- [X] I have [run the hosting checks locally](https://github.com/jenkins-infra/repository-permissions-updater#running-the-hosting-checks-locally) against this repository and fixed all issues marked Required");

        assertEquals("https://github.com/timja/dummy-jenkins-plugin", request.getRepositoryUrl());
        assertEquals("dummy-plugin", request.getNewRepoName());
        assertEquals(Collections.singletonList("timja"), request.getGithubUsers());
        assertEquals(Collections.singletonList("timja"), request.getJenkinsProjectUsers());
        assertTrue(request.isEnableCD());
    }
}
