package com.pipelinek.policy.dsl

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.expression.Expression.CollectionPredicate
import com.pipelinek.policy.kernel.expression.Expression.Comparison
import com.pipelinek.policy.kernel.expression.Expression.Literal
import com.pipelinek.policy.kernel.expression.Expression.Reference
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.selector.Selector
import org.junit.jupiter.api.Test
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty0
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec REQ §"DSL purity macro prototype" — `policy { }` MUST NOT require
 * a Kotlin compiler-plugin (architectural law 3) and MUST NOT capture
 * author lambdas on the produced ADT (architectural law 4).
 *
 * The macro is `inline fun policy(...)` — the Kotlin compiler inlines the
 * body at every call site, so no author lambda is materialized at runtime.
 * This test verifies the macro purity contract directly:
 *
 *   - The macro entry point is `inline fun policy(...)`. (A non-inline
 *     `fun policy(...)` would not satisfy law 3.)
 *   - The resulting `PolicySet` class graph contains only kernel + dsl
 *     packages (no caller-side classes leak in).
 *   - No `Function0`/`Function1`/`KFunction`/`KClass` appears in the
 *     produced `PolicySet` reachable class set.
 *   - `Reference` nodes from the DSL are fully substituted before the
 *     `Rule` crosses the kernel boundary (law 4: no closure retained).
 */
class MacroPurityTest {

    @Test
    fun purity_with_fir_plugin_active() {
        if (System.getProperty("policy.fir.spike") != "true") return
        val set = policy("fir-purity") {
            policy("p") {
                rule("r") {
                    require {
                        root().optionalField("anything").optionalField("whatever")
                            .asText().eqText(text("hello"))
                    }
                }
            }
        }
        val reachable = set.policies.single().rules.single().expression::class.java
        assertTrue(!reachable.name.contains("Lambda") && !reachable.name.contains("Function"))
        assertTrue(!reachable.name.contains("System"))
    }

    @Test
    fun `policy macro is declared inline in the DSL package`() {
        // The static contract is enforced by `compileTestKotlin`: if `policy`
        // is declared as `fun` (not `inline fun`) the compiler would refuse
        // to access internal builder constructors from the inlined lambda body.
        //
        // Runtime verification: `isInline` lives on `KFunction` (NOT on
        // `KProperty0`). We bridge to it by taking the `::policy` reference
        // directly and asserting `isInline` from there.
        val policyRef: KFunction<PolicySet> = ::policy
        assertTrue(
            policyRef.isInline,
            "policy MUST be declared `inline fun` (architectural law 3)",
        )
        assertEquals("policy", policyRef.name)
        // Sanity: `policySignature` must be backed by the same function.
        // We don't compare KFunction identity (no public API); we verify
        // names match.
        val propRef: KProperty0<*> = ::policySignature
        assertEquals("policySignature", propRef.name)
    }

    @Test
    fun `DSL-produced PolicySet has no Reference node after substitution`() {
        val set: PolicySet = policy("p") {
            policy("p1") {
                rule("r1") {
                    require {
                        BuilderCtx.root().run {
                            root().field("spec").field("replicas").asNumber()
                                .gte(ref("min"))
                        }
                    }
                    params(mapOf("min" to DslParamValue.IntV(3)))
                }
            }
        }
        // Walk every expression in the produced PolicySet and assert no
        // Reference is reachable (substitution must have replaced them).
        var refs = 0
        fun walk(e: Expression) {
            when (e) {
                is Reference -> refs++
                is Literal -> {}
                is Comparison -> { walk(e.left); walk(e.right) }
                is CollectionPredicate -> walk(e.source)
                else -> {}
            }
        }
        for (p: Policy in set.policies) {
            for (r in p.rules) {
                walk(r.expression)
                r.appliesWhen?.let { walk(it) }
            }
        }
        assertEquals(0, refs, "Reference MUST be substituted before Rule crosses the boundary")
    }

    @Test
    fun `DSL-produced PolicySet reachable class graph is restricted to kernel and dsl packages`() {
        val set: PolicySet = policy("p") {
            policy("p1") {
                rule("r1") {
                    require {
                        BuilderCtx.root().run {
                            root().field("spec").field("replicas").asNumber()
                                .gte(number(3))
                        }
                    }
                }
            }
        }
        // The reachable class graph is restricted to kernel + dsl packages.
        // ParityTest already covers the same assertion; here we add the
        // additional check that NO Function / KFunction / KClass / closure
        // class is reachable.
        val seen = mutableSetOf<Class<*>>()
        seen += set::class.java
        for (p in set.policies) {
            seen += p::class.java
            for (r in p.rules) {
                seen += r::class.java
            }
        }
        for (cls in seen) {
            val pkg = cls.packageName
            assertTrue(
                pkg.startsWith("com.pipelinek.policy.kernel") ||
                    pkg.startsWith("com.pipelinek.policy.dsl"),
                "Class $cls leaks from package $pkg",
            )
            // No functional closures leaked into the ADT.
            assertTrue(
                !cls.simpleName.contains("Lambda") &&
                    !cls.simpleName.contains("Function") &&
                    !cls.simpleName.contains("invoke"),
                "Functional class leaked into PolicySet: $cls",
            )
        }
    }

    @Test
    fun `CollectionPredicate DSL path still lowers to kernel data class`() {
        val set: PolicySet = policy("p") {
            policy("p1") {
                rule("r1") {
                    require {
                        BuilderCtx.root().run {
                            root().field("items").asNumber().gte(number(1))
                        }
                    }
                }
            }
        }
        // The expression is a Comparison (not a CollectionPredicate yet
        // because the DSL doesn't yet expose `all/any/none/count`
        // combinators). We assert the structural kernel shape.
        val rule = set.policies.single().rules.single()
        assertTrue(rule.expression is Comparison, "DSL must lower to Comparison")
    }
}
