package io.jenkins.infra.repository_permissions_updater.hosting;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.License;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.Scm;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.jenkins.infra.repository_permissions_updater.hosting.HostingChecker.LOWEST_JENKINS_VERSION;
import static java.util.regex.Pattern.CASE_INSENSITIVE;

public class MavenVerifier implements BuildSystemVerifier {
    private static final int MAX_LENGTH_OF_GROUP_ID_PLUS_ARTIFACT_ID = 100;
    private static final int MAX_LENGTH_OF_ARTIFACT_ID = 37;
    private static final Logger LOGGER = LoggerFactory.getLogger(MavenVerifier.class);

    public static final Version LOWEST_PARENT_POM_VERSION = new Version(4, 50);
    public static final Version PARENT_POM_WITH_JENKINS_VERSION = new Version(2);

    public static final String INVALID_POM = "The pom.xml file in the root of the origin repository is not valid";
    public static final String SPECIFY_LICENSE = "Please specify a license in your pom.xml file using the &lt;licenses&gt; tag. See https://maven.apache.org/pom.html#Licenses for more information.";
    public static final String MISSING_POM_XML = "No pom.xml found in root of project, if you are using a different build system, or this is not a plugin, you can disregard this message";

    public static final String SHOULD_BE_IO_JENKINS_PLUGINS = "The &lt;groupId&gt; from the pom.xml should be `io.jenkins.plugins` instead of `org.jenkins-ci.plugins`";

