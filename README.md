Repository Permissions Updater
==============================

About
-----

The Jenkins project hosts Maven artifacts such as core and plugin releases on [Artifactory](https://repo.jenkins-ci.org/).

Its permissions system is independent of GitHub's, and we limit which users (identified by the Jenkins LDAP account, same as wiki and JIRA) are allowed to upload which artifacts.

This repository contains both the definitions for Artifactory upload permissions in [YAML format](https://en.wikipedia.org/wiki/YAML), as well as the tool that synchronizes them to Artifactory.

**Note:** These permissions are specifically for _uploading_ artifacts to the Jenkins project's Maven repository. It is independent of GitHub repository permissions. You may have one without the other. Typically, you'll either have both, or just the GitHub repository access.

Requesting Permissions
----------------------

**Prerequisite**: You need to have logged in once to [Artifactory](https://repo.jenkins-ci.org/) and [Jira](https://issues.jenkins.io) with your Jenkins community account (this is the same as the account you would use to login to Jira) before you can be added to a permissions target.

To request upload permissions to an artifact (typically a plugin), [file a PR](https://help.github.com/articles/creating-a-pull-request/) editing the appropriate YAML file, and provide a reference that shows you have commit permissions, or have an existing committer to the plugin comment on your PR, approving it.
See [this page](https://jenkins.io/doc/developer/plugin-governance/managing-permissions/) for more information.

Managing Permissions
--------------------

The directory `permissions/` contains a set of files, one per plugin or artifact, that define the permissions for the respective artifacts. Files typically have a `component`, `plugin`, or `pom` prefix for organization purposes:

* `plugin` is used for Jenkins plugins.
* `pom` is used for parent POMs and everything else consisting of just a POM file.
* `component` is used for everything else, usually libraries.

These prefixes, like the rest of the file name, have no semantic meaning and just help in organizing these files. 

Each file contains the following in [YAML format](https://en.wikipedia.org/wiki/YAML):

- A `name` (typically mirrored in the file name), this is also the `artifactId` of the Maven artifact.
- A `github` field indicating the GitHub organization and repository which is expected to produce these artifacts.
- A set of paths, usually just one. These correspond to the full Maven coordinates (`groupId` and `artifactId`) used for the artifact. Since Jenkins plugins can change group IDs and are still considered the same artifact, multiple entries are possible.
- A set of user names (Jenkins community user accounts in LDAP, the same as used for wiki and JIRA) allowed to upload this artifact to Artifactory. This set can be empty, which means nobody is currently allowed to upload the plugin in question (except Artifactory admins). This can happen for plugins that haven't seen releases in several years, or permission cleanups.

Example file:

```yaml
---
name: "p4"
github: "jenkinsci/p4-plugin"
paths:
  - "org/jenkins-ci/plugins/p4"
developers:
  - "p4paul"
```

* `p4` (lines 2 and 5): `artifactId`
* `p4-plugin` (line 3): GitHub repository name
* `org/jenkins-ci` (line 5): `groupId` (with slashes replacing periods)
* `p4paul` (line 7): Jenkins community account user name

### Adding a new plugin

Create a new YAML file similar to existing files.

### Adding a new uploader to an existing plugin

Edit the `developers` list in the YAML file for the plugin.

### Remove uploaders from an existing plugin

Remove entries from the `developers` list.

### Deprecating a plugin

See [the documentation on jenkins.io](https://www.jenkins.io/doc/developer/plugin-governance/deprecating-or-removing-plugin/).
Do not delete YAML files from this repository.

### Changing plugin ID

Jenkins cannot handle plugin renames, so if a release of the plugin has been published already, it's not possible to rename it.
A plugin can theoretically be replaced by a new one with a different ID, but this is difficult to get right (e.g., when both plugins are installed at the same time).

If the plugin _hasn't_ been released yet, you can just rename and edit the existing permissions file, changing the `name` component.
You may also edit the `github` component, if you wish to rename the repository.

### Changing a plugin's `groupId`

Changing the `paths` or modifying the `<artifactId>` in the plugin `pom.xml` is highly discouraged.  
Modifying the path will break any Maven dependencies from other plugins.
Altering the `artifactId` means changing the identifier by which the Jenkins plugin manager differentiates one plugin from others, and will cause chaos for users who have already installed it under the old name.

Managing Continuous Delivery (JEP-229 CD)
-----------------------------------------

Jenkins plugins and other components can be continuously delivered through a supported process described in [JEP-229](https://github.com/jenkinsci/jep/blob/master/jep/229/README.adoc).

You can enable JEP-229 CD for your component by adding the following to your component's YAML file:

```yaml
cd:
  enabled: true
```

**IMPORTANT:**
When using JEP-229 CD, [every committer to your repository](https://www.jenkins.io/doc/developer/publishing/source-code-hosting/) can create new releases by merging pull requests.
As a result, the list of maintainer accounts maintained in your plugin's YAML file is no longer the single reference on who can publish new releases.
Be sure to check [which users have commit access](https://www.jenkins.io/doc/developer/publishing/source-code-hosting/) to your repository and remove any that are unexpected before enabling CD, as well as any unexpected [deploy keys](https://docs.github.com/en/developers/overview/managing-deploy-keys).
Additionally, the users listed in this repository still serve as the contacts for security issues and plugin/component governance questions.
In particular, the Jenkins security team will _not_ make an effort to reach out to GitHub committers when maintainers (and security contacts, see below) are unresponsive before [announcing vulnerabilities without a fix](https://www.jenkins.io/security/plugins/#unresolved).


Managing Security Process
-------------------------

The Jenkins project acts as a primary contact point for security researchers seeking to report security vulnerabilities in Jenkins and Jenkins plugins ([learn more](https://jenkins.io/security/)).

We generally assign reported issues to maintainers after a basic sanity check ([learn more](https://www.jenkins.io/security/for-maintainers/)).
Through additional metadata in the YAML file described above, you can define who else should be contacted in the event of a report being received and is authorized to make decisions regarding security updates, e.g. scheduling.
Add a section like the following to your plugin's YAML file:

```yaml
security:
  contacts:
    jira: some_user_name
```

Given the above example, we will primarily assign any security issue in Jira to `some_user_name`.
Regular maintainers are added to the issue as well to give visibility and allow participation/discussion.
This means that specifying a Jira security contact is only useful when it's an account not already listed as maintainer.
`jira` is optional.

If you represent a company with dedicated security team that needs to be involved, we recommend you to create a Jira account backed by a shared email.

Managing Issue Trackers
-----------------------

The YAML metadata files in this repository also hold information about issue trackers.
This is used to make issue trackers more accessible, e.g. by adding them to the UI of Jenkins or on https://plugins.jenkins.io.

### Declaring Issue Trackers

The top-level `issues` key contains a sorted list of issue tracker references in descending order of preference.

For GitHub issues, a GitHub repository must be specified as the value for the `github` key, and the value must start with `jenkinsci/`, followed by the repository name.
For Jira, a component name or ID must be specified as the value for the `jira` key.
The component name is easier to read, while the component ID is resilient in case of component renames.

Either kind of issue tracker supports the `report:` boolean value that controls whether new issues should be reported in this issue tracker.
The default is `true`.

A complete example with two trackers:

```yaml
issues:
  - github: 'jenkinsci/configuration-as-code-plugin' # The preferred issue tracker
  - jira: 'configuration-as-code-plugin' # A secondary issue tracker is the Jira component 'configuration-as-code-plugin'
    report: false # No new issues should be reported here
```

When GitHub Issues is used, there would be some duplicated content in the file (between `github` and `issues` entries) which can be resolved by using a YAML reference.
Example:

```yaml
github: &GH 'jenkinsci/configuration-as-code-plugin' # Declare a reference
issues:
  - github: *GH # Use the reference
```

### Consuming Issue Trackers

A file `issues.index.json` is generated when the tool is executed, containing a map from component names to a list of issue trackers.
Only plugins are expected to specify an issue tracker here.

If a plugin does not have a corresponding key in this map, the tool did not consider it for inclusion.
If a plugin has a corresponding key in this map but an empty list of issue trackers, no issue trackers are known or supported.

Each issue tracker entry will have the following keys:

* `type`: Currently `jira` (meaning issues.jenkins.io) or `github` (meaning GitHub issues)
* `reference` contains a `type`-specific identifier string that provides additional information how issues are tracked; for Jira it is the component name or ID and for GitHub Issues it is the `orgname/reponame` String.
* `viewUrl` is a URL to a human-readable overview page. This value may not exist if no valid URL could be determined.
* `reportUrl` is a URL where users can report issues. This value may not exist if no valid URL could be determined, or new issues should not be reported in this tracker.

The list is sorted in descending order of preference.
The first issue tracker in the list with a `reportUrl` should be presented as the primary (or only) option for reporting issues.
Further issue trackers are mostly provided as a reference, e.g. when listing existing issues, although a different issue tracker with `reportUrl` can be linked if users provide a preference for a specific kind of issue tracker.

Usage
-----

To see how to run this tool to synchronize Artifactory permission targets with the definitions in this repository, see `Jenkinsfile`.

The following Java system properties can be used to customize the tool's behavior:

* `dryRun` - Set to `true` to generate the API payloads without submitting them. No modifications will be executed.
* `development` - Set to `true` during tool development to ensure production data is not overridden. This will have the following effects:
  - Permissions are only granted to deploy to the `snapshots` repository (rather than both `snapshots` and `releases`)
  - A different, non-colliding set of prefixes (unless overridden, see below) is used.
* `definitionsDir` - Path to directory containing permission definitions YAML files, defaults to `./permissions`.
* `artifactoryUserNamesJsonListUrl` - URL to a list containing known Artifactory user names, any permissions assigned to a user not on that list will cause the tool to abort
* `artifactoryApiTempDir` - Path to directory (that will be created) where this tool stores Artifactory permissions API JSON payloads, defaults to `./json`.
* `artifactoryObjectPrefix` - Override the prefix for groups and permission targets managed (created, updated, removed) using the tool.
  If unspecified, the value will be `generatedv2-` by default, or `generateddev-` in _development mode_.
* `artifactoryUrl` - URL to Artifactory, defaults to `https://repo.jenkins-ci.org`
* `artifactoryTokenMinutesValid` - How long authentication tokens to Artifactory for CD enabled repos should be valid for, default `240` (4 hours).
  Regular script execution frequency needs to be aligned with this.
* `gitHubSecretNamePrefix` - Prefix for secrets sent to GitHub repos.
  If unspecified, the value will be `MAVEN_` by default, or `DEV_MAVEN_` in _development mode_.
* `jiraUserNamesJsonListUrl` - URL to a list containing known Jira user names of (potential) maintainers.
  This is essentially a workaround to reduce the number of individual user lookups via Jira API.

It expected the following environment variables to be set:

- `ARTIFACTORY_USERNAME` - Admin user name for Artifactory
- `ARTIFACTORY_PASSWORD` - Corresponding admin password (or API key) for Artifactory admin user
- `GITHUB_USERNAME` - GitHub user name for a user with admin access to any CD enabled repos
- `GITHUB_TOKEN` - Corresponding token for the user with admin access to any CD enabled repos, [requires `repo` scope to create/update secrets](https://docs.github.com/en/free-pro-team@latest/rest/reference/actions#create-or-update-a-repository-secret)
- `JIRA_USERNAME` - User name (does not need admin permissions) for Jira
- `JIRA_PASSWORD` - Corresponding password for Jira user

### How It Works

The tool runs three steps in sequence:

1. Generate JSON payloads from YAML permission definition files.
2. Submit generated JSON payloads to Artifactory.
3. Remove all generated permission targets in Artifactory that have no corresponding generated JSON payload file.
