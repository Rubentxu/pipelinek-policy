// M6 UAT pass: policy.check over 16 REAL compliant resource files (replicas >= 3).
// Expected: pipeline SUCCEEDS, exit 0, every check PASSED.
//
// 16 files rather than 1 because B4.9 requires the installed plugin to be
// walked over a real corpus, not a single hand-picked document. Each is a
// distinct file on disk, not a loop variable inside one file: the point is to
// exercise repeated admission and decoding through the installed Step. The
// violating half of the corpus lives in uat-violate.pipeline.kts, which
// asserts the opposite outcome.
import com.pipelinek.policy.plugin.policyCheck

val compliant = (1..16).map { "corpus/compliant-$it.json" }

pipeline {
    stages {
        stage("policy-gate") {
            compliant.forEach { path ->
                policyCheck(resource = path, format = "JSON", bundle = "policy.bundle.bin")
            }
        }
    }
}
