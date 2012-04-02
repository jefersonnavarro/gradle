/*
 * Copyright 2012 the original author or authors.
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

package org.gradle.api.plugins.quality.internal.findbugs;

import edu.umd.cs.findbugs.FindBugs;
import edu.umd.cs.findbugs.FindBugs2;
import edu.umd.cs.findbugs.IFindBugsEngine;
import edu.umd.cs.findbugs.TextUICommandLine;
import org.gradle.api.Action;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.process.internal.WorkerProcessContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class FindBugsDaemonServer implements Action<WorkerProcessContext>, Serializable {
    private static final Logger LOGGER = Logging.getLogger(FindBugsDaemon.class);

    private volatile CountDownLatch stop;
    private FindBugsSpec spec;

    public FindBugsDaemonServer(FindBugsSpec spec) {
        this.spec = spec;
    }

    public void execute(WorkerProcessContext context) {
        final FindBugsResult result = execute();
        final FindBugsDaemonClientProtocol clientProtocol = context.getServerConnection().addOutgoing(FindBugsDaemonClientProtocol.class);
        clientProtocol.executed(result);
    }

    public FindBugsResult execute() {
        LOGGER.info("Executing findbugs daemon.");
        try {
            return runFindbugs(spec);
        } catch (Exception e) {
            LOGGER.warn(e.getMessage());
            return new FindBugsResult(0, 0, 1); //mark result with error count 1
        }
    }

    private FindBugsResult runFindbugs(FindBugsSpec spec) throws IOException, InterruptedException {
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        final PrintStream origOut = System.out;
        final PrintStream origErr = System.err;
        try {

            LOGGER.debug("Running findbugs specification {}", spec);
            final List<String> args = spec.getArguments();
            String[] strArray = new String[args.size()];
            args.toArray(strArray);
            // TODO RG: replace ByteArrayOutputStream by OutputStream that handles logging directly.
            // TODO RG: use seperate streams for out and err.
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            System.setOut(new PrintStream(baos));
            System.setErr(new PrintStream(baos));
            Thread.currentThread().setContextClassLoader(FindBugs2.class.getClassLoader());

            FindBugs2 findBugs2 = new FindBugs2();
            TextUICommandLine commandLine = new TextUICommandLine();

            FindBugs.processCommandLine(commandLine, strArray, findBugs2);
            findBugs2.execute();

            LOGGER.debug(baos.toString());
            LOGGER.info("Successfully executed in findbugs daemon.");

            return createFindbugsResult(findBugs2);
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    FindBugsResult createFindbugsResult(IFindBugsEngine findBugs) {
        int bugCount = findBugs.getBugCount();
        int missingClassCount = findBugs.getMissingClassCount();
        int errorCount = findBugs.getErrorCount();
        return new FindBugsResult(bugCount, missingClassCount, errorCount);
    }
}
