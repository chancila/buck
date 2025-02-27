/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.rules.query;

import com.facebook.buck.core.description.arg.ConstructorArg;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.QueryTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryFileTarget;
import com.facebook.buck.rules.coercer.CoercedTypeCache;
import com.facebook.buck.rules.coercer.ParamInfo;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.function.Predicate;

public class QueryTargetAccessor {

  private QueryTargetAccessor() {}

  /** Get targets in attribute. */
  public static <T extends ConstructorArg> ImmutableSet<QueryTarget> getTargetsInAttribute(
      TypeCoercerFactory typeCoercerFactory, TargetNode<T> node, String attribute) {
    Class<?> constructorArgClass = node.getConstructorArg().getClass();
    ParamInfo info =
        CoercedTypeCache.INSTANCE
            .getAllParamInfo(typeCoercerFactory, constructorArgClass)
            .get(attribute);
    if (info == null) {
      // Ignore if the field does not exist in this rule.
      return ImmutableSet.of();
    }
    ImmutableSet.Builder<QueryTarget> builder =
        new ImmutableSortedSet.Builder<>(QueryTarget::compare);
    info.traverse(
        node.getCellNames(),
        value -> {
          if (value instanceof Path) {
            builder.add(QueryFileTarget.of(PathSourcePath.of(node.getFilesystem(), (Path) value)));
          } else if (value instanceof SourcePath) {
            builder.add(extractSourcePath((SourcePath) value));
          } else if (value instanceof BuildTarget) {
            builder.add(extractBuildTargetContainer((BuildTarget) value));
          }
        },
        node.getConstructorArg());
    return builder.build();
  }

  public static QueryTarget extractSourcePath(SourcePath sourcePath) {
    if (sourcePath instanceof PathSourcePath) {
      return QueryFileTarget.of(sourcePath);
    } else if (sourcePath instanceof BuildTargetSourcePath) {
      return QueryBuildTarget.of(((BuildTargetSourcePath) sourcePath).getTarget());
    }
    throw new HumanReadableException("Unsupported source path type: %s", sourcePath.getClass());
  }

  /** Filters the objects in the given attribute that satisfy the given predicate. */
  public static <T extends ConstructorArg> ImmutableSet<Object> filterAttributeContents(
      TypeCoercerFactory typeCoercerFactory,
      TargetNode<T> node,
      String attribute,
      Predicate<Object> predicate) {
    Class<?> constructorArgClass = node.getConstructorArg().getClass();
    ParamInfo info =
        CoercedTypeCache.INSTANCE
            .getAllParamInfo(typeCoercerFactory, constructorArgClass)
            .get(attribute);
    if (info == null) {
      // Ignore if the field does not exist in this rule.
      return ImmutableSet.of();
    }
    ImmutableSet.Builder<Object> builder = ImmutableSet.builder();
    info.traverse(
        node.getCellNames(),
        value -> {
          if (predicate.test(value)) {
            builder.add(value);
          }
        },
        node.getConstructorArg());
    return builder.build();
  }

  public static QueryTarget extractBuildTargetContainer(BuildTarget buildTargetContainer) {
    return QueryBuildTarget.of(buildTargetContainer);
  }
}
