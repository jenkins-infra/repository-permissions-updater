package io.jenkins.infra.repository_permissions_updater.hosting;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/**
 * Checks the plugin sources for problems that hosting reviews commonly point out.
 */
public class SourceCodeVerifier implements Verifier {

    static final String UNNEEDED_STRUCTS =
            "The dependency on `org.jenkins-ci.plugins:structs` is not needed, `@Symbol` is provided by Jenkins core. "
                    + "Remove it unless you use other APIs from the structs plugin.";
    static final String NO_PROXY_SUPPORT =
            "The following files open HTTP connections without using the proxy configured in Jenkins. "
                    + "Use [`ProxyConfiguration.newHttpClientBuilder()`](https://javadoc.jenkins.io/hudson/ProxyConfiguration.html#newHttpClientBuilder()) "
                    + "to get an `HttpClient` that honours the Jenkins proxy settings:";
    static final String JSR305_ANNOTATIONS =
            "The following files use the deprecated JSR 305 annotations from `javax.annotation`. "
                    + "Use the SpotBugs annotations from `edu.umd.cs.findbugs.annotations` instead, e.g. `@NonNull`, they are available via the parent pom:";
    static final String CHECK_PERMISSION_IN_FILL =
            "The following `doFill...Items` methods call `checkPermission`, which shows an error to users that are allowed to view the form "
                    + "(e.g. with Item/ExtendedRead or in the Pipeline Syntax snippet generator) but not to configure it. "
                    + "Use `hasPermission` and return an empty model instead:";
    static final String LEGACY_ICON =
            "The following actions use an image file as icon. Use a symbol instead, e.g. from the "
                    + "[ionicons-api](https://plugins.jenkins.io/ionicons-api/) plugin, or add your own as described in the "
                    + "[design library](https://weekly.ci.jenkins.io/design-library/symbols/):";
    static final String CREDENTIALS_CONFIG_JELLY =
            "The following credentials types have a `config.jelly` but no `credentials.jelly`. The configuration page of credentials must be named `credentials.jelly`:";
    static final String HARD_CODED_COLORS =
            "The following files use hard coded colors, which might not work well with other themes (e.g. dark theme). "
                    + "Use the theme aware CSS variables from the [design library](https://weekly.ci.jenkins.io/design-library/colors/) instead:";
    static final String README_REQUIREMENTS =
            "The README mentions required Jenkins or Java versions, which gets outdated quickly. "
                    + "Once hosted, the required Jenkins version is shown on plugins.jenkins.io, and the Java version is implied by it:";
    static final String README_MANUAL_INSTALL =
            "The README explains how to install the plugin manually. Once hosted, users install the plugin from the plugin manager, "
                    + "so you can remove these instructions:";

    private static final Pattern HTTP_CLIENT_CREATION = Pattern.compile("\\bHttpURLConnection\\b"
            + "|\\.openConnection\\s*\\("
            + "|\\bHttpClient\\s*\\.\\s*(newHttpClient|newBuilder)\\s*\\("
            + "|\\bHttpClients\\s*\\.\\s*create\\w*\\s*\\("
            + "|\\bHttpClientBuilder\\s*\\.\\s*create\\s*\\("
            + "|\\bnew\\s+OkHttpClient\\b"
            // FilePath#copyFrom(URL) downloads without a proxy, URL#openStream is left out as it is mostly used for
            // local resources
            + "|\\.copyFrom\\s*\\(\\s*(?:new\\s+URL\\b|URI\\s*\\.\\s*create\\s*\\([^;]*\\.toURL\\s*\\()");
    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s[^;]*;", Pattern.MULTILINE);
    private static final Pattern JSR305_IMPORT = Pattern.compile(
            "^\\s*import\\s+(?:static\\s+)?javax\\.annotation\\.(?:Nonnull|Nullable|CheckForNull|CheckReturnValue|"
                    + "ParametersAreNonnullByDefault|ParametersAreNullableByDefault|OverridingMethodsMustInvokeSuper|"
                    + "WillClose|WillCloseWhenClosed|WillNotClose|concurrent\\.|meta\\.)",
            Pattern.MULTILINE);
    private static final Pattern FILL_METHOD = Pattern.compile("\\bdoFill\\w*Items\\s*\\(");
    private static final Pattern ICON_METHOD = Pattern.compile("\\bgetIconFileName\\s*\\(\\s*\\)");
    private static final Pattern IMAGE_LITERAL = Pattern.compile("\"[^\"\\n]*\\.(?:png|gif|jpe?g)\"");
    private static final Pattern CREDENTIALS_CLASS = Pattern.compile(
            "\\bextends\\s+(?:BaseStandardCredentials|BaseCredentials|BaseStandardUsernameCredentials|UsernamePasswordCredentialsImpl)\\b");

