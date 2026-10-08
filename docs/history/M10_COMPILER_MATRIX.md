# M10 Compiler Matrix (02a/02b)

| Runtime | Tests executed | Verdict |
|---|---|---|
| JVM toolchain 21 (Temurin 21.0.8 LTS) | 273 | OK |
| JVM toolchain 25 (Temurin 25.0.4 LTS) | 273 | OK |

Overall: **PASS** (same suite ⇒ same count)

Executed: 2026-10-08T18:54:04Z · SHA 5a9162b1912ee00853c33610a6917be136525d37
Command: ./gradle-jdk21.sh test -PtestJvm={21,25} --rerun-tasks
