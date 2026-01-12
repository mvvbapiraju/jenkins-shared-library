// What this Jenkinsfile demonstrates:
// - Jenkins Shared Libraries
// - Declarative Pipeline
// - Parameters
// - Environment variables
// - Credentials handling
// - Parallel stages
// - Conditional execution
// - Input approvals
// - Artifact versioning & promotion
// - AWS deployment (ECS-style)
// - Post actions
// - Notifications
// - Rollback strategy

@Library('jenkins-shared-library@main') _

pipeline {

    agent none

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 60, unit: 'MINUTES')
    }

    parameters {
        choice(name: 'ENV', choices: ['dev', 'qa', 'prod'], description: 'Target environment')
        booleanParam(name: 'RUN_SECURITY_SCANS', defaultValue: true, description: 'Run security scans')
        booleanParam(name: 'AUTO_DEPLOY', defaultValue: false, description: 'Auto deploy without approval')
    }

    environment {
        APP_NAME        = 'payments-service'
        AWS_REGION     = 'us-east-1'
        ARTIFACT_REPO  = 'artifactory'
        VERSION        = VersionUtils.generateVersion(env.BUILD_NUMBER)
        AWS_CREDS      = credentials('aws-iam-role-creds')
        SLACK_CHANNEL  = '#deployments'
    }

    stages {

        stage('Checkout') {
            agent { label 'linux' }
            steps {
                checkout scm
            }
        }

        stage('Build & Unit Tests') {
            agent { label 'docker' }
            parallel {

                stage('Build') {
                    steps {
                        buildApp(config: [
                            type: "maven",
                            workDir: ".",
                            stashName: "ws",
                            archivePatterns: "target/*.jar"
                        ])
                    }
                }

                stage('Unit Tests') {
                    steps {
                        runTests(config: [
                            stashName: "ws",
                            workDir: ".",
                            type: "maven",
                            junitPatterns: "**/target/surefire-reports/*.xml"
                        ])
                    }
                }
            }
        }

        stage('Quality & Security') {
            when {
                expression { params.RUN_SECURITY_SCANS }
            }
            agent { label 'docker' }
            parallel {

//                 stage('Static Analysis') {
//                     steps {
//                         runSecurityScans(type: 'sast')
//                     }
//                 }
//
//                 stage('Dependency Scan') {
//                     steps {
//                         runSecurityScans(type: 'sca')
//                     }
//                 }

                stage("Security") {
                    steps {
                        runSecurityScans(config: [
                            stashName: "ws",
                            workDir: ".",
                            scanners: ["trivy"],
                            failOn: "HIGH"
                        ])
                    }
                }
            }
        }

        stage('Package & Publish Artifact') {
            agent { label 'linux' }
            steps {
                publishArtifact(config: [
                    stashName: "ws",
                    workDir: ".",
                    mode: "ecr",
                    dockerfile: "Dockerfile",
                    ecr: [
                        region: "us-east-1",
                        registry: "123456789012.dkr.ecr.us-east-1.amazonaws.com",
                        repository: "my-service",
                        roleArn: "arn:aws:iam::123456789012:role/jenkins-ecr-push"
                    ]
                ])
            }
        }

        stage('Approval Gate') {
            when {
                allOf {
                    environment name: 'ENV', value: 'prod'
                    expression { !params.AUTO_DEPLOY }
                }
            }
            steps {
                input(
                    message: "Approve deployment to PROD?",
                    ok: "Deploy",
                    submitter: "release-managers"
                )
            }
        }

        stage('Deploy (Blue-Green ECS)') {
            agent { label 'aws' }
            steps {
                deployEcsBlueGreen(config: [
                    useSampleResources: true,
                    aws: [
                        region: "us-east-1",
                        roleArn: "arn:aws:iam::123456789012:role/jenkins-codedeploy"
                    ],
                    codedeploy: [
                        applicationName: "my-ecs-app",
                        deploymentGroup: "my-ecs-dg"
                    ],
                    // the image you pushed in publishArtifact (tag or digest)
                    image: env.PUBLISHED_IMAGE,
                    containerName: "app",
                    // recommended revision type
                    revision: [
                        type: "s3",
                        bucket: "my-codedeploy-revisions-bucket",
                        keyPrefix: "revisions/my-service"
                    ]
                ])
            }
        }

        stage('Post-Deploy Validation') {
            agent { label 'linux' }
            steps {
                runTests(
                    type: 'smoke',
                    environment: params.ENV
                )
            }
        }
    }

    post {

        success {
            notify(config: [
                status: "SUCCESS",
                channel: env.SLACK_CHANNEL,
                subjectPrefix: "✅ ${env.APP_NAME} ${env.VERSION} deployed successfully to ${params.ENV}"
            ])
        }

        failure {
            script {
                if (params.ENV == 'prod' || env.CODEDEPLOY_DEPLOYMENT_ID) {
                    rollbackDeployment(config: [
                        aws: [region: "us-east-1", roleArn: "arn:aws:iam::123456789012:role/jenkins-codedeploy"],
                        deploymentId: env.CODEDEPLOY_DEPLOYMENT_ID,
                        mode: "stopAndAutoRollback",
                        events: [ printEvents: true, max: 25 ]
                    ])

// Example usage (Helm rollback + full diagnostics)
//                     rollbackK8s(config: [
//                         mode: "helmRollback",
//                         namespace: "prod",
//                         eks: [
//                             enabled: true,
//                             region: "us-east-1",
//                             clusterName: "fidelity-prod-eks",
//                             roleArn: "arn:aws:iam::123456789012:role/jenkins-eks-admin",
//                             kubeconfigPath: "${env.WORKSPACE}/.kubeconfig"
//                         ],
//                         helm: [
//                             release: "my-service",
//                             // revision: "12",  // optional; auto-detects previous revision if missing
//                             timeoutMinutes: 12
//                         ],
//                         diagnostics: [
//                             enabled: true,
//                             labelSelector: "app=my-service",
//                             container: "app",
//                             maxPods: 5,
//                             maxEvents: 40,
//                             logLines: 250,
//                             includePreviousLogs: true
//                         ]
//                     ])

// Example usage (kubectl rollout undo)
//                     rollbackK8s(config: [
//                         mode: "kubectlUndo",
//                         namespace: "prod",
//                         kubectl: [
//                             kind: "deployment",
//                             name: "my-service",
//                             // toRevision: "7",  // optional
//                             timeoutMinutes: 10
//                         ],
//                         diagnostics: [
//                             enabled: true,
//                             labelSelector: "app=my-service",
//                             container: "app",
//                             logLines: 200
//                         ]
//                     ])
                }
            }
            notify(config: [
                status: "FAILURE",
                channel: env.SLACK_CHANNEL,
                subjectPrefix: "❌ Deployment FAILED for ${env.APP_NAME} ${env.VERSION} in ${params.ENV}"
            ])
        }

        always {
            cleanWs()
        }
    }
}