    private static final Pattern CSS_DECLARATION = Pattern.compile(
            "(?<![\\w-])(color|background(?:-color)?|border(?:-(?:top|right|bottom|left))?(?:-color)?|outline(?:-color)?"
                    + "|fill|stroke|box-shadow|text-shadow|caret-color|text-decoration-color)\\s*:\\s*([^;{}\"]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COLOR_LITERAL = Pattern.compile(
            "#[0-9a-f]{3,8}\\b|\\b(?:rgba?|hsla?)\\s*\\("
                    + "|\\b(?:white|black|red|green|blue|gray|grey|yellow|orange|purple|pink|brown|silver|navy|maroon|teal|olive|lime|aqua|fuchsia)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    private static final Pattern README_JENKINS_VERSION =
            Pattern.compile("\\bjenkins\\b[^\\n]{0,40}?\\b2\\.\\d{2,3}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern README_JAVA_VERSION = Pattern.compile(
            "\\b(?:java|jdk)\\b\\s*(?:version)?\\s*[:>=]*\\s*(?:8|11|17|21|25)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern README_BUILD_CONTEXT =
            Pattern.compile("\\bbuil[dt]|\\bcompil|\\bmaven\\b|\\bmvn\\b|\\bdevelop", Pattern.CASE_INSENSITIVE);
    private static final Pattern README_MANUAL_INSTALL_LINE = Pattern.compile(
            "\\b(?:upload|deploy)\\s+(?:the\\s+)?plugin\\b|(?:upload|install|copy|\\bcp\\b)[^\\n]*\\.hpi\\b|\\.hpi\\b[^\\n]*(?:upload|install|copy)",
            Pattern.CASE_INSENSITIVE);

    private final HashSet<VerificationMessage> hostingIssues;
    private final RepositoryFiles files;

    public SourceCodeVerifier(HashSet<VerificationMessage> hostingIssues, RepositoryFiles files) {
        this.hostingIssues = hostingIssues;
        this.files = files;
    }

    @Override
    public void verify(HostingRequest request) throws IOException {
        checkStructsDependency(files, hostingIssues);
        checkProxySupport(files, hostingIssues);
        checkJsr305Annotations(files, hostingIssues);
        checkPermissionInFillMethods(files, hostingIssues);
        checkLegacyIcons(files, hostingIssues);
        checkCredentialsJelly(files, hostingIssues);
        checkHardCodedColors(files, hostingIssues);
        checkReadme(files, hostingIssues);
    }

    static void checkStructsDependency(RepositoryFiles files, HashSet<VerificationMessage> hostingIssues) {
        Optional<String> pom = files.get("pom.xml");
        if (pom.isEmpty()) {
            return;
        }
        Model model;
        try {
            model = new MavenXpp3Reader().read(new StringReader(pom.get()));
        } catch (IOException | XmlPullParserException e) {
            // reported by MavenVerifier
            return;
        }
        boolean dependsOnStructs = model.getDependencies().stream()
                .anyMatch(d -> "org.jenkins-ci.plugins".equals(d.getGroupId())
                        && "structs".equals(d.getArtifactId())
                        && !"test".equals(d.getScope()));
        if (!dependsOnStructs) {
            return;
        }
        boolean usesStructs = files.matching(p -> p.startsWith("src/main/") && isSource(p)).values().stream()
                .anyMatch(content -> content.contains("org.jenkinsci.plugins.structs"));
        if (!usesStructs) {
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.WARNING, UNNEEDED_STRUCTS));
        }
    }

    static void checkProxySupport(RepositoryFiles files, HashSet<VerificationMessage> hostingIssues) {
        HashSet<VerificationMessage> findings = new HashSet<>();
        mainJavaSources(files).forEach((path, content) -> {
            String code = blank(stripComments(content), IMPORT);
            if (code.contains("ProxyConfiguration")) {
                return;
            }
            Matcher m = HTTP_CLIENT_CREATION.matcher(code);
            if (m.find()) {
                findings.add(finding(files, path, RepositoryFiles.lineOf(code, m.start())));
            }
        });
        addWithSubItems(hostingIssues, VerificationMessage.Severity.WARNING, NO_PROXY_SUPPORT, findings);
    }

    static void checkJsr305Annotations(RepositoryFiles files, HashSet<VerificationMessage> hostingIssues) {
        HashSet<VerificationMessage> findings = new HashSet<>();
        files.matching(p -> p.startsWith("src/") && p.endsWith(".java")).forEach((path, content) -> {
            Matcher m = JSR305_IMPORT.matcher(content);
            if (m.find()) {
                findings.add(finding(files, path, RepositoryFiles.lineOf(content, m.start())));
            }
        });
        addWithSubItems(hostingIssues, VerificationMessage.Severity.WARNING, JSR305_ANNOTATIONS, findings);
    }

