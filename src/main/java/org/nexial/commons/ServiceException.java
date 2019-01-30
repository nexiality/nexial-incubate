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

package org.nexial.commons;

/**
 *

 */
public class ServiceException extends AppException {
    private static final long serialVersionUID = -9047901766636337810L;

    public ServiceException() { }

    public ServiceException(String message) { super(message); }

    public ServiceException(String message, Throwable cause) { super(message, cause); }

    public ServiceException(String errorCode, String errorMsg, Throwable cause) { super(errorCode, errorMsg, cause); }
}
