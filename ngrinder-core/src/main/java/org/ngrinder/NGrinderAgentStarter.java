/* 
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */
package org.ngrinder;

import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.joran.spi.JoranException;
import com.beust.jcommander.JCommander;
import net.grinder.AgentControllerDaemon;
import net.grinder.util.VersionNumber;
import org.apache.commons.lang.StringUtils;
import org.hyperic.sigar.ProcState;
import org.hyperic.sigar.Sigar;
import org.hyperic.sigar.SigarException;
import org.ngrinder.common.constants.AgentConstants;
import org.ngrinder.common.constants.CommonConstants;
import org.ngrinder.infra.AgentConfig;
import org.ngrinder.infra.ArchLoaderInit;
import org.ngrinder.monitor.agent.MonitorServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

import static net.grinder.util.NetworkUtils.getIP;
import static org.ngrinder.common.constants.InternalConstants.PROP_INTERNAL_NGRINDER_VERSION;
import static org.ngrinder.common.util.NoOp.noOp;

/**
 * Main class to start agent or monitor.
 *
 * @author Mavlarn
 * @author JunHo Yoon
 * @since 3.0
 */
public class NGrinderAgentStarter implements AgentConstants, CommonConstants {

	private static final Logger LOG = LoggerFactory.getLogger(NGrinderAgentStarter.class);

	private AgentConfig agentConfig;

	private AgentControllerDaemon agentController;


	/**
	 * Constructor.
	 */
	public NGrinderAgentStarter() {
	}

	public void init() {
		// Check agent start mode
		this.agentConfig = createAgentConfig();
		try {
			new ArchLoaderInit().init(agentConfig.getHome().getNativeDirectory());
		} catch (Exception e) {
			LOG.error("Error while expanding native lib", e);
		}
		// Configure log.
		configureLogging();
	}

	protected AgentConfig createAgentConfig() {
		AgentConfig agentConfig = new AgentConfig();
		agentConfig.init();
		return agentConfig;
	}

	private void configureLogging() {
		Boolean verboseMode = agentConfig.getCommonProperties().getPropertyBoolean(PROP_COMMON_VERBOSE);
		File logDirectory = agentConfig.getHome().getLogDirectory();
		configureLogging(verboseMode, logDirectory);
	}

	/*
	 * Get the start mode, "agent" or "monitor". If it is not set in configuration, it will return "agent".
	 */
	public String getStartMode() {
		return agentConfig.getCommonProperties().getProperty(PROP_COMMON_START_MODE);
	}

	/**
	 * Get agent version.
	 *
	 * @return version string
	 */
	public String getVersion() {
		return agentConfig.getInternalProperties().getProperty(PROP_INTERNAL_NGRINDER_VERSION);
	}

	/**
	 * Start the performance monitor.
	 */
	public void startMonitor() {
		printLog("***************************************************");
		printLog("* Start nGrinder Monitor... ");
		printLog("***************************************************");
		try {
			MonitorServer.getInstance().init(agentConfig);
			MonitorServer.getInstance().start();
		} catch (Exception e) {
			LOG.error("ERROR: {}", e.getMessage());
			printHelpAndExit("Error while starting Monitor", e);
		}
	}

	/**
	 * Stop monitors.
	 * Only for unit-test.
	 */
	void stopMonitor() {
		MonitorServer.getInstance().stop();
	}

	/**
	 * Start ngrinder agent.
	 */
	public void startAgent() {
		printLog("***************************************************");
		printLog("   Start nGrinder Agent ...");
		printLog("***************************************************");

		if (StringUtils.isEmpty(System.getenv("JAVA_HOME"))) {
			printLog("Hey!! JAVA_HOME env var was not provided. "
					+ "Please provide JAVA_HOME env var before running agent."
					+ "Otherwise you can not execute the agent in the security mode.");
		}


		boolean serverMode = agentConfig.isServerMode();
		if (!serverMode) {
			printLog("JVM server mode is disabled.");
		}

		String controllerIP = getIP(agentConfig.getControllerIP());
		int controllerPort = agentConfig.getControllerPort();
		agentConfig.setControllerIP(controllerIP);
		LOG.info("connecting to controller {}:{}", controllerIP, controllerPort);

		try {
			agentController = new AgentControllerDaemon(agentConfig);
			agentController.run();
		} catch (Exception e) {
			LOG.error("Error while connecting to : {}:{}", controllerIP, controllerPort);
			printHelpAndExit("Error while starting Agent", e);
		}

	}

	private void printLog(String s, Object... args) {
		if (!agentConfig.isSilentMode()) {
			LOG.info(s, args);
		}
	}

	/**
	 * Stop the ngrinder agent.
	 * Only for unit-test.
	 */
	void stopAgent() {
		LOG.info("Stop nGrinder agent!");
		agentController.shutdown();
	}

	private void configureLogging(boolean verbose, File logDirectory) {
		final Context context = (Context) LoggerFactory.getILoggerFactory();
		final JoranConfigurator configurator = new JoranConfigurator();
		configurator.setContext(context);
		context.putProperty("LOG_LEVEL", verbose ? "TRACE" : "INFO");
		context.putProperty("LOG_DIRECTORY", logDirectory.getAbsolutePath());
		try {
			configurator.doConfigure(NGrinderAgentStarter.class.getResource("/logback-agent.xml"));
		} catch (JoranException e) {
			staticPrintHelpAndExit("Can not configure logger on " + logDirectory.getAbsolutePath()
					+ ".\n Please check if it's writable.");
		}
	}

