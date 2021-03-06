/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.baseline.plugins;

import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import java.util.stream.Collectors;
import netflix.nebula.dependency.recommender.DependencyRecommendationsPlugin;
import netflix.nebula.dependency.recommender.RecommendationStrategies;
import netflix.nebula.dependency.recommender.provider.RecommendationProviderContainer;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.plugins.BasePlugin;

/**
 * Transitively applies nebula.dependency recommender to replace the following common gradle snippet.
 *
 * <pre>
 * buildscript {
 *     dependencies {
 *         classpath 'com.netflix.nebula:nebula-dependency-recommender:5.2.0'
 *     }
 * }
 *
 * allprojects {
 *     apply plugin: 'nebula.dependency-recommender'
 *
 *     dependencyRecommendations {
 *         strategy OverrideTransitives
 *         propertiesFile file: project.rootProject.file('versions.props')
 *         if (file('versions.props').exists()) {
 *             propertiesFile file: project.file('versions.props')
 *         }
 *     }
 * }
 * </pre>
 */
public final class BaselineVersions implements Plugin<Project> {

    @Override
    public void apply(Project project) {

        // apply plugin: "nebula.dependency-recommender"
        project.getPluginManager().apply(DependencyRecommendationsPlugin.class);

        // get dependencyRecommendations extension
        RecommendationProviderContainer extension = project
                .getExtensions()
                .getByType(RecommendationProviderContainer.class);

        extension.setStrategy(RecommendationStrategies.OverrideTransitives); // default is 'ConflictResolved'

        File rootVersionsPropsFile = rootVersionsPropsFile(project);
        extension.propertiesFile(ImmutableMap.of("file", rootVersionsPropsFile));

        project.getTasks().register("checkBomConflict", BomConflictCheckTask.class, rootVersionsPropsFile);
        if (project != project.getRootProject()) {
            // allow nested projects to specify their own nested versions.props file
            if (project.file("versions.props").exists()) {
                extension.propertiesFile(ImmutableMap.of("file", project.file("versions.props")));
            }
            project.getTasks().register("checkVersionsProps", task -> task.dependsOn("checkBomConflict"));
        } else {
            project.getTasks().register("checkNoUnusedPin", NoUnusedPinCheckTask.class, rootVersionsPropsFile);
            project.getTasks().register("checkVersionsProps",
                    task -> task.dependsOn("checkBomConflict", "checkNoUnusedPin"));
        }

        project.getPluginManager().apply(BasePlugin.class);
        project.getTasks().named("check").configure(task -> task.dependsOn("checkVersionsProps"));
    }

    private static File rootVersionsPropsFile(Project project) {
        File file = project.getRootProject().file("versions.props");
        if (!file.canRead()) {
            try {
                project.getLogger().info("Could not find 'versions.props' file, creating...");
                Files.createFile(file.toPath());
            } catch (IOException e) {
                project.getLogger().warn("Unable to create empty versions.props file, please create this manually", e);
            }
        }
        return file;
    }

    static Set<String> getResolvedArtifacts(Project project) {
        return project.getConfigurations().stream()
                .filter(Configuration::isCanBeResolved)
                .flatMap(configuration -> {
                    try {
                        return configuration
                                .getResolvedConfiguration()
                                .getResolvedArtifacts()
                                .stream()
                                .map(resolvedArtifact -> {
                                    ModuleVersionIdentifier id = resolvedArtifact.getModuleVersion().getId();
                                    return id.getGroup() + ":" + id.getName();
                                });
                    } catch (Exception e) {
                        throw new RuntimeException("Error during resolution of the artifacts of all "
                                + "configuration from all subprojects", e);
                    }
                })
                .collect(Collectors.toSet());
    }
}
