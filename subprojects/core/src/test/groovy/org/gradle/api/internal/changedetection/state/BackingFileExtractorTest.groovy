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

package org.gradle.api.internal.changedetection.state

import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileVisitDetails
import org.gradle.api.file.FileVisitor
import org.gradle.api.internal.file.*
import org.gradle.api.internal.file.archive.TarFileTree
import org.gradle.api.internal.file.collections.FileTreeAdapter
import org.gradle.api.internal.file.collections.LazilyInitializedFileCollection
import org.gradle.api.internal.file.collections.MinimalFileTree
import org.gradle.api.internal.file.collections.SimpleFileCollection
import org.gradle.api.tasks.util.PatternSet
import org.gradle.test.fixtures.file.TestFile
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.gradle.util.UsesNativeServices
import org.junit.Rule
import spock.lang.Specification
import spock.lang.Subject

import static org.gradle.api.internal.file.TestFiles.directoryFileTreeFactory
import static org.gradle.api.internal.file.TestFiles.fileSystem

@UsesNativeServices
class BackingFileExtractorTest extends Specification {
    @Rule
    public final TestNameTestDirectoryProvider testDir = new TestNameTestDirectoryProvider();

    @Subject
    BackingFileExtractor backingFileExtractor = new BackingFileExtractor()

    def "should extract files and directories for simple cases"() {
        given:
        def files = (1..10).collect { file(it as String).absoluteFile }
        def fileCollection = new SimpleFileCollection(files)

        when:
        List<BackingFileExtractor.FileEntry> extracted = backingFileExtractor.extractFilesOrDirectories(fileCollection)

        then:
        extracted.size() == files.size()
        extracted*.file as Set == files as Set

    }

    def "tar filetree doesn't get extracted"() {
        given:
        def tarFile = testDir.file("test.tar")
        testDir.createDir("tarRoot").with {
            file("a/file1.txt") << "content"
            file("b/file2.txt") << "some content"
            tarTo(tarFile)
        }
        def expandDir = testDir.createDir("tarExpand");
        def tarFileTree = new TarFileTree(tarFile, new MaybeCompressedFileResource(new FileResource(tarFile)), expandDir, fileSystem(), fileSystem(), directoryFileTreeFactory());
        def fileCollection = new FileTreeAdapter(tarFileTree)

        when:
        List<BackingFileExtractor.FileEntry> extracted = backingFileExtractor.extractFilesOrDirectories(fileCollection)

        then:
        extracted.size() == 1
        extracted[0].file.absolutePath == tarFile.absolutePath
        !expandDir.listFiles()
    }

    def "Configuration doesn't get extracted"() {
        given:
        def configurationFileCollection = Mock(ConfigurationFileCollection)
        when:
        List<BackingFileExtractor.FileEntry> extracted = backingFileExtractor.extractFilesOrDirectories(configurationFileCollection)

        then:
        extracted.isEmpty()
        0 * _._
    }

    private interface ConfigurationFileCollection extends FileCollectionInternal, Configuration {

    }

    def "LazilyInitializedFileCollection doesn't get extracted"() {
        given:
        def lazyFileCollection = Mock(LazilyInitializedFileCollection)
        when:
        List<BackingFileExtractor.FileEntry> extracted = backingFileExtractor.extractFilesOrDirectories(lazyFileCollection)

        then:
        extracted.isEmpty()
        0 * _._
    }

    def "Configuration in a nested CompositeFileCollection doesn't get extracted"() {
        given:
        def configurationFileCollection = Mock(ConfigurationFileCollection)
        def compositeFileCollection = new UnionFileCollection([configurationFileCollection])
        when:
        List<BackingFileExtractor.FileEntry> extracted = backingFileExtractor.extractFilesOrDirectories(compositeFileCollection)

        then:
        extracted.isEmpty()
        0 * _._
    }

    def "Configuration in a deeply nested CompositeFileCollection doesn't get extracted"() {
        given:
        def configurationFileCollection = Mock(ConfigurationFileCollection)
        def compositeFileCollection = new UnionFileCollection([new UnionFileCollection([new UnionFileCollection([configurationFileCollection])])])
        when:
        List<BackingFileExtractor.FileEntry> extracted = backingFileExtractor.extractFilesOrDirectories(compositeFileCollection)

        then:
        extracted.isEmpty()
        0 * _._
    }

