import com.company.utils.CommonUtils
import com.company.utils.VersionUtils

/**
 * publishArtifact(config: ...)
 *
 * Supports:
 * - Docker image build + push to ECR
 * - (Optional) Maven/Gradle publish to artifact repo (placeholder)
 *
 * config:
 *  - stashName
 *  - workDir
 *  - mode: ecr|mavenRepo
 *  - dockerfile: 'Dockerfile'
 *  - imageName: 'my-service'
 *  - ecr:
 *      - region
 *      - registry (123456789012.dkr.ecr.us-east-1.amazonaws.com)
 *      - repository (my-service)
 *      - roleArn (optional for assumeRole)
 */
def call(Map config = [:]) {
    def u = new CommonUtils(this)
    def v = new VersionUtils(this)

    def cfg = u.deepMerge([
            stashName: "workspace",
            workDir: ".",
            mode: "ecr",
            dockerfile: "Dockerfile",
            imageName: "",
            ecr: [
                    region: "",
                    registry: "",
                    repository: "",
                    roleArn: ""
            ]
    ], config ?: [:])

    u.requireKeys(cfg, ["stashName", "workDir", "mode"])

    deleteDir()
    unstash cfg.stashName

    dir(cfg.workDir) {
        if (cfg.mode == "ecr") {
            u.requireKeys(cfg.ecr as Map, ["region", "registry", "repository"])
            def tag = v.computeVersion(strategy: "gitDescribe")
            def image = "${cfg.ecr.registry}/${cfg.ecr.repository}:${tag}"

            echo "Publishing Docker image to ECR: ${image}"

            u.withAwsAssumeRole([region: cfg.ecr.region, roleArn: cfg.ecr.roleArn], {
                u.ecrLogin(cfg.ecr.region, cfg.ecr.registry)

                sh """
          docker build -f '${cfg.dockerfile}' -t '${image}' .
          docker push '${image}'
        """.stripIndent()
            })

            // Expose image for downstream deploy step
            env.PUBLISHED_IMAGE = image
            echo "PUBLISHED_IMAGE=${env.PUBLISHED_IMAGE}"
        }
        else if (cfg.mode == "mavenRepo") {
            echo "Example placeholder for Maven publish (Artifactory/Nexus)."
            echo "In real usage, use withCredentials + mvn deploy, and set repository settings.xml."
            // sh "mvn -B -ntp deploy"
        }
        else {
            error "Unsupported publish mode: ${cfg.mode}"
        }
    }
}
