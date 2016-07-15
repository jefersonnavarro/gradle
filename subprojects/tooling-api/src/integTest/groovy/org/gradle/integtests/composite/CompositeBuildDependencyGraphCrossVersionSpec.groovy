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

package org.gradle.integtests.composite

import org.gradle.integtests.fixtures.resolve.ResolveTestFixture
import org.gradle.test.fixtures.maven.MavenFileRepository
import org.gradle.tooling.BuildException

/**
 * Tests for resolving dependency graph with substitution within a composite build.
 */
class CompositeBuildDependencyGraphCrossVersionSpec extends AbstractCompositeBuildIntegrationTest {
    def buildA
    def buildB
    MavenFileRepository mavenRepo
    ResolveTestFixture resolve
    def buildArgs = []

    def setup() {
        mavenRepo = new MavenFileRepository(file("maven-repo"))
        mavenRepo.module("org.test", "buildB", "1.0").publish()

        buildA = multiProjectBuild("buildA", ['a1', 'a2']) {
            buildFile << """
                repositories {
                    maven { url "${mavenRepo.uri}" }
                }
                allprojects {
                    apply plugin: 'java'
                    configurations { compile }
                }
"""
        }
        resolve = new ResolveTestFixture(buildA.buildFile)

        buildB = multiProjectBuild("buildB", ['b1', 'b2']) {
            buildFile << """
                allprojects {
                    apply plugin: 'java'
                    version "2.0"

                    repositories {
                        maven { url "${mavenRepo.uri}" }
                    }
                }
"""
        }
        builds = [buildA, buildB]
    }

    def "reports failure to configure one participant build"() {
        given:
        def buildC = singleProjectBuild("buildC") {
            buildFile << """
                throw new RuntimeException('exception thrown on configure')
"""
        }
        builds << buildC

        when:
        checkDependencies()

        then:
        def t = thrown(BuildException)
        assertFailure(t,
            "A problem occurred evaluating root project 'buildC'.",
            "exception thrown on configure")
    }

    def "reports failure for duplicate project path"() {
        given:
        def buildC = singleProjectBuild("buildC")
        buildC.settingsFile.text = "rootProject.name = 'buildB'"
        builds << buildC

        when:
        checkDependencies()

        then:
        def t = thrown(BuildException)
        assertFailure(t,
            "Project path 'buildB::' is not unique in composite.")
    }

    def "does no substitution when no project matches external dependencies"() {
        given:
        mavenRepo.module("org.different", "buildB", "1.0").publish()
        mavenRepo.module("org.test", "buildC", "1.0").publish()

        buildA.buildFile << """
            dependencies {
                compile "org.different:buildB:1.0"
                compile "org.test:buildC:1.0"
            }
"""

        when:
        checkDependencies()

        then:
        checkGraph {
            module("org.different:buildB:1.0")
            module("org.test:buildC:1.0")
        }
    }

    def "substitutes external dependency with root project dependency"() {
        given:
        buildA.buildFile << """
            dependencies {
                compile "org.test:buildB:1.0"
            }
"""

        when:
        checkDependencies()

        then:
        checkGraph {
            edge("org.test:buildB:1.0", "project buildB::", "org.test:buildB:2.0") {
                compositeSubstitute()
            }
        }
    }

    def "substitutes external dependencies with subproject dependencies"() {
        given:
        buildA.buildFile << """
            dependencies {
                compile "org.test:b1:1.0"
                compile "org.test:b2:1.0"
            }
"""

        when:
        checkDependencies()

        then:
        checkGraph {
            edge("org.test:b1:1.0", "project buildB::b1", "org.test:b1:2.0") {
                compositeSubstitute()
            }
            edge("org.test:b2:1.0", "project buildB::b2", "org.test:b2:2.0") {
                compositeSubstitute()
            }
        }
    }

    def "substitutes external dependency with project dependency from same build"() {
        given:
        buildA.buildFile << """
            dependencies {
                compile "org.test:a2:1.0"
            }
            project(':a2') {
                apply plugin: 'java' // Ensure that the project produces a jar
            }
"""

        when:
        checkDependencies()

        then:
        checkGraph {
            edge("org.test:a2:1.0", "project buildA::a2", "org.test:a2:1.0") {
                compositeSubstitute()
            }
        }
    }

