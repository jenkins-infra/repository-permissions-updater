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

**Prerequisite**: You need to have logged in once to [Artifactory](https://repo.jenkins-ci.org/) with your Jenkins community account before you can be added to a permissions target.

To request upload permissions to an artifact (typically a plugin), [file a PR](https://help.github.com/articles/creating-a-pull-request/) editing the appropriate YAML file, and provide a reference that shows you have commit permissions, or have an existing committer to the plugin comment on your PR, approving it.

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

### Deprecating a plugin

Remove the YAML file. The next synchronization will remove permissions for the plugin.

### Renaming a plugin

Rename and edit the existing permissions file, changing both `name` and the last `path` component.

### Changing a plugin's `groupId`

Change the `paths` to match the new Maven coordinates, or, if further uploads for the old coordinates are expected, add a new list entry.

Managing Security Process
-------------------------

The Jenkins project acts as a primary contact point for security researchers seeking to report security vulnerabilities in Jenkins and Jenkins plugins ([learn more](https://jenkins.io/security/)).

Through additional metadata in the YAML file described above, you can define who should be contacted in the event of a report being received.
Add a section like the following to your plugin's YAML file:

```yaml
security:
  contacts:
    jira: some_user_name
    email: security@acme.org
```

The above example will result in the Jira issue being assigned to `some_user_name`, and an email notification being sent to `security@acme.org` to establish contact.
Both of `jira` and `email` are optional.

Please note that we generally reject email contacts due to the additional overhead in reaching out via email.
Unless you represent a large organization (e.g. cloud providers) in which most developers would not know how you coordinate security issues, please refrain from requesting to be contacted via email.

Usage
-----

To see how to run this tool to synchronize Artifactory permission targets with the definitions in this repository, see `Jenkinsfile`.

It expects the following System properties to be set:

- `definitionsDir` - Path to directory containing permission definitions YAML files
- `artifactoryApiTempDir` - Path to directory (that will be created) where this tool stores Artifactory permissions API JSON payloads.

It expected the following environment variables to be set:

- `ARTIFACTORY_USERNAME` - Admin user name for Artifactory
- `ARTIFACTORY_PASSWORD` - Corresponding admin password (or API key) for Artifactory admin user

### How It Works

The tool runs three steps in sequence:

1. Generate JSON payloads from YAML permission definition files.
2. Submit generated JSON payloads to Artifactory.
3. Remove all generated permission targets in Artifactory that have no corresponding generated JSON payload file.
