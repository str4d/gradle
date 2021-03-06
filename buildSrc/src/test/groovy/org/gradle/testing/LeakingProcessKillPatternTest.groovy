/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.testing

import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Subject

@Subject(LeakingProcessKillPattern)
class LeakingProcessKillPatternTest extends Specification {
    @Issue("https://github.com/gradle/ci-health/issues/138")
    def "can match Play application process command-line on Windows"() {
        def line = '"C:\\Program Files\\Java\\jdk1.7/bin/java.exe"    -Dhttp.port=0  -classpath "C:\\some\\ci\\workspace\\subprojects\\platform-play\\build\\tmp\\test files\\PlayDistributionAdvancedAppIntegrationTest\\can_run_play_distribution\\d3r0j\\build\\stage\\playBinary\\bin\\..\\lib\\advancedplayapp.jar" play.core.server.NettyServer '
        def projectDir = 'C:\\some\\ci\\workspace'
        def buildDir = 'C:\\some\\ci\\workspace\\build'

        expect:
        (line =~ LeakingProcessKillPattern.generate(projectDir, buildDir)).find()
    }
}