    def "substitutes external dependency with subproject dependency that has transitive dependencies"() {
        given:
        def transitive1 = mavenRepo.module("org.test", "transitive1").publish()
        mavenRepo.module("org.test", "transitive2").dependsOn(transitive1).publish()
        buildA.buildFile << """
            dependencies {
                compile "org.test:buildB:1.0"
            }
"""
        buildB.buildFile << """
            dependencies {
                compile "org.test:transitive2:1.0"
            }
"""

        when:
        checkDependencies()

        then:
        checkGraph {
            edge("org.test:buildB:1.0", "project buildB::", "org.test:buildB:2.0") {
                compositeSubstitute()
                module("org.test:transitive2:1.0") {
                    module("org.test:transitive1:1.0")
                }
            }
        }
    }

    def "substitutes external dependency with subproject dependency that has transitive project dependencies"() {
        given:
        buildA.buildFile << """
            dependencies {
                compile "org.test:buildB:1.0"
            }
"""
        buildB.settingsFile << """
include ':b1:b11'
"""
        buildB.buildFile << """
            dependencies {
                compile project(':b1')
            }

            project(":b1") {
                dependencies {
                    compile project("b11") // Relative project path
                }
            }
"""

        when:
        checkDependencies()

        then:
        checkGraph {
            edge("org.test:buildB:1.0", "project buildB::", "org.test:buildB:2.0") {
                compositeSubstitute()
                project("buildB::b1", "org.test:b1:2.0") {
                    project("buildB::b1:b11", "org.test:b11:2.0") {}
                }
            }
        }
    }

    def "honours excludes defined in substituted subproject dependency that has transitive dependencies"() {
        given:
        def transitive1 = mavenRepo.module("org.test", "transitive1").publish()
        mavenRepo.module("org.test", "transitive2").dependsOn(transitive1).publish()
        buildA.buildFile << """
            dependencies {
                compile("org.test:buildB:1.0")
            }
"""
        buildB.buildFile << """
            dependencies {
                compile("org.test:transitive2:1.0")  {
                    exclude module: 'transitive1'
                }
            }
"""

        when:
        checkDependencies()

        then:
        checkGraph {
            edge("org.test:buildB:1.0", "project buildB::", "org.test:buildB:2.0") {
                compositeSubstitute()
                module("org.test:transitive2:1.0")
            }
        }
    }

    def "substitutes transitive dependency of substituted project dependency"() {
        given:
        buildA.buildFile << """
            dependencies {
                compile "org.test:buildB:1.0"
            }
"""
        buildB.buildFile << """
            dependencies {
                compile "org.test:buildC:1.0"
            }
"""
        def buildC = singleProjectBuild("buildC") {
            buildFile << """
                apply plugin: 'java'
"""
        }
        builds << buildC

        when:
        checkDependencies()

        then:
        checkGraph {
            edge("org.test:buildB:1.0", "project buildB::", "org.test:buildB:2.0") {
                compositeSubstitute()
                edge("org.test:buildC:1.0", "project buildC::", "org.test:buildC:1.0") {
                    compositeSubstitute()
                }
            }
        }
    }

    def "substitutes transitive dependency of non-substituted external dependency"() {
        given:
        mavenRepo.module("org.external", "external-dep", '1.0').dependsOn("org.test", "buildB", "1.0").publish()

        buildA.buildFile << """
            dependencies {
                compile "org.external:external-dep:1.0"
            }
"""

        when:
        checkDependencies()

        then:
        checkGraph {
            module("org.external:external-dep:1.0") {
                edge("org.test:buildB:1.0", "project buildB::", "org.test:buildB:2.0") {
                    compositeSubstitute()
                }
            }
        }
    }

    def "substitutes forced direct dependency"() {
        given:
        buildA.buildFile << """
            dependencies {
                compile("org.test:buildB:1.0") { force = true }
            }
"""

        when:
        checkDependencies()

        then:
        checkGraph {
            edge("org.test:buildB:1.0", "project buildB::", "org.test:buildB:2.0") {
                compositeSubstitute()
            }
        }
    }

    def "substitutes transitive dependency with forced version"() {
        given:
        mavenRepo.module("org.external", "external-dep", '1.0').dependsOn("org.test", "buildB", "1.0").publish()

        buildA.buildFile << """
            dependencies {
                compile "org.external:external-dep:1.0"
            }
            configurations.compile.resolutionStrategy.force("org.test:buildB:5.0")
"""

        when:
        checkDependencies()

        then:
        checkGraph {
            module("org.external:external-dep:1.0") {
                edge("org.test:buildB:1.0", "project buildB::", "org.test:buildB:2.0") {
                    compositeSubstitute()
                }
            }
        }
    }

