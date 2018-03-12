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

package org.nexial.core.plugins.web;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Stack;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.validation.constraints.NotNull;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.collections4.Predicate;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.http.client.methods.HttpRequestBase;
import org.nexial.commons.utils.CollectionUtil;
import org.nexial.commons.utils.RegexUtils;
import org.nexial.commons.utils.TextUtils;
import org.nexial.core.WebProxy;
import org.nexial.core.browsermob.ProxyHandler;
import org.nexial.core.model.ExecutionContext;
import org.nexial.core.model.StepResult;
import org.nexial.core.model.TestStep;
import org.nexial.core.plugins.CanLogExternally;
import org.nexial.core.plugins.CanTakeScreenshot;
import org.nexial.core.plugins.RequireBrowser;
import org.nexial.core.plugins.base.BaseCommand;
import org.nexial.core.plugins.base.ScreenshotUtils;
import org.nexial.core.plugins.ws.Response;
import org.nexial.core.plugins.ws.WsCommand;
import org.nexial.core.utils.ConsoleUtils;
import org.nexial.core.utils.OutputFileUtils;
import org.nexial.core.utils.WebDriverUtils;
import org.openqa.selenium.*;
import org.openqa.selenium.WebDriver.Timeouts;
import org.openqa.selenium.WebDriver.Window;
import org.openqa.selenium.interactions.Action;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.interactions.internal.Coordinates;
import org.openqa.selenium.interactions.internal.Locatable;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import net.lightbody.bmp.proxy.jetty.http.HttpMessage;
import net.lightbody.bmp.proxy.ProxyServer;
import net.lightbody.bmp.proxy.http.RequestInterceptor;
import net.lightbody.bmp.proxy.jetty.http.HttpRequest;

import static java.io.File.separator;
import static java.lang.Thread.sleep;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.commons.lang3.SystemUtils.IS_OS_MAC;
import static org.nexial.core.NexialConst.*;
import static org.nexial.core.NexialConst.Data.*;
import static org.nexial.core.utils.CheckUtils.*;

public class WebCommand extends BaseCommand implements CanTakeScreenshot, CanLogExternally, RequireBrowser {
    protected Browser browser;
    protected WebDriver driver;
    protected JavascriptExecutor jsExecutor;
    protected TakesScreenshot screenshot;

    // helper
    protected LocatorHelper locatorHelper;
    protected FrameHelper frameHelper;
    protected AlertCommand alert;
    protected CookieCommand cookie;
    protected WsCommand ws;

    protected boolean logToBrowser;
    protected long browserStabilityWaitMs;
    private FluentWait<WebDriver> waiter;

    @Override
    public Browser getBrowser() { return browser; }

    @Override
    public void setBrowser(Browser browser) { this.browser = browser; }

    @Override
    public void init(ExecutionContext context) {
        super.init(context);

        // initWebDriver();

        // todo: revisit to handle proxy
        if (context.getBooleanData(OPT_PROXY_ENABLE, false)) {
            ProxyHandler proxy = new ProxyHandler();
        	proxy.setContext(context);
        	proxy.startProxy();
        	browser.setProxy(proxy);
        }

        if (!context.getBooleanData(OPT_DELAY_BROWSER, false)) { initWebDriver(); }

        ws = (WsCommand) context.findPlugin("ws");
        ws.init(context);

        locatorHelper = new LocatorHelper(this);

        browserStabilityWaitMs = context.getIntData(OPT_UI_RENDER_WAIT_MS, DEF_UI_RENDER_WAIT_MS);
        log("setting browser stability wait time to " + browserStabilityWaitMs + " ms");

        // todo: consider this http://seleniumhq.github.io/selenium/docs/api/javascript/module/selenium-webdriver/lib/logging.html
        logToBrowser = !browser.isRunChrome() && context.getBooleanData(OPT_BROWSER_CONSOLE_LOG, false);
        //log("logToBrowser=" + logToBrowser);

        if (driver != null) {
            waiter = new FluentWait<>(driver).withTimeout(context.getPollWaitMs(), MILLISECONDS)
                                             .pollingEvery(10, MILLISECONDS)
                                             .ignoring(NoSuchElementException.class);
        }
    }

    @Override
    public void destroy() {
        super.destroy();
        if (browser != null && browser.getDriver() != null) {
            try { browser.getDriver().quit(); } catch (Exception e) { }
        }
    }

    @Override
    public String getTarget() { return "web"; }

    public StepResult assertTextOrder(String locator, String descending) {
        return locatorHelper.assertTextOrder(locator, descending);
    }

    public StepResult assertFocus(String locator) {
        String expected = getAttributeValue(locator, "id");
        if (StringUtils.isBlank(expected)) {
            return StepResult.fail("element found via " + locator + " does not contain ID attribute");
        }

        try {
            String actual = findActiveElementId();
            if (StringUtils.isBlank(actual)) {
                // since our asserted element has id, this must not be our element..
                return StepResult.fail("active element does not contain ID attribute");
            }

            if (!StringUtils.equals(expected, actual)) {
                return StepResult.fail("EXPECTED element '" + expected + "' not focused; " +
                                       "focused element ID is '" + actual + "'");
            }
        } catch (NoSuchElementException e) {
            return StepResult.fail(e.getMessage());
        }

        return StepResult.success("validated EXPECTED focus on locator '" + locator + "'");
    }

    public StepResult assertNotFocus(String locator) {
        String expected = getAttributeValue(locator, "id");
        if (StringUtils.isBlank(expected)) {
            return StepResult.fail("EXPECTS element does not contain ID attribute");
        }

        try {
            String actual = findActiveElementId();
            if (StringUtils.isBlank(actual) || !StringUtils.equals(expected, actual)) {
                return StepResult.success("validated no-focus on locator '" + locator + "' as EXPECTED");
            }
        } catch (NoSuchElementException e) {
            return StepResult.fail(e.getMessage());
        }

        return StepResult.fail("element '" + expected + "' EXPECTS not to be focused but it is.");
    }

    public StepResult assertLinkByLabel(String label) {
        return assertElementPresent("//a[text()=" + locatorHelper.normalizeXpathText(label) + "]");
    }

    public StepResult assertChecked(String locator) { return new StepResult(isChecked(locator)); }

    public StepResult assertNotChecked(String locator) { return new StepResult(!isChecked(locator)); }

    /** treated all matching elements as checkbox, radio or select-option and toggle their current 'selected' status */
    public StepResult toggleSelections(String locator) {
        List<WebElement> elements = findElements(locator);
        if (CollectionUtils.isEmpty(elements)) {
            return StepResult.fail("No elements matching locator '" + locator + "'");
        }

        for (WebElement element : elements) {
            if (element == null) { continue; }
            element.click();
        }

        return StepResult.success("Successfully toggled " + elements.size() +
                                  " elements matching locator '" + locator + "'");
    }

    public StepResult select(String locator, String text) {
        Select select = getSelectElement(locator);
        if (StringUtils.isBlank(text)) {
            select.deselectAll();
        } else {
            select.selectByVisibleText(text);
        }

        return StepResult.success("selected '" + text + "' from '" + locator + "'");
    }

    public StepResult selectMulti(String locator, String array) {
        requires(StringUtils.isNotBlank(array), "invalid text array", array);

        Select select = getSelectElement(locator);

        List<String> labels = TextUtils.toList(array, context.getTextDelim(), true);
        for (String label : labels) { select.selectByVisibleText(label); }

        return StepResult.success("selected '" + array + "' on widgets matching '" + locator + "'");
    }

    public StepResult selectMultiOptions(String locator) {
        List<WebElement> elements = findElements(locator);
        if (CollectionUtils.isEmpty(elements)) {
            return StepResult.success("none selected since no matches found via '" + locator + "'");
        }

        for (WebElement elem : elements) { elem.click(); }

        return StepResult.success("selected " + elements.size() + " widgets via '" + locator + "'");
    }

    public StepResult deselectMulti(String locator, String array) {
        requires(StringUtils.isNotBlank(array), "invalid text array", array);

        Select select = new Select(toElement(locator));

        List<String> labels = TextUtils.toList(array, context.getTextDelim(), true);
        for (String label : labels) { select.deselectByVisibleText(label); }

        return StepResult.success("deselected '" + array + "' on widgets matching '" + locator + "'");
    }

    /**
     * assert that an element can be found by {@code locator} and it contains {@code text}.  The textual content is
     * the visible (i.e. not hidden by CSS) innerText of this element, including sub-elements, without any leading
     * or trailing whitespace.
     * <p/>
     * <b>Note: it is possible to match more than 1 element by using {@code locator} and {@code text} alone</b>
     */
    public StepResult assertElementByText(String locator, String text) {
        requires(StringUtils.isNotBlank(locator), "invalid locator", locator);
        requires(StringUtils.isNotBlank(text), "invalid text to search by", text);

        List<WebElement> matches = findElements(locator);
        if (CollectionUtils.isEmpty(matches)) { return StepResult.fail("No element found via xpath " + locator); }

        String expected = StringUtils.trim(text);
        for (WebElement elem : matches) {
            String actual = StringUtils.trim(elem.getText());
            if (StringUtils.equals(expected, actual)) {
                return StepResult.success("validated locator '" + locator + "' by text '" + text + "' as EXPECTED");
            }
        }

        return StepResult.fail("No element with text '" + text + "' can be found.");
    }

