/***************************************************************************
 *  Copyright 2020 ForgeRock AS
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

import static org.forgerock.openam.auth.node.api.SharedStateConstants.PASSWORD;
import static org.forgerock.openam.auth.node.api.SharedStateConstants.USERNAME;
import static org.forgerock.openam.modernize.utils.NodeConstants.ACCOUNTING_PORT;
import static org.forgerock.openam.modernize.utils.NodeConstants.AUTHENTICATION_PORT;
import static org.forgerock.openam.modernize.utils.NodeConstants.AUTHORIZATION_PORT;
import static org.forgerock.openam.modernize.utils.NodeConstants.CONNECTION_MAX;
import static org.forgerock.openam.modernize.utils.NodeConstants.CONNECTION_MIN;
import static org.forgerock.openam.modernize.utils.NodeConstants.CONNECTION_STEP;
import static org.forgerock.openam.modernize.utils.NodeConstants.CONNECTION_TIMEOUT;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import javax.inject.Inject;

import org.forgerock.http.HttpApplicationException;
import org.forgerock.http.handler.HttpClientHandler;
import org.forgerock.openam.annotations.sm.Attribute;
import org.forgerock.openam.auth.node.api.Action;
import org.forgerock.openam.auth.node.api.Node;
import org.forgerock.openam.auth.node.api.NodeProcessException;
import org.forgerock.openam.auth.node.api.TreeContext;
import org.forgerock.openam.auth.node.base.AbstractLegacyCreateForgeRockUserNode;
import org.forgerock.openam.core.realms.Realm;
import org.forgerock.openam.modernize.legacy.SmSdkUtils;
import org.forgerock.openam.secrets.Secrets;
import org.forgerock.openam.secrets.SecretsProviderFacade;
import org.forgerock.secrets.NoSuchSecretException;
import org.forgerock.secrets.Purpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.assistedinject.Assisted;
import com.netegrity.sdk.apiutil.SmApiConnection;
import com.netegrity.sdk.apiutil.SmApiException;
import com.netegrity.sdk.apiutil.SmApiResult;
import com.netegrity.sdk.apiutil.SmApiSession;
import com.netegrity.sdk.dmsapi.SmDmsApi;
import com.netegrity.sdk.dmsapi.SmDmsApiImpl;
import com.netegrity.sdk.dmsapi.SmDmsConfig;
import com.netegrity.sdk.dmsapi.SmDmsDirectory;
import com.netegrity.sdk.dmsapi.SmDmsDirectoryContext;
import com.netegrity.sdk.dmsapi.SmDmsObject;
import com.netegrity.sdk.dmsapi.SmDmsOrganization;
import com.netegrity.sdk.dmsapi.SmDmsSearch;
import com.netegrity.sdk.policyapi.SmPolicyApi;
import com.netegrity.sdk.policyapi.SmPolicyApiImpl;
import com.netegrity.sdk.policyapi.SmUserDirectory;
import com.sun.identity.sm.RequiredValueValidator;

import netegrity.siteminder.javaagent.AgentAPI;
import netegrity.siteminder.javaagent.InitDef;
import netegrity.siteminder.javaagent.ServerDef;

/**
 * <p>
 * A node which creates a user in ForgeRock IDM by calling the user endpoint
 * with the action query parameter set to create:
 * <b><i>{@code ?_action=create}</i></b>.
 * </p>
 */
@Node.Metadata(configClass = LegacySMCreateForgeRockUser.LegacyFRConfig.class, outcomeProvider = AbstractLegacyCreateForgeRockUserNode.OutcomeProvider.class)
public class LegacySMCreateForgeRockUser extends AbstractLegacyCreateForgeRockUserNode {

	private static final Logger LOGGER = LoggerFactory.getLogger(LegacySMCreateForgeRockUser.class);
	private final LegacyFRConfig config;
	private final HttpClientHandler httpClientHandler;

	private String idmPassword;
	private String webAgentSecret;
	private String smAdminPassword;

	/**
	 * Node configuration
	 */
	public interface LegacyFRConfig extends AbstractLegacyCreateForgeRockUserNode.Config {

		/**
		 * Siteminder Policy Server IP address.
		 * 
		 * @return the configured policyServerIP
		 */
		@Attribute(order = 10, validators = { RequiredValueValidator.class })
		String policyServerIP();

		/**
		 * Siteminder Policy Server Accounting server port (0 for none). Mandatory if
		 * "Is 4x Web agent" config is activated.
		 * 
		 * @return the configured accountingPort
		 */
		@Attribute(order = 20)
		default int accountingPort() {
			return ACCOUNTING_PORT;
		}

