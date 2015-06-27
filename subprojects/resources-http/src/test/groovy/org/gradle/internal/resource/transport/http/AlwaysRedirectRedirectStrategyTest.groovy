/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.resource.transport.http

import org.apache.http.HttpRequest
import org.apache.http.HttpResponse
import org.apache.http.RequestLine
import org.apache.http.message.BasicHeader
import org.apache.http.protocol.HttpContext
import spock.lang.Specification
import spock.lang.Unroll

class AlwaysRedirectRedirectStrategyTest extends Specification {

    static final String[] methods = ['GET', 'POST', 'PUT', 'HEAD', 'DELETE', 'OPTIONS', 'TRACE', 'PATCH']

    def "should consider all requests redirectable"() {
        expect:
        new AlwaysRedirectRedirectStrategy().isRedirectable(method)

        where:
        method << methods
    }


    @Unroll
    def "should get redirect for http method [#httpMethod]"() {
        setup:
        HttpRequest request = Mock()
        HttpResponse response = Mock()
        HttpContext context = Mock()
        response.getFirstHeader("location") >> new BasicHeader('location', 'http://redirectTo')
        request.getRequestLine() >> Mock(RequestLine) {
            getMethod() >> httpMethod
        }

        when:
        def redirect = new AlwaysRedirectRedirectStrategy().getRedirect(request, response, context)

        then:
        redirect.getClass() == Class.forName("org.apache.http.client.methods.Http${httpMethod.toLowerCase().capitalize()}")

        where:
        httpMethod << methods + methods.collect{it.toLowerCase()}
    }
}
