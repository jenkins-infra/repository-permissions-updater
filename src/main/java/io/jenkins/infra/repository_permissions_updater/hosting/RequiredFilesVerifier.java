package io.jenkins.infra.repository_permissions_updater.hosting;

import static io.jenkins.infra.repository_permissions_updater.hosting.Requirements.ALLOWED_JDK_VERSIONS;
import static java.util.regex.Pattern.CASE_INSENSITIVE;

import groovy.lang.GroovyClassLoader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
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
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

public class RequiredFilesVerifier implements Verifier {

    @Override
    public void verify(HostingRequest request, HashSet<VerificationMessage> hostingIssues) throws IOException {

        GitHub github = GitHub.connect();
        String forkFrom = request.getRepositoryUrl();
        String forkTo = request.getNewRepoName();

        if (StringUtils.isNotBlank(forkFrom)) {
            Matcher m = Pattern.compile("(?:https://github\\.com/)?(\\S+)/(\\S+)", CASE_INSENSITIVE)
                    .matcher(forkFrom);
            if (m.matches()) {
                String owner = m.group(1);
                String repoName = m.group(2);

                GHRepository repo = github.getRepository(owner + "/" + repoName);
                checkJenkinsfile(repo, hostingIssues);
                checkSecurityScan(repo, hostingIssues);
                checkCodeOwners(repo, hostingIssues, forkTo);
                checkGitignore(repo, hostingIssues);
                checkDependencyBot(repo, hostingIssues);
                if (request.isEnableCD()) {
                    checkFilesForCD(repo, hostingIssues);
                }
            }
        }
    }

