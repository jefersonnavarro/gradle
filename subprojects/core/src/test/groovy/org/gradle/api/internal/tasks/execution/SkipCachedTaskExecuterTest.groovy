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

package org.gradle.api.internal.tasks.execution

import org.gradle.api.Project
import org.gradle.api.internal.TaskInternal
import org.gradle.api.internal.TaskOutputsInternal
import org.gradle.api.internal.changedetection.TaskArtifactState
import org.gradle.api.internal.changedetection.TaskArtifactStateRepository
import org.gradle.api.internal.tasks.TaskExecuter
import org.gradle.api.internal.tasks.TaskExecutionContext
import org.gradle.api.internal.tasks.TaskStateInternal
import org.gradle.api.internal.tasks.cache.TaskCacheKey
import org.gradle.api.internal.tasks.cache.TaskOutputCache
import org.gradle.api.internal.tasks.cache.TaskOutputPacker
import org.gradle.api.internal.tasks.cache.TaskOutputReader
import org.gradle.api.internal.tasks.cache.TaskOutputWriter
import spock.lang.Specification

public class SkipCachedTaskExecuterTest extends Specification {
    def delegate = Mock(TaskExecuter)
    def task = Mock(TaskInternal)
    def project = Mock(Project)
    def projectDir = Mock(File)
    def outputs = Mock(TaskOutputsInternal)
    def taskState = Mock(TaskStateInternal)
    def taskContext = Mock(TaskExecutionContext)
    def taskArtifactStateRepository = Mock(TaskArtifactStateRepository)
    def taskArtifactState = Mock(TaskArtifactState)
    def taskOutputCache = Mock(TaskOutputCache)
    def taskOutputPacker = Mock(TaskOutputPacker)
    def cacheKey = Mock(TaskCacheKey)

    def executer = new SkipCachedTaskExecuter(taskArtifactStateRepository, taskOutputCache, taskOutputPacker, delegate)

    def "skip task when cached results exist"() {
        def cachedResult = Mock(TaskOutputReader)

        when:
        executer.execute(task, taskState, taskContext)

        then:
        1 * task.getOutputs() >> outputs
        1 * outputs.isCacheEnabled() >> true
        1 * outputs.isCacheAllowed() >> true

        1 * taskArtifactStateRepository.getStateFor(task) >> taskArtifactState
        1 * taskArtifactState.calculateCacheKey() >> cacheKey
        1 * taskOutputCache.get(cacheKey) >> cachedResult
        1 * taskOutputPacker.unpack(outputs, cachedResult)
        1 * taskState.upToDate("CACHED")
        0 * _
    }

    def "executes task when no cached result is available"() {
        def cachedResult = Mock(TaskOutputWriter)

        when:
        executer.execute(task, taskState, taskContext)

        then:
        1 * task.getOutputs() >> outputs
        1 * outputs.isCacheEnabled() >> true
        1 * outputs.isCacheAllowed() >> true

        1 * taskArtifactStateRepository.getStateFor(task) >> taskArtifactState
        1 * taskArtifactState.calculateCacheKey() >> cacheKey
        1 * taskOutputCache.get(cacheKey) >> null

        then:
        1 * delegate.execute(task, taskState, taskContext)
        1 * taskState.getFailure() >> null

        then:
        1 * taskOutputPacker.createWriter(outputs) >> cachedResult
        1 * taskOutputCache.put(cacheKey, cachedResult)
        0 * _
    }

    def "does not cache results when executed task fails"() {
        when:
        executer.execute(task, taskState, taskContext)

        then:
        1 * task.getOutputs() >> outputs
        1 * outputs.isCacheEnabled() >> true
        1 * outputs.isCacheAllowed() >> true

        1 * taskArtifactStateRepository.getStateFor(task) >> taskArtifactState
        1 * taskArtifactState.calculateCacheKey() >> cacheKey
        1 * taskOutputCache.get(cacheKey) >> null

        then:
        1 * delegate.execute(task, taskState, taskContext)
        1 * taskState.getFailure() >> new RuntimeException()
        0 * _
    }

    def "executes task and does not cache results when cacheIf is false"() {
        when:
        executer.execute(task, taskState, taskContext)

        then:
        1 * task.getOutputs() >> outputs
        1 * outputs.isCacheAllowed() >> true
        1 * outputs.isCacheEnabled() >> false

        then:
        1 * delegate.execute(task, taskState, taskContext)
        0 * _
    }

    def "executes task and does not cache results when task is not allowed to use cache"() {
        when:
        executer.execute(task, taskState, taskContext)

        then:
        1 * task.getOutputs() >> outputs
        1 * outputs.isCacheAllowed() >> false

        then:
        1 * delegate.execute(task, taskState, taskContext)
        0 * _
    }
}
