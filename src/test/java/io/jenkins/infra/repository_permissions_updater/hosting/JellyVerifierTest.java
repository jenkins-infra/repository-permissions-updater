package io.jenkins.infra.repository_permissions_updater.hosting;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashSet;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
}
