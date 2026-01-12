import com.company.utils.CommonUtils

/**
 * notify(config: ...)
 *
 * Supports Slack + Email patterns.
 *
 * config:
 *  - status: SUCCESS|FAILURE|UNSTABLE|STARTED
 *  - channel: slack channel (optional)
 *  - slackCredentialId: if using token-based slack steps (optional)
 *  - emailTo: comma-separated
 *  - subjectPrefix
 *  - extra: Map of extra fields to print
 */
def call(Map config = [:]) {
    def u = new CommonUtils(this)

    def cfg = u.deepMerge([
            status: currentBuild.currentResult ?: "UNKNOWN",
            channel: "",
            emailTo: "",
            subjectPrefix: "Jenkins",
            extra: [:]
    ], config ?: [:])

    def msg = """
${cfg.subjectPrefix}: ${env.JOB_NAME} #${env.BUILD_NUMBER}
Status: ${cfg.status}
Branch: ${env.BRANCH_NAME ?: "N/A"}
Build URL: ${env.BUILD_URL}
Extra: ${cfg.extra}
""".trim()

    echo "Notification message:\n${msg}"

    // Slack (if plugin configured)
    if (cfg.channel?.trim()) {
        try {
            slackSend(channel: cfg.channel, message: msg)
        } catch (Throwable t) {
            echo "Slack notify failed (non-fatal): ${t.message}"
        }
    }

    // Email (if email-ext plugin configured)
    if (cfg.emailTo?.trim()) {
        try {
            emailext(
                    to: cfg.emailTo,
                    subject: "${cfg.subjectPrefix}: ${env.JOB_NAME} #${env.BUILD_NUMBER} - ${cfg.status}",
                    body: msg
            )
        } catch (Throwable t) {
            echo "Email notify failed (non-fatal): ${t.message}"
        }
    }
}
