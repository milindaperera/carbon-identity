/*
*  Copyright (c) WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.wso2.carbon.identity.entitlement.internal;

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TSSLTransportFactory;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TTransportException;
import org.osgi.service.component.ComponentContext;
import org.wso2.carbon.base.ServerConfigurationException;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.entitlement.EntitlementUtil;
import org.wso2.carbon.identity.entitlement.PDPConstants;
import org.wso2.carbon.identity.entitlement.dto.PolicyDTO;
import org.wso2.carbon.identity.entitlement.listner.CacheClearingUserOperationListener;
import org.wso2.carbon.identity.entitlement.listner.UserOperationsNotificationListener;
import org.wso2.carbon.identity.entitlement.pap.store.PAPPolicyStore;
import org.wso2.carbon.identity.entitlement.thrift.EntitlementService;
import org.wso2.carbon.identity.entitlement.thrift.ThriftConfigConstants;
import org.wso2.carbon.identity.entitlement.thrift.ThriftEntitlementServiceImpl;
import org.wso2.carbon.identity.thrift.authentication.ThriftAuthenticatorService;
import org.wso2.carbon.registry.core.Registry;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.registry.core.service.RegistryService;
import org.wso2.carbon.user.core.listener.UserOperationEventListener;
import org.wso2.carbon.user.core.service.RealmService;
import org.wso2.carbon.utils.CarbonUtils;
import org.wso2.carbon.utils.NetworkUtils;

import java.io.File;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @scr.component name="identity.entitlement.component" immediate="true"
 * @scr.reference name="registry.service"
 * interface="org.wso2.carbon.registry.core.service.RegistryService"
 * cardinality="1..1" policy="dynamic" bind="setRegistryService"
 * unbind="unsetRegistryService"
 * @scr.reference name="user.realmservice.default"
 * interface="org.wso2.carbon.user.core.service.RealmService" cardinality="1..1"
 * policy="dynamic" bind="setRealmService" unbind="unsetRealmService"
 * @scr.reference name="org.wso2.carbon.identity.thrift.authentication.internal.ThriftAuthenticationServiceComponent"
 * interface="org.wso2.carbon.identity.thrift.authentication.ThriftAuthenticatorService"
 * cardinality="1..1" policy="dynamic" bind="setThriftAuthenticationService"  unbind="unsetThriftAuthenticationService"
 */
public class EntitlementServiceComponent {

	private static final Log log = LogFactory.getLog(EntitlementServiceComponent.class);
	private static RegistryService registryService = null;
	private static EntitlementConfigHolder entitlementConfig = null;
	private static RealmService realmservice;
	private ThriftAuthenticatorService thriftAuthenticationService;
	private ExecutorService executor = Executors.newFixedThreadPool(2);

	/**
	 *
	 */
	public EntitlementServiceComponent() {
	}

	/**
	 * @param ctxt
	 */
	protected void activate(ComponentContext ctxt) {

		if (log.isDebugEnabled()) {
			log.info("Identity Entitlement bundle is activated");
		}

		try {
			// build configuration file
			entitlementConfig = new EntitlementConfigHolder();
			EntitlementExtensionBuilder builder = new EntitlementExtensionBuilder();
			builder.setBundleContext(ctxt.getBundleContext());
			builder.buildEntitlementConfig(entitlementConfig);

			// Start loading schema.
			new Thread(new SchemaBuilder(entitlementConfig)).start();

			// Read XACML policy files from a pre-defined location in the
			// filesystem and load to registry at the server startup
			PAPPolicyStore papPolicyStore = new PAPPolicyStore(
			  registryService.getGovernanceSystemRegistry());

			String startUpPolicyAdding = entitlementConfig.getEngineProperties().getProperty(
			  PDPConstants.START_UP_POLICY_ADDING);

			if (startUpPolicyAdding != null && Boolean.parseBoolean(startUpPolicyAdding)) {
				if (papPolicyStore.getAllPolicyIds() == null
				    || papPolicyStore.getAllPolicyIds().length == 0) {
					File policyFolder = null;
					String policyPathFromConfig = entitlementConfig.getEngineProperties().getProperty(
					  PDPConstants.FILESYSTEM_POLICY_PATH);

					if (policyPathFromConfig != null && policyPathFromConfig.trim().length() > 0) {
						policyFolder = new File(policyPathFromConfig);
					}

					if (policyFolder != null && !policyFolder.exists()) {
						log.warn("Defined policy directory location is not exit. " +
						         "Therefore using default policy location");
					}

					if (policyPathFromConfig == null || !policyFolder.exists()) {
						policyFolder = new File(CarbonUtils.getCarbonHome() + File.separator
						                        + "repository" + File.separator + "resources" + File.separator
						                        + "security" + File.separator + "policies" + File.separator + "xacml");

					}

					boolean customPolicies = false;

					if (policyFolder != null && policyFolder.exists()) {
						for (File policyFile : policyFolder.listFiles()) {
							if (policyFile.isFile()) {
								PolicyDTO policyDTO = new PolicyDTO();
								policyDTO.setPolicy(FileUtils.readFileToString(policyFile));
								try {
									EntitlementUtil.addFilesystemPolicy(policyDTO,
									  registryService.getGovernanceSystemRegistry(), true);
								} catch (Exception e) {
									// log and ignore
									log.error("Error while adding XACML policies", e);
								}
								customPolicies = true;

							}
						}
					}

					if (!customPolicies) {
						// load default policies
						EntitlementUtil.addSamplePolicies(registryService.getGovernanceSystemRegistry());
					}
				}
			}

			CacheClearingUserOperationListener pipUserOperationListener =
                    new CacheClearingUserOperationListener();
			ctxt.getBundleContext().registerService(
                    UserOperationEventListener.class.getName(), pipUserOperationListener, null);

			UserNotificationConfig config = new UserNotificationConfig();
			if(config.isNotificationEnabled()){
				log.info("Registering notification sender on user operations");
				UserOperationsNotificationListener notificationListener =
                        new UserOperationsNotificationListener(config);
				ctxt.getBundleContext().registerService(
                        UserOperationEventListener.class.getName(),notificationListener, null);
			}


			//TODO: Read from identity.xml, the configurations to be used in thrift based entitlement service.
			//initialize thrift authenticator
			ThriftEntitlementServiceImpl.init(thriftAuthenticationService);
			//initialize thrift based Entitlement Service.
			startThriftServices();
			//TODO catch a throwable
		} catch (Exception e) {
			log.error("Failed to initialize Entitlement Service", e);
		}
	}

