/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.rules.macros;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AbsoluteOutputMacroExpanderTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  private ProjectFilesystem filesystem;
  private ActionGraphBuilder graphBuilder;
  private CellPathResolver cellPathResolver;
  private StringWithMacrosConverter converter;

  private ActionGraphBuilder setup(ProjectFilesystem projectFilesystem, BuildTarget buildTarget) {
    cellPathResolver = TestCellBuilder.createCellRoots(projectFilesystem);
    graphBuilder = new TestActionGraphBuilder();
    converter =
        StringWithMacrosConverter.builder()
            .setBuildTarget(buildTarget)
            .setCellPathResolver(cellPathResolver)
            .setActionGraphBuilder(graphBuilder)
            .addExpanders(new AbsoluteOutputMacroExpander())
            .build();
    return graphBuilder;
  }

  @Test
  public void replaceOutputOfSupplementaryOutputWithRelativePath() throws Exception {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//some:target");
    filesystem = new FakeProjectFilesystem();
    graphBuilder = setup(filesystem, buildTarget);
    RuleWithSupplementaryOutput rule = new RuleWithSupplementaryOutput(buildTarget, filesystem);
    graphBuilder.addToIndex(rule);

    String originalCmd = "$(abs_output one)";

    String transformedString = coerceAndStringify(originalCmd, rule);

    // Verify that the correct cmd was created.
    Path absolutePath =
        graphBuilder
            .getSourcePathResolver()
            .getAbsolutePath(rule.getSourcePathToSupplementaryOutput("one"));
    String expectedCmd = absolutePath.toString();

    assertEquals(expectedCmd, transformedString);
  }

  @Test
  public void missingLocationArgumentThrows() throws Exception {
    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem("/some_root");
    cellPathResolver = TestCellBuilder.createCellRoots(filesystem);

    thrown.expect(CoerceFailedException.class);
    thrown.expectMessage(
        allOf(
            containsString("The macro '$(abs_output )' could not be expanded:"),
            containsString("expected exactly one argument (found 1)")));

    new DefaultTypeCoercerFactory()
        .typeCoercerForType(StringWithMacros.class)
        .coerce(
            cellPathResolver,
            filesystem,
            Paths.get(""),
            EmptyTargetConfiguration.INSTANCE,
            "$(abs_output )");
  }

  private String coerceAndStringify(String input, BuildRule rule) throws CoerceFailedException {
    StringWithMacros stringWithMacros =
        (StringWithMacros)
            new DefaultTypeCoercerFactory()
                .typeCoercerForType(StringWithMacros.class)
                .coerce(
                    cellPathResolver,
                    filesystem,
                    rule.getBuildTarget().getBasePath(),
                    EmptyTargetConfiguration.INSTANCE,
                    input);
    Arg arg = converter.convert(stringWithMacros);
    return Arg.stringify(arg, graphBuilder.getSourcePathResolver());
  }
}
