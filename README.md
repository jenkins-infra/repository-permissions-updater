Repository Permissions Updater
==============================

About
-----

The Jenkins project hosts Maven artifacts such as core and plugin releases on [Artifactory](https://repo.jenkins-ci.org/).

Its permissions system is independent of GitHub's, and we limit which users (identified by the Jenkins LDAP account, same as wiki and JIRA) are allowed to upload which artifacts.

This repository contains both the definitions for Artifactory upload permissions in YAML format, as well as the tool that synchronizes them to Artifactory.

**Note:** These permissions are specifically for _uploading_ artifacts to the Jenkins project's Maven repository. It is independent of GitHub repository permissions. You may have one without the other. Typically, you'll either have both, or just the GitHub repository access.

**To request upload permissions to an artifact (typically a plugin), file a PR editing the appropriate YAML file, and provide a reference that shows you have commit permissions, or have an existing committer to the plugin comment on your PR.**

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

Managing Permissions
--------------------

The directory `permissions/` contains a set of files, one per plugin or artifact, that define the permissions for the respective artifacts. Files have a `component` or `plugin` prefix for organization purposes.

Each file contains the following:

- A `name` (also mirrored in the file name), this is also the `artifactId` of the Maven artifact.
- A set of paths, usually just one. These are the group IDs used for the artifact. Since Jenkins plugins can change group IDs and are still considered the same artifact, multiple entries are possible.
- A set of user names (Jenkins community user accounts in LDAP) allowed to upload this artifact to Artifactory. This set can be empty, which means nobody is currently allowed to upload the plugin in question (except Artifactory admins). This can happen for plugins that haven't seen releases in several years, or permission cleanups.

### Adding a new plugin

Create a new YAML file similar to existing files.

### Adding a new uploader to an existing plugin

Edit the `developers` list in the YAML file for the plugin.

### Deprecating a plugin

Remove the YAML file.

### Renaming a plugin

Rename and edit the existing one.
