/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.integtests.fixtures.publish

import org.gradle.integtests.fixtures.GradleMetadataResolveRunner
import org.gradle.test.fixtures.HttpModule
import org.gradle.test.fixtures.HttpRepository
import org.gradle.test.fixtures.Module
import org.gradle.test.fixtures.ivy.IvyModule
import org.gradle.test.fixtures.maven.MavenModule

class ModuleVersionSpec {
    private final String groupId
    private final String artifactId
    private final String version
    private final boolean mustPublish = !RemoteRepositorySpec.DEFINES_INTERACTIONS.get()

    private final List<Object> dependsOn = []
    private final List<Closure<?>> withModule = []
    private List<InteractionExpectation> expectGetMetadata = [InteractionExpectation.NONE]
    private List<ArtifactExpectation> expectGetArtifact = []

    static class ArtifactExpectation {
        final InteractionExpectation type
        final Object spec

        ArtifactExpectation(InteractionExpectation type, Object spec) {
            this.type = type
            this.spec = spec
        }
    }

    ModuleVersionSpec(String group, String module, String version) {
        this.groupId = group
        this.artifactId = module
        this.version = version
    }

    void expectResolve() {
        expectGetMetadata()
        expectGetArtifact()
    }

    void expectGetMetadata() {
        expectGetMetadata << InteractionExpectation.GET
    }

    void expectGetMetadataMissing() {
        expectGetMetadata << InteractionExpectation.GET_MISSING
    }

    void expectHeadMetadata() {
        expectGetMetadata << InteractionExpectation.HEAD
    }

    void expectGetArtifact(String artifact = '') {
        expectGetArtifact << new ArtifactExpectation(InteractionExpectation.GET, artifact)
    }

    void expectGetArtifact(Map<String, String> artifact) {
        expectGetArtifact << new ArtifactExpectation(InteractionExpectation.GET, artifact)
    }

    void expectHeadArtifact(String artifact = '') {
        expectGetArtifact << new ArtifactExpectation(InteractionExpectation.HEAD, artifact)
    }

    void expectHeadArtifact(Map<String, String> artifact) {
        expectGetArtifact << new ArtifactExpectation(InteractionExpectation.HEAD, artifact)
    }

    void maybeGetMetadata() {
        expectGetMetadata << InteractionExpectation.MAYBE
    }

    void dependsOn(coord) {
        dependsOn << coord
    }

    void withModule(@DelegatesTo(HttpModule) Closure<?> spec) {
        withModule << spec
    }

    public <T extends Module> void withModule(Class<T> moduleClass, @DelegatesTo(type = "T") Closure<?> spec) {
        withModule << { ->
            if (moduleClass.isAssignableFrom(delegate.class)) {
                spec.delegate = delegate
                spec()
            }
        }
    }

    void allowAll() {
        withModule {
            delegate.allowAll()
        }
    }

    void build(HttpRepository repository) {
        def module = repository.module(groupId, artifactId, version)
        def gradleMetadataEnabled = GradleMetadataResolveRunner.isGradleMetadataEnabled()
        if (gradleMetadataEnabled) {
            module.withModuleMetadata()
        }
        expectGetMetadata.each {
            switch (it) {
                case InteractionExpectation.NONE:
                    break;
                case InteractionExpectation.MAYBE:
                    if (module instanceof MavenModule) {
                        module.pom.allowGetOrHead()
                    } else if (module instanceof IvyModule) {
                        module.ivy.allowGetOrHead()
                    }
                    if (gradleMetadataEnabled) {
                        module.moduleMetadata.allowGetOrHead()
                    }
                    break
                case InteractionExpectation.HEAD:
                    if (module instanceof MavenModule) {
                        module.pom.expectHead()
                    } else if (module instanceof IvyModule) {
                        module.ivy.expectHead()
                    }
                    if (gradleMetadataEnabled) {
                        module.moduleMetadata.expectHead()
                    }
                    break
                case InteractionExpectation.GET_MISSING:
                    if (module instanceof MavenModule) {
                        module.pom.expectGetMissing()
                    } else if (module instanceof IvyModule) {
                        module.ivy.expectGetMissing()
                    }
                    if (gradleMetadataEnabled) {
                        module.moduleMetadata.expectGetMissing()
                    }
                    break
                default:
                    if (module instanceof MavenModule) {
                        module.pom.expectGet()
                    } else if (module instanceof IvyModule) {
                        module.ivy.expectGet()
                    }
                    if (gradleMetadataEnabled) {
                        module.moduleMetadata.expectGet()
                    }
            }
        }

        if (expectGetArtifact) {
            expectGetArtifact.each { ArtifactExpectation expectation ->
                def artifact
                if (expectation.spec) {
                    if (module instanceof MavenModule) {
                        artifact = module.getArtifact(expectation.spec)
                    } else if (module instanceof IvyModule) {
                        artifact = module.artifact(expectation.spec)
                    }
                } else {
                    artifact = module.artifact
                }
                switch (expectation.type) {
                    case InteractionExpectation.GET:
                        artifact.expectGet()
                        break
                    case InteractionExpectation.HEAD:
                        artifact.expectHead()
                        break
                    case InteractionExpectation.MAYBE:
                        artifact.allowGetOrHead()
                        break
                    case InteractionExpectation.NONE:
                        break
                }
            }
        }
        if (dependsOn) {
            dependsOn.each {
                if (it instanceof CharSequence) {
                    def args = it.split(':') as List
                    module.dependsOn(repository.module(*args))
                } else if (it instanceof Map) {
                    def other = repository.module(it.group, it.artifact, it.version)
                    module.dependsOn(it, other)
                } else {
                    module.dependsOn(it)
                }
            }
        }
        if (withModule) {
            withModule.each { spec ->
                spec.delegate = module
                spec()
            }
        }
        if (mustPublish) {
            // do not publish modules created during a `repositoryInteractions { ... }` block
            module.publish()
        }
    }

}
