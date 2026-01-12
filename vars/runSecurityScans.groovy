import com.company.utils.CommonUtils

/**
 * runSecurityScans(config: ...)
 *
 * Runs multiple scanners in a consistent, interview-grade pattern:
 * - dependency scan
 * - container scan (optional)
 * - IaC scan (optional)
 *
 * config:
 *  - stashName
 *  - workDir
 *  - scanners: [ "trivy", "grype", "owasp" ] etc
 *  - failOn: "CRITICAL" | "HIGH" | "NONE"
 *  - image: optional docker image tag to scan
 */
def call(Map config = [:]) {
    def u = new CommonUtils(this)

    def cfg = u.deepMerge([
            stashName: "workspace",
            workDir: ".",
            scanners: ["trivy"],
            failOn: "HIGH",
            image: ""
    ], config ?: [:])

    u.requireKeys(cfg, ["stashName", "workDir", "scanners", "failOn"])

    deleteDir()
    unstash cfg.stashName

    dir(cfg.workDir) {
        def reportsDir = "security-reports"
        sh "mkdir -p ${reportsDir}"

        // NOTE: in real orgs these tools are installed in agent image, or wrapped via Docker.
        cfg.scanners.each { scanner ->
            switch (scanner) {
                case "trivy":
                    echo "Running Trivy filesystem scan..."
                    sh """
            trivy fs --quiet --format json --output ${reportsDir}/trivy-fs.json .
            trivy fs --quiet --format table --output ${reportsDir}/trivy-fs.txt .
          """.stripIndent()

                    if (cfg.image?.trim()) {
                        echo "Running Trivy image scan: ${cfg.image}"
                        sh """
              trivy image --quiet --format json --output ${reportsDir}/trivy-image.json '${cfg.image}'
              trivy image --quiet --format table --output ${reportsDir}/trivy-image.txt '${cfg.image}'
            """.stripIndent()
                    }
                    break

                case "grype":
                    echo "Running Grype filesystem scan..."
                    sh """
            grype dir:. -o json > ${reportsDir}/grype.json || true
            grype dir:. -o table > ${reportsDir}/grype.txt || true
          """.stripIndent()
                    break

                case "owasp":
                    echo "Running OWASP dependency-check (example)..."
                    sh """
            dependency-check --scan . --format JSON --out ${reportsDir} || true
          """.stripIndent()
                    break

                default:
                    error "Unsupported scanner: ${scanner}"
            }
        }

        archiveArtifacts artifacts: "${reportsDir}/**", allowEmptyArchive: true

        // Simple gate example (adapt to your org standards)
        if (cfg.failOn != "NONE") {
            echo "Applying security gate: failOn=${cfg.failOn}"
            // In real life: parse JSON reports and enforce thresholds.
            // Here: demonstrate a pattern without fragile parsing.
            echo "Gate check placeholder: implement severity parsing for your scanner outputs."
        }
    }
}
