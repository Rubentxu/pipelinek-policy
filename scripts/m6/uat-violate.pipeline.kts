// M6 UAT violate: policy.check over a non-compliant resource (replicas=2 < 3).
// Expected: pipeline FAILS with the plugin's VIOLATED verdict, non-zero exit.
import com.pipelinek.policy.plugin.policyCheck

pipeline {
    stages {
        stage("policy-gate") {
            policyCheck(resource = "service-bad.json", format = "JSON", bundle = "policy.bundle.bin")
        }
    }
}