    public StepResult assertElementNotPresent(String locator) {
        try {
            return locatorHelper.assertElementNotPresent(locator);
        } catch (NoSuchElementException | TimeoutException e) {
            // that's fine.  we expected that..
            return StepResult.success("Element as represented by " + locator + " is not available");
        }
    }

    public StepResult assertElementCount(String locator, String count) {
        return locatorHelper.assertElementCount(locator, count);
    }

    public StepResult assertElementPresent(String locator) {
        if (isElementPresent(locator)) {
            return StepResult.success("EXPECTED element '" + locator + "' found");
        } else {
            ConsoleUtils.log("Expected element not found at " + locator);
            return StepResult.fail("Expected element not found at " + locator);
        }
    }

    public StepResult saveTextSubstringAfter(String var, String locator, String delim) {
        return saveTextSubstring(var, locator, delim, null);
    }

    public StepResult saveTextSubstringBefore(String var, String locator, String delim) {
        return saveTextSubstring(var, locator, null, delim);
    }

    public StepResult saveTextSubstringBetween(String var, String locator, String start, String end) {
        return saveTextSubstring(var, locator, start, end);
    }

    /**
     * validate the presence of an element (possibly elements) based on a set of attributes.  The attributes are
     * expressed by a list of name-value pairs that represent the xpath filtering criteria, with the following rules:
     * <ol>
     * <li>Order is important! xpath is constructed in the order specified via the {@code nameValues} list</li>
     * <li>The pairs in the {@code nameValues} list are separated by pipe character ({@code | }) or newline</li>
     * <li>The name/value within each pair is separated by equals character ({@code = })</li>
     * <li>Use * in value to signify inexact search.  For example, {@code @class=button*} => filter by class
     * attribute where the class name starts with '{@code button}'</li>
     * </ol>
     * <p/>
     * For example (Java code),
     * <pre>
     * String nameValues = "@class=dijit*|text()=Save|@id=*save*";
     * StepResult result = assertElementPresentByAttribs(nameValues);
     * </pre>
     * Same example (spreadsheet),<br/>
     * <pre>
     * assertElementByAttributes(nameValues) |  @class=jumbo*|text()=Save|@id=*save*
     *      - OR -
     * assertElementByAttributes(nameValues) |  @class=jumbo*
     *                                          text()=Save
     *                                          &#0064;id=*save*
     * </pre>
     * <p/>
     * The above code will construct an XPATH as follows:
     * <pre>
     * //*[ends-with(@class,'dijit') and text()='Save' and contains(@id,'save')]
     * </pre>
     * <p/>
     * This XPATH is then used to check if the current page contains an element that would satisfy the
     * specified filtering.
     * <br/>
     * <p/>
     * <b>NOTE: It is possible for the resulting XPATH to return more than 1 element!</b>
     *
     * @see LocatorHelper#resolveFilteringXPath(String)
     * @see #assertElementPresent(String)
     */
    public StepResult assertElementByAttributes(String nameValues) {
        requires(StringUtils.isNotBlank(nameValues), "empty name/value pair", nameValues);
        requires(StringUtils.contains(nameValues, "="), "invalid name/value pair", nameValues);
        return assertElementPresent(locatorHelper.resolveFilteringXPath(nameValues));
    }

    public StepResult waitForElementPresent(final String locator) {
        return new StepResult(waitForCondition(context.getPollWaitMs(), object -> isElementPresent(locator)));
    }

    public StepResult saveValue(String var, String locator) {
        requires(StringUtils.isNotBlank(var) && !StringUtils.startsWith(var, "${"), "invalid variable", var);
        context.setData(var, getValue(locator));
        return StepResult.success("stored value of '" + locator + "' as ${" + var + "}");
    }

    public StepResult saveAttribute(String var, String locator, String attrName) {
        requiresNotBlank(locator, "invalid locator", locator);
        requiresNotBlank(attrName, "invalid attribute name", attrName);
        requiresValidVariableName(var);

        WebElement element = findElement(locator);
        if (element == null) { return StepResult.fail("Element NOT found via '" + locator + "'"); }

        String actual = element.getAttribute(attrName);
        if (actual == null) { ConsoleUtils.log("saving null to variable '" + var + "'"); }

        context.setData(var, actual);
        return StepResult.success("attribute '" + attrName + "' for '" + locator + "' saved to '" + var + "'");
    }

    public StepResult saveCount(String var, String locator) {
        requires(StringUtils.isNotBlank(var) && !StringUtils.startsWith(var, "${"), "invalid variable", var);
        context.setData(var, getElementCount(locator));
        return StepResult.success("stored matche count of '" + locator + "' as ${" + var + "}");
    }

    public StepResult saveTextArray(String var, String locator) {
        requires(StringUtils.isNotBlank(var) && !StringUtils.startsWith(var, "${"), "invalid variable", var);
        List<WebElement> matches = findElements(locator);
        if (CollectionUtils.isNotEmpty(matches)) {
            List<String> matchedText = matches.stream().map(WebElement::getText).collect(Collectors.toList());
            context.setData(var, matchedText.toArray(new String[matchedText.size()]));
        }
        return StepResult.success("stored content of '" + locator + "' as ${" + var + "}");
    }

    public StepResult saveText(String var, String locator) {
        requires(StringUtils.isNotBlank(var) && !StringUtils.startsWith(var, "${"), "invalid variable", var);
        context.setData(var, getElementText(locator));
        return StepResult.success("stored content of '" + locator + "' as ${" + var + "}");
    }

    public StepResult assertOneMatch(String locator) {
        try {
            WebElement matched = findExactlyOneElement(locator);
            if (matched != null) {
                return StepResult.success("Found 1 element via locator '" + locator + "'");
            } else {
                return StepResult.fail("Unable to find matching element via locator '" + locator + "'");
            }
        } catch (IllegalArgumentException e) {
            return StepResult.fail(e.getMessage());
        }
    }

    public StepResult saveElement(String var, String locator) {
        requires(StringUtils.isNotBlank(var) && !StringUtils.startsWith(var, "${"), "invalid variable", var);
        context.setData(var, findElement(locator));
        return StepResult.success("element '" + locator + "' found and stored as ${" + var + "}");
    }

    public StepResult saveElements(String var, String locator) {
        requires(StringUtils.isNotBlank(var) && !StringUtils.startsWith(var, "${"), "invalid property", var);
        context.setData(var, findElements(locator));
        return StepResult.success("elements '" + locator + "' found and stored as ${" + var + "}");
    }

    public StepResult assertIECompatMode() {
        //ie only functionality; not for chrome,ff,safari
        if (!browser.isRunIE()) { return StepResult.success("not applicable to non-IE browser"); }
        if (isIENativeMode()) {
            return StepResult.fail("EXPECTS IE Compatibility Mode, but browser runs at native mode found");
        } else {
            return StepResult.success("browser runs in IE Compatibility Mode");
        }
    }

    public StepResult assertIENavtiveMode() {
        //ie only functionality; not for chrome,ff,safari
        if (!browser.isRunIE()) { return StepResult.success("not applicable to non-IE browser"); }
        if (isIENativeMode()) {
            return StepResult.success("browser runs in native Mode");
        } else {
            return StepResult.fail("EXPECTS native mode, but browser runs at Compatibility Mode found");
        }
    }

    public StepResult assertContainCount(String locator, String text, String count) {
        return locatorHelper.assertContainCount(locator, text, count);
    }

    public StepResult assertTextCount(String locator, String text, String count) {
        return locatorHelper.assertTextCount(locator, text, count);
    }

    public StepResult assertTextList(String locator, String list, String ignoreOrder) {
        return locatorHelper.assertTextList(locator, list, ignoreOrder);
    }

    public StepResult assertTextContains(String locator, String text) {
        requires(StringUtils.isNotBlank(text), "empty text is not allowed", text);
        String elementText = getElementText(locator);
        if (lenientContains(elementText, text, false)) {
            return StepResult.success("validated text '" + elementText + "' contains '" + text + "'");
        } else {
            return StepResult.fail("Expects \"" + text + "\" be contained in \"" + elementText + "\"");
        }
    }

    public StepResult assertNotText(String locator, String text) {
        assertNotEquals(text, getElementText(locator));
        return StepResult.success("validated text '" + text + "' not found in '" + locator + "'");
    }

    public StepResult waitForTextPresent(final String text) {
        requires(StringUtils.isNotBlank(text), "invalid text", text);
        return new StepResult(waitForCondition(context.getPollWaitMs(), object -> isTextPresent(text)));
    }

    public StepResult assertText(String locator, String text) {
        assertEquals(text, getElementText(locator));
        return StepResult.success();
    }

    public StepResult assertTextPresent(String text) {
        requires(StringUtils.isNotBlank(text), "invalid text", text);
        String msgPrefix = "EXPECTED text '" + text + "' ";

        // selenium.isTextPresent() isn't cutting it when text contains spaces or line breaks.
        //if (selenium.isTextPresent(text)) { }

        // text might contain single quote; hence use resolveContainLabelXpath()
        StepResult result = assertElementPresent(locatorHelper.resolveContainLabelXpath(text));
        if (result.failed()) {
            return StepResult.fail(msgPrefix + "not found");
        } else {
            return StepResult.success(msgPrefix + "found");
        }
    }

