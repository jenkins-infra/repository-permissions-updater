package io.jenkins.infra.repository_permissions_updater;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.Gson;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ArtifactoryPermissionUpdaterTest {

    private static File payloads;

    @BeforeAll
    static void preparePayloads() throws IOException {
        File permissions = Files.createTempDirectory("permissions").toFile();
        permissions.deleteOnExit();
        payloads = Files.createTempDirectory("json").toFile();
        Files.copy(
                Path.of("permissions/plugin-delphix.yml"),
                Path.of(permissions.getAbsolutePath(), "plugin-delphix.yml"));
        ArtifactoryPermissionsUpdater.doGenerateApiPayloads(permissions, payloads, new MockArtifactoryAPI());
    }

    @Test
    void shouldMatchIncludePattern() throws IOException {
        Map<String, Object> map = new HashMap<>();
        map = parseJson(map, "permissions", "generatedv2-plugin-delphix.json");
        assertEquals(
                "org/jenkins-ci/plugins/delphix/*/delphix-*," + "org/jenkins-ci/plugins/delphix/*/maven-metadata.xml,"
                        + "org/jenkins-ci/plugins/delphix/*/maven-metadata.xml.*,"
                        + "org/jenkins-ci/plugins/delphix/maven-metadata.xml,"
                        + "org/jenkins-ci/plugins/delphix/maven-metadata.xml.*,"
                        + "org/jenkins-ci/plugins/delphix-*/*/delphix-*,"
                        + "org/jenkins-ci/plugins/delphix-*/*/maven-metadata.xml,"
                        + "org/jenkins-ci/plugins/delphix-*/*/maven-metadata.xml.*,"
                        + "org/jenkins-ci/plugins/delphix-*/maven-metadata.xml,"
                        + "org/jenkins-ci/plugins/delphix-*/maven-metadata.xml.*",
                map.get("includesPattern"));
    }

    @Test
    void shouldListMaintainers() throws IOException {
        Map<String, List<String>> map = new HashMap<>();
        map = parseJson(map, "maintainers.index.json");
        List<String> keys = map.keySet().stream().sorted().collect(Collectors.toList());
        assertEquals(List.of("org.jenkins-ci.plugins:delphix", "org.jenkins-ci.plugins:delphix-plugin"), keys);
    }

    private static <T> T parseJson(T template, String... path) throws IOException {
        String stream = Files.readString(Path.of(payloads.getAbsolutePath(), path));
        return (T) new Gson().fromJson(stream, template.getClass());
    }

    private static class MockArtifactoryAPI extends ArtifactoryAPI {
        @Override
        public List<String> listGeneratedPermissionTargets() {
            return List.of();
        }

        @Override
        public void createOrReplacePermissionTarget(@NonNull String name, @NonNull File payloadFile) {}

        @Override
        public void deletePermissionTarget(@NonNull String target) {}

        @NonNull
        @Override
        public List<String> listGeneratedGroups() {
            return List.of();
        }

        @Override
        public void createOrReplaceGroup(String name, File payloadFile) {}

        @Override
        public void deleteGroup(String group) {}

        @Override
        public String generateTokenForGroup(String username, String group, long expiresInSeconds) {
            return "";
        }
    }
}
