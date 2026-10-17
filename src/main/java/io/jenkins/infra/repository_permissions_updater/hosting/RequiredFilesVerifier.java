package io.jenkins.infra.repository_permissions_updater.hosting;

import static io.jenkins.infra.repository_permissions_updater.hosting.Requirements.ALLOWED_JDK_VERSIONS;

import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyRuntimeException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;

public class RequiredFilesVerifier implements Verifier {

    private final HashSet<VerificationMessage> hostingIssues;
    private final RepositoryCheckout checkout;

    public RequiredFilesVerifier(HashSet<VerificationMessage> hostingIssues, RepositoryCheckout checkout) {
        this.hostingIssues = hostingIssues;
        this.checkout = checkout;
    }

    @Override
    public void verify(HostingRequest request) throws IOException {
        checkJenkinsfile();
        checkSecurityScan();
        checkCodeOwners(request.getNewRepoName());
        checkGitignore();
        checkDependencyBot();
        if (request.isEnableCD()) {
            checkFilesForCD();
        }
    }

    private void checkCodeOwners(String forkTo) throws IOException {
        String expected = "* @jenkinsci/" + forkTo + "-developers";
        Optional<String> file = checkout.read(".github/CODEOWNERS");
        if (file.isEmpty()) {
            hostingIssues.add(new VerificationMessage(
                    VerificationMessage.Severity.REQUIRED,
                    "Missing file `.github/CODEOWNERS`. Please add this file containing the line: `" + expected + "`"));
            return;
        }
        if (file.get().lines().noneMatch(line -> line.equals(expected))) {
            hostingIssues.add(new VerificationMessage(
                    VerificationMessage.Severity.REQUIRED,
                    "The file `.github/CODEOWNERS` doesn't contain the expected line `" + expected + "`"));
        }
    }

