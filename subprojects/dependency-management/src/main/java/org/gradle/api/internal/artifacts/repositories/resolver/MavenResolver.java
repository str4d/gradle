/*
 * Copyright 2012 the original author or authors.
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
package org.gradle.api.internal.artifacts.repositories.resolver;

import com.google.common.collect.ImmutableSet;
import org.gradle.api.artifacts.ComponentMetadataSupplier;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.internal.artifacts.ImmutableModuleIdentifierFactory;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ModuleComponentRepositoryAccess;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser.DescriptorParseContext;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser.MetaDataParser;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser.ModuleMetadataParser;
import org.gradle.api.internal.artifacts.repositories.ImmutableRepositoryContentFilter;
import org.gradle.api.internal.artifacts.repositories.transport.RepositoryTransport;
import org.gradle.api.internal.component.ArtifactType;
import org.gradle.api.resources.MissingResourceException;
import org.gradle.caching.internal.BuildCacheHasher;
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier;
import org.gradle.internal.component.external.model.DefaultMutableMavenModuleResolveMetadata;
import org.gradle.internal.component.external.model.FixedComponentArtifacts;
import org.gradle.internal.component.external.model.MavenModuleResolveMetadata;
import org.gradle.internal.component.external.model.MetadataSourcedComponentArtifacts;
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier;
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata;
import org.gradle.internal.component.external.model.MutableMavenModuleResolveMetadata;
import org.gradle.internal.component.model.ComponentArtifactMetadata;
import org.gradle.internal.component.model.ComponentOverrideMetadata;
import org.gradle.internal.component.model.DefaultIvyArtifactName;
import org.gradle.internal.component.model.IvyArtifactName;
import org.gradle.internal.component.model.ModuleSource;
import org.gradle.internal.resolve.result.BuildableArtifactSetResolveResult;
import org.gradle.internal.resolve.result.BuildableComponentArtifactsResolveResult;
import org.gradle.internal.resolve.result.BuildableModuleComponentMetaDataResolveResult;
import org.gradle.internal.resolve.result.DefaultResourceAwareResolveResult;
import org.gradle.internal.resolve.result.ResourceAwareResolveResult;
import org.gradle.internal.resource.ExternalResourceName;
import org.gradle.internal.resource.local.FileResourceRepository;
import org.gradle.internal.resource.local.FileStore;
import org.gradle.internal.resource.local.LocallyAvailableExternalResource;
import org.gradle.internal.resource.local.LocallyAvailableResourceFinder;
import org.gradle.internal.resource.transfer.CacheAwareExternalResourceAccessor;

import javax.annotation.Nullable;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MavenResolver extends GradleMetadataAwareExternalResourceResolver<MavenModuleResolveMetadata, MutableMavenModuleResolveMetadata> {
    private final URI root;
    private final List<URI> artifactRoots = new ArrayList<URI>();
    private final MavenMetadataLoader mavenMetaDataLoader;
    private final MetaDataParser<MutableMavenModuleResolveMetadata> pomParser;
    private final ImmutableModuleIdentifierFactory moduleIdentifierFactory;

    private static final Pattern UNIQUE_SNAPSHOT = Pattern.compile("(?:.+)-(\\d{8}\\.\\d{6}-\\d+)");
    private final MavenLocalRepositoryAccess localAccess = new MavenLocalRepositoryAccess();
    private final MavenRemoteRepositoryAccess remoteAccess = new MavenRemoteRepositoryAccess();

    public MavenResolver(String name, URI rootUri, RepositoryTransport transport,
                         LocallyAvailableResourceFinder<ModuleComponentArtifactMetadata> locallyAvailableResourceFinder,
                         FileStore<ModuleComponentArtifactIdentifier> artifactFileStore,
                         MetaDataParser<MutableMavenModuleResolveMetadata> pomParser,
                         ModuleMetadataParser metadataParser,
                         ImmutableModuleIdentifierFactory moduleIdentifierFactory,
                         CacheAwareExternalResourceAccessor cacheAwareExternalResourceAccessor,
                         FileStore<String> resourcesFileStore,
                         FileResourceRepository fileResourceRepository,
                         boolean preferGradleMetadata,
                         ImmutableRepositoryContentFilter repositoryContentFilter) {
        super(name, transport.isLocal(),
            transport.getRepository(),
            transport.getResourceAccessor(),
            new ChainedVersionLister(new MavenVersionLister(cacheAwareExternalResourceAccessor, resourcesFileStore), new ResourceVersionLister(transport.getRepository())),
            locallyAvailableResourceFinder,
            artifactFileStore,
            moduleIdentifierFactory,
            fileResourceRepository,
            preferGradleMetadata,
            metadataParser,
            repositoryContentFilter);
        this.pomParser = pomParser;
        this.mavenMetaDataLoader = new MavenMetadataLoader(cacheAwareExternalResourceAccessor, resourcesFileStore);
        this.root = rootUri;
        this.moduleIdentifierFactory = moduleIdentifierFactory;
        updatePatterns();
    }

    @Override
    public String toString() {
        return "Maven repository '" + getName() + "'";
    }

    @Override
    protected void appendId(BuildCacheHasher hasher) {
        super.appendId(hasher);
        hasher.putBoolean(isUseGradleMetadata());
    }

    @Override
    protected Class<MavenModuleResolveMetadata> getSupportedMetadataType() {
        return MavenModuleResolveMetadata.class;
    }

    public URI getRoot() {
        return root;
    }

    protected void doResolveComponentMetaData(ModuleComponentIdentifier moduleComponentIdentifier, ComponentOverrideMetadata prescribedMetaData, BuildableModuleComponentMetaDataResolveResult result) {
        if (isNonUniqueSnapshot(moduleComponentIdentifier)) {
            MavenUniqueSnapshotModuleSource uniqueSnapshotVersion = findUniqueSnapshotVersion(moduleComponentIdentifier, result);
            if (uniqueSnapshotVersion != null) {
                MavenUniqueSnapshotComponentIdentifier snapshotIdentifier = composeSnapshotIdentifier(moduleComponentIdentifier, uniqueSnapshotVersion);
                resolveUniqueSnapshotDependency(snapshotIdentifier, prescribedMetaData, result, uniqueSnapshotVersion);
                return;
            }
        } else {
            MavenUniqueSnapshotModuleSource uniqueSnapshotVersion = composeUniqueSnapshotVersion(moduleComponentIdentifier);
            if (uniqueSnapshotVersion != null) {
                MavenUniqueSnapshotComponentIdentifier snapshotIdentifier = composeSnapshotIdentifier(moduleComponentIdentifier, uniqueSnapshotVersion);
                resolveUniqueSnapshotDependency(snapshotIdentifier, prescribedMetaData, result, uniqueSnapshotVersion);
                return;
            }
        }

        resolveStaticDependency(moduleComponentIdentifier, prescribedMetaData, result, super.createArtifactResolver());
    }

    protected boolean isMetaDataArtifact(ArtifactType artifactType) {
        return artifactType == ArtifactType.MAVEN_POM;
    }

    private MutableMavenModuleResolveMetadata processMetaData(MutableMavenModuleResolveMetadata metaData) {
        if (isNonUniqueSnapshot(metaData.getComponentId())) {
            metaData.setChanging(true);
        }
        return metaData;
    }

    private void resolveUniqueSnapshotDependency(MavenUniqueSnapshotComponentIdentifier module, ComponentOverrideMetadata prescribedMetaData, BuildableModuleComponentMetaDataResolveResult result, MavenUniqueSnapshotModuleSource snapshotSource) {
        resolveStaticDependency(module, prescribedMetaData, result, createArtifactResolver(snapshotSource));
    }

    @Override
    MutableMavenModuleResolveMetadata metadata(ModuleVersionIdentifier id, ModuleComponentIdentifier cid) {
        return new DefaultMutableMavenModuleResolveMetadata(id, cid);
    }

    @Override
    protected ExternalResourceArtifactResolver createArtifactResolver(ModuleSource moduleSource) {

        if (moduleSource instanceof MavenUniqueSnapshotModuleSource) {
            return new MavenUniqueSnapshotExternalResourceArtifactResolver(super.createArtifactResolver(moduleSource), (MavenUniqueSnapshotModuleSource) moduleSource);
        }

        return super.createArtifactResolver(moduleSource);
    }

    public void addArtifactLocation(URI baseUri) {
        artifactRoots.add(baseUri);
        updatePatterns();
    }

    private M2ResourcePattern getWholePattern() {
        return new M2ResourcePattern(root, MavenPattern.M2_PATTERN);
    }

    private void updatePatterns() {
        setIvyPatterns(Collections.singletonList(getWholePattern()));

        List<ResourcePattern> artifactPatterns = new ArrayList<ResourcePattern>();
        artifactPatterns.add(getWholePattern());
        for (URI artifactRoot : artifactRoots) {
            artifactPatterns.add(new M2ResourcePattern(artifactRoot, MavenPattern.M2_PATTERN));
        }
        setArtifactPatterns(artifactPatterns);
    }

    @Override
    protected IvyArtifactName getMetaDataArtifactName(String moduleName) {
        return new DefaultIvyArtifactName(moduleName, "pom", "pom");
    }

    private MavenUniqueSnapshotModuleSource findUniqueSnapshotVersion(ModuleComponentIdentifier module, ResourceAwareResolveResult result) {
        M2ResourcePattern wholePattern = getWholePattern();
        if (!wholePattern.isComplete(module)) {
            //do not attempt to download maven-metadata.xml for incomplete identifiers
            return null;
        }
        ExternalResourceName metadataLocation = wholePattern.toModuleVersionPath(module).resolve("maven-metadata.xml");
        result.attempted(metadataLocation);
        MavenMetadata mavenMetadata = parseMavenMetadata(metadataLocation);

        if (mavenMetadata.timestamp != null) {
            // we have found a timestamp, so this is a snapshot unique version
            String timestamp = mavenMetadata.timestamp + "-" + mavenMetadata.buildNumber;
            return new MavenUniqueSnapshotModuleSource(timestamp);
        }
        return null;
    }

    @Nullable
    private MavenUniqueSnapshotModuleSource composeUniqueSnapshotVersion(ModuleComponentIdentifier moduleComponentIdentifier) {
        Matcher matcher = UNIQUE_SNAPSHOT.matcher(moduleComponentIdentifier.getVersion());
        if (!matcher.matches()) {
            return null;
        }
        return new MavenUniqueSnapshotModuleSource(matcher.group(1));
    }

    private MavenMetadata parseMavenMetadata(ExternalResourceName metadataLocation) {
        try {
            return mavenMetaDataLoader.load(metadataLocation);
        } catch (MissingResourceException e) {
            return new MavenMetadata();
        }
    }

    public ModuleComponentRepositoryAccess getLocalAccess() {
        return localAccess;
    }

    public ModuleComponentRepositoryAccess getRemoteAccess() {
        return remoteAccess;
    }

    public ComponentMetadataSupplier createMetadataSupplier() {
        return null;
    }

    @Override
    protected MutableMavenModuleResolveMetadata createMissingComponentMetadata(ModuleComponentIdentifier moduleComponentIdentifier) {
        ModuleVersionIdentifier mvi = moduleIdentifierFactory.moduleWithVersion(moduleComponentIdentifier.getGroup(), moduleComponentIdentifier.getModule(), moduleComponentIdentifier.getVersion());
        return processMetaData(DefaultMutableMavenModuleResolveMetadata.missing(mvi, moduleComponentIdentifier));
    }

    protected MutableMavenModuleResolveMetadata parseMetaDataFromResource(ModuleComponentIdentifier moduleComponentIdentifier, LocallyAvailableExternalResource cachedResource, DescriptorParseContext context) {
        MutableMavenModuleResolveMetadata metaData = pomParser.parseMetaData(context, cachedResource);
        if (moduleComponentIdentifier instanceof MavenUniqueSnapshotComponentIdentifier) {
            // Snapshot POMs use -SNAPSHOT instead of the timestamp as version, so validate against the expected id
            MavenUniqueSnapshotComponentIdentifier snapshotComponentIdentifier = (MavenUniqueSnapshotComponentIdentifier) moduleComponentIdentifier;
            checkMetadataConsistency(snapshotComponentIdentifier.getSnapshotComponent(), metaData);
            // Use the requested id. Currently we're discarding the MavenUniqueSnapshotComponentIdentifier and replacing with DefaultModuleComponentIdentifier as pretty
            // much every consumer of the meta-data is expecting a DefaultModuleComponentIdentifier.
            ModuleComponentIdentifier lossyId = DefaultModuleComponentIdentifier.newId(moduleComponentIdentifier.getGroup(), moduleComponentIdentifier.getModule(), moduleComponentIdentifier.getVersion());
            metaData.setComponentId(lossyId);
            metaData.setSnapshotTimestamp(snapshotComponentIdentifier.getTimestamp());
        } else {
            checkMetadataConsistency(moduleComponentIdentifier, metaData);
        }
        return processMetaData(metaData);
    }

    private class MavenLocalRepositoryAccess extends LocalRepositoryAccess {
        @Override
        protected void resolveModuleArtifacts(MavenModuleResolveMetadata module, BuildableComponentArtifactsResolveResult result) {
            if (!module.getVariants().isEmpty()) {
                result.resolved(new MetadataSourcedComponentArtifacts());
            } else if (module.isKnownJarPackaging()) {
                ModuleComponentArtifactMetadata artifact = module.artifact("jar", "jar", null);
                result.resolved(new FixedComponentArtifacts(ImmutableSet.of(artifact)));
            } else if (module.isRelocated()) {
                result.resolved(new FixedComponentArtifacts(Collections.<ComponentArtifactMetadata>emptyList()));
            }
        }

        @Override
        protected void resolveJavadocArtifacts(MavenModuleResolveMetadata module, BuildableArtifactSetResolveResult result) {
            // Javadoc artifacts are optional, so we need to probe for them remotely
        }

        @Override
        protected void resolveSourceArtifacts(MavenModuleResolveMetadata module, BuildableArtifactSetResolveResult result) {
            // Source artifacts are optional, so we need to probe for them remotely
        }
    }

    private class MavenRemoteRepositoryAccess extends RemoteRepositoryAccess {
        @Override
        protected void resolveModuleArtifacts(MavenModuleResolveMetadata module, BuildableComponentArtifactsResolveResult result) {
            if (module.isPomPackaging()) {
                result.resolved(new FixedComponentArtifacts(findOptionalArtifacts(module, "jar", null)));
            } else {
                ModuleComponentArtifactMetadata artifactMetaData = module.artifact(module.getPackaging(), module.getPackaging(), null);

                if (createArtifactResolver(module.getSource()).artifactExists(artifactMetaData, new DefaultResourceAwareResolveResult())) {
                    result.resolved(new FixedComponentArtifacts(ImmutableSet.of(artifactMetaData)));
                } else {
                    ModuleComponentArtifactMetadata artifact = module.artifact("jar", "jar", null);
                    result.resolved(new FixedComponentArtifacts(ImmutableSet.of(artifact)));
                }
            }
        }

        @Override
        protected void resolveJavadocArtifacts(MavenModuleResolveMetadata module, BuildableArtifactSetResolveResult result) {
            result.resolved(findOptionalArtifacts(module, "javadoc", "javadoc"));
        }

        @Override
        protected void resolveSourceArtifacts(MavenModuleResolveMetadata module, BuildableArtifactSetResolveResult result) {
            result.resolved(findOptionalArtifacts(module, "source", "sources"));
        }
    }

    private boolean isNonUniqueSnapshot(ModuleComponentIdentifier moduleComponentIdentifier) {
        return moduleComponentIdentifier.getVersion().endsWith("-SNAPSHOT");
    }

    private MavenUniqueSnapshotComponentIdentifier composeSnapshotIdentifier(ModuleComponentIdentifier moduleComponentIdentifier, MavenUniqueSnapshotModuleSource uniqueSnapshotVersion) {
        return new MavenUniqueSnapshotComponentIdentifier(moduleComponentIdentifier.getGroup(),
            moduleComponentIdentifier.getModule(),
            moduleComponentIdentifier.getVersion(),
            uniqueSnapshotVersion.getTimestamp());
    }
}
