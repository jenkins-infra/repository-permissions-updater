
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

        stage 'Build'
        def mvnHome = tool 'mvn'
        env.JAVA_HOME = tool 'jdk8'
        sh "${mvnHome}/bin/mvn clean verify"

        stage 'Run'
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'artifactoryAdmin',
                          usernameVariable: 'ARTIFACTORY_USERNAME', passwordVariable: 'ARTIFACTORY_PASSWORD']]) {
            sh '${JAVA_HOME}/bin/java' +
                    ' -DdefinitionsDir=$WORKSPACE/permissions' +
                    ' -DartifactoryApiTempDir=$WORKSPACE/json' +
                    ' -jar target/repository-permissions-updater-*-bin/repository-permissions-updater.jar'
        }
    }
} finally {
    stage 'Archive'
    archive 'permissions/*.yml'
    archive 'json/*.json'

}