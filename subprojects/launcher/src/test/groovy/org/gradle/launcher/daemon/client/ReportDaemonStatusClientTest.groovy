/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.launcher.daemon.client

import org.gradle.api.internal.DocumentationRegistry
import org.gradle.internal.id.IdGenerator
import org.gradle.launcher.daemon.protocol.Finished
import org.gradle.launcher.daemon.protocol.ReportStatus
import org.gradle.launcher.daemon.protocol.Status
import org.gradle.launcher.daemon.protocol.Success
import org.gradle.launcher.daemon.registry.DaemonInfo
import org.gradle.launcher.daemon.registry.DaemonRegistry
import spock.lang.Specification

class ReportDaemonStatusClientTest extends Specification {
    DaemonRegistry registry = Mock(DaemonRegistry)
    DaemonConnector connector = Mock(DaemonConnector)
    DaemonClientConnection connection = Mock(DaemonClientConnection)
    DocumentationRegistry documentationRegistry = Mock(DocumentationRegistry)
    IdGenerator<?> idGenerator = {12} as IdGenerator
    def client = new ReportDaemonStatusClient(registry, connector, idGenerator, documentationRegistry)

    def "does nothing given no daemons in registry"() {
        when:
        client.listAll()

        then:
        1 * registry.getAll() >> []
        0 * _
    }

    def "reports unknown status if command failed"() {
        given:
        def daemon1 = Stub(DaemonInfo)

        when:
        client.listAll()

        then:
        1 * registry.getAll() >> { [daemon1] as List<DaemonInfo> }
        1 * connector.maybeConnect(daemon1) >>> connection
        _ * connection.daemon >> daemon1
        1 * connection.dispatch({it instanceof ReportStatus})
        1 * connection.receive() >> null
        1 * connection.dispatch({it instanceof Finished})
        1 * connection.stop()

        and:
        1 * documentationRegistry.getDocumentationFor('gradle_daemon', 'status') >> { "DOCUMENTATION_URL" }
        0 * _
    }

    def "requests status report from all daemons"() {
        given:
        def daemon1 = Stub(DaemonInfo)
        def daemon2 = Stub(DaemonInfo)

        when:
        client.listAll()

        then:
        1 * registry.getAll() >> { [daemon1, daemon2] as List<DaemonInfo> }
        1 * connector.maybeConnect(daemon1) >>> connection
        _ * connection.daemon >> daemon1
        1 * connection.dispatch({it instanceof ReportStatus})
        1 * connection.receive() >> new Success(new Status(12345, "3.0", "BOGUS"))
        1 * connection.dispatch({it instanceof Finished})
        1 * connection.stop()

        and:
        1 * connector.maybeConnect(daemon2) >>> connection
        _ * connection.daemon >> daemon2
        1 * connection.dispatch({it instanceof ReportStatus})
        1 * connection.receive() >> new Success(new Status(12346, "3.0", "BOGUS"))
        1 * connection.dispatch({it instanceof Finished})
        1 * connection.stop()

        and:
        1 * documentationRegistry.getDocumentationFor('gradle_daemon', 'status') >> { "DOCUMENTATION_URL" }
        0 * _
    }

    def "handles failed connection"() {
        given:
        def daemon1 = Stub(DaemonInfo)

        when:
        client.listAll()

        then:
        1 * registry.getAll() >> { [daemon1] as List<DaemonInfo> }
        1 * connector.maybeConnect(daemon1) >> { null }
        0 * _
    }
}
