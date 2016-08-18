
/* Only keep the 10 most recent builds. */
properties([[$class: 'BuildDiscarderProperty',
             strategy: [$class: 'LogRotator', numToKeepStr: '100']]])

try {
    node('linux') {
        stage 'Clean workspace'
        deleteDir()
        sh 'ls -lah'

        stage 'Checkout source'
        checkout scm

        stage 'Build tool'
        def mvnHome = tool 'mvn'
        env.JAVA_HOME = tool 'jdk8'

        stage 'Build'
        sh "${mvnHome}/bin/mvn clean verify"

        stage 'Run Permissions Updater'
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'artifactoryAdmin',
                          usernameVariable: 'ARTIFACTORY_USERNAME', passwordVariable: 'ARTIFACTORY_PASSWORD']]) {
            sh '${JAVA_HOME}/bin/java' +
                    '-DdefinitionsDir=$WORKSPACE/permissions' +
                    '-DartifactoryApiTempDir=$WORKSPACE/json' +
                    '-jar target/repository-permissions-updater-*-bin/repository-permissions-updater.jar'
        }
    }
} catch (Exception e) {
    // TODO handle error
} finally {
    // Mark the archive 'stage'....
    stage 'Archive'
    archive 'permissions/*.yml'
    archive 'json/*.json'

}