/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */
package org.jboss.as.server.deployment;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ARCHIVE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.BYTES;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CONTENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DEPLOYMENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ENABLED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FULL_REPLACE_DEPLOYMENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.HASH;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.INPUT_STREAM_INDEX;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PATH;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RELATIVE_TO;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RUNTIME_NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.URL;
import static org.jboss.as.controller.operations.validation.ChainedParameterValidator.chain;
import static org.jboss.as.server.ServerMessages.MESSAGES;
import static org.jboss.as.server.deployment.AbstractDeploymentHandler.CONTENT_ADDITION_PARAMETERS;
import static org.jboss.as.server.deployment.AbstractDeploymentHandler.asString;
import static org.jboss.as.server.deployment.AbstractDeploymentHandler.createFailureException;
import static org.jboss.as.server.deployment.AbstractDeploymentHandler.getInputStream;
import static org.jboss.as.server.deployment.AbstractDeploymentHandler.hasValidContentAdditionParameterDefined;
import static org.jboss.as.server.deployment.AbstractDeploymentHandler.validateOnePieceOfContent;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;

import org.jboss.as.controller.HashUtil;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationContext.ResultAction;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.descriptions.DescriptionProvider;
import org.jboss.as.controller.descriptions.common.DeploymentDescription;
import org.jboss.as.controller.operations.validation.AbstractParameterValidator;
import org.jboss.as.controller.operations.validation.ListValidator;
import org.jboss.as.controller.operations.validation.ModelTypeValidator;
import org.jboss.as.controller.operations.validation.ParametersOfValidator;
import org.jboss.as.controller.operations.validation.ParametersValidator;
import org.jboss.as.controller.operations.validation.StringLengthValidator;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.protocol.StreamUtils;
import org.jboss.as.repository.ContentRepository;
import org.jboss.as.repository.DeploymentFileRepository;
import org.jboss.as.server.ServerMessages;
import org.jboss.as.server.services.security.AbstractVaultReader;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

/**
 * Handles replacement in the runtime of one deployment by another.
 *
 * @author Brian Stansberry (c) 2011 Red Hat Inc.
 */
public class DeploymentFullReplaceHandler implements OperationStepHandler, DescriptionProvider {

    public static final String OPERATION_NAME = FULL_REPLACE_DEPLOYMENT;

    protected final ContentRepository contentRepository;

    private final ParametersValidator validator = new ParametersValidator();
    private final ParametersValidator unmanagedContentValidator = new ParametersValidator();
    private final ParametersValidator managedContentValidator = new ParametersValidator();

    private final AbstractVaultReader vaultReader;

    protected DeploymentFullReplaceHandler(final ContentRepository contentRepository, final AbstractVaultReader vaultReader) {
        assert contentRepository != null : "Null contentRepository";
        this.contentRepository = contentRepository;
        this.validator.registerValidator(NAME, new StringLengthValidator(1, Integer.MAX_VALUE, false, false));
        this.validator.registerValidator(RUNTIME_NAME, new StringLengthValidator(1, Integer.MAX_VALUE, true, false));
        // TODO: can we force enablement on replace?
        //this.validator.registerValidator(ENABLED, new ModelTypeValidator(ModelType.BOOLEAN, true));
        final ParametersValidator contentValidator = new ParametersValidator();
        // existing managed content
        contentValidator.registerValidator(HASH, new ModelTypeValidator(ModelType.BYTES, true));
        // existing unmanaged content
        contentValidator.registerValidator(ARCHIVE, new ModelTypeValidator(ModelType.BOOLEAN, true));
        contentValidator.registerValidator(PATH, new StringLengthValidator(1, true));
        contentValidator.registerValidator(RELATIVE_TO, new ModelTypeValidator(ModelType.STRING, true));
        // content additions
        contentValidator.registerValidator(INPUT_STREAM_INDEX, new ModelTypeValidator(ModelType.INT, true));
        contentValidator.registerValidator(BYTES, new ModelTypeValidator(ModelType.BYTES, true));
        contentValidator.registerValidator(URL, new StringLengthValidator(1, true));
        this.validator.registerValidator(CONTENT, chain(new ListValidator(new ParametersOfValidator(contentValidator)),
                new AbstractParameterValidator() {
                    @Override
                    public void validateParameter(String parameterName, ModelNode value) throws OperationFailedException {
                        validateOnePieceOfContent(value);
                    }
                }));
        this.managedContentValidator.registerValidator(HASH, new ModelTypeValidator(ModelType.BYTES));
        this.unmanagedContentValidator.registerValidator(ARCHIVE, new ModelTypeValidator(ModelType.BOOLEAN));
        this.unmanagedContentValidator.registerValidator(PATH, new StringLengthValidator(1));

        this.vaultReader = vaultReader;
    }

    public static DeploymentFullReplaceHandler createForStandalone(final ContentRepository  contentRepository, final AbstractVaultReader vaultReader) {
        return new DeploymentFullReplaceHandler(contentRepository, vaultReader);
    }

    public static DeploymentFullReplaceHandler createForDomainServer(final ContentRepository contentRepository, DeploymentFileRepository remoteFileRepository, final AbstractVaultReader vaultReader) {
        return new DomainServerDeploymentFullReplaceHandler(contentRepository, remoteFileRepository, vaultReader);
    }

    @Override
    public ModelNode getModelDescription(Locale locale) {
        return DeploymentDescription.getFullReplaceDeploymentOperation(locale);
    }

