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
package com.facebook.buck.parser.targetnode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.graph.transformation.impl.FakeComputationEnvironment;
import com.facebook.buck.core.model.ImmutableUnconfiguredBuildTarget;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.impl.MultiPlatformTargetConfigurationTransformer;
import com.facebook.buck.core.model.platform.TargetPlatformResolver;
import com.facebook.buck.core.model.platform.impl.DefaultPlatform;
import com.facebook.buck.core.model.targetgraph.impl.ImmutableRawTargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode;
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNodeWithDeps;
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNodeWithDepsPackage;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.core.rules.platform.ThrowingConstraintResolver;
import com.facebook.buck.core.select.TestSelectableResolver;
import com.facebook.buck.core.select.impl.DefaultSelectorListResolver;
import com.facebook.buck.parser.NoopPackageBoundaryChecker;
import com.facebook.buck.parser.RawTargetNodeToTargetNodeFactory;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.ImmutableBuildFileManifest;
import com.facebook.buck.parser.manifest.ImmutableBuildPackagePathToBuildFileManifestKey;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

public class BuildPackagePathToRawTargetNodePackageComputationTest {

  @Test
  public void canParseDeps() {
    Cell cell = new TestCellBuilder().build();

    TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    TargetPlatformResolver targetPlatformResolver = configuration -> DefaultPlatform.INSTANCE;
    RawTargetNodeToTargetNodeFactory rawTargetNodeToTargetNodeFactory =
        new RawTargetNodeToTargetNodeFactory(
            typeCoercerFactory,
            TestKnownRuleTypesProvider.create(BuckPluginManagerFactory.createPluginManager()),
            new DefaultConstructorArgMarshaller(typeCoercerFactory),
            new TargetNodeFactory(typeCoercerFactory),
            new NoopPackageBoundaryChecker(),
            (file, targetNode) -> {},
            new DefaultSelectorListResolver(new TestSelectableResolver()),
            new ThrowingConstraintResolver(),
            targetPlatformResolver,
            new MultiPlatformTargetConfigurationTransformer(targetPlatformResolver));

    ImmutableMap<String, Object> rawAttributes1 =
        ImmutableMap.of(
            "name",
            "target1",
            "buck.type",
            "java_library",
            "buck.base_path",
            "",
            "deps",
            ImmutableSortedSet.of(":target2"));
    UnconfiguredBuildTarget unconfiguredBuildTarget1 =
        ImmutableUnconfiguredBuildTarget.of(
            cell.getCanonicalName(), "//", "target1", UnconfiguredBuildTarget.NO_FLAVORS);
    RawTargetNode rawTargetNode1 =
        ImmutableRawTargetNode.of(
            unconfiguredBuildTarget1,
            RuleType.of("java_library", RuleType.Kind.BUILD),
            rawAttributes1,
            ImmutableSet.of(),
            ImmutableSet.of());

    ImmutableMap<String, Object> rawAttributes2 =
        ImmutableMap.of("name", "target2", "buck.type", "java_library", "buck.base_path", "");
    UnconfiguredBuildTarget unconfiguredBuildTarget2 =
        ImmutableUnconfiguredBuildTarget.of(
            cell.getCanonicalName(), "//", "target2", UnconfiguredBuildTarget.NO_FLAVORS);
    RawTargetNode rawTargetNode2 =
        ImmutableRawTargetNode.of(
            unconfiguredBuildTarget2,
            RuleType.of("java_library", RuleType.Kind.BUILD),
            rawAttributes2,
            ImmutableSet.of(),
            ImmutableSet.of());

    BuildFileManifest buildFileManifest =
        ImmutableBuildFileManifest.of(
            ImmutableMap.of("target1", rawAttributes1, "target2", rawAttributes2),
            ImmutableSortedSet.of(),
            ImmutableMap.of(),
            Optional.empty(),
            ImmutableList.of(),
            ImmutableList.of());

    BuildPackagePathToRawTargetNodePackageComputation transformer =
        BuildPackagePathToRawTargetNodePackageComputation.of(
            rawTargetNodeToTargetNodeFactory, cell, false);
    RawTargetNodeWithDepsPackage rawTargetNodeWithDepsPackage =
        transformer.transform(
            ImmutableBuildPackagePathToRawTargetNodePackageKey.of(Paths.get("")),
            new FakeComputationEnvironment(
                ImmutableMap.of(
                    ImmutableBuildPackagePathToBuildFileManifestKey.of(Paths.get("")),
                    buildFileManifest,
                    ImmutableBuildTargetToRawTargetNodeKey.of(
                        unconfiguredBuildTarget1, Paths.get("")),
                    rawTargetNode1,
                    ImmutableBuildTargetToRawTargetNodeKey.of(
                        unconfiguredBuildTarget2, Paths.get("")),
                    rawTargetNode2)));

    ImmutableMap<String, RawTargetNodeWithDeps> allTargets =
        rawTargetNodeWithDepsPackage.getRawTargetNodesWithDeps();

    assertEquals(2, allTargets.size());

    RawTargetNodeWithDeps target1 = allTargets.get("target1");
    assertNotNull(target1);

    RawTargetNodeWithDeps target2 = allTargets.get("target2");
    assertNotNull(target2);

    ImmutableSet<UnconfiguredBuildTarget> deps = target1.getDeps();
    assertEquals(1, deps.size());

    UnconfiguredBuildTarget dep = deps.iterator().next();
    assertEquals("//", dep.getBaseName());
    assertEquals("target2", dep.getName());
  }
}