		/**
		 * Siteminder Policy Server Authentication server port (0 for none). Mandatory
		 * if "Is 4x Web agent" config is activated.
		 * 
		 * @return the configured authenticationPort
		 */
		@Attribute(order = 30)
		default int authenticationPort() {
			return AUTHENTICATION_PORT;
		}

		/**
		 * Siteminder Policy Server Authorization server port (0 for none). Mandatory if
		 * "Is 4x Web agent" config is activated.
		 * 
		 * @return the configured authorizationPort
		 */
		@Attribute(order = 40)
		default int authorizationPort() {
			return AUTHORIZATION_PORT;
		}

		/**
		 * Number of initial connections. Mandatory if "Is 4x Web agent" config is
		 * activated.
		 * 
		 * @return the configured connectionMin value
		 */
		@Attribute(order = 50)
		default int connectionMin() {
			return CONNECTION_MIN;
		}

		/**
		 * Maximum number of connections. Mandatory if "Is 4x Web agent" config is
		 * activated.
		 * 
		 * @return the configured connectionMax value
		 */
		@Attribute(order = 60)
		default int connectionMax() {
			return CONNECTION_MAX;
		}

		/**
		 * Number of connections to allocate when out of connections. Mandatory if "Is
		 * 4x Web agent" config is activated.
		 * 
		 * @return the configured connectionStep value
		 */
		@Attribute(order = 70)
		default int connectionStep() {
			return CONNECTION_STEP;
		}

		/**
		 * Connection timeout in seconds. Mandatory if "Is 4x Web agent" config is
		 * activated.
		 * 
		 * @return the configured timeoutin seconds
		 */
		@Attribute(order = 80)
		default int timeout() {
			return CONNECTION_TIMEOUT;
		}

		/**
		 * The agent name. This name must match the agent name provided to the Policy
		 * Server. The agent name is not case sensitive.
		 * 
		 * @return the configured webAgentName
		 */
		@Attribute(order = 90, validators = { RequiredValueValidator.class })
		String webAgentName();

		/**
		 * The secret id of the AM secret that contains the web agent shared secret as
		 * defined in the SiteMinder user interface (case sensitive).
		 * 
		 * @return the configured webAgentSecretId
		 */
		@Attribute(order = 100)
		String webAgentPasswordSecretId();

		/**
		 * The version of web agent used by the siteminder policy server. Should be True
		 * if the "Is 4x" check box is active on the Siteminder Web Agent.
		 * 
		 * @return true if siteminder web agent version is 4x, false otherwise
		 */
		@Attribute(order = 110, validators = { RequiredValueValidator.class })
		default boolean is4xAgent() {
			return true;
		}

		/**
		 * Location on the AM instance, where the Siteminder web agent SmHost.conf file
		 * is located. Mandatory if "Is 4x Web agent" configuration is set to false
		 * (disabled).
		 * 
		 * @return configured smHostFilePath
		 */
		@Attribute(order = 120)
		String smHostFilePath();

		/**
		 * A debug switch used to activate additional debug information.
		 * 
		 * @return configured debug value
		 */
		@Attribute(order = 130, validators = { RequiredValueValidator.class })
		default boolean debug() {
			return false;
		}

		/**
		 * A map which should hold as keys the name of the SiteMinder user attributes,
		 * and as values their equivalent name in the ForgeRock IDM database.
		 * 
		 * @return the configured attributes map
		 */
		@Attribute(order = 140, validators = { RequiredValueValidator.class })
		Map<String, String> migrationAttributesMap();

		/**
		 * Distinguished name of the siteminder administrator
		 * 
		 * @return the configured smAdminUser
		 */
		@Attribute(order = 150, validators = { RequiredValueValidator.class })
		String smAdminUser();

		/**
		 * Password of the sitemidner DMS administrator logging in
		 * 
		 * @return the configured smAdminPassword
		 */
		@Attribute(order = 160, validators = { RequiredValueValidator.class })
		String smAdminPasswordSecretId();

		/**
		 * Name of the siteminder user directory
		 * 
		 * @return the configured user directory
		 */
		@Attribute(order = 170, validators = { RequiredValueValidator.class })
		String smUserDirectory();

		/**
		 * The user directory root search base. For example, "dc=mycompany,dc=com"
		 * 
		 * @return the configured smDirectoryRoot
		 */
		@Attribute(order = 180, validators = { RequiredValueValidator.class })
		String smDirectoryRoot();

