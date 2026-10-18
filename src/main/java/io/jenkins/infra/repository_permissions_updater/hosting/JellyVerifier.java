package io.jenkins.infra.repository_permissions_updater.hosting;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Locale;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class JellyVerifier implements Verifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(JellyVerifier.class);
    private static final String INLINE_STYLE = "The jelly file %s contains an inline `<style>` tag";
    private static final String INLINE_SCRIPT = "The jelly file %s contains a `<script>` tag with inline javascript.";
    private static final String INLINE_SCRIPT_METHOD =
            "The jelly file %s potentially uses inline javascript attribute `%s`";
    private static final String INLINE_TAGLIB_ONCLICK_METHOD =
            "The jelly file %s potentially uses the `onclick` attribute of a jelly taglib";
    private static final String LEGACY_CHECK_URL =
            "The jelly file %s makes use of the legacy `checkUrl` form without using `checkDependsOn`";
    private static final String CSP_HELP =
            "One or more usages of inline javascript tags, style tags or event handlers have been identified. See "
                    + "https://www.jenkins.io/doc/developer/security/csp/ for more information how to make your jelly files CSP compliant";

    private final HashSet<VerificationMessage> hostingIssues;
    private final RepositoryCheckout checkout;

    public JellyVerifier(HashSet<VerificationMessage> hostingIssues, RepositoryCheckout checkout) {
        this.hostingIssues = hostingIssues;
        this.checkout = checkout;
    }

    @Override
    public void verify(HostingRequest issue) throws IOException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        try {
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            for (String jellyFile : checkout.findFiles("src/main/resources", ".jelly")) {
                String url = checkout.fileUrl(jellyFile);
                try (StringReader contents =
                        new StringReader(checkout.read(jellyFile).orElseThrow())) {
                    Document doc = db.parse(new InputSource(contents));
                    Element root = doc.getDocumentElement();
                    boolean hasIssues = checkStyleElement(root, hostingIssues, url);
                    hasIssues |= checkScriptElement(root, hostingIssues, url);
                    hasIssues |= checkJavaScriptAttributes(root, hostingIssues, url);
                    if (hasIssues) {
                        hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.INFO, CSP_HELP));
                    }
                } catch (SAXException e) {
                    LOGGER.warn("Failed to parse jelly {}", jellyFile);
                }
            }
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        }
    }

    static boolean checkStyleElement(Element root, HashSet<VerificationMessage> hostingIssues, String jellyPath) {
        NodeList styleElements = root.getElementsByTagName("style");
        if (styleElements.getLength() > 0) {
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, INLINE_STYLE, jellyPath));
            return true;
        }
        return false;
    }

    static boolean checkScriptElement(Element root, HashSet<VerificationMessage> hostingIssues, String jellyPath) {
        NodeList scriptElements = root.getElementsByTagName("script");
        for (int i = 0; i < scriptElements.getLength(); i++) {
            Node e = scriptElements.item(i);
            Attr typeAttribute = (Attr) e.getAttributes().getNamedItem("type");
            if (e.getAttributes().getNamedItem("src") == null
                    && (typeAttribute == null
                            || !"application/json"
                                    .equals(typeAttribute.getValue().toLowerCase(Locale.US)))) {
                hostingIssues.add(
                        new VerificationMessage(VerificationMessage.Severity.REQUIRED, INLINE_SCRIPT, jellyPath));
                return true;
            }
        }
        return false;
    }

    static boolean checkJavaScriptAttributes(
            Element root, HashSet<VerificationMessage> hostingIssues, String jellyPath) {
        NodeList allElements = root.getElementsByTagName("*");
        boolean hasIssues = false;
        for (int i = 0; i < allElements.getLength(); i++) {
            Node e = allElements.item(i);
            // ignore namespaced element, those are usually references to taglibs, except for onclick which
            Attr onclick = (Attr) e.getAttributes().getNamedItem("onclick");

            Attr checkUrl = (Attr) e.getAttributes().getNamedItem("checkUrl");
            Attr checkDependsOn = (Attr) e.getAttributes().getNamedItem("checkDependsOn");
            if (checkUrl != null && checkDependsOn == null) {
                hostingIssues.add(
                        new VerificationMessage(VerificationMessage.Severity.REQUIRED, LEGACY_CHECK_URL, jellyPath));
                hasIssues = true;
            }
            if (onclick != null && e.getNamespaceURI() != null) {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.WARNING, INLINE_TAGLIB_ONCLICK_METHOD, jellyPath));
                hasIssues = true;
                continue;
            }
            if (e.getNamespaceURI() == null && e.hasAttributes()) {
                NamedNodeMap attributes = e.getAttributes();
                for (int j = 0; j < attributes.getLength(); j++) {
                    Attr attr = (Attr) attributes.item(j);
                    if (attr.getNodeName().startsWith("on")) {
                        hostingIssues.add(new VerificationMessage(
                                VerificationMessage.Severity.WARNING, INLINE_SCRIPT_METHOD, jellyPath, attr.getName()));
                        hasIssues = true;
                    }
                }
            }
        }
        return hasIssues;
    }
}
