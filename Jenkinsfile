def props = [
        buildDiscarder(logRotator(numToKeepStr: '10'))
]

def triggers = []

def dryRun = true

if (!env.CHANGE_ID && (!env.BRANCH_NAME || env.BRANCH_NAME == 'master')) {
    if (infra.isTrusted()) {
        // only on trusted.ci, running on master is not a dry-run
        dryRun = false

        // Check for code change every 5 minutes as there are no webhooks on trusted.ci.jenkins.io
        // The goal is to run RPU as soon as possible for any code change
        triggers += pollSCM('H/5 * * * *')
        // Run every 3 hours
        triggers += cron('H H/3 * * *')
    } else {
        // elsewhere, it still should get built periodically
        // apparently this spikes load on Artifactory pretty badly, so don't run often
        triggers += cron('H H * * *')
    }
}

props += pipelineTriggers(triggers)

properties(props)

node('maven-25 ') {
    try {
        stage('Clean') {
            deleteDir()
            sh 'ls -lah'
        }

        stage('Checkout') {
            checkout scm
        }

        stage('Build') {
            sh "mvn -U -B -ntp clean verify"
        }

        stage('Run') {
            def javaArgs = ' -DdefinitionsDir=$PWD/permissions' +
                    ' -DartifactoryApiTempDir=$PWD/json' +
                    ' -DartifactoryUserNamesJsonListUrl=https://reports.jenkins.io/artifactory-ldap-users-report.json' +
                    ' -Djava.util.logging.SimpleFormatter.format="%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS %4$s: %5$s%6$s%n"' +
                    ' -jar target/repository-permissions-updater-*-bin/repository-permissions-updater-*.jar' +
                    ' sync'


            if (dryRun) {
                try {
                    sh 'java -DdryRun=true' + javaArgs
                } catch (ignored) {
                    if (fileExists('checks-title.txt')) {
                        def title = readFile file: 'checks-title.txt', encoding: 'utf-8'
                        def summary = readFile file: 'checks-details.txt', encoding: 'utf-8'
                        publishChecks conclusion: 'ACTION_REQUIRED',
                                name: 'Validation',
                                summary: summary,
                                title: title
                    }
                    throw ignored
                }
                publishChecks conclusion: 'SUCCESS',
                        name: 'Validation',
                        title: 'All checks passed'
            } else {
                withCredentials([
                        string(credentialsId: 'artifactoryAdminToken', variable: 'ARTIFACTORY_TOKEN'),
                        usernamePassword(credentialsId: 'jenkins-infra-bot-github-token', passwordVariable: 'GITHUB_TOKEN', usernameVariable: 'GITHUB_USERNAME')
                ]) {
                    retry(conditions: [agent(), nonresumable()], count: 2) {
                        sh 'java ' + javaArgs
                    }
                }
            }
        }
    } finally {
        stage('Archive') {
            archiveArtifacts 'permissions/*.yml'
            archiveArtifacts 'json/*.json'
            if (infra.isTrusted()) {
                dir('json') {
                    publishReports(['issues.index.json', 'maintainers.index.json', 'github.index.json'], [useWorkloadIdentity: true])
                }
            }
        }
    }
}
