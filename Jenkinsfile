properties([
        [$class: 'BuildDiscarderProperty', strategy: [$class: 'LogRotator', numToKeepStr: '10']],
        [$class: 'PipelineTriggersJobProperty', triggers: [
            [$class: 'SCMTrigger', scmpoll_spec: 'H/2 * * * *', ignorePostCommitHooks: false],
            cron('H/30 * * * *')
        ]]
])

/* Exit early if we are executing in a pull request, until this ticket is resolved:
 * https://issues.jenkins-ci.org/browse/INFRA-902
 */
if (env.CHANGE_ID) {
    return
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
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'artifactoryAdmin',
                          usernameVariable: 'ARTIFACTORY_USERNAME', passwordVariable: 'ARTIFACTORY_PASSWORD']]) {
            sh 'target/appassembler/bin/repository-permissions-updater -d $PWD/permissions -w $PWD/json'
        }
    } finally {
        stage 'Archive'
        archiveArtifacts 'permissions/*.yml'
        archiveArtifacts 'json/*.json'
    }
}
