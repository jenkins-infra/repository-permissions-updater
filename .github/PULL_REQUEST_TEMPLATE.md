# Link to GitHub repository

<!-- Provide a link to the plugin or component repository you want to modify -->

# When modifying release permission

If you are modifying the release permission of your plugin or component, fill out the following checklist:

<!-- If you're enabling CD only, leave the following checklist blank! -->

```[tasklist]
### Release permission checklist (for submitters)
- [ ] The usernames of the users added to the "developers" section in the .yml file are the same the users use to log in to [accounts.jenkins.io](https://accounts.jenkins.io/).
- [ ] All users added have logged in to [Artifactory](https://repo.jenkins-ci.org/) and [Jira](https://issues.jenkins.io/) once.
- [ ] I have mentioned an [existing team member](https://github.com/orgs/jenkinsci/teams) of the plugin or component team to approve this request.
```

## When enabling automated releases (cd: true)

Follow the [documentation](https://www.jenkins.io/doc/developer/publishing/releasing-cd/) to ensure, your pull request is set up properly. Don't merge it yet.  
In case of changes requested by the hosting team, an open PR facilitates future reviews, without derailing work across multiple PRs.

### Link to the PR enabling CD in your plugin

<!-- Provide a link to the pull request containing the necessary changes in your plugin -->

```[tasklist]
### CD checklist (for submitters)
- [ ] I have provided a link to the pull request in my plugin, which enables CD according to the documentation. 
```

```[tasklist]
### Reviewer checklist
- [ ] Check that the `$pluginId Developers` team has `Admin` permissions while granting the access.
- [ ] In the case of plugin adoption, ensure that the Jenkins Jira default assignee is either removed or changed to the new maintainer.
- [ ] If security contacts are changed (this includes add/remove), ping the security officer (currently `@Wadeck`) in this pull request. If an email contact is changed, wait for approval from the security officer.
```
There are [IRC Bot commands](https://jenkins.io/projects/infrastructure/ircbot/#issue-tracker-management) for it.
