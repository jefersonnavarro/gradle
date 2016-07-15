/*
 * Copyright 2013 the original author or authors.
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
package org.gradle.api.publish.ivy
import org.gradle.integtests.fixtures.CrossVersionIntegrationSpec
import org.gradle.test.fixtures.file.TestFile
import org.gradle.test.fixtures.ivy.IvyFileRepository
import org.gradle.util.TextUtil

class IvyPublishCrossVersionIntegrationTest extends CrossVersionIntegrationSpec {

    final TestFile repoDir = file("ivy-repo")
    final IvyFileRepository repo = new IvyFileRepository(repoDir)

    void setup() {
        requireOwnGradleUserHomeDir()
    }

    def "ivy java publication generated by ivy-publish plugin can be consumed by previous versions of Gradle"() {
        given:
        projectPublishedUsingMavenPublishPlugin('java')

        expect:
        consumePublicationWithPreviousVersion()

        file('build/resolved').assertHasDescendants("published-1.9.jar", 'test-project-1.2.jar')
    }

    def "ivy war publication generated by ivy-publish plugin can be consumed by previous versions of Gradle"() {
        given:
        projectPublishedUsingMavenPublishPlugin('web')

        expect:
        consumePublicationWithPreviousVersion()

        file('build/resolved').assertHasDescendants("published-1.9.war")
    }

    def projectPublishedUsingMavenPublishPlugin(def componentToPublish) {
        repo.module("org.gradle", "test-project", "1.2").publish()

        settingsFile.text = "rootProject.name = 'published'"

        buildFile.text = """
apply plugin: 'war'
apply plugin: 'ivy-publish'

group = 'org.gradle.crossversion'
version = '1.9'

repositories {
    ivy { url "${repo.uri}" }
}
dependencies {
    compile "org.gradle:test-project:1.2"
}
publishing {
    repositories {
        ivy { url "${repo.uri}" }
    }
    publications {
        ivy(IvyPublication) {
            from components.${componentToPublish}
        }
    }
}
"""

        version current withTasks 'publish' run()
    }

    def consumePublicationWithPreviousVersion() {
        settingsFile.text = "rootProject.name = 'consumer'"

        def repoPath = TextUtil.normaliseFileSeparators(repoDir.absolutePath)

        buildFile.text = """
configurations {
    lib
}
repositories {
    if (${previous.fullySupportsIvyRepository}) {
        ivy { url "${repo.uri}" }
    } else {
        add(Class.forName('org.apache.ivy.plugins.resolver.FileSystemResolver').newInstance()) {
            name = 'repo'
            addIvyPattern("${repoPath}/[organisation]/[module]/[revision]/ivy-[revision].xml")
            addArtifactPattern("${repoPath}/[organisation]/[module]/[revision]/[artifact]-[revision].[ext]")
            descriptor = 'required'
            checkmodified = true
        }
    }
}
dependencies {
    lib 'org.gradle.crossversion:published:1.9'
}
task retrieve(type: Sync) {
    into 'build/resolved'
    from configurations.lib
}
"""

        version previous requireOwnGradleUserHomeDir() expectDeprecationWarning() withTasks 'retrieve' run()
    }
}