		/**
		 * The username attribute used to search for a user, given it's username. For
		 * example, "samaccountname"
		 * 
		 * @return the configured smUserSearchAttr
		 */
		@Attribute(order = 190, validators = { RequiredValueValidator.class })
		String smUserSearchAttr();

		/**
		 * The object class used to define the users -- for example, "user"
		 * 
		 * @return the configured smUserSearchClass
		 */
		@Attribute(order = 200, validators = { RequiredValueValidator.class })
		String smUserSearchClass();

	}

	/**
	 * Creates a LegacySMCreateForgeRockUser node with the provided configuration
	 * 
	 * @param config  the configuration for this Node.
	 * @param realm   the realm the node is accessed from.
	 * @param secrets the secret store used to get passwords
	 * @throws NodeProcessException     If there is an error reading the
	 *                                  configuration.
	 * @throws HttpApplicationException
	 */
	@Inject
	public LegacySMCreateForgeRockUser(@Assisted LegacyFRConfig config, @Assisted Realm realm, Secrets secrets,
			HttpClientHandler httpClientHandler) throws NodeProcessException {
		this.config = config;
		this.httpClientHandler = httpClientHandler;
		SecretsProviderFacade secretsProvider = secrets.getRealmSecrets(realm);
		if (secretsProvider != null) {
			try {
				this.idmPassword = secretsProvider.getNamedSecret(Purpose.PASSWORD, config.idmPassworSecretdId())
						.getOrThrowUninterruptibly().revealAsUtf8(String::valueOf).trim();
				// non 4x web agent takes the secret from SmHost.conf file
				if (config.is4xAgent()) {
					this.webAgentSecret = secretsProvider
							.getNamedSecret(Purpose.PASSWORD, config.webAgentPasswordSecretId())
							.getOrThrowUninterruptibly().revealAsUtf8(String::valueOf).trim();
				}
				this.smAdminPassword = secretsProvider
						.getNamedSecret(Purpose.PASSWORD, config.smAdminPasswordSecretId()).getOrThrowUninterruptibly()
						.revealAsUtf8(String::valueOf).trim();
			} catch (NoSuchSecretException e) {
				throw new NodeProcessException(
						"Check secret configurations for secret id's: " + config.idmPassworSecretdId() + ", "
								+ config.webAgentPasswordSecretId() + "," + config.smAdminPasswordSecretId());
			}
		}
	}

	/**
	 * Main method called when the node is triggered.
	 */
	@Override
	public Action process(TreeContext context) throws NodeProcessException {
		LOGGER.debug("LegacySMCreateForgeRockUser > Start");

		if (!SmSdkUtils.isNodeConfigurationValid(config.is4xAgent(), config.smHostFilePath(), config.accountingPort(),
				config.authenticationPort(), config.authorizationPort(), config.connectionMin(), config.connectionMax(),
				config.connectionStep(), config.timeout(), config.webAgentPasswordSecretId())) {
			throw new NodeProcessException(
					"LegacySMCreateForgeRockUser::process: Configuration is not valid for the selected agent type");
		}

		String userName = context.sharedState.get(USERNAME).asString();
		String password = "";
		if (context.transientState.get(PASSWORD) != null) {
			password = context.transientState.get(PASSWORD).asString();
		}
		Map<String, String> userAttributes = new HashMap<>();
		try {
			userAttributes = getUserAttributes(userName);
			if (userAttributes != null) {
				if (password != null && password.length() > 0) {
					userAttributes.put(PASSWORD, password);
				}
				return goTo(provisionUser(userAttributes, idmPassword, config.idmUserEndpoint(), config.idmAdminUser(),
						config.setPasswordReset(), httpClientHandler)).build();
			}
		} catch (SmApiException e) {
			throw new NodeProcessException("LegacySMCreateForgeRockUser::SmApiException: " + e);
		}

		return goTo(false).build();
	}