    public StepResult assertTextNotPresent(String text) {
        requiresNotBlank(text, "invalid text", text);
        String msgPrefix = "unexpected text '" + text + "' ";

        // text might contain single quote; hence use resolveContainLabelXpath()

        // StepResult result = assertElementNotPresent(locatorHelper.resolveContainLabelXpath(text));
        // if (result.failed()) {
        if (isTextPresent(text)) {
            return StepResult.fail(msgPrefix + "FOUND");
        } else {
            return StepResult.success(msgPrefix + "not found");
        }
    }

    public StepResult assertTable(String locator, String row, String column, String text) {
        requires(NumberUtils.isDigits(row) && NumberUtils.toInt(row) > 0, "invalid row number", row);
        requires(NumberUtils.isDigits(column) && NumberUtils.toInt(column) > 0, "invalid column number", column);

        WebElement table = toElement(locator);

        // todo need to test
        WebElement cell = table.findElement(By.xpath("./tr[" + row + "]/td[" + column + "]"));
        if (cell == null) {
            return StepResult.fail("EXPECTED cell at Row " + row + " Column " + column +
                                   " of table '" + locator + "' does not exist.");
        }

        String actual = cell.getText();
        if (StringUtils.isBlank(text)) {
            if (StringUtils.isBlank(actual)) {
                return StepResult.success("found empty value in table '" + locator + "'");
            } else {
                return StepResult.fail("EXPECTED empty value but found '" + actual + "' instead.");
            }
        }

        if (StringUtils.isBlank(actual)) {
            return StepResult.fail("EXPECTED '" + text + "' but found empty value instead.");
        }

        String msgPrefix = "EXPECTED '" + text + "' in table '" + locator + "'";
        if (StringUtils.equals(text, actual)) {
            return StepResult.success(msgPrefix);
        } else {
            return StepResult.fail(msgPrefix + " but found '" + actual + "' instead.");
        }
    }

    public StepResult assertValue(String locator, String value) {
        assertEquals(value, getValue(locator));
        return StepResult.success();
    }

    public StepResult assertValueOrder(String locator, String descending) {
        return locatorHelper.assertValueOrder(locator, descending);
    }

    public StepResult assertAttribute(String locator, String attrName, String value) {
        try {
            String actual = getAttributeValue(locator, attrName);
            if (actual == null) {
                boolean expectsNull = context.isNullValue(value);
                return new StepResult(expectsNull,
                                        "Attribute '" + attrName + "' of element '" + locator + "' is null/missing " +
                                        (expectsNull ? "as EXPECTED" : " but EXPECTS " + value), null);
            }

            return assertEqual(value, actual);
        } catch (NoSuchElementException e) {
            return StepResult.fail(e.getMessage());
        }
    }

    public StepResult assertAttributePresent(String locator, String attrName) {
        return assertAttributePresentInternal(locator, attrName, true);
    }

    public StepResult assertAttributeNotPresent(String locator, String attrName) {
        return assertAttributePresentInternal(locator, attrName, false);
    }

    public StepResult assertAttributeContains(String locator, String attrName, String contains) {
        return assertAttributeContainsInternal(locator, attrName, contains, true);
    }

    public StepResult assertAttributeNotContains(String locator, String attrName, String contains) {
        return assertAttributeContainsInternal(locator, attrName, contains, false);
    }

    public StepResult assertCssNotPresent(String locator, String property) {
        requiresNotBlank(property, "invalid css property", property);

        String actual = getCssValue(locator, property);
        return StringUtils.isEmpty(actual) ?
               StepResult.success("No CSS property '" + property + "' found, as EXPECTED") :
               StepResult.fail("CSS property '" + property + "' found with UNEXPECTED value '" + actual + "'");
    }

    public StepResult assertCssPresent(String locator, String property, String value) {
        requiresNotBlank(property, "invalid css property", property);

        String actual = getCssValue(locator, property);
        if (context.isVerbose()) {
            context.getLogger().log(context.getCurrentTestStep(),
                                    "CSS property '" + property + "' for locator '" + locator + "' is " + actual);
        }

        if (StringUtils.isEmpty(actual) && StringUtils.isEmpty(value)) {
            return StepResult.success("no value found for CSS property '" + property + "' as EXPECTED");
        }

        value = StringUtils.lowerCase(StringUtils.trim(value));

        return StringUtils.equals(actual, value) ?
               StepResult.success("CSS property '" + property + "' contains EXPECTED value") :
               StepResult.fail("CSS property '" + property + "' DOES NOT contain expected value: " + value);
    }

    public StepResult assertVisible(String locator) {
        String msgPrefix = "EXPECTED visible element '" + locator + "'";
        return isVisible(locator) ?
               StepResult.success(msgPrefix + " found") :
               StepResult.fail(msgPrefix + " not found");
    }

    public StepResult assertNotVisible(String locator) {
        return !isVisible(locator) ?
               StepResult.success("element '" + locator + " NOT visible as EXPECTED.") :
               StepResult.fail("EXPECTS element as NOT visible but it is.");
    }

    public StepResult assertAndClick(String locator, String label) {
        requires(StringUtils.isNotBlank(label), "invalid label");

        StepResult result = assertElementByText(locator, label);
        if (result.failed()) {
            log("text/label for '" + label + "' not found at " + locator);
            return result;
        }

        // new locator should either add "text() = ..." or concatenate the "text() = ..." clause to existing filter
        String locatorWithText;
        if (StringUtils.endsWith(locator, "]")) {
            locatorWithText = StringUtils.substringBeforeLast(locator, "]") + " and ";
        } else {
            locatorWithText = locator + "[";
        }
        locatorWithText += "text() = " + locatorHelper.normalizeXpathText(label) + "]";

        result = click(locatorWithText);
        if (result.failed()) { log("text/label for '" + label + "' not clickable at " + locator); }
        return result;
    }

    public StepResult click(String locator) { return clickInternal(locator); }

    public StepResult clickAndWait(String locator, String waitMs) {
        boolean isNumber = NumberUtils.isDigits(waitMs);
        long waitMs1 = isNumber ? (long) NumberUtils.toDouble(waitMs) : pollWaitMs;

        StepResult result = clickInternal(locator);
        if (result.failed()) { return result; }

        waitForBrowserStability(waitMs1);
        if (!isNumber) {
            return StepResult.warn("invalid waitMs: " + waitMs + ", default to " + waitMs1);
        } else {
            return StepResult.success("clicked-and-waited '" + locator + "'");
        }
    }

    public StepResult clickByLabel(String label) { return clickByLabelAndWait(label, pollWaitMs + ""); }

    public StepResult clickByLabelAndWait(String label, String waitMs) {
        String xpath = locatorHelper.resolveLabelXpath(label);
        StepResult result = assertOneMatch(xpath);
        if (result.failed()) { return result; }

        return clickAndWait(xpath, waitMs);
    }

    public StepResult doubleClickByLabel(String label) {
        return doubleClickByLabelAndWait(label, context.getPollWaitMs() + "");
    }

    public StepResult doubleClickByLabelAndWait(String label, String waitMs) {
        if (browser.isRunSafari()) { return StepResult.fail("double-click not supported by Safari"); }

        String xpath = locatorHelper.resolveLabelXpath(label);
        StepResult result = assertOneMatch(xpath);
        if (result.failed()) { return result; }

        return doubleClickAndWait(xpath, waitMs);
    }

    public StepResult doubleClick(String locator) {
        StepResult result = doubleClickAndWait(locator, context.getPollWaitMs() + "");
        if (result.failed()) { return result; }
        return StepResult.success("double-clicked '" + locator + "'");
    }

    public StepResult dismissInvalidCert() {
        wait("2000");
        if (!browser.isRunIE() && !browser.isRunFireFox()) {
            return StepResult.success("dismissInvalidCert(): Not applicable to " + browser);
        }

        ensureReady();
        String title = driver.getTitle();
        if (!StringUtils.contains(title, "Certificate Error: Navigation Blocked") &&
            !StringUtils.contains(title, "Untrusted Connection")) {
            return StepResult.success("dismissInvalidCert(): Invalid certificat message not found " +
                                      browser);
        }

        try {
            driver.get("javascript:document.getElementById('overridelink').click();");
        } catch (Exception e) {
            return StepResult.fail(e.getMessage());
        }
        return StepResult.success("dismissInvalidCert(): Invalid certificate message in " + browser);

    }

    public StepResult dismissInvalidCertPopup() {
        wait("2000");

        ensureReady();
        String parentTitle = driver.getTitle();
        String initialWinHandle = browser.getInitialWinHandle();

        try {
            //get all window handles available
            for (String popUpHandle : driver.getWindowHandles()) {
                wait("750");
                if (!popUpHandle.equals(initialWinHandle)) {
                    //switch driver to popup handle
                    driver.switchTo().window(popUpHandle);
                }
            }

            //take care of invalid cert error message; call method
            dismissInvalidCert();
            //switch back to parent window
            driver.switchTo().window(initialWinHandle);
            waitForBrowserStability(1000);
            waitForTitle(parentTitle);

        } catch (Exception e) {
            return StepResult.fail(e.getMessage());
        }

        return StepResult.success("Dismiss invalid certification popup message in " + browser);
    }

    public StepResult goBack() {
        ensureReady();
        driver.navigate().back();
        return StepResult.success("went back previous page");
    }

    public StepResult goBackAndWait() {
        ensureReady();
        driver.navigate().back();
        waitForBrowserStability(context.getPollWaitMs());
        return StepResult.success("went back previous page");
    }