    private void checkJenkinsfile() throws IOException {
        Optional<String> file = checkout.read("Jenkinsfile");
        if (file.isEmpty()) {
            hostingIssues.add(
                    new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "Missing file `Jenkinsfile`. Please add a Jenkinsfile to your repo so it can be built on ci.jenkins.io. A suitable version can be downloaded [here](https://github.com/jenkinsci/archetypes/blob/master/common-files/Jenkinsfile)"));
            return;
        }
        validateJenkinsFile(file.get());
    }

    private void checkGitignore() throws IOException {
        Optional<String> file = checkout.read(".gitignore");
        if (file.isEmpty()) {
            hostingIssues.add(
                    new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "Missing file `.gitignore`. Please add a `.gitignore` to help keep your git repo lean. A suitable version can be downloaded [here](https://github.com/jenkinsci/archetypes/blob/master/common-files/gitignore)"));
            return;
        }
        List<String> lines = file.get().lines().toList();
        if (lines.stream()
                .noneMatch(line -> line.equals("target") || line.equals("target/") || line.equals("/target/"))) {
            hostingIssues.add(
                    new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "The file `.gitignore` doesn't exclude `target`. Please add a line so that you don't check-in this directory by accident."));
        }
        if (lines.stream().noneMatch(line -> line.equals("work") || line.equals("work/") || line.equals("/work/"))) {
            hostingIssues.add(
                    new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "The file `.gitignore` doesn't exclude `work`. Please add a line so that you don't check-in this directory by accident."));
        }
    }

    private void checkSecurityScan() {
        if (fileNotExistsInRepo(".github/workflows/jenkins-security-scan.yml")
                && fileNotExistsInRepo(".github/workflows/jenkins-security-scan.yaml")) {
            hostingIssues.add(
                    new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "Missing file `.github/workflows/jenkins-security-scan.yml`. This file helps to keep your plugin conform to security standards defined by the Jenkins project."
                                    + " A suitable version can be downloaded [here](https://github.com/jenkinsci/archetypes/blob/master/common-files/.github/workflows/jenkins-security-scan.yml)"));
        }
    }

    private void checkDependencyBot() {
        if (fileNotExistsInRepo(".github/dependabot.yml")
                && fileNotExistsInRepo(".github/dependabot.yaml")
                && fileNotExistsInRepo("renovate.json")
                && fileNotExistsInRepo(".github/renovate.json")
                && fileNotExistsInRepo(".github/workflows/updatecli.yml")
                && fileNotExistsInRepo(".github/workflows/updatecli.yaml")) {
            hostingIssues.add(
                    new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "No files found related to automatically updating the plugin dependencies. "
                                    + "Please ensure that you have dependabot, renovate or updatecli configured in the repo. "
                                    + "A suitable version for renovate can be downloaded [here](https://github.com/jenkinsci/archetypes/blob/master/common-files/.github/renovate.json)"));
        }
    }

    private void checkFilesForCD() throws IOException {

        if (fileNotExistsInRepo(".mvn/extensions.xml")) {
            hostingIssues.add(
                    new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "Missing file `.mvn/extensions.xml`. This file is required when CD is enabled. "
                                    + "A suitable version can be downloaded [here](https://raw.githubusercontent.com/jenkinsci/archetypes/refs/heads/master/common-files/.mvn/extensions.xml)"));
        }
        if (fileNotExistsInRepo(".github/workflows/cd.yaml") && fileNotExistsInRepo(".github/workflows/cd.yml")) {
            hostingIssues.add(
                    new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "Missing file `.github/workflows/cd.yaml`. This file is required when CD is enabled. "
                                    + "A suitable version can be downloaded [here](https://raw.githubusercontent.com/jenkinsci/.github/master/workflow-templates/cd.yaml"));
        }
        if (!fileNotExistsInRepo(".github/release-drafter.yml")
                || !fileNotExistsInRepo(".github/release-drafter.yaml")) {
            hostingIssues.add(new VerificationMessage(
                    VerificationMessage.Severity.REQUIRED,
                    "The file `.github/release-drafter.y*ml` should be removed when CD is enabled."));
        }
        if (!fileNotExistsInRepo(".github/workflows/release-drafter.yml")
                || !fileNotExistsInRepo(".github/workflows/release-drafter.yaml")) {
            hostingIssues.add(new VerificationMessage(
                    VerificationMessage.Severity.REQUIRED,
                    "The file `.github/workflows/release-drafter.y*ml` should be removed when CD is enabled."));
        }
        String expected = "-Dchangelist.format=%d.v%s";
        Optional<String> file = checkout.read(".mvn/maven.config");
        if (file.isPresent()) {
            if (file.get().lines().noneMatch(line -> line.equals(expected))) {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED,
                        "The file `.mvn/maven.config` doesn't contain the expected line `"
                                + expected.replaceAll("%", "%%") + "`"));
            }
        } else {
            hostingIssues.add(new VerificationMessage(
                    VerificationMessage.Severity.REQUIRED,
                    "Missing file `.mvn/maven.config`. This file is required when CD is enabled. "
                            + "Download [maven.config](https://raw.githubusercontent.com/jenkinsci/archetypes/refs/heads/master/common-files/.mvn/maven.config) "
                            + "and add the line: `" + expected.replaceAll("%", "%%") + "`"));
        }
    }

    @SuppressWarnings("unchecked")
    public void validateJenkinsFile(String script) {
        try {
            GroovyClassLoader loader = new GroovyClassLoader();
            CompilerConfiguration config = new CompilerConfiguration();
            CompilationUnit unit = new CompilationUnit(config, null, loader);

            unit.addSource("Script.groovy", script);
            unit.compile(Phases.SEMANTIC_ANALYSIS);

            List<ClassNode> classes = unit.getAST().getClasses();
            if (classes.isEmpty()) {
                hostingIssues.add(
                        new VerificationMessage(VerificationMessage.Severity.REQUIRED, "Could not parse Jenkinsfile."));
                return;
            }

            ClassNode scriptClass =
                    classes.stream().filter(ClassNode::isScript).findFirst().orElse(classes.getFirst());

            MethodNode runMethod = scriptClass.getDeclaredMethod("run", new Parameter[0]);
            if (runMethod == null) {
                hostingIssues.add(
                        new VerificationMessage(VerificationMessage.Severity.REQUIRED, "Could not parse Jenkinsfile."));
                return;
            }

            Statement code = runMethod.getCode();
            if (!(code instanceof BlockStatement blockCode)) {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED,
                        "The call of `buildPlugin` should be the only statement in the Jenkinsfile."));
                return;
            }

            List<Statement> statements = blockCode.getStatements().stream()
                    .filter(s -> s instanceof ExpressionStatement)
                    .filter(es -> ((ExpressionStatement) es).getExpression() instanceof MethodCallExpression)
                    .toList();

            if (statements.size() != 1) {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED,
                        "The call of `buildPlugin` should be the only method call in the Jenkinsfile."));
                return;
            }

            Statement stmt = statements.getFirst();
            if (!(stmt instanceof ExpressionStatement es)) {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED,
                        "The call of `buildPlugin` should be the only method call in the Jenkinsfile."));
                return;
            }

            if (!(es.getExpression() instanceof MethodCallExpression)) {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED,
                        "The call of `buildPlugin` should be the only statement in the Jenkinsfile."));
            }

            MethodCallExpression call = (MethodCallExpression) es.getExpression();

            String name = call.getMethodAsString();
            if (!"buildPlugin".equals(name)) {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED,
                        "Expected a call of `buildPlugin` but found a call of `%s` in the Jenkinsfile.",
                        name));
                return;
            }

            if (!(call.getArguments() instanceof TupleExpression)) {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED,
                        "The call of `buildPlugin` should be the only statement in the Jenkinsfile."));
            }

            TupleExpression tuple = (TupleExpression) call.getArguments();
            List<Expression> args = tuple.getExpressions();

            if (args.size() != 1) {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED,
                        "The call of `buildPlugin` in the Jenkinsfile must be parameterized."));
                return;
            }

            if (!(args.getFirst() instanceof MapExpression)) {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED,
                        "The call of `buildPlugin` in the Jenkinsfile must be parameterized."));
                return;
            }

            Map<String, Expression> declaredVariables = new HashMap<>();
            for (Statement st : blockCode.getStatements()) {
                if (st instanceof ExpressionStatement s) {
                    if (s.getExpression() instanceof DeclarationExpression de) {
                        VariableExpression var = (VariableExpression) de.getLeftExpression();
                        declaredVariables.put(var.getName(), de.getRightExpression());
                    }
                }
            }
            Map<String, Object> argMap = (Map<String, Object>) toJavaValue(args.getFirst(), declaredVariables);

            if (!argMap.containsKey("configurations")) {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED,
                        "The call of `buildPlugin` must contain a parameter `configurations`."));
                return;
            }
            Object configurationsObj = argMap.get("configurations");
            if (!(configurationsObj instanceof List)) {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED,
                        "The parameter `configurations` of method `buildPlugin` must be a list in the Jenkinsfile."));
                return;
            }

            List<Map<String, Object>> configurations = (List<Map<String, Object>>) configurationsObj;
            for (Map<String, Object> pluginConfig : configurations) {
                if (!pluginConfig.containsKey("platform")) {
                    hostingIssues.add(
                            new VerificationMessage(
                                    VerificationMessage.Severity.REQUIRED,
                                    "Missing value for `platform` in the `configuration` parameter of `buildPlugin` call in the Jenkinsfile."));
                    return;
                }
                if (!pluginConfig.containsKey("jdk")) {
                    hostingIssues.add(
                            new VerificationMessage(
                                    VerificationMessage.Severity.REQUIRED,
                                    "Missing value for `jdk` in the `configuration` parameter of `buildPlugin` call in the Jenkinsfile."));
                    return;
                }
                Object jdkObj = pluginConfig.get("jdk");
                if (!(jdkObj instanceof Integer)) {
                    hostingIssues.add(new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "`jdk` must be an integer in the `buildPlugin` call in the Jenkinsfile."));
                    return;
                }
                if (!ALLOWED_JDK_VERSIONS.contains(jdkObj)) {
                    hostingIssues.add(new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "Invalid version `%d` for `jdk`. `jdk` must be one of %s in the `buildPlugin` call in the Jenkinsfile.",
                            jdkObj,
                            ALLOWED_JDK_VERSIONS));
                    return;
                }
            }
            Integer newestJdk = ALLOWED_JDK_VERSIONS.getLast();
            if (configurations.stream().noneMatch(c -> newestJdk.equals(c.get("jdk")))) {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.WARNING,
                        "The `buildPlugin` call in the Jenkinsfile should build with the newest supported Java version `%d`, e.g. `[platform: 'linux', jdk: %d]`.",
                        newestJdk,
                        newestJdk));
            }

        } catch (GroovyRuntimeException e) {
            hostingIssues.add(
                    new VerificationMessage(VerificationMessage.Severity.REQUIRED, "Could not parse Jenkinsfile."));
        }
    }

    private static Object toJavaValue(Expression expr, Map<String, Expression> declaredVariables) {
        if (expr instanceof ConstantExpression) {
            return ((ConstantExpression) expr).getValue();
        }
        if (expr instanceof ListExpression) {
            List<Object> list = new ArrayList<>();
            for (Expression e : ((ListExpression) expr).getExpressions()) {
                list.add(toJavaValue(e, declaredVariables));
            }
            return list;
        }
        if (expr instanceof MapExpression) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (MapEntryExpression entry : ((MapExpression) expr).getMapEntryExpressions()) {
                Object key = toJavaValue(entry.getKeyExpression(), declaredVariables);
                Object value = toJavaValue(entry.getValueExpression(), declaredVariables);
                map.put(String.valueOf(key), value);
            }
            return map;
        }
        if (expr instanceof VariableExpression) {
            String name = ((VariableExpression) expr).getName();
            Expression initializer = declaredVariables.get(name);
            if (initializer == null) {
                throw new RuntimeException("Unknown variable: " + name);
            }
            return toJavaValue(initializer, declaredVariables);
        }
        throw new RuntimeException(
                "Unsupported expression type: " + expr.getClass().getSimpleName());
    }

    private boolean fileNotExistsInRepo(String fileName) {
        return !checkout.isFile(fileName);
    }
}