    private void checkCodeOwners(GHRepository repo, HashSet<VerificationMessage> hostingIssues, String forkTo)
            throws IOException {
        String expected = "* @jenkinsci/" + forkTo + "-developers";
        GHContent file = null;
        try {
            file = repo.getFileContent(".github/CODEOWNERS");
        } catch (GHFileNotFoundException e) {
            hostingIssues.add(new VerificationMessage(
                    VerificationMessage.Severity.REQUIRED,
                    "Missing file `.github/CODEOWNERS`. Please add this file containing the line: `" + expected + "`"));
            return;
        }
        try (BufferedReader bufferedReader =
                new BufferedReader(new InputStreamReader(file.read(), StandardCharsets.UTF_8))) {
            if (bufferedReader.lines().noneMatch(line -> line.equals(expected))) {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED,
                        "The file `.github/CODEOWNERS` doesn't contain the expected line `" + expected + "`"));
            }
        }
    }

    private void checkJenkinsfile(GHRepository repo, HashSet<VerificationMessage> hostingIssues) throws IOException {
        GHContent file = null;
        try {
            file = repo.getFileContent("Jenkinsfile");
        } catch (GHFileNotFoundException e) {
            hostingIssues.add(
                    new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "Missing file `Jenkinsfile`. Please add a Jenkinsfile to your repo so it can be built on ci.jenkins.io. A suitable version can be downloaded [here](https://github.com/jenkinsci/archetypes/blob/master/common-files/Jenkinsfile)"));
            return;
        }
        validateJenkinsFile(file, hostingIssues);
    }

    private void checkGitignore(GHRepository repo, HashSet<VerificationMessage> hostingIssues) throws IOException {
        GHContent file = null;
        try {
            file = repo.getFileContent(".gitignore");
        } catch (GHFileNotFoundException e) {
            hostingIssues.add(
                    new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "Missing file `.gitignore`. Please add a `.gitignore` to help keep your git repo lean. A suitable version can be downloaded [here](https://github.com/jenkinsci/archetypes/blob/master/common-files/gitignore)"));
            return;
        }
        try (BufferedReader bufferedReader =
                new BufferedReader(new InputStreamReader(file.read(), StandardCharsets.UTF_8))) {
            List<String> lines = bufferedReader.lines().toList();
            if (lines.stream()
                    .noneMatch(line -> line.equals("target") || line.equals("target/") || line.equals("/target/"))) {
                hostingIssues.add(
                        new VerificationMessage(
                                VerificationMessage.Severity.REQUIRED,
                                "The file `.gitignore` doesn't exclude `target`. Please add a line so that you don't check-in this directory by accident."));
            }
            if (lines.stream()
                    .noneMatch(line -> line.equals("work") || line.equals("work/") || line.equals("/work/"))) {
                hostingIssues.add(
                        new VerificationMessage(
                                VerificationMessage.Severity.REQUIRED,
                                "The file `.gitignore` doesn't exclude `work`. Please add a line so that you don't check-in this directory by accident."));
            }
        }
    }

    private void checkSecurityScan(GHRepository repo, HashSet<VerificationMessage> hostingIssues) throws IOException {
        if (fileNotExistsInRepo(repo, ".github/workflows/jenkins-security-scan.yml")
                && fileNotExistsInRepo(repo, ".github/workflows/jenkins-security-scan.yaml")) {
            hostingIssues.add(
                    new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "Missing file `.github/workflows/jenkins-security-scan.yml`. This file helps to keep your plugin conform to security standards defined by the Jenkins project."
                                    + " A suitable version can be downloaded [here](https://github.com/jenkinsci/archetypes/blob/master/common-files/.github/workflows/jenkins-security-scan.yml)"));
        }
    }

    private void checkDependencyBot(GHRepository repo, HashSet<VerificationMessage> hostingIssues) throws IOException {
        if (fileNotExistsInRepo(repo, ".github/dependabot.yml")
                && fileNotExistsInRepo(repo, ".github/dependabot.yaml")
                && fileNotExistsInRepo(repo, "renovate.json")
                && fileNotExistsInRepo(repo, ".github/renovate.json")
                && fileNotExistsInRepo(repo, ".github/workflows/updatecli.yml")
                && fileNotExistsInRepo(repo, ".github/workflows/updatecli.yaml")) {
            hostingIssues.add(
                    new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "No files found related to automatically updating the plugin dependencies. "
                                    + "Please ensure that you have dependabot, renovate or updatecli configured in the repo. "
                                    + "A suitable version for dependabot can be downloaded [here](https://github.com/jenkinsci/archetypes/blob/master/common-files/.github/dependabot.yml)"));
        }
    }

    private void checkFilesForCD(GHRepository repo, HashSet<VerificationMessage> hostingIssues) throws IOException {

        if (fileNotExistsInRepo(repo, ".mvn/extensions.xml")) {
            hostingIssues.add(
                    new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "Missing file `.mvn/extensions.xml`. This file is required when CD is enabled. "
                                    + "A suitable version can be downloaded [here](https://raw.githubusercontent.com/jenkinsci/archetypes/refs/heads/master/common-files/.mvn/extensions.xml)"));
        }
        if (fileNotExistsInRepo(repo, ".github/workflows/cd.yaml")
                && fileNotExistsInRepo(repo, ".github/workflows/cd.yml")) {
            hostingIssues.add(
                    new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "Missing file `.github/workflows/cd.yaml`. This file is required when CD is enabled. "
                                    + "A suitable version can be downloaded [here](https://raw.githubusercontent.com/jenkinsci/.github/master/workflow-templates/cd.yaml"));
        }
        if (!fileNotExistsInRepo(repo, ".github/release-drafter.yml")
                || !fileNotExistsInRepo(repo, ".github/release-drafter.yaml")) {
            hostingIssues.add(new VerificationMessage(
                    VerificationMessage.Severity.REQUIRED,
                    "The file `.github/release-drafter.y*ml` should be removed when CD is enabled."));
        }
        if (!fileNotExistsInRepo(repo, ".github/workflows/release-drafter.yml")
                || !fileNotExistsInRepo(repo, ".github/workflows/release-drafter.yaml")) {
            hostingIssues.add(new VerificationMessage(
                    VerificationMessage.Severity.REQUIRED,
                    "The file `.github/workflows/release-drafter.y*ml` should be removed when CD is enabled."));
        }
        GHContent file = null;
        String expected = "-Dchangelist.format=%d.v%s";
        try {
            file = repo.getFileContent(".mvn/maven.config");
            try (BufferedReader bufferedReader =
                    new BufferedReader(new InputStreamReader(file.read(), StandardCharsets.UTF_8))) {
                if (bufferedReader.lines().noneMatch(line -> line.equals(expected))) {
                    hostingIssues.add(new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "The file `.mvn/maven.config` doesn't contain the expected line `"
                                    + expected.replaceAll("%", "%%") + "`"));
                }
            }

        } catch (GHFileNotFoundException e) {
            hostingIssues.add(new VerificationMessage(
                    VerificationMessage.Severity.REQUIRED,
                    "Missing file `.mvn/config`. This file is required when CD is enabled. "
                            + "Download [maven.config](https://raw.githubusercontent.com/jenkinsci/archetypes/refs/heads/master/common-files/.mvn/maven.config) "
                            + "and add the line: `" + expected.replaceAll("%", "%%") + "`"));
        }
    }

    @SuppressWarnings("unchecked")
    public static void validateJenkinsFile(GHContent file, HashSet<VerificationMessage> hostingIssues) {
        try {
            GroovyClassLoader loader = new GroovyClassLoader();
            CompilerConfiguration config = new CompilerConfiguration();
            CompilationUnit unit = new CompilationUnit(config, null, loader);

            String script = IOUtils.toString(file.read(), StandardCharsets.UTF_8);

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

        } catch (IOException e) {
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

    private boolean fileNotExistsInRepo(GHRepository repo, String fileName) throws IOException {
        try {
            GHContent file = repo.getFileContent(fileName);
            return file == null || !file.isFile();
        } catch (GHFileNotFoundException e) {
            return true;
        }
    }
}
