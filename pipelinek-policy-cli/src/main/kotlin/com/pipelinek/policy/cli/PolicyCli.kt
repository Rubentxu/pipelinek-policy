package com.pipelinek.policy.cli

/**
 * M9 · entry point. `run(args)` returns the exit code so tests exercise
 * the real dispatch without forking; `main` only maps it to the process.
 */
object PolicyCli {

    /** Stub commands return this until their WU lands; distinct from OK. */
    internal const val NOT_IMPLEMENTED_YET: Int = 3

    fun run(args: List<String>, out: (String) -> Unit = ::println): Int {
        if (args.isEmpty()) {
            printUsage(out)
            return ExitCodes.USAGE
        }
        val first = args[0]
        if (first == "--json-help") {
            out(JsonHelp.rootJson())
            return ExitCodes.OK
        }
        if (first == "--help" || first == "-h") {
            printUsage(out)
            return ExitCodes.OK
        }
        val spec = CommandRegistry.byName(first)
        if (spec == null) {
            out("unknown command: $first")
            printUsage(out)
            return ExitCodes.USAGE
        }
        val rest = CliArgs.parse(args.drop(1))
        if (rest.has("help")) {
            out(JsonHelp.commandJson(spec))
            return ExitCodes.OK
        }
        if (rest.has("json-help")) {
            out(JsonHelp.commandJson(spec))
            return ExitCodes.OK
        }
        return dispatch(spec, rest, out)
    }

    /** Per-command dispatch; every branch is filled in by its WU. */
    private fun dispatch(spec: CommandSpec, args: CliArgs, out: (String) -> Unit): Int =
        when (spec.name) {
            "compile" -> com.pipelinek.policy.cli.commands.CompileCmd.run(args, out)
            "check" -> com.pipelinek.policy.cli.commands.CheckCmd.run(args, out)
            "test" -> com.pipelinek.policy.cli.commands.TestCmd.run(args, out)
            "diff" -> com.pipelinek.policy.cli.commands.DiffCmd.run(args, out)
            "explain" -> com.pipelinek.policy.cli.commands.ExplainCmd.run(args, out)
            "inspect" -> com.pipelinek.policy.cli.commands.InspectCmd.run(args, out)
            "shape" -> com.pipelinek.policy.cli.commands.ShapeCmd.run(args, out)
            "bundle" -> com.pipelinek.policy.cli.commands.BundleCmd.run(args, out)
            else -> ExitCodes.INTERNAL
        }

    private fun printUsage(out: (String) -> Unit) {
        out("usage: pipelinek-policy <command> [args]")
        out("commands: ${CommandRegistry.names.joinToString(", ")}")
        out("run `pipelinek-policy <command> --json-help` for machine-readable help")
    }
}

fun main(args: Array<String>) {
    kotlin.system.exitProcess(PolicyCli.run(args.toList()))
}
