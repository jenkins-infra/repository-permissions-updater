package io.jenkins.infra.repository_permissions_updater.hosting;

import static io.jenkins.infra.repository_permissions_updater.hosting.Requirements.LOWEST_JENKINS_VERSION;
import static java.util.regex.Pattern.CASE_INSENSITIVE;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.builder.AstBuilder;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilePhase;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

/**
 * @deprecated until the Gradle JPI plugin supports the same scope of features as the Maven HPI plugin.
 */
@Deprecated
public class GradleVerifier extends CodeVisitorSupport implements BuildSystemVerifier {

    public static final String SPECIFY_LICENSE =
            "Please specify a license in your build.gradle file using the `licenses` closure. See https://github.com/jenkinsci/gradle-jpi-plugin#configuration for an example.";
    public static final String MISSING_JENKINS_VERSION = "Your build.gradle file is missing the `jenkinsVersion` item.";
    public static final String MISSING_SHORTNAME = "Your build.gradle file is missing the `shortName` item.";
    public static final String MISSING_DISPLAYNAME = "Your build.gradle file is missing the `displayName` item.";
    public static final String MISSING_GROUP =
            "Your build.gradle file is missing the `group` item. Please add one with the value `io.jenkins.plugins`";
    public static final String MISSING_GROUP_VALUE =
            "Your build.gradle file has a `group` but the value is empty. Please set the value to `io.jenkins.plugins` or a company/group specific `group`";
    public static final String JPI_PLUGIN_VERSION_TOO_LOW =
            "The version of the gradle jpi plugin in your build.gradle (%s) is to low, please update to at least (%s)";
    public static final String CANNOT_VERIFY_GROUP =
            "The `group` value from your build.gradle seems to be a variable, we cannot verify the `group` value in this case, please make sure the group is `io.jenkins.plugins` or a company/group specific `group`";
    public static final String SHOULD_BE_IO_JENKINS_PLUGINS =
            "The `group` from the build.gradle should be `io.jenkins.plugins` instead of `org.jenkins-ci.plugins`";
    public static final String NO_BUILD_GRADLE_FOUND =
            "No build.gradle found in root of project, if you are using a different build system, or this is not a plugin, you can disregard this message";
    public static final String INVALID_BUILD_GRADLE =
            "Your build.gradle file did not parse correctly, please verify the content.";
    public static final String INCORRECT_TARGET_COMPAT_VERSION =
            "Only targetCompatibility=1.8 is supported for Jenkins plugins.";
    public static final String CANNOT_VERIFY_TARGET_COMPAT =
            "The `targetCompatibility` value from your build.gradle seems to be a variable, we cannot verify the `targetCompatibility` in this case, please make sure it is set to `1.8`";
    public static final String CANNOT_VERIFY_JENKINS_VERSION =
            "The `jenkinsVersion` value from your build.gradle seems to be a variable, we cannot verify the `jenkinsVersion` in this case, please make sure it is set to at least `%s`";

    public static final Version LOWEST_JPI_PLUGIN_VERSION = new Version(0, 47);
    public static final Version JAVA_COMPATIBILITY_VERSION = new Version(1, 8, 0);

    private boolean hasJenkinsVersion = false;
    private boolean hasShortName = false;
    private boolean hasDisplayName = false;
    private boolean hasGroup = false;
    private boolean hasLicense = false;

    private String forkTo;
    private String forkFrom;