    public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {

        validator.validate(operation);

        final String name = operation.require(NAME).asString();
        final PathAddress address = PathAddress.EMPTY_ADDRESS.append(PathElement.pathElement(DEPLOYMENT, name));

        final Resource root = context.readResource(PathAddress.EMPTY_ADDRESS);
        boolean exists = root.hasChild(PathElement.pathElement(DEPLOYMENT, name));
        if (! exists) {
            ServerMessages.MESSAGES.noSuchDeployment(name);
        }

        final ModelNode replaceNode = context.readResourceForUpdate(address).getModel();
        final String replacedRuntimeName = replaceNode.require(RUNTIME_NAME).asString();
        final String runtimeName = operation.hasDefined(RUNTIME_NAME) ? operation.get(RUNTIME_NAME).asString() : replacedRuntimeName;

        // clone it, so we can modify it to our own content
        final ModelNode content = operation.require(CONTENT).clone();
        // TODO: JBAS-9020: for the moment overlays are not supported, so there is a single content item
        final DeploymentHandlerUtil.ContentItem contentItem;
        final ModelNode contentItemNode = content.require(0);
        if (contentItemNode.hasDefined(HASH)) {
            managedContentValidator.validate(contentItemNode);
            byte[] hash = contentItemNode.require(HASH).asBytes();

            contentItem = addFromHash(hash);
        } else if (hasValidContentAdditionParameterDefined(contentItemNode)) {
            contentItem = addFromContentAdditionParameter(context, contentItemNode);
        } else {
            contentItem = addUnmanaged(contentItemNode);
        }

        boolean start = replaceNode.get(ENABLED).asBoolean();

        byte[] originalHash = replaceNode.get(CONTENT).get(0).hasDefined(HASH) ? replaceNode.get(CONTENT).get(0).get(HASH).asBytes() : null;

        final ModelNode deployNode = context.readResourceForUpdate(address).getModel();
        deployNode.get(NAME).set(name);
        deployNode.get(RUNTIME_NAME).set(runtimeName);
        deployNode.get(CONTENT).set(content);
        deployNode.get(ENABLED).set(start);

        // the content repo will already have these, note that content should not be empty
        removeContentAdditions(deployNode.require(CONTENT));

        if (start) {
            DeploymentHandlerUtil.replace(context, replaceNode, runtimeName, name, replacedRuntimeName, vaultReader, contentItem);
        }

        if (context.completeStep() == ResultAction.KEEP) {
            if (originalHash != null) {
                if (replaceNode.get(CONTENT).get(0).hasDefined(HASH)) {
                    byte[] newHash = replaceNode.get(CONTENT).get(0).get(HASH).asBytes();
                    if (!Arrays.equals(originalHash, newHash)) {
                        contentRepository.removeContent(originalHash);
                    }
                }
            }
        } else {
            if (replaceNode.get(CONTENT).get(0).hasDefined(HASH)) {
                byte[] newHash = replaceNode.get(CONTENT).get(0).get(HASH).asBytes();
                contentRepository.removeContent(newHash);
            }
        }
    }

    private static void removeAttributes(final ModelNode node, final Iterable<String> attributeNames) {
        for (final String attributeName : attributeNames) {
            node.remove(attributeName);
        }
    }

    private static void removeContentAdditions(final ModelNode content) {
        for (final ModelNode contentItem : content.asList()) {
            removeAttributes(contentItem, CONTENT_ADDITION_PARAMETERS);
        }
    }

    DeploymentHandlerUtil.ContentItem addFromHash(byte[] hash) throws OperationFailedException {
        if (!contentRepository.hasContent(hash)) {
            throw ServerMessages.MESSAGES.noSuchDeploymentContent(HashUtil.bytesToHexString(hash));
        }
        return new DeploymentHandlerUtil.ContentItem(hash);
    }

    DeploymentHandlerUtil.ContentItem addFromContentAdditionParameter(OperationContext context, ModelNode contentItemNode) throws OperationFailedException {
        byte[] hash;
        InputStream in = getInputStream(context, contentItemNode);
        try {
            try {
                hash = contentRepository.addContent(in);
            } catch (IOException e) {
                throw createFailureException(e.toString());
            }

        } finally {
            StreamUtils.safeClose(in);
        }
        contentItemNode.clear(); // AS7-1029
        contentItemNode.get(HASH).set(hash);
        // TODO: remove the content addition stuff?
        return new DeploymentHandlerUtil.ContentItem(hash);
    }

    DeploymentHandlerUtil.ContentItem addUnmanaged(ModelNode contentItemNode) throws OperationFailedException {
        unmanagedContentValidator.validate(contentItemNode);
        final String path = contentItemNode.require(PATH).asString();
        final String relativeTo = asString(contentItemNode, RELATIVE_TO);
        final boolean archive = contentItemNode.require(ARCHIVE).asBoolean();
        return new DeploymentHandlerUtil.ContentItem(path, relativeTo, archive);
    }

    private static class DomainServerDeploymentFullReplaceHandler extends DeploymentFullReplaceHandler {
        final DeploymentFileRepository remoteFileRepository;

        DomainServerDeploymentFullReplaceHandler(ContentRepository contentRepository, DeploymentFileRepository remoteFileRepository, final AbstractVaultReader vaultReader) {
            super(contentRepository, vaultReader);
            assert remoteFileRepository != null : "Null remoteFileRepository";
            this.remoteFileRepository = remoteFileRepository;
        }

        @Override
        DeploymentHandlerUtil.ContentItem addFromHash(byte[] hash) throws OperationFailedException {
            remoteFileRepository.getDeploymentFiles(hash);
            return super.addFromHash(hash);
        }

        @Override
        DeploymentHandlerUtil.ContentItem addFromContentAdditionParameter(OperationContext context, ModelNode contentItemNode) throws OperationFailedException {
            throw MESSAGES.onlyHashAllowedForDeploymentFullReplaceInDomainServer(contentItemNode);
        }
    }
}
