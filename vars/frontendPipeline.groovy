#!/usr/bin/groovy

def call() {

    pipeline{
        agent {
            label env.PIPELINE_AGENT ?: 'docker-local-agent'
        }
        options {
            skipDefaultCheckout()
            disableConcurrentBuilds()
            timeout(time: 1, unit: 'HOURS')
            buildDiscarder(
                logRotator(
                    artifactDaysToKeepStr: '',
                    artifactNumToKeepStr: '',
                    daysToKeepStr: '10',
                    numToKeepStr: env.BRANCH_NAME.contains('release') ? '10' : '3'
                )
            )
            ansiColor('xterm')
        }
        environment {
            DEFAULT_SLACK_CHANNEL = '#jenkins-ci'
            RELEASES_SLACK_CHANNEL = '#techy-releases'
            SSH_CREDENTIALS_ID = 'ec2-user-ssh-pubkey'
            TS_DEPLOY_KEY_ID = 'TS_DEPLOY_KEY'
            DATADOG_API_KEY_ID = 'DatadogAPIKey'

            BRANCH_DEV = 'develop'
            BRANCH_REL_STRING = 'release'

            BUILD_ENV_DEV = 'dev'
            BUILD_ENV_STAGE = 'stage'
            BUILD_ENV_PROD = 'prod'

            BUILD_SCRIPT = 'build_scripts/build_zip.sh'
            PACKAGE_SCRIPT = 'build_scripts/build_docker_jenkins.sh'
            DEPLOY_SCRIPT = 'build_scripts/deploy_ebs.sh'

            ARTIFACT_ARCHIVE_PATH = 'deploy/*.zip'
        }
        stages{
            stage('Checkout'){
                steps{
                    script{
                        try {
                            env.BUILD_MESSAGE = 'COMPLETED: '
                            env.SUCCESS_BUILD_MESSAGE = ''
                            env.SUCCESS_DEPLOY_MESSAGE = ''
                            notifySlack(env.DEFAULT_SLACK_CHANNEL, 'STARTED')
                            deleteDir()
                            figlet "Checking  out  Source  Code"
                            final scmVars = checkout(scm)
                            env.GIT_COMMIT = scmVars?.GIT_COMMIT?.substring(0,7)
                            env.GIT_REPO_NAME = scm.getUserRemoteConfigs()[0].getUrl().tokenize('/').last().split("\\.")[0]

                            env.SUCCESS_BUILD_MESSAGE = "Built ${env.GIT_COMMIT} for"
                            env.SUCCESS_DEPLOY_MESSAGE = "Deployed ${env.GIT_COMMIT} to"

                            env.BUILD_MESSAGE += 'Checkout'
                        } catch (Exception e) {
                            env.BUILD_MESSAGE = "${e.toString().minus('hudson.AbortException: ').minus('java.lang.Exception: ')}\n${env.BUILD_MESSAGE}"
                            error("${e.toString().minus('hudson.AbortException: ')}")
                        }
                    }
                }
            }
            stage('Prepare Environment'){
                steps{
                    script{
                        try {
                            figlet "Preparing  Environment"

                            def buildEnv
                            def codeVersion = sh(returnStdout: true, script: "cat package.json  | grep '\"version\":' | sed 's/^.*\\\"version\\\": \\\"\\([a-zA-Z0-9_\\.\\-]*\\)\\\".*\$/\\1/'").trim()

                            env.BUILD_MESSAGE += ', Prepare'

                            if ( env.BRANCH_NAME.contains(env.BRANCH_REL_STRING) ) {                    // Release Branches for Stage and Prod

                                def releaseVersion = env.BRANCH_NAME.toString().minus('release-')
                                if ( releaseVersion != codeVersion ) {
                                    throw new Exception("ABORTED: Release Version (${releaseVersion}) is not the same as code version (${codeVersion}).")
                                }

                                buildEnv = params.ENVIRONMENT ?: env.BUILD_ENV_STAGE                    // Automated Release Builds for Stage, and Manual Release Builds for Prod
                            } else {                                                                    // Non-Release Branches for Dev
                                buildEnv = params.ENVIRONMENT ?: env.BUILD_ENV_DEV                      // Automated Develop, and Manual Non-Develop branch Builds for Dev
                            }

                            if ( params.JOB_TYPE != 'DEPLOY_ONLY' ) {
                                prepareBuildStage(buildEnv, codeVersion).call()
                            }

                            if ( params.JOB_TYPE != 'BUILD_ONLY' ) {
                                prepareDeployStage(buildEnv, params.EBS_ENV, params.EBS_LABEL, codeVersion).call()
                            }

                        } catch (Exception e) {
                            env.BUILD_MESSAGE = "${e.toString().minus('hudson.AbortException: ').minus('java.lang.Exception: ')}\n${env.BUILD_MESSAGE}"
                            error("${e.toString().minus('hudson.AbortException: ')}")
                        }
                    }
                }
            }
        }
        post{
            always{
                script {
                    def buildMessage
                    if (currentBuild.currentResult != 'SUCCESS') {
                        if (env.BUILD_MESSAGE.contains("TIMEOUT") || env.BUILD_MESSAGE.contains("ABORTED")) {
                            buildMessage = env.BUILD_MESSAGE
                            currentBuild.result = 'ABORTED'
                        } else {
                            buildMessage = "${currentBuild.currentResult}: ${env.BUILD_MESSAGE}"
                        }
                    } else {
                        buildMessage = (params.JOB_TYPE=='BUILD_ONLY') ? env.SUCCESS_BUILD_MESSAGE : (params.JOB_TYPE=='DEPLOY_ONLY') ? env.SUCCESS_DEPLOY_MESSAGE : env.SUCCESS_BUILD_MESSAGE + '\n' + env.SUCCESS_DEPLOY_MESSAGE
                    }

                    jobUtils.setBuildDescription(buildMessage)
                    notifyUtils.notifySlack(env.DEFAULT_SLACK_CHANNEL, currentBuild.currentResult)

                    // Wiping Out Current Workspace
                    cleanWs()
                }
            }
        }
    }

}


