properties([
        [$class: 'BuildDiscarderProperty', strategy: [$class: 'LogRotator', numToKeepStr: '10']],
        [$class: 'PipelineTriggersJobProperty', triggers: [
            [$class: 'SCMTrigger', scmpoll_spec: 'H/2 * * * *', ignorePostCommitHooks: false],
            cron('H/30 * * * *')
        ]]
])

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
        if (env.CHANGE_ID) {
            sh 'target/appassembler/bin/repository-permissions-updater -o -d $PWD/permissions -w $PWD/json'
        } else {
            withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'artifactoryAdmin',
                              usernameVariable: 'ARTIFACTORY_USERNAME', passwordVariable: 'ARTIFACTORY_PASSWORD']]) {
                sh 'target/appassembler/bin/repository-permissions-updater -d $PWD/permissions -w $PWD/json'
            }
        }
    } finally {
        stage 'Archive'
        if (!env.CHANGE_ID) {
            archiveArtifacts 'permissions/*.yml'
            archiveArtifacts 'json/*.json'
        }
    }
}