    def "substitutes transitive dependency based on result of resolution rules"() {
        given:
        mavenRepo.module("org.external", "external-dep", '1.0')
            .dependsOn("org.test", "something", "1.0")
            .dependsOn("org.other", "something-else", "1.0")
            .publish()

        buildA.buildFile << """
            dependencies {
                compile "org.external:external-dep:1.0"
            }
            configurations.compile.resolutionStrategy {
                eachDependency { DependencyResolveDetails details ->
                    if (details.requested.name == 'something') {
                        details.useTarget "org.test:buildB:1.0"
                    }
                }
                dependencySubstitution {
                    substitute module("org.other:something-else:1.0") with module("org.test:b1:1.0")
                }
            }
"""

        when:
        checkDependencies()

        then:
        checkGraph {
            module("org.external:external-dep:1.0") {
                edge("org.test:something:1.0", "project buildB::", "org.test:buildB:2.0") {
                    compositeSubstitute()
                }
                edge("org.other:something-else:1.0", "project buildB::b1", "org.test:b1:2.0") {
                    compositeSubstitute()
                }
            }
        }
    }

    def "evaluates subprojects when substituting external dependencies with subproject dependencies"() {
        given:
        buildA.buildFile << """
            dependencies {
                compile "group.requires.subproject.evaluation:b1:1.0"
            }
"""

        buildB.file("b1", "build.gradle") << """
afterEvaluate {
    group = 'group.requires.subproject.evaluation'
}
"""

        when:
        withArgs(args)
        checkDependencies()

        then:
        checkGraph {
            edge("group.requires.subproject.evaluation:b1:1.0", "project buildB::b1", "group.requires.subproject.evaluation:b1:2.0") {
                compositeSubstitute()
            }
        }

        where:
        name                  | args
        "regular"             | []
        "configure on demand" | ["--configure-on-demand"]
        "parallel"            | ["--parallel"]
    }

    def "can resolve with dependency cycle between substituted projects in a multiproject build"() {
        given:
        buildA.buildFile << """
            dependencies {
                compile "org.test:a1:1.0"
            }
            project(':a1') {
                apply plugin: 'java'
                dependencies {
                    compile "org.test:a2:1.0"
                }
            }
            project(':a2') {
                apply plugin: 'java'
                dependencies {
                    compile "org.test:a1:1.0"
                }
            }
"""

        when:
        resolve.withoutBuildingArtifacts()
        checkDependencies()

        then:
        checkGraph {
            edge("org.test:a1:1.0", "project buildA::a1", "org.test:a1:1.0") {
                compositeSubstitute()
                edge("org.test:a2:1.0", "project buildA::a2", "org.test:a2:1.0") {
                    compositeSubstitute()
                    edge("org.test:a1:1.0", "project buildA::a1", "org.test:a1:1.0") {}
                }
            }
        }
    }

    def "can resolve with dependency cycle between substituted participants in a composite build"() {
        given:
        buildA.buildFile << """
            dependencies {
                compile "org.test:buildB:1.0"
            }
"""
        buildB.buildFile << """
            dependencies {
                compile "org.test:buildA:1.0"
            }
"""

        when:
        resolve.withoutBuildingArtifacts()
        checkDependencies()

        then:
        checkGraph {
            edge("org.test:buildB:1.0", "project buildB::", "org.test:buildB:2.0") {
                compositeSubstitute()
                edge("org.test:buildA:1.0", "project :", "org.test:buildA:1.0") {}
            }
        }
    }

    def "substitutes dependency in composite containing participants with same root directory name"() {
        given:
        buildA.buildFile << """
            dependencies {
                compile "org.test:buildB:1.0"
                compile "org.test:buildC:1.0"
            }
"""

        def buildC = rootDir.file("hierarchy", "buildB");
        buildC.file('settings.gradle') << """
            rootProject.name = 'buildC'
"""
        buildC.file('build.gradle') << """
            apply plugin: 'java'
            group = 'org.test'
            version = '1.0'
"""
        builds << buildC

        when:
        checkDependencies()

        then:
        checkGraph {
            edge("org.test:buildB:1.0", "project buildB::", "org.test:buildB:2.0") {
                compositeSubstitute()
            }
            edge("org.test:buildC:1.0", "project buildC::", "org.test:buildC:1.0") {
                compositeSubstitute()
            }
        }
    }

