/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UserFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.CustomFieldBehavior;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.SymlinkTree;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.AbstractCxxSource.Type;
import com.facebook.buck.cxx.CxxBinaryDescription.CommonArg;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.HeaderMode;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.Linker.CxxRuntimeType;
import com.facebook.buck.cxx.toolchain.linker.Linker.LinkableDepType;
import com.facebook.buck.cxx.toolchain.linker.impl.Linkers;
import com.facebook.buck.cxx.toolchain.nativelink.LinkableListFilter;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroups;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.json.JsonConcatenate;
import com.facebook.buck.rules.args.AddsToRuleKeyFunction;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.rules.macros.AbstractMacroExpanderWithoutPrecomputedWork;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.OutputMacroExpander;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.rules.modern.SourcePathResolverSerialization;
import com.facebook.buck.shell.ExportFile;
import com.facebook.buck.shell.ExportFileDescription.Mode;
import com.facebook.buck.shell.ExportFileDirectoryAction;
import com.facebook.buck.util.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimaps;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.google.common.io.Files;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

public class CxxDescriptionEnhancer {

  private static final Logger LOG = Logger.get(CxxDescriptionEnhancer.class);

  public static final Flavor INCREMENTAL_THINLTO = InternalFlavor.of("incremental-thinlto");
  public static final Flavor HEADER_SYMLINK_TREE_FLAVOR = InternalFlavor.of("private-headers");
  public static final Flavor EXPORTED_HEADER_SYMLINK_TREE_FLAVOR = InternalFlavor.of("headers");
  public static final Flavor STATIC_FLAVOR = InternalFlavor.of("static");
  public static final Flavor STATIC_PIC_FLAVOR = InternalFlavor.of("static-pic");
  public static final Flavor SHARED_FLAVOR = InternalFlavor.of("shared");
  public static final Flavor MACH_O_BUNDLE_FLAVOR = InternalFlavor.of("mach-o-bundle");
  public static final Flavor SHARED_LIBRARY_SYMLINK_TREE_FLAVOR =
      InternalFlavor.of("shared-library-symlink-tree");
  public static final Flavor BINARY_WITH_SHARED_LIBRARIES_SYMLINK_TREE_FLAVOR =
      InternalFlavor.of("binary-with-shared-libraries-symlink-tree");

  public static final Flavor CXX_LINK_BINARY_FLAVOR = InternalFlavor.of("binary");
  public static final Flavor CXX_LINK_THININDEX_FLAVOR = InternalFlavor.of("thinindex");

  public static final Flavor CXX_LINK_MAP_FLAVOR = UserFlavor.of("linkmap", "LinkMap file");

  private static final Pattern SONAME_EXT_MACRO_PATTERN =
      Pattern.compile("\\$\\(ext(?: ([.0-9]+))?\\)");

  private CxxDescriptionEnhancer() {}

  public static HeaderMode getHeaderModeForPlatform(
      BuildRuleResolver resolver,
      TargetConfiguration targetConfiguration,
      CxxPlatform cxxPlatform,
      boolean shouldCreateHeadersSymlinks) {
    return cxxPlatform
        .getHeaderMode()
        .orElseGet(
            () -> {
              boolean useHeaderMap =
                  (cxxPlatform.getCpp().resolve(resolver, targetConfiguration).supportsHeaderMaps()
                      && cxxPlatform
                          .getCxxpp()
                          .resolve(resolver, targetConfiguration)
                          .supportsHeaderMaps());
              return !useHeaderMap
                  ? HeaderMode.SYMLINK_TREE_ONLY
                  : (shouldCreateHeadersSymlinks
                      ? HeaderMode.SYMLINK_TREE_WITH_HEADER_MAP
                      : HeaderMode.HEADER_MAP_ONLY);
            });
  }

