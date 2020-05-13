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
 *
 */

package org.nexial.core.plugins.base;

import java.awt.image.*;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import javax.validation.constraints.NotNull;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.nexial.commons.utils.FileUtil;
import org.nexial.commons.utils.JRegexUtils;
import org.nexial.commons.utils.RegexUtils;
import org.nexial.commons.utils.ResourceUtils;
import org.nexial.commons.utils.TextUtils;
import org.nexial.core.ExecutionThread;
import org.nexial.core.TokenReplacementException;
import org.nexial.core.excel.ext.CellTextReader;
import org.nexial.core.model.CommandRepeater;
import org.nexial.core.model.ExecutionContext;
import org.nexial.core.model.StepResult;
import org.nexial.core.model.TestStep;
import org.nexial.core.plugins.CanLogExternally;
import org.nexial.core.plugins.NexialCommand;
import org.nexial.core.plugins.image.ImageCaptionHelper;
import org.nexial.core.plugins.image.ImageCaptionHelper.CaptionModel;
import org.nexial.core.plugins.ws.WsCommand;
import org.nexial.core.tools.CommandDiscovery;
import org.nexial.core.utils.CheckUtils;
import org.nexial.core.utils.ConsoleUtils;
import org.nexial.core.utils.ExecUtils;
import org.nexial.core.utils.OutputFileUtils;
import org.nexial.core.variable.Syspath;

import static java.io.File.separator;
import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static java.lang.System.lineSeparator;
import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static org.apache.commons.lang3.SystemUtils.JAVA_IO_TMPDIR;
import static org.nexial.core.CommandConst.*;
import static org.nexial.core.NexialConst.*;
import static org.nexial.core.NexialConst.Data.CMD_PROFILE_DEFAULT;
import static org.nexial.core.NexialConst.Data.NULL;
import static org.nexial.core.NexialConst.ImageCaption.*;
import static org.nexial.core.excel.ExcelConfig.MSG_PASS;
import static org.nexial.core.plugins.base.IncrementStrategy.ALPHANUM;
import static org.nexial.core.utils.CheckUtils.*;
import static org.nexial.core.utils.OutputFileUtils.CASE_INSENSIVE_SORT;

public class BaseCommand implements NexialCommand {
    protected static final IncrementStrategy STRATEGY_DEFAULT = ALPHANUM;
    private static final String TMP_DELIM = "<--~.$.~-->";
    protected transient Map<String, Method> commandMethods = new HashMap<>();
    protected transient ExecutionContext context;
    protected long pauseMs;
    protected transient ContextScreenRecorder screenRecorder;
    protected Syspath syspath = new Syspath();
    protected String profile = CMD_PROFILE_DEFAULT;

    public BaseCommand() {
        collectCommandMethods();
    }

    @Override
    public void init(ExecutionContext context) {
        this.context = context;
        pauseMs = context.getDelayBetweenStep();
    }

    @Override
    public ExecutionContext getContext() { return context; }

    @Override
    public void destroy() {}

    @Override
    public String getTarget() { return "base"; }

    @Override
    public String getProfile() { return profile; }

    @Override
    public void setProfile(String profile) { this.profile = profile; }

    @Override
    public boolean isValidCommand(String command, String... params) {
        return getCommandMethod(command, params) != null;
    }

    @Override
    public StepResult execute(String command, String... params)
        throws InvocationTargetException, IllegalAccessException {

        Method m = getCommandMethod(command, params);
        if (m == null) {
            return StepResult.fail("Unknown/unsupported command " + getTarget() + "." + command +
                                   " OR mismatched parameters");
        }

        // resolve more values, but not for logging
        Object[] values = resolveParamValues(m, params);

        if (context.isVerbose() && (this instanceof CanLogExternally)) {
            StringBuilder displayValues = new StringBuilder(command + " (");
            for (Object value : values) {
                if (value == null) {
                    displayValues.append(NULL).append(",");
                } else {
                    String val = value.toString();
                    if (context.containsCrypt(val)) {
                        displayValues.append(context.replaceTokens(val, true)).append(",");
                    } else {
                        displayValues.append(CellTextReader.readValue(val)).append(",");
                    }
                }
            }
            ((CanLogExternally) this).logExternally(context.getCurrentTestStep(),
                                                    StringUtils.removeEnd(displayValues.toString(), ",") + ")");
        }

        StepResult result = (StepResult) m.invoke(this, values);
        String methodName = StringUtils.substringBefore(StringUtils.substringBefore(command, "("), ".");
        if (!PARAM_DERIVED_COMMANDS.contains(getTarget() + "." + methodName)) { result.setParamValues(values); }
        return result;
    }

    public StepResult startRecording() {
        if (!ContextScreenRecorder.isRecordingEnabled(context)) {
            return StepResult.success("desktop recording is currently disabled.");
        }

        if (screenRecorder != null && screenRecorder.isVideoRunning()) {
            // can't support multiple simultaneous video recording
            StepResult result = stopRecording();
            if (result.failed()) {
                return StepResult.fail("Unable to stop previous desktop recording in progress: " + result.getMessage());
            }
        }

        try {
            // Create a instance of ScreenRecorder with the required configurations
            if (screenRecorder == null) { screenRecorder = ContextScreenRecorder.newInstance(context); }
            screenRecorder.start(context.getCurrentTestStep());
            return StepResult.success("desktop recording started; saved " + screenRecorder.getVideoFile());
        } catch (Exception e) {
            if (context.isVerbose()) { e.printStackTrace(); }
            return StepResult.fail("Unable to start desktop recording: " + e.getMessage());
        }
    }

    public StepResult stopRecording() {
        if (screenRecorder == null || !screenRecorder.isVideoRunning()) {
            return StepResult.success("desktop recording already stopped (or never ran)");
        }

        ConsoleUtils.log("stopping currently in-progress desktop recording...");

        try {
            screenRecorder.setContext(context);
            screenRecorder.stop();

            if (screenRecorder.videoFile != null) {
                String link = screenRecorder.videoFile;
                if (context.isOutputToCloud() && context.getOtc() != null) {
                    try {
                        link = context.getOtc().importMedia(new File(link), true);
                    } catch (IOException e) {
                        log(toCloudIntegrationNotReadyMessage(link) + ": " + e.getMessage());
                    }
                }

                context.setData(OPT_LAST_OUTPUT_LINK, link);
                context.setData(OPT_LAST_OUTPUT_PATH, StringUtils.contains(link, "\\") ?
                                                      StringUtils.substringBeforeLast(link, "\\") :
                                                      StringUtils.substringBeforeLast(link, "/"));

                TestStep testStep = context.getCurrentTestStep();
                if (testStep != null && testStep.getWorksheet() != null) {
                    // test step undefined could mean that we are in interactive mode, or we are running unit testing
                    testStep.addNestedScreenCapture(link, "recording from " + screenRecorder.startingLocation);
                }

                return StepResult.success("previous desktop recording saved and is accessible via " + link);
            } else {
                return StepResult.fail("Unable to determine video file used for current desktop recording!");
            }

        } catch (Throwable e) {
            return StepResult.fail("Unable to stop previous desktop recording: " + e.getMessage());
        } finally {
            screenRecorder = null;
        }
    }

    // todo: reconsider command name.  this is not intuitive
    public StepResult incrementChar(String var, String amount, String config) {
        requiresValidAndNotReadOnlyVariableName(var);
        int amountInt = toInt(amount, "amount");

        String current = StringUtils.defaultString(context.getStringData(var));
        requires(StringUtils.isNotBlank(current), "var " + var + " is not valid.", current);

        IncrementStrategy strategy = STRATEGY_DEFAULT;
        if (StringUtils.isNotBlank(config)) {
            try {
                strategy = IncrementStrategy.valueOf(config);
            } catch (IllegalArgumentException e) {
                // don't worry.. we'll just ue default strategy
            }
        }

        String newVal = strategy.increment(current, amountInt);
        updateDataVariable(var, newVal);

        return StepResult.success("incremented ${" + var + "} by " + amountInt + " to " + newVal);
    }