    @Override
    public void verify(HostingRequest issue, HashSet<VerificationMessage> hostingIssues) throws IOException {
        GitHub github = GitHub.connect();
        String forkTo = issue.getNewRepoName();
        String forkFrom = issue.getRepositoryUrl();

        if(StringUtils.isNotBlank(forkFrom)) {
            Matcher m = Pattern.compile("(?:https://github\\.com/)?(\\S+)/(\\S+)", CASE_INSENSITIVE).matcher(forkFrom);
            if(m.matches()) {
                String owner = m.group(1);
                String repoName = m.group(2);

                GHRepository repo = github.getRepository(owner+"/"+repoName);
                try {
                    GHContent pomXml = repo.getFileContent("pom.xml");
                    if(pomXml != null && pomXml.isFile()) {
                        InputStream contents = pomXml.read();
                        MavenXpp3Reader reader = new MavenXpp3Reader();
                        Model model = reader.read(contents);

                        try {
                            checkArtifactId(model, forkTo, hostingIssues);
                            checkParentInfoAndJenkinsVersion(model, hostingIssues);
                            checkName(model, hostingIssues);
                            checkLicenses(model, hostingIssues);
                            checkGroupId(model, hostingIssues);
                            checkRepositories(model, hostingIssues);
                            checkPluginRepositories(model, hostingIssues);
                            checkSoftwareConfigurationManagementField(model, hostingIssues);
                        } catch(Exception e) {
                            LOGGER.error("Failed looking at pom.xml", e);
                            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, INVALID_POM));
                        }
                    }
                } catch(GHFileNotFoundException e) {
                    hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.WARNING, MISSING_POM_XML));
                } catch(XmlPullParserException e) {
                    hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, INVALID_POM));
                }
            } else {
                hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, HostingChecker.INVALID_FORK_FROM, forkFrom));
            }
        }
    }

    @Override
    public boolean hasBuildFile(HostingRequest issue) throws IOException {
        return HostingChecker.fileExistsInRepo(issue, "pom.xml");
    }

    private void checkArtifactId(Model model, String forkTo, HashSet<VerificationMessage> hostingIssues) {
        try {
            if(StringUtils.isBlank(forkTo)) {
                hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "Missing value in Jira for 'New Repository Name' field"));
            }

            String groupId = model.getGroupId();
            String artifactId = model.getArtifactId();
            if(StringUtils.isNotBlank(artifactId)) {
                if(StringUtils.isNotBlank(forkTo) && !artifactId.equals(forkTo.replace("-plugin", ""))) {
                    hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The 'artifactId' from the pom.xml (`%s`) is incorrect, it should be `%s` ('New Repository Name' field with \"-plugin\" removed)", artifactId, forkTo.replace("-plugin", "")));
                }

                if (artifactId.length() >= MAX_LENGTH_OF_ARTIFACT_ID) {
                    hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The 'artifactId' `%s` from the pom.xml is incorrect, it must have less than %d characters, currently it has %d characters", artifactId, MAX_LENGTH_OF_ARTIFACT_ID, artifactId.length()));
                }
                
                int lengthOfGroupIdAndArtifactId = groupId.length() + artifactId.length();
                if (lengthOfGroupIdAndArtifactId >= MAX_LENGTH_OF_GROUP_ID_PLUS_ARTIFACT_ID) {
                    hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The 'artifactId' `%s` and 'groupId' `%s` from the pom.xml is incorrect, combined they must have less than %d characters, currently they have %d characters", artifactId, groupId, MAX_LENGTH_OF_GROUP_ID_PLUS_ARTIFACT_ID, lengthOfGroupIdAndArtifactId));
                }

                if(artifactId.toLowerCase().contains("jenkins")) {
                    hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The 'artifactId' from the pom.xml (`%s`) should not contain \"Jenkins\"", artifactId));
                }

                if(!artifactId.toLowerCase().equals(artifactId)) {
                    hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The 'artifactId' from the pom.xml (`%s`) should be all lower case", artifactId));
                }
            } else {
                hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The pom.xml file does not contain a valid 'artifactId' for the project"));
            }
        } catch(Exception e) {
            LOGGER.error("Error trying to access artifactId", e);
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, INVALID_POM));
        }
    }

    private void checkGroupId(Model model, HashSet<VerificationMessage> hostingIssues) {
        try {
            String groupId = model.getGroupId();
            if(StringUtils.isNotBlank(groupId)) {
                if(groupId.equals("org.jenkins-ci.plugins")) {
                    hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, SHOULD_BE_IO_JENKINS_PLUGINS));
                }
            } else {
                Parent parent = model.getParent();
                if(parent != null) {
                    groupId = parent.getGroupId();
                    if(StringUtils.isNotBlank(groupId)) {
                        if(groupId.equals("org.jenkins-ci.plugins")) {
                            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "You must add a 'groupId' in your pom.xml with the value `io.jenkins.plugins`."));
                        }
                    }
                }
            }
        } catch(Exception e) {
            LOGGER.error("Error trying to access groupId", e);
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, INVALID_POM));
        }
    }

    private void checkName(Model model, HashSet<VerificationMessage> hostingIssues) {
        try {
            String name = model.getName();
            if(StringUtils.isNotBlank(name)) {
                if(name.toLowerCase().contains("jenkins")) {
                    hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The 'name' field in the pom.xml should not contain \"Jenkins\""));
                }
            } else {
                hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The pom.xml file does not contain a valid `<name>` for the project"));
            }
        } catch(Exception e) {
            LOGGER.error("Error trying to access <name>", e);
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, INVALID_POM));
        }
    }

    public static String getArtifactId(String contents) {
        String res;
        MavenXpp3Reader reader = new MavenXpp3Reader();
        try(StringReader stringReader = new StringReader(contents)) {
            Model model = reader.read(stringReader);
            res = model.getArtifactId();
        } catch(XmlPullParserException | IOException e) {
            res = null;
        }
        return res;
    }

    public static String getGroupId(String contents) {
        String res;
        MavenXpp3Reader reader = new MavenXpp3Reader();
        try(StringReader stringReader = new StringReader(contents)) {
            Model model = reader.read(stringReader);
            res = model.getGroupId();
        } catch(XmlPullParserException | IOException e) {
            res = null;
        }
        return res;
    }

    private void checkParentInfoAndJenkinsVersion(Model model, HashSet<VerificationMessage> hostingIssues) {
        try {
            Parent parent = model.getParent();
            if(parent != null) {
                String groupId = parent.getGroupId();
                if(StringUtils.isNotBlank(groupId) && !groupId.equals("org.jenkins-ci.plugins")) {
                    hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The groupId for your parent pom is not \"org.jenkins-ci.plugins,\" if this is not a plugin hosting request, you can disregard this notice."));
                }

                String version = parent.getVersion();
                if(StringUtils.isNotBlank(version)) {
                    Version parentVersion = new Version(version);
                    if(parentVersion.compareTo(LOWEST_PARENT_POM_VERSION) < 0) {
                        hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The parent pom version '%s' should be at least '%s' or higher.", parentVersion, LOWEST_PARENT_POM_VERSION));
                    }

                    if(parentVersion.compareTo(PARENT_POM_WITH_JENKINS_VERSION) >= 0) {
                        Version jenkinsVersion = null;
                        if(model.getProperties().containsKey("jenkins.version")) {
                            jenkinsVersion = new Version(model.getProperties().get("jenkins.version").toString());
                        }

                        if(jenkinsVersion != null && jenkinsVersion.compareTo(LOWEST_JENKINS_VERSION) < 0) {
                            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "Your baseline specified does not meet the minimum Jenkins version required, please update `<jenkins.version>%s</jenkins.version>` to at least %s in your pom.xml. Take a look at the [baseline recommendations](https://www.jenkins.io/doc/developer/plugin-development/choosing-jenkins-baseline/#currently-recommended-versions).",
                                    jenkinsVersion, LOWEST_JENKINS_VERSION));
                        }
                    }
                }
            }
        } catch(Exception e) {
            LOGGER.error("Error trying to access the <parent> information", e);
        }
    }

    private void checkLicenses(Model model, HashSet<VerificationMessage> hostingIssues) {
        // first check the pom.xml
        List<License> licenses = model.getLicenses();
        if(licenses.isEmpty()) {
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, SPECIFY_LICENSE));
        }
    }

    private void checkRepositories(Model model, HashSet<VerificationMessage> hostingIssues) {
        for(Repository r : model.getRepositories()) {
            if(r.getUrl().contains("repo.jenkins-ci.org") || r.getId().contains("repo.jenkins-ci.org")) {
                try {
                    URI uri = new URI(r.getUrl());
                    if(!uri.getScheme().equals("https")) {
                        hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "You MUST use an https:// scheme in your pom.xml for the `<repository><url></url></repository>` tag for repo.jenkins-ci.org"));
                    }
                } catch (URISyntaxException e) {
                    hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The `<repository><url></url></repository>` in your pom.xml for 'repo.jenkins-ci.org' has an invalid URL"));
                }
            }
        }
    }

    private void checkPluginRepositories(Model model, HashSet<VerificationMessage> hostingIssues) {
        for(Repository r : model.getPluginRepositories()) {
            if(r.getUrl().contains("repo.jenkins-ci.org") || r.getId().contains("repo.jenkins-ci.org")) {
                try {
                    URI uri = new URI(r.getUrl());
                    if(!uri.getScheme().equals("https")) {
                        hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "You MUST use an https:// scheme in your pom.xml for the `<pluginRepository><url></url></pluginRepository>` tag for repo.jenkins-ci.org"));
                    }
                } catch (URISyntaxException e) {
                    hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The `<pluginRepository><url></url></pluginRepository>` in your pom.xml for 'repo.jenkins-ci.org' has an invalid URL"));
                }
            }
        }
    }

    private void checkSoftwareConfigurationManagementField(Model model, HashSet<VerificationMessage> hostingIssues) {
        if (model.getScm() == null) {
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "You must specify an `<scm>` block in your pom.xml. See https://maven.apache.org/pom.html#SCM for more information."));
        } else {
            if (model.getScm().getConnection() != null && ((model.getScm().getConnection().startsWith("scm:git:git:")) || model.getScm().getConnection().startsWith("scm:git:http:"))) {
                hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "You must use HTTPS for the `<connection>` tag in your `<scm>` block in your pom.xml. You can use this sample: `<connection>scm:git:https://github.com/jenkinsci/${project.artifactId}-plugin.git</connection>`"));
            } else if (model.getScm().getConnection() == null) {
                hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "You must specify a `<connection>` tag in your `<scm>` block in your pom.xml. You can use this sample: `<connection>scm:git:https://github.com/jenkinsci/${project.artifactId}-plugin.git</connection>`"));
            }
            if (model.getScm().getUrl() == null) {
                hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "You must specify an `<url>` tag in your `<scm>` block in your pom.xml. You can use this sample: `<url>scm:git:https://github.com/jenkinsci/${project.artifactId}-plugin</url>`"));
            }
            if (model.getScm().getDeveloperConnection() == null) {
                hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "You must specify a `<developerConnection>` tag in your <scm> block in your pom.xml. You can use this sample: `<developerConnection>scm:git:git@github.com:jenkinsci/${project.artifactId}-plugin.git</developerConnection>`"));
            }
            if (model.getScm().getTag() == null) {
                hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "You must specify a `<tag>` tag in your `<scm>` block in your pom.xml. You can use this sample: `<tag>${scmTag}</tag>`"));
            }
        }
    }
}
