def props = [
        buildDiscarder(logRotator(numToKeepStr: '10'))
]

def triggers = [
        pollSCM('H/2 * * * *')
]

def dryRun = true

if (!env.CHANGE_ID && (!env.BRANCH_NAME || env.BRANCH_NAME == 'master')) {
    if (infra.isTrusted()) {
        // only on trusted.ci, running on master is not a dry-run
        dryRun = false
    }
    // elsewhere, it still should get built periodically
    // apparently this spikes load on Artifactory pretty badly, so don't run often
    triggers += cron('H H * * *')
}

props += pipelineTriggers(triggers)

properties(props)


node('java') {
    try {
        stage ('Clean') {
            deleteDir()
            sh 'ls -lah'
        }

        stage ('Checkout') {
            checkout scm
        }

        stage ('Build') {
            def mvnHome = tool 'mvn'
            env.JAVA_HOME = tool 'jdk8'
            sh "${mvnHome}/bin/mvn -U clean verify"
        }

        stage ('Run') {
            if (dryRun) {
                sh './update-permissions.sh'
            } else {
                withCredentials([usernamePassword(credentialsId: 'artifactoryAdmin', passwordVariable: 'ARTIFACTORY_PASSWORD', usernameVariable: 'ARTIFACTORY_USERNAME')]) {
                    sh './update-permissions.sh -f'
                }
            }
        }
    } finally {
        stage ('Archive') {
            archiveArtifacts 'permissions/*.yml'
            archiveArtifacts 'json/*.json'
        }
    }
}