    public StepResult selectText(String locator) {
        WebElement elem = toElement(locator);
        if (StringUtils.isBlank(elem.getText())) { return StepResult.fail("Element found without text to select."); }

        String id = elem.getAttribute("id");
        if (StringUtils.isBlank(id)) { return StepResult.fail("Element found without 'id';REQUIRED"); }

        jsExecutor.executeScript("window.getSelection().selectAllChildren(document.getElementById('" + id + "'));");
        return StepResult.success("selected text at '" + locator + "'");
    }

    public StepResult unselectAllText() {
        ensureReady();
        jsExecutor.executeScript("window.getSelection().removeAllRanges();");
        return StepResult.success("unselected all text");
    }

    public StepResult maximizeWindow() {
        ensureReady();

        String winHandle = browser.getCurrentWinHandle();
        Window window;
        if (StringUtils.isNotBlank(winHandle)) {
            window = driver.switchTo().window(winHandle).manage().window();
        } else {
            log("Unable to recognize current window, this command will likely fail..");
            window = driver.manage().window();
        }

        if (window == null) { return StepResult.fail("No current window found"); }

        try {
            window.maximize();
            return StepResult.success("browser window maximized");
        } catch (WebDriverException e) {
            // fail safe..
            // Toolkit toolkit = Toolkit.getDefaultToolkit();
            // int screenWidth = (int) toolkit.getScreenSize().getWidth();
            // int screenHeight = (int) toolkit.getScreenSize().getHeight();
            // driver.manage().window().setSize(new Dimension(screenWidth, screenHeight));
            return StepResult.fail("Unable to maximize window: " + e.getMessage() +
                                   ".  Consider running browser in non-incognito mode");
        }
    }

    public StepResult resizeWindow(String width, String height) {
        requires(NumberUtils.isDigits(width), "invalid value for width", width);
        requires(NumberUtils.isDigits(height), "invalid value for height", height);

        ensureReady();
        int numWidth = NumberUtils.toInt(width);
        int numHeight = NumberUtils.toInt(height);
        Window window = driver.manage().window();
        // dimension unit is point (or px)
        window.setSize(new Dimension(numWidth, numHeight));

        return StepResult.success("browser window resized to " + width + "x" + height);
    }

    public StepResult assertScrollbarVPresent(String locator) { return checkScrollbarV(locator, false); }

    public StepResult assertScrollbarVNotPresent(String locator) { return checkScrollbarV(locator, true); }

    public StepResult assertScrollbarHPresent(String locator) { return checkScrollbarH(locator, false); }

    public StepResult assertScrollbarHNotPresent(String locator) { return checkScrollbarH(locator, true); }

    public StepResult open(String url) { return openAndWait(url, "100"); }

    public StepResult openAndWait(String url, String waitMs) {
        requires(StringUtils.isNotBlank(url), "invalid URL", url);

        ensureReady();

        url = validateUrl(url);
        registerStartURL(url);

        driver.get(url);
        waitForBrowserStability(toPositiveLong(waitMs, "waitMs"));

        // bring browser to foreground
        String initialHandle = browser.updateWinHandle();
        ConsoleUtils.log("current browser window handle:" + initialHandle);
        if (StringUtils.isNotBlank(initialHandle)) {
            driver = driver.switchTo().window(initialHandle).switchTo().defaultContent();
        }

        return StepResult.success("opened URL " + url);
    }

    public StepResult refresh() {
        ensureReady();
        driver.navigate().refresh();
        return StepResult.success("active window refreshed");
    }

    public StepResult refreshAndWait() {
        ensureReady();
        driver.navigate().refresh();
        waitForBrowserStability(context.getPollWaitMs());
        return StepResult.success("active window refreshed");
    }

    public StepResult assertTitle(String text) {
        ensureReady();
        assertEquals(text, driver.getTitle());
        return StepResult.success();
    }

    public StepResult waitForTitle(final String text) {
        requires(StringUtils.isNotBlank(text), "invalid title text", text);

        ensureReady();
        return new StepResult(
            waitForCondition(context.getPollWaitMs(),
                             object -> StringUtils.equals(driver.getTitle(), text))
        );
    }

    public StepResult selectWindow(String winId) { return selectWindowAndWait(winId, "2000"); }

    public StepResult selectWindowByIndex(String index) {
        ensureReady();
        return selectWindowAndWait((String) driver.getWindowHandles().toArray()[Integer.parseInt(index)], "2000");
    }

    public StepResult selectWindowByIndexAndWait(String index, String waitMs) {
        requiresPositiveNumber(index, "window index must be a positive integer (zero-based");
        requiresPositiveNumber(waitMs, "waitMs must be a positve integer", waitMs);

        ensureReady();

        int windowIndex = Integer.parseInt(index);

        Set<String> windowHandles = driver.getWindowHandles();
        if (CollectionUtils.size(windowHandles) <= windowIndex) {
            return StepResult.fail("Number of available window is less than " + (windowIndex + 1));
        }

        return selectWindowAndWait(IterableUtils.get(windowHandles, windowIndex), waitMs);
    }

    public StepResult selectWindowAndWait(String winId, String waitMs) {
        requiresNotNull(winId, "Invalid window handle/id", winId);
        requiresPositiveNumber(waitMs, "waitMs must be a positve integer", waitMs);

        // wait time removed since in a multi-window scenario, the last (main) window might no yet selected.
        //waitForBrowserStability(context.getPollWaitMs());

        winId = StringUtils.defaultString(winId, "");
        if (StringUtils.equals(winId, "null")) { winId = ""; }

        if (StringUtils.isEmpty(winId)) {
            String initialWinHandle = browser.getInitialWinHandle();
            return trySelectWindow(StringUtils.isNotBlank(initialWinHandle) ? initialWinHandle : "#DEF#", waitMs);
        } else {
            return trySelectWindow(winId, waitMs);
        }
    }

    public StepResult waitForPopUp(String winId, String waitMs) {
        requires(StringUtils.isNotBlank(winId), "invalid window ID ", winId);
        long waitMsLong = toPositiveLong(waitMs, "wait millisecond");

        ensureReady();

        if (browser.isRunIE()) {
            return StepResult.warnUnsupportedFeature("waitForPopUp", driver);
            // todo IE fix? need more testing
        }

        // todo need to test
        WebDriverWait wait = new WebDriverWait(driver, waitMsLong);
        wait.until((Function<WebDriver, Object>) webDriver -> {
            driver = driver.switchTo().window(winId);
            return true;
        });

        return StepResult.success("waited for popup window '" + winId + "'");
    }

    public StepResult saveAllWindowNames(String var) {
        String names = TextUtils.toString(getAllWindowNames().toArray(new String[]{}), ",", "", "");
        context.setData(var, names);
        return StepResult.success("stored existing window names '" + names + "' as ${" + var + "}");
    }

    public StepResult saveAllWindowIds(String var) {
        String names = TextUtils.toString(getAllWindowIds().toArray(new String[]{}), ",", "", "");
        context.setData(var, names);
        return StepResult.success("stored existing window ID '" + names + "' as ${" + var + "}");
    }

    public StepResult assertFramePresent(String frameName) { return frameHelper.assertFramePresent(frameName); }

    public StepResult assertFrameCount(String count) { return frameHelper.assertFrameCount(count); }

    public StepResult selectFrame(String locator) { return frameHelper.selectFrame(locator); }

    public StepResult wait(String waitMs) {
        if (StringUtils.isBlank(waitMs)) { return StepResult.success("waited " + waitMs + "ms"); }

        int waitMsInt = toPositiveInt(waitMs, "waitMs");
        try {
            sleep(waitMsInt);
            return StepResult.success("waited " + waitMs + "ms");
        } catch (InterruptedException e) {
            return StepResult.fail(e.getMessage());
        }
    }

    public StepResult scrollTo(String locator) { return scrollTo(locator, (Locatable) toElement(locator)); }

    public StepResult scrollLeft(String locator, String pixel) {
        requires(NumberUtils.isDigits(pixel), "invalid number", pixel);

        WebElement element = toElement(locator);
        jsExecutor.executeScript("arguments[0].scrollLeft=" + pixel, element);

        return scrollTo(locator, (Locatable) element);
    }

    public StepResult scrollRight(String locator, String pixel) {
        requires(NumberUtils.isDigits(pixel), "invalid number", pixel);

        WebElement element = toElement(locator);
        jsExecutor.executeScript("arguments[0].scrollLeft=" + (NumberUtils.toInt(pixel) * -1), element);

        return scrollTo(locator, (Locatable) element);
    }

    public StepResult type(String locator, String value) {
        // WebElement element = shouldWait() ?
        //                      findElement(locator) :
        //                      !isElementPresent(locator) ? null : toElement(locator);
        WebElement element = findElement(locator);
        if (element == null) {
            String msg = "unable to complete type() since locator (" + locator + ") cannot be found.";
            error(msg);
            return StepResult.fail(msg);
        }

        jsExecutor.executeScript("arguments[0].scrollIntoView(true);", element);
        if (context.isHighlightWebElementEnabled()) { highlight(element); }
        element.clear();

        if (StringUtils.isNotEmpty(value)) {
            if (browser.isRunSafari()) { focus("//body"); }
            element.sendKeys(value);
        }

        return StepResult.success("typed text at '" + locator + "'");
    }

