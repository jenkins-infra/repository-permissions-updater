### Security audit, information and commands

The security team is auditing all the hosting requests, to ensure a better security by default.

This message informs you that a [Jenkins Security Scan](https://www.jenkins.io/redirect/jenkins-security-scan/) was triggered on your repository.
It takes ~10 minutes to complete.

<details><summary>Commands</summary>

The bot will parse all comments, and it will check if any line start with a command.

Security team only:
<ul>
    <li><code>/audit-ok</code> => the audit is complete, the hosting can continue :tada:.</li>
    <li><code>/audit-skip</code> => the audit is not necessary, the hosting can continue :tada:.</li>
    <li><code>/audit-findings</code> => the audit reveals some issues that require corrections :pencil2:.</li>
</ul>

Anyone:
<ul>
    <li><code>/request-security-scan</code> => the findings from the <a href="https://www.jenkins.io/redirect/jenkins-security-scan/" rel="nofollow">Jenkins Security Scan</a> were corrected, this command will re-scan your repository :mag:.</li>
    <li><code>/audit-review</code> => the findings from the audit were corrected, this command will ping the security team to review the findings :eyes:.
    It's only applicable when the previous audit required changes.</li>
</ul>

<i>Only one command can be requested per comment.</i>

</details>