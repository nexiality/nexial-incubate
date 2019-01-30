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

package org.nexial.core.plugins.sms

import org.nexial.commons.utils.TextUtils
import org.nexial.core.IntegrationConfigException
import org.nexial.core.model.StepResult
import org.nexial.core.plugins.base.BaseCommand
import org.nexial.core.utils.CheckUtils.requiresNotBlank
import org.nexial.core.utils.CheckUtils.requiresNotNull

/** commands around sending sms messages to one or more recipients */
class SmsCommand : BaseCommand() {

    override fun getTarget() = "sms"

    var smsNotReadyMessage: String = ""

    /** send SMS `text` to one or more `phones` numbers.  */
    @Throws(IntegrationConfigException::class)
    fun sendText(phones: String, text: String): StepResult {
        requiresNotBlank(phones, "phones CANNOT be emptied", phones)
        requiresNotBlank(text, "text CANNOT be emptied", text)

        val sms = context.smsHelper
        requiresNotNull(sms, smsNotReadyMessage)

        sms.send(TextUtils.toList(phones, context.textDelim, true), text)
        return StepResult.success()
    }
}