    static void checkPermissionInFillMethods(RepositoryFiles files, HashSet<VerificationMessage> hostingIssues) {
        HashSet<VerificationMessage> findings = new HashSet<>();
        mainJavaSources(files).forEach((path, content) -> {
            String code = stripComments(content);
            Matcher m = FILL_METHOD.matcher(code);
            while (m.find()) {
                String body = methodBody(code, m.end());
                if (body != null && body.contains("checkPermission(")) {
                    findings.add(finding(files, path, RepositoryFiles.lineOf(code, m.start())));
                }
            }
        });
        addWithSubItems(hostingIssues, VerificationMessage.Severity.WARNING, CHECK_PERMISSION_IN_FILL, findings);
    }

    static void checkLegacyIcons(RepositoryFiles files, HashSet<VerificationMessage> hostingIssues) {
        HashSet<VerificationMessage> findings = new HashSet<>();
        mainJavaSources(files).forEach((path, content) -> {
            String code = stripComments(content);
            Matcher m = ICON_METHOD.matcher(code);
            while (m.find()) {
                String body = methodBody(code, m.end());
                if (body != null && IMAGE_LITERAL.matcher(body).find()) {
                    findings.add(finding(files, path, RepositoryFiles.lineOf(code, m.start())));
                }
            }
        });
        addWithSubItems(hostingIssues, VerificationMessage.Severity.WARNING, LEGACY_ICON, findings);
    }

    static void checkCredentialsJelly(RepositoryFiles files, HashSet<VerificationMessage> hostingIssues) {
        HashSet<VerificationMessage> findings = new HashSet<>();
        mainJavaSources(files).forEach((path, content) -> {
            if (!CREDENTIALS_CLASS.matcher(stripComments(content)).find()) {
                return;
            }
            String resources = "src/main/resources/"
                    + path.substring("src/main/java/".length(), path.length() - ".java".length()) + "/";
            if (files.get(resources + "config.jelly").isPresent()
                    && files.get(resources + "credentials.jelly").isEmpty()) {
                findings.add(finding(files, resources + "config.jelly", 0));
            }
        });
        addWithSubItems(hostingIssues, VerificationMessage.Severity.WARNING, CREDENTIALS_CONFIG_JELLY, findings);
    }

    static void checkHardCodedColors(RepositoryFiles files, HashSet<VerificationMessage> hostingIssues) {
        HashSet<VerificationMessage> findings = new HashSet<>();
        files.matching(p -> p.startsWith("src/main/") && p.matches(".*\\.(css|scss|less|jelly)$"))
                .forEach((path, content) -> {
                    int line = path.endsWith(".jelly")
                            ? firstHardCodedColorInJelly(content)
                            : firstHardCodedColor(content);
                    if (line > 0) {
                        findings.add(finding(files, path, line));
                    }
                });
        addWithSubItems(hostingIssues, VerificationMessage.Severity.WARNING, HARD_CODED_COLORS, findings);
    }

    /**
     * @return the line of the first hard coded color in a stylesheet, or 0 if there is none
     */
    static int firstHardCodedColor(String css) {
        String code = blank(css, CSS_COMMENT);
        Matcher m = CSS_DECLARATION.matcher(code);
        while (m.find()) {
            String value = removeVarAndUrl(m.group(2));
            if (COLOR_LITERAL.matcher(value).find()) {
                return RepositoryFiles.lineOf(code, m.start());
            }
        }
        return 0;
    }