    public StepResult typeKeys(String locator, String value) {
        WebElement element = toElement(locator);
        jsExecutor.executeScript("arguments[0].scrollIntoView(true);", element);
        if (context.isHighlightWebElementEnabled()) { highlight(element); }

        if (StringUtils.isBlank(value)) {
            element.clear();
            return StepResult.success("cleared out value at '" + locator + "'");
        }

        element.click();
        waitFor(MIN_STABILITY_WAIT_MS);

        Action action = WebDriverUtils.toSendKeyAction(driver, element, value);
        if (action != null) { action.perform(); }

        // could have alert text...
        alert.harvestText();

        return StepResult.success("typed text at '" + locator + "'");
    }

    public StepResult upload(String fieldLocator, String file) {
        requires(StringUtils.isNotBlank(fieldLocator), "invalid field locator", fieldLocator);
        requires(StringUtils.isNotBlank(file), "invalid file to upload", file);

        File f = new File(file);
        if (!f.isFile() || !f.canRead()) { return StepResult.fail("specified file '" + file + "' is not readable"); }

        //driver.setFileDetector(new LocalFileDetector());
        WebElement upload = findElement(fieldLocator);
        if (upload == null) { return StepResult.fail("expected locator '" + fieldLocator + "' NOT FOUND"); }

        upload.sendKeys(f.getAbsolutePath());
        return StepResult.success("adding file '" + file + "' to '" + fieldLocator + "'");
    }

    public StepResult verifyContainText(String locator, String text) { return assertTextContains(locator, text); }

    public StepResult verifyText(String locator, String text) { return assertText(locator, text); }

    public StepResult savePageAs(String var, String sessionIdName, String url) {
        requires(StringUtils.isNotBlank(var), "invalid variable", var);

        try {
            context.setData(var, downloadLink(sessionIdName, url));
            return StepResult.success("saved '" + url + "' as ${" + var + "}");
        } catch (Exception e) {
            String message = "Unable to save link '" + url + "' as property '" + var + "': " + e.getMessage();
            return StepResult.fail(message);
        }
    }

    public StepResult savePageAsFile(String sessionIdName, String url, String fileName) {
        requires(StringUtils.isNotBlank(fileName), "invalid filename", fileName);

        File f = new File(fileName);
        requires(!f.isDirectory(), "filename cannot be a directory", fileName);

        try {
            // download
            byte[] payload = downloadLink(sessionIdName, url);

            // just in case
            f.getParentFile().mkdirs();

            FileUtils.writeByteArrayToFile(f, payload);
            return StepResult.success("saved '" + url + "' as '" + fileName + "'");
        } catch (IOException e) {
            return StepResult.fail("Unable to save '" + url + "' as '" + fileName + "': " + e.getMessage());
        }
    }

    public StepResult saveLocation(String var) {
        requires(StringUtils.isNotBlank(var) && !StringUtils.startsWith(var, "${"), "invalid variable", var);

        context.setData(var, driver.getCurrentUrl());
        return StepResult.success("stored current URL as ${" + var + "}");
    }

    public StepResult clearLocalStorage() {
        jsExecutor.executeScript("window.localStorage.clear();");
        return StepResult.success("browser's local storage cleared");
    }

    public StepResult editLocalStorage(String key, String value) {
        requiresNotBlank(key, "local storage key must not be null");

        if (StringUtils.isBlank(value)) {
            jsExecutor.executeScript("window.localStorage.removeItem('" + key + "');");
        } else {
            jsExecutor.executeScript("window.localStorage.setItem('" + key + "','" +
                                     StringUtils.replace(value, "'", "\\'") +
                                     "');");
        }
        return StepResult.success("browser's local storage updated");
    }

    public StepResult saveLocalStorage(String var, String key) {
        requiresValidVariableName(var);
        requiresNotBlank(key, "local storage key must not be null");

        Object response = jsExecutor.executeScript("return window.localStorage.getItem('" + key + "')");
        context.setData(var, response);
        return StepResult.success("browser's local storage (" + key + ") stored to " + var + " as " + response);
    }

    /**
     * designed for JS injection / web security testing
     */
    public StepResult executeScript(String var, String script) {
        requiresValidVariableName(var);
        requiresNotBlank(script, "Invalid script", script);

        Object retVal = jsExecutor.executeScript(script);
        if (retVal != null) { context.setData(var, retVal); }

        return StepResult.success("script executed");
    }

    @Override
    public String takeScreenshot(TestStep testStep) {
        if (testStep == null) { return null; }

        //if (browser == null || selenium == null || browser.isRunBrowserStack()) {
        if (browser == null) {
            // possibly delayBrowser turned on.. skip screenshot if selenium hasn't been initialized..
            ConsoleUtils.log("selenium/browser not yet initialized; skip screen capturing");
            return null;
        }

        // short-circuit for firefox+alert
        if (browser.isRunFireFox() && alert.isAlertPresent()) {
            log("screen capture is not supported by firefox when Javascript alert dialog is present");
            return null;
        }

        // proceed... with caution (or not!)
        waitForBrowserStability(browserStabilityWaitMs);

        String filename = generateScreenshotFilename(testStep);
        if (StringUtils.isBlank(filename)) {
            error("Unable to generate screen capture filename!");
            return null;
        }
        filename = context.getProject().getScreenCaptureDir() + separator + filename;

        File screenshotFile = ScreenshotUtils.saveScreenshot(screenshot, filename);
        if (screenshotFile == null) {
            ConsoleUtils.error("Unable to save screenshot for " + testStep);
            return null;
        }

        if (context.isOutputToCloud()) {
            try {
                return context.getS3Helper().importMedia(screenshotFile);
            } catch (IOException e) {
                log("Unable to save " + screenshotFile + " to cloud storage due to " + e.getMessage());
            }
        }

        return screenshotFile.getAbsolutePath();
    }

    @Override
    public String generateScreenshotFilename(TestStep testStep) {
        return OutputFileUtils.generateScreenCaptureFilename(testStep);
    }

    @Override
    public void logExternally(TestStep testStep, String message) {
        if (testStep == null || StringUtils.isBlank(message)) { return; }
        logToBrowserConsole(testStep.showPosition() + " " + message);
    }

    // todo need to test
    public StepResult doubleClickAndWait(String locator, String waitMs) {
        boolean isNumber = NumberUtils.isDigits(waitMs);
        String waitMsStr = isNumber ? (long) NumberUtils.toDouble(waitMs) + "" : context.getPollWaitMs() + "";

        WebElement element = toElement(locator);
        jsExecutor.executeScript("arguments[0].scrollIntoView(true);", element);
        if (context.isHighlightWebElementEnabled()) { highlight(element); }
        new Actions(driver).doubleClick(element).build().perform();

        // could have alert text...
        alert.harvestText();
        waitForBrowserStability(Long.parseLong(waitMsStr));

        if (!isNumber) {
            return StepResult.warn("invalid waitMs: " + waitMs + ", default to " + waitMsStr);
        } else {
            return StepResult.success("double-clicked-and-waited '" + locator + "'");
        }
    }

    public StepResult close() {
        ensureReady();

        boolean lastWindow = browser.isOnlyOneWindowRemaining();
        String activeWindowHandle = driver.getWindowHandle();

        // warning for non-OSX safari instances or IE
        if ((browser.isRunSafari() && !IS_OS_MAC) || browser.isRunIE()) {
            ConsoleUtils.log("close() might not work on IE or Safari/Win32; likely to close the wrong tab/window");
            jsExecutor.executeScript("window.close()");
        } else {
            driver.switchTo().window(activeWindowHandle).close();
        }

        // give it time to settle down
        wait(context.getIntData(BROWSER_POST_CLOSE_WAIT, DEF_BROWSER_POST_CLOSE_WAIT) + "");

        if (lastWindow) { return closeAll(); }

        browser.removeWinHandle(activeWindowHandle);

        Stack<String> lastWinHandles = browser.getLastWinHandles();
        if (CollectionUtils.isNotEmpty(lastWinHandles)) {
            try {
                //do not pop if there's only 1 win handle left (that's the initial win handle).
                String handle = lastWinHandles.size() == 1 ? lastWinHandles.peek() : lastWinHandles.pop();
                ConsoleUtils.log("focus returns to previous window '" + handle + "'");
                driver = driver.switchTo().window(handle);
            } catch (NotFoundException e) {
                ConsoleUtils.error("Unable to focus on windows due to invalid handler; default to main window");
                selectWindow("null");
            }
        } else {
            ConsoleUtils.log("focus returns to initial browser window");
            selectWindow("null");
        }

        return StepResult.success("closed active window");
    }

    public StepResult closeAll() {
        // ensureReady();
        browser.shutdown();
        driver = null;
        waiter = null;
        return StepResult.success("closed last tab/window");
    }

    // todo need to test
    public StepResult mouseOver(String locator) {
        new Actions(driver).moveToElement(toElement(locator)).build().perform();

        //selenium.fireEvent(locator, "focus");
        // work as of 2.26.0
        //selenium.mouseOver(locator);

        return StepResult.success("mouse-over on '" + locator + "'");
    }

    protected StepResult scrollTo(String locator, Locatable element) {
        try {
            boolean success = scrollTo(element);
            if (success) {
                return StepResult.success("scrolled to '" + locator + "'");
            } else {
                return StepResult.fail("Unable to scroll to '" + locator + "' - failed to obtain coordinates");
            }
        } catch (ElementNotVisibleException e) {
            return StepResult.fail("Unable to scroll to '" + locator + "': " + e.getMessage());
        }
    }