	/**
	 * @param ctxt
	 */
	protected void deactivate(ComponentContext ctxt) {
		if (log.isDebugEnabled()) {
			log.debug("Identity Entitlement bundle is deactivated");
		}
	}

	/**
	 * sets registry service
	 *
	 * @param registryService <code>RegistryService</code>
	 */
	protected void setRegistryService(RegistryService registryService) {
		if (log.isDebugEnabled()) {
			log.debug("RegistryService set in Entitlement bundle");
		}
		EntitlementServiceComponent.registryService = registryService;
	}

	/**
	 * un-sets registry service
	 *
	 * @param registryService <code>RegistryService</code>
	 */
	protected void unsetRegistryService(RegistryService registryService) {
		if (log.isDebugEnabled()) {
			log.debug("RegistryService unset in Entitlement bundle");
		}
		EntitlementServiceComponent.registryService = null;
	}

	/**
	 * sets realm service
	 *
	 * @param realmService <code>RealmService</code>
	 */
	protected void setRealmService(RealmService realmService) {
		if (log.isDebugEnabled()) {
			log.debug("DefaultUserRealm set in Entitlement bundle");
		}
		EntitlementServiceComponent.realmservice = realmService;
	}

	/**
	 * un-sets realm service
	 *
	 * @param realmService <code>RealmService</code>
	 */
	protected void unsetRealmService(RealmService realmService) {
		if (log.isDebugEnabled()) {
			log.debug("DefaultUserRealm unset in Entitlement bundle");
		}
		EntitlementServiceComponent.realmservice = null;
	}

	/**
	 * @param httpService
	 */
	/*protected void setHttpService(HttpService httpService) {
        httpServiceInstance = httpService;
    }

    *//**
	 * @param httpService
	 *//*
    protected void unsetHttpService(HttpService httpService) {
        httpServiceInstance = null;
    }*/

	/**
	 * set Thrift authentication service
	 *
	 * @param authenticationService <code>ThriftAuthenticatorService</code>
	 */
	protected void setThriftAuthenticationService(ThriftAuthenticatorService authenticationService) {
		if (log.isDebugEnabled()) {
			log.debug("ThriftAuthenticatorService set in Entitlement bundle");
		}
		this.thriftAuthenticationService = authenticationService;

	}

	/**
	 * un-set Thrift authentication service
	 *
	 * @param authenticationService <code>ThriftAuthenticatorService</code>
	 */
	protected void unsetThriftAuthenticationService(
	  ThriftAuthenticatorService authenticationService) {
		if (log.isDebugEnabled()) {
			log.debug("ThriftAuthenticatorService unset in Entitlement bundle");
		}
		this.thriftAuthenticationService = null;
	}

	/**
	 * @return
	 */
	public static EntitlementConfigHolder getEntitlementConfig() {
		return entitlementConfig;
	}

	/**
	 * @return
	 */
	public static RealmService getRealmservice() {
		return realmservice;
	}

	/**
	 * @param realmservice
	 */
	public static void setRealmservice(RealmService realmservice) {
		EntitlementServiceComponent.realmservice = realmservice;
	}

	/**
	 * Return registry service
	 *
	 * @return RegistryService
	 */
	public static RegistryService getRegistryService() {
		return registryService;
	}

	public static Registry getGovernanceRegistry(int tenantId) {
		try {
			return registryService.getGovernanceSystemRegistry(tenantId);
		} catch (RegistryException e) {
			// ignore
		}
		return null;
	}

	private void startThriftServices() throws Exception {
		startThriftEntitlementService();
	}

