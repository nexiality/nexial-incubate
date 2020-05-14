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

import java.util.Arrays;
import java.util.List;

import org.apache.commons.collections4.CollectionUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.nexial.core.model.ExecutionSummary;

import static org.nexial.core.NexialConst.Data.OPT_RUN_ID;

public class ExecutionInterruptTests extends ExcelBasedTests {

    @Override
    @Before
    public void init() throws Exception {
        super.init();

        // System.setProperty(OUTPUT_TO_CLOUD, "true");
        // System.setProperty(OUTPUT_TO_CLOUD, "false");
        // System.setProperty(OPT_RUN_ID_PREFIX, "unitTest_ExecIntrpt");
        // System.setProperty(OPT_OPEN_RESULT, "off");

        System.out.println();
    }

    static {
        System.clearProperty(OPT_RUN_ID);
    }
}