    def "can substitute dependencies in composite with duplicate publication if not involved in resolution"() {
        given:
        def buildC = multiProjectBuild("buildC", ['a2', 'b2', 'c1']) {
            buildFile << """
                allprojects {
                    apply plugin: 'java'
                }
"""
        }
        builds << buildC

        buildA.buildFile << """
            dependencies {
                compile "org.test:b1:1.0"
                compile "org.test:c1:1.0"
            }
"""

        when:
        checkDependencies()

        then:
        checkGraph {
            edge("org.test:b1:1.0", "project buildB::b1", "org.test:b1:2.0") {
                compositeSubstitute()
            }
            edge("org.test:c1:1.0", "project buildC::c1", "org.test:c1:1.0") {
                compositeSubstitute()
            }
        }
    }

    def "reports failure to resolve dependencies when substitution is ambiguous"() {
        given:
        def buildC = multiProjectBuild("buildC", ['a1', 'b1']) {
            buildFile << """
                allprojects {
                    apply plugin: 'java'
                    version = '3.0'
                }
"""
        }
        builds << buildC

        buildA.buildFile << """
            dependencies {
                compile "org.test:b1:1.0"
            }
"""

        when:
        checkDependencies()

        then:
        def t = thrown(BuildException)
        assertFailure(t, "Module version 'org.test:b1:1.0' is not unique in composite: can be provided by projects [buildB::b1, buildC::b1].")
    }

    def "reports failure to resolve dependencies when substitution is ambiguous within single participant"() {
        given:
        buildB
        def buildC = multiProjectBuild("buildC", ['c1', 'c2']);
        buildC.settingsFile << """
            include ':nested:c1'
"""
        buildC.buildFile << """
            allprojects {
                apply plugin: 'java'
            }
"""
        builds << buildC

        buildA.buildFile << """
            dependencies {
                compile "org.test:c1:1.0"
            }
"""

        when:
        checkDependencies()

        then:
        def t = thrown(BuildException)
        assertFailure(t, "Module version 'org.test:c1:1.0' is not unique in composite: can be provided by projects [buildC::c1, buildC::nested:c1].")
    }

    def "reports failure to resolve dependencies when transitive dependency substitution is ambiguous"() {
        given:
        transitiveDependencyIsAmbiguous("'org.test:b1:2.0'")

        when:
        checkDependencies()

        then:
        def t = thrown(BuildException)
        assertFailure(t, "Module version 'org.test:b1:2.0' is not unique in composite: can be provided by projects [buildB::b1, buildC::b1].")
    }

    def "resolve transitive project dependency that is ambiguous in the composite"() {
        given:
        transitiveDependencyIsAmbiguous("project(':b1')")

        when:
        checkDependencies()

        then:
        checkGraph {
            edge("org.test:buildB:1.0", "project buildB::", "org.test:buildB:2.0") {
                compositeSubstitute()
                project("buildB::b1", "org.test:b1:2.0") {}
            }
        }
    }

    def transitiveDependencyIsAmbiguous(String dependencyNotation) {
        def buildC = multiProjectBuild("buildC", ['b1']) {
            buildFile << """
                allprojects {
                    apply plugin: 'java'
                    version = '3.0'
                }
"""
        }
        builds << buildC

        buildB.buildFile << """
            dependencies {
                compile ${dependencyNotation}
            }
"""

        buildA.buildFile << """
            dependencies {
                compile "org.test:buildB:1.0"
            }
"""
    }

    def "handles unused participant with no defined configurations"() {
        given:
        def buildC = singleProjectBuild("buildC")
        builds << buildC

        buildA.buildFile << """
            dependencies {
                compile "org.test:buildB:1.0"
            }
"""

        when:
        checkDependencies()

        then:
        checkGraph {
            edge("org.test:buildB:1.0", "project buildB::", "org.test:buildB:2.0") {
                compositeSubstitute()
            }
        }
    }

    def "reports failure when substituted project does not have requested configuration"() {
        given:
        def buildC = singleProjectBuild("buildC")
        builds << buildC

        buildA.buildFile << """
            dependencies {
                compile "org.test:buildC:1.0"
            }
"""

        when:
        checkDependencies()

        then:
        def t = thrown(BuildException)
        assertFailure(t, "Module version org.test:buildA:1.0, configuration 'compile' declares a dependency on configuration 'default' which is not declared in the module descriptor for org.test:buildC:1.0")
    }

    private void withArgs(List<String> args) {
        buildArgs = args as List
    }

    private void checkDependencies() {
        resolve.prepare()
        execute(buildA, ":checkDeps")
    }

    void checkGraph(@DelegatesTo(ResolveTestFixture.NodeBuilder) Closure closure) {
        resolve.expectGraph {
            root(":", "org.test:buildA:1.0", closure)
        }
    }
}
