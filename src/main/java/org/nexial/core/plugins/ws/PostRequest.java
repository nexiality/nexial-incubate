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

package org.nexial.core.plugins.ws;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpRequest;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.nexial.core.model.ExecutionContext;

import static org.nexial.core.NexialConst.Ws.*;

public class PostRequest extends Request implements Serializable {
    protected String payload;
    protected byte[] payloadBytes;

    PostRequest(ExecutionContext context) {
        super(context);
        method = "POST";
    }

    PostRequest(ExecutionContext context, String url, String payload, byte[] payloadBytes) {
        super(context);
        setUrl(url);
        setPayload(payload);
        setPayloadBytes(payloadBytes);
        method = "POST";
    }

    public String getPayload() { return payload; }

    public void setPayload(String payload) { this.payload = payload; }

    public byte[] getPayloadBytes() { return payloadBytes; }

    public void setPayloadBytes(byte[] payloadBytes) { this.payloadBytes = payloadBytes; }

    @Override
    public String toString() {
        return super.toString() + "; " + this.getClass().getSimpleName() + (
            payload != null ? "{payload='" + payload + "'}" :
            payloadBytes != null ? "{payload as bytes, length=" + payloadBytes.length + "}" :
            "no payload"
        );
    }

    @Override
    protected HttpUriRequest prepRequest(RequestConfig requestConfig) throws UnsupportedEncodingException {
        HttpPost http = new HttpPost(url);
        prepPostRequest(requestConfig, http);
        return http;
    }

    protected void prepPostRequest(RequestConfig requestConfig, HttpEntityEnclosingRequestBase http)
        throws UnsupportedEncodingException {
        http.setConfig(requestConfig);

        String charset = null;
        String contentType = String.valueOf(getHeaders().get(WS_CONTENT_TYPE));
        if (StringUtils.contains(contentType, CONTENT_TYPE_CHARSET)) {
            charset = StringUtils.substringAfter(charset, CONTENT_TYPE_CHARSET);
            if (StringUtils.contains(charset, "/")) { charset = null; }

            contentType = StringUtils.removeEnd(
                StringUtils.trim(StringUtils.substringBefore(contentType, CONTENT_TYPE_CHARSET)), ";");
        }

        if (payloadBytes != null) {
            ContentType ct = contentType == null ? null :
                             charset == null ? ContentType.create(contentType) :
                             ContentType.create(contentType, charset);
            http.setEntity(new ByteArrayEntity(payloadBytes, ct));
        } else {
            http.setEntity(charset == null ? new StringEntity(payload, charset) : new StringEntity(payload));
        }
        setRequestHeaders(http);
    }

    @Override
    protected void setRequestHeaders(HttpRequest http) {
        super.setRequestHeaders(http);
        if (StringUtils.isNotBlank(payload)) { addHeaderIfNotSpecified(WS_CONTENT_LENGTH, payload.length() + ""); }
        if (payloadBytes != null) { addHeaderIfNotSpecified(WS_CONTENT_LENGTH, payloadBytes.length + ""); }
    }
}
