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

package org.nexial.core.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.Assert;
import org.junit.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Interactive;
import org.openqa.selenium.interactions.Sequence;
import org.openqa.selenium.remote.RemoteWebElement;

public class WebDriverUtilsTest {
    private static class TestWebDriver implements WebDriver, Interactive {
        public List<Sequence> actions = new ArrayList<>();

        @Override
        public void get(String url) { }

        @Override
        public String getCurrentUrl() { return null;}

        @Override
        public String getTitle() { return null; }

        @Override
        public List<WebElement> findElements(By by) { return null; }

        @Override
        public WebElement findElement(By by) { return null; }

        @Override
        public String getPageSource() { return null; }

        @Override
        public void close() {}

        @Override
        public void quit() { }

        @Override
        public Set<String> getWindowHandles() { return null; }

        @Override
        public String getWindowHandle() { return null; }

        @Override
        public TargetLocator switchTo() { return null; }

        @Override
        public Navigation navigate() { return null; }

        @Override
        public Options manage() { return null; }

        @Override
        public void perform(Collection<Sequence> actions) { this.actions.addAll(actions); }

        @Override
        public void resetInputState() { }
    }

    @Test
    public void toSendKeyAction_multi_nav() {
        WebElement element = new RemoteWebElement();
        TestWebDriver driver = new TestWebDriver();
        WebDriverUtils.toSendKeyAction(driver, element, "{BACKSPACE}{TAB}").perform();

        List<Sequence> actions = driver.actions;
        Assert.assertNotNull(actions);
        Assert.assertEquals(2, actions.size());

        // Map<String, Object> encodedMap = actions.get(0).encode();
        Map<String, Object> encodedMap = actions.stream()
                                                .filter(action -> action.encode().get("id").equals("default keyboard"))
                                                .findFirst()
                                                .map(Sequence::encode)
                                                .orElse(null);
        Assert.assertNotNull(encodedMap);
        Assert.assertEquals("key", encodedMap.get("type"));
        Assert.assertTrue(encodedMap.containsKey("actions"));

        Object actionsObj = encodedMap.get("actions");
        Assert.assertNotNull(actionsObj);
        Assert.assertTrue(actionsObj instanceof List);

        int keyDownCount = 2;
        int keyUpCount = 2;
        int backspaceCount = 2;
        int tabCount = 2;

        List actionList = (List) actionsObj;
        for (Object actionItem : actionList) {
            Map details = (Map) actionItem;

            Object type = ObjectUtils.defaultIfNull(details.get("type"), "");
            if (StringUtils.equals("keyDown", type.toString())) { keyDownCount--; }
            if (StringUtils.equals("keyUp", type.toString())) { keyUpCount--; }

            Object value = ObjectUtils.defaultIfNull(details.get("value"), "");
            if (StringUtils.equals(Keys.BACK_SPACE, value.toString())) { backspaceCount--; }
            if (StringUtils.equals(Keys.TAB, value.toString())) { tabCount--; }
        }

        Assert.assertEquals(0, keyDownCount);
        Assert.assertEquals(0, keyUpCount);
        Assert.assertEquals(0, backspaceCount);
        Assert.assertEquals(0, tabCount);
    }

    @Test
    public void toSendKeyAction_multi_nav_and_chars() {
        WebElement element = new RemoteWebElement();
        TestWebDriver driver = new TestWebDriver();
        WebDriverUtils.toSendKeyAction(driver, element, "{BACKSPACE}ABC{TAB}").perform();

        List<Sequence> actions = driver.actions;

        Assert.assertNotNull(actions);
        // keyboard, and mouse actions
        Assert.assertEquals(2, actions.size());

        // for keyboard
        Map<String, Object> encodedMap = actions.stream()
                                                .filter(action -> action.encode().get("id").equals("default keyboard"))
                                                .findFirst()
                                                .map(Sequence::encode)
                                                .orElse(null);
        Assert.assertNotNull(encodedMap);
        Assert.assertEquals("key", encodedMap.get("type"));
        Assert.assertTrue(encodedMap.containsKey("actions"));

        Object actionsObj = encodedMap.get("actions");
        Assert.assertNotNull(actionsObj);
        Assert.assertTrue(actionsObj instanceof List);

        List actionList = (List) actionsObj;
        Assert.assertEquals(11, actionList.size());

        int keyDownCount = 5;
        int keyUpCount = 5;
        int backspaceCount = 2;
        int aCount = 2;
        int bCount = 2;
        int cCount = 2;

        for (Object actionItem : actionList) {
            Map details = (Map) actionItem;

            Object type = ObjectUtils.defaultIfNull(details.get("type"), "");
            if (StringUtils.equals("keyDown", type.toString())) { keyDownCount--; }
            if (StringUtils.equals("keyUp", type.toString())) { keyUpCount--; }

            Object value = ObjectUtils.defaultIfNull(details.get("value"), "");
            if (StringUtils.equals(Keys.BACK_SPACE, value.toString())) { backspaceCount--; }
            if (StringUtils.equals("A", value.toString())) { aCount--; }
            if (StringUtils.equals("B", value.toString())) { bCount--; }
            if (StringUtils.equals("C", value.toString())) { cCount--; }
        }

        Assert.assertEquals(0, keyDownCount);
        Assert.assertEquals(0, keyUpCount);
        Assert.assertEquals(0, backspaceCount);
        Assert.assertEquals(0, aCount);
        Assert.assertEquals(0, bCount);
        Assert.assertEquals(0, cCount);
    }

