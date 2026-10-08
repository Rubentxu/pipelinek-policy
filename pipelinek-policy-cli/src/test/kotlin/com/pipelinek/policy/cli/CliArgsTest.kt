package com.pipelinek.policy.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliArgsTest {

    @Test
    fun `positional and flag args are separated`() {
        val a = CliArgs.parse(listOf("res1.json", "--policy", "b.zip", "res2.json", "--format", "jsonl"))
        assertEquals(listOf("res1.json", "res2.json"), a.positionals)
        assertEquals("b.zip", a.flag("policy"))
        assertEquals("jsonl", a.flag("format"))
        assertTrue(a.has("policy"))
        assertFalse(a.has("waivers"))
    }

    @Test
    fun `valueless flags land in boolean set`() {
        val a = CliArgs.parse(listOf("check", "--json-help"))
        assertTrue(a.has("json-help"))
        assertTrue(a.positionals.contains("check"))
    }

    @Test
    fun `flag followed by flag is boolean not consumed value`() {
        val a = CliArgs.parse(listOf("--policy", "--format", "text"))
        assertTrue(a.has("policy"))
        assertEquals("text", a.flag("format"))
    }
}