    /**
     * Removes {@code var(...)} and {@code url(...)} from a CSS value, as colors in fallbacks of variables and fragment
     * references like {@code url(#gradient)} are fine.
     */
    static String removeVarAndUrl(String value) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < value.length()) {
            String rest = value.substring(i).toLowerCase(Locale.ROOT);
            if (rest.startsWith("var(") || rest.startsWith("url(")) {
                int depth = 0;
                int j = i + 3;
                for (; j < value.length(); j++) {
                    char c = value.charAt(j);
                    if (c == '(') {
                        depth++;
                    } else if (c == ')' && --depth == 0) {
                        break;
                    }
                }
                i = j + 1;
            } else {
                result.append(value.charAt(i));
                i++;
            }
        }
        return result.toString();
    }

    private static final Pattern JELLY_STYLE = Pattern.compile(
            "\\sstyle\\s*=\\s*\"([^\"]*)\"|\\sstyle\\s*=\\s*'([^']*)'|<style[^>]*>(.*?)</style>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    static int firstHardCodedColorInJelly(String jelly) {
        Matcher m = JELLY_STYLE.matcher(jelly);
        while (m.find()) {
            int group = m.group(1) != null ? 1 : m.group(2) != null ? 2 : 3;
            int line = firstHardCodedColor(m.group(group));
            if (line > 0) {
                return RepositoryFiles.lineOf(jelly, m.start(group)) + line - 1;
            }
        }
        return 0;
    }

    static void checkReadme(RepositoryFiles files, HashSet<VerificationMessage> hostingIssues) {
        HashSet<VerificationMessage> requirements = new HashSet<>();
        HashSet<VerificationMessage> install = new HashSet<>();
        files.matching(p -> p.matches("(?i)README\\.(md|adoc)")).forEach((path, content) -> {
            String[] lines = content.split("\n", -1);
            String fence = path.toLowerCase().endsWith(".adoc") ? "----" : "```";
            boolean inCodeBlock = false;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line.trim().startsWith(fence)) {
                    inCodeBlock = !inCodeBlock;
                    continue;
                }
                if (README_MANUAL_INSTALL_LINE.matcher(line).find()) {
                    install.add(finding(files, path, i + 1));
                }
                if (!inCodeBlock
                        && !line.contains("jenkins.version")
                        && (README_JENKINS_VERSION.matcher(line).find()
                                || (README_JAVA_VERSION.matcher(line).find()
                                        && !README_BUILD_CONTEXT.matcher(line).find()))) {
                    requirements.add(finding(files, path, i + 1));
                }
            }
        });
        addWithSubItems(hostingIssues, VerificationMessage.Severity.INFO, README_REQUIREMENTS, requirements);
        addWithSubItems(hostingIssues, VerificationMessage.Severity.INFO, README_MANUAL_INSTALL, install);
    }

    private static Map<String, String> mainJavaSources(RepositoryFiles files) {
        return files.matching(p -> p.startsWith("src/main/java/") && p.endsWith(".java"));
    }

    private static boolean isSource(String path) {
        return path.endsWith(".java") || path.endsWith(".groovy") || path.endsWith(".kt");
    }

    private static VerificationMessage finding(RepositoryFiles files, String path, int line) {
        return new VerificationMessage(VerificationMessage.Severity.INFO, "%s", files.link(path, line));
    }

    private static void addWithSubItems(
            HashSet<VerificationMessage> hostingIssues,
            VerificationMessage.Severity severity,
            String message,
            HashSet<VerificationMessage> findings) {
        if (!findings.isEmpty()) {
            hostingIssues.add(new VerificationMessage(severity, findings, "%s", message));
        }
    }

    /**
     * @param from an offset after the method name, before the opening brace of the method body
     * @return the method body, or {@code null} if there is none (e.g. for an abstract method)
     */
    static String methodBody(String code, int from) {
        int start = -1;
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == ';') {
                return null;
            }
            if (c == '{') {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        int depth = 0;
        for (int i = start; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(start, i + 1);
            }
        }
        return code.substring(start);
    }

    private static String blank(String content, Pattern pattern) {
        StringBuilder result = new StringBuilder(content);
        Matcher m = pattern.matcher(content);
        while (m.find()) {
            for (int i = m.start(); i < m.end(); i++) {
                if (result.charAt(i) != '\n') {
                    result.setCharAt(i, ' ');
                }
            }
        }
        return result.toString();
    }

    /**
     * Replaces comments in Java code with spaces, keeping line breaks so offsets and line numbers stay the same.
     */
    static String stripComments(String code) {
        StringBuilder result = new StringBuilder(code);
        List<int[]> comments = new ArrayList<>();
        int i = 0;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (code.startsWith("\"\"\"", i)) {
                int end = code.indexOf("\"\"\"", i + 3);
                i = end < 0 ? code.length() : end + 3;
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < code.length() && code.charAt(i) != c && code.charAt(i) != '\n') {
                    i += code.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
            } else if (code.startsWith("//", i)) {
                int end = code.indexOf('\n', i);
                end = end < 0 ? code.length() : end;
                comments.add(new int[] {i, end});
                i = end;
            } else if (code.startsWith("/*", i)) {
                int end = code.indexOf("*/", i + 2);
                end = end < 0 ? code.length() : end + 2;
                comments.add(new int[] {i, end});
                i = end;
            } else {
                i++;
            }
        }
        for (int[] comment : comments) {
            for (int j = comment[0]; j < comment[1]; j++) {
                if (result.charAt(j) != '\n') {
                    result.setCharAt(j, ' ');
                }
            }
        }
        return result.toString();
    }
}
