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

package org.nexial.core;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.collections4.MapUtils;

import org.nexial.core.model.ExecutionContext;
import org.nexial.core.plugins.NexialCommand;
import org.nexial.core.plugins.RequireBrowser;
import org.nexial.core.plugins.web.Browser;
import org.nexial.core.tools.CommandDiscovery;
import org.nexial.core.utils.ConsoleUtils;

public class PluginManager {
    protected ExecutionContext context;
    protected Map<String, NexialCommand> plugins;
    protected Map<String, NexialCommand> initialized = new HashMap<>();
    protected Browser browser;
    protected boolean browserInitialized;

    public void init() {
        assert context != null;
        assert MapUtils.isNotEmpty(plugins);

        if (CommandDiscovery.isInDiscoveryMode()) {
            ConsoleUtils.log("WARNING: printing out discovered commands and exit");
            CommandDiscovery discovery = CommandDiscovery.getInstance();
            if (CommandDiscovery.shouldSaveDiscoveredCommands()) {
                discovery.persistDiscoveredCommands();
            } else {
                discovery.printDiscoveredCommands();
            }
            System.exit(0);
        }
    }

    public void setContext(ExecutionContext context) {
        this.context = context;
        // needed when the same context instance is used for another script (in the same test plan)
        if (MapUtils.isNotEmpty(initialized)) { initialized.forEach((name, plugin) -> plugin.init(context)); }
        if (browser != null) { browser.setContext(context); }
    }

    public void setPlugins(Map<String, NexialCommand> plugins) { this.plugins = plugins; }

    public void setBrowser(Browser browser) { this.browser = browser; }

    public NexialCommand getPlugin(String target) {
        boolean isInitialized = false;

        NexialCommand nexialCommand;
        if (initialized.containsKey(target)) {
            nexialCommand = initialized.get(target);
            isInitialized = true;
        } else {
            nexialCommand = plugins.get(target);
        }

        if (nexialCommand instanceof RequireBrowser) {
            initBrowser();
            ((RequireBrowser) nexialCommand).setBrowser(browser);
        }

        // todo: fix this when proxy code is ready for use
        //if (nexialCommand instanceof ProxyAware && context.isProxyRequired()) {
        //	((ProxyAware) nexialCommand).setProxy(proxy);
        //}

        if (!isInitialized) {
            nexialCommand.init(context);
            initialized.put(target, nexialCommand);
        }

        return nexialCommand;
    }

    protected void initBrowser() {
        if (browser == null) {
            browser = new Browser();
            browserInitialized = false;
        }

        browser.setContext(context);
        browser.ensureWebDriverReady();
        browserInitialized = true;
    }
}
