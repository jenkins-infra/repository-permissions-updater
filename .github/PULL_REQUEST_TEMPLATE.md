_(This PR template only applies to permission changes -- ignore for changes to the tool)_

# Description

_(fill in description here)_

# Permissions pull request checklist

### Always

- [ ] Add link to plugin/component Git repository in description above

### For a newly hosted plugin only

- [ ] Add link to resolved HOSTING issue in description above

### For a new permissions file only

- [ ] Make sure the file is created in `permissions/` directory
- [ ] `artifactId` (pom.xml) is used for `name` (permissions YAML file).
- [ ] [`groupId` / `artifactId` (pom.xml) are correctly represented in `path` (permissions YAML file)](https://github.com/jenkins-infra/repository-permissions-updater/#managing-permissions)
- [ ] Check that the file is named `plugin-${artifactId}.yml` for plugins

### When adding new uploaders (this includes newly created permissions files)

- [ ] [Make sure to `@`mention an existing maintainer to confirm the permissions request, if applicable](https://github.com/jenkins-infra/repository-permissions-updater/#requesting-permissions)
- [ ] Use the Jenkins community (LDAP) account name in the YAML file, not the GitHub account name
- [ ] [All newly added users have logged in to Artifactory at least once](https://github.com/jenkins-infra/repository-permissions-updater/#requesting-permissions)
