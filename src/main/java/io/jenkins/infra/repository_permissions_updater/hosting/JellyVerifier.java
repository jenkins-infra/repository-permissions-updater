package io.jenkins.infra.repository_permissions_updater.hosting;

import static java.util.regex.Pattern.CASE_INSENSITIVE;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHContentSearchBuilder;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedSearchIterable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
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

    @Override
    public void verify(HostingRequest issue, HashSet<VerificationMessage> hostingIssues) throws IOException {
        GitHub github = GitHub.connect();
        String forkFrom = issue.getRepositoryUrl();
        if (StringUtils.isNotBlank(forkFrom)) {
            Matcher m = Pattern.compile("(?:https://github\\.com/)?(\\S+)/(\\S+)", CASE_INSENSITIVE)
                    .matcher(forkFrom);
            if (m.matches()) {
                String owner = m.group(1);
                String repoName = m.group(2);

                GHContentSearchBuilder search = github.searchContent();
                PagedSearchIterable<GHContent> list = search.q(".jelly")
                        .in("path")
                        .repo(owner + "/" + repoName)
                        .list();
                List<GHContent> jellyFiles = list.toList().stream()
                        .filter(item -> item.getPath().endsWith(".jelly")
                                && item.getPath().startsWith("src/main/resources/"))
                        .toList();

                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                dbf.setNamespaceAware(true);
                try {
                    dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                    DocumentBuilder db = dbf.newDocumentBuilder();
                    for (GHContent jellyFile : jellyFiles) {
                        InputStream is = jellyFile.read();
                        try {
                            Document doc = db.parse(is);
                            Element root = doc.getDocumentElement();
                            boolean hasIssues = checkStyleElement(root, hostingIssues, jellyFile.getHtmlUrl());
                            hasIssues |= checkScriptElement(root, hostingIssues, jellyFile.getHtmlUrl());
                            hasIssues |= checkJavaScriptAttributes(root, hostingIssues, jellyFile.getHtmlUrl());
                            if (hasIssues) {
                                hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.INFO, CSP_HELP));
                            }
                        } catch (SAXException e) {
                            LOGGER.warn("Failed to parse jelly {}", jellyFile.getPath());
                        }
                    }
                } catch (ParserConfigurationException e) {
                    throw new RuntimeException(e);
                }
            }
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
