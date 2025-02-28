/*
 * Copyright (c) 2021 PANTHEON.tech s.r.o. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */

package io.lighty.gnmi.southbound.schema.impl;

import com.google.common.collect.Sets;
import com.google.common.io.ByteSource;
import io.lighty.gnmi.southbound.capabilities.GnmiDeviceCapability;
import io.lighty.gnmi.southbound.schema.SchemaConstants;
import io.lighty.gnmi.southbound.schema.SchemaContextHolder;
import io.lighty.gnmi.southbound.schema.yangstore.service.YangDataStoreService;
import io.lighty.gnmi.southbound.timeout.TimeoutUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;
import org.opendaylight.yang.gen.v1.urn.lighty.gnmi.yang.storage.rev210331.gnmi.yang.models.GnmiYangModel;
import org.opendaylight.yangtools.yang.common.Revision;
import org.opendaylight.yangtools.yang.common.UnresolvedQName;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;
import org.opendaylight.yangtools.yang.model.api.ModuleImport;
import org.opendaylight.yangtools.yang.model.repo.api.SourceIdentifier;
import org.opendaylight.yangtools.yang.model.repo.api.YangTextSchemaSource;
import org.opendaylight.yangtools.yang.parser.api.YangSyntaxErrorException;
import org.opendaylight.yangtools.yang.parser.rfc7950.repo.YangModelDependencyInfo;
import org.opendaylight.yangtools.yang.parser.rfc7950.repo.YangStatementStreamSource;
import org.opendaylight.yangtools.yang.parser.spi.meta.ReactorException;
import org.opendaylight.yangtools.yang.parser.stmt.reactor.CrossSourceStatementReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SchemaContextHolderImpl implements SchemaContextHolder {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaContextHolderImpl.class);

    private final YangDataStoreService yangDataStoreService;
    private final Map<CapabilitiesKey, EffectiveModelContext> contextCache;
    private final CrossSourceStatementReactor yangReactor;

    public SchemaContextHolderImpl(final YangDataStoreService yangDataStoreService,
                                   final @Nullable CrossSourceStatementReactor reactor) {
        this.yangDataStoreService = yangDataStoreService;
        this.yangReactor = Objects.requireNonNullElse(reactor, SchemaConstants.DEFAULT_YANG_REACTOR);
        this.contextCache = new ConcurrentHashMap<>();
    }

    /**
     * Based on imports/includes statements of yang models reported by gNMI CapabilityResponse, tries to deduce and
     * read all necessary models so that EffectiveModelContext creation does not fail on missing module dependencies.
     * This step is necessary for cases when device reports non complete set of models, for example, module
     * in Capability response imports/includes another module which is not present in Capability response.
     *
     * @param baseCaps capabilities on which to perform the resolution
     * @return set containing all models for building EffectiveModelContext
     */
    private Set<GnmiYangModel> prepareModelsForSchema(
            final List<GnmiDeviceCapability> baseCaps) throws SchemaException {
        final Set<String> processedModuleNames = new HashSet<>();
        final SchemaException schemaException = new SchemaException();

        Set<GnmiYangModel> fullModelSet = new HashSet<>();
        try {
            // Read models reported in capabilities
            fullModelSet = readCapabilities(baseCaps, processedModuleNames, schemaException);
            // Get dependencies of models reported in capabilities
            Set<YangModelDependencyInfo> dependencyInfos = getDependenciesOfModels(fullModelSet, schemaException);

            boolean nonComplete = true;
            while (nonComplete) {
                // Read dependency models
                Set<GnmiYangModel> dependencyModels = new HashSet<>();
                for (YangModelDependencyInfo dependencyInfo : dependencyInfos) {
                    final Set<GnmiYangModel> gnmiYangModels =
                            readDependencyModels(dependencyInfo, processedModuleNames, schemaException);
                    dependencyModels.addAll(gnmiYangModels);
                }
                // See which models are new, if any, do it again
                final Sets.SetView<GnmiYangModel> newModels = Sets.difference(dependencyModels, fullModelSet);
                dependencyInfos = getDependenciesOfModels(newModels.immutableCopy(), schemaException);
                nonComplete = fullModelSet.addAll(newModels);
            }
        } catch (ExecutionException | TimeoutException e) {
            LOG.error("Error reading yang model from datastore", e);
            schemaException.addErrorMessage(e.getMessage());
        } catch (InterruptedException e) {
            LOG.error("Interrupted while reading model from datastore", e);
            Thread.currentThread().interrupt();
            schemaException.addErrorMessage(e.getMessage());
        }

        if (schemaException.getMissingModels().isEmpty() && schemaException.getErrorMessages().isEmpty()) {
            return fullModelSet;
        }
        throw schemaException;
    }

    private Set<GnmiYangModel> readCapabilities(final List<GnmiDeviceCapability> baseCaps,
                                                final Set<String> processedModuleNames,
                                                final SchemaException schemaException)
            throws InterruptedException, ExecutionException, TimeoutException {
        Set<GnmiYangModel> readModels = new HashSet<>();
        for (GnmiDeviceCapability capability : baseCaps) {
            if (!processedModuleNames.contains(capability.getName())) {
                final Optional<GnmiYangModel> readModel = tryToReadModel(capability);
                if (readModel.isPresent()) {
                    readModels.add(readModel.get());
                } else {
                    schemaException.addMissingModel(capability);
                }
                processedModuleNames.add(capability.getName());
            }
        }
        return readModels;
    }

    private Optional<GnmiYangModel> tryToReadModel(final GnmiDeviceCapability capability)
            throws InterruptedException, ExecutionException, TimeoutException {
        // Try to find the model stored with version
        Optional<GnmiYangModel> readImport;
        Optional<String> capabilityVersion = capability.getVersionString();
        if (capabilityVersion.isPresent()) {
            readImport = yangDataStoreService.readYangModel(capability.getName(), capabilityVersion.get())
                    .get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            if (readImport.isEmpty()) {
                LOG.warn("Requested gNMI (capability/dependency of capability) {} was not found with requested version"
                        + " {}.", capability.getName(), capabilityVersion.get());
                readImport = yangDataStoreService.readYangModel(capability.getName())
                        .get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                readImport.ifPresent(gnmiYangModel ->
                        LOG.warn("Model {} was found, but with version {}, since it is the only one"
                                        + " present, using it for schema.", capability.getName(),
                                gnmiYangModel.getVersion().getValue()));
            }
        } else {
            readImport = yangDataStoreService.readYangModel(capability.getName())
                    .get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }

        return readImport;
    }

    private Set<YangModelDependencyInfo> getDependenciesOfModels(final Set<GnmiYangModel> toCheck,
                                                                 final SchemaException schemaException) {
        Set<YangModelDependencyInfo> dependencies = new HashSet<>();
        for (GnmiYangModel model : toCheck) {
            try {
                final YangModelDependencyInfo dependencyInfo = YangModelDependencyInfo.forYangText(
                        makeTextSchemaSource(model));
                dependencies.add(dependencyInfo);
            } catch (IOException | YangSyntaxErrorException e) {
                schemaException.addErrorMessage(e.getMessage());
            }
        }
        return dependencies;
    }

    private Set<GnmiYangModel> readDependencyModels(final YangModelDependencyInfo dependencyInfo,
                                                    final Set<String> processedModuleNames,
                                                    final SchemaException schemaException)
            throws InterruptedException, ExecutionException, TimeoutException {
        Set<GnmiYangModel> models = new HashSet<>();
        for (ModuleImport moduleImport : dependencyInfo.getDependencies()) {
            if (!processedModuleNames.contains(moduleImport.getModuleName().getLocalName())) {
                final GnmiDeviceCapability importedCapability = new GnmiDeviceCapability(
                        moduleImport.getModuleName().getLocalName(), null,
                        moduleImport.getRevision().orElse(null));
                final Optional<GnmiYangModel> gnmiYangModel = tryToReadModel(importedCapability);
                if (gnmiYangModel.isPresent()) {
                    models.add(gnmiYangModel.get());
                } else {
                    schemaException.addMissingModel(importedCapability);
                }
                processedModuleNames.add(moduleImport.getModuleName().getLocalName());
            }
        }
        return models;
    }

    @Override
    public EffectiveModelContext getSchemaContext(final List<GnmiDeviceCapability> capabilities)
            throws SchemaException {
        final CapabilitiesKey key = new CapabilitiesKey(capabilities);
        if (contextCache.containsKey(key)) {
            LOG.info("Schema context for capabilities {} is already cached, reusing", capabilities);
            return contextCache.get(key);
        }
        // Compute schema and add to cache
        final CrossSourceStatementReactor.BuildAction buildAction = yangReactor.newBuild();
        final SchemaException schemaException = new SchemaException();
        boolean success = true;
        final Set<GnmiYangModel> completeCapabilities = prepareModelsForSchema(capabilities);
        for (GnmiYangModel model : completeCapabilities) {
            try {
                buildAction.addSource(YangStatementStreamSource.create(makeTextSchemaSource(model)));
            } catch (IOException | YangSyntaxErrorException e) {
                LOG.error("Adding YANG {} to reactor failed!", model, e);
                schemaException.addErrorMessage(e.getMessage());
                success = false;
            }
        }
        if (success) {
            try {
                final EffectiveModelContext context = buildAction.buildEffective();
                LOG.debug("Schema context created {}", context.getModules());
                contextCache.put(key, context);
                return context;
            } catch (ReactorException e) {
                LOG.error("Reactor failed processing schema context", e);
                schemaException.addErrorMessage(e.getMessage());
            }
        }
        throw schemaException;
    }

    private YangTextSchemaSource makeTextSchemaSource(final GnmiYangModel model) {
        if (model.getVersion().getValue().matches(SchemaConstants.REVISION_REGEX)) {
            return YangTextSchemaSource.delegateForByteSource(
                    new SourceIdentifier(UnresolvedQName.Unqualified.of(model.getName()),
                            Revision.of(model.getVersion().getValue())), bodyByteSource(model.getBody()));
        } else {
            return YangTextSchemaSource.delegateForByteSource(new SourceIdentifier(model.getName()),
                    bodyByteSource(model.getBody()));
        }

    }

    private ByteSource bodyByteSource(final String yangBody) {
        return new ByteSource() {
            @Override
            public InputStream openStream() {
                return new ByteArrayInputStream(yangBody.getBytes(StandardCharsets.UTF_8));
            }
        };
    }

}