    protected StepResult mouseOut(String locator) {

        ensureReady();
        jsExecutor.executeScript("arguments[0].mouseout();", toElement(locator));

        //selenium.fireEvent(locator, "blur");
        // work as of 2.26.0
        //selenium.mouseOut(locator);
        return StepResult.success("mouse-out on '" + locator + "'");
    }

    protected StepResult checkScrollbarV(String locator, boolean failIfExists) {
        try {
            WebElement element = findElement(locator);

            boolean exists;
            // special treatment for body tag
            if (StringUtils.equalsIgnoreCase(element.getTagName(), "body")) {
                exists = BooleanUtils.toBoolean(Objects.toString(jsExecutor.executeScript(
                    "document.documentElement.clientHeight < document.documentElement.scrollHeight"))
                                               );
            } else {
                exists = NumberUtils.toInt(element.getAttribute("clientHeight")) <
                         NumberUtils.toInt(element.getAttribute("scrollHeight"));
            }

            boolean result = failIfExists != exists;
            String msg = "vertical scrollbar " + (exists ? "exists" : "does not exists") + " at '" + locator + "'";
            return new StepResult(result, msg, null);
        } catch (Throwable e) {
            return StepResult.fail("Error determining vertical scrollbar at '" + locator + "': " + e.getMessage());
        }
    }

    protected StepResult checkScrollbarH(String locator, boolean failIfExists) {
        try {
            WebElement element = findElement(locator);

            boolean exists;
            // special treatment for body tag
            if (StringUtils.equalsIgnoreCase(element.getTagName(), "body")) {
                exists = BooleanUtils.toBoolean(Objects.toString(jsExecutor.executeScript(
                    "return document.documentElement.clientWidth < document.documentElement.scrollWidth")));
            } else {
                exists = NumberUtils.toInt(element.getAttribute("clientWidth")) <
                         NumberUtils.toInt(element.getAttribute("scrollWidth"));
            }

            boolean result = failIfExists != exists;
            String msg = "horizontal scrollbar " + (exists ? "exists" : "does not exists") + " at '" + locator + "'";
            return new StepResult(result, msg, null);
        } catch (Throwable e) {
            return StepResult.fail("Error determining horizontal scrollbar at '" + locator + "': " + e.getMessage());
        }
    }

    protected String getCssValue(String locator, String property) {
        WebElement element = findElement(locator);
        if (element == null) { throw new NoSuchElementException("Element NOT found via '" + locator + "'"); }
        return StringUtils.lowerCase(StringUtils.trim(element.getCssValue(property)));
    }

    /**
     * todo: need to evaluate whether we need this still... for now, move it to protected
     */
    protected StepResult uploadAndSubmit(String fieldLocator, String file, String submitLocator) {
        requires(StringUtils.isNotBlank(submitLocator), "invalid submit locator", file);
        StepResult result = upload(fieldLocator, file);
        if (result.failed()) { return result; }
        return click(submitLocator);
    }

    /**
     * INTERNAL METHOD; not for public consumption
     */
    protected StepResult assertAttributePresentInternal(String locator, String attrName, boolean expectsFound) {
        try {
            String actual = getAttributeValue(locator, attrName);
            boolean success = expectsFound ? StringUtils.isNotEmpty(actual) : StringUtils.isEmpty(actual);
            return new StepResult(success,
                                    "Attribute '" + attrName + "' of element '" + locator + "' is " +
                                    (success ? "found as EXPECTED" : " NOT FOUND"), null);
        } catch (NoSuchElementException e) {
            return StepResult.fail(e.getMessage());
        }
    }

    protected StepResult assertAttributeContainsInternal(String locator,
                                                         String attrName,
                                                         String contains,
                                                         boolean expectsContains) {

        String msg = "Attribute '" + attrName + "' of element '" + locator + "' ";

        boolean success;
        try {
            String actual = getAttributeValue(locator, attrName);
            if (StringUtils.isBlank(contains) && StringUtils.isBlank(actual)) {
                // got a match
                if (expectsContains) {
                    success = true;
                    msg += "CONTAINS blank as EXPECTED";
                } else {
                    success = false;
                    msg += "CONTAINS blank, which is NOT expected";
                }
            } else {
                if (StringUtils.contains(actual, contains)) {
                    if (expectsContains) {
                        success = true;
                        msg += "CONTAINS '" + contains + "' as EXPECTED";
                    } else {
                        success = false;
                        msg += "CONTAINS '" + contains + "', which is NOT as expected";
                    }
                } else {
                    if (expectsContains) {
                        success = false;
                        msg += "DOES NOT contains '" + contains + "', which is NOT as expected";
                    } else {
                        success = true;
                        msg += "DOES NOT contains '" + contains + "' as EXPECTED";
                    }
                }
            }

            return new StepResult(success, msg, null);
        } catch (NoSuchElementException e) {
            return StepResult.fail(e.getMessage());
        }
    }

    protected void registerStartURL(String url) {
        if (StringUtils.isBlank(url) || context.hasData(OPT_START_URL)) { return; }
        context.setData(OPT_START_URL, url);
    }

    protected StepResult trySelectWindow(String winId, String waitMs) {
        long waitMsLong = toPositiveLong(waitMs, "waitMs");
        long endTime = System.currentTimeMillis() + waitMsLong;
        boolean rc = false;

        ensureReady();

        // track current window handle as the last focused window object (used in close() cmd)
        try {
            String windowHandle = driver.getWindowHandle();
            Stack<String> lastWinHandles = browser.getLastWinHandles();
            if (CollectionUtils.isNotEmpty(lastWinHandles)) {
                if (!StringUtils.equals(lastWinHandles.peek(), windowHandle)) { lastWinHandles.push(windowHandle); }
            }
        } catch (NotFoundException e) {
            // failsafe.. in case we can't select window by selenium api
            driver = driver.switchTo().defaultContent();
        }

        String dummyWinId = "#DEF#";

        do {
            try {
                if (StringUtils.equals(winId, dummyWinId)) {
                    driver = driver.switchTo().defaultContent();
                } else {
                    driver = driver.switchTo().window(StringUtils.isNotBlank(winId) ?
                                                      winId :
                                                      browser.getInitialWinHandle());
                }
                rc = true;
                break;
            } catch (Exception e) {
                try { sleep(250); } catch (InterruptedException e1) { }
            }
        } while (System.currentTimeMillis() < endTime);

        String targetWinId = (StringUtils.equals(winId, dummyWinId) ? "default" : winId) + " window";
        if (rc) {
            waitForBrowserStability(context.getPollWaitMs());
            return StepResult.success("selected " + targetWinId);
        } else {
            return StepResult.fail("could not select " + targetWinId);
        }
    }

    // todo: merge with selectMultiOptions(), which is just a form of clicks
    protected StepResult clickInternal(String locator) {

        WebElement element;
        if (shouldWait()) {
            element = findElement(locator);
        } else {
            List<WebElement> matches = findElements(locator);
            element = CollectionUtils.isEmpty(matches) ? null : matches.get(0);
        }

        if (element == null) { return StepResult.fail("No element via locator '" + locator + "'"); }

        ConsoleUtils.log("clicking '" + locator + "'...");
        return clickInternal(element);
    }

    /** internal impl. to handle browser-specific behavior regarding click */
    protected StepResult clickInternal(WebElement element) {
        if (element == null) { return StepResult.fail("Unable to obtain element"); }

        if (jsExecutor != null) {
            jsExecutor.executeScript("arguments[0].scrollIntoView(true);", element);
        } else {
            scrollTo((Locatable) element);
            tryFocus(element);
        }

        if (context.isHighlightWebElementEnabled()) { highlight(element); }

        boolean forceJSClick = jsExecutor != null &&
                               (browser.favorJSClick() || context.getBooleanData(FORCE_JS_CLICK, DEF_FORCE_JS_CLICK));

        try {
            if (forceJSClick && StringUtils.isNotBlank(element.getAttribute("id"))) {
                ConsoleUtils.log("click target via JS, @id=" + element.getAttribute("id"));
                Object retObj = jsExecutor.executeScript("arguments[0].click(); return true;", element);
                ConsoleUtils.log("clicked -> " + retObj);
                return StepResult.success("click via JS event");
            } else {
                element.click();
                return StepResult.success("clicked on web element");
            }
        } catch (StaleElementReferenceException e) {
            return StepResult.fail(e.getMessage(), e);
        } catch (Exception e) {
            // try again..
            if (forceJSClick) {
                ConsoleUtils.log("AGAIN click target via JS");
                Object retObj = jsExecutor.executeScript("arguments[0].click(); return true;", element);
                ConsoleUtils.log("clicked -> " + retObj);
                return StepResult.success("click via JS event");
            }

            return StepResult.fail(e.getMessage(), e);
        } finally {
            // could have alert text...
            alert.harvestText();
        }
    }

