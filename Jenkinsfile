properties([
        [$class: 'BuildDiscarderProperty', strategy: [$class: 'LogRotator', numToKeepStr: '10']],
        [$class: 'PipelineTriggersJobProperty', triggers: [
            [$class: 'SCMTrigger', scmpoll_spec: 'H/2 * * * *', ignorePostCommitHooks: false],
            cron('H/30 * * * *')
        ]]
])

def dryRun = true

/* Exit early if we are executing in a pull request or branch in trusted.ci, until this ticket is resolved:
 * https://issues.jenkins-ci.org/browse/INFRA-902
 */
if (!env.CHANGE_ID && (!env.BRANCH_NAME || env.BRANCH_NAME == 'master') && infra.isTrusted()) {
    dryRun = false
}

node('java') {
    try {
        stage 'Clean'
        deleteDir()
        sh 'ls -lah'

        stage 'Checkout'
        checkout scm

        stage 'Build'
        def mvnHome = tool 'mvn'
        env.JAVA_HOME = tool 'jdk8'
        sh "${mvnHome}/bin/mvn -U clean verify"

        stage 'Run'

        def javaArgs = ' -DdefinitionsDir=$PWD/permissions' +
                       ' -DartifactoryApiTempDir=$PWD/json' +
                       ' -Djava.util.logging.SimpleFormatter.format="%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS %4$s: %5$s%6$s%n"' +
                       ' -jar target/repository-permissions-updater-*-bin/repository-permissions-updater-*.jar'


        if (dryRun) {
            sh '${JAVA_HOME}/bin/java -DdryRun=true' + javaArgs
        } else {
            withCredentials([usernamePassword(credentialsId: 'artifactoryAdmin', passwordVariable: 'ARTIFACTORY_PASSWORD', usernameVariable: 'ARTIFACTORY_USERNAME')]) {
                sh '${JAVA_HOME}/bin/java ' + javaArgs
            }
        }
    } finally {
        stage 'Archive'
        archiveArtifacts 'permissions/*.yml'
        archiveArtifacts 'json/*.json'
    }
}