	private void startThriftEntitlementService() throws Exception {
		try {
			//read identity.xml
			IdentityUtil.populateProperties();
			//if thrift based EntitlementService is enabled.
			String thriftEnabled = IdentityUtil.getProperty(ThriftConfigConstants.PARAM_ENABLE_THRIFT_SERVICE);

			if (thriftEnabled != null && Boolean.parseBoolean(thriftEnabled)) {

				TSSLTransportFactory.TSSLTransportParameters transportParam =
				  new TSSLTransportFactory.TSSLTransportParameters();

				//read the keystore and password used for ssl communication from config
				String keystorePath = IdentityUtil.getProperty(
				  ThriftConfigConstants.PARAM_KEYSTORE_LOCATION);
				String keystorePassword = IdentityUtil.getProperty(
				  ThriftConfigConstants.PARAM_KEYSTORE_PASSWORD);

				//set it in parameters
				transportParam.setKeyStore(keystorePath, keystorePassword);
				//int receivePort = 10395;
				int receivePort = readThriftReceivePort();
				//int clientTimeOut = 10000;
				int clientTimeOut = Integer.parseInt(IdentityUtil.getProperty(
				  ThriftConfigConstants.PARAM_CLIENT_TIMEOUT));
				//String ifAddress = "localhost";
				TServerSocket serverTransport =
				  TSSLTransportFactory.getServerSocket(receivePort,
					clientTimeOut,
					getHostAddress(readThriftHostName()),
					transportParam);

				EntitlementService.Processor processor = new EntitlementService.Processor(
				  new ThriftEntitlementServiceImpl());

				//TODO: have to decide on the protocol.
				TServer server = new TThreadPoolServer(new TThreadPoolServer.Args(serverTransport).
				  processor(processor));
				//TServer server = new TThreadPoolServer(new TThreadPoolServer.Args())
/*
                TServer server = new TThreadPoolServer(processor, serverTransport,
                                                   new TCompactProtocol.Factory());*/
				Runnable serverThread = new ServerRunnable(server);
				executor.submit(serverThread);

				log.info("Started thrift entitlement service at port:" + receivePort);
			}


		} catch (TTransportException e) {
			String transportErrorMsg = "Error in initializing thrift transport";
			log.error(transportErrorMsg, e);
			throw new Exception(transportErrorMsg);
		} catch (UnknownHostException e) {
			String hostErrorMsg = "Error in obtaining host name";
			log.error(hostErrorMsg, e);
			throw new Exception(hostErrorMsg);
		} catch (ServerConfigurationException e) {
			String configError = "Error in reading configuration.";
			log.error(configError, e);
			throw new Exception(configError);

		}


	}

	/**
	 * Read the port from identity.xml which is overridden by carbon.xml to facilitating
	 * multiple servers at a time.
	 */
	private int readThriftReceivePort() {
		int port = -1;
		String portValue = IdentityUtil.getProperty(ThriftConfigConstants.PARAM_RECEIVE_PORT);
		//if the port contains a template string that refers to carbon.xml
		if ((portValue.contains("${")) && (portValue.contains("}"))) {
			port = (CarbonUtils.getPortFromServerConfig(portValue));
		} else { //if port directly mentioned in identity.xml
			port = Integer.parseInt(portValue);
		}
		return port;
	}

	/**
	 * Thread that starts thrift server
	 */
	private static class ServerRunnable implements Runnable {
		TServer server;

		public ServerRunnable(TServer server) {
			this.server = server;
		}

		public void run() {
			server.serve();
		}
	}

	/**
	 * Get INetAddress by host name or  IP Address
	 *
	 * @param host name or host IP String
	 * @return InetAddress
	 * @throws UnknownHostException
	 */
	private InetAddress getHostAddress(String host) throws UnknownHostException {

		String[] splittedString = host.split("\\.");

		if (splittedString.length == 4) {
			// check whether this is ip address or not.
			try {
				Integer.parseInt(splittedString[0]);
				Integer.parseInt(splittedString[1]);
				Integer.parseInt(splittedString[2]);
				Integer.parseInt(splittedString[3]);
				byte[] byteAddress = new byte[4];
				for (int i = 0; i < splittedString.length; i++) {
					if (Integer.parseInt(splittedString[i]) > 127) {
						byteAddress[i] = new Integer(Integer.parseInt(splittedString[i]) - 256).byteValue();
					} else {
						byteAddress[i] = Byte.parseByte(splittedString[i]);
					}
				}
				return InetAddress.getByAddress(byteAddress);
			} catch (Exception e) {
				log.debug(e);
				// ignore.
			}
		}
		// if not ip address return host name
		return InetAddress.getByName(host);
	}

	/**
	 * Read the thrift hostname from identity.xml which overrides the hostName from carbon.xml on facilitating
	 * identifying the host for thrift server .
	 */
	 private String readThriftHostName() throws SocketException {

		 String thriftHostName = IdentityUtil.getProperty(ThriftConfigConstants.PARAM_HOST_NAME);

	     //if the thrift host name doesn't exist in config, load from carbon.xml
		 if(thriftHostName != null){
			return thriftHostName;
		}else{
			return NetworkUtils.getLocalHostname();
		}
	 }
}
