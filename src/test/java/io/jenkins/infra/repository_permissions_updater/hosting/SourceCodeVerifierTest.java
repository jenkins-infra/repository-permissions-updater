package io.jenkins.infra.repository_permissions_updater.hosting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SourceCodeVerifierTest {

    private static final String BLOB_URL = "https://github.com/owner/repo/blob/abc/";

    private HashSet<VerificationMessage> hostingIssues;

    @BeforeEach
    public void reset() {
        hostingIssues = new HashSet<>();
    }

    private static RepositoryFiles files(String... pathsAndContents) {
        Map<String, String> files = new java.util.HashMap<>();
        for (int i = 0; i < pathsAndContents.length; i += 2) {
            files.put(pathsAndContents[i], pathsAndContents[i + 1]);
        }
        return new RepositoryFiles(BLOB_URL, files);
    }

    private VerificationMessage single() {
        assertEquals(1, hostingIssues.size(), hostingIssues::toString);
        return hostingIssues.iterator().next();
    }

    @Test
    public void proxySupport() {
        SourceCodeVerifier.checkProxySupport(
                files(
                        "src/main/java/a/Client.java",
                        "class Client {\n  void get() {\n    HttpURLConnection c = (HttpURLConnection) url.openConnection();\n  }\n}",
                        "src/main/java/a/Download.java",
                        "import java.net.HttpURLConnection;\nclass Download {\n  void get() {\n    archive.copyFrom(new URL(downloadUrl));\n  }\n}",
                        "src/main/java/a/Secure.java",
                        "class Secure {\n  HttpsURLConnection connection;\n}",
                        "src/main/java/a/Good.java",
                        "class Good {\n  HttpClient c = ProxyConfiguration.newHttpClientBuilder().build();\n}",
                        "src/main/java/a/Commented.java",
                        "class Commented {\n  // HttpClient.newHttpClient() is not used\n  String url = \"https://example.com\";\n}"),
                hostingIssues);
        VerificationMessage message = single();
        assertEquals(VerificationMessage.Severity.WARNING, message.getSeverity());
        assertEquals(
                Set.of(
                        "[Client.java#L3](" + BLOB_URL + "src/main/java/a/Client.java#L3)",
                        "[Download.java#L4](" + BLOB_URL + "src/main/java/a/Download.java#L4)",
                        "[Secure.java#L2](" + BLOB_URL + "src/main/java/a/Secure.java#L2)"),
                message.getSubItems().stream()
                        .map(VerificationMessage::getMessage)
                        .collect(Collectors.toSet()));
    }

    @Test
    public void jsr305() {
        SourceCodeVerifier.checkJsr305Annotations(
                files(
                        "src/main/java/a/A.java", "package a;\nimport javax.annotation.Nonnull;\n",
                        "src/main/java/a/B.java", "package a;\nimport javax.annotation.PostConstruct;\n",
                        "src/main/java/a/C.java", "package a;\nimport edu.umd.cs.findbugs.annotations.NonNull;\n"),
                hostingIssues);
        assertEquals(1, single().getSubItems().size());
    }

    @Test
    public void checkPermissionInFillMethod() {
        SourceCodeVerifier.checkPermissionInFillMethods(files("src/main/java/a/Step.java", """
                        class Step {
                          public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item item) {
                            item.checkPermission(Item.CONFIGURE);
                            return new ListBoxModel();
                          }
                          public ListBoxModel doFillOtherItems(@AncestorInPath Item item) {
                            if (!item.hasPermission(Item.CONFIGURE)) {
                              return new ListBoxModel();
                            }
                            return new ListBoxModel();
                          }
                          public FormValidation doCheckUrl() {
                            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
                            return FormValidation.ok();
                          }
                        }
                        """), hostingIssues);
        VerificationMessage message = single();
        assertEquals(1, message.getSubItems().size());
        assertTrue(message.getSubItems().iterator().next().getMessage().contains("#L2"));
    }

    @Test
    public void legacyIcon() {
        SourceCodeVerifier.checkLegacyIcons(
                files(
                        "src/main/java/a/OldAction.java",
                        "class OldAction {\n  public String getIconFileName() {\n    return \"graph.png\";\n  }\n}",
                        "src/main/java/a/NewAction.java",
                        "class NewAction {\n  public String getIconFileName() {\n    return \"symbol-key-outline plugin-ionicons-api\";\n  }\n}"),
                hostingIssues);
        assertEquals(1, single().getSubItems().size());
    }

    @Test
    public void credentialsJelly() {
        SourceCodeVerifier.checkCredentialsJelly(
                files(
                        "src/main/java/a/MyCredentials.java",
                        "class MyCredentials extends BaseStandardCredentials {}",
                        "src/main/resources/a/MyCredentials/config.jelly",
                        "<j:jelly/>",
                        "src/main/java/a/OtherCredentials.java",
                        "class OtherCredentials extends BaseStandardCredentials {}",
                        "src/main/resources/a/OtherCredentials/credentials.jelly",
                        "<j:jelly/>"),
                hostingIssues);
        VerificationMessage message = single();
        assertEquals(1, message.getSubItems().size());
        assertTrue(message.getSubItems().iterator().next().getMessage().contains("MyCredentials/config.jelly"));
    }

    @Test
    public void hardCodedColors() {
        assertEquals(2, SourceCodeVerifier.firstHardCodedColor(".a {\n  color: #ff0000;\n}"));
        assertEquals(1, SourceCodeVerifier.firstHardCodedColor(".a { background-color: rgba(0, 0, 0, 0.5) }"));
        assertEquals(1, SourceCodeVerifier.firstHardCodedColor(".a { border: 1px solid white }"));
        assertEquals(0, SourceCodeVerifier.firstHardCodedColor(".a { color: var(--text-color); }"));
        assertEquals(0, SourceCodeVerifier.firstHardCodedColor(".a { color: var(--text-color, #333); }"));
        assertEquals(0, SourceCodeVerifier.firstHardCodedColor("a:hover { padding: 0; }"));
        assertEquals(0, SourceCodeVerifier.firstHardCodedColor("/* color: #fff */ .a { display: flex; }"));
        assertEquals(0, SourceCodeVerifier.firstHardCodedColor(".icon { fill: url(#gradient); }"));
        assertEquals(
                3,
                SourceCodeVerifier.firstHardCodedColorInJelly(
                        "<j:jelly>\n<div class=\"a\">\n<span style=\"color: #c00\"/>\n</div>\n</j:jelly>"));
        assertEquals(
                0,
                SourceCodeVerifier.firstHardCodedColorInJelly(
                        "<j:jelly><span style=\"color: var(--error-color)\"/></j:jelly>"));

        SourceCodeVerifier.checkHardCodedColors(
                files(
                        "src/main/webapp/css/style.css",
                        ".x { color: #fff; }",
                        "src/main/resources/a/A/index.jelly",
                        "<j:jelly><style>.y { background: #000; }</style></j:jelly>",
                        "src/test/resources/style.css",
                        ".x { color: #fff; }"),
                hostingIssues);
        assertEquals(2, single().getSubItems().size());
    }

    @Test
    public void readme() {
        SourceCodeVerifier.checkReadme(files("README.md", """
                        # My plugin

                        ## Requirements
                        - Jenkins 2.479.3 or newer
                        - Java 17+
                        - Use JDK 21 and Maven to build the plugin

                        ## Installation
                        Go to Manage Jenkins > Plugins > Advanced and upload the plugin `my.hpi`.

                        ```xml
                        <jenkins.version>2.479.3</jenkins.version>
                        ```
                        """), hostingIssues);
        assertEquals(2, hostingIssues.size());
        for (VerificationMessage message : hostingIssues) {
            assertEquals(VerificationMessage.Severity.INFO, message.getSeverity());
            if (message.getMessage().equals(SourceCodeVerifier.README_REQUIREMENTS)) {
                assertEquals(2, message.getSubItems().size());
            } else {
                assertEquals(SourceCodeVerifier.README_MANUAL_INSTALL, message.getMessage());
                assertEquals(1, message.getSubItems().size());
            }
        }
    }

    @Test
    public void readmeWithoutFindings() {
        SourceCodeVerifier.checkReadme(files("README.md", """
                        # My plugin

                        Integrates Jenkins with the Example service.

                        ## Development
                        Run `mvn hpi:run` to start Jenkins with the plugin.
                        """), hostingIssues);
        assertTrue(hostingIssues.isEmpty());
    }

    @Test
    public void stripComments() {
        String code = "a // b\n/* c\n d */ e \"// f\" g";
        String stripped = SourceCodeVerifier.stripComments(code);
        assertEquals(code.length(), stripped.length());
        assertEquals("a     \n    \n      e \"// f\" g", stripped);
    }
}
