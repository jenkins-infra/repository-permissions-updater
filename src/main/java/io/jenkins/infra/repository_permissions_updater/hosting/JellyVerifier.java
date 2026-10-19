package io.jenkins.infra.repository_permissions_updater.hosting;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    static final String CHECK_METHOD_POST =
            "The following jelly files set `checkMethod=\"post\"`. POST is the default for form validation, so this can be removed, "
                    + "and it is not supported by all tags (e.g. `c:select`):";
    static final String REPEATABLE_WITHOUT_DELETE =
            "The following jelly files use `f:repeatable` or `f:repeatableProperty` without a `f:repeatableDeleteButton` inside it, so users cannot remove entries. "
                    + "If the order matters, also set the `header` attribute to allow reordering entries with drag and drop:";
    static final String CREDENTIALS_WITHOUT_SELECT =
            "The following jelly files ask for a credentials ID in a text box or a plain select. "
                    + "Use `<c:select/>` from the credentials plugin and implement `doFill...Items` with `StandardListBoxModel` so users can select from the available credentials:";

    private static final String FORM_TAGLIB = "/lib/form";
    private static final String CREDENTIALS_TAGLIB = "/lib/credentials";

    private final HashSet<VerificationMessage> hostingIssues;
    private final RepositoryFiles files;

    public JellyVerifier(HashSet<VerificationMessage> hostingIssues, RepositoryFiles files) {
        this.hostingIssues = hostingIssues;
        this.files = files;
    }

    @Override
    public void verify(HostingRequest issue) throws IOException {
        Map<String, String> jellyFiles =
                files.matching(path -> path.endsWith(".jelly") && path.startsWith("src/main/resources/"));

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        try {
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            HashSet<VerificationMessage> checkMethodPost = new HashSet<>();
            HashSet<VerificationMessage> repeatableWithoutDelete = new HashSet<>();
            HashSet<VerificationMessage> credentialsWithoutSelect = new HashSet<>();
            for (Map.Entry<String, String> jellyFile : jellyFiles.entrySet()) {
                String link = files.link(jellyFile.getKey(), 0);
                try {
                    Document doc = db.parse(new InputSource(new StringReader(jellyFile.getValue())));
                    Element root = doc.getDocumentElement();
                    boolean hasIssues = checkStyleElement(root, hostingIssues, link);
                    hasIssues |= checkScriptElement(root, hostingIssues, link);
                    hasIssues |= checkJavaScriptAttributes(root, hostingIssues, link);
                    if (hasIssues) {
                        hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.INFO, CSP_HELP));
                    }
                    if (hasCheckMethodPost(root)) {
                        checkMethodPost.add(new VerificationMessage(VerificationMessage.Severity.INFO, "%s", link));
                    }
                    if (hasRepeatableWithoutDeleteButton(root)) {
                        repeatableWithoutDelete.add(
                                new VerificationMessage(VerificationMessage.Severity.INFO, "%s", link));
                    }
                    if (hasCredentialsFieldWithoutCredentialsSelect(root)) {
                        credentialsWithoutSelect.add(
                                new VerificationMessage(VerificationMessage.Severity.INFO, "%s", link));
                    }
                } catch (SAXException e) {
                    LOGGER.warn("Failed to parse jelly {}", jellyFile.getKey());
                }
            }
            addWithSubItems(VerificationMessage.Severity.INFO, CHECK_METHOD_POST, checkMethodPost);
            addWithSubItems(VerificationMessage.Severity.WARNING, REPEATABLE_WITHOUT_DELETE, repeatableWithoutDelete);
            addWithSubItems(VerificationMessage.Severity.WARNING, CREDENTIALS_WITHOUT_SELECT, credentialsWithoutSelect);
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        }
    }

    private void addWithSubItems(
            VerificationMessage.Severity severity, String message, HashSet<VerificationMessage> findings) {
        if (!findings.isEmpty()) {
            hostingIssues.add(new VerificationMessage(severity, findings, "%s", message));
        }
    }

    /**
     * {@code checkMethod="post"} is the default, and not supported on all tags.
     */
    static boolean hasCheckMethodPost(Element root) {
        NodeList allElements = root.getElementsByTagName("*");
        for (int i = 0; i < allElements.getLength(); i++) {
            Element e = (Element) allElements.item(i);
            if ("post".equalsIgnoreCase(e.getAttribute("checkMethod"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Without {@code f:repeatableDeleteButton} users can add entries to an {@code f:repeatable} or
     * {@code f:repeatableProperty} but not remove them.
     * For {@code f:repeatableProperty} the button could also be in the nested type's {@code config.jelly}, but that is
     * deprecated, the documentation of the tag says to put it inside the tag instead.
     */
    static boolean hasRepeatableWithoutDeleteButton(Element root) {
        for (String tag : List.of("repeatable", "repeatableProperty")) {
            NodeList repeatables = root.getElementsByTagNameNS(FORM_TAGLIB, tag);
            for (int i = 0; i < repeatables.getLength(); i++) {
                Element repeatable = (Element) repeatables.item(i);
                if (repeatable
                                .getElementsByTagNameNS(FORM_TAGLIB, "repeatableDeleteButton")
                                .getLength()
                        == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Fields for credentials IDs should let users select from the available credentials with {@code c:select}.
     */
    static boolean hasCredentialsFieldWithoutCredentialsSelect(Element root) {
        NodeList entries = root.getElementsByTagNameNS(FORM_TAGLIB, "entry");
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            if (!entry.getAttribute("field").toLowerCase(Locale.US).matches(".*credentials?id.*")) {
                continue;
            }
            boolean credentialsSelect =
                    entry.getElementsByTagNameNS(CREDENTIALS_TAGLIB, "select").getLength() > 0;
            boolean otherInput = entry.getElementsByTagNameNS(FORM_TAGLIB, "textbox")
                                    .getLength()
                            > 0
                    || entry.getElementsByTagNameNS(FORM_TAGLIB, "select").getLength() > 0
                    || entry.getElementsByTagNameNS(FORM_TAGLIB, "password").getLength() > 0;
            if (otherInput && !credentialsSelect) {
                return true;
            }
        }
        return false;
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
