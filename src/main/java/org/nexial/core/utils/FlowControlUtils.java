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

package org.nexial.core.utils;

import java.util.Map;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.nexial.core.model.ExecutionContext;
import org.nexial.core.model.FlowControl;
import org.nexial.core.model.FlowControl.Directive;
import org.nexial.core.model.StepResult;
import org.nexial.core.model.TestStep;
import org.nexial.core.plugins.desktop.DesktopNotification;

import static org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS;
import static org.nexial.core.NexialConst.MSG_ABORT;
import static org.nexial.core.model.FlowControl.Directive.*;
import static org.nexial.core.plugins.desktop.DesktopNotification.NotificationLevel.warn;

public final class FlowControlUtils {

    private FlowControlUtils() { }

    public static void checkPauseBefore(ExecutionContext context, TestStep testStep) {
        if (mustNotPause(context, testStep)) { return; }

        String msgPrefix = "PAUSE BEFORE EXECUTION - row " + (testStep.getRow().get(0).getRowIndex() + 1);

        if (context.isStepByStep()) {
            ConsoleUtils.pause(context, msgPrefix + ", conditions: step-by-step=true");
            return;
        }

        if (!shouldPause(context, testStep, PauseBefore)) { return; }

        if (IS_OS_WINDOWS) {
            DesktopNotification.notifyNoAutoDismiss(warn,
                                                    "TEST EXECUTION PAUSED DUE TO PauseBefore() CONDITION. "
                                                    + "PRESS ENTER ON CONSOLE TO CONTINUE EXECUTION");
        }

        ConsoleUtils.pause(context, msgPrefix + ", conditions: " +
                                    serializeDirectives(testStep.getFlowControls().get(PauseBefore)));
    }

    public static void checkPauseAfter(ExecutionContext context, TestStep testStep) {
        if (mustNotPause(context, testStep)) { return; }

        if (!shouldPause(context, testStep, PauseAfter)) { return; }

        if (IS_OS_WINDOWS) {
            DesktopNotification.notifyNoAutoDismiss(warn,
                                                    "TEST EXECUTION PAUSED DUE TO PauseAfter() CONDITION. "
                                                    + "PRESS ENTER ON CONSOLE TO CONTINUE EXECUTION");
        }

        ConsoleUtils.pause(context, "PAUSE AFTER EXECUTION - row " + (testStep.getRow().get(0).getRowIndex() + 1) +
                                    ", conditions: " + serializeDirectives(testStep.getFlowControls().get(PauseAfter)));
    }

    public static boolean shouldPause(ExecutionContext context, TestStep testStep, Directive directive) {
        if (testStep == null) { return false; }
        Map<Directive, FlowControl> flowControls = testStep.getFlowControls();
        if (MapUtils.isEmpty(flowControls)) { return false; }

        FlowControl flowControl = flowControls.get(directive);
        return isMatched(context, flowControl);
    }

    public static StepResult checkSkipIf(ExecutionContext context, TestStep testStep) {
        return checkFlowControl(context, testStep, SkipIf);
    }

    public static StepResult checkProceedIf(ExecutionContext context, TestStep testStep) {
        return checkFlowControl(context, testStep, ProceedIf);
    }

    public static StepResult checkFailIf(ExecutionContext context, TestStep testStep) {
        return checkFlowControl(context, testStep, FailIf);
    }

    public static StepResult checkEndIf(ExecutionContext context, TestStep testStep) {
        return checkFlowControl(context, testStep, EndIf);
    }

    public static StepResult checkEndLoopIf(ExecutionContext context, TestStep testStep) {
        return checkFlowControl(context, testStep, EndLoopIf);
    }

    protected static StepResult checkFlowControl(ExecutionContext context, TestStep testStep, Directive directive) {
        if (testStep == null || context == null) { return null; }

        Map<Directive, FlowControl> flowControls = testStep.getFlowControls();
        if (MapUtils.isEmpty(flowControls)) { return null; }

        FlowControl flowControl = flowControls.get(directive);
        if (directive == ProceedIf) {
            if (flowControl == null || flowControl.hasNoCondition()) { return null; }
            return isMatched(context, flowControl) ?
                   StepResult.success("current step proceeds on") :
                   StepResult.skipped("current step skipped: NOT TRUE(" + serializeDirectives(flowControl) + ")");
        }

        if (!isMatched(context, flowControl)) { return null; }

        String flowControlText = directive + "(" + serializeDirectives(flowControl) + ")";
        ExecutionLogger logger = context.getLogger();

        switch (directive) {
            case FailIf:
                logger.log(testStep, MSG_ABORT + "due to flow control " + flowControlText);
                context.setFailImmediate(true);
                return StepResult.fail("current step failed: " + flowControlText);

            case EndIf:
                logger.log(testStep, MSG_ABORT + "due to flow control " + flowControlText);
                context.setEndImmediate(true);
                return StepResult.success("test execution ends here: " + flowControlText);

            case SkipIf:
                // logger.log(testStep, "skipped due to flow control " + flowControlText);
                return StepResult.skipped("current step skipped: " + flowControlText);

            case EndLoopIf:
                logger.log(testStep, MSG_ABORT + "loop ends due to flow control " + flowControlText);
                context.setBreakCurrentIteration(true);
                return StepResult.skipped("current iteration ends here: " + flowControlText);

            case ProceedIf:
                logger.log(testStep, "proceed due to flow control " + flowControlText);
                return StepResult.success("current step proceeds on " + flowControlText);

            default:
                throw new RuntimeException("Unknown/unsupported directive " + directive);
        }
    }

    private static String serializeDirectives(FlowControl flowControl) {
        StringBuilder buffer = new StringBuilder();
        flowControl.getConditions().forEach(filter -> buffer.append(filter.toString()).append(" & "));
        return StringUtils.removeEnd(buffer.toString(), " & ");
    }

    /**
     * no pausing when we run via castle/jenkins
     */
    private static boolean mustNotPause(ExecutionContext context, TestStep testStep) {
        return context == null || testStep == null || ExecUtils.isRunningInZeroTouchEnv();
    }

    private static boolean isMatched(ExecutionContext context, FlowControl flowControl) {
        if (context == null || flowControl == null || CollectionUtils.isEmpty(flowControl.getConditions())) {
            return false;
        }

        Directive directive = flowControl.getDirective();
        return flowControl.getConditions().isMatched(context, "evaluating flow control:\t" + directive);
    }

}
