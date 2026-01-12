#!/usr/bin/groovy
package pipeline

def notifySlack(String channelName=env.DEFAULT_SLACK_CHANNEL, String notifyStatus='', String addMessage='' ) {
    // Build status of null means success
    def buildStatus = notifyStatus ?: 'SUCCESS'

    def color

    def buildCause = currentBuild.getBuildCauses()[0].shortDescription

    def changeSetMsg = "\n\nChanges:\n" + getChangeString()

    if ( buildStatus == 'STARTED' ) {
        color = '#D4DADF'       // Gray
        changeSetMsg = ''
    } else if (buildStatus == 'SUCCESS') {
        color = 'good'          // Green - '#BDFFC3'
    } else if (buildStatus == 'ABORTED') {
        color = '#D4DADF'       // Gray
    } else if (buildStatus == 'UNSTABLE') {
        color = 'warning'       //  Yellow - #FFFE89
    } else {
        color = 'danger'        // Red - '#FF9FA1'
    }

    def jobBaseName = sh(returnStdout: true, script: '''echo "${JOB_NAME%/*}"''').trim().split('/').last()
    def message = "${buildStatus}: `${jobBaseName} (${env.BRANCH_NAME})` - ${currentBuild.displayName} ${ buildStatus=='STARTED' ? buildCause : buildStatus.toLowerCase().capitalize() + " after ${currentBuild.durationString.minus(' and counting')}" } (<${currentBuild.absoluteUrl}|Open>) ${changeSetMsg}"

    if(addMessage){
        message = addMessage
    }

    slackSend( channel: channelName, color: color, message: message )
}

def getChangeString() {
    def maxMsgLen = 100
    def changeString = ""
    def changeFileCount = 0

    def changeLogSets = currentBuild.rawBuild.changeSets
    for (int i = 0; i < changeLogSets.size(); i++) {
        def entries = changeLogSets[i].items
        for (int j = 0; j < entries.length; j++) {
            def entry = entries[j]
            truncated_msg = entry.msg.take(maxMsgLen)
            changeString += " > ${truncated_msg} - ${entry.commitId.substring(0,7)} [${entry.author}]\n"
            changeFileCount += entry.affectedFiles.size()
        }
    }

    if (!changeString) {
        changeString = " > No new changes"
    } else {
        changeString += "\n${changeFileCount} file(s) changed"
    }

    return changeString
}