	/**
	 * Print help and exit. This is provided for mocking.
	 *
	 * @param message message
	 */
	protected void printHelpAndExit(String message) {
		staticPrintHelpAndExit(message);
	}

	/**
	 * print help and exit. This is provided for mocking.
	 *
	 * @param message message
	 * @param e       exception
	 */
	protected void printHelpAndExit(String message, Exception e) {
		staticPrintHelpAndExit(message, e);
	}


	public static JCommander commander;

	/**
	 * Agent starter.
	 *
	 * @param args arguments
	 */
	public static void main(String[] args) {
		NGrinderAgentStarter starter = new NGrinderAgentStarter();
		final NGrinderAgentStarterParam param = new NGrinderAgentStarterParam();
		checkJavaVersion();
		commander = new JCommander(param);
		commander.setProgramName("ngrinder-agent");
		try {
			commander.parse(args);
		} catch (Exception e) {
			System.err.println("[Configuration Error]");
			System.err.println(e.getMessage());
			commander.usage();
			return;
		}

		if (param.help) {
			commander.usage();
			return;
		}

		if (param.controllerIP != null) {
			System.setProperty(PROP_AGENT_CONTROLLER_IP, param.controllerIP);
		}

		if (param.controllerPort != null) {
			System.setProperty(PROP_AGENT_CONTROLLER_PORT,
					param.controllerPort.toString());
		}

		if (param.hostId != null) {
			System.setProperty(PROP_AGENT_HOST_ID, param.hostId);
		}

		if (param.region != null) {
			System.setProperty(PROP_AGENT_REGION, param.region);
		}

		if (param.agentHome != null) {
			System.setProperty("ngrinder.agent.home", param.agentHome);
		}

		if (param.overwriteConfig) {
			System.setProperty("ngrinder.overwrite.config", "true");
		}

		System.getProperties().putAll(param.params);
		starter.init();


		System.out.println("nGrinder v" + starter.getVersion());
		String startMode = (param.mode == null) ? starter.getStartMode() : param.mode;
		if ("stop".equalsIgnoreCase(param.command)) {
			starter.stopProcess(startMode);
			System.out.println("Stop the " + startMode);
			return;
		}
		starter.checkDuplicatedRun(startMode);
		if (startMode.equalsIgnoreCase("agent")) {
			starter.startAgent();
		} else if (startMode.equalsIgnoreCase("monitor")) {
			starter.startMonitor();
		} else {
			staticPrintHelpAndExit("Invalid agent.conf, '-mode' must be set as 'monitor' or 'agent'.");
		}
	}

	static void checkJavaVersion() {
		String curJavaVersion = System.getProperty("java.version", "1.6");
		checkJavaVersion(curJavaVersion);
	}

	static void checkJavaVersion(String curJavaVersion) {
		if (new VersionNumber(curJavaVersion).compareTo(new VersionNumber("1.6")) < 0) {
			LOG.info("- Current java version {} is less than 1.6. nGrinder Agent might not work well", curJavaVersion);
		}
	}

	/**
	 * Stop process.
	 *
	 * @param mode agent or monitor.
	 */
	protected void stopProcess(String mode) {
		String pid = agentConfig.getAgentPidProperties(mode);
		try {
			if (StringUtils.isNotBlank(pid)) {
				new Sigar().kill(pid, 15);
			}
			agentConfig.updateAgentPidProperties(mode);
		} catch (SigarException e) {
			printHelpAndExit(String.format("Error occurred while terminating %s process.\n"
					+ "It can be already stopped or you may not have the permission.\n"
					+ "If everything is OK. Please stop it manually.", mode), e);
		}
	}

	/**
	 * Check if the process is already running in this env.
	 *
	 * @param startMode monitor or agent
	 */
	public void checkDuplicatedRun(String startMode) {
		Sigar sigar = new Sigar();
		String existingPid = this.agentConfig.getAgentPidProperties(startMode);
		if (StringUtils.isNotEmpty(existingPid)) {
			try {
				ProcState procState = sigar.getProcState(existingPid);
				if (procState.getState() == ProcState.RUN || procState.getState() == ProcState.IDLE
						|| procState.getState() == ProcState.SLEEP) {
					printHelpAndExit("Currently " + startMode + " is running with pid " + existingPid
							+ ". Please stop it before run");
				}
				agentConfig.updateAgentPidProperties(startMode);
			} catch (SigarException e) {
				noOp();
			}
		}
		this.agentConfig.saveAgentPidProperties(String.valueOf(sigar.getPid()), startMode);
	}

	private static void staticPrintHelpAndExit(String message) {
		staticPrintHelpAndExit(message, null);
	}

	private static void staticPrintHelpAndExit(String message, Exception e) {
		if (e == null) {
			LOG.error(message);
		} else {
			LOG.error(message, e);
		}
		if (commander != null) {
			commander.usage();
		}
		System.exit(-1);
	}
}
