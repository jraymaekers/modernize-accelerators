/***************************************************************************
 *  Copyright 2019 ForgeRock AS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 ***************************************************************************/
package org.forgerock.openam.auth.node;

import static org.forgerock.openam.auth.node.api.SharedStateConstants.USERNAME;

import java.io.IOException;

import javax.inject.Inject;

import org.forgerock.http.handler.HttpClientHandler;
import org.forgerock.openam.auth.node.api.Action;
import org.forgerock.openam.auth.node.api.Node;
import org.forgerock.openam.auth.node.api.NodeProcessException;
import org.forgerock.openam.auth.node.api.TreeContext;
import org.forgerock.openam.auth.node.base.AbstractLegacyMigrationStatusNode;
import org.forgerock.openam.core.realms.Realm;
import org.forgerock.openam.secrets.Secrets;
import org.forgerock.openam.secrets.SecretsProviderFacade;
import org.forgerock.secrets.NoSuchSecretException;
import org.forgerock.secrets.Purpose;
import org.forgerock.util.promise.NeverThrowsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.assistedinject.Assisted;

/**
 * <p>
 * A node that validates if the user that is accessing the tree is already
 * migrated into ForgeRock IDM.
 * </p>
 */
@Node.Metadata(configClass = AbstractLegacyMigrationStatusNode.Config.class, outcomeProvider = AbstractLegacyMigrationStatusNode.OutcomeProvider.class)
public class LegacyFRMigrationStatus extends AbstractLegacyMigrationStatusNode {

	private Logger LOGGER = LoggerFactory.getLogger(LegacyFRMigrationStatus.class);
	private final AbstractLegacyMigrationStatusNode.Config config;
	private String idmPassword;
	private final HttpClientHandler httpClientHandler;

	/**
	 * Creates a LegacyFRMigrationStatus node with the provided configuration
	 * 
	 * @param config  the configuration for this Node.
	 * @param realm   the realm the node is accessed from.
	 * @param secrets the secret store used to get passwords
	 * @throws NodeProcessException If there is an error reading the configuration.
	 */
	@Inject
	public LegacyFRMigrationStatus(@Assisted LegacyFRMigrationStatus.Config config, @Assisted Realm realm,
			Secrets secrets, HttpClientHandler httpClientHandler) throws NodeProcessException {
		this.config = config;
		this.httpClientHandler = httpClientHandler;
		SecretsProviderFacade secretsProvider = secrets.getRealmSecrets(realm);
		if (secretsProvider != null) {
			try {
				this.idmPassword = secretsProvider.getNamedSecret(Purpose.PASSWORD, config.idmPasswordId())
						.getOrThrowUninterruptibly().revealAsUtf8(String::valueOf);
			} catch (NoSuchSecretException e) {
				throw new NodeProcessException("No secret " + config.idmPasswordId() + " found");
			}
		}
	}

	/**
	 * Main method called when the node is triggered.
	 */
	@Override
	public Action process(TreeContext context) throws NodeProcessException {
		LOGGER.debug("process()::Start");
		String username = context.sharedState.get(USERNAME).asString();
		LOGGER.debug("process()::Username: " + username);
		try {
			return goTo(getUserMigrationStatus(username, config.idmUserEndpoint(), config.idmAdminUser(), idmPassword,
					httpClientHandler)).build();
		} catch (NeverThrowsException | InterruptedException | IOException e) {
			throw new NodeProcessException("Error in LegacyFRMigrationStatus: " + e);
		}
	}

}