    def "Configuration in a deeply nested CompositeFileCollection doesn't get extracted while simple file collections do"() {
        given:
        def configurationFileCollection = Mock(ConfigurationFileCollection)
        def files = (1..3).collect { file(it as String).absoluteFile }
        def compositeFileCollection = new UnionFileCollection([new SimpleFileCollection([files[0]]), new UnionFileCollection([new SimpleFileCollection([files[1]]), new UnionFileCollection([configurationFileCollection, new SimpleFileCollection([files[2]])])])])
        when:
        List<BackingFileExtractor.FileEntry> extracted = backingFileExtractor.extractFilesOrDirectories(compositeFileCollection)

        then:
        extracted.size() == files.size()
        extracted*.file as Set == files as Set
    }

    def "should extract directory and patternset"() {
        given:
        def root = testDir.createDir("root")
        def patternSet = new PatternSet()
        def fileCollection = new FileTreeAdapter(directoryFileTreeFactory().create(root, patternSet))

        when:
        List<BackingFileExtractor.FileEntry> extracted = backingFileExtractor.extractFilesOrDirectories(fileCollection)

        then:
        extracted.size() == 1
        extracted[0].file.absolutePath == root.absolutePath
        extracted[0].patterns == patternSet

    }

    def "should support composite file tree"() {
        given:
        def files = (1..2).collect { file(it as String).absoluteFile }
        def patternSet = new PatternSet()
        def fileTrees = files.collect { new FileTreeAdapter(directoryFileTreeFactory().create(it, patternSet)) } as FileTreeInternal[]
        def fileTree = new UnionFileTree(fileTrees)
        def filter = new PatternSet()
        filter.include("**/*.java")
        def fileCollection = fileTree.matching(filter)

        when:
        List<BackingFileExtractor.FileEntry> extracted = backingFileExtractor.extractFilesOrDirectories(fileCollection)

        then:
        extracted.size() == files.size()
        extracted*.file as Set == files as Set
    }

    def "should support generic file trees"() {
        def files = (1..10).collect {
            file(it as String).createDir {
                createFile("subdir1/a.txt")
                createFile("subdir2/b.txt")
            }.absoluteFile
        }
        def fileCollection = new SimpleFileCollection(files).getAsFileTree()

        when:
        List<BackingFileExtractor.FileEntry> extracted = backingFileExtractor.extractFilesOrDirectories(fileCollection)

        then:
        extracted.size() == files.size()
        extracted*.file as Set == files as Set
    }

    def "should support custom file trees"() {
        def root = file("root").createDir()
        (1..10).collect {
            root.file(it as String).create {
                subdir1 {
                    file "a.txt"
                }
                subdir2 {
                    file "b.txt"
                }
            }
        }
        def files = []
        new FileTreeAdapter(directoryFileTreeFactory().create(root)).visit(new FileVisitor() {
            @Override
            void visitDir(FileVisitDetails dirDetails) {
                files << dirDetails.file
            }

            @Override
            void visitFile(FileVisitDetails fileDetails) {
                files << fileDetails.file
            }
        })
        assert files.size() == 50
        def minimalFileTree = Stub(MinimalFileTree)
        minimalFileTree.visitTreeOrBackingFile(_) >> { FileVisitor visitor ->
            for (File file : files) {
                def fileVisitDetails = new DefaultFileVisitDetails(file, fileSystem(), fileSystem())
                if (file.isDirectory()) {
                    visitor.visitDir(fileVisitDetails)
                } else {
                    visitor.visitFile(fileVisitDetails)
                }
            }
        }
        def fileCollection = new FileTreeAdapter(minimalFileTree)

        when:
        List<BackingFileExtractor.FileEntry> extracted = backingFileExtractor.extractFilesOrDirectories(fileCollection)

        then:
        extracted.size() == files.size()
        extracted*.file as Set == files as Set
    }

    TestFile file(Object... path) {
        testDir.file(path)
    }
}
