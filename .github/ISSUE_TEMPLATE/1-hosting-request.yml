name: '🏠 Hosting request'
labels: 'hosting-request'
description: I want to host a plugin or library in the Jenkins organization

body:
  - type: input
    attributes:
      label: Repository URL
      description: |
        URL of the repository to host in the jenkinsci organization
      placeholder: |
        https://github.com/your-org/your-repo-name
    validations:
      required: true
  - type: input
    attributes:
      label: New Repository Name
      description: |
        Name of the repository in the jenkinsci organization on GitHub, plugins should end with `-plugin`, libraries should start with `lib-`
      placeholder: |
        your-cool-plugin
    validations:
      required: true
  - type: textarea
    attributes:
      label: Description
      description: |
        Describe what you want hosted here. Explain how it's different from other components that may be considered similar to this one.
    validations:
      required: true
  - type: textarea
    attributes:
      label: GitHub users to have commit permission
      placeholder: |
        @user1
        @user2
        @user3
    validations:
      required: true
  - type: textarea
    attributes:
      label: Jenkins project users to have release permission
      description: |
        The Jenkins project has it's own identity system, users can sign up at https://accounts.jenkins.io. All users *must* sign in to [Jira](https://issues.jenkins.io) and [Artifactory](https://repo.jenkins-ci.org/).
        The user(s) listed must NOT be mentioned. The Jenkins identity system account name is handled independently of the GitHub user handle.
      placeholder: |
        user1
        user2
        user3
    validations:
      required: true
  - type: dropdown
    attributes:
      label: Issue tracker
      description: Which issue tracker to use. Issues for this component can be tracked on issues.jenkins.io in a component in the JENKINS project, or in the GitHub repository using GitHub issues. Using both at the same time is discouraged.
      options:
        - "Jira"
        - "GitHub issues"
    validations:
      required: true
