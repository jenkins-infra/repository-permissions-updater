package io.jenkins.infra.repository_permissions_updater.hosting;

import static io.jenkins.infra.repository_permissions_updater.hosting.Requirements.LOWEST_JENKINS_VERSION;
import static io.jenkins.infra.repository_permissions_updater.hosting.Requirements.LOWEST_PARENT_POM_VERSION;
import static io.jenkins.infra.repository_permissions_updater.hosting.Requirements.PARENT_POM_WITH_JENKINS_VERSION;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.License;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Repository;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.interpolation.InterpolationException;
import org.codehaus.plexus.interpolation.MapBasedValueSource;
import org.codehaus.plexus.interpolation.RegexBasedInterpolator;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MavenVerifier implements BuildSystemVerifier {
    private static final String BOM_ROOT_URL = "https://repo.jenkins-ci.org/artifactory/public/io/jenkins/tools/bom/";
    private static final String REPO_ROOT_URL = "https://repo.jenkins-ci.org/public/";
    private static final Set<String> COMPILE_ONLY_ANNOTATIONS = Set.of(
            "com.google.errorprone:error_prone_annotations",
            "com.google.code.findbugs:jsr305",
            "org.jetbrains:annotations",
            "org.checkerframework:checker-qual",
            "com.google.j2objc:j2objc-annotations");
    private static final int MAX_LENGTH_OF_GROUP_ID_PLUS_ARTIFACT_ID = 100;
    private static final int MAX_LENGTH_OF_ARTIFACT_ID = 37;
    private static final Logger LOGGER = LoggerFactory.getLogger(MavenVerifier.class);

    public static final String INVALID_POM = "The pom.xml file in the root of the origin repository is not valid";
    public static final String SPECIFY_LICENSE =
            "Please specify a license in your pom.xml file using the &lt;licenses&gt; tag. See https://maven.apache.org/pom.html#Licenses for more information.";

    public static final String SHOULD_BE_IO_JENKINS_PLUGINS =
            "The &lt;groupId&gt; from the pom.xml should be `io.jenkins.plugins` instead of `%s`";

    public static final String DEPENDENCY_SHOULD_USE_API_PLUGIN =
            "The dependency `%s` should be replaced with a dependency to the api plugin `%s` %s";
    public static final String COMPILE_ONLY_ANNOTATION =
            "The dependency `%s` contains annotations that are only needed at compile time. Add `<scope>provided</scope>` so it is not bundled in the plugin.";
    public static final String REDUNDANT_DEPENDENCIES =
            "The following dependencies are already provided and can be removed from the pom.xml:";
    public static final String REDUNDANT_BUILD_PLUGINS =
            "The following build plugins are already configured by the parent pom:";
    public static final String BUNDLED_ARTIFACTS =
            "The following libraries listed in `hpi.bundledArtifacts` should not be bundled in the plugin. "
                    + "Add an `<exclusion>` for them to the dependency that brings them in:";
    public static final String UNUSED_BOM =
            "None of the dependencies are managed by the bom, so the `<dependencyManagement>` section can be removed.";
    private final HashSet<VerificationMessage> hostingIssues;
    private final RepositoryCheckout checkout;

    public MavenVerifier(HashSet<VerificationMessage> hostingIssues, RepositoryCheckout checkout) {
        this.hostingIssues = hostingIssues;
        this.checkout = checkout;
    }

    @Override
    public void verify(HostingRequest issue) throws IOException {
        Optional<String> pomXml = checkout.read("pom.xml");
        if (pomXml.isEmpty()) {
            // reported as "No pom.xml detected." by HostingChecker
            return;
        }
        Model model;
        try (StringReader contents = new StringReader(pomXml.get())) {
            model = new MavenXpp3Reader().read(contents);
        } catch (XmlPullParserException | IOException e) {
            // reading from a string, so an IOException is also a parse error, e.g. a truncated file
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, INVALID_POM));
            return;
        }
        verifyModel(model, issue);
    }

    void verifyModel(Model model, HostingRequest issue) {
        try {
            checkArtifactId(model, issue.getNewRepoName());
            checkParentInfoAndJenkinsVersion(model);
            checkName(model);
            checkLicenses(model);
            checkGroupId(model);
            checkRepositories(model);
            checkPluginRepositories(model);
            checkSoftwareConfigurationManagementField(model);
            checkDependencies(model);
            checkCompileOnlyAnnotations(model);
            try {
                checkProvidedByParentOrCore(model);
            } catch (RuntimeException e) {
                // downloads poms from repo.jenkins-ci.org, so a failure here says nothing about the plugin's pom.xml
                LOGGER.warn("Failed to compare the pom.xml with the parent pom and Jenkins core", e);
            }
            checkDependencyManagement(model);
            checkDevelopersTag(model);
            checkProperties(model);
            checkUrl(model);
            if (issue.isEnableCD()) {
                checkAutomaticReleasesSettings(model);
            }
        } catch (Exception e) {
            LOGGER.error("Failed looking at pom.xml", e);
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, INVALID_POM));
        }
    }

    private void checkUrl(Model model) {
        Properties props = model.getProperties();
        props.put("project.artifactId", model.getArtifactId());
        RegexBasedInterpolator interpolator = new RegexBasedInterpolator();
        interpolator.addValueSource(new MapBasedValueSource(props));
        String url = model.getUrl();
        try {
            url = interpolator.interpolate(url);
            if (!url.equals("https://github.com/jenkinsci/" + model.getArtifactId() + "-plugin")) {
                hostingIssues.add(
                        new VerificationMessage(
                                VerificationMessage.Severity.REQUIRED,
                                "The `<url>` field in the pom.xml should be `https://github.com/jenkinsci/${project.artifactId}-plugin`."));
            }
        } catch (InterpolationException e) {
            LOGGER.warn("Failed to interpolate url", e);
        }
    }

    @Override
    public boolean hasBuildFile(HostingRequest issue) {
        return checkout.isFile("pom.xml");
    }

    private void checkAutomaticReleasesSettings(Model model) {
        Properties props = model.getProperties();
        requireProperty(
                "changelist",
                "999999-SNAPSHOT",
                props,
                "This is required for automatic releases to work correctly."
                        + " See https://www.jenkins.io/doc/developer/publishing/releasing-cd/ for best practices.");
        String version = model.getVersion();
        if (!version.contains("${changelist}")) {
            hostingIssues.add(new VerificationMessage(
                    VerificationMessage.Severity.REQUIRED,
                    "The version in the pom.xml must contain `${changelist}` when CD is enabled."));
        }
        if (version.contains("${revision}${changelist}")) {
            hostingIssues.add(
                    new VerificationMessage(
                            VerificationMessage.Severity.WARNING,
                            "Using ${revision}${changelist} for the version is not recommended."
                                    + " See https://www.jenkins.io/doc/developer/publishing/releasing-cd/ for best practices."));
        }
    }

    private void checkArtifactId(Model model, String forkTo) {
        try {
            if (StringUtils.isBlank(forkTo)) {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED, "Missing value for 'New Repository Name' field"));
            }

            String groupId = effectiveGroupId(model);
            String artifactId = model.getArtifactId();

            if (StringUtils.isNotBlank(artifactId)) {
                if (StringUtils.isNotBlank(forkTo) && !artifactId.equals(forkTo.replace("-plugin", ""))) {
                    hostingIssues.add(new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "The 'artifactId' from the pom.xml (`%s`) is incorrect, it should be `%s` ('New Repository Name' field with \"-plugin\" removed)",
                            artifactId,
                            (forkTo.replace("-plugin", "")).toLowerCase()));
                }

                if (artifactId.length() >= MAX_LENGTH_OF_ARTIFACT_ID) {
                    hostingIssues.add(new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "The 'artifactId' `%s` from the pom.xml is incorrect, it must have less than %d characters, currently it has %d characters",
                            artifactId,
                            MAX_LENGTH_OF_ARTIFACT_ID,
                            artifactId.length()));
                }

                int lengthOfGroupIdAndArtifactId = StringUtils.length(groupId) + artifactId.length();
                if (lengthOfGroupIdAndArtifactId >= MAX_LENGTH_OF_GROUP_ID_PLUS_ARTIFACT_ID) {
                    hostingIssues.add(new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "The 'artifactId' `%s` and 'groupId' `%s` from the pom.xml is incorrect, combined they must have less than %d characters, currently they have %d characters",
                            artifactId,
                            groupId,
                            MAX_LENGTH_OF_GROUP_ID_PLUS_ARTIFACT_ID,
                            lengthOfGroupIdAndArtifactId));
                }

                if (artifactId.toLowerCase().contains("jenkins")) {
                    hostingIssues.add(new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "The 'artifactId' from the pom.xml (`%s`) should not contain \"Jenkins\"",
                            artifactId));
                }

                if (!artifactId.toLowerCase().equals(artifactId)) {
                    hostingIssues.add(new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "The 'artifactId' from the pom.xml (`%s`) should be all lower case",
                            artifactId));
                }
            } else {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED,
                        "The pom.xml file does not contain a valid 'artifactId' for the project"));
            }
        } catch (Exception e) {
            LOGGER.error("Error trying to access artifactId", e);
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, INVALID_POM));
        }
    }

    /**
     * The project's groupId, or the parent's when the project inherits it, as Maven does.
     */
    static String effectiveGroupId(Model model) {
        if (StringUtils.isNotBlank(model.getGroupId())) {
            return model.getGroupId();
        }
        return model.getParent() != null ? model.getParent().getGroupId() : null;
    }

    private void checkGroupId(Model model) {
        try {
            String groupId = model.getGroupId();
            if (StringUtils.isNotBlank(groupId)) {
                if (!groupId.equals("io.jenkins.plugins")) {
                    hostingIssues.add(new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED, SHOULD_BE_IO_JENKINS_PLUGINS, groupId));
                }
            } else {
                Parent parent = model.getParent();
                if (parent != null) {
                    groupId = parent.getGroupId();
                    if (StringUtils.isNotBlank(groupId)) {
                        if (groupId.equals("org.jenkins-ci.plugins")) {
                            hostingIssues.add(new VerificationMessage(
                                    VerificationMessage.Severity.REQUIRED,
                                    "You must add a 'groupId' in your pom.xml with the value `io.jenkins.plugins`."));
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error trying to access groupId", e);
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, INVALID_POM));
        }
    }

    private void checkName(Model model) {
        try {
            String name = model.getName();
            if (StringUtils.isNotBlank(name)) {
                if (name.toLowerCase().contains("jenkins")) {
                    hostingIssues.add(new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "The 'name' field in the pom.xml should not contain \"Jenkins\""));
                }
            } else {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED,
                        "The pom.xml file does not contain a valid `<name>` for the project"));
            }
        } catch (Exception e) {
            LOGGER.error("Error trying to access <name>", e);
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, INVALID_POM));
        }
    }

    public static String getArtifactId(String contents) {
        String res;
        MavenXpp3Reader reader = new MavenXpp3Reader();
        try (StringReader stringReader = new StringReader(contents)) {
            Model model = reader.read(stringReader);
            res = model.getArtifactId();
        } catch (XmlPullParserException | IOException e) {
            res = null;
        }
        return res;
    }

    public static String getGroupId(String contents) {
        String res;
        MavenXpp3Reader reader = new MavenXpp3Reader();
        try (StringReader stringReader = new StringReader(contents)) {
            Model model = reader.read(stringReader);
            res = model.getGroupId();
        } catch (XmlPullParserException | IOException e) {
            res = null;
        }
        return res;
    }

    private void checkParentInfoAndJenkinsVersion(Model model) {
        try {
            Parent parent = model.getParent();
            if (parent != null) {
                String groupId = parent.getGroupId();
                if (StringUtils.isNotBlank(groupId) && !groupId.equals("org.jenkins-ci.plugins")) {
                    hostingIssues.add(
                            new VerificationMessage(
                                    VerificationMessage.Severity.REQUIRED,
                                    "The groupId for your parent pom is not \"org.jenkins-ci.plugins,\" if this is not a plugin hosting request, you can disregard this notice."));
                }

                String version = parent.getVersion();
                if (StringUtils.isNotBlank(version)) {
                    Version parentVersion = new Version(version);
                    if (parentVersion.compareTo(LOWEST_PARENT_POM_VERSION) < 0) {
                        hostingIssues.add(new VerificationMessage(
                                VerificationMessage.Severity.REQUIRED,
                                "The parent pom version '%s' should be at least '%s' or higher.",
                                parentVersion,
                                LOWEST_PARENT_POM_VERSION));
                    }

                    if (parentVersion.compareTo(PARENT_POM_WITH_JENKINS_VERSION) >= 0) {
                        JenkinsVersion jenkinsVersion = getJenkinsVersion(model);

                        if (jenkinsVersion == null) {
                            hostingIssues.add(
                                    new VerificationMessage(
                                            VerificationMessage.Severity.REQUIRED,
                                            "Your pom.xml is missing the property `jenkins.version`. Take a look at the [baseline recommendations](https://www.jenkins.io/doc/developer/plugin-development/choosing-jenkins-baseline/#currently-recommended-versions)."));
                        }

                        if (jenkinsVersion != null
                                && jenkinsVersion.jenkinsVersion().compareTo(LOWEST_JENKINS_VERSION) < 0) {
                            hostingIssues.add(new VerificationMessage(
                                    VerificationMessage.Severity.REQUIRED,
                                    "Your baseline specified does not meet the minimum Jenkins version required, please update `<jenkins.version>%s</jenkins.version>` to at least %s in your pom.xml. Take a look at the [baseline recommendations](https://www.jenkins.io/doc/developer/plugin-development/choosing-jenkins-baseline/#currently-recommended-versions).",
                                    jenkinsVersion.jenkinsVersion(),
                                    LOWEST_JENKINS_VERSION));
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error trying to access the <parent> information", e);
        }
    }

    private void checkDevelopersTag(Model model) {
        if (!model.getDevelopers().isEmpty()) {
            hostingIssues.add(
                    new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "Please remove the `developers` tag from your pom.xml. This information is fetched from this repository on the update site."));
        }
    }

    private void checkLicenses(Model model) {
        // first check the pom.xml
        List<License> licenses = model.getLicenses();
        if (licenses.isEmpty()) {
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, SPECIFY_LICENSE));
        }
    }

    private void checkRepositories(Model model) {
        for (Repository r : model.getRepositories()) {
            if (r.getUrl().contains("repo.jenkins-ci.org") || r.getId().contains("repo.jenkins-ci.org")) {
                try {
                    URI uri = new URI(r.getUrl());
                    if (!uri.getScheme().equals("https")) {
                        hostingIssues.add(
                                new VerificationMessage(
                                        VerificationMessage.Severity.REQUIRED,
                                        "You MUST use an https:// scheme in your pom.xml for the `<repository><url></url></repository>` tag for repo.jenkins-ci.org"));
                    }
                } catch (URISyntaxException e) {
                    hostingIssues.add(
                            new VerificationMessage(
                                    VerificationMessage.Severity.REQUIRED,
                                    "The `<repository><url></url></repository>` in your pom.xml for 'repo.jenkins-ci.org' has an invalid URL"));
                }
            }
        }
    }

    private void checkPluginRepositories(Model model) {
        for (Repository r : model.getPluginRepositories()) {
            if (r.getUrl().contains("repo.jenkins-ci.org") || r.getId().contains("repo.jenkins-ci.org")) {
                try {
                    URI uri = new URI(r.getUrl());
                    if (!uri.getScheme().equals("https")) {
                        hostingIssues.add(
                                new VerificationMessage(
                                        VerificationMessage.Severity.REQUIRED,
                                        "You MUST use an https:// scheme in your pom.xml for the `<pluginRepository><url></url></pluginRepository>` tag for repo.jenkins-ci.org"));
                    }
                } catch (URISyntaxException e) {
                    hostingIssues.add(
                            new VerificationMessage(
                                    VerificationMessage.Severity.REQUIRED,
                                    "The `<pluginRepository><url></url></pluginRepository>` in your pom.xml for 'repo.jenkins-ci.org' has an invalid URL"));
                }
            }
        }
    }

    private void checkSoftwareConfigurationManagementField(Model model) {
        if (model.getScm() == null) {
            hostingIssues.add(
                    new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "You must specify an `<scm>` block in your pom.xml. See https://maven.apache.org/pom.html#SCM for more information."));
        } else {
            if (model.getScm().getConnection() != null
                    && ((model.getScm().getConnection().startsWith("scm:git:git:"))
                            || model.getScm().getConnection().startsWith("scm:git:http:"))) {
                hostingIssues.add(
                        new VerificationMessage(
                                VerificationMessage.Severity.REQUIRED,
                                "You must use HTTPS for the `<connection>` tag in your `<scm>` block in your pom.xml. You can use this sample: `<connection>scm:git:https://github.com/jenkinsci/${project.artifactId}-plugin.git</connection>`"));
            } else if (model.getScm().getConnection() == null) {
                hostingIssues.add(
                        new VerificationMessage(
                                VerificationMessage.Severity.REQUIRED,
                                "You must specify a `<connection>` tag in your `<scm>` block in your pom.xml. You can use this sample: `<connection>scm:git:https://github.com/jenkinsci/${project.artifactId}-plugin.git</connection>`"));
            }
            if (model.getScm().getUrl() == null) {
                hostingIssues.add(
                        new VerificationMessage(
                                VerificationMessage.Severity.REQUIRED,
                                "You must specify an `<url>` tag in your `<scm>` block in your pom.xml. You can use this sample: `<url>scm:git:https://github.com/jenkinsci/${project.artifactId}-plugin</url>`"));
            }
            if (model.getScm().getDeveloperConnection() == null) {
                hostingIssues.add(
                        new VerificationMessage(
                                VerificationMessage.Severity.REQUIRED,
                                "You must specify a `<developerConnection>` tag in your <scm> block in your pom.xml. You can use this sample: `<developerConnection>scm:git:https://github.com/jenkinsci/${project.artifactId}-plugin</developerConnection>`"));
            }
            if (model.getScm().getTag() == null) {
                hostingIssues.add(
                        new VerificationMessage(
                                VerificationMessage.Severity.REQUIRED,
                                "You must specify a `<tag>` tag in your `<scm>` block in your pom.xml. You can use this sample: `<tag>${scmTag}</tag>`"));
            }
        }
    }

    private void checkDependencyManagement(Model model) {
        JenkinsVersion jenkinsVersion = getJenkinsVersion(model);
        if (jenkinsVersion != null) {
            HashSet<VerificationMessage> dependencyManagementIssues = new HashSet<>();
            DependencyManagement depManagement = model.getDependencyManagement();
            Optional<Dependency> bom = Optional.empty();
            if (depManagement != null) {
                bom = depManagement.getDependencies().stream()
                        .filter(d -> d.getGroupId().equals("io.jenkins.tools.bom")
                                && d.getArtifactId().startsWith("bom-"))
                        .findFirst();
            }

            Set<String> managedDependencies;
            String bomArtifactId = "bom-" + jenkinsVersion.baseline() + ".x";
            String latestReleasedBom = getLatestBomVersion(bomArtifactId);
            if (latestReleasedBom != null) {
                managedDependencies = getManagedDependenciesFromBom(bomArtifactId, latestReleasedBom);
            } else {
                managedDependencies = Collections.emptySet();
            }
            List<Dependency> pluginDependencies = model.getDependencies().stream()
                    .filter(d -> managedDependencies.contains(getDependencyAsString(d, false)))
                    .toList();
            if (pluginDependencies.isEmpty() && bom.isPresent() && !managedDependencies.isEmpty()) {
                hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.INFO, UNUSED_BOM));
            }
            if (!pluginDependencies.isEmpty() && bom.isEmpty()) {
                dependencyManagementIssues.add(
                        new VerificationMessage(
                                VerificationMessage.Severity.REQUIRED,
                                "You're using one or more Jenkins plugins without making use of the bom. "
                                        + "Please add a corresponding dependencyManagement section to your pom.xml and remove the version for those dependencies."
                                        + " See [here](https://www.jenkins.io/doc/developer/plugin-development/dependency-management/#jenkins-plugin-bom) for details."));
            }
            if (bom.isPresent()) {
                Properties props = model.getProperties();
                RegexBasedInterpolator interpolator = new RegexBasedInterpolator();
                interpolator.addValueSource(new MapBasedValueSource(props));

                Dependency dep = bom.get();
                String version = dep.getVersion();
                try {
                    version = interpolator.interpolate(version);
                } catch (Exception e) {
                    LOGGER.error("Error interpolating bom version", e);
                    dependencyManagementIssues.add(new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "There was an error trying to interpolate the bom version `%s`. Please make sure that all properties used in the version are defined and can be interpolated correctly.",
                            dep.getVersion()));
                }
                if (latestReleasedBom != null && !latestReleasedBom.equals(version)) {
                    dependencyManagementIssues.add(new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "The bom version `%s` of `%s` should be updated to the latest version `%s`",
                            dep.getVersion(),
                            dep.getArtifactId(),
                            latestReleasedBom));
                }
                String artifactId = dep.getArtifactId();
                artifactId = artifactId.replace("${jenkins.baseline}", jenkinsVersion.baseline());
                String expected = "bom-" + jenkinsVersion.baseline() + ".x";
                if (!expected.equals(artifactId)) {
                    dependencyManagementIssues.add(new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "The artifactId `%s` of the bom is not in sync with the jenkins baseline version `%s`",
                            artifactId,
                            jenkinsVersion.baseline()));
                }
            }
            if (latestReleasedBom != null) {
                pluginDependencies.forEach(d -> {
                    if (d.getVersion() != null) {
                        dependencyManagementIssues.add(new VerificationMessage(
                                VerificationMessage.Severity.REQUIRED,
                                "The dependency `%s` is covered by the bom. The version should be removed.",
                                getDependencyAsString(d, true)));
                    }
                });
            }
            if (!dependencyManagementIssues.isEmpty()) {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED,
                        dependencyManagementIssues,
                        "The following issues have been identified related to plugin dependency management:"));
            }
        }
    }

    private void requireProperty(String name, String value, Properties props, String message) {
        if (!props.containsKey(name) || !props.getProperty(name).equals(value)) {
            hostingIssues.add(new VerificationMessage(
                    VerificationMessage.Severity.REQUIRED,
                    "Please define the property `" + name + "` and set it to `" + value + "`. " + message));
        }
    }

    private void checkProperties(Model model) {
        Properties props = model.getProperties();
        List<String> illegalProps =
                Arrays.asList("java.level", "maven.compiler.source", "maven.compiler.target", "maven.compiler.release");
        illegalProps.forEach(p -> {
            if (props.containsKey(p)) {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED,
                        "Please remove the property `%s` from the pom.xml. It is already defined via the parent pom.",
                        p));
            }
        });
        if (!props.containsKey("jenkins.baseline")) {
            hostingIssues.add(
                    new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "Please define the property `jenkins.baseline` and use this property in `<jenkins.version>${jenkins.baseline}.3</jenkins.version>` and the artifactId of the bom."));
        }
        requireProperty(
                "hpi.strictBundledArtifacts",
                "true",
                props,
                "This should help prevent accidental library bundling when adding and updating dependencies."
                        + "See [Bundling third-party libraries](https://www.jenkins.io/doc/developer/plugin-development/dependencies-and-class-loading/#bundling-third-party-libraries).");
        requireProperty(
                "ban-commons-lang-2.skip",
                "false",
                props,
                "This should help prevent accidental usage of the deprecated commons-lang-2 library that is included in core.");
        requireProperty(
                "ban-deprecated-stapler.skip",
                "false",
                props,
                "This should help prevent usage of deprecated stapler and javax.servlet classes."
                        + " included in core.");
        requireProperty(
                "ban-junit4-imports.skip",
                "false",
                props,
                "This should help prevent usage of deprecated junit 4 classes.");
        requireProperty(
                "banObsoleteDependencyOverrides.skip",
                "false",
                props,
                "This should help identify unnecessary overrides that can be removed when updating BOM versions or parent POMs.");
    }

    private void checkDependencies(Model model) {
        Map<String, String> bd = getBannedDependencies();
        model.getDependencies().forEach(d -> {
            String dep = d.getGroupId() + ":" + d.getArtifactId();
            String scope = d.getScope();
            if (scope == null) {
                scope = "compile";
            }
            if (scope.equals("compile") && bd.containsKey(dep)) {
                String[] alt = bd.get(dep).split(";", 2);
                String comment = "";
                if (alt.length > 1) {
                    comment = ". " + alt[1];
                }
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED, DEPENDENCY_SHOULD_USE_API_PLUGIN, dep, alt[0], comment));
            }
        });
    }

    private void checkCompileOnlyAnnotations(Model model) {
        for (Dependency d : model.getDependencies()) {
            String dep = d.getGroupId() + ":" + d.getArtifactId();
            if (isBundled(d) && COMPILE_ONLY_ANNOTATIONS.contains(dep)) {
                hostingIssues.add(
                        new VerificationMessage(VerificationMessage.Severity.WARNING, COMPILE_ONLY_ANNOTATION, dep));
            }
        }
    }

    private static boolean isBundled(Dependency d) {
        return d.getScope() == null
                || d.getScope().equals("compile")
                || d.getScope().equals("runtime");
    }

    private void checkProvidedByParentOrCore(Model model) {
        Parent parent = model.getParent();
        if (parent == null
                || !"org.jenkins-ci.plugins".equals(parent.getGroupId())
                || !"plugin".equals(parent.getArtifactId())
                || StringUtils.isBlank(parent.getVersion())) {
            return;
        }
        Model parentModel = readPom(REPO_ROOT_URL + "org/jenkins-ci/plugins/plugin/" + parent.getVersion() + "/plugin-"
                + parent.getVersion() + ".pom");
        if (parentModel == null) {
            return;
        }
        Set<String> coreDependencies = Collections.emptySet();
        JenkinsVersion jenkinsVersion = getJenkinsVersion(model);
        if (jenkinsVersion != null) {
            String version = jenkinsVersion.jenkinsVersion().toString();
            coreDependencies = bundledDependencies(readPom(REPO_ROOT_URL + "org/jenkins-ci/main/jenkins-core/" + version
                    + "/jenkins-core-" + version + ".pom"));
        }
        Set<String> harnessDependencies = Collections.emptySet();
        String harnessVersion = parentModel.getProperties().getProperty("jenkins-test-harness.version");
        if (harnessVersion != null) {
            harnessDependencies =
                    bundledDependencies(readPom(REPO_ROOT_URL + "org/jenkins-ci/main/jenkins-test-harness/"
                            + harnessVersion + "/jenkins-test-harness-" + harnessVersion + ".pom"));
        }
        Set<String> parentDependencies = new HashSet<>();
        parentModel.getDependencies().forEach(d -> parentDependencies.add(d.getGroupId() + ":" + d.getArtifactId()));
        checkRedundantDependencies(model, parentDependencies, coreDependencies, harnessDependencies);
        checkBundledArtifacts(model, coreDependencies);
        checkRedundantBuildPlugins(model, parentModel);
    }

    void checkRedundantDependencies(
            Model model,
            Set<String> parentDependencies,
            Set<String> coreDependencies,
            Set<String> harnessDependencies) {
        Set<String> banned = getBannedDependencies().keySet();
        HashSet<VerificationMessage> redundant = new HashSet<>();
        for (Dependency d : model.getDependencies()) {
            String dep = d.getGroupId() + ":" + d.getArtifactId();
            if (banned.contains(dep)) {
                // reported with the api plugin to use instead
                continue;
            }
            if (parentDependencies.contains(dep)) {
                redundant.add(new VerificationMessage(
                        VerificationMessage.Severity.INFO, "`%s` is already added by the parent pom", dep));
            } else if (coreDependencies.contains(dep)) {
                redundant.add(new VerificationMessage(
                        VerificationMessage.Severity.INFO, "`%s` is provided by Jenkins core", dep));
            } else if ("test".equals(d.getScope()) && harnessDependencies.contains(dep)) {
                redundant.add(new VerificationMessage(
                        VerificationMessage.Severity.INFO,
                        "`%s` is already added as test dependency by `jenkins-test-harness`",
                        dep));
            }
        }
        if (!redundant.isEmpty()) {
            hostingIssues.add(
                    new VerificationMessage(VerificationMessage.Severity.WARNING, redundant, REDUNDANT_DEPENDENCIES));
        }
    }

    /**
     * Libraries that come in transitively and are allowed to be bundled with {@code hpi.bundledArtifacts}
     * should not be bundled when they are provided by Jenkins core or only needed at compile time.
     */
    void checkBundledArtifacts(Model model, Set<String> coreDependencies) {
        String bundledArtifacts = model.getProperties().getProperty("hpi.bundledArtifacts");
        if (bundledArtifacts == null) {
            return;
        }
        HashSet<VerificationMessage> excludable = new HashSet<>();
        for (String artifactId : bundledArtifacts.split(",")) {
            String suffix = ":" + artifactId.trim();
            if (coreDependencies.stream().anyMatch(d -> d.endsWith(suffix))) {
                excludable.add(new VerificationMessage(
                        VerificationMessage.Severity.INFO, "`%s` is provided by Jenkins core", artifactId.trim()));
            } else if (COMPILE_ONLY_ANNOTATIONS.stream().anyMatch(d -> d.endsWith(suffix))) {
                excludable.add(new VerificationMessage(
                        VerificationMessage.Severity.INFO,
                        "`%s` contains annotations that are only needed at compile time",
                        artifactId.trim()));
            }
        }
        if (!excludable.isEmpty()) {
            hostingIssues.add(
                    new VerificationMessage(VerificationMessage.Severity.WARNING, excludable, BUNDLED_ARTIFACTS));
        }
    }

    void checkRedundantBuildPlugins(Model model, Model parentModel) {
        if (model.getBuild() == null || parentModel.getBuild() == null) {
            return;
        }
        Set<String> configured = new HashSet<>();
        parentModel.getBuild().getPlugins().forEach(p -> configured.add(p.getKey()));
        Set<String> managed = new HashSet<>(configured);
        if (parentModel.getBuild().getPluginManagement() != null) {
            parentModel.getBuild().getPluginManagement().getPlugins().forEach(p -> managed.add(p.getKey()));
        }
        HashSet<VerificationMessage> redundant = new HashSet<>();
        for (Plugin plugin : model.getBuild().getPlugins()) {
            boolean customized = plugin.getConfiguration() != null
                    || !plugin.getExecutions().isEmpty()
                    || !plugin.getDependencies().isEmpty();
            if (configured.contains(plugin.getKey()) && !customized) {
                redundant.add(new VerificationMessage(
                        VerificationMessage.Severity.INFO,
                        "`%s` is already configured by the parent pom, remove it",
                        plugin.getArtifactId()));
            } else if (managed.contains(plugin.getKey()) && plugin.getVersion() != null) {
                redundant.add(new VerificationMessage(
                        VerificationMessage.Severity.INFO,
                        "The version of `%s` is managed by the parent pom, remove the `<version>`",
                        plugin.getArtifactId()));
            }
        }
        if (!redundant.isEmpty()) {
            hostingIssues.add(
                    new VerificationMessage(VerificationMessage.Severity.WARNING, redundant, REDUNDANT_BUILD_PLUGINS));
        }
    }

    private static Set<String> bundledDependencies(Model model) {
        Set<String> dependencies = new HashSet<>();
        if (model != null) {
            model.getDependencies().stream()
                    .filter(d -> isBundled(d) && !d.isOptional())
                    .forEach(d -> dependencies.add(d.getGroupId() + ":" + d.getArtifactId()));
        }
        return dependencies;
    }

    @SuppressFBWarnings("URLCONNECTION_SSRF_FD")
    private static Model readPom(String pomUrl) {
        try (InputStream is = new URI(pomUrl).toURL().openStream()) {
            return new MavenXpp3Reader().read(is);
        } catch (XmlPullParserException | URISyntaxException | IOException e) {
            LOGGER.warn("Failed to read {}", pomUrl);
        }
        return null;
    }

    private Map<String, String> getBannedDependencies() {
        Map<String, String> bannedDependencies = new HashMap<>();
        try (InputStream is = new FileInputStream("banned-dependencies.lst");
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(Objects.requireNonNull(is), StandardCharsets.UTF_8))) {

            String line = reader.readLine();
            while (line != null) {
                line = line.trim();
                if (!line.startsWith("#") && !line.isEmpty()) {
                    String[] parts = line.split(";", 2);
                    if (parts.length < 2) {
                        continue;
                    }
                    bannedDependencies.put(parts[0], parts[1]);
                }
                line = reader.readLine();
            }
            return bannedDependencies;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private JenkinsVersion getJenkinsVersion(Model model) {
        Properties props = model.getProperties();
        RegexBasedInterpolator interpolator = new RegexBasedInterpolator();
        interpolator.addValueSource(new MapBasedValueSource(props));
        if (props.containsKey("jenkins.version")) {
            try {
                String version = interpolator.interpolate(props.getProperty("jenkins.version"));
                String baseline = interpolator.interpolate(props.getProperty("jenkins.baseline"));
                if (baseline == null || baseline.isBlank()) {
                    Matcher m = Pattern.compile("(\\d\\.\\d+)(|\\.\\d)").matcher(version);
                    if (m.matches()) {
                        baseline = m.group(1);
                    }
                }
                return new JenkinsVersion(baseline, new Version(version));
            } catch (Exception e) {
                LOGGER.warn("Failed to interpolate jenkins.version", e);
            }
        }
        return null;
    }

    private record JenkinsVersion(String baseline, Version jenkinsVersion) {}

    @SuppressFBWarnings("URLCONNECTION_SSRF_FD")
    private Set<String> getManagedDependenciesFromBom(String artifactId, String bomVersion) {
        try {
            String pomUrl = BOM_ROOT_URL + artifactId + "/" + bomVersion + "/" + artifactId + "-" + bomVersion + ".pom";
            Set<String> dependencies = new HashSet<>();
            URL url = new URI(pomUrl).toURL();
            MavenXpp3Reader reader = new MavenXpp3Reader();
            Model model = reader.read(url.openStream());
            DependencyManagement dependencyManagement = model.getDependencyManagement();
            dependencyManagement.getDependencies().forEach(d -> {
                if ("pom".equals(d.getType()) && d.getArtifactId().startsWith("bom-")) {
                    dependencies.addAll(getManagedDependenciesFromBom(d.getArtifactId(), bomVersion));
                } else {
                    dependencies.add(getDependencyAsString(d, false));
                }
            });
            return dependencies;
        } catch (XmlPullParserException | URISyntaxException | IOException e) {
            LOGGER.warn("Failed to read bom pom");
        }
        return Collections.emptySet();
    }

    private String getDependencyAsString(Dependency d, boolean includeVersion) {
        StringBuilder builder = new StringBuilder();
        builder.append(d.getGroupId()).append(":").append(d.getArtifactId());
        if (includeVersion && d.getVersion() != null) {
            builder.append(":").append(d.getVersion());
        }
        if (d.getClassifier() != null) {
            builder.append(":").append(d.getClassifier());
        }
        return builder.toString();
    }

    @SuppressFBWarnings("URLCONNECTION_SSRF_FD")
    private String getLatestBomVersion(String artifactId) {
        try {
            String metadataUrl = BOM_ROOT_URL + artifactId + "/maven-metadata.xml";
            URL url = new URI(metadataUrl).toURL();
            MetadataXpp3Reader reader = new MetadataXpp3Reader();
            Metadata metadata = reader.read(url.openStream());
            return metadata.getVersioning().getLatest();
        } catch (XmlPullParserException | URISyntaxException | IOException e) {
            LOGGER.warn("Failed to read maven metadata");
        }
        return null;
    }
}