    @Override
    public void verify(HostingRequest issue, HashSet<VerificationMessage> hostingIssues) throws IOException {
        GitHub github = GitHub.connect();
        forkFrom = issue.getRepositoryUrl();
        forkTo = issue.getNewRepoName();

        if (StringUtils.isNotBlank(forkFrom)) {
            Matcher m = Pattern.compile("(?:https://github\\.com/)?(\\S+)/(\\S+)", CASE_INSENSITIVE)
                    .matcher(forkFrom);
            if (m.matches()) {
                String owner = m.group(1);
                String repoName = m.group(2);

                GHRepository repo = github.getRepository(owner + "/" + repoName);
                try {
                    GHContent buildGradle = repo.getFileContent("build.gradle");
                    if (buildGradle != null) {
                        InputStream input = buildGradle.read();

                        AstBuilder astBuilder = new AstBuilder();
                        List<ASTNode> nodes = astBuilder.buildFromString(
                                CompilePhase.SEMANTIC_ANALYSIS,
                                false,
                                IOUtils.toString(input, Charset.defaultCharset()));

                        BlockStatement node = (BlockStatement) nodes.getFirst();
                        for (Statement s : node.getStatements()) {
                            if (s instanceof ExpressionStatement statement) {
                                Expression e = statement.getExpression();
                                if (e instanceof MethodCallExpression mc) {
                                    if (mc.getMethodAsString().equals("plugins")) {
                                        // make sure we get the correct version of the gradle jpi plugin
                                        checkPluginVersion(
                                                ((ArgumentListExpression) mc.getArguments()).getExpression(0),
                                                hostingIssues);
                                    } else if (mc.getMethodAsString().equals("repositories")) {
                                        // verify that any references to repo.jenkins-ci.org are correct
                                        checkRepositories(
                                                ((ArgumentListExpression) mc.getArguments()).getExpression(0),
                                                hostingIssues);
                                    } else if (mc.getMethodAsString().equals("jenkinsPlugin")) {
                                        // verify the things that will make it into the pom.xml that is published
                                        checkJenkinsPlugin(
                                                ((ArgumentListExpression) mc.getArguments()).getExpression(0),
                                                hostingIssues);
                                    }
                                } else if (e instanceof BinaryExpression be) {
                                    VariableExpression v = (VariableExpression) be.getLeftExpression();
                                    if (v.getName().equals("group")) {
                                        checkGroup(be.getRightExpression(), hostingIssues);
                                    } else if (v.getName().equals("targetCompatibility")) {
                                        checkTargetCompatibility(be.getRightExpression(), hostingIssues);
                                    }
                                }
                            }
                            // There are other possibilities here, but we currently don't take them into account
                        }

                        if (!hasJenkinsVersion) {
                            hostingIssues.add(new VerificationMessage(
                                    VerificationMessage.Severity.REQUIRED, MISSING_JENKINS_VERSION));
                        }

                        if (!hasShortName) {
                            hostingIssues.add(
                                    new VerificationMessage(VerificationMessage.Severity.REQUIRED, MISSING_SHORTNAME));
                        }

                        if (!hasDisplayName) {
                            hostingIssues.add(new VerificationMessage(
                                    VerificationMessage.Severity.REQUIRED, MISSING_DISPLAYNAME));
                        }

                        if (!hasGroup) {
                            hostingIssues.add(
                                    new VerificationMessage(VerificationMessage.Severity.REQUIRED, MISSING_GROUP));
                        }

                        if (!hasLicense) {
                            hostingIssues.add(
                                    new VerificationMessage(VerificationMessage.Severity.REQUIRED, SPECIFY_LICENSE));
                        }
                    } else {
                        hostingIssues.add(
                                new VerificationMessage(VerificationMessage.Severity.WARNING, NO_BUILD_GRADLE_FOUND));
                    }
                } catch (GHFileNotFoundException e) {
                    hostingIssues.add(
                            new VerificationMessage(VerificationMessage.Severity.WARNING, NO_BUILD_GRADLE_FOUND));
                } catch (CompilationFailedException e) {
                    hostingIssues.add(
                            new VerificationMessage(VerificationMessage.Severity.REQUIRED, INVALID_BUILD_GRADLE));
                }
            } else {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED, HostingChecker.INVALID_FORK_FROM, forkFrom));
            }
        }
    }

    @Override
    public boolean hasBuildFile(HostingRequest issue) throws IOException {
        return HostingChecker.fileExistsInRepo(issue, "build.gradle");
    }

    public static String getShortName(String contents) {
        String res = null;

        AstBuilder astBuilder = new AstBuilder();
        List<ASTNode> nodes = astBuilder.buildFromString(CompilePhase.SEMANTIC_ANALYSIS, false, contents);
        boolean isDone = false;

        BlockStatement node = (BlockStatement) nodes.getFirst();
        for (Statement s : node.getStatements()) {
            Expression e = ((ExpressionStatement) s).getExpression();
            if (e instanceof MethodCallExpression mc) {
                if (mc.getMethodAsString().equals("jenkinsPlugin")) {
                    Expression jenkinsPlugin = ((ArgumentListExpression) mc.getArguments()).getExpression(0);
                    if (jenkinsPlugin instanceof ClosureExpression c) {
                        for (Statement st : ((BlockStatement) c.getCode()).getStatements()) {
                            ExpressionStatement sm = (ExpressionStatement) st;
                            if (sm.getExpression() instanceof BinaryExpression) {
                                BinaryExpression be = (BinaryExpression) sm.getExpression();
                                if (be.getLeftExpression().getText().equals("shortName")) {
                                    if (be.getRightExpression() instanceof ConstantExpression) {
                                        res = be.getRightExpression().getText();
                                        isDone = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (isDone) {
                break;
            }
        }
        return res;
    }

    public static String getGroupId(String contents) {
        String res = null;
        AstBuilder astBuilder = new AstBuilder();
        List<ASTNode> nodes = astBuilder.buildFromString(CompilePhase.SEMANTIC_ANALYSIS, false, contents);

        BlockStatement node = (BlockStatement) nodes.getFirst();
        for (Statement s : node.getStatements()) {
            Expression e = ((ExpressionStatement) s).getExpression();
            if (e instanceof BinaryExpression be) {
                VariableExpression v = (VariableExpression) be.getLeftExpression();
                if (v.getName().equals("group")) {
                    if (be.getRightExpression() instanceof ConstantExpression) {
                        res = be.getRightExpression().getText();
                        break;
                    }
                }
            }
        }
        return res;
    }

    private void checkPluginVersion(Expression plugins, HashSet<VerificationMessage> hostingIssues) {
        if (plugins instanceof ClosureExpression c) {
            for (Statement st : ((BlockStatement) c.getCode()).getStatements()) {
                Expression e = ((ExpressionStatement) st).getExpression();
                if (e instanceof MethodCallExpression mc) {
                    // if no version if there for the jpi plugin, the latest will be used
                    if (mc.getMethodAsString().equals("version")) {
                        e = ((ArgumentListExpression) mc.getArguments()).getExpression(0);
                        if (e instanceof ConstantExpression expression
                                && mc.getObjectExpression() instanceof MethodCallExpression) {
                            if (((MethodCallExpression) mc.getObjectExpression())
                                    .getMethodAsString()
                                    .equals("id")) {
                                String pluginId = ((ConstantExpression)
                                                ((ArgumentListExpression) mc.getArguments()).getExpression(0))
                                        .getValue()
                                        .toString();
                                if (pluginId.equals("org.jenkins-ci.jpi")) {
                                    Version jpiPluginVersion =
                                            new Version(expression.getValue().toString());
                                    if (jpiPluginVersion.compareTo(LOWEST_JPI_PLUGIN_VERSION) < 0) {
                                        hostingIssues.add(new VerificationMessage(
                                                VerificationMessage.Severity.REQUIRED,
                                                JPI_PLUGIN_VERSION_TOO_LOW,
                                                jpiPluginVersion,
                                                LOWEST_JPI_PLUGIN_VERSION));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void checkRepositories(Expression repositories, HashSet<VerificationMessage> hostingIssues) {
        if (repositories instanceof ClosureExpression c) {
            for (Statement st : ((BlockStatement) c.getCode()).getStatements()) {
                Expression e = ((ExpressionStatement) st).getExpression();
                if (e instanceof MethodCallExpression expression
                        && expression.getMethodAsString().equals("maven")) {
                    c = (ClosureExpression) ((ArgumentListExpression) expression.getArguments()).getExpression(0);
                    for (Statement s : ((BlockStatement) c.getCode()).getStatements()) {
                        e = ((ExpressionStatement) s).getExpression();
                        if (e instanceof MethodCallExpression callExpression
                                && callExpression.getMethodAsString().equals("url")) {
                            e = ((ArgumentListExpression) callExpression.getArguments()).getExpression(0);
                            if (e instanceof ConstantExpression) {
                                String url = e.getText();
                                if (url.contains("repo.jenkins-ci.org")) {
                                    try {
                                        URI uri = new URI(url);
                                        if (!uri.getScheme().equals("https")) {
                                            hostingIssues.add(
                                                    new VerificationMessage(
                                                            VerificationMessage.Severity.REQUIRED,
                                                            "You MUST use an https:// scheme in your build.gradle for the `repositories` block for repo.jenkins-ci.org urls."));
                                        }
                                    } catch (URISyntaxException ex) {
                                        hostingIssues.add(
                                                new VerificationMessage(
                                                        VerificationMessage.Severity.REQUIRED,
                                                        "The `repositories` block in your build.gradle for 'repo.jenkins-ci.org' has an invalid URL"));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void checkJenkinsPlugin(Expression jenkinsPlugin, HashSet<VerificationMessage> hostingIssues) {
        if (jenkinsPlugin instanceof ClosureExpression c) {
            for (Statement st : ((BlockStatement) c.getCode()).getStatements()) {
                ExpressionStatement s = (ExpressionStatement) st;
                if (s.getExpression() instanceof BinaryExpression) {
                    BinaryExpression be = (BinaryExpression) s.getExpression();
                    if (be.getLeftExpression().getText().equals("coreVersion")) {
                        hostingIssues.add(new VerificationMessage(
                                VerificationMessage.Severity.REQUIRED,
                                "`coreVersion` is deprecated, please use `jenkinsVersion`"));
                    } else if (be.getLeftExpression().getText().equals("jenkinsVersion")) {
                        checkJenkinsVersion(be.getRightExpression(), hostingIssues);
                    } else if (be.getLeftExpression().getText().equals("shortName")) {
                        checkShortName(be.getRightExpression(), hostingIssues);
                    } else if (be.getLeftExpression().getText().equals("displayName")) {
                        checkDisplayName(be.getRightExpression(), hostingIssues);
                    }
                } else if (s.getExpression() instanceof MethodCallExpression) {
                    MethodCallExpression mc = (MethodCallExpression) s.getExpression();
                    if (mc.getMethodAsString().equals("licenses")) {
                        checkLicenses(((ArgumentListExpression) mc.getArguments()).getExpression(0), hostingIssues);
                    }
                }
            }
        }
    }

    private void checkGroup(Expression e, HashSet<VerificationMessage> hostingIssues) {
        if (e instanceof ConstantExpression) {
            String groupId = e.getText();
            if (StringUtils.isBlank(groupId)) {
                hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, MISSING_GROUP_VALUE));
            } else {
                hasGroup = true;
                if (groupId.equals("org.jenkins-ci.plugins")) {
                    hostingIssues.add(new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED, SHOULD_BE_IO_JENKINS_PLUGINS));
                }
            }
        } else {
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.INFO, CANNOT_VERIFY_GROUP));
        }
    }

    private void checkTargetCompatibility(Expression e, HashSet<VerificationMessage> hostingIssues) {
        if (e instanceof ConstantExpression) {
            Version targetCompatVersion = new Version(e.getText());
            if (targetCompatVersion.compareTo(JAVA_COMPATIBILITY_VERSION) != 0) {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED, INCORRECT_TARGET_COMPAT_VERSION));
            }
        } else {
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.INFO, CANNOT_VERIFY_TARGET_COMPAT));
        }
    }

    private void checkLicenses(Expression e, HashSet<VerificationMessage> hostingIssues) {
        if (e instanceof ClosureExpression c) {
            for (Statement st : ((BlockStatement) c.getCode()).getStatements()) {
                e = ((ExpressionStatement) st).getExpression();
                if (e instanceof MethodCallExpression expression
                        && expression.getMethodAsString().equals("license")) {
                    hasLicense = true;
                    //                    c =
                    // (ClosureExpression)((ArgumentListExpression)((MethodCallExpression)e).getArguments()).getExpression(0);
                    //                    for(Statement s : ((BlockStatement)c.getCode()).getStatements()) {
                    //                        e = ((ExpressionStatement)s).getExpression();
                    //                        if(e instanceof MethodCallExpression) {
                    //                            String methodName = ((MethodCallExpression)e).getMethodAsString();
                    //                            if(methodName.equals("name")) {
                    //
                    //                            } else if(methodName.equals("url")) {
                    //                                // would be nice if we could check the url
                    //                            }
                    //                        }
                    //                    }
                    //                    System.out.println(e.getText());
                }
            }
        }
    }

    private void checkJenkinsVersion(Expression e, HashSet<VerificationMessage> hostingIssues) {
        if (e instanceof ConstantExpression) {
            Version jenkinsVersion = new Version(e.getText());
            if (jenkinsVersion.compareTo(LOWEST_JENKINS_VERSION) < 0) {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED,
                        "The `jenkinsVersion` value in your build.gradle does not meet the minimum Jenkins version required, please update your jenkinsVersion to at least %s. Take a look at the [baseline recommendations](https://www.jenkins.io/doc/developer/plugin-development/choosing-jenkins-baseline/#currently-recommended-versions)."
                                .formatted(jenkinsVersion, LOWEST_JENKINS_VERSION)));
            }
            hasJenkinsVersion = true;
        } else {
            hostingIssues.add(new VerificationMessage(
                    VerificationMessage.Severity.INFO, CANNOT_VERIFY_JENKINS_VERSION, LOWEST_JENKINS_VERSION));
        }
    }

    private void checkShortName(Expression e, HashSet<VerificationMessage> hostingIssues) {
        if (e instanceof ConstantExpression) {
            String shortName = e.getText();
            if (StringUtils.isNotBlank(shortName)) {
                if (StringUtils.isNotBlank(forkTo) && !shortName.equals(forkTo.replace("-plugin", ""))) {
                    hostingIssues.add(new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "The `shortName` from the build.gradle (%s) is incorrect, it should be '%s' ('New Repository Name' field with \"-plugin\" removed)",
                            shortName,
                            (forkTo.replace("-plugin", "")).toLowerCase()));
                }

                if (shortName.toLowerCase().contains("jenkins")) {
                    hostingIssues.add(new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "The `shortName` from the build.gradle (%s) should not contain \"Jenkins\"",
                            shortName));
                }

                if (!shortName.toLowerCase().equals(shortName)) {
                    hostingIssues.add(new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "The `shortName`` from the build.gradle (%s) should be all lower case",
                            shortName));
                }
                hasShortName = true;
            } else {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED,
                        "The build.gradle file does not contain a valid `shortName` for the project"));
            }
        }
    }

    private void checkDisplayName(Expression e, HashSet<VerificationMessage> hostingIssues) {
        if (e instanceof ConstantExpression) {
            String name = e.getText();
            if (StringUtils.isNotBlank(name)) {
                if (name.toLowerCase().contains("jenkins")) {
                    hostingIssues.add(new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "The `displayName` in the build.gradle should not contain \"Jenkins\""));
                }
                hasDisplayName = true;
            } else {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED,
                        "The build.gradle file does not contain a valid `displayName` for the project"));
            }
        }
    }
}
