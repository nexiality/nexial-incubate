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

package org.nexial.core.tools.inspector

import org.apache.commons.lang3.StringUtils
import org.nexial.core.tools.inspector.InspectorConst.LOG_DATE_FORMAT
import java.util.*

private const val LABEL_WIDTH = 40

class InspectorLogger(val verbose: Boolean) {

    fun log(message: String) {
        if (!verbose) return
        if (StringUtils.isBlank(message)) return
        println("${LOG_DATE_FORMAT.format(Date())}\t$message")
    }

    fun title(title: String, message: String) {
        if (!verbose) return
        if (StringUtils.isBlank(message)) return

        val timestamp = LOG_DATE_FORMAT.format(Date())
        println("$timestamp\t${StringUtils.repeat("-", 55)}")
        println("$timestamp\t${StringUtils.rightPad(StringUtils.truncate(title, LABEL_WIDTH), LABEL_WIDTH)} - $message")
        println("$timestamp\t${StringUtils.repeat("-", 55)}")
    }

    fun log(label: String, message: String) {
        if (!verbose) return
        if (StringUtils.isBlank(message)) return
        println("${LOG_DATE_FORMAT.format(Date())}\t" +
                "${StringUtils.rightPad(StringUtils.truncate(label, LABEL_WIDTH), LABEL_WIDTH)} - $message")
    }

    fun error(message: String) = InspectorLogger.error(message)

    companion object {
        fun error(message: String) {
            if (StringUtils.isBlank(message)) return
            System.err.println("${LOG_DATE_FORMAT.format(Date())}\t[ERROR] $message")
        }
    }
}