    public StepResult save(String var, String value) {
        requiresValidAndNotReadOnlyVariableName(var);
        updateDataVariable(var, value);
        return StepResult.success("stored '" + CellTextReader.readValue(value) + "' as ${" + var + "}");
    }

    /** clear data variables by name */
    public StepResult clear(String vars) {
        requiresNotBlank(vars, "invalid variable(s)", vars);
        return StepResult.success(clearVariables(TextUtils.toList(vars, context.getTextDelim(), true)));
    }

    @NotNull
    public String clearVariables(@NotNull List<String> variables) {
        List<String> ignoredVars = new ArrayList<>();
        List<String> removedVars = new ArrayList<>();

        if (variables.size() == 1 && StringUtils.equals(variables.get(0), "*")) {
            variables.clear();
            context.getDataNames("")
                   .stream()
                   .filter(varName -> !ExecUtils.isSystemVariable(varName) &&
                                      !StringUtils.startsWithAny(varName,
                                                                 "nexial.",
                                                                 "os.",
                                                                 "user.",
                                                                 "oracle.",
                                                                 "testsuite.startTs") &&
                                      !System.getProperties().containsKey(varName))
                   .forEach(variables::add);
        }

        variables.forEach(var -> {
            if (context.isReadOnlyData(var)) {
                ignoredVars.add(var);
            } else if (StringUtils.isNotEmpty(context.removeData(var))) {
                removedVars.add(var);
            }
        });

        StringBuilder message = new StringBuilder();
        if (CollectionUtils.isNotEmpty(ignoredVars)) {
            message.append("The following data variable(s) are READ ONLY and ignored: ")
                   .append(TextUtils.toString(ignoredVars, ","))
                   .append("\n");
        }
        if (CollectionUtils.isNotEmpty(removedVars)) {
            message.append("The following data variable(s) are removed from execution: ")
                   .append(TextUtils.toString(removedVars, ","))
                   .append("\n");
        }
        if (CollectionUtils.isEmpty(ignoredVars) && CollectionUtils.isEmpty(removedVars)) {
            message.append("None of the specified variables are removed since they either are READ-ONLY or not exist");
        }

        return message.toString();
    }

    public StepResult substringAfter(String text, String delim, String saveVar) {
        return saveSubstring(text, delim, null, saveVar);
    }

    public StepResult substringBefore(String text, String delim, String saveVar) {
        return saveSubstring(text, null, delim, saveVar);
    }

    public StepResult substringBetween(String text, String start, String end, String saveVar) {
        return saveSubstring(text, start, end, saveVar);
    }

    public StepResult split(String text, String delim, String saveVar) {
        requires(StringUtils.isNotEmpty(text), "invalid source", text);
        requiresValidAndNotReadOnlyVariableName(saveVar);

        if (context.isNullOrEmptyValue(delim)) { delim = context.getTextDelim(); }

        String targetText = context.hasData(text) ? context.getStringData(text) : text;

        List<String> array = Arrays.asList(StringUtils.splitByWholeSeparatorPreserveAllTokens(targetText, delim));
        if (context.hasData(saveVar)) { log("overwrite variable named as " + saveVar); }
        context.setData(saveVar, array);

        return StepResult.success("stored transformed array as ${" + saveVar + "}");
    }

    public StepResult appendText(String var, String appendWith) {
        requiresValidAndNotReadOnlyVariableName(var);
        String newValue = StringUtils.defaultString(context.getStringData(var)) + appendWith;
        updateDataVariable(var, context.isNullValue(newValue) ? null : newValue);
        return StepResult.success("appended '" + appendWith + "' to ${" + var + "}");
    }

    public StepResult prependText(String var, String prependWith) {
        requiresValidAndNotReadOnlyVariableName(var);
        updateDataVariable(var, prependWith + StringUtils.defaultString(context.getStringData(var)));
        return StepResult.success("prepend '" + prependWith + " to ${" + var + "}");
    }

    public StepResult saveCount(String text, String regex, String saveVar) {
        requiresValidAndNotReadOnlyVariableName(saveVar);
        List<String> groups = findTextMatches(text, regex);
        int count = CollectionUtils.size(groups);
        context.setData(saveVar, count);
        return StepResult.success("match count (" + count + ") stored to variable ${" + saveVar + "}");
    }

    public StepResult saveMatches(String text, String regex, String saveVar) {
        requiresValidAndNotReadOnlyVariableName(saveVar);
        List<String> groups = findTextMatches(text, regex);
        if (CollectionUtils.isEmpty(groups)) {
            context.removeData(saveVar);
            return StepResult.success("No matches found on text '" + text + "'");
        } else {
            context.setData(saveVar, groups);
            return StepResult.success("matches stored to variable ${" + saveVar + "}");
        }
    }

    public StepResult saveReplace(String text, String regex, String replace, String saveVar) {
        requiresValidAndNotReadOnlyVariableName(saveVar);
        if (replace == null || context.isNullValue(replace)) { replace = ""; }

        // run regex routine
        List<String> groups = findTextMatches(text, regex);
        if (CollectionUtils.isEmpty(groups)) {
            context.removeData(saveVar);
            return StepResult.success("No matches found on text '" + text + "'");
        } else {
            updateDataVariable(saveVar, JRegexUtils.replace(text, regex, replace));
            return StepResult.success("matches replaced and stored to variable " + saveVar);
        }
    }

    public StepResult assertCount(String text, String regex, String expects) {
        requiresPositiveNumber(expects, "invalid numeric value as 'expects'", expects);
        return toInt(expects, "expects") == CollectionUtils.size(findTextMatches(text, regex)) ?
               StepResult.success("expected number of matches found") :
               StepResult.fail("expected number of matches was NOT found");
    }

    public StepResult assertTextOrder(String var, String descending) {
        requiresValidVariableName(var);

        Object obj = context.getObjectData(var);
        if (obj == null) { return StepResult.fail("no value stored as ${" + var + "}"); }

        List<String> actual = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        if (obj instanceof List || obj instanceof String) {
            List list = obj instanceof String ?
                        TextUtils.toList((String) obj, context.getTextDelim(), false) : (List) obj;
            list.stream().filter(o -> o != null).forEach(o -> {
                String string = o.toString();
                actual.add(string);
                expected.add(string);
            });
        } else if (obj.getClass().isArray()) {
            int length = ArrayUtils.getLength(obj);
            for (int i = 0; i < length; i++) {
                Object o = Array.get(obj, i);
                if (o != null) {
                    String string = o.toString();
                    actual.add(string);
                    expected.add(string);
                }
            }
        } else {
            return StepResult.fail("order cannot be assert for ${" + var + "} since its value type is " +
                                   obj.getClass().getSimpleName() + "; EXPECTS array type");
        }

        if (CollectionUtils.isEmpty(expected) && CollectionUtils.isEmpty(actual)) {
            return StepResult.fail("No data for comparison, hence no order can be asserted");
        }

        // now make 'expected' organized as expected - but applying case insensitive sort
        expected.sort(CASE_INSENSIVE_SORT);

        if (BooleanUtils.toBoolean(descending)) { Collections.reverse(expected); }

        // string comparison to determine if both the actual (displayed) list and sorted list is the same
        return assertEqual(expected.toString(), actual.toString());
    }

    public StepResult assertEmpty(String text) {
        return StringUtils.isEmpty(text) ?
               StepResult.success("EXPECTED empty data found") :
               StepResult.fail("EXPECTS empty but found '" + text + "'");
    }

    public StepResult assertNotEmpty(String text) {
        return StringUtils.isNotEmpty(text) ?
               StepResult.success("EXPECTED non-empty data found") :
               StepResult.fail("EXPECTS non-empty data found empty data instead.");
    }

