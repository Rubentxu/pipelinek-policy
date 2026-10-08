// M6 UAT pass: policy.check over a compliant resource (replicas=4 >= 3).
// Expected: pipeline SUCCEEDS, exit 0.
import com.pipelinek.policy.plugin.policyCheck

pipeline {
    stages {
        stage("policy-gate") {
            policyCheck(resource = "service.json", format = "JSON", bundle = "policy.bundle.bin")
        }
    }
}
