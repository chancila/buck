/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Tests for AndroidLibraryDescription with query_deps */
public class AndroidLibraryAsAnnotationProcessorHostIntegrationTest extends AbiCompilationModeTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();
  private ProjectWorkspace workspace;
  private Path output;

  @Before
  public void setUp() throws IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();

    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "android_library_as_ap_host", tmpFolder);
    workspace.setUp();
    workspace.enableDirCache();
    workspace.addBuckConfigLocalOption("build", "depfiles", "cache");
    setWorkspaceCompilationMode(workspace);
  }

  @Test
  public void testAddingResourceFileInvalidatesManifestBasedCacheHit() throws Exception {
    // Build once to warm cache
    output = workspace.buildAndReturnOutput("//:top_level");
    workspace.getBuildLog().assertTargetBuiltLocally("//:top_level");
    assertZipFileContains(output, "res1");
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();

    // Now, add a new config and assert we get a cache miss and the new file is read
    workspace.replaceFileContents("BUCK", "#add_res", "");
    workspace.runBuckCommand("build", "//:top_level").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:lib");
    workspace.getBuildLog().assertTargetBuiltLocally("//:top_level");
    assertZipFileContains(output, "res2");
  }

  @Test
  public void testAddingDepInvalidatesManifestBasedCacheHit() throws Exception {
    // Build once to warm cache
    output = workspace.buildAndReturnOutput("//:top_level");
    workspace.getBuildLog().assertTargetBuiltLocally("//:top_level");
    assertZipFileContains(output, "res1");
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();

    // Now, add a new config via a dep and assert we get a cache miss and the new file is read
    workspace.replaceFileContents("BUCK", "#add_dep", "");
    workspace.buildAndReturnOutput("//:top_level");
    workspace.getBuildLog().assertTargetBuiltLocally("//:lib2");
    workspace.getBuildLog().assertTargetBuiltLocally("//:top_level");
    assertZipFileContains(output, "res2");
  }

  @Test
  public void testAddingResourceFileRebuildsDependents() throws Exception {
    // Build once to warm cache
    output = workspace.buildAndReturnOutput("//:top_level");
    workspace.getBuildLog().assertTargetBuiltLocally("//:top_level");
    assertZipFileContains(output, "res1");

    // Now, edit a config and assert we rebuild
    workspace.replaceFileContents("res/_STRIPPED_RESOURCES/res1.json", "res1", "replaced");
    workspace.runBuckCommand("build", "//:top_level").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:lib");
    workspace.getBuildLog().assertTargetBuiltLocally("//:top_level");
    assertZipFileContains(output, "replaced");

    // Now, add a new config and assert we re-run the processor
    workspace.replaceFileContents("BUCK", "#add_res", "");
    workspace.runBuckCommand("build", "//:top_level").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:lib");
    workspace.getBuildLog().assertTargetBuiltLocally("//:top_level");
    assertZipFileContains(output, "res2");

    // Now, add an unrelated file and assert dep files are working
    workspace.replaceFileContents("BUCK", "#add_file", "");
    workspace.runBuckCommand("build", "//:top_level").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:lib");
    workspace.getBuildLog().assertTargetHadMatchingDepfileRuleKey("//:top_level");
  }

  @Test
  public void testAddingDepRebuildsDependents() throws Exception {
    // Build once to warm cache
    output = workspace.buildAndReturnOutput("//:top_level");
    workspace.getBuildLog().assertTargetBuiltLocally("//:top_level");
    assertZipFileContains(output, "res1");

    // Now, edit a used config and assert we rebuild
    workspace.replaceFileContents("res/_STRIPPED_RESOURCES/res1.json", "res1", "replaced");
    workspace.runBuckCommand("build", "//:top_level").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:lib");
    workspace.getBuildLog().assertTargetBuiltLocally("//:top_level");
    assertZipFileContains(output, "replaced");

    // Now, add a new config via a dep and assert we re-run the processor
    workspace.replaceFileContents("BUCK", "#add_dep", "");
    workspace.runBuckCommand("build", "//:top_level").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:lib2");
    workspace.getBuildLog().assertTargetBuiltLocally("//:top_level");
    assertZipFileContains(output, "res2");
  }

  @Test
  public void testAddingNonResourceFileDoesNotRebuildDependents() throws Exception {
    // Build once to warm cache
    output = workspace.buildAndReturnOutput("//:top_level");
    workspace.getBuildLog().assertTargetBuiltLocally("//:top_level");

    // Now, add an unrelated file and assert dep files are working
    workspace.replaceFileContents("BUCK", "#add_file", "");
    workspace.runBuckCommand("build", "//:top_level").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:lib");
    workspace.getBuildLog().assertTargetHadMatchingDepfileRuleKey("//:top_level");
  }

  @Test
  public void testEditingUnreadResourceFileDoesNotRebuildDependents() throws Exception {
    // Build once to warm cache
    workspace.runBuckCommand("build", "//:top_level").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:top_level");

    // Edit the unread metadata file and assert no dep file false negative
    workspace.replaceFileContents("res/_STRIPPED_RESOURCES/unread.json", "replace_me", "foobar");
    workspace.runBuckCommand("build", "//:top_level").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:lib");
    workspace.getBuildLog().assertTargetHadMatchingDepfileRuleKey("//:top_level");
  }

  @Test
  public void testEditingUnreadResourceFileDoesNotChangeManifestKey() throws Exception {
    // Build once to warm cache
    workspace.runBuckCommand("build", "//:top_level").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:top_level");
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();

    // Edit the unread metadata file and assert we can get a manifest hit
    workspace.replaceFileContents("res/_STRIPPED_RESOURCES/unread.json", "replace_me", "foobar");
    workspace.runBuckCommand("build", "//:top_level").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:lib");
    workspace.getBuildLog().assertTargetWasFetchedFromCacheByManifestMatch("//:top_level");
  }

  private void assertZipFileContains(Path file, String content) throws IOException {
    new ZipInspector(file)
        .assertFileContains(
            Paths.get("com/facebook/example/config/collected_configs.json"), content);
  }
}