    public StepResult assertEqual(String expected, String actual) {
        assertEquals(context.isNullValue(expected) ? null : expected, context.isNullValue(actual) ? null : actual);

        String nullValue = context.getNullValueToken();
        String expectedForDisplay = context.truncateForDisplay(StringUtils.defaultString(expected, nullValue));
        String actualForDisplay = context.truncateForDisplay(StringUtils.defaultString(actual, nullValue));
        return StepResult.success("validated EXPECTED = ACTUAL; '%s' = '%s'", expectedForDisplay, actualForDisplay);
    }

    public StepResult assertNotEqual(String expected, String actual) {
        String nullValue = context.getNullValueToken();

        assertNotEquals(StringUtils.equals(expected, nullValue) ? null : expected,
                        StringUtils.equals(actual, nullValue) ? null : actual);

        String expectedForDisplay = context.truncateForDisplay(StringUtils.defaultString(expected, nullValue));
        String actualForDisplay = context.truncateForDisplay(StringUtils.defaultString(actual, nullValue));
        return StepResult.success("validated EXPECTED not equal to ACTUAL; '%s' not equal to '%s'",
                                  expectedForDisplay,
                                  actualForDisplay);
    }

    public StepResult assertContains(String text, String substring) {
        @NotNull String textForDisplay = context.truncateForDisplay(text);
        if (lenientContains(text, substring, false)) {
            return StepResult.success("validated text '%s' contains '%s'", textForDisplay, substring);
        } else {
            return StepResult.fail("'%s' NOT contained in '%s'", substring, textForDisplay);
        }
    }

    public StepResult assertNotContain(String text, String substring) {
        if (context.isTextMatchLeniently()) {
            String[] searchFor = {"\r", "\n"};
            String[] replaceWith = {" ", " "};
            text = StringUtils.replaceEach(text, searchFor, replaceWith);
            substring = StringUtils.replaceEach(substring, searchFor, replaceWith);
        }

        @NotNull String textForDisplay = context.truncateForDisplay(text);
        if (!StringUtils.contains(text, substring)) {
            return StepResult.success("validated text '%s' does NOT contain '%s'", textForDisplay, substring);
        } else {
            return StepResult.fail("Expects '%s' NOT to be found in '%s'", substring, textForDisplay);
        }
    }

    public StepResult assertStartsWith(String text, String prefix) {
        @NotNull String textForDisplay = context.truncateForDisplay(text);
        if (lenientContains(text, prefix, true)) {
            return StepResult.success("'%s' starts with '%s'", textForDisplay, prefix);
        } else {
            return StepResult.fail("EXPECTS '%s' to start with '%s' but it is NOT", textForDisplay, prefix);
        }
    }

    public StepResult assertEndsWith(String text, String suffix) {
        boolean contains = StringUtils.endsWith(text, suffix);
        // not so fast.. could be one of those quirky IE issues..
        if (!contains && context.isTextMatchLeniently()) {
            String lenientSuffix = StringUtils.replaceEach(StringUtils.trim(suffix),
                                                           new String[]{"\r", "\n"},
                                                           new String[]{" ", " "});
            String lenientText = StringUtils.replaceEach(StringUtils.trim(text),
                                                         new String[]{"\r", "\n"},
                                                         new String[]{" ", " "});
            contains = StringUtils.endsWith(lenientText, lenientSuffix);
        }

        @NotNull String textForDisplay = context.truncateForDisplay(text);
        if (contains) {
            return StepResult.success("'%s' ends with '%s' as EXPECTED", textForDisplay, suffix);
        } else {
            return StepResult.fail("EXPECTS '%s' to end with '%s' but it is NOT", textForDisplay, suffix);
        }
    }

    public StepResult assertArrayEqual(String array1, String array2, String exactOrder) {
        // requires(StringUtils.isNotEmpty(array1), "first array is empty", array1);
        // requires(StringUtils.isNotEmpty(array2), "second array is empty", array2);
        requires(StringUtils.isNotEmpty(exactOrder), "invalid value for exactOrder", exactOrder);

        // null corner case
        if (areBothEmpty(array1, array2)) {
            String nullValue = context.getNullValueToken();
            return StepResult.success("validated " + StringUtils.defaultString(array1, nullValue) + "=" +
                                      StringUtils.defaultString(array2, nullValue));
        }

        String delim = context.getTextDelim();
        List<String> expectedList = TextUtils.toList(array1, delim, false);
        requiresNotEmpty(expectedList, "EXPECTED array is empty", array1);

        List<String> actualList = TextUtils.toList(array2, delim, false);
        requiresNotEmpty(actualList, "ACTUAL array is empty", array2);

        if (!BooleanUtils.toBoolean(exactOrder)) {
            Collections.sort(expectedList);
            Collections.sort(actualList);
        }

        assertEquals(expectedList, actualList);
        return StepResult.success("validated ACTUAL [" + array2 + "] = [" + array1 + "] as EXPECTED");
    }

    /** assert that {@code array} contains all items in {@code expected}. */
    public StepResult assertArrayContain(String array, String expected) {
        requiresNotBlank(array, "array is blank", array);
        requiresNotBlank(expected, "expected is blank", expected);

        // null corner case
        if (areBothEmpty(array, expected)) {
            String nullValue = context.getNullValueToken();
            return StepResult.success("validated " + StringUtils.defaultString(array, nullValue) +
                                      " contain " + StringUtils.defaultString(expected, nullValue));
        }

        String delim = context.getTextDelim();

        List<String> list = TextUtils.toList(array, delim, false);
        if (CollectionUtils.isEmpty(list)) { CheckUtils.fail("'array' cannot be parsed: " + array); }

        List<String> expectedList = TextUtils.toList(expected, delim, false)
                                             .stream()
                                             .distinct()
                                             .collect(Collectors.toList());

        if (CollectionUtils.isEmpty(expectedList)) { CheckUtils.fail("'expected' cannot be parsed: " + expected); }

        if (context.isVerbose()) {
            ConsoleUtils.log("asserting that array (size " + list.size() + ") " + list + " contains " + expectedList);
        }

        // all all items in `expectedList` is removed due to match against `list`, then all items in `expected` matched
        boolean containsAll = expectedList.removeIf(list::contains);
        if (context.isVerbose()) {
            ConsoleUtils.log("All expected items matched: " + containsAll + ", remaining unmatched: " + expectedList);
        }

        if (containsAll && expectedList.isEmpty()) {
            return StepResult.success("All items in 'expected' are found in 'array'");
        }

        return StepResult.fail("Not all items in 'expected' are found in 'array': " + expected);
    }

    /** assert that {@code array} DOES NOT contains any items in {@code expected}. */
    public StepResult assertArrayNotContain(String array, String unexpected) {
        // null corner case
        if (areBothEmpty(array, unexpected)) { return StepResult.fail("Both 'array' and 'unexpected' are NULL"); }

        String delim = context.getTextDelim();

        List<String> list = TextUtils.toList(array, delim, false);
        if (CollectionUtils.isEmpty(list)) { return StepResult.success("empty array found"); }

        List<String> unexpectedList = TextUtils.toList(unexpected, delim, false)
                                               .stream()
                                               .distinct()
                                               .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(unexpectedList)) { CheckUtils.fail("'unexpected' cannot be parsed: " + unexpected);}

        // all all items in `expectedList` is removed due to match against `list`, then all items in `expected` matched
        if (unexpectedList.removeIf(item -> !list.contains(item)) && unexpectedList.isEmpty()) {
            return StepResult.success("All items in 'unexpected' are NOT found in 'array'");
        }

