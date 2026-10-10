// M6 UAT violate: policy.check over 4 REAL non-compliant resource files (replicas < 3).
// Expected: pipeline FAILS, non-zero exit, FailureKind PLUGIN, VIOLATED event.
//
// The counterpart to uat-pass: same installed plugin, same bundle, same
// admission path, opposite outcome. Together the pair is what B4.9 asks for —
// the installed Step is walked over a real corpus and must agree with the
// policy in both directions, so a plugin that always passed would fail here
// and a plugin that always failed would fail uat-pass.
import com.pipelinek.policy.plugin.policyCheck

val violating = (1..4).map { "corpus/violating-$it.json" }

pipeline {
    stages {
        stage("policy-gate") {
            violating.forEach { path ->
                policyCheck(resource = path, format = "JSON", bundle = "policy.bundle.bin")
            }
        }
    }
}
