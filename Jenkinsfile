properties([
        [$class: 'BuildDiscarderProperty', strategy: [$class: 'LogRotator', numToKeepStr: '100']],
        [$class: 'PipelineTriggersJobProperty', triggers: [[$class: 'SCMTrigger', spec: 'H/2 * * * *']]]
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
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'artifactoryAdmin',
                          usernameVariable: 'ARTIFACTORY_USERNAME', passwordVariable: 'ARTIFACTORY_PASSWORD']]) {
            sh '${JAVA_HOME}/bin/java' +
                    ' -DdefinitionsDir=$PWD/permissions' +
                    ' -DartifactoryApiTempDir=$PWD/json' +
                    ' -Djava.util.logging.SimpleFormatter.format="%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS %4$s: %5$s%6$s%n"' +
                    ' -jar target/repository-permissions-updater-*-bin/repository-permissions-updater-*.jar'
        }
    } finally {
        stage 'Archive'
        archiveArtifacts 'permissions/*.yml'
        archiveArtifacts 'json/*.json'
    }
}