        return StepResult.fail("One or more items in 'unexpected' are found in 'array': " + unexpectedList);
    }

    public StepResult assertVarPresent(String var) {
        requiresValidVariableName(var);
        boolean found = context.hasData(var);
        return new StepResult(found, "Variable '" + var + "' " + (found ? "" : "DOES NOT ") + "exist", null);
    }

    public StepResult assertVarNotPresent(String var) {
        requiresValidVariableName(var);
        boolean found = context.hasData(var);
        return new StepResult(!found, "Variable '" + var + "' " + (found ? "INDEED" : "does not") + " exist", null);
    }

    public StepResult saveVariablesByRegex(String var, String regex) {
        requiresValidAndNotReadOnlyVariableName(var);
        requiresNotBlank(regex, "invalid regex", regex);
        return saveMatchedVariables(var, regex, context.getDataNamesByRegex(regex));
    }

    public StepResult saveVariablesByPrefix(String var, String prefix) {
        requiresValidAndNotReadOnlyVariableName(var);
        requiresNotBlank(prefix, "invalid prefix", prefix);
        return saveMatchedVariables(var, prefix, context.getDataNames(prefix));
    }

    public StepResult failImmediate(String text) {
        context.setFailImmediate(true);
        return StepResult.fail(text);
    }

    public StepResult verbose(String text) {
        if (text == null) { text = context.getNullValueToken(); }

        // we should not allow to print this.. security violation
        if (context.containsCrypt(text)) {
            // but we should still process any functions or expressions
            context.replaceTokens(text);
            log("crypto found; no data variable expansion");
        }

        // with priority so that this log will not be silenced
        context.getLogger().log(this, text, true);
        return StepResult.success(text);
    }

    public StepResult waitFor(String waitMs) {
        requires(NumberUtils.isDigits(waitMs), "invalid waitMs", waitMs);
        waitFor(NumberUtils.toInt(waitMs));
        return StepResult.success();
    }

    public StepResult repeatUntil(String steps, String maxWaitMs) {
        requiresPositiveNumber(steps, "Invalid step count", steps);
        int stepCount = NumberUtils.toInt(steps);
        requires(stepCount > 0, "At least 1 step is required", steps);

        long maxWait = -1;
        if (StringUtils.isNotBlank(maxWaitMs) && !StringUtils.equals(StringUtils.trim(maxWaitMs), "-1")) {
            requiresPositiveNumber(maxWaitMs, "maxWaitMs must be a positive number", maxWait);
            maxWait = NumberUtils.toLong(maxWaitMs);
            requires(maxWait > 1000, "minimum maxWaitMs is 1 second", maxWaitMs);
        }

        TestStep currentTestStep = context.getCurrentTestStep();
        CommandRepeater commandRepeater = currentTestStep.getCommandRepeater();
        if (commandRepeater == null) {
            return StepResult.fail("Unable to gather the appropriate commands to perform repeat-until execution");
        }

        return commandRepeater.start();
    }

    /**
     * invoke a set of test steps stored in {@code file}, referenced by {@code name}.  {@code file} must be
     * fully qualified, whilst Nexial function may be used (e.g. {@code $(syspath)}).
     *
     * @param file the full path of the macro library.
     * @param name the name of the macro to invoke
     * @return pass/fail based on the validity of the referenced macro/file.  If macro {@code name} or library
     *     ({@code file}) is not found, a failure is returned with fail-immediate in effect.
     */
    public StepResult macro(String file, String sheet, String name) {
        return failImmediate("Runtime error: Macro reference (" + file + "," + sheet + "," + name + ") not expanded");
    }

    /**
     * identify a set of test steps considered as section which includes number of test steps {@code steps}
     *
     * @param steps the number test steps to be included in the section
     * @return pass
     */
    public StepResult section(String steps) {
        requiresPositiveNumber(steps, "Invalid step count", steps);
        int stepCount = NumberUtils.toInt(steps);
        requires(stepCount > 0, "At least 1 step is required", steps);
        return StepResult.success();
    }

    /**
     * forcefully move a resource in the output directory to the cloud. <B>ONLY WORKS IF
     * <code>nexial.outputToCloud</code> IS SET TO <code>true</code></B>
     **/
    public StepResult outputToCloud(String resource) {
        requiresNotBlank(resource, "invalid resource", resource);

        if (!context.isOutputToCloud()) { return StepResult.skipped("Execution not configured for cloud-based output");}

        // resource is a fully-qualified file?
        if (!FileUtil.isFileReadable(resource, 1)) {
            // resource is relative path?
            String fqResource = StringUtils.appendIfMissing(new Syspath().out("fullpath"), separator) + resource;
            if (FileUtil.isFileReadable(fqResource, 1)) {
                resource = fqResource;
            } else {
                return StepResult.fail("resource not found in OUTPUT directory: " + resource);
            }
        }

        addLinkRef(null, "Click here", resource);
        return StepResult.success("resource move to cloud storage " + resource);
    }

    /** Like JUnit's Assert.assertEquals, but handles "regexp:" strings like HTML Selenese */
    public void assertEquals(String expected, String actual) {
        assertTrue(NL + displayForCompare("expected", expected, "actual", actual),
                   assertEqualsInternal(expected, actual));
    }

    public static String displayForCompare(String label1, Object data1, String label2, Object data2) {
        // 1. label treatment
        if (StringUtils.isBlank(label1)) { label1 = "expected"; }
        if (StringUtils.isBlank(label2)) { label2 = "actual"; }
        int labelLength = Math.max(label1.length(), label2.length());
        label1 = StringUtils.rightPad(label1, labelLength, " ");
        label2 = StringUtils.rightPad(label2, labelLength, " ");

        // 2. check for special types
        List<String> data1List = null;
        List<String> data2List = null;
        Map<String, String> data1Map = null;
        Map<String, String> data2Map = null;

        // 3. form display for data1 :: first pass
        String data1Display = label1;
        if (data1 == null) {
            data1Display += "=<null/undefined>";
        } else if (data1.getClass().isArray()) {
            data1List = Arrays.asList(TextUtils.toStringArray(data1));
        } else if (data1 instanceof Collection) {
            data1List = TextUtils.toStringList(data1);
        } else if (data1 instanceof Map) {
            data1Map = TextUtils.toStringMap(data1);
        } else if (data1 instanceof String) {
            String data1String = (String) data1;
            if (StringUtils.isEmpty(data1String)) {
                data1Display += "=<empty>";
            } else if (StringUtils.isBlank(data1String)) {
                data1Display += "=<blank>[" + data1 + "]";
            } else {
                data1Display += "=" + data1String;
            }
        } else {
            data1Display += "=" + data1;
        }

        // 3. form display for data2 :: first pass
        String data2Display = label2;
        if (data2 == null) {
            data2Display += "=<null/undefined>";
        } else if (data2.getClass().isArray()) {
            data2List = Arrays.asList(TextUtils.toStringArray(data2));
        } else if (data2 instanceof Collection) {
            data2List = TextUtils.toStringList(data2);
        } else if (data2 instanceof Map) {
            data2Map = TextUtils.toStringMap(data2);
        } else if (data2 instanceof String) {
            String data2String = (String) data2;
            if (StringUtils.isEmpty(data2String)) {
                data2Display += "=<empty>";
            } else if (StringUtils.isBlank(data2String)) {
                data2Display += "=<blank>[" + data2 + "]";
            } else {
                data2Display += "=" + data2String;
            }
        } else {
            data2Display += "=" + data2;
        }

        if (data1List != null) {
            if (data2List != null) {
                Pair<String, String> lines = displayAligned(data1List, data2List);
                data1Display += "=" + lines.getKey();
                data2Display += "=" + lines.getValue();
            } else {
                data1Display += "=" + TextUtils.toString(data1List, ",");
            }
        } else {
            if (data1Map != null) {
                if (data2Map != null) {
                    return displayAsMapDiff(label1, data1Map, label2, data2Map);
                } else {
                    data1Display += "=" + data1Map;
                }
            } else {
                if (data2Map != null) { data2Display += "=" + data2Map; }
                if (data2List != null) { data2Display += "=" + TextUtils.toString(data2List, ","); }
            }
        }

        return data1Display + NL + data2Display;
    }

    public void waitFor(int waitMs) {
        try { Thread.sleep(waitMs); } catch (InterruptedException e) { log("sleep - " + e.getMessage());}
    }

    public int toPositiveInt(String something, String label) {
        int integer = toInt(something, label);
        requires(integer >= 0, "Invalid " + label + "; EXPECTS a number greater than 0", something);
        return integer;
    }

    public long toPositiveLong(String something, String label) {
        long integer = toLong(something, label);
        requires(integer >= 0, "Invalid " + label + "; EXPECTS a number greater than 0", something);
        return integer;
    }

    public long toLong(String something, String label) {
        double number = toDouble(something, label);
        long whole = (long) number;
        if (whole != number) {
            ConsoleUtils.log("convert " + label + " " + something + " to " + whole + "; possible loss of precision");
        }
        return whole;
    }

    /**
     * ensuring we can convert "something" to an integer, even at the expense of precision loss.
     */
    public int toInt(String something, String label) {
        double number = toDouble(something, label);
        int integer = (int) number;
        if (integer != number) {
            ConsoleUtils.log("convert " + label + " " + something + " to " + integer + "; possible loss of precision");
        }
        return integer;
    }

    public double toPositiveDouble(String something, String label) {
        double number = toDouble(something, label);
        requires(number >= 0, "Invalid " + label + "; EXPECTS a number greater than 0", something);
        return number;
    }

    public double toDouble(String something, String label) {
        requires(StringUtils.isNotBlank(something), "invalid " + label, something);

        // check for starting-with-equal (stoopid excel!)
        if (StringUtils.startsWith(something, "=")) { something = something.substring(1); }

        // check for double quotes
        if (StringUtils.startsWith(something, "\"") && StringUtils.endsWith(something, "\"")) {
            something = StringUtils.substringBeforeLast(StringUtils.substringAfter(something, "\""), "\"");
        }

        boolean isNegative = StringUtils.startsWith(something, "-");
        something = StringUtils.removeStart(something, "-");
        while (StringUtils.startsWith(something, "0") && StringUtils.length(something) > 1) {
            something = StringUtils.removeStart(something, "0");
        }

        requires(NumberUtils.isCreatable(something), "invalid " + label, something);
        return NumberUtils.toDouble((isNegative ? "-" : "") + something);
    }

    public void addLinkRef(String message, String label, String link) {
        if (StringUtils.isBlank(link)) { return; }

        if (context.isOutputToCloud() && OutputFileUtils.isContentReferencedAsFile(link, context)) {
            try {
                link = context.getOtc().importFile(new File(link), true);
            } catch (IOException e) {
                log(toCloudIntegrationNotReadyMessage(link) + ": " + e.getMessage());
            }
        }

        if (context != null) {
            TestStep testStep = context.getCurrentTestStep();
            if (testStep != null && testStep.getWorksheet() != null) {
                // test step undefined could mean that we are in interactive mode, or we are running unit testing
                context.setData(OPT_LAST_OUTPUT_LINK, link);
                context.setData(OPT_LAST_OUTPUT_PATH, StringUtils.contains(link, "\\") ?
                                                      StringUtils.substringBeforeLast(link, "\\") :
                                                      StringUtils.substringBeforeLast(link, "/"));

                // if there's no message, then we'll create link in the screenshot column of the SAME row (as test step)
                if (StringUtils.isEmpty(message)) {
                    testStep.addStepOutput(link, label);
                } else {
                    testStep.addNestedScreenCapture(link, message, label);
                }
            }
        }
    }

    public void logDeprecated(String deprecated, String replacement) {
        errorToOutput(deprecated + " IS DEPRECATED. PLEASE CONSIDER USING " + replacement + " INSTEAD", null);
    }

    protected boolean requiresValidAndNotReadOnlyVariableName(String var) {
        requires(isValidVariable(var), "Invalid variable name", var);
        requires(!context.isReadOnlyData(var), "Overriding read-only variable is NOT permitted", var);
        return true;
    }

    protected String postScreenshot(TestStep testStep, File file) {
        if (file == null) {
            error("Unable to save screenshot for " + testStep);
            return null;
        }

        String caption = context.getStringData(SCREENSHOT_CAPTION);
        if (StringUtils.isNotBlank(caption)) {
            CaptionModel model = new CaptionModel();
            model.addCaptions(TextUtils.toList(caption, "\n", true));

            String color = context.getStringData(SCREENSHOT_CAPTION_COLOR);
            if (StringUtils.isNotBlank(color)) { model.setCaptionColor(color); }

            String[] position = StringUtils.split(context.getStringData(SCREENSHOT_CAPTION_POSITION),
                                                  context.getTextDelim());
            if (ArrayUtils.getLength(position) == 2) {
                CaptionPositions captionPosition = CaptionPositions.toCaptionPosition(position[0], position[1]);
                if (captionPosition != null) { model.setPosition(captionPosition); }
            }

            if (context.hasData(SCREENSHOT_CAPTION_WRAP)) {
                model.setWrap(context.getBooleanData(SCREENSHOT_CAPTION_WRAP));
            }

            if (context.hasData(SCREENSHOT_CAPTION_ALPHA)) {
                double alpha = context.getDoubleData(SCREENSHOT_CAPTION_ALPHA);
                if (alpha != UNDEFINED_DOUBLE_DATA) { model.setAlpha((float) alpha); }
            }

            if (context.hasData(SCREENSHOT_CAPTION_NO_BKGRD)) {
                model.setWithBackground(!context.getBooleanData(SCREENSHOT_CAPTION_NO_BKGRD));
            }

            ImageCaptionHelper.addCaptionToImage(file, model);
        }

        if (context.isOutputToCloud()) {
            try {
                String cloudUrl = context.getOtc().importMedia(file, true);
                context.setData(OPT_LAST_SCREENSHOT_NAME, cloudUrl);
                return cloudUrl;
            } catch (IOException e) {
                log(toCloudIntegrationNotReadyMessage(file.toString()) + ": " + e.getMessage());
            }
        } else {
            context.setData(OPT_LAST_SCREENSHOT_NAME, file.getAbsolutePath());
        }

        // return local file if `output-to-cloud` is disabled or failed to transfer to cloud
        return file.getAbsolutePath();
    }

    /**
     * this method - TO BE USED INTERNALLY ONLY - is created to compensate the data state discrepancy when such is
     * initially set via `-override` flag or via `-D...` environment variable. When a data variable is defined prior to
     * Nexial execution, for example,
     * <pre>
     * ./nexial.sh -script ... ... -override myData=myValue
     * </pre>
     * <p>
     * Such data variable is also added to the System properties. As such, Nexial will not attempt to override the
     * corresponding System property when the same data value is being manipulated during execution
     * (e.g. {@link #save(String, String)} or {@link #saveMatches(String, String, String)}). However, when retrieving
     * data variable, Nexial will (and must) consider the System properties and in fact it does so <b>AHEAD</b> of
     * its internal memory space for data variables. This can cause confusion since the initially-set data variable
     * does not appear to be overwritten.  This method is created to overcome such issue. Use this only for the
     * user-defined data variables. System variables and Java/OS specific ones should not be impacted by this phenomena.
     * since when
     */
    protected void updateDataVariable(String var, String value) {
        if (context.containsCrypt(TOKEN_START + var + TOKEN_END)) {
            throw new TokenReplacementException("Tampering with encrypted data is NOT permissible");
        }
        // add `var` to context.. also update sys prop if `var` already exists in system and is not a read-only variable
        // - if sys prop exists means the same var was already declared either in cmdline or in setup.properties
        // - if same var is a read-only variable, then we don't want to override it
        // context.setData(var, value, StringUtils.isNotBlank(System.getProperty(var)) && !context.isReadOnlyData(var));
        context.setData(var, value, StringUtils.isNotBlank(System.getProperty(var)));
    }

    @NotNull
    protected StepResult saveMatchedVariables(String var, String regex, Collection<String> matched) {
        int matchCount = CollectionUtils.size(matched);
        if (matchCount < 1) {
            ConsoleUtils.log("No data variable matching to " + regex);
            context.removeData(var);
        } else {
            updateDataVariable(var, TextUtils.toString(matched, context.getTextDelim()));
        }

        return StepResult.success(matchCount + " matched variable(s) saved to data variable '" + var + "'");
    }

    protected boolean areBothEmpty(String value1, String value2) {
        return (StringUtils.isEmpty(value1) || context.isNullValue(value1) || context.isEmptyValue(value1)) &&
               (StringUtils.isEmpty(value2) || context.isNullValue(value2) || context.isEmptyValue(value2));
    }

    /**
     * Compares two strings, but handles "regexp:" strings like HTML Selenese
     *
     * @return true if actual matches the expectedPattern, or false otherwise
     */
    protected static boolean seleniumEquals(String expectedPattern, String actual) {
        if (expectedPattern == null || actual == null) { return expectedPattern == null && actual == null; }

        if (actual.startsWith("regexp:") || actual.startsWith("regex:")
            || actual.startsWith("regexpi:") || actual.startsWith("regexi:")) {
            // swap 'em
            String tmp = actual;
            actual = expectedPattern;
            expectedPattern = tmp;
        }

        Boolean b = handleRegex("regexp:", expectedPattern, actual, 0);
        if (b != null) { return b; }

        b = handleRegex("regex:", expectedPattern, actual, 0);
        if (b != null) { return b; }

        b = handleRegex("regexpi:", expectedPattern, actual, CASE_INSENSITIVE);
        if (b != null) { return b; }

        b = handleRegex("regexi:", expectedPattern, actual, CASE_INSENSITIVE);
        if (b != null) { return b; }

        if (expectedPattern.startsWith("exact:")) {
            String expectedExact = expectedPattern.replaceFirst("exact:", "");
            if (!expectedExact.equals(actual)) {
                ConsoleUtils.log("expected " + actual + " to match " + expectedPattern);
                return false;
            }
            return true;
        }

        String expectedGlob = expectedPattern.replaceFirst("glob:", "");
        expectedGlob = expectedGlob.replaceAll("([\\]\\[\\\\{\\}$\\(\\)\\|\\^\\+.])", "\\\\$1");

        expectedGlob = expectedGlob.replaceAll("\\*", ".*");
        expectedGlob = expectedGlob.replaceAll("\\?", ".");
        if (!Pattern.compile(expectedGlob, Pattern.DOTALL).matcher(actual).matches()) {
            // ConsoleUtils.log("expected \"" + actual + "\" to match glob \"" + expectedPattern
            //                  + "\" (had transformed the glob into regexp \"" + expectedGlob + "\"");
            return false;
        }
        return true;
    }

    /**
     * Compares two objects, but handles "regexp:" strings like HTML Selenese
     *
     * @return true if actual matches the expectedPattern, or false otherwise
     * @see #seleniumEquals(String, String)
     */
    protected static boolean seleniumEquals(Object expected, Object actual) {
        if (expected == null) { return actual == null; }
        if (expected instanceof String && actual instanceof String) {
            return seleniumEquals((String) expected, (String) actual);
        }
        return expected.equals(actual);
    }

    protected static String stringArrayToString(String[] sa) {
        StringBuilder sb = new StringBuilder("{");
        for (String aSa : sa) { sb.append(" ").append("\"").append(aSa).append("\""); }
        sb.append(" }");
        return sb.toString();
    }

    protected static String join(String[] sa, char c) {
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < sa.length; j++) {
            sb.append(sa[j]);
            if (j < sa.length - 1) { sb.append(c); }
        }
        return sb.toString();
    }

    protected boolean lenientContains(String text, String prefix, boolean startsWith) {
        boolean valid = startsWith ? StringUtils.startsWith(text, prefix) : StringUtils.contains(text, prefix);
        // not so fast.. could be one of those quarky IE issues..
        if (!valid && context.isTextMatchLeniently()) {
            String[] searchFor = {"\r", "\n"};
            String[] replaceWith = {" ", " "};
            String lenientPrefix = StringUtils.replaceEach(StringUtils.trim(prefix), searchFor, replaceWith);
            String lenientText = StringUtils.replaceEach(StringUtils.trim(text), searchFor, replaceWith);
            valid = startsWith ?
                    StringUtils.startsWith(lenientText, lenientPrefix) :
                    StringUtils.contains(lenientText, lenientPrefix);
        }

        return valid;
    }

    /** Like JUnit's Assert.assertEquals, but knows how to compare string arrays */
    protected void assertEquals(Object expected, Object actual) {
        if (expected == null) {
            assertTrue(NL + "expected=null" + NL + "actual  =" + actual, actual == null);
        } else if (expected instanceof String && actual instanceof String) {
            assertEquals((String) expected, (String) actual);
        } else if (expected instanceof String && actual instanceof String[]) {
            assertEquals((String) expected, (String[]) actual);
        } else if (expected instanceof String && actual instanceof Number) {
            assertEquals((String) expected, actual.toString());
        } else if (expected instanceof Number && actual instanceof String) {
            assertEquals(expected.toString(), (String) actual);
        } else if (expected instanceof String[] && actual instanceof String[]) {
            assertEquals((String[]) expected, (String[]) actual);
        } else {
            assertTrue(NL + displayForCompare("expected", expected, "actual", actual), expected.equals(actual));
        }
    }

    /**
     * Like JUnit's Assert.assertEquals, but joins the string array with commas, and handles "regexp:"
     * strings like HTML Selenese
     */
    protected void assertEquals(String expected, String[] actual) { assertEquals(expected, join(actual, ',')); }

    /** Asserts that two string arrays have identical string contents */
    protected void assertEquals(String[] expected, String[] actual) {
        String comparisonDumpIfNotEqual = verifyEqualsAndReturnComparisonDumpIfNot(expected, actual);
        if (comparisonDumpIfNotEqual != null) {
            error(MSG_FAIL + NL + comparisonDumpIfNotEqual);
            throw new AssertionError(comparisonDumpIfNotEqual);
        }
    }

    protected void assertTrue(String message, boolean condition) { if (!condition) { CheckUtils.fail(message); } }

    /** Asserts that two objects are not the same (compares using .equals()) */
    protected void assertNotEquals(Object expected, Object actual) {
        if (expected == null) {
            // both should be null
            assertFalse(NL + "expected=null " + NL + "actual  =" + actual, actual == null);
            return;
        }

        if (expected.equals(actual)) { CheckUtils.fail(NL + displayForCompare("expected", expected, "actual", actual));}
    }

    protected void assertTrue(boolean condition) { assertTrue(null, condition); }

    protected void assertFalse(String message, boolean condition) { assertTrue(message, !condition); }

    protected void assertFalse(boolean condition) { assertTrue(null, !condition); }

    /** Asserts that two booleans are not the same */
    protected void assertNotEquals(boolean expected, boolean actual) {
        assertNotEquals(Boolean.valueOf(expected), Boolean.valueOf(actual));
    }

    protected void verifyFalse(String description, boolean b) {
        description = StringUtils.isNotBlank(description) ? description : "";
        if (!b) {
            log(MSG_PASS + description);
        } else {
            error(MSG_FAIL + description);
        }
        verifyFalse(b);
    }

    protected void verifyFalse(boolean b) {
        try {
            assertFalse(b);
        } catch (Error e) {
            error(throwableToString(e));
        }
    }

    /** Like assertTrue, but fails at the end of the test (during tearDown) */
    protected void verifyTrue(boolean b) {
        try {
            assertTrue(b);
        } catch (Error e) {
            error(throwableToString(e));
        }
    }

    protected void verifyTrue(String description, boolean b) {
        description = StringUtils.isNotBlank(description) ? description : "";
        if (b) {
            log(MSG_PASS + description);
        } else {
            error(MSG_FAIL + description);
        }
        verifyTrue(b);
    }

    protected Method getCommandMethod(String command, String... params) {
        if (StringUtils.isBlank(command)) {
            CheckUtils.fail("Unknown command " + command);
            return null;
        }

        String methodName = StringUtils.substringBefore(StringUtils.substringBefore(command, "("), ".");

        if (!commandMethods.containsKey(methodName)) {
            CheckUtils.fail("Unknown command " + command);
            return null;
        }

        Method m = commandMethods.get(methodName);
        // fill in null for missing params later
        if (PARAM_AUTO_FILL_COMMANDS.contains(getTarget() + "." + methodName)) { return m; }

        int actualParamCount = ArrayUtils.getLength(params);
        int expectedParamCount = m.getParameterCount();
        if (actualParamCount == expectedParamCount) { return m; }

        CheckUtils.fail("MISMATCHED parameters - " + getTarget() + "." + methodName +
                        " EXPECTS " + expectedParamCount + " but found " + actualParamCount);
        return null;
    }

    protected void shutdown() throws IOException {
        if (screenRecorder != null) {
            screenRecorder.stop();
            screenRecorder = null;
        }
    }

    protected List<String> findMatches(String var, String regex) {
        return findTextMatches(getContextValueAsString(var), regex);
    }

    protected List<String> findTextMatches(String text, String regex) {
        requiresNotBlank(text, "invalid text", text);
        requires(StringUtils.isNotBlank(regex), "invalid regular expression", regex);
        return RegexUtils.eagerCollectGroups(text, regex, true, true);
    }

    protected String getContextValueAsString(String var) {
        requiresValidVariableName(var);

        // convert stored variable into text
        Object value = context.getObjectData(var);
        if (value == null) { return null; }

        if (value.getClass().isArray()) {
            int length = ArrayUtils.getLength(value);
            String text = "";
            for (int i = 0; i < length; i++) {
                text += Objects.toString(Array.get(value, i));
                if (i < length - 1) { text += lineSeparator(); }
            }
            return text;
        }

        if (value instanceof List) { return TextUtils.toString((List) value, lineSeparator()); }
        if (value instanceof Map) { return TextUtils.toString((Map) value, lineSeparator(), "="); }
        return Objects.toString(value);
    }

    protected StepResult saveSubstring(List<String> textArray, String delimStart, String delimEnd, String var) {
        requires(StringUtils.isNotEmpty(delimStart) || StringUtils.isNotEmpty(delimEnd), "invalid delimiter");
        requiresValidAndNotReadOnlyVariableName(var);

        boolean before = StringUtils.isNotEmpty(delimEnd) && StringUtils.isEmpty(delimStart);
        boolean between = StringUtils.isNotEmpty(delimEnd) && StringUtils.isNotEmpty(delimStart);

        List<String> substrings =
            textArray.stream()
                     .map(text -> between ?
                                  StringUtils.substringBetween(text, delimStart, delimEnd) :
                                  before ? StringUtils.substringBefore(text, delimEnd) :
                                  StringUtils.substringAfter(text, delimStart)).collect(Collectors.toList());

        context.setData(var, substrings);
        return StepResult.success(textArray.size() + " substring(s) stored to ${" + var + "}");
    }

    protected StepResult saveSubstring(String text, String delimStart, String delimEnd, String var) {
        requiresNotBlank(text, "invalid text", text);
        requires(StringUtils.isNotEmpty(delimStart) || StringUtils.isNotEmpty(delimEnd), "invalid delimiter");
        requiresValidAndNotReadOnlyVariableName(var);

        boolean before = StringUtils.isNotEmpty(delimEnd) && StringUtils.isEmpty(delimStart);
        boolean between = StringUtils.isNotEmpty(delimEnd) && StringUtils.isNotEmpty(delimStart);

        String substr = between ? StringUtils.substringBetween(text, delimStart, delimEnd) :
                        before ? StringUtils.substringBefore(text, delimEnd) :
                        StringUtils.substringAfter(text, delimStart);
        updateDataVariable(var, substr);
        return StepResult.success("substring '" + substr + "' stored to ${" + var + "}");
    }

    protected void collectCommandMethods() {
        CommandDiscovery discovery = CommandDiscovery.getInstance();

        Method[] allMethods = this.getClass().getDeclaredMethods();
        Arrays.stream(allMethods).forEach(m -> {
            if (Modifier.isPublic(m.getModifiers()) &&
                !Modifier.isStatic(m.getModifiers()) &&
                StepResult.class.isAssignableFrom(m.getReturnType()) &&
                !StringUtils.equals(m.getName(), "execute")) {

                commandMethods.put(m.getName(), m);

                if (CommandDiscovery.isInDiscoveryMode()) {
                    // workaround for kotlin (var is reserved in kotlin)
                    discovery.addCommand(getTarget(),
                                         m.getName() + "(" +
                                         Arrays.stream(m.getParameters())
                                               .map(param -> StringUtils.equals(param.getName(), "Var") ?
                                                             "var" : param.getName())
                                               .collect(Collectors.joining(",")) +
                                         ")");
                }
            }
        });
    }

    protected static Boolean handleRegex(String prefix, String expectedPattern, String actual, int flags) {
        if (!expectedPattern.startsWith(prefix)) { return null; }

        String expectedRegEx = expectedPattern.replaceFirst(prefix, ".*") + ".*";
        Pattern p = Pattern.compile(expectedRegEx, flags);
        if (p.matcher(actual).matches()) { return TRUE; }

        ConsoleUtils.log("expected " + actual + " to match regexp " + expectedPattern);
        return FALSE;
    }

    protected static boolean assertEqualsInternal(String expected, String actual) {
        if (expected == null && actual == null) { return true; }

        ExecutionContext context = ExecutionThread.get();
        if (context.isTextMatchUseTrim()) {
            expected = StringUtils.trim(expected);
            actual = StringUtils.trim(actual);
        }

        if (context.isTextMatchAsNumber()) {
            boolean isExpectedANumber = false;
            BigDecimal expectedBD = null;
            try {
                expectedBD = NumberUtils.createBigDecimal(StringUtils.trim(expected));
                isExpectedANumber = true;
            } catch (NumberFormatException e) {
                // ConsoleUtils.log("The 'expected' is expected a number BUT its not: " + expected);
            }

            boolean isActualANumber = false;
            BigDecimal actualBD = null;
            try {
                actualBD = NumberUtils.createBigDecimal(StringUtils.trim(actual));
                isActualANumber = true;
            } catch (NumberFormatException e) {
                // ConsoleUtils.log("The 'actual' is expected a number BUT its not: " + actual);
            }

            if (isActualANumber && isExpectedANumber) {
                // number conversion works for both 'expected' and 'actual'
                if (expectedBD != null && actualBD != null) {
                    // both are number, then we should assert by double
                    return expectedBD.doubleValue() == actualBD.doubleValue();
                } else {
                    // both null.. so they matched!
                    return true;
                }
            }
            // ELSE: so one of them is not a number... moving on
        }

        if (context.isTextMatchCaseInsensitive()) {
            expected = StringUtils.lowerCase(expected);
            actual = StringUtils.lowerCase(actual);
        }

        boolean equals = seleniumEquals(expected, actual);
        if (!equals && context.isTextMatchLeniently()) {
            // not so fast.. could be one of those quirky IE issues..
            String lenientExpected = TextUtils.toOneLine(expected, true);
            String lenientActual = TextUtils.toOneLine(actual, true);
            equals = StringUtils.equals(lenientExpected, lenientActual);
        }

        return equals;
    }

    protected static String verifyEqualsAndReturnComparisonDumpIfNot(String[] expected, String[] actual) {
        boolean misMatch = false;
        if (expected.length != actual.length) { misMatch = true; }
        for (int j = 0; j < expected.length; j++) {
            if (!seleniumEquals(expected[j], actual[j])) {
                misMatch = true;
                break;
            }
        }

        if (misMatch) { return displayForCompare("expected", expected, "actual", actual); }

        return null;
    }

    protected static String throwableToString(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }

    protected void log(String message) {
        if (context != null && context.getLogger() != null) { context.getLogger().log(this, message); }
    }

    protected void error(String message) { error(message, null); }

    protected void error(String message, Throwable e) {
        if (StringUtils.isNotBlank(message) && context != null && context.getLogger() != null) {
            context.getLogger().error(this, message, e);
        }
    }

    protected void errorToOutput(String message, Throwable e) {
        if (StringUtils.isNotBlank(message) && context != null && context.getLogger() != null) {
            context.getLogger().errorToOutput(this, message, e);
        }
    }

    /**
     * create a file with {@code output} as its text content and its name based on current step and {@code extension}.
     * <p>
     * to improve readability and user experience, use {@code caption} to describe such file on the execution output.
     */
    protected void addOutputAsLink(String caption, String output, String extension) {
        String outFile = context.generateTestStepOutput(extension);

        try {
            FileUtils.writeStringToFile(new File(outFile), output, DEF_FILE_ENCODING);
            addLinkRef(caption, extension + " report", outFile);
        } catch (IOException e) {
            error("Unable to write to '" + outFile + "': " + e.getMessage(), e);
        }
    }

    /**
     * create a file with {@code output} as its content and its name based on current step and {@code extension}.
     * <p>
     * to improve readability and user experience, use {@code caption} to describe such file on the execution output.
     */
    protected void addOutputAsLink(String caption, BufferedImage output, String extension) {
        String outFile = context.generateTestStepOutput(extension);

        try {
            ImageIO.write(output, extension, new File(outFile));
            addLinkRef(caption, "comparison", outFile);
        } catch (IOException e) {
            error("Unable to create image file '" + outFile + "': " + e.getMessage(), e);
        }
    }

    protected boolean isCryptRestricted(Method method) {
        return method != null && CRYPT_RESTRICTED_COMMANDS.contains(getTarget() + "." + method.getName());
    }

    protected Object[] resolveParamValues(Method m, String... params) {
        int numOfParamSpecified = ArrayUtils.getLength(params);
        int numOfParamExpected = ArrayUtils.getLength(m.getParameterTypes());

        boolean cryptRestricted = isCryptRestricted(m);

        Object[] args = new Object[numOfParamExpected];
        for (int i = 0; i < args.length; i++) {
            if (i >= numOfParamSpecified) {
                args[i] = "";
            } else {
                String param = params[i];
                if (cryptRestricted && context.containsCrypt(param)) {
                    args[i] = param;
                } else {
                    args[i] = context.replaceTokens(param);
                }
            }
        }

        return args;
    }

    @NotNull
    protected File resolveFileResource(String resource) throws IOException {
        if (!ResourceUtils.isWebResource(resource)) { return new File(resource); }

        String target = StringUtils.appendIfMissing(JAVA_IO_TMPDIR, separator) +
                        StringUtils.substringAfterLast(resource, "/");
        return WsCommand.saveWebContent(resource, new File(target));
    }

    @NotNull
    protected static File resolveSaveTo(String saveTo, String defaultFileName) {
        File saveFile = new File(saveTo);
        if (StringUtils.endsWithAny(saveTo, "/", "\\") || saveFile.isDirectory()) {
            saveFile.mkdirs();
            return new File(StringUtils.appendIfMissing(saveTo, separator) + defaultFileName);
        } else {
            saveFile.getParentFile().mkdirs();
            return saveFile;
        }
    }

    @NotNull
    protected List<String> paramToList(String array) {
        List<String> list = new ArrayList<>();
        if (StringUtils.isEmpty(array)) { return list; }

        String delim = context.getTextDelim();

        array = StringUtils.remove(array, "\r");
        array = StringUtils.replace(array, "\\" + delim, TMP_DELIM);
        if (!StringUtils.equals(delim, "\n")) { array = StringUtils.replace(array, delim, "\n"); }
        List<String> values = TextUtils.toList(array, "\n", false);
        values.forEach(v -> list.add(StringUtils.replace(v, TMP_DELIM, delim)));

        return list;
    }

    private static Pair<String, String> displayAligned(List<String> list1, List<String> list2) {
        if (CollectionUtils.isEmpty(list1)) { return new ImmutablePair<>("", TextUtils.toString(list2, ",")); }
        if (CollectionUtils.isEmpty(list2)) { return new ImmutablePair<>(TextUtils.toString(list1, ","), ""); }

        StringBuilder buffer1 = new StringBuilder();
        StringBuilder buffer2 = new StringBuilder();

        int list1Size = list1.size();
        int list2Size = list2.size();
        int commonSize = Math.min(list1Size, list2Size);

        for (int i = 0; i < commonSize; i++) {
            String item1 = list1.get(i);
            String item2 = list2.get(i);
            int maxWidth = Math.max(StringUtils.length(item1), StringUtils.length(item2));
            buffer1.append(StringUtils.rightPad(item1, maxWidth, " ")).append(",");
            buffer2.append(StringUtils.rightPad(item2, maxWidth, " ")).append(",");
        }

        if (list1Size > commonSize) {
            for (int i = commonSize; i < list1Size; i++) {
                String item1 = list1.get(i);
                String item2 = "<missing>";
                int maxWidth = Math.max(StringUtils.length(item1), StringUtils.length(item2));
                buffer1.append(StringUtils.rightPad(item1, maxWidth, " ")).append(",");
                buffer2.append(StringUtils.rightPad(item2, maxWidth, " ")).append(",");
            }
        }

        if (list2Size > commonSize) {
            for (int i = commonSize; i < list2Size; i++) {
                String item1 = "<missing>";
                String item2 = list2.get(i);
                int maxWidth = Math.max(StringUtils.length(item1), StringUtils.length(item2));
                buffer1.append(StringUtils.rightPad(item1, maxWidth, " ")).append(",");
                buffer2.append(StringUtils.rightPad(item2, maxWidth, " ")).append(",");
            }
        }

        return new ImmutablePair<>(StringUtils.removeEnd(buffer1.toString(), ","),
                                   StringUtils.removeEnd(buffer2.toString(), ","));
    }

    private static String displayAsMapDiff(String label1,
                                           Map<String, String> map1,
                                           String label2,
                                           Map<String, String> map2) {
        List<String> headers = Arrays.asList("key",
                                             StringUtils.trim(label1) + " value",
                                             StringUtils.trim(label2) + " value",
                                             "matched");
        List<List<String>> records = new ArrayList<>();

        List<String> map1Keys = new ArrayList<>(map1.keySet());
        List<String> map2Keys = new ArrayList<>(map2.keySet());

        map1Keys.forEach(map1Key -> {
            List<String> record = new ArrayList<>();
            record.add(map1Key);
            record.add(map1.get(map1Key));
            record.add(StringUtils.defaultString(map2.get(map1Key), "<missing>"));
            record.add(StringUtils.equals(record.get(1), record.get(2)) ? "yes" : "NO");
            records.add(record);

            map2Keys.remove(map1Key);
        });

        map2Keys.forEach(map2Key -> {
            List<String> record = new ArrayList<>();
            record.add(map2Key);
            record.add("<missing>");
            record.add(map2.get(map2Key));
            record.add("NO");
            records.add(record);
        });

        return TextUtils.createAsciiTable(headers, records, List::get);
    }
}