def prepareBuildStage(String buildEnv, String codeVersion) {

    def deployVersion = env.GIT_REPO_NAME + '-' + codeVersion + '-' + buildEnv + '-' + env.BUILD_NUMBER

    return {
        stage("Build - ${buildEnv}") {
            script {
                withCredentials([string(credentialsId: env.TS_DEPLOY_KEY_ID, variable: 'TS_DEPLOY_KEY'), string(credentialsId: env.DATADOG_API_KEY_ID, variable: 'DATADOG_API_KEY')]) {
                    sshagent(["${env.SSH_CREDENTIALS_ID}"]) {
                        figlet "Building  '${buildEnv}'  Code"
                        sh "${env.BUILD_SCRIPT} ${buildEnv}"
                        figlet "Packaging  '${buildEnv}'  Code"
                        sh "${env.PACKAGE_SCRIPT} ${buildEnv}"
                    }
                }

                addDeployToDashboard(env: "BUILD: ${buildEnv}", buildNumber: "${deployVersion} <==> [${new Date().format('dd-MMM-yy  HH:mm:ss')}]")
                def slackMessage = "BUILT: Successfully built `${deployVersion}` for `${buildEnv}`"
                notifySlack(env.DEFAULT_SLACK_CHANNEL, 'SUCCESS', slackMessage)

                env.BUILD_MESSAGE += ", Built ${buildEnv}"
                env.SUCCESS_BUILD_MESSAGE += " '${buildEnv}' as '${deployVersion}'"

                try {
                    archiveArtifacts artifacts: env.ARTIFACT_ARCHIVE_PATH, defaultExcludes: true, caseSensitive: true, allowEmptyArchive: false, onlyIfSuccessful: false
                } catch (Exception e) {
                    env.BUILD_MESSAGE = "${e.toString().minus('hudson.AbortException: ')}\n${env.BUILD_MESSAGE}"
                    error("${e.toString().minus('hudson.AbortException: ')}")
                }
            }
        }
    }
}

def prepareDeployStage(String buildEnv, String ebsEnv, String ebsLabel, String codeVersion) {

    def deployVersion = env.GIT_REPO_NAME + '-' + codeVersion + '-' + buildEnv + '-' + env.BUILD_NUMBER

    return {
        stage("Deploy - ${buildEnv}") {
            script {
                sshagent(["${env.SSH_CREDENTIALS_ID}"]) {
                    figlet "Deploying  to  '${buildEnv}'"
                    sh "${env.DEPLOY_SCRIPT} '${buildEnv}' '${ebsEnv}' '${ebsLabel}'"
                }

                addDeployToDashboard(env: "DEPLOY: ${buildEnv}", buildNumber: "${deployVersion} <==> [${new Date().format('dd-MMM-yy  HH:mm:ss')}]")
                def slackMessage = "DEPLOYED: `${deployVersion}` is now updated on `${buildEnv}` and will be availble in a few minutes"
                if ( buildEnv != env.BUILD_ENV_DEV ) {
                    notifySlack(env.RELEASES_SLACK_CHANNEL, 'SUCCESS', slackMessage)
                }
                notifySlack(env.DEFAULT_SLACK_CHANNEL, 'SUCCESS', slackMessage)

                env.BUILD_MESSAGE += ", Deployed to ${buildEnv}"
                env.SUCCESS_DEPLOY_MESSAGE += " '${buildEnv}' as '${deployVersion}'"
            }
        }
    }
}

