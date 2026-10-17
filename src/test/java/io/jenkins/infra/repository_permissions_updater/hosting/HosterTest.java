package io.jenkins.infra.repository_permissions_updater.hosting;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class HosterTest {

    static Stream<Arguments> getDevelopers() {
        return Stream.of(
                Arguments.of(false, null, List.of("user1", "user2"), """
  - "user1"
  - "user2"
""".stripTrailing()),
                Arguments.of(true, List.of(Map.of("github", "@user1", "ldap", "user1")), null, """
  - ldap: "user1"
    github: "user1"
manageGitHubPermissions: true
""".stripTrailing()),
                Arguments.of(
                        true,
                        List.of(
                                Map.of("github", "@user1", "ldap", "user1"),
                                Map.of("github", "@user3"),
                                Map.of("github", "@user2", "ldap", "user2"),
                                Map.of("ldap", "user4")),
                        null,
                        """
  - ldap: "user1"
    github: "user1"
  - github: "user3"
  - ldap: "user2"
    github: "user2"
  - ldap: "user4"
manageGitHubPermissions: true
""".stripTrailing()),
                Arguments.of(true, List.of(Map.of("github", "@user1")), null, """
  - github: "user1"
manageGitHubPermissions: true
""".stripTrailing()),
                Arguments.of(true, List.of(Map.of("ldap", "user1")), null, """
  - ldap: "user1"
manageGitHubPermissions: true
""".stripTrailing()));
    }

    @ParameterizedTest
    @MethodSource("getDevelopers")
    void testGetDevelopers(
            boolean isManageGitHubPermissions,
            List<Map<String, String>> githubJenkinsProjectUsers,
            List<String> releaseUsers,
            String expected) {
        String developers = Hoster.getDevelopers(isManageGitHubPermissions, githubJenkinsProjectUsers, releaseUsers);
        assertEquals(expected, developers);
    }
}