  public static HeaderSymlinkTree createHeaderSymlinkTree(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      HeaderMode mode,
      ImmutableMap<Path, SourcePath> headers,
      HeaderVisibility headerVisibility,
      Flavor... flavors) {
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            buildTarget, headerVisibility, flavors);
    Path headerSymlinkTreeRoot =
        CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
            projectFilesystem, buildTarget, headerVisibility, flavors);
    return CxxPreprocessables.createHeaderSymlinkTreeBuildRule(
        headerSymlinkTreeTarget, projectFilesystem, headerSymlinkTreeRoot, headers, mode);
  }

  public static HeaderSymlinkTree createHeaderSymlinkTree(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      ImmutableMap<Path, SourcePath> headers,
      HeaderVisibility headerVisibility,
      boolean shouldCreateHeadersSymlinks) {
    return createHeaderSymlinkTree(
        buildTarget,
        projectFilesystem,
        getHeaderModeForPlatform(
            resolver,
            buildTarget.getTargetConfiguration(),
            cxxPlatform,
            shouldCreateHeadersSymlinks),
        headers,
        headerVisibility,
        cxxPlatform.getFlavor());
  }

  public static HeaderSymlinkTree requireHeaderSymlinkTree(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      ImmutableMap<Path, SourcePath> headers,
      HeaderVisibility headerVisibility,
      boolean shouldCreateHeadersSymlinks) {
    BuildTarget untypedTarget = CxxLibraryDescription.getUntypedBuildTarget(buildTarget);

    return (HeaderSymlinkTree)
        graphBuilder.computeIfAbsent(
            // TODO(yiding): this build target gets recomputed in createHeaderSymlinkTree, it should
            // be passed down instead.
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                untypedTarget, headerVisibility, cxxPlatform.getFlavor()),
            (ignored) ->
                createHeaderSymlinkTree(
                    untypedTarget,
                    projectFilesystem,
                    graphBuilder,
                    cxxPlatform,
                    headers,
                    headerVisibility,
                    shouldCreateHeadersSymlinks));
  }

  /**
   * @return the {@link BuildTarget} to use for the {@link BuildRule} generating the symlink tree of
   *     headers.
   */
  @VisibleForTesting
  public static BuildTarget createHeaderSymlinkTreeTarget(
      BuildTarget target, HeaderVisibility headerVisibility, Flavor... flavors) {
    return target.withAppendedFlavors(
        ImmutableSet.<Flavor>builder()
            .add(getHeaderSymlinkTreeFlavor(headerVisibility))
            .add(flavors)
            .build());
  }

  /** @return the absolute {@link Path} to use for the symlink tree of headers. */
  public static Path getHeaderSymlinkTreePath(
      ProjectFilesystem filesystem,
      BuildTarget target,
      HeaderVisibility headerVisibility,
      Flavor... flavors) {
    return BuildTargetPaths.getGenPath(
        filesystem, createHeaderSymlinkTreeTarget(target, headerVisibility, flavors), "%s");
  }

  public static Flavor getHeaderSymlinkTreeFlavor(HeaderVisibility headerVisibility) {
    switch (headerVisibility) {
      case PUBLIC:
        return EXPORTED_HEADER_SYMLINK_TREE_FLAVOR;
      case PRIVATE:
        return HEADER_SYMLINK_TREE_FLAVOR;
      default:
        throw new RuntimeException("Unexpected value of enum ExportMode");
    }
  }

  static ImmutableMap<String, SourcePath> parseOnlyHeaders(
      BuildTarget buildTarget,
      SourcePathRuleFinder ruleFinder,
      String parameterName,
      SourceSortedSet exportedHeaders) {
    return exportedHeaders.toNameMap(
        buildTarget,
        ruleFinder.getSourcePathResolver(),
        parameterName,
        path -> !CxxGenruleDescription.wrapsCxxGenrule(ruleFinder, path),
        path -> path);
  }

  static ImmutableMap<String, SourcePath> parseOnlyPlatformHeaders(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      String headersParameterName,
      SourceSortedSet headers,
      String platformHeadersParameterName,
      PatternMatchedCollection<SourceSortedSet> platformHeaders) {
    ImmutableMap.Builder<String, SourcePath> parsed = ImmutableMap.builder();

    Function<SourcePath, SourcePath> fixup =
        path -> CxxGenruleDescription.fixupSourcePath(graphBuilder, cxxPlatform, path);

    // Include all normal exported headers that are generated by `cxx_genrule`.
    parsed.putAll(
        headers.toNameMap(
            buildTarget,
            graphBuilder.getSourcePathResolver(),
            headersParameterName,
            path -> CxxGenruleDescription.wrapsCxxGenrule(graphBuilder, path),
            fixup));

    // Include all platform specific headers.
    for (SourceSortedSet sourceList :
        platformHeaders.getMatchingValues(cxxPlatform.getFlavor().toString())) {
      parsed.putAll(
          sourceList.toNameMap(
              buildTarget,
              graphBuilder.getSourcePathResolver(),
              platformHeadersParameterName,
              path -> true,
              fixup));
    }

    return parsed.build();
  }

  /**
   * @return a map of header locations to input {@link SourcePath} objects formed by parsing the
   *     input {@link SourcePath} objects for the "headers" parameter.
   */
  public static ImmutableMap<Path, SourcePath> parseHeaders(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      Optional<CxxPlatform> cxxPlatform,
      CxxConstructorArg args) {
    ImmutableMap.Builder<String, SourcePath> headers = ImmutableMap.builder();

    // Add platform-agnostic headers.
    headers.putAll(parseOnlyHeaders(buildTarget, graphBuilder, "headers", args.getHeaders()));

    // Add platform-specific headers.
    cxxPlatform.ifPresent(
        cxxPlatformValue ->
            headers.putAll(
                parseOnlyPlatformHeaders(
                    buildTarget,
                    graphBuilder,
                    cxxPlatformValue,
                    "headers",
                    args.getHeaders(),
                    "platform_headers",
                    args.getPlatformHeaders())));

    return CxxPreprocessables.resolveHeaderMap(
        args.getHeaderNamespace().map(Paths::get).orElse(buildTarget.getBasePath()),
        headers.build());
  }

  /**
   * @return a map of header locations to input {@link SourcePath} objects formed by parsing the
   *     input {@link SourcePath} objects for the "exportedHeaders" parameter.
   */
  public static ImmutableMap<Path, SourcePath> parseExportedHeaders(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      Optional<CxxPlatform> cxxPlatform,
      CxxLibraryDescription.CommonArg args) {
    ImmutableMap.Builder<String, SourcePath> headers = ImmutableMap.builder();

    // Include platform-agnostic headers.
    headers.putAll(
        parseOnlyHeaders(buildTarget, graphBuilder, "exported_headers", args.getExportedHeaders()));

    // If a platform is specific, include platform-specific headers.
    cxxPlatform.ifPresent(
        cxxPlatformValue ->
            headers.putAll(
                parseOnlyPlatformHeaders(
                    buildTarget,
                    graphBuilder,
                    cxxPlatformValue,
                    "exported_headers",
                    args.getExportedHeaders(),
                    "exported_platform_headers",
                    args.getExportedPlatformHeaders())));

    return CxxPreprocessables.resolveHeaderMap(
        args.getHeaderNamespace().map(Paths::get).orElse(buildTarget.getBasePath()),
        headers.build());
  }

  /**
   * @return a map of header locations to input {@link SourcePath} objects formed by parsing the
   *     input {@link SourcePath} objects for the "exportedHeaders" parameter.
   */
  public static ImmutableMap<Path, SourcePath> parseExportedPlatformHeaders(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      CxxLibraryDescription.CommonArg args) {
    return CxxPreprocessables.resolveHeaderMap(
        args.getHeaderNamespace().map(Paths::get).orElse(buildTarget.getBasePath()),
        parseOnlyPlatformHeaders(
            buildTarget,
            graphBuilder,
            cxxPlatform,
            "exported_headers",
            args.getExportedHeaders(),
            "exported_platform_headers",
            args.getExportedPlatformHeaders()));
  }

  /**
   * @return a list {@link CxxSource} objects formed by parsing the input {@link SourcePath} objects
   *     for the "srcs" parameter.
   */
  public static ImmutableMap<String, CxxSource> parseCxxSources(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      CxxConstructorArg args) {
    return parseCxxSources(
        buildTarget, graphBuilder, cxxPlatform, args.getSrcs(), args.getPlatformSrcs());
  }

  public static ImmutableMap<String, CxxSource> parseCxxSources(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      ImmutableSortedSet<SourceWithFlags> srcs,
      PatternMatchedCollection<ImmutableSortedSet<SourceWithFlags>> platformSrcs) {
    ImmutableMap.Builder<String, SourceWithFlags> sources = ImmutableMap.builder();
    putAllSources(buildTarget, graphBuilder, cxxPlatform, srcs, sources);
    for (ImmutableSortedSet<SourceWithFlags> sourcesWithFlags :
        platformSrcs.getMatchingValues(cxxPlatform.getFlavor().toString())) {
      putAllSources(buildTarget, graphBuilder, cxxPlatform, sourcesWithFlags, sources);
    }
    return resolveCxxSources(sources.build());
  }

  private static void putAllSources(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      ImmutableSortedSet<SourceWithFlags> sourcesWithFlags,
      ImmutableMap.Builder<String, SourceWithFlags> sources) {
    sources.putAll(
        graphBuilder
            .getSourcePathResolver()
            .getSourcePathNames(
                buildTarget,
                "srcs",
                sourcesWithFlags.stream()
                    .map(
                        s ->
                            s.withSourcePath(
                                CxxGenruleDescription.fixupSourcePath(
                                    graphBuilder,
                                    cxxPlatform,
                                    Objects.requireNonNull(s.getSourcePath()))))
                    .collect(ImmutableList.toImmutableList()),
                x -> true,
                SourceWithFlags::getSourcePath));
  }

  public static ImmutableList<CxxPreprocessorInput> collectCxxPreprocessorInput(
      BuildTarget target,
      CxxPlatform cxxPlatform,
      ActionGraphBuilder graphBuilder,
      Iterable<BuildRule> deps,
      ImmutableMultimap<CxxSource.Type, ? extends Arg> preprocessorFlags,
      ImmutableList<HeaderSymlinkTree> headerSymlinkTrees,
      ImmutableSet<FrameworkPath> frameworks,
      Iterable<CxxPreprocessorInput> cxxPreprocessorInputFromDeps,
      ImmutableSortedSet<SourcePath> rawHeaders,
      ImmutableSortedSet<String> includeDirectories,
      ProjectFilesystem projectFilesystem) {

    // Add the private includes of any rules which this rule depends on, and which list this rule as
    // a test.
    BuildTarget targetWithoutFlavor = target.withoutFlavors();
    ImmutableList.Builder<CxxPreprocessorInput> cxxPreprocessorInputFromTestedRulesBuilder =
        ImmutableList.builder();
    for (BuildRule rule : deps) {
      if (rule instanceof NativeTestable) {
        NativeTestable testable = (NativeTestable) rule;
        if (testable.isTestedBy(targetWithoutFlavor)) {
          LOG.debug(
              "Adding private includes of tested rule %s to testing rule %s",
              rule.getBuildTarget(), target);
          cxxPreprocessorInputFromTestedRulesBuilder.add(
              testable.getPrivateCxxPreprocessorInput(cxxPlatform, graphBuilder));

          // Add any dependent headers
          cxxPreprocessorInputFromTestedRulesBuilder.addAll(
              CxxPreprocessables.getTransitiveCxxPreprocessorInputFromDeps(
                  cxxPlatform, graphBuilder, ImmutableList.of(rule)));
        }
      }
    }

    ImmutableList<CxxPreprocessorInput> cxxPreprocessorInputFromTestedRules =
        cxxPreprocessorInputFromTestedRulesBuilder.build();
    LOG.verbose(
        "Rules tested by target %s added private includes %s",
        target, cxxPreprocessorInputFromTestedRules);

    ImmutableList.Builder<CxxHeaders> allIncludes = ImmutableList.builder();
    for (HeaderSymlinkTree headerSymlinkTree : headerSymlinkTrees) {
      allIncludes.add(
          CxxSymlinkTreeHeaders.from(headerSymlinkTree, CxxPreprocessables.IncludeType.LOCAL));
    }

    CxxPreprocessorInput.Builder builder = CxxPreprocessorInput.builder();
    builder.putAllPreprocessorFlags(preprocessorFlags);

    if (!rawHeaders.isEmpty()) {
      builder.addIncludes(CxxRawHeaders.of(rawHeaders));
    }

    for (String privateInclude : includeDirectories) {
      builder.addIncludes(
          CxxIncludes.of(
              CxxPreprocessables.IncludeType.LOCAL,
              PathSourcePath.of(
                  projectFilesystem, target.getBasePath().resolve(privateInclude).normalize())));
    }

    builder.addAllIncludes(allIncludes.build()).addAllFrameworks(frameworks);

    CxxPreprocessorInput localPreprocessorInput = builder.build();

    return ImmutableList.<CxxPreprocessorInput>builder()
        .add(localPreprocessorInput)
        .addAll(cxxPreprocessorInputFromDeps)
        .addAll(cxxPreprocessorInputFromTestedRules)
        .build();
  }

  public static BuildTarget createStaticLibraryBuildTarget(
      BuildTarget target, Flavor platform, PicType pic) {
    return target.withAppendedFlavors(
        platform, pic == PicType.PDC ? STATIC_FLAVOR : STATIC_PIC_FLAVOR);
  }

  public static BuildTarget createSharedLibraryBuildTarget(
      BuildTarget target, Flavor platform, Linker.LinkType linkType) {
    Flavor linkFlavor;
    switch (linkType) {
      case SHARED:
        linkFlavor = SHARED_FLAVOR;
        break;
      case MACH_O_BUNDLE:
        linkFlavor = MACH_O_BUNDLE_FLAVOR;
        break;
      case EXECUTABLE:
      default:
        throw new IllegalStateException(
            "Only SHARED and MACH_O_BUNDLE types expected, got: " + linkType);
    }
    return target.withAppendedFlavors(platform, linkFlavor);
  }

  public static String getStaticLibraryName(
      BuildTarget target,
      Optional<String> staticLibraryBasename,
      String extension,
      boolean uniqueLibraryNameEnabled) {
    return getStaticLibraryName(
        target, staticLibraryBasename, extension, "", uniqueLibraryNameEnabled);
  }

  public static String getStaticLibraryBasename(
      BuildTarget target, String suffix, boolean uniqueLibraryNameEnabled) {
    String postfix = "";
    if (uniqueLibraryNameEnabled) {
      String hashedPath =
          BaseEncoding.base64Url()
              .omitPadding()
              .encode(
                  Hashing.sha1()
                      .hashString(
                          target.getUnflavoredBuildTarget().getFullyQualifiedName(), Charsets.UTF_8)
                      .asBytes())
              .substring(0, 10);
      postfix = "-" + hashedPath;
    }
    return target.getShortName() + postfix + suffix;
  }

  /** Returns static library name */
  public static String getStaticLibraryName(
      BuildTarget target,
      Optional<String> staticLibraryBasename,
      String extension,
      String suffix,
      boolean uniqueLibraryNameEnabled) {
    String basename =
        staticLibraryBasename.orElseGet(
            () -> getStaticLibraryBasename(target, suffix, uniqueLibraryNameEnabled));
    return String.format("lib%s.%s", basename, extension);
  }

  public static String getSharedLibrarySoname(
      Optional<String> declaredSoname, BuildTarget target, CxxPlatform platform) {
    if (!declaredSoname.isPresent()) {
      return getDefaultSharedLibrarySoname(target, platform);
    }
    return getNonDefaultSharedLibrarySoname(
        declaredSoname.get(),
        platform.getSharedLibraryExtension(),
        platform.getSharedLibraryVersionedExtensionFormat());
  }

  @VisibleForTesting
  static String getNonDefaultSharedLibrarySoname(
      String declared,
      String sharedLibraryExtension,
      String sharedLibraryVersionedExtensionFormat) {
    Matcher match = SONAME_EXT_MACRO_PATTERN.matcher(declared);
    if (!match.find()) {
      return declared;
    }
    String version = match.group(1);
    if (version == null) {
      return match.replaceFirst(sharedLibraryExtension);
    }
    return match.replaceFirst(String.format(sharedLibraryVersionedExtensionFormat, version));
  }

  public static String getDefaultSharedLibrarySoname(BuildTarget target, CxxPlatform platform) {
    String libName =
        Joiner.on('_')
            .join(
                ImmutableList.builder()
                    .addAll(
                        StreamSupport.stream(target.getBasePath().spliterator(), false)
                            .map(Object::toString)
                            .filter(x -> !x.isEmpty())
                            .iterator())
                    .add(target.getShortName())
                    .build());
    String extension = platform.getSharedLibraryExtension();
    return String.format("lib%s.%s", libName, extension);
  }

  public static Path getSharedLibraryPath(
      ProjectFilesystem filesystem, BuildTarget sharedLibraryTarget, String soname) {
    return BuildTargetPaths.getGenPath(filesystem, sharedLibraryTarget, "%s/" + soname);
  }

  private static Path getBinaryOutputPath(
      BuildTarget target,
      ProjectFilesystem filesystem,
      Optional<String> extension,
      final Optional<String> outputRootName) {
    String fullFormat;
    if (outputRootName.isPresent()) {
      // Make sure that for user defined output root name, the output goes into
      // <target>/<User Defined Output Root Name> file.
      String extensionFormat = extension.map(ext -> "." + ext).orElse("");
      String outputName = outputRootName.get() + extensionFormat;
      fullFormat = String.format("%%s%s%s", File.separator, outputName);
    } else {
      // Keep the current behavior if the user has not specified it's own output root name.
      fullFormat = extension.map(ext -> "%s." + ext).orElse("%s");
    }
    return BuildTargetPaths.getGenPath(filesystem, target, fullFormat);
  }

  @VisibleForTesting
  public static BuildTarget createCxxLinkTarget(
      BuildTarget target, Optional<LinkerMapMode> flavoredLinkerMapMode) {
    if (flavoredLinkerMapMode.isPresent()) {
      target = target.withAppendedFlavors(flavoredLinkerMapMode.get().getFlavor());
    }
    return target.withAppendedFlavors(CXX_LINK_BINARY_FLAVOR);
  }

  /**
   * @return a function that transforms the {@link FrameworkPath} to search paths with any embedded
   *     macros expanded.
   */
  public static AddsToRuleKeyFunction<FrameworkPath, Path> frameworkPathToSearchPath(
      CxxPlatform cxxPlatform, SourcePathResolver resolver) {
    return new FrameworkPathToSearchPathFunction(cxxPlatform, resolver);
  }

  private static class FrameworkPathToSearchPathFunction
      implements AddsToRuleKeyFunction<FrameworkPath, Path> {
    @AddToRuleKey private final AddsToRuleKeyFunction<String, String> translateMacrosFn;
    // TODO(cjhopman): This should be refactored to accept the resolver as an argument.
    @CustomFieldBehavior(SourcePathResolverSerialization.class)
    private final SourcePathResolver resolver;

    public FrameworkPathToSearchPathFunction(CxxPlatform cxxPlatform, SourcePathResolver resolver) {
      this.resolver = resolver;
      this.translateMacrosFn =
          new CxxFlags.TranslateMacrosFunction(
              ImmutableSortedMap.copyOf(cxxPlatform.getFlagMacros()), cxxPlatform);
    }

    @Override
    public Path apply(FrameworkPath input) {
      String pathAsString =
          FrameworkPath.getUnexpandedSearchPath(
                  resolver::getAbsolutePath, Functions.identity(), input)
              .toString();
      return Paths.get(translateMacrosFn.apply(pathAsString));
    }
  }

  public static CxxLinkAndCompileRules createBuildRuleForCxxThinLtoBinary(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      CommonArg args,
      ImmutableSet<BuildTarget> extraDeps,
      Optional<StripStyle> stripStyle,
      Optional<LinkerMapMode> flavoredLinkerMapMode) {
    ImmutableMap<String, CxxSource> srcs = parseCxxSources(target, graphBuilder, cxxPlatform, args);
    ImmutableMap<Path, SourcePath> headers =
        parseHeaders(target, graphBuilder, Optional.of(cxxPlatform), args);

    // Build the binary deps.
    ImmutableSortedSet.Builder<BuildRule> depsBuilder = ImmutableSortedSet.naturalOrder();
    // Add original declared and extra deps.
    args.getCxxDeps().get(graphBuilder, cxxPlatform).forEach(depsBuilder::add);
    // Add in deps found via deps query.
    ImmutableList<BuildRule> depQueryDeps =
        args.getDepsQuery().map(query -> Objects.requireNonNull(query.getResolvedQuery()))
            .orElse(ImmutableSortedSet.of()).stream()
            .map(graphBuilder::getRule)
            .collect(ImmutableList.toImmutableList());
    depsBuilder.addAll(depQueryDeps);
    // Add any extra deps passed in.
    extraDeps.stream().map(graphBuilder::getRule).forEach(depsBuilder::add);
    ImmutableSortedSet<BuildRule> deps = depsBuilder.build();

    CxxLinkOptions linkOptions =
        CxxLinkOptions.of(
            false, false
            );

    ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects =
        createCompileRulesForCxxBinary(
            target,
            projectFilesystem,
            graphBuilder,
            cellRoots,
            cxxBuckConfig,
            cxxPlatform,
            srcs,
            headers,
            deps,
            args.getLinkStyle().orElse(Linker.LinkableDepType.STATIC),
            linkOptions,
            args.getPreprocessorFlags(),
            args.getPlatformPreprocessorFlags(),
            args.getLangPreprocessorFlags(),
            args.getLangPlatformPreprocessorFlags(),
            args.getFrameworks(),
            args.getCompilerFlags(),
            args.getLangCompilerFlags(),
            args.getPlatformCompilerFlags(),
            args.getLangPlatformCompilerFlags(),
            args.getPrefixHeader(),
            args.getPrecompiledHeader(),
            args.getRawHeaders(),
            args.getIncludeDirectories());

    BuildTarget thinIndexTarget = target.withAppendedFlavors(CXX_LINK_THININDEX_FLAVOR);
    Path indexOutput =
        getBinaryOutputPath(
            thinIndexTarget, projectFilesystem, Optional.empty(), Optional.of("thinlto.indices"));

    CommandTool.Builder executableBuilder = new CommandTool.Builder();
    Linker linker = cxxPlatform.getLd().resolve(graphBuilder, target.getTargetConfiguration());

    ImmutableList<Arg> indexArgs =
        createLinkArgsForCxxBinary(
            target,
            projectFilesystem,
            graphBuilder,
            cellRoots,
            cxxPlatform,
            objects,
            deps,
            executableBuilder,
            linker,
            args.getLinkStyle().orElse(Linker.LinkableDepType.STATIC),
            indexOutput,
            args.getLinkerFlags(),
            args.getPlatformLinkerFlags());

    CxxThinLTOIndex cxxThinLTOIndex =
        (CxxThinLTOIndex)
            graphBuilder.computeIfAbsent(
                thinIndexTarget,
                ignored ->
                    CxxLinkableEnhancer.createCxxThinLTOIndexBuildRule(
                        cxxBuckConfig,
                        cxxPlatform,
                        projectFilesystem,
                        graphBuilder,
                        thinIndexTarget,
                        indexOutput,
                        args.getLinkStyle().orElse(Linker.LinkableDepType.STATIC),
                        Optional.empty(),
                        RichStream.from(deps)
                            .filter(NativeLinkableGroup.class)
                            .map(g -> g.getNativeLinkable(cxxPlatform, graphBuilder))
                            .toImmutableList(),
                        args.getCxxRuntimeType(),
                        ImmutableSet.of(),
                        args.getLinkDepsQueryWhole()
                            ? RichStream.from(depQueryDeps)
                                .map(BuildRule::getBuildTarget)
                                .toImmutableSet()
                            : ImmutableSet.of(),
                        NativeLinkableInput.builder()
                            .setArgs(indexArgs)
                            .setFrameworks(args.getFrameworks())
                            .setLibraries(args.getLibraries())
                            .build()));

    ImmutableMap.Builder<String, CxxSource> srcObjects = ImmutableMap.builder();
    // We need a map<key, val>.
    // key is the filename of the output thin object file, with the full directory structure
    // of the original input source. The directory structure is required to disambiguate
    // source files with the same name.
    // val is the sourcePath of the output of the compile phase.
    // Example:
    // lib/item.cpp.o -> buck-out/gen/binary/target#flavors-item.cpp.o/item.cpp.o
    objects.entrySet().stream()
        .forEach(
            entry -> {
              final CxxPreprocessAndCompile rule = entry.getKey();
              final SourcePath path = entry.getValue();
              final String filenameWithPath =
                  rule.getSourceInputPath(graphBuilder.getSourcePathResolver());
              final String outputNameWithPath =
                  filenameWithPath + "." + cxxPlatform.getObjectFileExtension();

              Preconditions.checkState(
                  srcs.get(filenameWithPath) != null,
                  "Requesting flags for non existent source file in ThinLTO build");

              srcObjects.put(
                  outputNameWithPath,
                  CxxSource.of(Type.CXX_THINLINK, path, srcs.get(filenameWithPath).getFlags()));
            });

    ImmutableMap<CxxThinLTOOpt, SourcePath> nativeObjects =
        createThinOptRulesForCxxBinary(
            target,
            projectFilesystem,
            graphBuilder,
            cellRoots,
            cxxBuckConfig,
            cxxPlatform,
            srcObjects.build(),
            args.getLinkStyle().orElse(Linker.LinkableDepType.STATIC),
            linkOptions,
            args.getCompilerFlags(),
            args.getLangCompilerFlags(),
            args.getPlatformCompilerFlags(),
            args.getLangPlatformCompilerFlags(),
            args.getPrefixHeader(),
            args.getPrecompiledHeader(),
            cxxThinLTOIndex.getSourcePathToOutput());

    Path linkOutput =
        getBinaryOutputPath(
            flavoredLinkerMapMode.isPresent()
                ? target.withAppendedFlavors(flavoredLinkerMapMode.get().getFlavor())
                : target,
            projectFilesystem,
            cxxPlatform.getBinaryExtension(),
            args.getExecutableName());

    ImmutableList<Arg> linkArgs =
        createLinkArgsForCxxBinary(
            target,
            projectFilesystem,
            graphBuilder,
            cellRoots,
            cxxPlatform,
            nativeObjects,
            deps,
            executableBuilder,
            linker,
            args.getLinkStyle().orElse(Linker.LinkableDepType.STATIC),
            indexOutput,
            args.getLinkerFlags(),
            args.getPlatformLinkerFlags());

    BuildTarget linkRuleTarget = createCxxLinkTarget(target, flavoredLinkerMapMode);

    CxxLink cxxLink =
        (CxxLink)
            graphBuilder.computeIfAbsent(
                linkRuleTarget,
                ignored ->
                    // Generate the final link rule.  We use the top-level target as the link rule's
                    // target, so that it corresponds to the actual binary we build.
                    CxxLinkableEnhancer.createCxxLinkableBuildRule(
                        cxxBuckConfig,
                        cxxPlatform,
                        projectFilesystem,
                        graphBuilder,
                        linkRuleTarget,
                        Linker.LinkType.EXECUTABLE,
                        Optional.empty(),
                        linkOutput,
                        args.getLinkerExtraOutputs(),
                        args.getLinkStyle().orElse(Linker.LinkableDepType.STATIC),
                        Optional.empty(),
                        CxxLinkOptions.of(
                            args.getThinLto(),
                            args.getFatLto()
                            ),
                        RichStream.from(deps)
                            .filter(NativeLinkableGroup.class)
                            .map(g -> g.getNativeLinkable(cxxPlatform, graphBuilder))
                            .toImmutableList(),
                        args.getCxxRuntimeType(),
                        Optional.empty(),
                        ImmutableSet.of(),
                        args.getLinkDepsQueryWhole()
                            ? RichStream.from(depQueryDeps)
                                .map(BuildRule::getBuildTarget)
                                .toImmutableSet()
                            : ImmutableSet.of(),
                        NativeLinkableInput.builder()
                            .setArgs(linkArgs)
                            .setFrameworks(args.getFrameworks())
                            .setLibraries(args.getLibraries())
                            .build(),
                        Optional.empty(),
                        cellRoots));

    BuildRule binaryRuleForExecutable;
    Optional<CxxStrip> cxxStrip = Optional.empty();
    if (stripStyle.isPresent()) {
      BuildTarget cxxTarget = target;
      if (flavoredLinkerMapMode.isPresent()) {
        cxxTarget = cxxTarget.withAppendedFlavors(flavoredLinkerMapMode.get().getFlavor());
      }
      CxxStrip stripRule =
          createCxxStripRule(
              cxxTarget,
              projectFilesystem,
              graphBuilder,
              stripStyle.get(),
              cxxBuckConfig.shouldCacheStrip(),
              cxxLink,
              cxxPlatform,
              args.getExecutableName());
      cxxStrip = Optional.of(stripRule);
      binaryRuleForExecutable = stripRule;
    } else {
      binaryRuleForExecutable = cxxLink;
    }

    SourcePath sourcePathToExecutable = binaryRuleForExecutable.getSourcePathToOutput();

    // Add the output of the link as the lone argument needed to invoke this binary as a tool.
    executableBuilder.addArg(SourcePathArg.of(sourcePathToExecutable));

    return new CxxLinkAndCompileRules(
        cxxLink,
        cxxStrip,
        ImmutableSortedSet.copyOf(objects.keySet()),
        executableBuilder.build(),
        deps);
  }

  public static CxxLinkAndCompileRules createBuildRulesForCxxBinaryDescriptionArg(
      TargetGraph targetGraph,
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      CommonArg args,
      ImmutableSet<BuildTarget> extraDeps,
      Optional<StripStyle> stripStyle,
      Optional<LinkerMapMode> flavoredLinkerMapMode) {

    ImmutableMap<String, CxxSource> srcs = parseCxxSources(target, graphBuilder, cxxPlatform, args);
    ImmutableMap<Path, SourcePath> headers =
        parseHeaders(target, graphBuilder, Optional.of(cxxPlatform), args);

    // Build the binary deps.
    ImmutableSortedSet.Builder<BuildRule> depsBuilder = ImmutableSortedSet.naturalOrder();
    // Add original declared and extra deps.
    args.getCxxDeps().get(graphBuilder, cxxPlatform).forEach(depsBuilder::add);
    // Add in deps found via deps query.
    ImmutableList<BuildRule> depQueryDeps =
        args.getDepsQuery().map(query -> Objects.requireNonNull(query.getResolvedQuery()))
            .orElse(ImmutableSortedSet.of()).stream()
            .map(graphBuilder::getRule)
            .collect(ImmutableList.toImmutableList());
    depsBuilder.addAll(depQueryDeps);
    // Add any extra deps passed in.
    extraDeps.stream().map(graphBuilder::getRule).forEach(depsBuilder::add);
    ImmutableSortedSet<BuildRule> deps = depsBuilder.build();

    CxxLinkOptions linkOptions =
        CxxLinkOptions.of(
            args.getThinLto(),
            args.getFatLto()
            );

    Optional<LinkableListFilter> linkableListFilter =
        LinkableListFilterFactory.from(cxxBuckConfig, args, targetGraph);

    return createBuildRulesForCxxBinary(
        target,
        projectFilesystem,
        graphBuilder,
        cellRoots,
        cxxBuckConfig,
        cxxPlatform,
        srcs,
        headers,
        deps,
        args.getLinkDepsQueryWhole()
            ? RichStream.from(depQueryDeps).map(BuildRule::getBuildTarget).toImmutableSet()
            : ImmutableSet.of(),
        stripStyle,
        flavoredLinkerMapMode,
        args.getLinkStyle().orElse(Linker.LinkableDepType.STATIC),
        linkableListFilter,
        linkOptions,
        args.getPreprocessorFlags(),
        args.getPlatformPreprocessorFlags(),
        args.getLangPreprocessorFlags(),
        args.getLangPlatformPreprocessorFlags(),
        args.getFrameworks(),
        args.getLibraries(),
        args.getCompilerFlags(),
        args.getLangCompilerFlags(),
        args.getPlatformCompilerFlags(),
        args.getLangPlatformCompilerFlags(),
        args.getPrefixHeader(),
        args.getPrecompiledHeader(),
        args.getLinkerFlags(),
        args.getLinkerExtraOutputs(),
        args.getPlatformLinkerFlags(),
        args.getCxxRuntimeType(),
        args.getRawHeaders(),
        args.getIncludeDirectories(),
        args.getExecutableName());
  }

  private static ImmutableList<Arg> createLinkArgsForCxxBinary(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      ImmutableMap<? extends CxxIntermediateBuildProduct, SourcePath> objects,
      SortedSet<BuildRule> deps,
      CommandTool.Builder executableBuilder,
      Linker linker,
      LinkableDepType linkStyle,
      Path linkOutput,
      ImmutableList<StringWithMacros> linkerFlags,
      PatternMatchedCollection<ImmutableList<StringWithMacros>> platformLinkerFlags) {
    ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();

    // Build up the linker flags, which support macro expansion.
    {
      ImmutableList<AbstractMacroExpanderWithoutPrecomputedWork<? extends Macro>> expanders =
          ImmutableList.of(new CxxLocationMacroExpander(cxxPlatform), new OutputMacroExpander());

      StringWithMacrosConverter macrosConverter =
          StringWithMacrosConverter.builder()
              .setBuildTarget(target)
              .setCellPathResolver(cellRoots)
              .setActionGraphBuilder(graphBuilder)
              .setExpanders(expanders)
              .setSanitizer(getStringWithMacrosArgSanitizer(cxxPlatform))
              .build();
      CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
              linkerFlags, platformLinkerFlags, cxxPlatform)
          .stream()
          .map(macrosConverter::convert)
          .forEach(argsBuilder::add);
    }

    // Special handling for dynamically linked binaries with rpath support
    if (linkStyle == Linker.LinkableDepType.SHARED
        && linker.getSharedLibraryLoadingType() == Linker.SharedLibraryLoadingType.RPATH) {
      // Create a symlink tree with for all shared libraries needed by this binary.
      SymlinkTree sharedLibraries =
          requireSharedLibrarySymlinkTree(
              target, projectFilesystem, graphBuilder, cxxPlatform, deps);

      // Embed a origin-relative library path into the binary so it can find the shared libraries.
      // The shared libraries root is absolute. Also need an absolute path to the linkOutput
      Path absLinkOut = projectFilesystem.resolve(linkOutput);
      argsBuilder.addAll(
          StringArg.from(
              Linkers.iXlinker(
                  "-rpath",
                  String.format(
                      "%s/%s",
                      linker.origin(),
                      absLinkOut.getParent().relativize(sharedLibraries.getRoot()).toString()))));

      // Add all the shared libraries and the symlink tree as inputs to the tool that represents
      // this binary, so that users can attach the proper deps.
      executableBuilder.addNonHashableInput(sharedLibraries.getRootSourcePath());
      executableBuilder.addInputs(sharedLibraries.getLinks().values());
    }

    // Add object files into the args.
    ImmutableList<SourcePathArg> objectArgs =
        SourcePathArg.from(objects.values()).stream()
            .map(
                input -> {
                  Preconditions.checkArgument(input instanceof SourcePathArg);
                  return (SourcePathArg) input;
                })
            .collect(ImmutableList.toImmutableList());
    argsBuilder.addAll(FileListableLinkerInputArg.from(objectArgs));

    return argsBuilder.build();
  }

  // Generate and add all the build rules to preprocess and compile the source to the
  // graphBuilder and get the `SourcePath`s representing the generated object files.
  private static PicType createPicTypeForCxxBinary(
      CxxPlatform cxxPlatform, LinkableDepType linkStyle) {
    return linkStyle == Linker.LinkableDepType.STATIC
        ? PicType.PDC
        : cxxPlatform.getPicTypeForSharedLinking();
  }

  private static ImmutableListMultimap<CxxSource.Type, Arg> createCompilerFlagsForCxxBinary(
      BuildTarget target,
      CellPathResolver cellRoots,
      ActionGraphBuilder graphBuilder,
      CxxLinkOptions linkOptions,
      CxxPlatform cxxPlatform,
      ImmutableList<StringWithMacros> compilerFlags,
      ImmutableMap<Type, ImmutableList<StringWithMacros>> langCompilerFlags,
      PatternMatchedCollection<ImmutableList<StringWithMacros>> platformCompilerFlags,
      ImmutableMap<Type, PatternMatchedCollection<ImmutableList<StringWithMacros>>>
          langPlatformCompilerFlags) {
    StringWithMacrosConverter macrosConverter =
        getStringWithMacrosArgsConverter(target, cellRoots, graphBuilder, cxxPlatform);
    ImmutableListMultimap.Builder<CxxSource.Type, Arg> allCompilerFlagsBuilder =
        ImmutableListMultimap.builder();
    allCompilerFlagsBuilder.putAll(
        Multimaps.transformValues(
            CxxFlags.getLanguageFlagsWithMacros(
                compilerFlags,
                platformCompilerFlags,
                langCompilerFlags,
                langPlatformCompilerFlags,
                cxxPlatform),
            macrosConverter::convert));
    if (linkOptions.getThinLto()) {
      allCompilerFlagsBuilder.putAll(CxxFlags.toLanguageFlags(StringArg.from("-flto=thin")));
    } else if (linkOptions.getFatLto()) {
      allCompilerFlagsBuilder.putAll(CxxFlags.toLanguageFlags(StringArg.from("-flto")));
    }

    return allCompilerFlagsBuilder.build();
  }

  private static ImmutableMap<CxxThinLTOOpt, SourcePath> createThinOptRulesForCxxBinary(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, CxxSource> srcs,
      LinkableDepType linkStyle,
      CxxLinkOptions linkOptions,
      ImmutableList<StringWithMacros> compilerFlags,
      ImmutableMap<Type, ImmutableList<StringWithMacros>> langCompilerFlags,
      PatternMatchedCollection<ImmutableList<StringWithMacros>> platformCompilerFlags,
      ImmutableMap<Type, PatternMatchedCollection<ImmutableList<StringWithMacros>>>
          langPlatformCompilerFlags,
      Optional<SourcePath> prefixHeader,
      Optional<SourcePath> precompiledHeader,
      SourcePath thinIndicesRoot) {
    ImmutableListMultimap<CxxSource.Type, Arg> allCompilerFlags =
        createCompilerFlagsForCxxBinary(
            target,
            cellRoots,
            graphBuilder,
            linkOptions,
            cxxPlatform,
            compilerFlags,
            langCompilerFlags,
            platformCompilerFlags,
            langPlatformCompilerFlags);
    PicType pic = createPicTypeForCxxBinary(cxxPlatform, linkStyle);

    return CxxSourceRuleFactory.of(
            projectFilesystem,
            target,
            graphBuilder,
            graphBuilder.getSourcePathResolver(),
            cxxBuckConfig,
            cxxPlatform,
            ImmutableList.of(),
            allCompilerFlags,
            prefixHeader,
            precompiledHeader,
            pic)
        .requireThinOptRules(srcs, thinIndicesRoot);
  }

  private static ImmutableMap<CxxPreprocessAndCompile, SourcePath> createCompileRulesForCxxBinary(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, CxxSource> srcs,
      ImmutableMap<Path, SourcePath> headers,
      SortedSet<BuildRule> deps,
      LinkableDepType linkStyle,
      CxxLinkOptions linkOptions,
      ImmutableList<StringWithMacros> preprocessorFlags,
      PatternMatchedCollection<ImmutableList<StringWithMacros>> platformPreprocessorFlags,
      ImmutableMap<Type, ImmutableList<StringWithMacros>> langPreprocessorFlags,
      ImmutableMap<Type, PatternMatchedCollection<ImmutableList<StringWithMacros>>>
          langPlatformPreprocessorFlags,
      ImmutableSortedSet<FrameworkPath> frameworks,
      ImmutableList<StringWithMacros> compilerFlags,
      ImmutableMap<Type, ImmutableList<StringWithMacros>> langCompilerFlags,
      PatternMatchedCollection<ImmutableList<StringWithMacros>> platformCompilerFlags,
      ImmutableMap<Type, PatternMatchedCollection<ImmutableList<StringWithMacros>>>
          langPlatformCompilerFlags,
      Optional<SourcePath> prefixHeader,
      Optional<SourcePath> precompiledHeader,
      ImmutableSortedSet<SourcePath> rawHeaders,
      ImmutableSortedSet<String> includeDirectories) {
    StringWithMacrosConverter macrosConverter =
        getStringWithMacrosArgsConverter(target, cellRoots, graphBuilder, cxxPlatform);

    ImmutableListMultimap<CxxSource.Type, Arg> allCompilerFlags =
        createCompilerFlagsForCxxBinary(
            target,
            cellRoots,
            graphBuilder,
            linkOptions,
            cxxPlatform,
            compilerFlags,
            langCompilerFlags,
            platformCompilerFlags,
            langPlatformCompilerFlags);
    PicType pic = createPicTypeForCxxBinary(cxxPlatform, linkStyle);

    // Setup the header symlink tree and combine all the preprocessor input from this rule
    // and all dependencies.
    boolean shouldCreatePrivateHeadersSymlinks = cxxBuckConfig.getPrivateHeadersSymlinksEnabled();
    HeaderSymlinkTree headerSymlinkTree =
        requireHeaderSymlinkTree(
            target,
            projectFilesystem,
            graphBuilder,
            cxxPlatform,
            headers,
            HeaderVisibility.PRIVATE,
            shouldCreatePrivateHeadersSymlinks);
    ImmutableList<CxxPreprocessorInput> cxxPreprocessorInput =
        collectCxxPreprocessorInput(
            target,
            cxxPlatform,
            graphBuilder,
            deps,
            ImmutableListMultimap.copyOf(
                Multimaps.transformValues(
                    CxxFlags.getLanguageFlagsWithMacros(
                        preprocessorFlags,
                        platformPreprocessorFlags,
                        langPreprocessorFlags,
                        langPlatformPreprocessorFlags,
                        cxxPlatform),
                    macrosConverter::convert)),
            ImmutableList.of(headerSymlinkTree),
            frameworks,
            CxxPreprocessables.getTransitiveCxxPreprocessorInputFromDeps(
                cxxPlatform,
                graphBuilder,
                RichStream.from(deps)
                    .filter(CxxPreprocessorDep.class::isInstance)
                    .toImmutableList()),
            rawHeaders,
            includeDirectories,
            projectFilesystem);

    return CxxSourceRuleFactory.of(
            projectFilesystem,
            target,
            graphBuilder,
            graphBuilder.getSourcePathResolver(),
            cxxBuckConfig,
            cxxPlatform,
            cxxPreprocessorInput,
            allCompilerFlags,
            prefixHeader,
            precompiledHeader,
            pic)
        .requirePreprocessAndCompileRules(srcs);
  }

  public static CxxLinkAndCompileRules createBuildRulesForCxxBinary(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, CxxSource> srcs,
      ImmutableMap<Path, SourcePath> headers,
      SortedSet<BuildRule> deps,
      ImmutableSet<BuildTarget> linkWholeDeps,
      Optional<StripStyle> stripStyle,
      Optional<LinkerMapMode> flavoredLinkerMapMode,
      LinkableDepType linkStyle,
      Optional<LinkableListFilter> linkableListFilter,
      CxxLinkOptions linkOptions,
      ImmutableList<StringWithMacros> preprocessorFlags,
      PatternMatchedCollection<ImmutableList<StringWithMacros>> platformPreprocessorFlags,
      ImmutableMap<Type, ImmutableList<StringWithMacros>> langPreprocessorFlags,
      ImmutableMap<Type, PatternMatchedCollection<ImmutableList<StringWithMacros>>>
          langPlatformPreprocessorFlags,
      ImmutableSortedSet<FrameworkPath> frameworks,
      ImmutableSortedSet<FrameworkPath> libraries,
      ImmutableList<StringWithMacros> compilerFlags,
      ImmutableMap<Type, ImmutableList<StringWithMacros>> langCompilerFlags,
      PatternMatchedCollection<ImmutableList<StringWithMacros>> platformCompilerFlags,
      ImmutableMap<Type, PatternMatchedCollection<ImmutableList<StringWithMacros>>>
          langPlatformCompilerFlags,
      Optional<SourcePath> prefixHeader,
      Optional<SourcePath> precompiledHeader,
      ImmutableList<StringWithMacros> linkerFlags,
      ImmutableList<String> linkerExtraOutputs,
      PatternMatchedCollection<ImmutableList<StringWithMacros>> platformLinkerFlags,
      Optional<CxxRuntimeType> cxxRuntimeType,
      ImmutableSortedSet<SourcePath> rawHeaders,
      ImmutableSortedSet<String> includeDirectories,
      Optional<String> outputRootName) {
    //    TODO(beefon): should be:
    //    Path linkOutput = getLinkOutputPath(
    //        createCxxLinkTarget(params.getBuildTarget(), flavoredLinkerMapMode),
    //        projectFilesystem);

    ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects =
        createCompileRulesForCxxBinary(
            target,
            projectFilesystem,
            graphBuilder,
            cellRoots,
            cxxBuckConfig,
            cxxPlatform,
            srcs,
            headers,
            deps,
            linkStyle,
            linkOptions,
            preprocessorFlags,
            platformPreprocessorFlags,
            langPreprocessorFlags,
            langPlatformPreprocessorFlags,
            frameworks,
            compilerFlags,
            langCompilerFlags,
            platformCompilerFlags,
            langPlatformCompilerFlags,
            prefixHeader,
            precompiledHeader,
            rawHeaders,
            includeDirectories);

    CommandTool.Builder executableBuilder = new CommandTool.Builder();
    Linker linker = cxxPlatform.getLd().resolve(graphBuilder, target.getTargetConfiguration());
    BuildTarget linkRuleTarget = createCxxLinkTarget(target, flavoredLinkerMapMode);

    Path linkOutput =
        getBinaryOutputPath(
            flavoredLinkerMapMode.isPresent()
                ? target.withAppendedFlavors(flavoredLinkerMapMode.get().getFlavor())
                : target,
            projectFilesystem,
            cxxPlatform.getBinaryExtension(),
            outputRootName);

    ImmutableList<Arg> args =
        createLinkArgsForCxxBinary(
            linkRuleTarget,
            projectFilesystem,
            graphBuilder,
            cellRoots,
            cxxPlatform,
            objects,
            deps,
            executableBuilder,
            linker,
            linkStyle,
            linkOutput,
            linkerFlags,
            platformLinkerFlags);

    CxxLink cxxLink =
        (CxxLink)
            graphBuilder.computeIfAbsent(
                linkRuleTarget,
                ignored ->
                    // Generate the final link rule.  We use the top-level target as the link rule's
                    // target, so that it corresponds to the actual binary we build.
                    CxxLinkableEnhancer.createCxxLinkableBuildRule(
                        cxxBuckConfig,
                        cxxPlatform,
                        projectFilesystem,
                        graphBuilder,
                        linkRuleTarget,
                        Linker.LinkType.EXECUTABLE,
                        Optional.empty(),
                        linkOutput,
                        linkerExtraOutputs,
                        linkStyle,
                        linkableListFilter,
                        linkOptions,
                        RichStream.from(deps)
                            .filter(NativeLinkableGroup.class)
                            .map(g -> g.getNativeLinkable(cxxPlatform, graphBuilder))
                            .toImmutableList(),
                        cxxRuntimeType,
                        Optional.empty(),
                        ImmutableSet.of(),
                        linkWholeDeps,
                        NativeLinkableInput.builder()
                            .setArgs(args)
                            .setFrameworks(frameworks)
                            .setLibraries(libraries)
                            .build(),
                        Optional.empty(),
                        cellRoots));

    BuildRule binaryRuleForExecutable;
    Optional<CxxStrip> cxxStrip = Optional.empty();
    if (stripStyle.isPresent()) {
      BuildTarget cxxTarget = target;
      if (flavoredLinkerMapMode.isPresent()) {
        cxxTarget = cxxTarget.withAppendedFlavors(flavoredLinkerMapMode.get().getFlavor());
      }
      CxxStrip stripRule =
          createCxxStripRule(
              cxxTarget,
              projectFilesystem,
              graphBuilder,
              stripStyle.get(),
              cxxBuckConfig.shouldCacheStrip(),
              cxxLink,
              cxxPlatform,
              outputRootName);
      cxxStrip = Optional.of(stripRule);
      binaryRuleForExecutable = stripRule;
    } else {
      binaryRuleForExecutable = cxxLink;
    }

    SourcePath sourcePathToExecutable = binaryRuleForExecutable.getSourcePathToOutput();

    // Special handling for dynamically linked binaries requiring dependencies to be in the same
    // directory
    if (linkStyle == Linker.LinkableDepType.SHARED
        && linker.getSharedLibraryLoadingType()
            == Linker.SharedLibraryLoadingType.THE_SAME_DIRECTORY) {
      Path binaryName = linkOutput.getFileName();
      BuildTarget binaryWithSharedLibrariesTarget =
          createBinaryWithSharedLibrariesSymlinkTreeTarget(target, cxxPlatform.getFlavor());
      Path symlinkTreeRoot =
          getBinaryWithSharedLibrariesSymlinkTreePath(
              projectFilesystem, binaryWithSharedLibrariesTarget, cxxPlatform.getFlavor());
      Path appPath = symlinkTreeRoot.resolve(binaryName);
      SymlinkTree binaryWithSharedLibraries =
          requireBinaryWithSharedLibrariesSymlinkTree(
              target,
              projectFilesystem,
              graphBuilder,
              cxxPlatform,
              deps,
              binaryName,
              sourcePathToExecutable);

      executableBuilder.addNonHashableInput(binaryWithSharedLibraries.getRootSourcePath());
      executableBuilder.addInputs(binaryWithSharedLibraries.getLinks().values());
      sourcePathToExecutable =
          ExplicitBuildTargetSourcePath.of(binaryWithSharedLibrariesTarget, appPath);
    }

    // Add the output of the link as the lone argument needed to invoke this binary as a tool.
    executableBuilder.addArg(SourcePathArg.of(sourcePathToExecutable));

    return new CxxLinkAndCompileRules(
        cxxLink,
        cxxStrip,
        ImmutableSortedSet.copyOf(objects.keySet()),
        executableBuilder.build(),
        deps);
  }

  public static CxxStrip createCxxStripRule(
      BuildTarget baseBuildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      StripStyle stripStyle,
      boolean isCacheable,
      BuildRule unstrippedBinaryRule,
      CxxPlatform cxxPlatform,
      Optional<String> outputRootName) {
    return (CxxStrip)
        graphBuilder.computeIfAbsent(
            baseBuildTarget.withAppendedFlavors(CxxStrip.RULE_FLAVOR, stripStyle.getFlavor()),
            stripBuildTarget ->
                new CxxStrip(
                    stripBuildTarget,
                    projectFilesystem,
                    Preconditions.checkNotNull(
                        unstrippedBinaryRule.getSourcePathToOutput(),
                        "Cannot strip BuildRule with no output (%s)",
                        unstrippedBinaryRule.getBuildTarget()),
                    graphBuilder,
                    stripStyle,
                    cxxPlatform.getStrip(),
                    isCacheable,
                    CxxDescriptionEnhancer.getBinaryOutputPath(
                        stripBuildTarget,
                        projectFilesystem,
                        cxxPlatform.getBinaryExtension(),
                        outputRootName)));
  }

  public static BuildRule createUberCompilationDatabase(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder) {
    Optional<CxxCompilationDatabaseDependencies> compilationDatabases =
        graphBuilder.requireMetadata(
            buildTarget
                .withoutFlavors(CxxCompilationDatabase.UBER_COMPILATION_DATABASE)
                .withAppendedFlavors(CxxCompilationDatabase.COMPILATION_DATABASE),
            CxxCompilationDatabaseDependencies.class);
    Preconditions.checkState(compilationDatabases.isPresent());
    return new JsonConcatenate(
        buildTarget,
        projectFilesystem,
        new BuildRuleParams(
            () ->
                ImmutableSortedSet.copyOf(
                    graphBuilder.filterBuildRuleInputs(
                        compilationDatabases.get().getSourcePaths())),
            ImmutableSortedSet::of,
            ImmutableSortedSet.of()),
        graphBuilder
            .getSourcePathResolver()
            .getAllAbsolutePaths(compilationDatabases.get().getSourcePaths()),
        "compilation-database-concatenate",
        "Concatenate compilation databases",
        "uber-compilation-database",
        "compile_commands.json");
  }

  public static Optional<CxxCompilationDatabaseDependencies> createCompilationDatabaseDependencies(
      BuildTarget buildTarget,
      FlavorDomain<?> platforms,
      ActionGraphBuilder graphBuilder,
      ImmutableSortedSet<BuildTarget> deps) {
    Preconditions.checkState(
        buildTarget.getFlavors().contains(CxxCompilationDatabase.COMPILATION_DATABASE));
    Optional<Flavor> cxxPlatformFlavor = platforms.getFlavor(buildTarget);
    Preconditions.checkState(
        cxxPlatformFlavor.isPresent(),
        "Could not find cxx platform in:\n%s",
        Joiner.on(", ").join(buildTarget.getFlavors()));
    ImmutableSet.Builder<SourcePath> sourcePaths = ImmutableSet.builder();
    for (BuildTarget dep : deps) {
      Optional<CxxCompilationDatabaseDependencies> compilationDatabases =
          graphBuilder.requireMetadata(
              dep.withAppendedFlavors(
                  CxxCompilationDatabase.COMPILATION_DATABASE, cxxPlatformFlavor.get()),
              CxxCompilationDatabaseDependencies.class);
      compilationDatabases.ifPresent(cxxDeps -> sourcePaths.addAll(cxxDeps.getSourcePaths()));
    }
    // Not all parts of Buck use require yet, so require the rule here so it's available in the
    // graphBuilder for the parts that don't.
    BuildRule buildRule = graphBuilder.requireRule(buildTarget);
    sourcePaths.add(buildRule.getSourcePathToOutput());
    return Optional.of(CxxCompilationDatabaseDependencies.of(sourcePaths.build()));
  }

  /**
   * @return the {@link BuildTarget} to use for the {@link BuildRule} generating the symlink tree of
   *     shared libraries.
   */
  public static BuildTarget createSharedLibrarySymlinkTreeTarget(
      BuildTarget target, Flavor platform) {
    return target.withAppendedFlavors(SHARED_LIBRARY_SYMLINK_TREE_FLAVOR, platform);
  }

  /** @return the {@link Path} to use for the symlink tree of headers. */
  public static Path getSharedLibrarySymlinkTreePath(
      ProjectFilesystem filesystem, BuildTarget target, Flavor platform) {
    return BuildTargetPaths.getGenPath(
        filesystem, createSharedLibrarySymlinkTreeTarget(target, platform), "%s");
  }

  /**
   * Build a {@link HeaderSymlinkTree} of all the shared libraries found via the top-level rule's
   * transitive dependencies.
   */
  public static SymlinkTree createSharedLibrarySymlinkTree(
      BuildTarget baseBuildTarget,
      ProjectFilesystem filesystem,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> deps,
      Function<? super BuildRule, Optional<Iterable<? extends BuildRule>>> passthrough) {

    BuildTarget symlinkTreeTarget =
        createSharedLibrarySymlinkTreeTarget(baseBuildTarget, cxxPlatform.getFlavor());
    Path symlinkTreeRoot =
        getSharedLibrarySymlinkTreePath(filesystem, baseBuildTarget, cxxPlatform.getFlavor());

    ImmutableMap<BuildTarget, NativeLinkableGroup> roots =
        NativeLinkableGroups.getNativeLinkableRoots(deps, passthrough);
    ImmutableSortedMap<String, SourcePath> libraries =
        NativeLinkables.getTransitiveSharedLibraries(
            graphBuilder,
            Iterables.transform(
                roots.values(), g -> g.getNativeLinkable(cxxPlatform, graphBuilder)),
            false);

    ImmutableMap.Builder<Path, SourcePath> links = ImmutableMap.builder();
    for (Map.Entry<String, SourcePath> ent : libraries.entrySet()) {
      links.put(Paths.get(ent.getKey()), ent.getValue());
    }
    return new SymlinkTree(
        "cxx_binary", symlinkTreeTarget, filesystem, symlinkTreeRoot, links.build());
  }

  public static SymlinkTree requireSharedLibrarySymlinkTree(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> deps) {
    return (SymlinkTree)
        graphBuilder.computeIfAbsent(
            createSharedLibrarySymlinkTreeTarget(buildTarget, cxxPlatform.getFlavor()),
            ignored ->
                createSharedLibrarySymlinkTree(
                    buildTarget,
                    filesystem,
                    graphBuilder,
                    cxxPlatform,
                    deps,
                    n -> Optional.empty()));
  }

  private static BuildTarget createBinaryWithSharedLibrariesSymlinkTreeTarget(
      BuildTarget target, Flavor platform) {
    return target.withAppendedFlavors(BINARY_WITH_SHARED_LIBRARIES_SYMLINK_TREE_FLAVOR, platform);
  }

  private static Path getBinaryWithSharedLibrariesSymlinkTreePath(
      ProjectFilesystem filesystem, BuildTarget target, Flavor platform) {
    return BuildTargetPaths.getGenPath(
        filesystem, createBinaryWithSharedLibrariesSymlinkTreeTarget(target, platform), "%s");
  }

  private static SymlinkTree createBinaryWithSharedLibrariesSymlinkTree(
      BuildTarget baseBuildTarget,
      ProjectFilesystem filesystem,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> deps,
      Path binaryName,
      SourcePath binarySource) {

    BuildTarget symlinkTreeTarget =
        createBinaryWithSharedLibrariesSymlinkTreeTarget(baseBuildTarget, cxxPlatform.getFlavor());
    Path symlinkTreeRoot =
        getBinaryWithSharedLibrariesSymlinkTreePath(
            filesystem, baseBuildTarget, cxxPlatform.getFlavor());

    ImmutableMap<BuildTarget, NativeLinkableGroup> roots =
        NativeLinkableGroups.getNativeLinkableRoots(deps, n -> Optional.empty());
    ImmutableSortedMap<String, SourcePath> libraries =
        NativeLinkables.getTransitiveSharedLibraries(
            graphBuilder,
            Iterables.transform(
                roots.values(), g -> g.getNativeLinkable(cxxPlatform, graphBuilder)),
            false);

    ImmutableMap.Builder<Path, SourcePath> links = ImmutableMap.builder();
    for (Map.Entry<String, SourcePath> ent : libraries.entrySet()) {
      links.put(Paths.get(ent.getKey()), ent.getValue());
    }
    links.put(binaryName, binarySource);
    return new SymlinkTree(
        "cxx_binary", symlinkTreeTarget, filesystem, symlinkTreeRoot, links.build());
  }

  private static SymlinkTree requireBinaryWithSharedLibrariesSymlinkTree(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> deps,
      Path binaryName,
      SourcePath binarySource) {
    return (SymlinkTree)
        graphBuilder.computeIfAbsent(
            createBinaryWithSharedLibrariesSymlinkTreeTarget(buildTarget, cxxPlatform.getFlavor()),
            ignored ->
                createBinaryWithSharedLibrariesSymlinkTree(
                    buildTarget,
                    filesystem,
                    graphBuilder,
                    cxxPlatform,
                    deps,
                    binaryName,
                    binarySource));
  }

  public static Flavor flavorForLinkableDepType(Linker.LinkableDepType linkableDepType) {
    switch (linkableDepType) {
      case STATIC:
        return STATIC_FLAVOR;
      case STATIC_PIC:
        return STATIC_PIC_FLAVOR;
      case SHARED:
        return SHARED_FLAVOR;
    }
    throw new RuntimeException(String.format("Unsupported LinkableDepType: '%s'", linkableDepType));
  }

  /** Resolve the map of names to SourcePaths to a map of names to CxxSource objects. */
  private static ImmutableMap<String, CxxSource> resolveCxxSources(
      ImmutableMap<String, SourceWithFlags> sources) {

    ImmutableMap.Builder<String, CxxSource> cxxSources = ImmutableMap.builder();

    // For each entry in the input C/C++ source, build a CxxSource object to wrap
    // it's name, input path, and output object file path.
    for (ImmutableMap.Entry<String, SourceWithFlags> ent : sources.entrySet()) {
      String extension = Files.getFileExtension(ent.getKey());
      Optional<CxxSource.Type> type = CxxSource.Type.fromExtension(extension);
      if (!type.isPresent()) {
        throw new HumanReadableException("invalid extension \"%s\": %s", extension, ent.getKey());
      }
      cxxSources.put(
          ent.getKey(),
          CxxSource.of(type.get(), ent.getValue().getSourcePath(), ent.getValue().getFlags()));
    }

    return cxxSources.build();
  }

  /** @return a {@link StringWithMacrosConverter} to use when converting C/C++ flags. */
  public static StringWithMacrosConverter getStringWithMacrosArgsConverter(
      BuildTarget target,
      CellPathResolver cellPathResolver,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform) {
    return StringWithMacrosConverter.builder()
        .setBuildTarget(target)
        .setCellPathResolver(cellPathResolver)
        .setActionGraphBuilder(graphBuilder)
        .addExpanders(new CxxLocationMacroExpander(cxxPlatform), new OutputMacroExpander())
        .setSanitizer(getStringWithMacrosArgSanitizer(cxxPlatform))
        .build();
  }

  private static Function<String, String> getStringWithMacrosArgSanitizer(CxxPlatform platform) {
    return platform.getCompilerDebugPathSanitizer().sanitizer(Optional.empty());
  }

  public static String normalizeModuleName(String moduleName) {
    return moduleName.replaceAll("[^A-Za-z0-9]", "_");
  }

  /**
   * @return a {@link BuildRule} that produces a single file that contains linker map produced
   *     during the linking process.
   * @throws HumanReadableException if the linked does not support linker maps.
   */
  public static BuildRule createLinkMap(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      CxxLinkAndCompileRules cxxLinkAndCompileRules) {
    CxxLink cxxLink = cxxLinkAndCompileRules.getCxxLink();
    Optional<Path> linkerMap = cxxLink.getLinkerMapPath();
    if (!linkerMap.isPresent()) {
      throw new HumanReadableException(
          "Linker for target %s does not support linker maps.", target);
    }
    return new ExportFile(
        target,
        projectFilesystem,
        ruleFinder,
        "LinkerMap.txt",
        Mode.COPY,
        ExplicitBuildTargetSourcePath.of(cxxLink.getBuildTarget(), linkerMap.get()),
        ExportFileDirectoryAction.FAIL);
  }
}
