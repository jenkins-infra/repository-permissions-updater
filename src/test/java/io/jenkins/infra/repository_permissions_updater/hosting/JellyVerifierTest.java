package io.jenkins.infra.repository_permissions_updater.hosting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

public class JellyVerifierTest {

    private HashSet<VerificationMessage> hostingIssues;

    @BeforeEach
    public void reset() {
        hostingIssues = new HashSet<>();
    }

    @Test
    public void inlineStyle() {
        Element root = loadJelly("inlineStyle");
        JellyVerifier.checkStyleElement(root, hostingIssues, "inlineStyle");
        Assertions.assertEquals(1, hostingIssues.size());
    }

    @Test
    public void inlineScript() {
        Element root = loadJelly("inlineScript");
        JellyVerifier.checkScriptElement(root, hostingIssues, "inlineScript");
        Assertions.assertEquals(1, hostingIssues.size());
    }

    @Test
    public void inlineScriptJson() {
        Element root = loadJelly("inlineScriptJson");
        JellyVerifier.checkScriptElement(root, hostingIssues, "inlineScriptJson");
        Assertions.assertEquals(0, hostingIssues.size());
    }

    @Test
    public void checkJavaScriptAttributes() {
        Element root = loadJelly("inlineScriptAttribute");
        JellyVerifier.checkJavaScriptAttributes(root, hostingIssues, "inlineScriptAttribute");
        Assertions.assertEquals(4, hostingIssues.size());
    }

    @Test
    public void ok() {
        Element root = loadJelly("ok");
        JellyVerifier.checkStyleElement(root, hostingIssues, "ok");
        JellyVerifier.checkScriptElement(root, hostingIssues, "ok");
        JellyVerifier.checkJavaScriptAttributes(root, hostingIssues, "ok");
        Assertions.assertEquals(0, hostingIssues.size());
    }

    @Test
    public void formIssues() {
        Element root = loadJelly("formIssues");
        Assertions.assertTrue(JellyVerifier.hasCheckMethodPost(root));
        Assertions.assertTrue(JellyVerifier.hasRepeatableWithoutDeleteButton(root));
        Assertions.assertTrue(JellyVerifier.hasCredentialsFieldWithoutCredentialsSelect(root));
    }

    @Test
    public void formOk() {
        Element root = loadJelly("formOk");
        Assertions.assertFalse(JellyVerifier.hasCheckMethodPost(root));
        Assertions.assertFalse(JellyVerifier.hasRepeatableWithoutDeleteButton(root));
        Assertions.assertFalse(JellyVerifier.hasCredentialsFieldWithoutCredentialsSelect(root));
    }

    private Element loadJelly(String file) {
        String fullPath = "src/test/resources/" + file + ".jelly";
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        try {
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new FileInputStream(fullPath));
            return doc.getDocumentElement();
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void verifyFindsJellyFilesInCheckout(@TempDir Path dir) throws Exception {
        TestGitRepository repo = new TestGitRepository(dir)
                .commit(
                        "src/main/resources/io/jenkins/Cool/config.jelly",
                        Files.readString(Path.of("src/test/resources/inlineScript.jelly")))
                .commit(
                        "src/main/resources/io/jenkins/Cool/ok.jelly",
                        Files.readString(Path.of("src/test/resources/ok.jelly")))
                .commit(
                        "src/test/resources/ignored.jelly",
                        Files.readString(Path.of("src/test/resources/inlineStyle.jelly")));

        try (RepositoryCheckout checkout = repo.checkout()) {
            new JellyVerifier(hostingIssues, RepositoryFiles.from(checkout))
                    .verify(new HostingRequest(
                            TestGitRepository.HTML_URL, "cool-plugin", List.of(), List.of(), null, false));
        }

        assertThat(hostingIssues.stream().map(VerificationMessage::getMessage))
                .hasSize(2)
                .anyMatch(
                        m -> m.matches(
                                "The jelly file \\[src/main/resources/io/jenkins/Cool/config.jelly]"
                                        + "\\(https://github.com/me/cool-plugin/blob/[0-9a-f]{40}/"
                                        + "src/main/resources/io/jenkins/Cool/config.jelly\\) contains a `<script>` tag with inline javascript."))
                .anyMatch(m -> m.startsWith("One or more usages of inline javascript"));
    }
}
