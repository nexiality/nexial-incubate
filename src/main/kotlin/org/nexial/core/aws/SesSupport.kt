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

package org.nexial.core.aws

import com.amazonaws.handlers.AsyncHandler
import com.amazonaws.regions.Regions.DEFAULT_REGION
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceAsyncClientBuilder
import com.amazonaws.services.simpleemail.model.*
import org.apache.commons.collections4.CollectionUtils
import org.apache.commons.lang3.StringUtils
import org.nexial.core.NexialConst.DEF_FILE_ENCODING
import org.nexial.core.utils.ConsoleUtils
import javax.validation.constraints.NotNull

class SesSupport : AwsSupport() {

    class SesConfig {
        var to: List<String>? = null
        var from: String? = null
        var cc: List<String>? = null
        var bcc: List<String>? = null
        var replyTo: List<String>? = null
        var subject: String? = null
        var html: String? = null
        var plainText: String? = null
        var configurationSetName: String? = null
        var xmailer: String? = null
    }

    fun sendMail(config: SesConfig) {
        // sanity check
        if (StringUtils.isBlank(accessKey)) throw IllegalArgumentException("AWS accessKey is missing")
        if (StringUtils.isBlank(secretKey)) throw IllegalArgumentException("AWS secretKey is missing")
        if (StringUtils.isBlank(config.from)) throw IllegalArgumentException("from address is required")
        if (CollectionUtils.isEmpty(config.to)) throw IllegalArgumentException("to address is required")
        if (StringUtils.isBlank(config.subject)) throw IllegalArgumentException("subject is required")
        if (StringUtils.isBlank(config.html) && StringUtils.isBlank(config.plainText)) {
            throw IllegalArgumentException("Either HTML or plain text email content is required")
        }

        // here we go
        val request = SendEmailRequest(config.from, toDestination(config), toMessage(config, toSubject(config)))
        request.withConfigurationSetName(config.configurationSetName)
        if (CollectionUtils.isNotEmpty(config.replyTo)) request.withReplyToAddresses(config.replyTo)

        sendMail(request)
    }

    private fun sendMail(request: SendEmailRequest) {
        val region = if (this.region == null) DEFAULT_REGION else this.region
        ConsoleUtils.log("invoking AWS SES on ${region.description}")

        val client = AmazonSimpleEmailServiceAsyncClientBuilder.standard()
            .withRegion(region)
            .withCredentials(resolveCredentials(region))
            .build()

        ConsoleUtils.log("scheduling sent-mail via AWS SES...")
        client.sendEmailAsync(request, object : AsyncHandler<SendEmailRequest, SendEmailResult> {
            override fun onError(e: Exception) = ConsoleUtils.error("FAILED to send email via AWS SES: ${e.message}")

            override fun onSuccess(request: SendEmailRequest, sendEmailResult: SendEmailResult) {
                ConsoleUtils.log("Email sent via AWS SES: $sendEmailResult")
            }
        })
    }

    @NotNull
    private fun toMessage(config: SesConfig, subject: Content): Message {
        val body = Body()

        if (StringUtils.isNotBlank(config.html)) {
            body.withHtml(Content().withCharset(DEF_FILE_ENCODING)
                              .withData("${config.html}\n" +
                                        "<br/><br/><br/><div style=\"text-align:right;font-size:9pt;color:#aaa\">" +
                                        "${StringUtils.defaultIfEmpty(config.xmailer, "")}" +
                                        "</div><br/>"))
        }
        if (StringUtils.isNotBlank(config.plainText)) {
            body.withText(Content().withCharset(DEF_FILE_ENCODING)
                              .withData("${config.plainText}\n\n\n\n${StringUtils.defaultIfEmpty(config.xmailer, "")}"))
        }

        return Message(subject, body)
    }

    @NotNull
    private fun toDestination(config: SesConfig): Destination {
        val destination = Destination().withToAddresses(config.to)
        if (CollectionUtils.isNotEmpty(config.cc)) destination.withCcAddresses(config.cc)
        if (CollectionUtils.isNotEmpty(config.bcc)) destination.withBccAddresses(config.bcc)
        return destination
    }

    @NotNull
    private fun toSubject(config: SesConfig) = Content().withCharset(DEF_FILE_ENCODING).withData(config.subject)

    companion object {
        fun newConfig() = SesConfig()
    }
}
