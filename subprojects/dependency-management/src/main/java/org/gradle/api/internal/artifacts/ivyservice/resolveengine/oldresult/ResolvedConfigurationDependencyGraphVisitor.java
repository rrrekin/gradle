/*
 * Copyright 2014 the original author or authors.
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

package org.gradle.api.internal.artifacts.ivyservice.resolveengine.oldresult;

import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ModuleVersionSelector;
import org.gradle.api.internal.artifacts.ResolvedConfigurationIdentifier;
import org.gradle.api.internal.artifacts.ivyservice.DefaultUnresolvedDependency;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.DependencyGraphEdge;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.DependencyGraphNode;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.DependencyGraphPathResolver;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.DependencyGraphVisitor;
import org.gradle.internal.resolve.ModuleVersionResolveException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class ResolvedConfigurationDependencyGraphVisitor implements DependencyGraphVisitor {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResolvedConfigurationDependencyGraphVisitor.class);

    private final ResolvedConfigurationBuilder builder;
    private final Map<ModuleVersionSelector, BrokenDependency> failuresByRevisionId = new LinkedHashMap<ModuleVersionSelector, BrokenDependency>();
    private DependencyGraphNode root;

    public ResolvedConfigurationDependencyGraphVisitor(ResolvedConfigurationBuilder builder) {
        this.builder = builder;
    }

    public void start(DependencyGraphNode root) {
        this.root = root;
    }

    public void visitNode(DependencyGraphNode resolvedConfiguration) {
        builder.newResolvedDependency(resolvedConfiguration.getNodeId());
        for (DependencyGraphEdge dependency : resolvedConfiguration.getOutgoingEdges()) {
            ModuleVersionResolveException failure = dependency.getFailure();
            if (failure != null) {
                addUnresolvedDependency(dependency, dependency.getRequestedModuleVersion(), failure);
            }
        }
    }

    public void visitEdge(DependencyGraphNode resolvedConfiguration) {
        LOGGER.debug("Attaching {} to its parents.", resolvedConfiguration);
        for (DependencyGraphEdge dependency : resolvedConfiguration.getIncomingEdges()) {
            attachToParents(dependency, resolvedConfiguration);
        }
    }

    private void attachToParents(DependencyGraphEdge dependency, DependencyGraphNode childConfiguration) {
        ResolvedConfigurationIdentifier parent = dependency.getFrom().getNodeId();
        ResolvedConfigurationIdentifier child = childConfiguration.getNodeId();

        builder.addChild(parent, child);
        if (parent == root.getNodeId()) {
            ModuleDependency moduleDependency = dependency.getModuleDependency();
            builder.addFirstLevelDependency(moduleDependency, child);
        }
    }

    public void finish(DependencyGraphNode root) {
        attachFailures(builder);
        builder.done(root.getNodeId());
    }

    private void attachFailures(ResolvedConfigurationBuilder result) {
        for (Map.Entry<ModuleVersionSelector, BrokenDependency> entry : failuresByRevisionId.entrySet()) {
            Collection<List<ModuleVersionIdentifier>> paths = DependencyGraphPathResolver.calculatePaths(entry.getValue().requiredBy, root);
            result.addUnresolvedDependency(new DefaultUnresolvedDependency(entry.getKey(), entry.getValue().failure.withIncomingPaths(paths)));
        }
    }

    private void addUnresolvedDependency(DependencyGraphEdge dependency, ModuleVersionSelector requested, ModuleVersionResolveException failure) {
        BrokenDependency breakage = failuresByRevisionId.get(requested);
        if (breakage == null) {
            breakage = new BrokenDependency(failure);
            failuresByRevisionId.put(requested, breakage);
        }
        breakage.requiredBy.add(dependency.getFrom());
    }

    private static class BrokenDependency {
        final ModuleVersionResolveException failure;
        final List<DependencyGraphNode> requiredBy = new ArrayList<DependencyGraphNode>();

        private BrokenDependency(ModuleVersionResolveException failure) {
            this.failure = failure;
        }
    }

}