    protected void initWebDriver() {
        // todo: revisit to handle proxy
        //if (context.getBooleanData(OPT_PROXY_ENABLE, false)) {
        //	ProxyHandler proxy = new ProxyHandler();
        //	proxy.setContext(this);
        //	proxy.startProxy();
        //	browser.setProxy(proxy);
        //}

        if (driver == null) {
            driver = browser.ensureWebDriverReady();
            waiter = new FluentWait<>(driver).withTimeout(context.getPollWaitMs(), MILLISECONDS)
                                             .pollingEvery(10, MILLISECONDS)
                                             .ignoring(NoSuchElementException.class);
        }
        jsExecutor = (JavascriptExecutor) driver;
        screenshot = (TakesScreenshot) driver;
        frameHelper = new FrameHelper(this, driver);

        if (alert == null) {
            alert = (AlertCommand) context.findPlugin("webalert");
            alert.init(context);
        } else {
            alert.driver = driver;
        }
        alert.setBrowser(browser);

        if (cookie == null) {
            cookie = (CookieCommand) context.findPlugin("webcookie");
            cookie.init(context);
        } else {
            cookie.driver = driver;
        }
        cookie.setBrowser(browser);
    }

    protected void ensureReady() { initWebDriver(); }

    // todo: need to enable proxy capbility across nexial
    protected void initProxy() {
        // todo: need to decide key
        ProxyServer proxyServer = WebProxy.getProxyServer();
        if (context.hasData(BROWSER_LANG) && proxyServer != null) {
            String browserLang = context.getStringData(BROWSER_LANG);
            proxyServer.addHeader("Accept-Language", browserLang);
            proxyServer.addRequestInterceptor((RequestInterceptor) (request, har) -> {
                //Accept-Language: en-US,en;q=0.5
                //request.addRequestHeader("Accept-Language", getProp(OPT_BROWSER_LANG));
                HttpRequestBase method = request.getMethod();
                method.removeHeaders("Accept-Language");
                method.setHeader("Accept-Language", browserLang);

                HttpRequest proxyRequest = request.getProxyRequest();
                int oldState = proxyRequest.setState(HttpMessage.__MSG_EDITABLE);
                proxyRequest.removeField("Accept-Language");
                proxyRequest.setField("Accept-Language", browserLang);
                proxyRequest.setState(oldState);
            });
        }
    }

    @NotNull
    protected Select getSelectElement(String locator) {
        WebElement element = findElement(locator);
        if (element == null) { throw new NoSuchElementException("element '" + locator + "' not found."); }
        return new Select(element);
    }

    protected int getElementCount(String locator) {
        List<WebElement> elements = findElements(locator);
        return CollectionUtils.isEmpty(elements) ? 0 : elements.size();
    }

    protected boolean isElementPresent(String locator) { return findElement(locator) != null; }

    protected List<WebElement> findElements(String locator) {
        ensureReady();

        By by = locatorHelper.findBy(locator);
        boolean wait = shouldWait();
        boolean timeoutChangesEnabled = browser.getBrowserType().isTimeoutChangesEnabled();
        Timeouts timeouts = driver.manage().timeouts();

        try {
            // shorten wait to avoid competing timeout in selenium-browser comm.
            if (!wait && timeoutChangesEnabled) { timeouts.implicitlyWait(ELEM_PRESENT_WAIT_MS, MILLISECONDS); }

            return wait ? waiter.until(driver -> driver.findElements(by)) : driver.findElements(by);
        } catch (NoSuchElementException e) {
            return null;
        } finally {
            // set it back to default
            if (!wait && timeoutChangesEnabled) { timeouts.implicitlyWait(pollWaitMs, MILLISECONDS); }
        }
    }

    protected WebElement toElement(String locator) {
        WebElement element = findElement(locator);
        if (element == null) { throw new NoSuchElementException("element not found via '" + locator + "'."); }
        return element;
    }

    protected boolean isIENativeMode() {
        ensureReady();
        Object ret = jsExecutor.executeScript("javascript:document.documentMode");
        if (ret == null) { throw new NotFoundException("Unable to determine IE native mode"); }

        String ieDocMode = (String) ret;
        String ieDocModeCompare = ieDocMode.substring(0, ieDocMode.indexOf("."));
        double docModeVersion = NumberUtils.toDouble(ieDocModeCompare);
        return browser.getBrowserVersionNum() == docModeVersion;
    }

    protected String getElementText(String locator) { return toElement(locator).getText(); }

    protected boolean isTextPresent(String text) {
        ensureReady();
        return StringUtils.contains(driver.findElement(By.tagName("body")).getText(), text);
    }

    protected String getValue(String locator) {
        WebElement element = toElement(locator);
        if (element == null) {
            String msg = "unable to complete command since locator (" + locator + ") cannot be found.";
            log(msg);
            throw new NoSuchElementException(msg);
        }

        return element.getAttribute("value");
    }

    protected String getAttributeValue(String locator, String attrName) throws NoSuchElementException {
        requiresNotBlank(locator, "invalid locator", locator);
        requiresNotBlank(attrName, "invalid attribute name", attrName);
        WebElement element = findElement(locator);
        if (element == null) { throw new NoSuchElementException("Element NOT found via '" + locator + "'"); }
        return element.getAttribute(attrName);
    }

    protected List<String> getAllWindowNames() {
        if (driver == null) { return new ArrayList<>(); }

        // ensureReady();
        browser.resyncWinHandles();

        List<String> windowNames = new ArrayList<>();
        String current;
        try {
            current = driver.getWindowHandle();
        } catch (WebDriverException e) {
            ConsoleUtils.error("Error when obtaining available window names: " + e.getMessage());
            current = browser.initialWinHandle;
        }

        for (String handle : browser.getLastWinHandles()) {
            try {
                driver = driver.switchTo().window(handle);
                windowNames.add(jsExecutor.executeScript("return window.name").toString());
            } catch (WebDriverException e) {
                ConsoleUtils.error("Error when obtaining window name for " + handle + ": " + e.getMessage());
            }
        }

        driver = driver.switchTo().window(current);

        return windowNames;
    }

    protected List<String> getAllWindowIds() {
        if (driver == null) { return new ArrayList<>(); }
        // ensureReady();
        return CollectionUtil.toList(driver.getWindowHandles());
    }

    protected boolean waitForBrowserStability(long maxWait) {
        // for firefox we can't be calling driver.getPageSource() or driver.findElement() when alert dialog is present
        if (alert.isAlertPresent()) { return true; }

        // force at least 1 compare
        if (maxWait < MIN_STABILITY_WAIT_MS) { maxWait = MIN_STABILITY_WAIT_MS + 1; }

        if (!context.isPageSourceStabilityEnforced()) { return false; }

        // some browser might not support 'view-source'...
        boolean hasSource = browser.isPageSourceSupported();

        int successCount = 0;
        String oldSource = "";
        String newSource = "";
        int speed;
        long endTime = System.currentTimeMillis() + maxWait;

        try {
            speed = context.getIntData(OPT_WAIT_SPEED, BROWSER_STABILITY_COMPARE_TOLERANCE);

            if (hasSource) { oldSource = driver.getPageSource(); }

            do {
                sleep(MIN_STABILITY_WAIT_MS);
                if (hasSource) { newSource = driver.getPageSource(); }

                if (isBrowserLoadComplete() && StringUtils.equals(oldSource, newSource)) {
                    successCount += 1;
                    // compare is successful, but we'll keep trying until COMPARE_TOLERANCE is reached
                    if (successCount >= speed) { return true; }
                } else {
                    successCount = 0;
                    // compare didn't work.. but let's wait until maxWait is reached before declaring failure
                    if (hasSource) { oldSource = newSource; }
                }
            } while (System.currentTimeMillis() < endTime);
        } catch (Exception e) {
            if (e.getMessage().contains("Modal") || e instanceof UnhandledAlertException) {return true;}

            log("Unable to determine browser's stability: " + e.getMessage());
            throw new RuntimeException(e.getMessage(), e);
        }

        return false;
    }

    protected boolean isBrowserLoadComplete() {
        ensureReady();
        boolean timeoutChangesEnabled = browser.getBrowserType().isTimeoutChangesEnabled();
        Timeouts timeouts = driver.manage().timeouts();
        if (timeoutChangesEnabled) { timeouts.implicitlyWait(pollWaitMs, MILLISECONDS); }

        JavascriptExecutor jsExecutor = (JavascriptExecutor) this.driver;

        String readyState;
        try {
            readyState = (String) jsExecutor.executeScript("return document.readyState");
        } catch (Exception e) {
            log("Unable to execute [selenium.getEval(document.readyState)] due to " + e.getMessage());
            log("Trying another approach...");
            try {
                // failsafe retry
                readyState = (String) jsExecutor.executeScript(
                    "return selenium.browserbot.getCurrentWindow().document.readyState");
            } catch (Exception e1) {
                log("Unable to evaluate browser readyState: " + e1.getMessage());
                return false;
            }
        }

        return StringUtils.equals("complete", StringUtils.trim(readyState));
    }

    protected boolean waitForCondition(long maxWaitMs, Predicate condition) {
        // guanrantee at least 1 cycle
        if (maxWaitMs < DEF_SLEEP_MS) { maxWaitMs = DEF_SLEEP_MS + 1; }

        int count = 0;

        int maxWaitCycle = (int) (maxWaitMs / DEF_SLEEP_MS);
        for (int cycle = 0; cycle < maxWaitCycle; cycle++) {
            //if (cycle >= maxWaitCycle) { NexialTestBase.fail("timeout after " + maxWaitMs + "ms"); }
            try {
                if (condition.evaluate(driver)) {
                    count++;
                    cycle--;

                    // double twice before declaring condition met - confirm stability
                    if (count >= 2) { return true; }
                }
            } catch (Exception e) {
                log("Error while waiting for browser screen to stabilize: " + e.getMessage());
            }

            waitFor(DEF_SLEEP_MS);
        }

        return false;
    }