	/**
	 * 
	 * Gets a user's attributes from the Siteminder directory.
	 * 
	 * @param userName the user retrieved from the shared state
	 * @param password the password retrieved from the shared state
	 * @return a map of user attributes, in the format expected by ForgeRock IDM
	 * @throws SmApiException
	 */
	@SuppressWarnings("rawtypes")
	private Map<String, String> getUserAttributes(String userName) throws SmApiException {
		// Initialize AgentAPI
		AgentAPI agentapi = new AgentAPI();
		ServerDef serverDefinition = null;
		InitDef initDefinition = new InitDef();

		// Create SM server and init definitions
		if (config.is4xAgent()) {
			LOGGER.info(
					"LegacySMCreateForgeRockUser::getUserAttributes() > Configuring AgentAPI for using a 4.x web agent.");
			serverDefinition = SmSdkUtils.createServerDefinition(config.policyServerIP(), config.connectionMin(),
					config.connectionMax(), config.connectionStep(), config.timeout(), config.authorizationPort(),
					config.authenticationPort(), config.accountingPort());
			initDefinition = SmSdkUtils.createInitDefinition(config.webAgentName(), webAgentSecret, false,
					serverDefinition);
		} else {
			LOGGER.info(
					"LegacySMCreateForgeRockUser::getUserAttributes() > Configuring AgentAPI for using a > 4.x web agent.");
			int configStatus = agentapi.getConfig(initDefinition, config.webAgentName(), config.smHostFilePath());
			LOGGER.info("LegacySMCreateForgeRockUser::getUserAttributes() > getConfig returned status: {}",
					configStatus);
		}

		int retcode = agentapi.init(initDefinition);

		if (retcode != AgentAPI.SUCCESS) {
			LOGGER.error("LegacySMCreateForgeRockUser::getUserAttributes() > AgentAPI init failed with return code: {}",
					retcode);
			return null;
		} else {
			if (config.debug()) {
				LOGGER.info("LegacySMCreateForgeRockUser::getUserAttributes() > AgentAPI init SUCCESS.");
			}
		}

		// Connection to the policy server
		SmApiConnection apiConnection = new SmApiConnection(agentapi);
		SmApiSession apiSession = new SmApiSession(apiConnection);
		boolean loginResult = SmSdkUtils.adminLogin(apiSession, config.smAdminUser(), smAdminPassword.toCharArray());
		LOGGER.info("LegacySMCreateForgeRockUser::getUserAttributes() > adminLogin result: {}", loginResult);

		// Get a list of user directories the admin can manage.
		SmPolicyApi policyApi = new SmPolicyApiImpl(apiSession);
		Vector userDirs = new Vector();

		// Returns the list of directory names.
		SmApiResult result = policyApi.getAdminUserDirs(config.smAdminUser(), userDirs);
		if (config.debug()) {
			SmSdkUtils.printObject(userDirs, result);
		}

		// Check if the USER_DIR can be found in the list and if found assign it here
		SmUserDirectory userDir = null;
		for (int i = 0; i < userDirs.size(); ++i) {
			String dir = (String) userDirs.get(i);
			if (dir.equals(config.smUserDirectory())) {
				userDir = new SmUserDirectory(config.smUserDirectory());
				result = policyApi.getUserDirectory(config.smUserDirectory(), userDir);
				if (config.debug()) {
					SmSdkUtils.printObject(userDir, result);
				}
			}
		}

		SmDmsApi dmsApi = new SmDmsApiImpl(apiSession);
		SmDmsDirectoryContext dirContext = new SmDmsDirectoryContext();
		result = dmsApi.getDirectoryContext(userDir, new SmDmsConfig(), dirContext);

		if (!result.isSuccess()) {
			LOGGER.error("LegacySMCreateForgeRockUser::getUserAttributes() > getDirectoryContext STATUS_NOK");
			agentapi.unInit();
			return null;
		} else {
			LOGGER.info("LegacySMCreateForgeRockUser::getUserAttributes() > getDirectoryContext STATUS_OK");
		}

		SmDmsDirectory dmsDirectory = dirContext.getDmsDirectory();
		SmDmsOrganization dmsOrg = dmsDirectory.newOrganization(config.smDirectoryRoot());
		String dmsSearch = "(&(objectclass=" + config.smUserSearchClass() + ") (" + config.smUserSearchAttr() + "="
				+ userName + "))";

		SmDmsSearch search = new SmDmsSearch(dmsSearch, config.smDirectoryRoot());

		// Define search parameters - no need to have them configurable since we always
		// look for a single result
		search.setScope(2);// Number of levels to search.
		search.setNextItem(0);// Initialize forward search start
		search.setMaxItems(1);// Max number of items to display
		search.setPreviousItem(0);// Initialize back search start
		search.setMaxResults(1);// Max items in the result set
		result = dmsOrg.search(search, 1);
		List vsearch = search.getResults();
		vsearch.remove(0);
		SmDmsObject dmsObj = null;
		if (vsearch.size() == 1) {
			dmsObj = (SmDmsObject) vsearch.get(0);
			LOGGER.info("LegacySMCreateForgeRockUser::getUserAttributes() > found object: {}", dmsObj);
			if (config.debug()) {
				SmSdkUtils.printObject(dmsObj, result);
			}
			agentapi.unInit();
			return SmSdkUtils.getUserAttributes(dmsObj, config.migrationAttributesMap(), config.debug());
		}
		return null;
	}

}