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

package com.facebook.buck.apple;

import com.facebook.buck.apple.platform_type.ApplePlatformType;
import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.HasOutputName;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.TransitiveCxxPreprocessorInputCache;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.LegacyNativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableCacheKey;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.PlatformLockedNativeLinkableGroup;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

public class PrebuiltAppleFramework extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements CxxPreprocessorDep, LegacyNativeLinkableGroup, HasOutputName {

  @AddToRuleKey(stringify = true)
  private final Path out;

  @AddToRuleKey private final NativeLinkableGroup.Linkage preferredLinkage;

  @AddToRuleKey private final SourcePath frameworkPath;
  private final String frameworkName;
  private final Function<? super CxxPlatform, ImmutableList<String>> exportedLinkerFlags;
  private final ImmutableSet<FrameworkPath> frameworks;
  private final Optional<Pattern> supportedPlatformsRegex;
  private final FlavorDomain<AppleCxxPlatform> applePlatformFlavorDomain;

  private final LoadingCache<NativeLinkableCacheKey, NativeLinkableInput> nativeLinkableCache =
      CacheBuilder.newBuilder().build(CacheLoader.from(this::getNativeLinkableInputUncached));

  private final TransitiveCxxPreprocessorInputCache transitiveCxxPreprocessorInputCache =
      new TransitiveCxxPreprocessorInputCache(this);
  private final PlatformLockedNativeLinkableGroup.Cache linkableCache =
      LegacyNativeLinkableGroup.getNativeLinkableCache(this);

  public PrebuiltAppleFramework(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      SourcePathResolver pathResolver,
      SourcePath frameworkPath,
      Linkage preferredLinkage,
      ImmutableSet<FrameworkPath> frameworks,
      Optional<Pattern> supportedPlatformsRegex,
      Function<? super CxxPlatform, ImmutableList<String>> exportedLinkerFlags,
      FlavorDomain<AppleCxxPlatform> applePlatformFlavorDomain) {
    super(buildTarget, projectFilesystem, params);
    this.frameworkPath = frameworkPath;
    this.exportedLinkerFlags = exportedLinkerFlags;
    this.preferredLinkage = preferredLinkage;
    this.frameworks = frameworks;
    this.supportedPlatformsRegex = supportedPlatformsRegex;

    this.frameworkName = pathResolver.getAbsolutePath(frameworkPath).getFileName().toString();
    this.out =
        BuildTargetPaths.getGenPath(getProjectFilesystem(), buildTarget, "%s")
            .resolve(frameworkName);
    this.applePlatformFlavorDomain = applePlatformFlavorDomain;
  }

  private boolean isPlatformSupported(CxxPlatform cxxPlatform) {
    return !supportedPlatformsRegex.isPresent()
        || supportedPlatformsRegex.get().matcher(cxxPlatform.getFlavor().toString()).find();
  }

  @Override
  public boolean isCacheable() {
    // Frameworks on macOS include symbolic links which are not preserved when cached.
    // When the prebuilt framework target gets fetched from the cache, it includes
    // duplicate resources which means that the bundle cannot be signed anymore due
    // failing internal checks in Apple's `codesign` tool. Since prebuilt frameworks
    // are already built, not caching them is okay.
    return false;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    // This file is copied rather than symlinked so that when it is included in an archive zip and
    // unpacked on another machine, it is an ordinary file in both scenarios.
    ImmutableList.Builder<Step> builder = ImmutableList.builder();
    builder.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), out.getParent())));
    builder.add(
        RmStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), out))
            .withRecursive(true));
    builder.add(
        CopyStep.forDirectory(
            getProjectFilesystem(),
            context.getSourcePathResolver().getAbsolutePath(frameworkPath),
            out,
            CopyStep.DirectoryMode.CONTENTS_ONLY));

    buildableContext.recordArtifact(out);
    return builder.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), out);
  }

  @Override
  public String getOutputName() {
    return this.frameworkName;
  }

  @Override
  public PlatformLockedNativeLinkableGroup.Cache getNativeLinkableCompatibilityCache() {
    return linkableCache;
  }

  @Override
  public Iterable<CxxPreprocessorDep> getCxxPreprocessorDeps(
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableList.of();
    }
    return FluentIterable.from(getBuildDeps()).filter(CxxPreprocessorDep.class);
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    CxxPreprocessorInput.Builder builder = CxxPreprocessorInput.builder();

    if (isPlatformSupported(cxxPlatform)) {
      builder.addAllFrameworks(frameworks);
      builder.addFrameworks(FrameworkPath.ofSourcePath(getSourcePathToOutput()));
    }
    return builder.build();
  }

  @Override
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return transitiveCxxPreprocessorInputCache.getUnchecked(cxxPlatform, graphBuilder);
  }

  @Override
  public Iterable<NativeLinkableGroup> getNativeLinkableDeps(BuildRuleResolver ruleResolver) {
    return FluentIterable.from(getDeclaredDeps()).filter(NativeLinkableGroup.class);
  }

  @Override
  public Iterable<NativeLinkableGroup> getNativeLinkableDepsForPlatform(
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableList.of();
    }
    return getNativeLinkableDeps(ruleResolver);
  }

  @Override
  public Iterable<? extends NativeLinkableGroup> getNativeLinkableExportedDeps(
      BuildRuleResolver ruleResolver) {
    return ImmutableList.of();
  }

  private NativeLinkableInput getNativeLinkableInputUncached(NativeLinkableCacheKey key) {
    CxxPlatform cxxPlatform = key.getCxxPlatform();

    if (!isPlatformSupported(cxxPlatform)) {
      return NativeLinkableInput.of();
    }

    Linker.LinkableDepType type = key.getType();

    ImmutableList.Builder<Arg> linkerArgsBuilder = ImmutableList.builder();
    linkerArgsBuilder.addAll(
        StringArg.from(Objects.requireNonNull(exportedLinkerFlags.apply(cxxPlatform))));

    ImmutableSet.Builder<FrameworkPath> frameworkPaths = ImmutableSet.builder();
    frameworkPaths.addAll(Objects.requireNonNull(frameworks));

    frameworkPaths.add(FrameworkPath.ofSourcePath(getSourcePathToOutput()));
    if (type == Linker.LinkableDepType.SHARED) {
      Optional<AppleCxxPlatform> appleCxxPlatform =
          applePlatformFlavorDomain.getValue(ImmutableSet.of(cxxPlatform.getFlavor()));
      boolean isMacTarget =
          appleCxxPlatform
              .map(p -> p.getAppleSdk().getApplePlatform().getType() == ApplePlatformType.MAC)
              .orElse(false);
      String loaderPath = isMacTarget ? "@loader_path/../Frameworks" : "@loader_path/Frameworks";
      String executablePath =
          isMacTarget ? "@executable_path/../Frameworks" : "@loader_path/Frameworks";
      linkerArgsBuilder.addAll(StringArg.from("-rpath", loaderPath, "-rpath", executablePath));
    }

    ImmutableList<Arg> linkerArgs = linkerArgsBuilder.build();
    return NativeLinkableInput.of(linkerArgs, frameworkPaths.build(), Collections.emptySet());
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type,
      boolean forceLinkWhole,
      ActionGraphBuilder graphBuilder,
      TargetConfiguration targetConfiguration) {
    // forceLinkWhole is not needed for PrebuiltAppleFramework so we provide constant value
    return nativeLinkableCache.getUnchecked(
        NativeLinkableCacheKey.of(cxxPlatform.getFlavor(), type, false, cxxPlatform));
  }

  @Override
  public NativeLinkableGroup.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
    return this.preferredLinkage;
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return ImmutableMap.of();
  }
}
