/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.features.go;

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import org.immutables.value.Value;

/**
 * A rule for specifying go test runners. This rule effectively does nothing expect propagate the
 * path to a test runner template generator.
 */
public class GoTestRunnerDescription
    implements DescriptionWithTargetGraph<GoTestRunnerDescriptionArg> {

  @Override
  public Class<GoTestRunnerDescriptionArg> getConstructorArgType() {
    return GoTestRunnerDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      GoTestRunnerDescriptionArg args) {
    return new GoTestRunner(
        buildTarget, context.getProjectFilesystem(), args.getTestRunnerGenerator());
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractGoTestRunnerDescriptionArg extends CommonDescriptionArg {
    SourcePath getTestRunnerGenerator();
  }
}
