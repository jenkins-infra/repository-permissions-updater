<!-- This PR template only applies to permission changes. Ignore it for changes to the tool updating permissions in Artifactory -->

# Description

<!-- fill in description here, this will at least be a link to a GitHub repository, and often also links to hosting request, and @mentioning other committers/maintainers as per the checklist below -->

# Submitter checklist for adding or changing permissions

<!--
Make sure to implement all relevant entries (see section headers to when they apply) and mark them as checked (by replacing the space between brackets with an "x").
-->

### Always

- [ ] Add link to plugin/component Git repository in description above

### When enabling automated releases (cd: true)

- [ ] Add a link to the pull request, which enables continuous delivery for your plugin or component. Don't merge the PR yet.  
In case of changes requested by the hosting team, an open PR facilitates future reviews, without derailing work across multiple PRs.
Follow the [documentation](https://www.jenkins.io/doc/developer/publishing/releasing-cd/) to ensure, your pull request is set up properly.

### When adding new uploaders (this includes newly created permissions files)

- [ ] [Make sure to `@`mention an existing maintainer to confirm the permissions request, if applicable](https://github.com/jenkins-infra/repository-permissions-updater/#requesting-permissions)
- [ ] Use the Jenkins community (LDAP) account name in the YAML file, not the GitHub account name
- [ ] Make sure to `@`mention the users being added so their GitHub account names are known if they require GitHub merge access (see below).
- [ ] [All newly added users have logged in to Artifactory and Jira at least once](https://github.com/jenkins-infra/repository-permissions-updater/#requesting-permissions)

### Reviewer checklist (not for requesters!)

- [ ] Check this if newly added person also needs to be given merge permission to the GitHub repo (please @ the people/person with their GitHub username in this issue as well). If needed, it can be done using an [IRC Bot command](https://jenkins.io/projects/infrastructure/ircbot/#github-repo-management)
- [ ] Check that the `$pluginId Developers` team has `Admin` permissions while granting the access.
- [ ] In the case of plugin adoption, ensure that the Jenkins Jira default assignee is either removed or changed to the new maintainer.
- [ ] If security contacts are changed (this includes add/remove), ping the security officer (currently `@Wadeck`) in this pull request. If an email contact is changed, wait for approval from the security officer.

There are [IRC Bot commands](https://jenkins.io/projects/infrastructure/ircbot/#issue-tracker-management) for it
