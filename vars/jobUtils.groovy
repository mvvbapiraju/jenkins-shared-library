#!/usr/bin/groovy

def setFrontendProperties(Map envMap) {

    def envChoice = (env.BRANCH_NAME == 'release') ? ['stage','prod'] : ['dev']
    properties([
        parameters([
            choice(name: 'JOB_TYPE', choices: "BUILD_ONLY\nDEPLOY_ONLY\nBUILD_AND_DEPLOY", description: "Select an option to proceed with this job. 'develop' and 'release-*' branches can take any of these options, but branches ends with '-feature' are allowed only to build."),
            choice(name: 'ENVIRONMENT', choices: envChoice.join("\n"), description: 'Environment Build Flag'),
            string(name: 'EBS_LABEL', defaultValue: '', description: "REQUIRED: AWS Elastic BeanStalk Resource Label, MUST be provided only when 'DEPLOY_ONLY' JOB_TYPE is selected.<br><br>Eg: vidmob-creator-suite-0.48.0-dev-4 (without '.zip' extension)"),
            string(name: 'EBS_ENV', defaultValue: '', description: "OPTIONAL: AWS Elastic BeanStalk Environment, required only when 'DEPLOY_ONLY' JOB_TYPE is selected. If left blank, default value from application repo will be considered.<br><br>Eg: VidmobCreatorSuite-dev-20201216")
        ])
    ])

    env.BUILD_SCRIPT = envMap.buildScript ?: 'build_scripts/build_zip.sh'
    env.PACKAGE_SCRIPT = envMap.packageScript ?: 'build_scripts/build_docker_jenkins.sh'
    env.DEPLOY_SCRIPT = envMap.deployScript ?: 'build_scripts/deploy_ebs.sh'
    env.ARTIFACT_ARCHIVE_PATH = envMap.artifactArchivePath ?: 'deploy/*.zip'
}

def validateTrigger() {
    // Rejecting unwanted Pipeline Triggers
    def triggerCause = getPipelineTrigger()
    println("Pipeline Trigger Cause: ${triggerCause}")

    if ( ! env.BRANCH_NAME.contains('release') ) {
        // Disables Branch Indexing trigger on develop branch, and allows only Manual Trigger on other branches
        if ( ( env.BRANCH_NAME == 'develop' && triggerCause =~ /(BranchIndexing)/ ) || ( env.BRANCH_NAME != 'develop' && ! triggerCause =~ /(UserId)/ ) ) {
            println("Rejecting unwanted trigger, and maintaining previous build status...")
            usePreviousBuildResult()
            setBuildDescription("IGNORED: Unwanted Trigger Rejected")
            return
        }
    } else {
        if ( !(triggerCause =~ /(BranchEvent|UserId)/) ) {   // Only allow Commit Event and Manual triggers on release branches
            println("Rejecting automated trigger on release branch, and maintaining previous build status...")
            usePreviousBuildResult()
            setBuildDescription("IGNORED: Automated Trigger Rejected")
            return
        }
    }
}

String getPipelineTrigger() {
    def triggerCause = currentBuild.rawBuild.getCauses().toString()
    switch(triggerCause) {
        case ~ /.*UserId.*/:            // Triggered by User
            return 'UserId'
        case ~ /.*BranchEvent.*/:       // Triggered by a Commit
            return 'BranchEvent'
        case ~ /.*TimerTrigger.*/:      // Triggered by Timer
            return 'TimerTrigger'
        case ~ /.*GitHubPush.*/:        // Triggered by PR merges
            return 'GitHubPush'
        case ~ /.*BranchIndexing.*/:    // Triggered by Multi-Branch Branch Indexing
            return 'BranchIndexing'
        default:
            return triggerCause
    }
}

def usePreviousBuildResult() {
    if (currentBuild?.getPreviousBuild()?.result) {
        String previousJobResult =  currentBuild.getPreviousBuild().result.toString()
        println("Setting current build result to previous job result as '${previousJobResult}'")
        currentBuild.result = previousJobResult
    } else {
        println("Previous Job Result was NULL - Setting result to 'ABORTED'")
        currentBuild.result = 'ABORTED'
    }
}

def setBuildDescription(String message) {
    String colour = "LimeGreen"
    if (message?.contains("NOT BUILT") || message?.contains("ABORTED") || message?.contains("IGNORED")) {
        colour = "DimGrey"
    } else if (message?.contains("TIMEOUT") || message?.contains('DECLINED')) {
        colour = "Orange"
    } else if (message?.contains("FAILURE")) {
        colour = "RED"
    }
    currentBuild.description = colour ? "<span style='color:${colour}'>" + message + "</span>" : message
}