    protected boolean scrollTo(Locatable element) throws ElementNotVisibleException {
        Coordinates coordinates = element.getCoordinates();
        if (coordinates != null) {
            // according to Coordinates' Javadoc: "... This method automatically scrolls the page and/or
            // frames to make element visible in viewport before calculating its coordinates"
            Point topLeft = coordinates.inViewPort();
            log("scrolled to " + topLeft.getX() + "/" + topLeft.getY());
            return true;
        } else {
            return false;
        }
    }

    protected byte[] downloadLink(String sessionName, String url) {
        // sanity check
        if (ws == null) { fail("plugin 'ws' is not available.  Check with Nexial Support Group for details."); }
        if (cookie == null) { fail("plugin 'cookie' is not available.  Check with Nexial Support Group for details."); }
        requires(StringUtils.isNotBlank(url), "valid/full URL or property reference required", url);

        String cookieVar = NAMESPACE + "downloadCookies";
        String wsRespVar = NAMESPACE + "downloadResponse";

        try {
            // in case we need to use specific cookie(s) for the HTTP GET
            if (StringUtils.isBlank(sessionName) || context.isNullValue(sessionName)) {
                if (driver != null) { cookie.saveAll(cookieVar); }
            } else {
                // since session name is specified, we need to get cookie value from running browser
                if (driver == null) {
                    fail("sessionIdName='" + sessionName + "' is NOT COMPATIBLE with " + OPT_DELAY_BROWSER + "=true");
                    return null;
                }

                Cookie sessionCookie = cookie.getCookie(sessionName);
                if (sessionCookie == null || StringUtils.isBlank(sessionCookie.getValue())) {
                    fail("Unable to download - unable to switch to session-bound window");
                    return null;
                }

                cookie.save(cookieVar, sessionName);
            }

            if (context.hasData(cookieVar)) {
                StepResult result = ws.headerByVar("Cookie", cookieVar);
                if (result.failed()) {
                    fail("Failed to download from '" + url + "': " + result.getMessage());
                    return null;
                }
            }

            StepResult result = ws.get(url, null, wsRespVar);
            if (result.failed()) {
                fail("Failed to download from '" + url + "': " + result.getMessage());
                return null;
            }

            Object response = context.getObjectData(wsRespVar);
            if (!(response instanceof Response)) {
                fail("Failed to download from '" + url + "': valid HTTP response; check log for details");
                return null;
            }

            return ((Response) response).getRawBody();
        } finally {
            context.removeData(cookieVar);
            context.removeData(wsRespVar);
        }
    }

    /** IE8-specific fix by adding randomized number sequence to URL to prevent overly-intelligent-caching by IE. */
    protected String fixURL(String url) {
        if (browser.isRunIE() && browser.getMajorVersion() == 8 || browser.isRunChrome()) {
            url = addNoCacheRandom(url);
        }
        return url;
    }

    protected String validateUrl(String url) {
        requires(RegexUtils.isExact(StringUtils.lowerCase(url), REGEX_VALID_WEB_PROTOCOL), "invalid URL", url);
        return fixURL(url);
    }

    /** add logging to browser's console (via console.log call) */
    protected void logToBrowserConsole(String message) {
        if (!logToBrowser) { return; }
        if (driver == null) { return; }
        if (browser == null) { return; }
        if (!browser.getBrowserType().isConsoleLoggingEnabled()) { return; }

        jsExecutor.executeScript("console.log(\"[nexial]>> " + StringUtils.replace(message, "\"", "\\\"") + "\");");
    }

    // todo need to test
    protected WebElement findExactlyOneElement(String xpath) throws IllegalArgumentException {
        requiresNotBlank(xpath, "xpath is empty");

        ensureReady();

        List<WebElement> elements = findElements(xpath);
        if (CollectionUtils.isEmpty(elements)) {
            throw new IllegalArgumentException("No element found via locator '" + xpath + "'");
        }

        // exactly one
        if (elements.size() == 1) { return elements.get(0); }

        // more than one? could be browser DOM cache; check it out
        WebElement matched = null;
        for (WebElement element : elements) {
            Point location = element.getLocation();

            // impossible position... likely not the one we want
            if (location.getX() == 0 && location.getY() == 0) { continue; }

            // text not reflect our filter... likely not the one we want
            String elementText = element.getText();
            if (StringUtils.isBlank(elementText)) { continue; }
            if (!StringUtils.contains(xpath, elementText)) { continue; }

            if (matched == null) {
                // found one, and this is the only one so far..
                matched = element;
            } else {
                // found another one, hence failure is in order
                throw new IllegalArgumentException("More than 1 element found locator '" + xpath + "'");
            }
        }

        if (matched == null) { throw new IllegalArgumentException("No element matched via locator '" + xpath + "'"); }

        return matched;
    }

    protected WebElement findElement(String locator) {
        ensureReady();
        By by = locatorHelper.findBy(locator);
        return shouldWait() ? waiter.until(driver -> driver.findElement(by)) : driver.findElement(by);
    }

    protected String addNoCacheRandom(String url) {
        int indexAfterHttp = StringUtils.indexOf(url, "//") + 2;
        if (StringUtils.indexOf(url, "/", indexAfterHttp) == -1) { url += "/"; }

        // add random 'prevent-cache' tail request param
        url += (StringUtils.contains(url, "&") ? "&" : "?") + "random=" + RandomStringUtils.random(16, false, true);
        return url;
    }

    protected void highlight(String locator) { highlight(toElement(context.replaceTokens(locator))); }

    protected void highlight(WebElement we) {
        // if (browser.isRunPhantomJS()) { return; }

        String initialStyle = null;
        Object retObj = jsExecutor.executeScript("return arguments[0].getAttribute('style');", we);
        if (retObj != null && !retObj.equals("") && !retObj.equals(STYLE_HIGHLIGHT)) {
            initialStyle = retObj.toString();
            ConsoleUtils.log("found current style as '" + initialStyle + "'");
        }

        int waitMs = context.getIntData(HIGHLIGHT_WAIT_MS, DEF_HIGHLIGHT_WAIT_MS);
        jsExecutor.executeScript(
            "var elem = arguments[0];" +
            "var tmpStyle = arguments[1];" +
            "var initialStyle = arguments[2];" +
            "elem.setAttribute('style', tmpStyle); " +
            "setTimeout(function () { elem.setAttribute('style', initialStyle); }, " + waitMs + ");",
            we, STYLE_HIGHLIGHT, StringUtils.defaultString(initialStyle, ""));
    }

    // todo incomplete... need more testing
    protected void highlight(TestStep teststep) {
        String cmd = teststep.getCommand();
        if (StringUtils.contains(cmd, "()") || !StringUtils.contains(cmd, "(")) { return; }

        String parameters = StringUtils.substringBefore(StringUtils.substringAfter(cmd, "("), ")");
        List<String> paramList = TextUtils.toList(parameters, ",", true);

        for (int i = 0; i < paramList.size(); i++) {
            String param = paramList.get(i);
            if (StringUtils.equals(param, "locator")) {
                try {
                    highlight(teststep.getParams().get(i));
                } catch (Throwable e) {
                    // don't complain... this is debugging freebie..
                }
                break;
            }
        }
    }

    // todo need to test
    protected String findActiveElementId() throws NoSuchElementException {
        ensureReady();
        Object ret = jsExecutor.executeScript("return document.activeElement == null ? '' : document.activeElement.id");
        if (ret == null) { throw new NoSuchElementException("No active element or element has no ID"); }
        return StringUtils.trim((String) ret);
    }

    // todo need to test
    protected boolean isChecked(String locator) { return toElement(locator).isSelected(); }

    // todo need to test
    protected boolean isVisible(String locator) {
        WebElement element = findElement(locator);
        if (element == null) {
            log("element '" + locator + "' not found, hence considered as 'NOT VISIBLE'");
            return false;
        }
        return element.isDisplayed();
    }

    // todo need to test
    protected void focus(String locator) { focus(toElement(locator)); }

    protected void focus(WebElement element) {
        new Actions(driver).moveToElement(element).build().perform();
        if (StringUtils.equals(element.getTagName(), "input")) { element.sendKeys(""); }
    }

    protected void tryFocus(WebElement element) {
        try {
            focus(element);
            wait("250");
        } catch (Exception e) {
            log("unable to execute focus on target element: " + e.getMessage());
        }
    }

    private boolean shouldWait() { return context.getBooleanData(WEB_ALWAYS_WAIT, DEF_WEB_ALWAYS_WAIT); }

    private StepResult saveTextSubstring(String var, String locator, String delimStart, String delimEnd) {
        List<WebElement> matches = findElements(locator);
        if (CollectionUtils.isEmpty(matches)) {
            context.removeData(var);
            return StepResult.success("No matches found; ${" + var + "} removed from context");
        }

        if (matches.size() == 1) { return saveSubstring(matches.get(0).getText(), delimStart, delimEnd, var); }

        List<String> textArray = matches.stream().map(WebElement::getText).collect(Collectors.toList());

        return saveSubstring(textArray, delimStart, delimEnd, var);
    }
}
