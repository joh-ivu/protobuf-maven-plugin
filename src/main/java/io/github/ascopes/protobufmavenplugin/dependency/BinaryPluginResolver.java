/*
 * Copyright (C) 2023 - 2024, Ashley Scopes.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.ascopes.protobufmavenplugin.dependency;

import io.github.ascopes.protobufmavenplugin.Plugin;
import io.github.ascopes.protobufmavenplugin.system.FileUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import javax.inject.Inject;
import javax.inject.Named;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.shared.transfer.artifact.ArtifactCoordinate;

/**
 * Protoc plugin resolver.
 *
 * <p>This will either look the plugins up on the Maven repository (including any dependencies)
 * if they are provided as Maven dependency coordinates, or will discover them from the host system
 * PATH if an executable name is provided.
 *
 * @author Ashley Scopes
 */
@Named
public final class BinaryPluginResolver {

  private final MavenDependencyPathResolver mavenDependencyPathResolver;
  private final PlatformArtifactFactory platformDependencyFactory;
  private final SystemPathBinaryResolver systemPathResolver;

  @Inject
  public BinaryPluginResolver(
      MavenDependencyPathResolver mavenDependencyPathResolver,
      PlatformArtifactFactory platformDependencyFactory,
      SystemPathBinaryResolver systemPathResolver
  ) {
    this.mavenDependencyPathResolver = mavenDependencyPathResolver;
    this.platformDependencyFactory = platformDependencyFactory;
    this.systemPathResolver = systemPathResolver;
  }

  public Collection<ResolvedPlugin> resolveAll(
      MavenSession session,
      Collection<Plugin> pluginBeans
  ) throws ResolutionException {
    var resolvedPlugins = new ArrayList<ResolvedPlugin>();

    for (var pluginBean : pluginBeans) {
      var path = resolvePath(session, pluginBean);

      var resolvedPlugin = ImmutableResolvedPlugin
          .builder()
          .id(path.getFileName().toString())
          .path(path)
          .build();

      resolvedPlugins.add(resolvedPlugin);
    }

    return resolvedPlugins;
  }

  private Path resolvePath(
      MavenSession session,
      Plugin pluginBean
  ) throws ResolutionException {
    if (pluginBean.getArtifact().isPresent()) {
      var coordinate = enrich(pluginBean.getArtifact().get());
      try {
        var path = mavenDependencyPathResolver.resolveArtifact(session, coordinate);
        FileUtils.makeExecutable(path);
        return path;
      } catch (IOException ex) {
        throw new ResolutionException("Failed to set executable bit on protoc plugin", ex);
      }
    }

    // At this point, we know this will be present, since we validated this
    // earlier. Use orElseThrow to avoid a useless compiler warning.
    var executableName = pluginBean.getExecutableName().orElseThrow();
    return systemPathResolver.resolve(executableName)
          .orElseThrow(() -> new ResolutionException("No executable '"
              + executableName + "' was found on the system path"));
  }

  private ArtifactCoordinate enrich(ArtifactCoordinate coordinate) {
    // If the extension is null, then Maven treats this as a JAR by default, which is
    // annoying and the opposite of what we actually want to happen. If we pass a JAR
    // here, then explicitly swap it out with null as this is *never* what we want to
    // happen here.
    var extension = coordinate.getExtension().equals("jar")
        ? null
        : coordinate.getExtension();

    return platformDependencyFactory.createArtifact(
        coordinate.getGroupId(),
        coordinate.getArtifactId(),
        coordinate.getVersion(),
        extension,
        coordinate.getClassifier()
    );
  }
}