    // todo: need to fix for control, alt, and shift characters
    // @Test
    // public void toSendKeyAction_multi_controls_nav_and_chars() {
    //     WebElement element = new RemoteWebElement();
    //     TestWebDriver driver = new TestWebDriver();
    //     WebDriverUtils.toSendKeyAction(driver, element, "{BACKSPACE}ABC{CONTROL}A{ALT}C{TAB}").perform();
    //
    //     List<Sequence> actions = driver.actions;
    //     Assert.assertNotNull(actions);
    //     // 1 for key, and the other for mouse
    //     Assert.assertEquals(2, actions.size());
    //
    //     // 0 for key
    //     Map<String, Object> encodedMap = actions.get(0).encode();
    //     Assert.assertNotNull(encodedMap);
    //     Assert.assertEquals("key", encodedMap.get("type"));
    //     Assert.assertTrue(encodedMap.containsKey("actions"));
    //
    //     Object actionsObj = encodedMap.get("actions");
    //     Assert.assertNotNull(actionsObj);
    //     Assert.assertTrue(actionsObj instanceof List);
    //
    //     List actionList = (List) actionsObj;
    //     Assert.assertEquals(39, actionList.size());
    //
    //     actionList.forEach(o -> {
    //         System.out.println(o);
    //         System.out.println();
    //     });
    //
    //     Assert.assertEquals("keyDown", ((Map) actionList.get(0)).get("type"));
    //     Assert.assertEquals(BACK_SPACE.toString(), ((Map) actionList.get(0)).get("value"));
    //
    //     Assert.assertEquals("keyUp", ((Map) actionList.get(1)).get("type"));
    //     Assert.assertEquals(BACK_SPACE.toString(), ((Map) actionList.get(1)).get("value"));
    //
    //     // item 2, 3, 4 are pauses
    //
    //     Assert.assertEquals("keyDown", ((Map) actionList.get(5)).get("type"));
    //     Assert.assertEquals("A", ((Map) actionList.get(5)).get("value"));
    //
    //     Assert.assertEquals("keyUp", ((Map) actionList.get(6)).get("type"));
    //     Assert.assertEquals("A", ((Map) actionList.get(6)).get("value"));
    //
    //     Assert.assertEquals("keyDown", ((Map) actionList.get(7)).get("type"));
    //     Assert.assertEquals("B", ((Map) actionList.get(7)).get("value"));
    //
    //     Assert.assertEquals("keyUp", ((Map) actionList.get(8)).get("type"));
    //     Assert.assertEquals("B", ((Map) actionList.get(8)).get("value"));
    //
    //     Assert.assertEquals("keyDown", ((Map) actionList.get(9)).get("type"));
    //     Assert.assertEquals("C", ((Map) actionList.get(9)).get("value"));
    //
    //     Assert.assertEquals("keyUp", ((Map) actionList.get(10)).get("type"));
    //     Assert.assertEquals("C", ((Map) actionList.get(10)).get("value"));
    //
    //     // lastly the tab
    //     Assert.assertEquals("keyDown", ((Map) actionList.get(11)).get("type"));
    //     Assert.assertEquals(TAB.toString(), ((Map) actionList.get(11)).get("value"));
    //
    //     Assert.assertEquals("keyUp", ((Map) actionList.get(12)).get("type"));
    //     Assert.assertEquals(TAB.toString(), ((Map) actionList.get(12)).get("value"));
    // }
}