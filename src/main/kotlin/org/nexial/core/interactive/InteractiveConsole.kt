/*
 * Copyright 2012-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.nexial.core.interactive

import com.diogonunes.jcdp.bw.Printer.Builder
import com.diogonunes.jcdp.bw.Printer.Types
import com.diogonunes.jcdp.color.ColoredPrinter
import com.diogonunes.jcdp.color.api.Ansi.*
import com.diogonunes.jcdp.color.api.Ansi.Attribute.*
import org.apache.commons.collections4.CollectionUtils
import org.apache.commons.collections4.MapUtils
import org.apache.commons.lang3.ArrayUtils
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.SystemUtils.*
import org.nexial.commons.utils.DateUtility
import org.nexial.commons.utils.DateUtility.formatLongDate
import org.nexial.commons.utils.EnvUtils.getHostName
import org.nexial.commons.utils.RegexUtils
import org.nexial.commons.utils.ResourceUtils
import org.nexial.commons.utils.TextUtils
import org.nexial.core.interactive.InteractiveConsole.Commands.ALL_STEP
import org.nexial.core.interactive.InteractiveConsole.Commands.EXIT
import org.nexial.core.interactive.InteractiveConsole.Commands.HELP
import org.nexial.core.interactive.InteractiveConsole.Commands.INSPECT
import org.nexial.core.interactive.InteractiveConsole.Commands.OPEN_DATA
import org.nexial.core.interactive.InteractiveConsole.Commands.OPEN_SCRIPT
import org.nexial.core.interactive.InteractiveConsole.Commands.RELOAD_DATA
import org.nexial.core.interactive.InteractiveConsole.Commands.RELOAD_MENU
import org.nexial.core.interactive.InteractiveConsole.Commands.RELOAD_PROJPROP
import org.nexial.core.interactive.InteractiveConsole.Commands.RELOAD_SCRIPT
import org.nexial.core.interactive.InteractiveConsole.Commands.RUN
import org.nexial.core.interactive.InteractiveConsole.Commands.SET_ACTIVITY
import org.nexial.core.interactive.InteractiveConsole.Commands.SET_DATA
import org.nexial.core.interactive.InteractiveConsole.Commands.SET_ITER
import org.nexial.core.interactive.InteractiveConsole.Commands.SET_SCENARIO
import org.nexial.core.interactive.InteractiveConsole.Commands.SET_SCRIPT
import org.nexial.core.interactive.InteractiveConsole.Commands.SET_STEPS
import org.nexial.core.interactive.InteractiveConsole.MenuIdentifier.DIGIT
import org.nexial.core.interactive.InteractiveConsole.MenuIdentifier.UPPERCASE
import org.nexial.core.model.ExecutionSummary
import org.nexial.core.model.ExecutionSummary.ExecutionLevel.STEP
import org.nexial.core.utils.ConsoleUtils
import org.nexial.core.utils.ConsoleUtils.*
import java.io.IOException
import java.lang.System.out
import java.util.*

open class InteractiveConsole {

    internal enum class MenuIdentifier(val regex: String) { UPPERCASE("[A-Z]"), DIGIT("[0-9]") }

    object Commands {
        const val SET_SCRIPT = "1"
        const val SET_DATA = "2"
        const val SET_SCENARIO = "3"
        const val SET_ITER = "4"
        const val SET_ACTIVITY = "5"
        const val SET_STEPS = "6"
        const val RELOAD_SCRIPT = "7"
        const val RELOAD_DATA = "8"
        const val RELOAD_PROJPROP = "9"

        const val RELOAD_MENU = "R"
        const val RUN = "X"
        const val INSPECT = "I"
        const val ALL_STEP = "A"
        const val OPEN_SCRIPT = "S"
        const val OPEN_DATA = "D"
        const val HELP = "H"
        const val EXIT = "Q"
    }

    companion object {

        private const val HDR_EXECUTED = "${META_START}Executed$META_END"
        private const val HDR_SESSION = "${META_START}Session $META_END"
        private const val HDR_SCRIPT = "${META_START}Script  $META_END"
        private const val HDR_SCENARIO = "${META_START}Scenario$META_END"
        private const val HDR_ACTIVITY = "${META_START}Activity$META_END"
        private const val HDR_STEPS = "${META_START}Step    $META_END"
        private const val HDR_SUMMARY = "${META_START}Summary $META_END"
        private const val HDR_EXCEPTION = "${META_START}ERROR   $META_END"

        private val SUB1_START = StringUtils.repeat(" ", HDR_ACTIVITY.length)
        private const val SUB2_END = ": "
        private const val CMD_START = "  "
        private const val CMD_END = " "
        private const val FILLER = '~'

        private val HDR_TIMESPAN = "${SUB1_START}timespan       $SUB2_END"
        private val HDR_DURATION = "${SUB1_START}duration       $SUB2_END"
        private val HDR_ITERATION = "${SUB1_START}iteration      $SUB2_END"
        private val HDR_STATS = "${SUB1_START}total/pass/fail$SUB2_END"

        private const val MAX_LENGTH_BASE = PROMPT_LINE_WIDTH - MARGIN_LEFT.length - MARGIN_RIGHT.length
        private const val MAX_LENGTH_SCRIPT = MAX_LENGTH_BASE - HDR_SCRIPT.length
        private val MAX_LENGTH_REF = HDR_STATS.length - SUB1_START.length - SUB2_END.length
        private val LEFT_MARGIN_L2_VAL = MAX_LENGTH_BASE - HDR_STATS.length
        private val LEFT_MARGIN_L3_HEADER = MAX_LENGTH_BASE - SUB1_START.length

        private val CONSOLE = Builder(Types.TERM).timestamping(false).build()
        private val CPRINTER = ColoredPrinter.Builder(1, false).timestamping(false).build()

        private val HELP_TEMPLATE_RESOURCE =
            StringUtils.replace(InteractiveConsole::class.java.getPackage().name, ".", "/") +
            "/nexial-interactive-help.properties"
        private val HELP_TEMPLATE: Properties?

        fun showMenu(session: InteractiveSession?) {
            if (session == null) {
                System.err.println("ERROR: No interactive session found")
                return
            }

            printConsoleHeaderTop(out, "NEXIAL INTERACTIVE", FILLER)
            printHeaderLine(out, HDR_SESSION, formatExecutionMeta(session.startTime))
            printHeaderLine(out, HDR_SCRIPT, formatTestScript(session.script))
            printHeaderLine(out, HDR_SCENARIO, session.scenario)
            printHeaderLine(out, HDR_ACTIVITY, session.formatActivities(session.activities))
            printHeaderLine(out, HDR_STEPS, TextUtils.toString(session.steps, ","))

            printConsoleSectionSeparator(out, "~~options", FILLER)
            printMenu(CMD_START, DIGIT, "$SET_SCRIPT <script>   ${CMD_END}assign test script")
            printMenu(CMD_START, DIGIT, "$SET_DATA <data file>${CMD_END}assign data file")
            printMenu(CMD_START, DIGIT, "$SET_SCENARIO <scenario> ${CMD_END}assign scenario")
            printMenu(CMD_START, DIGIT, "$SET_ITER <iteration>${CMD_END}assign iteration")
            printMenu(CMD_START, DIGIT, "$SET_ACTIVITY <activity> ${CMD_END}assign activities; clears assigned steps")
            printMenu(CMD_START, DIGIT, "$SET_STEPS <step>     ${CMD_END}assign steps; clears assigned activities")
            printMenu(CMD_START, DIGIT, "$RELOAD_SCRIPT            ${CMD_END}reload assigned test script")
            printMenu(CMD_START, DIGIT, "$RELOAD_DATA            ${CMD_END}reload assigned data file")
            printMenu(CMD_START, DIGIT, "$RELOAD_PROJPROP            ${CMD_END}reload project.properties (if found)")
            printMenu("${CMD_START}action       $CMD_END", UPPERCASE,
                      StringUtils.rightPad("${RELOAD_MENU}eload menu", 15),
                      StringUtils.rightPad("e${RUN}ecute", 18),
                      StringUtils.rightPad("${INSPECT}nspect", 12),
                      StringUtils.rightPad("${ALL_STEP}ll steps", 12))
            printMenu("$CMD_START             $CMD_END", UPPERCASE,
                      StringUtils.rightPad("${OPEN_SCRIPT}cript open", 15),
                      StringUtils.rightPad("${OPEN_DATA}ata file open", 18),
                      StringUtils.rightPad("${HELP}elp", 12),
                      StringUtils.rightPad("${EXIT}uit", 12))
            printConsoleHeaderBottom(out, FILLER)
        }

        fun showRun(session: InteractiveSession?) {
            if (session == null) {
                System.err.println("ERROR: No interactive session found")
                return
            }

            val context = session.context
            val testScenarios = context.testScenarios
            if (CollectionUtils.isEmpty(testScenarios)) {
                System.err.println("ERROR: Test steps executed")
                return
            }

            testScenarios.forEach { scenario -> showRun(scenario.executionSummary, session) }
            println()
            println()
        }

        fun showRun(scenarioSummary: ExecutionSummary, session: InteractiveSession) {
            printConsoleHeaderTop(out, "NEXIAL INTERACTIVE", ConsoleUtils.FILLER)
            printHeaderLine(out, HDR_EXECUTED, formatExecutionMeta(scenarioSummary.startTime))
            printHeaderLine(out, HDR_SCRIPT, formatTestScript(session.script))
            printHeaderLine(out, HDR_SCENARIO, scenarioSummary.name)

            val error = session.exception
            if (error != null) printHeaderLine(out, HDR_EXCEPTION, error.message)

            printConsoleSectionSeparator(out, FILLER)

            val activitySummaries = scenarioSummary.nestedExecutions
            activitySummaries.forEach { activity ->
                val endTime = activity.endTime
                val startTime = activity.startTime
                val timeSpan = formatLongDate(startTime) + " - " + formatLongDate(endTime)
                val duration = DateUtility.formatStopWatchTime(endTime - startTime)

                val header = if (activity.executionLevel == STEP) HDR_STEPS else HDR_ACTIVITY
                printHeaderLine(out, header, activity.name)
                printHeaderLine(out, HDR_TIMESPAN, timeSpan)
                printHeaderLine(out, HDR_DURATION, duration)
                printStats(activity)
            }

            printConsoleSectionSeparator(out, FILLER)

            val endTime = scenarioSummary.endTime
            val startTime = scenarioSummary.startTime
            val timeSpan = formatLongDate(startTime) + " - " + formatLongDate(endTime)
            val duration = DateUtility.formatStopWatchTime(endTime - startTime)
            printHeaderLine(out, HDR_SUMMARY, scenarioSummary.name)
            printHeaderLine(out, HDR_TIMESPAN, timeSpan)
            printHeaderLine(out, HDR_DURATION, duration)
            printHeaderLine(out, HDR_ITERATION, session.iteration.toString())
            printStats(scenarioSummary)

            val context = session.context
            printReferenceData("script reference data", context.gatherScriptReferenceData())
            printReferenceData("scenario reference data", scenarioSummary.referenceData)

            printConsoleHeaderBottom(out, ConsoleUtils.FILLER)
        }

        fun showHelp(session: InteractiveSession) {
            // tokens for template search-n-replace
            val tokens = HashMap<String, String>()
            tokens["username"] = USER_NAME
            tokens["host"] = "${getHostName()} ($OS_NAME $OS_VERSION)"
            tokens["sessionStartDT"] = formatLongDate(session.startTime)
            tokens["cmd.script"] = SET_SCRIPT
            tokens["cmd.data"] = SET_DATA
            tokens["cmd.scenario"] = SET_SCENARIO
            tokens["cmd.iteration"] = SET_ITER
            tokens["cmd.activity"] = SET_ACTIVITY
            tokens["cmd.steps"] = SET_STEPS
            tokens["cmd.reloadscript"] = RELOAD_SCRIPT
            tokens["cmd.reloaddata"] = RELOAD_DATA
            tokens["cmd.reloadmenu"] = RELOAD_MENU
            tokens["cmd.inspect"] = INSPECT
            tokens["cmd.execute"] = RUN
            tokens["cmd.help"] = HELP
            tokens["cmd.quit"] = EXIT
            tokens["script"] = session.script ?: ""
            tokens["scenario"] = session.scenario ?: ""
            tokens["activities"] = TextUtils.toString(session.formatActivities(session.activities), ", ")
            tokens["steps"] = TextUtils.toString(session.steps, ", ")

            printConsoleHeaderTop(out, "NEXIAL INTERACTIVE HELP", FILLER)
            printHeaderLine(out, "INTRO ", resolveContent("intro", tokens))
            printHeaderLine(out, "NOTE- ", resolveContent("notes.1", tokens))
            printHeaderLine(out, "    - ", resolveContent("notes.2", tokens))
            printHeaderLine(out, "    - ", resolveContent("notes.3", tokens))
            printHeaderLine(out, "    - ", resolveContent("notes.4", tokens))

            printConsoleSectionSeparator(out, "~~informational", FILLER)
            printHeaderLine(out, HDR_SESSION, resolveContent("session", tokens))
            printHeaderLine(out, HDR_SCRIPT, resolveContent("script", tokens))
            printHeaderLine(out, HDR_SCENARIO, resolveContent("scenario", tokens))
            printHeaderLine(out, HDR_ACTIVITY, resolveContent("activity", tokens))
            printHeaderLine(out, HDR_STEPS, resolveContent("steps", tokens))

            printConsoleSectionSeparator(out, "~~options", FILLER)
            printHeaderLine(out, "$CMD_START$SET_SCRIPT <script>   $CMD_END", resolveContent("command.script", tokens))
            printHeaderLine(out, "$CMD_START$SET_DATA <data file>$CMD_END", resolveContent("command.data", tokens))
            printHeaderLine(out, "$CMD_START$SET_SCENARIO <scenario> $CMD_END",
                            resolveContent("command.scenario", tokens))
            printHeaderLine(out, "$CMD_START$SET_ITER <iteration>$CMD_END", resolveContent("command.iteration", tokens))
            printHeaderLine(out, "$CMD_START$SET_ACTIVITY <activity> $CMD_END",
                            resolveContent("command.activity", tokens))
            printHeaderLine(out, "$CMD_START$SET_STEPS <step>     $CMD_END", resolveContent("command.steps", tokens))
            printHeaderLine(out, "$CMD_START$RELOAD_SCRIPT            $CMD_END",
                            resolveContent("command.reloadscript", tokens))
            printHeaderLine(out, "$CMD_START$RELOAD_DATA            $CMD_END",
                            resolveContent("command.reloaddata", tokens))
            printHeaderLine(out, "$CMD_START$RELOAD_PROJPROP            $CMD_END",
                            resolveContent("command.reloadprojprop", tokens))
            printHeaderLine(out, " ($RELOAD_MENU)eload      $CMD_END", resolveContent("command.reloadmenu", tokens))
            printHeaderLine(out, "e($RUN)ecute      $CMD_END", resolveContent("command.run", tokens))
            printHeaderLine(out, " ($INSPECT)nspect     $CMD_END", resolveContent("command.inspect", tokens))
            printHeaderLine(out, " ($ALL_STEP)ll steps   $CMD_END", resolveContent("command.allstep", tokens))
            printHeaderLine(out, " ($OPEN_SCRIPT)cript open $CMD_END", resolveContent("command.openscript", tokens))
            printHeaderLine(out, " ($OPEN_DATA)ata file...$CMD_END", resolveContent("command.opendata", tokens))
            printHeaderLine(out, " ($HELP)elp        $CMD_END", resolveContent("command.help", tokens))
            printHeaderLine(out, " ($EXIT)uit        $CMD_END", resolveContent("command.exit", tokens))

            printConsoleHeaderBottom(out, FILLER)
            println()
            println()
        }

        private fun printMenu(prefix: String, menuIdentifier: MenuIdentifier, vararg menus: String) {
            if (ArrayUtils.isEmpty(menus)) return

            var charPrinted = 0

            CONSOLE.print(MARGIN_LEFT)
            CONSOLE.print(prefix)
            charPrinted += MARGIN_LEFT.length + prefix.length

            val regex = menuIdentifier.regex
            for (menu in menus) {
                charPrinted += menu.length
                val key = RegexUtils.firstMatches(menu, regex)
                if (StringUtils.isBlank(key)) {
                    CONSOLE.print(menu)
                } else {
                    val beforeKey = StringUtils.substringBefore(menu, key)
                    if (StringUtils.isNotEmpty(beforeKey)) CONSOLE.print(beforeKey)

                    CPRINTER.print(key, UNDERLINE, FColor.BLACK, BColor.WHITE)
                    CPRINTER.clear()

                    val afterKey = StringUtils.substringAfter(menu, key)
                    if (StringUtils.isNotEmpty(afterKey)) CONSOLE.print(afterKey)
                }
            }

            CONSOLE.print(StringUtils.repeat(' ', PROMPT_LINE_WIDTH - charPrinted - 1))
            CONSOLE.println(MARGIN_RIGHT)
        }

        private fun printReferenceData(header: String, refs: Map<String, String>) {
            if (MapUtils.isEmpty(refs)) return

            val header1 = "[$header]"

            CONSOLE.print(MARGIN_LEFT)
            CONSOLE.print(SUB1_START)
            CPRINTER.print(header1, UNDERLINE, FColor.CYAN, BColor.NONE)
            CPRINTER.clear()

            val fillerLength = LEFT_MARGIN_L3_HEADER - header1.length
            CONSOLE.print(StringUtils.repeat(" ", fillerLength))
            CONSOLE.println(MARGIN_RIGHT)

            refs.forEach { key, value ->
                val refKey = SUB1_START + StringUtils.rightPad("($key)", MAX_LENGTH_REF, " ") + SUB2_END
                printHeaderLine(out, refKey, value)
            }
        }

        private fun printStats(executionSummary: ExecutionSummary) {
            val totalCount = executionSummary.totalSteps
            val failCount = executionSummary.failCount
            val skipCount = totalCount - executionSummary.executed

            val total = StringUtils.leftPad(totalCount.toString(), 3)
            val pass = StringUtils.leftPad(executionSummary.passCount.toString(), 3)
            val fail = StringUtils.leftPad(failCount.toString(), 3)
            val skipped = StringUtils.leftPad(skipCount.toString(), 3)

            val headerLine = MARGIN_LEFT + HDR_STATS
            val skippedStat = if (skipCount > 0) "  (SKIPPED:$skipped)" else ""
            val statDetails = total + MULTI_SEP + pass + MULTI_SEP + fail + skippedStat

            CONSOLE.print(headerLine)
            CPRINTER.print(total, BOLD, FColor.WHITE, BColor.NONE)
            CPRINTER.print(MULTI_SEP, Attribute.NONE, FColor.WHITE, BColor.NONE)
            CPRINTER.print(pass, BOLD, FColor.GREEN, BColor.NONE)
            CPRINTER.print(MULTI_SEP, Attribute.NONE, FColor.WHITE, BColor.NONE)
            CPRINTER.print(fail, BOLD, if (failCount < 1) FColor.WHITE else FColor.RED, BColor.NONE)
            if (skipCount > 0) CPRINTER.print(skippedStat, CLEAR, FColor.YELLOW, BColor.NONE)
            CPRINTER.clear()

            val fillerLength = LEFT_MARGIN_L2_VAL - statDetails.length
            CONSOLE.print(StringUtils.repeat(" ", fillerLength))
            CONSOLE.println(MARGIN_RIGHT)
        }

        private fun resolveContent(templateKey: String, tokens: Map<String, String>): String? {
            if (StringUtils.isBlank(templateKey)) return " "

            var template = HELP_TEMPLATE!!.getProperty(templateKey)
            if (MapUtils.isEmpty(tokens)) return template

            val keys = tokens.keys
            for (token in keys) template = StringUtils.replace(template, "\${$token}", tokens[token])
            return template
        }

        private fun formatTestScript(testScript: String?) = when {
            testScript == null                                 -> ""
            StringUtils.length(testScript) > MAX_LENGTH_SCRIPT -> "..." + testScript.substring(
                testScript.length - MAX_LENGTH_SCRIPT + 3)
            else                                               -> testScript
        }

        private fun formatExecutionMeta(startTime: Long) =
            "$USER_NAME$MULTI_SEP${getHostName()} ($OS_NAME $OS_VERSION)$MULTI_SEP${formatLongDate(startTime)}"

        init {
            try {
                HELP_TEMPLATE = ResourceUtils.loadProperties(HELP_TEMPLATE_RESOURCE)
            } catch (e: IOException) {
                throw RuntimeException("Help resource cannot be loaded via '$HELP_TEMPLATE_RESOURCE': ${e.message}", e)
            }
        }
    }
}
