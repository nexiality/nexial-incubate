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
import org.apache.http.entity.StringEntity;
import org.nexial.core.model.ExecutionContext;

import static org.nexial.core.NexialConst.WS_CONTENT_LENGTH;
import static org.nexial.core.NexialConst.WS_CONTENT_TYPE;

public class PostRequest extends Request implements Serializable {
    protected String payload;

    PostRequest(ExecutionContext context) {
        super(context);
        method = "POST";
    }

    public String getPayload() { return payload; }

    public void setPayload(String payload) { this.payload = payload; }

    @Override
    public String toString() {
        return super.toString() + "; " + this.getClass().getSimpleName() + "{payload='" + payload + "'}";
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

        String charset = (String) getHeaders().get(WS_CONTENT_TYPE);
        if (StringUtils.contains(charset, "charset=")) {
            charset = StringUtils.substringAfter(charset, "charset=");
        } else if (StringUtils.contains(charset, "/")) {
            charset = null;
        }

        http.setEntity(StringUtils.isNotBlank(charset) ?
                       new StringEntity(getPayload(), charset) : new StringEntity(getPayload()));

        setRequestHeaders(http);
    }

    @Override
    protected void setRequestHeaders(HttpRequest http) {
        super.setRequestHeaders(http);
        if (StringUtils.isNotBlank(payload)) { addHeaderIfNotSpecified(WS_CONTENT_LENGTH, payload.length() + ""); }
    }
}
